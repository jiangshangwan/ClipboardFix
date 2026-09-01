package com.clipboardfix;

import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;

import dalvik.system.BaseDexClassLoader;

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

    public static void init(String pkg, ClassLoader cl) {
        boolean isNonCustomize = !contains(MIUI_IME_LIST, pkg);
        log(pkg + " start (customized=" + !isNonCustomize + ")");

        if (isNonCustomize) {
            Class<?> injector = Reflect.findClassIfExists(
                    "android.inputmethodservice.InputMethodServiceInjector", cl);
            if (injector == null) {
                injector = Reflect.findClassIfExists(
                        "android.inputmethodservice.InputMethodServiceStubImpl",
                        cl);
            }
            if (injector != null) {
                hookSIsImeSupport(injector);
                hookIsXiaoAiEnable(injector);
                setPhraseBgColor(injector, cl);
            } else {
                log("WARN: InputMethodServiceInjector / InputMethodServiceStubImpl not found");
            }
        }

        hookDeleteNotSupportIme(
                "android.inputmethodservice.InputMethodServiceInjector$MiuiSwitchInputMethodListener",
                cl);

        hookModuleManagerLoadDex(cl, isNonCustomize);

        log(pkg + " done");
    }

    private static void log(String msg) {
        XposedInit.log("[IME] " + msg);
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
            java.lang.reflect.Field f = Reflect.findField(clazz, "sIsImeSupport");
            if (f.getType() == boolean.class) {
                Reflect.setStaticField(clazz, "sIsImeSupport", true);
            } else {
                Reflect.setStaticField(clazz, "sIsImeSupport", 1);
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
            XposedInit.hook(m, chain -> false);
            log("OK: isXiaoAiEnable on " + clazz.getName());
        } catch (Throwable t) {
            log("FAIL: isXiaoAiEnable on " + clazz.getName() + " - " + t);
        }
    }

    /** 修复切换输入法列表被裁剪的问题。 */
    private static void hookDeleteNotSupportIme(String className, ClassLoader classLoader) {
        try {
            Class<?> clazz = Reflect.findClassIfExists(className, classLoader);
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
            XposedInit.hook(target, chain -> null);
            log("OK: deleteNotSupportIme on " + className);
        } catch (Throwable t) {
            log("FAIL: deleteNotSupportIme on " + className + " - " + t);
        }
    }

    /**
     * 常用语模块是从 dex 动态加载的，这里拦截加载过程，
     * 把 com.miui.inputmethod.InputMethodBottomManager 一并 hook 掉。
     */
    private static void hookModuleManagerLoadDex(final ClassLoader cl,
                                                 final boolean isNonCustomize) {
        try {
            Class<?> managerClass = Reflect.findClassIfExists(
                    "android.inputmethodservice.InputMethodModuleManager", cl);
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

            // 原 before hook 始终 setResult(null) 短路，这里等价于「不 proceed，直接返回 null」
            XposedInit.hook(loadDex, chain -> {
                ClassLoader loader = (ClassLoader) chain.getArg(0);
                String dexPath = (String) chain.getArg(1);

                if (!(loader instanceof BaseDexClassLoader)) {
                    return null;
                }
                // 已经加载过就别重复 hook
                try {
                    Class.forName("com.miui.inputmethod.InputMethodBottomManager", true, loader);
                    return null;
                } catch (ClassNotFoundException ignored) {
                    // 首次加载，继续往下走
                }

                if (!addDexPath(loader, dexPath)) {
                    return null;
                }

                hookDeleteNotSupportIme(
                        "com.miui.inputmethod.InputMethodBottomManager$MiuiSwitchInputMethodListener",
                        loader);

                Class<?> bottom = Reflect.findClassIfExists(
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
                return null;
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
            XposedInit.hook(m, chain -> {
                try {
                    Object helper = Reflect.getStaticField(bottomClass, "sBottomViewHelper");
                    Object imm = Reflect.getField(helper, "mImm");
                    return ((InputMethodManager) imm).getEnabledInputMethodList();
                } catch (Throwable t) {
                    log("getSupportIme fallback to original - " + t);
                    return chain.proceed();
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
            Class<?> phoneWindow = Reflect.findClassIfExists(
                    "com.android.internal.policy.PhoneWindow", classLoader);
            if (phoneWindow != null) {
                for (Method m : phoneWindow.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if ("setNavigationBarColor".equals(m.getName())
                            && ps.length == 1 && ps[0] == int.class) {
                        m.setAccessible(true);
                        XposedInit.hook(m, chain -> {
                            Object r = chain.proceed();
                            int color = (Integer) chain.getArg(0);
                            if (color != 0) {
                                navBarColor = color;
                                customizeBottomViewColor(injectorClass);
                            }
                            return r;
                        });
                        log("OK: setNavigationBarColor");
                        break;
                    }
                }
            }

            for (Method m : injectorClass.getDeclaredMethods()) {
                if ("addMiuiBottomView".equals(m.getName())) {
                    m.setAccessible(true);
                    XposedInit.hook(m, chain -> {
                        Object r = chain.proceed();
                        customizeBottomViewColor(injectorClass);
                        return r;
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
