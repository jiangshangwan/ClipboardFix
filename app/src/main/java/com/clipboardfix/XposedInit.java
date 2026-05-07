package com.clipboardfix;

import android.content.ContentProvider;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed Module: ClipboardFix for HyperOS 3.0
 * Target: com.miui.phrase
 *
 * v4.7.7 的 InputProvider 通过 PackageManager.getNameForUid() 验证调用者包名。
 * 第三方输入法（如 Wetype）的包名不在白名单中，被拒绝访问。
 * 白名单包括系统输入法，如 com.sohu.inputmethod.sogou.xiaomi。
 *
 * 策略：
 *  1. getNameForUid hook: 返回白名单包名（com.sohu.inputmethod.sogou.xiaomi）
 *  2. getPackagesForUid hook: 返回白名单包名数组
 *  3. attachInfo hook: 找到 obfuscated 的 InputProvider，hook 其 query 方法
 *  4. query hook: 如果 SecurityException 仍被抛出，捕获并清空
 *  5. SecurityException constructor hook: 备选方案
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "[ClipboardFix]";
    private static final String TARGET_PACKAGE = "com.miui.phrase";
    private static final int SYSTEM_UID = 1000;
    // 白名单包名：通过日志验证，SOGOU 输入法 (com.sohu.inputmethod.sogou.xiaomi)
    // 调用时 provider 返回了数据，说明它在白名单中
    private static final String[] ALLOWED_PACKAGES = {
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.xiaomi.type"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " ===== Module loaded =====");
        XposedBridge.log(TAG + " ClassLoader: " + lpparam.classLoader.getClass().getName());

        // hookCallingUid(); // 会导致 getNameForUid(1000) 返回 android.uid.system，不在白名单中
        hookPackageManager();
        hookAttachInfo();
        hookSecurityException();
    }

    // ====== Hook 1: Binder.getCallingUid spoof ======
    // 注意：此 hook 在某些设备上可能不生效（如日志所示）
    // 不影响核心策略，只是额外的保障
    private void hookCallingUid() {
        try {
            XposedHelpers.findAndHookMethod(Binder.class, "getCallingUid", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int uid = (int) param.getResult();
                    if (uid > SYSTEM_UID && uid < 100000) {
                        XposedBridge.log(TAG + " Binder.getCallingUid " + uid + " -> " + SYSTEM_UID);
                        param.setResult(SYSTEM_UID);
                    }
                }
            });
            XposedBridge.log(TAG + " OK: Binder.getCallingUid");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: Binder.getCallingUid - " + t.getMessage());
        }
    }

    // ====== Hook 2: PackageManager.getNameForUid / getPackagesForUid ======
    // 核心策略：让 provider 验证调用者时认为是白名单中的系统输入法
    private void hookPackageManager() {
        boolean nameHooked = hookNameForUidOn(PackageManager.class);
        boolean pkgHooked = hookPkgsForUidOn(PackageManager.class);

        if (nameHooked && pkgHooked) return;

        // Fallback: find ApplicationPackageManager (concrete subclass with non-abstract methods)
        try {
            Class<?> appPmClass = Class.forName("android.app.ApplicationPackageManager",
                    false, PackageManager.class.getClassLoader());
            XposedBridge.log(TAG + " Found ApplicationPackageManager: " + appPmClass.getName());
            if (!nameHooked) hookNameForUidOn(appPmClass);
            if (!pkgHooked) hookPkgsForUidOn(appPmClass);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + " WARN: ApplicationPackageManager not found");
        }
    }

    private boolean hookNameForUidOn(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "getNameForUid", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int uid = (int) param.args[0];
                            String result = (String) param.getResult();
                            // 只对非系统、非白名单 UID 进行 spoof
                            if (uid > SYSTEM_UID && uid < 100000) {
                                // 检查是否已经是白名单包名
                                boolean isAllowed = false;
                                if (result != null) {
                                    for (String pkg : ALLOWED_PACKAGES) {
                                        if (pkg.equals(result)) {
                                            isAllowed = true;
                                            break;
                                        }
                                    }
                                }
                                if (!isAllowed) {
                                    param.setResult(ALLOWED_PACKAGES[0]);
                                    XposedBridge.log(TAG + " getNameForUid spoofed: "
                                            + uid + " (" + result + ") -> " + ALLOWED_PACKAGES[0]);
                                } else {
                                    XposedBridge.log(TAG + " getNameForUid(" + uid + ") -> "
                                            + result + " [already allowed]");
                                }
                            } else {
                                XposedBridge.log(TAG + " getNameForUid(" + uid + ") -> "
                                        + (result != null ? result : "null"));
                            }
                        }
                    });
            XposedBridge.log(TAG + " OK: getNameForUid on " + clazz.getSimpleName());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: getNameForUid on " + clazz.getSimpleName()
                    + " - " + t.getMessage());
            return false;
        }
    }

    private boolean hookPkgsForUidOn(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "getPackagesForUid", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int uid = (int) param.args[0];
                            String[] result = (String[]) param.getResult();
                            if (uid > SYSTEM_UID && uid < 100000) {
                                boolean isAllowed = false;
                                if (result != null) {
                                    for (String pkg : result) {
                                        for (String allowed : ALLOWED_PACKAGES) {
                                            if (allowed.equals(pkg)) {
                                                isAllowed = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (!isAllowed) {
                                    param.setResult(new String[]{ALLOWED_PACKAGES[0]});
                                    XposedBridge.log(TAG + " getPackagesForUid spoofed: "
                                            + uid + " -> [" + ALLOWED_PACKAGES[0] + "]");
                                }
                            }
                        }
                    });
            XposedBridge.log(TAG + " OK: getPackagesForUid on " + clazz.getSimpleName());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: getPackagesForUid on " + clazz.getSimpleName()
                    + " - " + t.getMessage());
            return false;
        }
    }

    // ====== Hook 3: ContentProvider.attachInfo → find concrete query ======
    private void hookAttachInfo() {
        try {
            XposedHelpers.findAndHookMethod(
                    ContentProvider.class, "attachInfo",
                    android.content.Context.class,
                    android.content.pm.ProviderInfo.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Class<?> clazz = param.thisObject.getClass();
                            if (clazz.equals(ContentProvider.class)) return;
                            if (!ContentProvider.class.isAssignableFrom(clazz)) return;

                            android.content.pm.ProviderInfo info =
                                    (android.content.pm.ProviderInfo) param.args[1];
                            if (info != null && info.authority != null
                                    && info.authority.contains("input")) {
                                XposedBridge.log(TAG + " Found INPUT provider: "
                                        + clazz.getName() + " authority=" + info.authority);
                                hookConcreteQueryMethod(clazz);
                            }
                        }
                    });
            XposedBridge.log(TAG + " OK: ContentProvider.attachInfo");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: ContentProvider.attachInfo - " + t.getMessage());
        }
    }

    private void hookConcreteQueryMethod(Class<?> providerClass) {
        try {
            for (Method m : providerClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 5
                        && Uri.class.isAssignableFrom(params[0])
                        && String[].class.equals(params[1])
                        && String.class.equals(params[2])
                        && String[].class.equals(params[3])
                        && String.class.equals(params[4])) {

                    XposedBridge.log(TAG + " Found query method: "
                            + providerClass.getName() + "." + m.getName()
                            + "(Uri,String[],String,String[],String)");

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) {
                                Throwable t = param.getThrowable();
                                if (t instanceof SecurityException) {
                                    XposedBridge.log(TAG + " query CAUGHT: "
                                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                                    XposedBridge.log(TAG + " query: clearing exception");
                                    param.setThrowable(null);
                                }
                            } else {
                                Cursor result = (Cursor) param.getResult();
                                XposedBridge.log(TAG + " query returned: "
                                        + (result != null ? "Cursor(" + result.getCount() + " rows)" : "null"));
                            }
                        }
                    });
                    XposedBridge.log(TAG + " OK: hooked concrete query on " + providerClass.getName());
                    return;
                }
            }
            XposedBridge.log(TAG + " WARN: no query method found on " + providerClass.getName());

            for (Method m : providerClass.getDeclaredMethods()) {
                if (!Modifier.isAbstract(m.getModifiers())) {
                    XposedBridge.log(TAG + "   " + m.getName()
                            + "(" + Arrays.toString(m.getParameterTypes()) + ")");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: hookConcreteQueryMethod - " + t.getMessage());
        }
    }

    // ====== Hook 4: SecurityException constructor (backup) ======
    private void hookSecurityException() {
        hookSecExConstructor(String.class);
        hookSecExConstructor(String.class, Throwable.class);
        hookSecExConstructor();
    }

    private void hookSecExConstructor(Class<?>... paramTypes) {
        try {
            Object[] params = new Object[paramTypes.length + 1];
            for (int i = 0; i < paramTypes.length; i++) {
                params[i] = paramTypes[i];
            }
            params[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String msg = param.args.length > 0 && param.args[0] instanceof String
                            ? (String) param.args[0] : "";
                    if (!msg.contains("Permission Denied") && !msg.contains("Invalid caller")
                            && !msg.contains("Package validation") && !msg.contains("No package name")) {
                        return;
                    }
                    StackTraceElement[] st = new Exception().getStackTrace();
                    for (StackTraceElement e : st) {
                        if (e.getClassName().contains("miui.provider")
                                || e.getClassName().contains("miui.phrase")) {
                            XposedBridge.log(TAG + " SecurityException BLOCKED: " + msg);
                            throw new IllegalStateException("BYPASSED: " + msg);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookConstructor(SecurityException.class, params);
            XposedBridge.log(TAG + " OK: SecurityException("
                    + Arrays.toString(paramTypes) + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: SecurityException("
                    + Arrays.toString(paramTypes) + ") - " + t.getMessage());
        }
    }
}
