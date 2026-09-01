package com.clipboardfix;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.provider.Settings;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 用 DexKit 精确定位 com.miui.provider.InputProvider 的包名校验方法并放行。
 *
 * <p>这是比包名伪造更精确的一条路径：只对"当前默认输入法"放行，
 * 而不是把所有调用方都伪装成小米搜狗。命中时原方法根本不会执行。
 * 未命中时清除通行证标记，交还原方法，由 {@link ClipboardHook} 的包名伪造兜底。
 */
public final class PackageValidationHook {

    private static final String DECLARING_CLASS = "com.miui.provider.InputProvider";
    private static final String[] KEY_STRINGS = {
            "InputProvider",
            "Invalid caller UID: ",
            "No package name for UID: ",
            "Package validation failed: ",
            "Unexpected error during package validation"
    };

    private PackageValidationHook() {
    }

    public static void init(XposedModuleInterface.PackageLoadedParam param) {
        DexKitBridge bridge = null;
        try {
            System.loadLibrary("dexkit");
            bridge = DexKitBridge.create(param.getApplicationInfo().sourceDir);
            if (bridge == null) {
                log("FAIL: DexKitBridge.create returned null");
                return;
            }

            List<MethodData> found = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(DECLARING_CLASS)
                                    .returnType("boolean")
                                    .usingStrings(KEY_STRINGS))
            );

            if (found == null || found.isEmpty()) {
                log("WARN: validation method not found, fallback to package spoof only");
                return;
            }
            if (found.size() > 1) {
                log("WARN: " + found.size() + " candidates, using the first one");
            }

            ClassLoader cl;
            try {
                cl = param.getDefaultClassLoader();
            } catch (Throwable t) {
                log("FAIL: no classloader for dexkit - " + t);
                return;
            }

            final Method method = found.get(0).getMethodInstance(cl);
            method.setAccessible(true);
            XposedInit.hook(method, chain -> {
                try {
                    int callingUid = Binder.getCallingUid();
                    Context ctx = (Context) Reflect.callMethod(
                            chain.getThisObject(), "getContext");
                    if (ctx == null) return chain.proceed();

                    String current = Settings.Secure.getString(
                            ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                    if (current == null) return chain.proceed();
                    String currentPkg = current.split("/")[0];
                    if (currentPkg.isEmpty()) return chain.proceed();

                    String[] pkgs;
                    Bypass.begin();
                    try {
                        PackageManager pm = ctx.getPackageManager();
                        pkgs = pm.getPackagesForUid(callingUid);
                    } finally {
                        Bypass.end();
                    }
                    if (pkgs == null) return chain.proceed();

                    for (String p : pkgs) {
                        if (currentPkg.equals(p)) {
                            log("granted: uid=" + callingUid + " ime=" + currentPkg);
                            return Boolean.TRUE;
                        }
                    }
                } catch (Throwable t) {
                    log("validation hook error - " + t);
                }
                return chain.proceed();
            });
            log("OK: package validation on " + method.getName());
        } catch (Throwable t) {
            log("FAIL: DexKit validation hook - " + t);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                    // 释放失败不影响功能
                }
            }
        }
    }

    private static void log(String msg) {
        XposedInit.log("[DexKit] " + msg);
    }
}
