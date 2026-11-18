/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.Context
import android.net.Uri
import java.lang.reflect.Executable

/**
 * 跨设备剪贴板文件提供方的调用方门禁放行。cf/sc-2611
 *
 * <p>互联服务内的 UniversalClipboardProvider 在 openTypedAssetFile / query 时，用
 * AppHelper.isAvailableApplication(ctx, callingPkg) 判定调用方是否可用：命中内置白名单
 * （微信 / QQ / 邮箱）或其 manifest 带 is_support_miui_super_clipboard meta-data 才放行。
 * 第三方输入法两个条件都不满足，点击云端图片时打开 URI 抛 FileNotFoundException，无法上屏。
 *
 * <p>放行必须区分调用意图：输入法在后台预取缩略图时也调 openTypedAssetFile，但 URI 仅带
 * itemId（无 fileName/dataSize）；一刀切放行会让这次探测触发 VirtualPasteEvent 文件同步，
 * 在数据到达阶段就弹出进度框。真正的粘贴请求 URI 携带完整文件参数。
 *
 * <p>因此 hook openTypedAssetFile 按 URI 参数置位线程标志，再由 isAvailableApplication 读取：
 * 仅「完整 URI + 作用域内输入法」放行，缩略图探测与其它调用方一律走原判定。
 */
object SuperClipboardGateHook {

    /** 与模块作用域声明一致、需要放行的输入法调用方。 */
    private val ALLOWED_IME = setOf(
        "com.tencent.wetype",
        "com.tencent.qqpinyin",
        "com.bytedance.android.doubaoime",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.google.android.inputmethod.latin"
    )

    /** 同一次 provider 调用链内，「完整文件 URI」标志，随 binder 线程传递。 */
    private val fullUri = ThreadLocal<Boolean>()

    /** openTypedAssetFile 链内「仅 itemId 的缩略图探测」标志。sg-tp/2611 */
    private val thumbProbe = ThreadLocal<Boolean>()

    @Volatile
    private var installed = false

    @JvmStatic
    fun init(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val provider = Class.forName(
                "com.xiaomi.dist.universalclipboardservice.UniversalClipboardProvider",
                true, classLoader
            )
            var hooked = 0
            for (m in provider.declaredMethods) {
                if (m.name != "openTypedAssetFile") continue
                val pt = m.parameterTypes
                if (pt.isEmpty() || !Uri::class.java.isAssignableFrom(pt[0])) continue
                m.isAccessible = true
                hookOpenTyped(m)
                hooked++
            }
            XposedInit.log("[SuperClip] OK: hooked openTypedAssetFile x" + hooked)
        } catch (t: Throwable) {
            XposedInit.log("[SuperClip] openTyped hook failed - " + t)
        }
        try {
            val helper = Class.forName(
                "com.xiaomi.dist.universalclipboardservice.identity.AppHelper",
                true, classLoader
            )
            val m = helper.getDeclaredMethod(
                "isAvailableApplication", Context::class.java, String::class.java
            )
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                val pkg = chain.getArg(1) as? String
                // 系统自身拿缩略图探测 URI 预取会触发同步弹框，按探测意图回绝。sg-mg/2611
                if (java.lang.Boolean.TRUE == thumbProbe.get() && "com.milink.service" == pkg) {
                    XposedInit.log("[SuperClip] self probe declined")
                    return@hook false
                }
                if (pkg != null && pkg in ALLOWED_IME && java.lang.Boolean.TRUE == fullUri.get()) {
                    XposedInit.log("[SuperClip] gate pass: " + pkg)
                    return@hook true
                }
                chain.proceed()
            }
            XposedInit.log("[SuperClip] OK: hooked isAvailableApplication")
        } catch (t: Throwable) {
            XposedInit.log("[SuperClip] gate hook failed - " + t)
        }
    }

    /** 按 URI 是否携带完整文件参数设置线程标志；仅本次调用链有效。 */
    private fun hookOpenTyped(m: Executable) {
        XposedInit.hook(m) { chain ->
            val uri = chain.getArg(0) as? Uri
            val isFull = isFullFileUri(uri)
            // 非完整 URI 即缩略图探测链，供门禁识别系统自身的后台预取。sp-probe/2611
            if (isFull) fullUri.set(true) else thumbProbe.set(true)
            try {
                chain.proceed()
            } finally {
                if (isFull) fullUri.remove() else thumbProbe.remove()
            }
        }
    }

    /** 缩略图探测 URI 只有 itemId；粘贴请求带 fileName/dataSize/totalSize。 */
    private fun isFullFileUri(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            val q = uri.query ?: return false
            (q.contains("fileName=") && q.contains("dataSize=")) || q.contains("totalSize=")
        } catch (_: Throwable) {
            false
        }
    }
}
