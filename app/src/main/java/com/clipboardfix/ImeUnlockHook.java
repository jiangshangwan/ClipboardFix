package com.clipboardfix;

import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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

    /**
     * 需要启用「键盘抬高」修复的输入法白名单。
     *
     * <p>抬高修复会强制修改 keyboard view 的 LayoutParams（height=0/weight=1、清 paddingBottom），
     * 对本身布局正常的输入法（搜狗普通版/小米版、百度等）反而会造成输入框被顶高等异常。
     * 因此只对实测出现底部空白/抬高问题的输入法启用。
     */
    private static final String[] RAISE_FIX_IME_LIST = {
            "com.tencent.wetype",   // 微信输入法：澎湃 OS4 / 部分机型底部被抬高
    };

    private static volatile Integer navBarColor;

    private ImeUnlockHook() {
    }

    public static void init(String pkg, ClassLoader cl) {
        boolean isNonCustomize = !contains(MIUI_IME_LIST, pkg);
        log(pkg + " start (customized=" + !isNonCustomize
                + ", sdk=" + Build.VERSION.SDK_INT
                + ", device=" + Build.DEVICE
                + ", miui=" + SysProps.get("ro.miui.ui.version.name", "?"));

        if (isNonCustomize) {
            // 抬高修复只针对白名单输入法，避免误伤搜狗等本身布局正常的输入法
            if (contains(RAISE_FIX_IME_LIST, pkg)) {
                hookKeyboardRaiseFix();
                log("raise-fix enabled for " + pkg);
            } else {
                log("raise-fix skipped for " + pkg);
            }
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

    /**
     * 跳过包名检查，直接开启输入法优化。
     *
     * <p>同时做两件事，互为兜底：
     * <ol>
     *   <li>改静态字段 {@code sIsImeSupport}（可能是 int 也可能是 boolean）——对旧版 ROM 有效；</li>
     *   <li>hook 方法 {@code isImeSupport()} 恒返回 true——与赋值时机无关，服务重建后依然有效。</li>
     * </ol>
     *
     * <p>第 2 点参照 RC1844/MIUI_IME_Unlock v1.17（PR #34「Fix IME support hook after service
     * recreation」）。只改静态字段是一次性赋值：输入法服务重建、类被重新初始化或别处重新
     * 计算该字段时，值会被写回 false，底部常用语入口随之消失且必须重启输入法进程才恢复。
     * 改为拦截判断方法后，任何调用点拿到的恒为 true，不受重置时机影响。
     */
    private static void hookSIsImeSupport(Class<?> clazz) {
        boolean fieldOk = hookSIsImeSupportField(clazz);
        int methodCount = hookIsImeSupportMethod(clazz);
        boolean clinitOk = hookClassInitToReapply(clazz);
        if (fieldOk || methodCount > 0 || clinitOk) {
            log("OK: ime support on " + clazz.getName()
                    + " (field=" + fieldOk + ", method=" + methodCount
                    + ", clinit=" + clinitOk + ")");
        } else {
            log("SKIP: neither sIsImeSupport field nor isImeSupport() in " + clazz.getName());
        }
    }

    private static boolean hookSIsImeSupportField(Class<?> clazz) {
        try {
            java.lang.reflect.Field f = Reflect.findField(clazz, "sIsImeSupport");
            if (f.getType() == boolean.class) {
                Reflect.setStaticField(clazz, "sIsImeSupport", true);
            } else {
                Reflect.setStaticField(clazz, "sIsImeSupport", 1);
            }
            return true;
        } catch (Throwable t) {
            log("SKIP: field sIsImeSupport on " + clazz.getName() + " - " + t);
            return false;
        }
    }

    /**
     * 让目标类里所有 {@code isImeSupport()} 恒返回 true。
     *
     * <p>沿父类链找，命中所有「无参且返回 boolean / Boolean」的同名方法。
     * 静态、实例、私有方法都 hook，避免 ROM 改动签名后漏掉。
     *
     * @return 成功 hook 的方法数量
     */
    private static int hookIsImeSupportMethod(Class<?> clazz) {
        int count = 0;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"isImeSupport".equals(m.getName())) continue;
                Class<?> rt = m.getReturnType();
                if (rt != boolean.class && rt != Boolean.class) continue;
                try {
                    m.setAccessible(true);
                    // 系统框架里这类短方法容易被 ART 内联，内联后 hook 不会被调用，先反优化
                    XposedInit.deoptimizeMethod(m);
                    XposedInit.hook(m, chain -> Boolean.TRUE);
                    count++;
                    log("OK: isImeSupport(" + describeParams(m) + ") on "
                            + c.getSimpleName());
                } catch (Throwable t) {
                    log("FAIL: method isImeSupport on " + c.getName() + " - " + t);
                }
            }
            c = c.getSuperclass();
        }
        return count;
    }

    /**
     * 类被（重新）初始化后立刻把 support 字段改回来。
     *
     * <p>只在包加载时赋值一次是不够的：类重新初始化、或走动态加载路径稍后才初始化时，
     * {@code <clinit>} 会把 {@code sIsImeSupport} 重新算回默认值。
     * 这里挂在静态初始化之后补一刀，覆盖所有初始化时序。
     */
    private static boolean hookClassInitToReapply(Class<?> clazz) {
        try {
            XposedInit.hookClassInitializer(clazz, chain -> {
                Object r = chain.proceed();   // 先跑完原始静态初始化
                hookSIsImeSupportField(clazz); // 再覆盖成我们期望的值
                log("reapplied support flag after <clinit> of " + clazz.getSimpleName());
                return r;
            });
            return true;
        } catch (Throwable t) {
            log("SKIP: class initializer hook on " + clazz.getName() + " - " + t);
            return false;
        }
    }

    /** 把方法参数类型拼成可读字符串，便于在日志里确认签名差异。 */
    private static String describeParams(Method m) {
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ps[i].getSimpleName());
        }
        return sb.toString();
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

    // ---------------------------------------------------------------
    // 输入法被「抬高」修复
    //
    // 现象：部分机型（澎湃 OS4 / 小米 17 Pro Max、15 Ultra）上第三方输入法
    //（微信输入法）键盘底部出现多余空白、键盘整体被顶高；小米 13 Pro / 15 Pro 正常。
    // 机制与早期豆包输入法一致：MIUI 全面屏优化注入底部功能区（BottomView）后，
    // onCreateInputView 返回的 keyboard view 仍是 WRAP_CONTENT，且可能自带用于
    //「避让导航栏」的 paddingBottom —— 全面屏优化下导航栏已透明，这段间距就成了
    // 无用留白，把键盘顶高。
    //
    // 修法：让 keyboard view 在父容器 InputFrame（继承 LinearLayout）里撑满剩余空间
    //（height=0 / weight=1），并清掉 paddingBottom / bottomMargin。
    // 仅在值不等于目标值时才写回，保证幂等，不会与输入法的 layout 形成死循环。
    //
    // hook 点选 setInputView(View)：framework 的 public 方法、子类极少重写；
    // 而 onCreateInputView 常被子类重写，只 hook 基类拦截不到。
    // ---------------------------------------------------------------

    private static final int MAX_IME_DUMP = 8;
    private static int imeDumpCount = 0;

    private static void hookKeyboardRaiseFix() {
        try {
            Class<?> ims = android.inputmethodservice.InputMethodService.class;
            Method setInputView = ims.getDeclaredMethod("setInputView", View.class);
            setInputView.setAccessible(true);
            XposedInit.hook(setInputView, chain -> {
                Object r = chain.proceed();
                try {
                    Object arg0 = chain.getArg(0);
                    if (arg0 instanceof View) {
                        fixKeyboardView((View) arg0);
                    }
                } catch (Throwable t) {
                    log("raise: setInputView err - " + t);
                }
                return r;
            });
            log("OK: setInputView raise-fix hook");
        } catch (Throwable t) {
            log("FAIL: setInputView raise-fix - " + t);
        }
    }

    private static void fixKeyboardView(final View v) {
        if (v == null) return;
        dumpKeyboard(v, "setInputView");
        if (v.isAttachedToWindow()) {
            applyFillParent(v, "already-attached");
            dumpKeyboard(v, "already-attached");
        }
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(final View vv) {
                applyFillParent(vv, "attach");
                dumpKeyboard(vv, "attach");
                for (final long d : new long[]{300L, 1200L}) {
                    vv.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyFillParent(vv, "t+" + d);
                            dumpKeyboard(vv, "t+" + d);
                        }
                    }, d);
                }
                vv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v2, int l, int t, int r, int b,
                                               int ol, int ot, int or_, int ob) {
                        applyFillParent(v2, "layout");
                    }
                });
            }

            @Override
            public void onViewDetachedFromWindow(View vv) {
            }
        });
    }

    /** 让 keyboard view 撑满父容器并清掉底部多余间距；幂等，仅在需要时才写回。 */
    private static void applyFillParent(View v, String tag) {
        try {
            boolean changed = false;
            if (v.getPaddingBottom() != 0) {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), 0);
                changed = true;
            }
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp == null) return;

            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) lp;
                if (l.height != 0 || l.weight != 1f || l.bottomMargin != 0) {
                    log("raise: fix LinearLayout lp h=" + l.height + "->0"
                            + " weight=" + l.weight + "->1"
                            + " margB=" + l.bottomMargin + "->0 [" + tag + "]");
                    l.height = 0;
                    l.weight = 1f;
                    l.bottomMargin = 0;
                    changed = true;
                }
            } else if (lp instanceof FrameLayout.LayoutParams) {
                if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    log("raise: fix FrameLayout lp h=" + lp.height
                            + "->MATCH [" + tag + "]");
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    changed = true;
                }
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams ml = (ViewGroup.MarginLayoutParams) lp;
                    if (ml.bottomMargin != 0) {
                        ml.bottomMargin = 0;
                        changed = true;
                    }
                }
            } else if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams ml = (ViewGroup.MarginLayoutParams) lp;
                if (ml.bottomMargin != 0) {
                    ml.bottomMargin = 0;
                    changed = true;
                }
            }

            if (changed) {
                v.setLayoutParams(lp);
            }
        } catch (Throwable t) {
            log("raise: applyFillParent err - " + t);
        }
    }

    /** 打出键盘 view 与父容器的实际位置，用于确认「抬高」的空白来自哪一层。 */
    private static void dumpKeyboard(View v, String tag) {
        if (imeDumpCount >= MAX_IME_DUMP) return;
        imeDumpCount++;
        try {
            DisplayMetrics dm = v.getResources().getDisplayMetrics();
            StringBuilder sb = new StringBuilder();
            sb.append("raise-dump[").append(tag).append("] screenH=").append(dm.heightPixels);
            sb.append(" view=").append(v.getClass().getSimpleName())
                    .append('[').append(v.getTop()).append("->").append(v.getBottom()).append(']')
                    .append(" padB=").append(v.getPaddingBottom())
                    .append(" measH=").append(v.getMeasuredHeight());
            ViewParent p = v.getParent();
            if (p instanceof View) {
                View pv = (View) p;
                sb.append(" parent=").append(pv.getClass().getSimpleName())
                        .append('[').append(pv.getTop()).append("->").append(pv.getBottom())
                        .append(']');
                if (pv instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) pv;
                    sb.append(" kids=");
                    int n = Math.min(g.getChildCount(), 6);
                    for (int i = 0; i < n; i++) {
                        View c = g.getChildAt(i);
                        sb.append(c.getClass().getSimpleName())
                                .append('[').append(c.getTop()).append("->").append(c.getBottom())
                                .append(']');
                    }
                }
            }
            Object insets = v.getRootWindowInsets();
            if (insets != null) {
                try {
                    Class<?> typeCls = Class.forName("android.view.WindowInsets$Type");
                    int navBars = (Integer) typeCls.getMethod("navigationBars").invoke(null);
                    Object nav = insets.getClass().getMethod("getInsets", int.class)
                            .invoke(insets, navBars);
                    sb.append(" navInsets=").append(nav);
                } catch (Throwable ignored) {
                    // API < 30，忽略
                }
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("raise: dump err - " + t);
        }
    }
}
