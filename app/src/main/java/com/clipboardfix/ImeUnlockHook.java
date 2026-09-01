package com.clipboardfix;

import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;

import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 解锁 MIUI 全面屏优化（第三方输入法底部常用语/剪贴板入口）。
 *
 * <p>逻辑移植自 RC1844/MIUI_IME_Unlock，由 Kotlin + EzXHelper 改写为纯 Java。
 * 只在第三方输入法进程内安装，小米定制版输入法跳过大部分 hook。
 */
public final class ImeUnlockHook {

    /** 小米定制输入法，这些不做 sIsImeSupport / isXiaoAiEnable 处理。 */
    private static final String[] MIUI_IME_LIST = {
            "com.iflytek.inputmethod.miui",
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.baidu.input_mi",
            "com.miui.catcherpatch",
            "com.xiaomi.type",
    };

    private static volatile Integer navBarColor;

    private ImeUnlockHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        boolean isNonCustomize = !contains(MIUI_IME_LIST, pkg);
        log(pkg + " start (customized=" + !isNonCustomize + ")");

        if (isNonCustomize) {
            Class<?> injector = XposedHelpers.findClassIfExists(
                    "android.inputmethodservice.InputMethodServiceInjector", lpparam.classLoader);
            if (injector == null) {
                injector = XposedHelpers.findClassIfExists(
                        "android.inputmethodservice.InputMethodServiceStubImpl",
                        lpparam.classLoader);
            }
            if (injector != null) {
                hookSIsImeSupport(injector);
                hookIsXiaoAiEnable(injector);
                setPhraseBgColor(injector, lpparam.classLoader);
            } else {
                log("WARN: InputMethodServiceInjector / InputMethodServiceStubImpl not found");
            }
        }

        hookDeleteNotSupportIme(
                "android.inputmethodservice.InputMethodServiceInjector$MiuiSwitchInputMethodListener",
                lpparam.classLoader);

        hookModuleManagerLoadDex(lpparam, isNonCustomize);

        log(pkg + " done");
    }

    private static void log(String msg) {
        XposedBridge.log(XposedInit.TAG + "[IME] " + msg);
    }

    private static boolean contains(String[] arr, String value) {
        if (value == null) return false;
        for (String s : arr) {
            if (s.equals(value)) return true;
        }
        return false;
    }

    /** 跳过包名检查，直接开启输入法优化。字段可能是 int 也可能是 boolean，两种都试。 */
    private static void hookSIsImeSupport(Class<?> clazz) {
        try {
            java.lang.reflect.Field f = XposedHelpers.findField(clazz, "sIsImeSupport");
            if (f.getType() == boolean.class) {
                XposedHelpers.setStaticBooleanField(clazz, "sIsImeSupport", true);
            } else {
                XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1);
            }
            log("OK: sIsImeSupport on " + clazz.getName());
        } catch (Throwable t) {
            log("FAIL: sIsImeSupport on " + clazz.getName() + " - " + t);
        }
    }

    /** 小爱语音输入按钮失效修复。 */
    private static void hookIsXiaoAiEnable(Class<?> clazz) {
        try {
            Method m = clazz.getDeclaredMethod("isXiaoAiEnable");
            m.setAccessible(true);
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
            log("OK: isXiaoAiEnable on " + clazz.getName());
        } catch (Throwable t) {
            log("FAIL: isXiaoAiEnable on " + clazz.getName() + " - " + t);
        }
    }

    /** 修复切换输入法列表被裁剪的问题。 */
    private static void hookDeleteNotSupportIme(String className, ClassLoader classLoader) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                log("SKIP: class not found " + className);
                return;
            }
            Method target = null;
            for (Method m : clazz.getDeclaredMethods()) {
                if ("deleteNotSupportIme".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                log("SKIP: no deleteNotSupportIme in " + className);
                return;
            }
            target.setAccessible(true);
            XposedBridge.hookMethod(target, XC_MethodReplacement.returnConstant(null));
            log("OK: deleteNotSupportIme on " + className);
        } catch (Throwable t) {
            log("FAIL: deleteNotSupportIme on " + className + " - " + t);
        }
    }

    /**
     * 常用语模块是从 dex 动态加载的，这里拦截加载过程，
     * 把 com.miui.inputmethod.InputMethodBottomManager 一并 hook 掉。
     */
    private static void hookModuleManagerLoadDex(final XC_LoadPackage.LoadPackageParam lpparam,
                                                 final boolean isNonCustomize) {
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(
                    "android.inputmethodservice.InputMethodModuleManager", lpparam.classLoader);
            if (managerClass == null) {
                log("SKIP: InputMethodModuleManager not found");
                return;
            }
            Method loadDex = null;
            for (Method m : managerClass.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if ("loadDex".equals(m.getName()) && ps.length == 2
                        && ClassLoader.class.isAssignableFrom(ps[0])
                        && String.class.equals(ps[1])) {
                    loadDex = m;
                    break;
                }
            }
            if (loadDex == null) {
                log("SKIP: loadDex(ClassLoader, String) not found");
                return;
            }
            loadDex.setAccessible(true);

            XposedBridge.hookMethod(loadDex, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ClassLoader loader = (ClassLoader) param.args[0];
                    String dexPath = (String) param.args[1];

                    if (!(loader instanceof BaseDexClassLoader)) {
                        param.setResult(null);
                        return;
                    }
                    // 已经加载过就别重复 hook
                    try {
                        Class.forName("com.miui.inputmethod.InputMethodBottomManager", true, loader);
                        param.setResult(null);
                        return;
                    } catch (ClassNotFoundException ignored) {
                        // 首次加载，继续往下走
                    }

                    if (!addDexPath(loader, dexPath)) {
                        param.setResult(null);
                        return;
                    }

                    hookDeleteNotSupportIme(
                            "com.miui.inputmethod.InputMethodBottomManager$MiuiSwitchInputMethodListener",
                            loader);

                    Class<?> bottom = XposedHelpers.findClassIfExists(
                            "com.miui.inputmethod.InputMethodBottomManager", loader);
                    if (bottom != null) {
                        if (isNonCustomize) {
                            hookSIsImeSupport(bottom);
                            hookIsXiaoAiEnable(bottom);
                        }
                        hookGetSupportIme(bottom);
                    } else {
                        log("WARN: InputMethodBottomManager not found after addDexPath");
                    }
                    param.setResult(null);
                }
            });
            log("OK: InputMethodModuleManager.loadDex");
        } catch (Throwable t) {
            log("FAIL: hook loadDex - " + t);
        }
    }

    private static boolean addDexPath(ClassLoader loader, String dexPath) {
        Class<?> c = loader.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod("addDexPath", String.class);
                m.setAccessible(true);
                m.invoke(loader, dexPath);
                return true;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                log("FAIL: addDexPath - " + t);
                return false;
            }
        }
        log("FAIL: addDexPath not found on " + loader.getClass().getName());
        return false;
    }

    /** A11 修复切换输入法列表：直接用系统已启用的输入法列表替换。 */
    private static void hookGetSupportIme(final Class<?> bottomClass) {
        try {
            Method m = bottomClass.getDeclaredMethod("getSupportIme");
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object helper = XposedHelpers.getStaticObjectField(
                                bottomClass, "sBottomViewHelper");
                        Object imm = XposedHelpers.getObjectField(helper, "mImm");
                        return ((InputMethodManager) imm).getEnabledInputMethodList();
                    } catch (Throwable t) {
                        log("getSupportIme fallback to original - " + t);
                        return XposedBridge.invokeOriginalMethod(param.method,
                                param.thisObject, param.args);
                    }
                }
            });
            log("OK: getSupportIme on " + bottomClass.getName());
        } catch (Throwable t) {
            log("FAIL: getSupportIme on " + bottomClass.getName() + " - " + t);
        }
    }

    /** 在合适的时机把抬高区域的背景色改成导航栏颜色的反色。 */
    private static void setPhraseBgColor(final Class<?> injectorClass, ClassLoader classLoader) {
        try {
            Class<?> phoneWindow = XposedHelpers.findClassIfExists(
                    "com.android.internal.policy.PhoneWindow", classLoader);
            if (phoneWindow != null) {
                for (Method m : phoneWindow.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if ("setNavigationBarColor".equals(m.getName())
                            && ps.length == 1 && ps[0] == int.class) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                int color = (Integer) param.args[0];
                                if (color == 0) return;
                                navBarColor = color;
                                customizeBottomViewColor(injectorClass);
                            }
                        });
                        log("OK: setNavigationBarColor");
                        break;
                    }
                }
            }

            for (Method m : injectorClass.getDeclaredMethods()) {
                if ("addMiuiBottomView".equals(m.getName())) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            customizeBottomViewColor(injectorClass);
                        }
                    });
                    log("OK: addMiuiBottomView");
                    break;
                }
            }
        } catch (Throwable t) {
            log("FAIL: setPhraseBgColor - " + t);
        }
    }

    private static void customizeBottomViewColor(Class<?> clazz) {
        Integer navBar = navBarColor;
        if (navBar == null) return;
        int color = -1 - navBar;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!"customizeBottomViewColor".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 4) continue;
            if (ps[0] != boolean.class && ps[0] != Boolean.class) continue;
            if (ps[1] != int.class || ps[2] != int.class || ps[3] != int.class) continue;
            try {
                m.setAccessible(true);
                m.invoke(null, true, navBar, color | 0xFF000000, color | 0x66000000);
                return;
            } catch (Throwable t) {
                log("FAIL: customizeBottomViewColor invoke - " + t);
                return;
            }
        }
        log("SKIP: customizeBottomViewColor(boolean,int,int,int) not found");
    }
}
