package com.clipboardfix

import android.content.Context
import android.os.Binder
import android.provider.Settings
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * 用 DexKit 精确定位 com.miui.provider.InputProvider 的包名校验方法并放行。
 *
 * <p>这是比包名伪造更精确的一条路径：只对"当前默认输入法"放行，
 * 而不是把所有调用方都伪装成小米搜狗。命中时原方法根本不会执行。
 * 未命中时清除通行证标记，交还原方法，由 {@link ClipboardHook} 的包名伪造兜底。
 */
object PackageValidationHook {

    private const val DECLARING_CLASS = "com.miui.provider.InputProvider"
    private val KEY_STRINGS = arrayOf(
        "InputProvider",
        "Invalid caller UID: ",
        "No package name for UID: ",
        "Package validation failed: ",
        "Unexpected error during package validation"
    )

    @JvmStatic
    fun init(param: XposedModuleInterface.PackageLoadedParam) {
        var bridge: DexKitBridge? = null
        try {
            System.loadLibrary("dexkit")
            bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            if (bridge == null) {
                log("FAIL: DexKitBridge.create returned null")
                return
            }

            val found: List<MethodData>? = bridge.findMethod(
                FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(DECLARING_CLASS)
                        .returnType("boolean")
                        .usingStrings(KEY_STRINGS.toList()))
            )

            if (found == null || found.isEmpty()) {
                log("WARN: validation method not found, fallback to package spoof only")
                return
            }
            if (found.size > 1) {
                log("WARN: ${found.size} candidates, using the first one")
            }

            val cl: ClassLoader = try {
                param.defaultClassLoader
            } catch (t: Throwable) {
                log("FAIL: no classloader for dexkit - $t")
                return
            }

            val method: Method = found[0].getMethodInstance(cl)
            method.isAccessible = true
            XposedInit.hook(method) { chain ->
                try {
                    val callingUid = Binder.getCallingUid()
                    val ctx = Reflect.callMethod(chain.thisObject, "getContext") as? Context
                        ?: return@hook chain.proceed()

                    val current = Settings.Secure.getString(
                        ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                        ?: return@hook chain.proceed()
                    val currentPkg = current.split("/")[0]
                    if (currentPkg.isEmpty()) return@hook chain.proceed()

                    val pkgs: Array<String>?
                    Bypass.begin()
                    try {
                        pkgs = ctx.packageManager.getPackagesForUid(callingUid)
                    } finally {
                        Bypass.end()
                    }
                    if (pkgs == null) return@hook chain.proceed()

                    for (p in pkgs) {
                        if (currentPkg == p) {
                            log("granted: uid=$callingUid ime=$currentPkg")
                            return@hook java.lang.Boolean.TRUE
                        }
                    }
                } catch (t: Throwable) {
                    log("validation hook error - $t")
                }
                chain.proceed()
            }
            log("OK: package validation on ${method.name}")
        } catch (t: Throwable) {
            log("FAIL: DexKit validation hook - $t")
        } finally {
            try {
                bridge?.close()
            } catch (ignored: Throwable) {
                // 释放失败不影响功能
            }
        }
    }

    private fun log(msg: String) {
        XposedInit.log("[DexKit] $msg")
    }
}
