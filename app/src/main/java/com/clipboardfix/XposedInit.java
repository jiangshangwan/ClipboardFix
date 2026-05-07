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
 * v4.7.7 ? InputProvider ?? PackageManager.getNameForUid() ????????
 * ??????(? Wetype)?????????,??????
 * ??????????,? com.sohu.inputmethod.sogou.xiaomi?
 *
 * ??:
 *  1. getNameForUid hook: ???????(com.sohu.inputmethod.sogou.xiaomi)
 *  2. getPackagesForUid hook: ?????????
 *  3. attachInfo hook: ?? obfuscated ? InputProvider,hook ? query ??
 *  4. query hook: ?? SecurityException ????,?????
 *  5. SecurityException constructor hook: ????
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "[ClipboardFix]";
    private static final String TARGET_PACKAGE = "com.miui.phrase";
    private static final int SYSTEM_UID = 1000;
    // ?????:??????,SOGOU ??? (com.sohu.inputmethod.sogou.xiaomi)
    // ??? provider ?????,????????
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

        hookCallingUid();
        hookPackageManager();
        hookAttachInfo();
        hookSecurityException();
    }

    // ====== Hook 1: Binder.getCallingUid spoof ======
    // ??:? hook ???????????(?????)
    // ???????,???????
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
    // ????:? provider ???????????????????
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
                            // ?????????? UID ?? spoof
                            if (uid > SYSTEM_UID && uid < 100000) {
                                // ????????????
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

    // ====== Hook 3: ContentProvider.attachInfo ? find concrete query ======
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
