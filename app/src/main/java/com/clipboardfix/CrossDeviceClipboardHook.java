package com.clipboardfix;

import android.content.Context;
import android.provider.Settings;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 修复跨设备剪贴板同步在第三方输入法下不显示的 bug。
 *
 * <p>用 DexKit 找到 MiuiClipboardManager.startObserveThumbnailInfoChange(Context)，
 * 在 InputProvider.call() 执行前调用它，确保跨设备同步在查询前启动。
 */
public final class CrossDeviceClipboardHook {

    private static final String TAG = "[CrossDevice]";

    private static volatile Method targetMethod = null;
    private static volatile boolean initialized = false;
    private static volatile long lastTriggerTime = 0;

    private CrossDeviceClipboardHook() {}

    public static void init(XposedModuleInterface.PackageLoadedParam param) {
        if (initialized) return;
        initialized = true;

        DexKitBridge bridge = null;
        try {
            System.loadLibrary("dexkit");
            bridge = DexKitBridge.create(param.getApplicationInfo().sourceDir);
            if (bridge == null) {
                log("FAIL: DexKitBridge null");
                return;
            }

            // 找 startObserveThumbnailInfoChange
            List<org.luckypray.dexkit.result.MethodData> found = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings("startObserveThumbnailInfoChange"))
            );

            if (found == null || found.isEmpty()) {
                log("FAIL: method not found");
                return;
            }

            org.luckypray.dexkit.result.MethodData target = null;
            for (org.luckypray.dexkit.result.MethodData md : found) {
                if (md.getName().equals("startObserveThumbnailInfoChange")) {
                    target = md;
                    break;
                }
            }

            if (target == null) {
                log("FAIL: startObserveThumbnailInfoChange not found");
                return;
            }

            ClassLoader cl = param.getDefaultClassLoader();
            targetMethod = target.getMethodInstance(cl);
            targetMethod.setAccessible(true);
            log("target: " + targetMethod);

            // 找 InputProvider.call
            List<org.luckypray.dexkit.result.MethodData> callMethods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass("com.miui.provider.InputProvider")
                                    .returnType("android.os.Bundle")
                                    .paramCount(3))
            );

            if (callMethods == null || callMethods.isEmpty()) {
                log("FAIL: InputProvider.call not found");
                return;
            }

            final Method callMethod = callMethods.get(0).getMethodInstance(cl);
            callMethod.setAccessible(true);

            XposedInit.hook(callMethod, chain -> {
                // 在原始方法执行前触发同步
                try {
                    Context ctx = null;
                    Object thisObj = chain.getThisObject();
                    if (thisObj != null) {
                        ctx = (Context) Reflect.callMethod(thisObj, "getContext");
                    }
                    if (ctx != null) {
                        String currentIme = Settings.Secure.getString(
                                ctx.getContentResolver(),
                                Settings.Secure.DEFAULT_INPUT_METHOD);
                        if (currentIme != null) {
                            String pkg = currentIme.split("/")[0];
                            if (!isMiuiIme(pkg)) {
                                long now = System.currentTimeMillis();
                                // 限制触发频率，每 5 秒最多一次
                                if (now - lastTriggerTime > 5000) {
                                    lastTriggerTime = now;
                                    log("triggering sync for " + pkg + " on main thread");
                                    // 需要在主线程调用
                                    final Context appCtx = ctx;
                                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                    mainHandler.post(() -> {
                                        try {
                                            targetMethod.invoke(null, appCtx);
                                            log("invoked on main thread");
                                        } catch (Throwable t) {
                                            log("invoke error on main thread - " + t);
                                        }
                                    });
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    log("trigger error - " + t);
                }

                // 执行原始方法
                return chain.proceed();
            });
            log("OK: hooked InputProvider.call");

        } catch (Throwable t) {
            log("FAIL: init - " + t);
        } finally {
            if (bridge != null) {
                try { bridge.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isMiuiIme(String pkg) {
        if (pkg == null) return false;
        return pkg.startsWith("com.xiaomi.") || pkg.startsWith("com.miui.")
                || pkg.equals("com.sohu.inputmethod.sogou.xiaomi")
                || pkg.equals("com.iflytek.inputmethod.miui")
                || pkg.equals("com.baidu.input_mi")
                || pkg.equals("com.miui.catcherpatch");
    }

    private static void log(String msg) {
        XposedInit.log(TAG + " " + msg);
    }
}