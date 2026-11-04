/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import io.github.libxposed.api.XposedModuleInterface

/**
 * 系统设置内模块入口注入。
 *
 * 在系统设置的 header 列表构建完成后向列表插入模块入口项，
 * 位置由偏好控制（顶部 / 中部 / 底部），偏好为 0 时不注入。
 *
 * 锚点定位采用资源 id 匹配相邻 header：顶部插在「我的设备」后、
 * 中部插在「桌面」后、底部插在「特色功能」区域后；找不到锚点时按模式回退到
 * 列表头部 / 中部 / 末尾。
 */
object SettingsEntryHook {

    // cf-se-0xA7
    private const val TAG = "SettingsEntry"

    /** 固定 header 标识（重复调用时去重用）。 */
    private const val HEADER_ID = 0x00CF2611L

    private const val PKG_SETTINGS = "com.android.settings"
    private const val PKG_SELF = "com.clipboardfix"

    /**
     * 已注入模块资源的宿主 AssetManager 集合（弱引用，避免同进程内重复注入）。
     * 在 AssetManager 层面注入，所有 Resources 实例（含深色主题化的包装）共享同一
     * AssetManager，故夜间模式切换重建 Resources 后仍可解析模块私有资源 id。
     */
    private val injectedAssetManagers = java.util.WeakHashMap<android.content.res.AssetManager, Boolean>()

    fun init(param: XposedModuleInterface.PackageLoadedParam) {
        val cl = try {
            param.getDefaultClassLoader()
        } catch (t: Throwable) {
            XposedInit.log("$TAG: no classloader - $t")
            return
        }

        val clazz = Reflect.findClassIfExists("com.android.settings.MiuiSettings", cl)
        if (clazz == null) {
            XposedInit.log("$TAG: MiuiSettings not found")
            return
        }

        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == "updateHeaderList" &&
                m.parameterTypes.size == 1 &&
                java.util.List::class.java.isAssignableFrom(m.parameterTypes[0])
        }
        if (method == null) {
            XposedInit.log("$TAG: updateHeaderList not found")
            return
        }

        XposedInit.log("$TAG: hooked updateHeaderList")
        XposedInit.hook(method) { chain ->
            val result = chain.proceed()
            try {
                @Suppress("UNCHECKED_CAST")
                val headers = chain.getArgs()[0] as? MutableList<Any?>
                if (headers != null) {
                    val mode = ModulePrefs.settingsIconMode()
                    if (mode == 0) {
                        // 已禁用：清掉可能残留的旧条目
                        headers.removeAll { h -> h != null && readLong(h, "id") == HEADER_ID }
                    } else {
                        val ctx = chain.thisObject as? Context
                            ?: currentApplication()
                        insertEntry(headers, mode, cl, ctx)
                    }
                }
            } catch (t: Throwable) {
                XposedInit.log("$TAG: error - $t")
            }
            result
        }
    }

    // ---------------- 条目插入 ----------------

    private fun insertEntry(headers: MutableList<Any?>, mode: Int, cl: ClassLoader, ctx: Context?) {
        if (headers.isEmpty()) return

        // 去重：同 id 只保留一条
        headers.removeAll { h -> h != null && readLong(h, "id") == HEADER_ID }

        val headerClass =
            Reflect.findClassIfExists("com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header", cl)
                ?: Reflect.findClassIfExists("android.preference.PreferenceActivity\$Header", cl)
                ?: return

        val header = try {
            headerClass.getDeclaredConstructor().newInstance()
        } catch (t: Throwable) {
            XposedInit.log("$TAG: ctor failed - $t")
            return
        }

        writeField(header, "id", HEADER_ID)
        writeField(header, "title", "HyperClipFix" as CharSequence)
        writeField(header, "intent", buildLaunchIntent())
        writeField(header, "extras", buildExtras())

        // 图标：注入模块资源到宿主 Resources 后写模块 R 常量到 iconRes。
        // MIUI Header 用 iconRes(int) 渲染，不认 Drawable 字段。
        // 关键防护：仅当该资源 id 在宿主 Resources 中可解析时才写 iconRes。
        // 若注入尚未生效（如系统设置因深浅色切换重建、Resources 未就绪），
        // 跳过图标而非写入无法解析的 id，避免宿主取图标抛 Resources.NotFoundException 闪退。
        if (ctx != null) injectModuleRes(ctx)
        val iconRes = R.drawable.ic_settings_entry
        val iconOk = ctx != null && runCatching { ctx.resources.getResourceTypeName(iconRes); true }
            .getOrDefault(false)
        if (iconOk) {
            writeField(header, "iconRes", iconRes)
        } else {
            XposedInit.log("$TAG: icon res not resolvable yet, skip iconRes")
        }

        val idx = findInsertIndex(headers, mode, cl)
        // groupId 继承相邻条目，保持视觉分组一致
        val neighbor = headers.getOrNull(if (idx > 0) idx - 1 else 0)
        if (neighbor != null) {
            val gid = readInt(neighbor, "groupId")
            if (gid != null) writeField(header, "groupId", gid)
        }

        headers.add(idx.coerceIn(0, headers.size), header)
        XposedInit.log("$TAG: inserted at $idx (mode=$mode)")
    }

    /**
     * 计算插入位置：按模式找锚点 header 的资源 id，
     * 命中则插到锚点之后；未命中走 [fallbackIndex]。
     */
    private fun findInsertIndex(headers: List<Any?>, mode: Int, cl: ClassLoader): Int {
        val rIdClass = Reflect.findClassIfExists("com.android.settings.R\$id", cl)
        if (rIdClass != null) {
            val anchorNames: List<String> = when (mode) {
                1 -> listOf("my_device")
                2 -> listOf("launcher_settings")
                3 -> listOf("other_special_feature_settings", "app_timer")
                else -> emptyList()
            }
            for (name in anchorNames) {
                val anchorId = try {
                    val f = rIdClass.getDeclaredField(name)
                    f.isAccessible = true
                    f.getInt(null).toLong()
                } catch (_: Throwable) { continue }

                val idx = headers.indexOfFirst { h -> h != null && readLong(h, "id") == anchorId }
                if (idx >= 0) {
                    XposedInit.log("$TAG: anchor '$name' @ $idx")
                    return idx + 1
                }
            }
        }
        return fallbackIndex(headers, mode)
    }

    /** 锚点未命中时的回退位置。 */
    private fun fallbackIndex(headers: List<Any?>, mode: Int): Int {
        return when (mode) {
            1 -> 0
            2 -> headers.size / 2
            3 -> headers.size
            else -> headers.size.coerceAtMost(25)
        }
    }

    // ---------------- Intent / Extras ----------------

    private fun buildLaunchIntent(): Intent {
        return Intent().apply {
            setClassName(PKG_SELF, "$PKG_SELF.AboutActivity")
            putExtra("isDisplayHomeAsUpEnabled", true)
        }
    }

    private fun buildExtras(): Bundle {
        return Bundle().apply {
            val users = ArrayList<UserHandle>()
            try {
                users.add(android.os.Process.myUserHandle())
            } catch (_: Throwable) {
            }
            putParcelableArrayList("header_user", users)
        }
    }

    // ---------------- 图标资源注入 ----------------

    /**
     * 把模块 APK 资源注入宿主 Resources，使模块 R 类常量可在宿主内解析。
     * API 30+ 走 ResourcesLoader（公开 API），低版本回退 AssetManager.addAssetPath。
     * 幂等：重复调用无副作用。
     */
    private fun injectModuleRes(ctx: Context) {
        // 在 AssetManager 层面注入：所有 Resources（含夜间主题化的包装）共享同一 AssetManager，
        // 故深色模式切换重建 Resources 后模块私有资源 id 仍可解析，避免取图标闪退。
        val app = currentApplication() ?: ctx.applicationContext ?: ctx
        val am = app.assets ?: return
        if (injectedAssetManagers[am] == true) return
        val info = try {
            app.packageManager.getApplicationInfo(PKG_SELF, 0)
        } catch (_: Throwable) { return }

        // ① 共享库方式（addAssetPathAsSharedLibrary）保留模块原始 package id（0x7f…），
        //    使 R.drawable.ic_settings_entry 在宿主内按原 id 解析；且作用于 AssetManager，
        //    对主题化包装 Resources 同样生效。
        for (name in listOf("addAssetPathAsSharedLibrary", "addAssetPath")) {
            try {
                val m = am.javaClass.getDeclaredMethod(name, String::class.java)
                m.isAccessible = true
                val cookie = m.invoke(am, info.sourceDir) as? Int ?: 0
                if (cookie != 0) {
                    injectedAssetManagers[am] = true
                    XposedInit.log("$TAG: $name cookie=$cookie")
                    return
                }
            } catch (_: Throwable) {
                XposedInit.log("$TAG: $name failed")
            }
        }

        // ② 兜底：ResourcesLoader（原 EzResources 路径）。不保证在主题化包装上生效，
        //    仅作为最后手段；下游仍有「可解析性校验」兜底，失败时跳过图标不崩。
        try {
            val res = ctx.resources ?: return
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val loader = android.content.res.loader.ResourcesLoader()
                val fd = android.os.ParcelFileDescriptor.open(
                    java.io.File(info.sourceDir),
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
                val provider = android.content.res.loader.ResourcesProvider.loadFromApk(fd)
                loader.addProvider(provider)
                res.addLoaders(loader)
                XposedInit.log("$TAG: ResourcesLoader attached (fallback)")
            }
        } catch (t: Throwable) {
            XposedInit.log("$TAG: ResourcesLoader fallback failed - $t")
        }
    }

    private fun currentApplication(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            at.getDeclaredMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) { null }
    }

    // ---------------- 安全字段读写 ----------------

    /** 写字段，返回是否成功（字段存在且写入完成）。 */
    private fun writeField(obj: Any, name: String, value: Any?): Boolean {
        return try { Reflect.setField(obj, name, value); true } catch (_: Throwable) { false }
    }

    private fun readLong(obj: Any, name: String): Long? {
        return try {
            when (val v = Reflect.getField(obj, name)) {
                is Long -> v
                is Int -> v.toLong()
                is Number -> v.toLong()
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    private fun readInt(obj: Any, name: String): Int? {
        return try {
            when (val v = Reflect.getField(obj, name)) {
                is Int -> v
                is Number -> v.toInt()
                else -> null
            }
        } catch (_: Throwable) { null }
    }
}
