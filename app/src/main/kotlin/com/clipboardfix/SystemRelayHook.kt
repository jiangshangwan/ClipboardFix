package com.clipboardfix

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.provider.Settings

/**
 * system_server 侧的「已删除屏蔽表」中枢（v4.28）。
 *
 * ============ 背景（diag5–diag8 真机逐轮证伪）============
 *  - 被 hook 的第三方输入法进程拿不到 XposedService binder（LSPosed 只发给模块自身包），
 *    remote prefs 只能读、不能写；
 *  - 「输入法 → 模块 App」唤醒被 MIUI 双重拦截：自启动管理（WakePathChecker Reject）
 *    + Greezer 冻结（BroadcastQueue Denial，App 进程 cached）；
 *  - v4.27「system_server 注册 runtime receiver 收广播」也断链：输入法侧 sendBroadcast
 *    正常发出（blocklist update sent），system_server 的 receiver 却从未收到（diag8 实证）。
 *
 * ============ 方案（不依赖广播投递、不唤醒任何 App）============
 *  - 上行（输入法 → system_server）：输入法仍发 [ACTION_UPDATE] 广播，但 system_server
 *    直接 hook {@code ActivityManagerService.broadcastIntent*}——一切 sendBroadcast 的必经
 *    AMS 入口，在入口处拦截 Intent（不靠 receiver 投递，MIUI 的投递拦截绕不到这里）；
 *  - 下行（system_server → 输入法）：屏蔽表写进 [Settings.Global]（uid system 有
 *    WRITE_SECURE_SETTINGS，settings 落库、重启不丢），输入法面板打开时直接
 *    {@code Settings.Global.getString} 读取（任何 app 可读，无投递环节）。
 * 全程 system_server 常驻、不发推送广播、不唤醒任何 App。
 *
 * <p>settings 单值上限 32768 字符（SettingsState.MAX_LENGTH_PER_STRING），屏蔽表
 * 上限 200 条（JSON ≈ 14KB）远在限内；Global 表对 system 包无内存配额限制。
 */
object SystemRelayHook {

    /** 输入法 → system_server：上报一份 key→time 屏蔽表（合并）。经 AMS hook 拦截。 */
    const val ACTION_UPDATE = "com.clipboardfix.BLOCKLIST_UPDATE"

    const val EXTRA_JSON = "json"

    /** 屏蔽表落点（Settings.Global：system_server 写、输入法读）。 */
    const val KEY_BLOCKLIST = "clipboardfix_blocklist"

    /** 中枢就绪标记（"1" = AMS hook 已装上并可写 settings；输入法侧诊断用）。 */
    const val KEY_HUB_STATUS = "clipboardfix_hub_status"

    private const val AMS_CLASS = "com.android.server.am.ActivityManagerService"

    /** system_server 内的权威屏蔽表（key→time，「一次复制一代」语义）。 */
    private val sWipedKeys = java.util.LinkedHashMap<String, Long>()

    @Volatile
    private var sHooked = false

    /** 就绪标记是否已落 settings（开机早期写可能失败，首次 persist 时补写）。 */
    @Volatile
    private var sStatusWritten = false

    /**
     * 在 system_server 中给 AMS 的 {@code broadcastIntent*} 挂拦截
     * （由 [XposedInit.onSystemServerStarting] 调用）。
     */
    fun init(cl: ClassLoader) {
        if (sHooked) return
        var hooked = 0
        try {
            var amCls = Reflect.findClassIfExists(AMS_CLASS, cl)
            if (amCls == null) {
                amCls = try {
                    Class.forName(AMS_CLASS)
                } catch (_: Throwable) {
                    null
                }
            }
            if (amCls == null) {
                XposedInit.log("system hub: $AMS_CLASS not found")
                return
            }
            for (m in amCls.declaredMethods) {
                // broadcastIntent / broadcastIntentWithFeature / 厂商变体全部覆盖
                if (!m.name.startsWith("broadcastIntent")) continue
                val idx = m.parameterTypes.indexOfFirst { it == Intent::class.java }
                if (idx < 0) continue
                try {
                    XposedInit.hook(m) { chain ->
                        try {
                            val intent = chain.getArg(idx) as? Intent
                            if (intent != null && ACTION_UPDATE == intent.action) {
                                val json = intent.getStringExtra(EXTRA_JSON)
                                if (json != null) {
                                    merge(json)
                                    persist()
                                    XposedInit.log("system hub update (mem " + sWipedKeys.size + ")")
                                }
                            }
                        } catch (t: Throwable) {
                            XposedInit.log("system hub intercept error - " + t)
                        }
                        chain.proceed()
                    }
                    hooked++
                } catch (t: Throwable) {
                    XposedInit.log("system hub hook one failed - " + m.name + " - " + t)
                }
            }
        } catch (t: Throwable) {
            XposedInit.log("system hub hook failed - " + t)
        }
        sHooked = hooked > 0
        XposedInit.log("system hub hooked " + hooked + " methods")
        markReady()
    }

    /** 合并一份 key→time JSON 进内存表（同 key 取 time 更大的一代）。 */
    private fun merge(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val t = obj.optLong(k, 0L)
                if (t > 0L) {
                    synchronized(sWipedKeys) {
                        val old = sWipedKeys[k]
                        if (old == null || t > old) sWipedKeys[k] = t
                    }
                }
            }
        } catch (t: Throwable) {
            XposedInit.log("system hub merge failed - $t")
        }
    }

    /**
     * 把内存表写进 [Settings.Global]（输入法侧读取）。
     *
     * <p>hook 跑在 AMS 的 binder 线程上，此刻 Binder.getCallingUid() 是发广播的输入法
     * （无 WRITE_SECURE_SETTINGS）——必须 clearCallingIdentity 让写入以 system uid 落地，
     * 否则 SettingsProvider 按调用方 uid 判权限会拒写。
     */
    private fun persist() {
        try {
            val ctx = systemContext() ?: return
            val obj = org.json.JSONObject()
            synchronized(sWipedKeys) {
                for ((k, t) in sWipedKeys) {
                    obj.put(k, t)
                }
            }
            val token = Binder.clearCallingIdentity()
            try {
                Settings.Global.putString(ctx.contentResolver, KEY_BLOCKLIST, obj.toString())
                if (!sStatusWritten) {
                    Settings.Global.putString(ctx.contentResolver, KEY_HUB_STATUS, "1")
                    sStatusWritten = true
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (t: Throwable) {
            XposedInit.log("system hub persist failed - $t")
        }
    }

    /** 写就绪标记（诊断用：输入法侧读到 "1" 即中枢链路可用）。 */
    private fun markReady() {
        try {
            val ctx = systemContext() ?: return
            val token = Binder.clearCallingIdentity()
            try {
                Settings.Global.putString(ctx.contentResolver, KEY_HUB_STATUS, "1")
                sStatusWritten = true
                XposedInit.log("system hub ready")
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (t: Throwable) {
            XposedInit.log("system hub ready failed - $t")
        }
    }

    /** 取 system_server 的 system context（contentResolver 用）。 */
    private fun systemContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val ct = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getSystemContext").invoke(ct) as? Context
    } catch (t: Throwable) {
        XposedInit.log("system hub context failed - $t")
        null
    }
}
