package com.clipboardfix;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.List;

/**
 * 在 system_server 中放行输入法的"获取应用列表"权限。
 *
 * <p>用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，
 * 导致切换输入法功能不显示其他输入法的问题。
 */
public final class ImePermissionHook {

    private static final String TARGET_CLASS =
            "com.android.server.inputmethod.InputMethodManagerServiceImpl";

    private ImePermissionHook() {
    }

    public static void init(ClassLoader classLoader) {
        try {
            Class<?> clazz = Reflect.findClassIfExists(TARGET_CLASS, classLoader);
            if (clazz == null) {
                log("SKIP: " + TARGET_CLASS + " not found");
                return;
            }
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if ("isCallingBetweenCustomIME".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                log("SKIP: isCallingBetweenCustomIME not found");
                return;
            }

            XposedInit.hook(target, chain -> {
                Object result = chain.proceed();
                try {
                    if (Boolean.TRUE.equals(result)) return result;

                    List<Object> args = chain.getArgs();
                    if (args.size() < 2) return result;

                    Context ctx = (Context) args.get(0);
                    int uid = (Integer) args.get(1);

                    String current = Settings.Secure.getString(
                            ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                    if (current == null) return result;
                    String currentPkg = current.split("/")[0];
                    if (currentPkg.isEmpty()) return result;

                    PackageManager pm = ctx.getPackageManager();
                    String[] pkgs = pm.getPackagesForUid(uid);
                    if (pkgs == null) return result;

                    for (String p : pkgs) {
                        if (currentPkg.equals(p)) {
                            log("granted: uid=" + uid + " ime=" + currentPkg);
                            return Boolean.TRUE;
                        }
                    }
                } catch (Throwable t) {
                    log("isCallingBetweenCustomIME error - " + t);
                }
                return result;
            });
            log("OK: isCallingBetweenCustomIME");
        } catch (Throwable t) {
            log("FAIL: isCallingBetweenCustomIME - " + t);
        }
    }

    private static void log(String msg) {
        XposedInit.log("[Perm] " + msg);
    }
}
