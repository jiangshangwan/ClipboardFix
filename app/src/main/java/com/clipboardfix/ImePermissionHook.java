package com.clipboardfix;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 在 system_server（包名为 android）中放行输入法的"获取应用列表"权限。
 *
 * <p>用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，
 * 导致切换输入法功能不显示其他输入法的问题。
 */
public final class ImePermissionHook {

    private static final String TARGET_CLASS =
            "com.android.server.inputmethod.InputMethodManagerServiceImpl";

    private ImePermissionHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(TARGET_CLASS, lpparam.classLoader);
            if (clazz == null) {
                log("SKIP: " + TARGET_CLASS + " not found");
                return;
            }
            Method target = null;
            for (Method m : clazz.getDeclaredMethods()) {
                if ("isCallingBetweenCustomIME".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                log("SKIP: isCallingBetweenCustomIME not found");
                return;
            }
            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (Boolean.TRUE.equals(param.getResult())) return;
                        if (param.args == null || param.args.length < 2) return;

                        Context ctx = (Context) param.args[0];
                        int uid = (Integer) param.args[1];

                        String current = Settings.Secure.getString(
                                ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                        if (current == null) return;
                        String currentPkg = current.split("/")[0];
                        if (currentPkg.isEmpty()) return;

                        PackageManager pm = ctx.getPackageManager();
                        String[] pkgs = pm.getPackagesForUid(uid);
                        if (pkgs == null) return;

                        for (String p : pkgs) {
                            if (currentPkg.equals(p)) {
                                param.setResult(Boolean.TRUE);
                                log("granted: uid=" + uid + " ime=" + currentPkg);
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        log("isCallingBetweenCustomIME error - " + t);
                    }
                }
            });
            log("OK: isCallingBetweenCustomIME");
        } catch (Throwable t) {
            log("FAIL: isCallingBetweenCustomIME - " + t);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(XposedInit.TAG + "[Perm] " + msg);
    }
}
