package com.clipboardfix

import android.content.Context
import android.provider.Settings

/**
 * 在 system_server 中放行输入法的"获取应用列表"权限。
 *
 * <p>用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，
 * 导致切换输入法功能不显示其他输入法的问题。
 */
object ImePermissionHook {

    private const val TARGET_CLASS = "com.android.server.inputmethod.InputMethodManagerServiceImpl"

    @JvmStatic
    fun init(classLoader: ClassLoader) {
        try {
            val clazz = Reflect.findClassIfExists(TARGET_CLASS, classLoader)
            if (clazz == null) {
                log("SKIP: $TARGET_CLASS not found")
                return
            }
            var target: java.lang.reflect.Method? = null
            for (m in clazz.declaredMethods) {
                if ("isCallingBetweenCustomIME" == m.name) {
                    target = m
                    break
                }
            }
            if (target == null) {
                log("SKIP: isCallingBetweenCustomIME not found")
                return
            }

            XposedInit.hook(target) { chain ->
                val result = chain.proceed()
                try {
                    if (java.lang.Boolean.TRUE == result) return@hook result

                    val args = chain.getArgs()
                    if (args.size < 2) return@hook result

                    val ctx = args[0] as Context
                    val uid = args[1] as Int

                    val current = Settings.Secure.getString(
                        ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return@hook result
                    val currentPkg = current.split("/")[0]
                    if (currentPkg.isEmpty()) return@hook result

                    val pkgs = ctx.packageManager.getPackagesForUid(uid) ?: return@hook result

                    for (p in pkgs) {
                        if (currentPkg == p) {
                            log("granted: uid=$uid ime=$currentPkg")
                            return@hook java.lang.Boolean.TRUE
                        }
                    }
                } catch (t: Throwable) {
                    log("isCallingBetweenCustomIME error - $t")
                }
                result
            }
            log("OK: isCallingBetweenCustomIME")
        } catch (t: Throwable) {
            log("FAIL: isCallingBetweenCustomIME - $t")
        }
    }

    private fun log(msg: String) {
        XposedInit.log("[Perm] $msg")
    }
}
