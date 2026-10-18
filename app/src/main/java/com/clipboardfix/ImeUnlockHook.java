package com.clipboardfix;

import android.app.Dialog;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import dalvik.system.BaseDexClassLoader;

/**
 * 解锁 MIUI 全面屏优化（第三方输入法底部常用语/剪贴板入口）。
 *
 * <p>逻辑参考了公开的 IME 全面屏解锁方案思路，
 * 由 Kotlin 实现改写为纯 Java。只在第三方输入法进程内安装，
 * 小米定制版输入法跳过大部分 hook。
 *
 * <p>键盘「异常增高 / 抬高」的修复策略（2026-09-22 移植参考项目定稿）：
 * <ol>
 *   <li>{@link #hookImeWindowEdgeToEdge()} ——让输入法窗口 edge-to-edge。Android 15+ 上
 *      targetSdk&lt;35 的输入法窗口默认不是 edge-to-edge：系统会给 DecorView 内容容器设
 *      marginBottom=导航栏高度并保留一块可见 navigationBarBackground，导致 MIUI 底栏整体
 *      被抬高（实测搜狗官方版离屏底 203px = 内容容器 marginBottom 65 + 底栏 bottomMargin 138）。
 *      改为 setDecorFitsSystemWindows(false)，窗口延伸到屏幕底部。微信输入法自身已是
 *      edge-to-edge（targetSdk 35），故无此问题。</li>
 *   <li>{@link #compensateLegacyWindowInsets} ——兜底：若窗口仍未铺满（legacy 应用
 *      setDecorFitsSystemWindows 不生效），手动归零 MIUI 底栏与 DecorView 内容容器的
 *      bottomMargin 并隐藏 navigationBarBackground，把内容区延伸到底部。</li>
 *   <li>{@link #hookMiuiBottomInsetCompatibility} ——部分输入法（微信 3.5.2、Gboard 等）
 *      把内容视图填满 inputFrame，解锁后键盘顶入导航栏区域而「异常增高」。hook
 *      addMiuiBottomView，检测到内容视图填满 inputFrame 时，将内容视图底部 padding 减去
 *      导航栏 inset，并把 fullscreenArea 高度加回导航栏 inset，把被顶高的空间让还给
 *      MIUI 底栏。底栏隐藏/异常时自动完整还原，无副作用。</li>
 * </ol>
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

    /** 抬高区域背景色（反色）缓存，供 setPhraseBgColor 使用。 */
    private static volatile Integer navBarColor;

    // ---- MIUI 底栏 inset 兼容修复的运行时状态（均为弱引用，避免泄漏）----
    /** 已安装 layout 监听的 inputFrame 集合。 */
    private static final Set<ViewGroup> MONITORED_INPUT_FRAMES =
            Collections.newSetFromMap(new WeakHashMap<ViewGroup, Boolean>());
    /** 内容视图被调整前原始的底部 padding，用于还原。 */
    private static final Map<View, Integer> ORIGINAL_CONTENT_BOTTOM_PADDINGS = new WeakHashMap<>();
    /** fullscreenArea 的原始/目标高度三元组 [base, navInset, target]，用于还原。 */
    private static final Map<ViewGroup, int[]> ORIGINAL_FULLSCREEN_AREA_HEIGHTS = new WeakHashMap<>();
    /** 当前被调整过的内容视图，按 inputFrame 记录。 */
    private static final Map<ViewGroup, WeakReference<View>> ADJUSTED_CONTENT_VIEWS = new WeakHashMap<>();
    /** 每个 inputFrame 对应的 fullscreenArea / rootView / bottomArea 引用。 */
    private static final Map<ViewGroup, MiuiBottomFrame> MIUI_BOTTOM_FRAMES = new WeakHashMap<>();
    /** 已剥离冗余导航 padding 的视图，按 inputFrame 记录（用于还原）。 */
    private static final Map<ViewGroup, WeakReference<View>> ADJUSTED_NAV_PADDING_VIEWS = new WeakHashMap<>();
    /** 被剥离视图的原始 bottom padding，用于还原。 */
    private static final Map<View, Integer> ORIGINAL_NAV_PADDING_BOTTOMS = new WeakHashMap<>();
    /** 查找冗余导航 padding 宿主时的最大下钻深度。 */
    private static final int NAV_PADDING_MAX_DEPTH = 6;
    /** 冗余导航 padding 判定容差（px）。 */
    private static final int NAV_PADDING_TOLERANCE = 2;

    // ---- 键盘下沉校准（按输入法，让按键行贴近 MIUI 底栏图标行）----
    /**
     * 本进程的键盘下沉量（px），在 {@code init} 里按输入法包名查表并一次性换算好。
     *
     * <p><b>为什么不用「记住包名、用的时候再查表」</b>：同一进程内 LSPosed 会为每个被加载的
     * 包各调一次 {@code init}——输入法进程里还会加载 {@code com.google.android.webview}，
     * 包名字段会被后者覆盖，查表随即 miss，下沉时有时无（真机上表现为「切走再切回来尺寸复原」）。
     * 因此这里只在命中校准表时赋值，无关包的 {@code init} 不会把它清掉。
     */
    private static volatile float sSinkOffsetPx;

    /**
     * 每输入法的键盘下沉量（dp）。
     *
     * <p>第三方输入法的键盘绘制区（自定义 KeyboardView，无子视图）底边与按键行之间存在
     * 死空间（豆包实测 19px@420dpi），叠加 MIUI 底栏图标行居中布局自带的 ~46px 顶部空间，
     * 使「按键行 → 图标行」的视觉间距（豆包实测 65px）明显大于系统输入法（小爱 47px）。
     *
     * <p>校准方式：给键盘内容视图加 {@code translationY}，让键盘整体下沉，把这段死空间
     * 让出一部分，缩小按键行与图标行的间距。下沉后被 inputFrame 裁掉的是键盘自带的底部
     * 死空间，按键本体位于裁剪线以上。
     *
     * <p><b>不能把死空间全部吃光</b>：部分输入法（豆包、微信）键盘视图底边自带一条深色
     * 收边带，紧贴按键行下方。若下沉量 = 全部死空间，键帽底边恰好贴在底栏顶边上，收边带
     * 也被推到栏顶线，视觉上像被齐切（小爱无此收边带且背景色与栏融合，同样贴栏也不显）。
     * 因此这两个输入法的下沉量按「保留约 10px 呼吸间隙」折算，取值小于其全部死空间。
     *
     * <p>未列出的输入法为 0——不干预（各键盘死空间不同，须逐个实测校准后追加）。
     */
    private static final Map<String, Integer> KEYBOARD_SINK_DP;
    static {
        Map<String, Integer> m = new java.util.HashMap<>();
        // 校准基准：小米小爱（com.xiaomi.type）按键行→图标行实测 42px（@1080，密度 2.625）。
        // 各输入法实测 gap 减去 42px 即为需下沉的像素数，再换算成 dp。
        m.put("com.bytedance.android.doubaoime", 4);      // 死空间 25px@1440，保留 ~10px 间隙
        m.put("com.tencent.wetype", 3);                   // 死空间 20px@1440，保留 ~9px 间隙
        m.put("com.iflytek.inputmethod", 7);              // gap 61 → 下沉 19px
        m.put("com.sohu.inputmethod.sogou", 16);          // gap 85 → 下沉 43px
        m.put("com.google.android.inputmethod.latin", 12); // gap 73 → 下沉 31px
        // 未列入的输入法：
        //  · com.xiaomi.type / com.sohu.inputmethod.sogou.xiaomi —— 小米定制输入法，走
        //    customized 分支不安装底栏 hook，本机制对其不生效。
        //  · com.tencent.qqpinyin —— 其 IME 窗口未 edge-to-edge（实测 inputFrame 高度为 0、
        //    底栏位于 2036..2180 而非屏幕底部），拦不到内容视图，需先修窗口铺满问题。
        KEYBOARD_SINK_DP = Collections.unmodifiableMap(m);
    }

    /** 被下沉的键盘内容视图 → 原始 translationY（弱键，视图重建后自动失效）。 */
    private static final Map<View, Float> ORIGINAL_CONTENT_TRANSLATIONS = new WeakHashMap<>();

    private ImeUnlockHook() {
    }

    /**
     * @param pkg        输入法包名
     * @param cl         默认 classloader
     * @param imeUnlock  全面屏优化解锁开关（控制本类其余全部安装）
     * @param timeUnlock 剪贴板时间限制解锁开关（走 loadDex 回调装读取路径 hook）
     */
    public static void init(String pkg, ClassLoader cl, boolean imeUnlock, boolean timeUnlock) {
        // 按输入法包名解析下沉量并固化成像素；未校准的包不改动，避免覆盖已解析的值
        Integer sinkDp = KEYBOARD_SINK_DP.get(pkg);
        if (sinkDp != null && sinkDp > 0) {
            float density;
            try {
                density = android.content.res.Resources.getSystem()
                        .getDisplayMetrics().density;
            } catch (Throwable t) {
                density = 2.625f; // 420dpi 兜底
            }
            sSinkOffsetPx = sinkDp * density;
        }
        boolean isNonCustomize = !contains(MIUI_IME_LIST, pkg);
        log(pkg + " start (customized=" + !isNonCustomize
                + ", sdk=" + Build.VERSION.SDK_INT
                + ", device=" + Build.DEVICE
                + ", miui=" + SysProps.get("ro.miui.ui.version.name", "?")
                + ", timeUnlock=" + timeUnlock + ")");

        // 让输入法窗口 edge-to-edge（Android 15+ 上 targetSdk<35 的输入法窗口默认不是
        // edge-to-edge，系统给内容容器设 marginBottom=导航栏高度，导致 MIUI 底栏被整体抬高）。
        if (imeUnlock) {
            hookImeWindowEdgeToEdge();
        }

        if (isNonCustomize && imeUnlock) {
            Class<?> injector = Reflect.findClassIfExists(
                    "android.inputmethodservice.InputMethodServiceInjector", cl);
            if (injector == null) {
                injector = Reflect.findClassIfExists(
                        "android.inputmethodservice.InputMethodServiceStubImpl",
                        cl);
            }
            if (injector != null) {
                // 键盘抬高修复统一走 reconcileMiuiBottomFrame（addMiuiBottomView 触发）：
                // 内容视图填满 inputFrame 时把其 bottom padding 减去导航栏 inset，并剥离
                // MIUI 工具栏注入的冗余导航 padding。
                hookSIsImeSupport(injector);
                hookIsXiaoAiEnable(injector);
                setPhraseBgColor(injector, cl);
            } else {
                log("WARN: InputMethodServiceInjector / InputMethodServiceStubImpl not found");
            }
        }

        if (imeUnlock) {
            hookDeleteNotSupportIme(
                    "android.inputmethodservice.InputMethodServiceInjector$MiuiSwitchInputMethodListener",
                    cl);
        }

        // loadDex 拦截：imeUnlock 的底栏修复与 timeUnlock 的剪贴板时间 hook 都依赖它
        // （面板/管理类在动态 dex 里，onPackageLoaded 时按名加载必 ClassNotFound）
        if (imeUnlock || timeUnlock) {
            hookModuleManagerLoadDex(cl, isNonCustomize, imeUnlock, timeUnlock);
        }

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
     * <p>第 2 点参照社区方案 v1.17（PR #34「Fix IME support hook after service
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
     * 按开关安装：imeUnlock 的底栏/列表修复 + timeUnlock 的剪贴板 72h 过滤旁路。
     *
     * @param imeUnlock  是否安装全面屏优化相关 hook
     * @param timeUnlock 是否安装剪贴板时间限制解锁 hook（读取路径，装在 dex 的 classloader 上）
     */
    private static void hookModuleManagerLoadDex(final ClassLoader cl,
                                                 final boolean isNonCustomize,
                                                 final boolean imeUnlock,
                                                 final boolean timeUnlock) {
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

                if (imeUnlock) {
                    hookDeleteNotSupportIme(
                            "com.miui.inputmethod.InputMethodBottomManager$MiuiSwitchInputMethodListener",
                            loader);
                }

                Class<?> bottom = Reflect.findClassIfExists(
                        "com.miui.inputmethod.InputMethodBottomManager", loader);
                if (bottom != null) {
                    if (imeUnlock) {
                        // 键盘抬高修复：hook addMiuiBottomView，由 reconcileMiuiBottomFrame 在
                        // 内容视图填满 inputFrame、或 MIUI 工具栏注入冗余底部安全区时，把对应视图
                        // 的 bottom padding 减去导航栏 inset，并剥离冗余导航 padding。
                        hookMiuiBottomInsetCompatibility(bottom);
                        if (isNonCustomize) {
                            hookSIsImeSupport(bottom);
                            hookIsXiaoAiEnable(bottom);
                        }
                        hookGetSupportIme(bottom);
                    }
                } else if (imeUnlock) {
                    log("WARN: InputMethodBottomManager not found after addDexPath");
                }
                if (timeUnlock) {
                    // 剪贴板 72h 过滤旁路：面板跑在输入法进程，必须用 dex 的 classloader 装
                    UnlimitHook.hookClipboardTimeLimit(loader);
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
    // 键盘「异常增高 / 抬高」修复（2026-10-14 合并上游键盘抬高修复解法的关键增量）
    //
    // 现象：部分机型（澎湃 OS4 / 小米 17 Pro Max、15 Ultra）上第三方输入法
    //（微信 / 讯飞 / 搜狗 / 豆包 / Gboard）解锁全面屏优化后键盘底部出现多余空白、
    // 键盘整体被顶高 / 增高；小米 13 Pro / 15 Pro 正常。
    //
    // 真实根因（参考项目实证）：Android 15+ 上 targetSdk<35 的输入法窗口不是
    // edge-to-edge，系统给 DecorView 内容容器设 marginBottom=导航栏高度并保留一块
    // 可见 navigationBarBackground，导致 MIUI 底栏被整体抬高；而部分输入法（微信 3.5.2、
    // Gboard 等）又把内容视图填满 inputFrame，把键盘顶入导航栏区域「异常增高」。
    //
    // 修法（2026-10-14 定稿，含上游最新增量）：
    //   1. hookImeWindowEdgeToEdge —— setDecorFitsSystemWindows(false) 让窗口延伸到底部；
    //   2. compensateLegacyWindowInsets —— 兜底归零残留 bottomMargin + 隐藏 navigationBarBackground；
    //   3. hookMiuiBottomInsetCompatibility —— 在 addMiuiBottomView 时，若内容视图填满
    //      inputFrame，把内容视图底部 padding 减导航栏 inset、fullscreenArea 高度加导航栏 inset，
    //      把被顶高的空间让还给 MIUI 底栏；底栏隐藏/异常时自动还原。
    //   4. stripRedundantNavigationPadding —— 在 reconcile 收尾时，于视图树里查找 MIUI 工具栏
    //      注入的「冗余导航 padding 宿主」（bottom padding == 导航栏 inset 且子视图未填满该区域），
    //      把它的 bottom padding 减去导航栏 inset。这是键盘被抬高的真正根因之一，原 ImeInsetsHook
    //      方案未覆盖；anchorCandidatesAreaToKeyboard 先把候选区贴上键盘，避免被误判为增高。
    // 全程弱引用、幂等，仅在真正出现增高时干预，不与输入法 layout 死循环。
    // ---------------------------------------------------------------

    /**
     * 让输入法窗口 edge-to-edge。
     *
     * <p>Android 15+（API 30+）上 targetSdk&lt;35 的应用窗口不是 edge-to-edge：系统会给
     * DecorView 的内容容器设 marginBottom = 导航栏高度，并保留一块可见的 navigationBarBackground。
     * 普通应用没问题，但输入法不同——MIUI 全面屏优化要求内容区一直延伸到屏幕底部，由 MIUI 底栏
     * 占据导航栏位置。内容区被系统收缩后 MIUI 底栏会整体抬高。
     * 微信输入法（targetSdk 35）自身就是 edge-to-edge，所以没有这个问题。
     */
    private static void hookImeWindowEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Class<?> ims = android.inputmethodservice.InputMethodService.class;
            String[] names = {"onCreate", "onWindowShown"};
            for (String name : names) {
                for (Method m : ims.getDeclaredMethods()) {
                    if (!name.equals(m.getName())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    try {
                        m.setAccessible(true);
                        XposedInit.hook(m, chain -> {
                            Object r = chain.proceed();
                            try {
                                Object svc = chain.getThisObject();
                                if (svc instanceof InputMethodService) {
                                    Dialog d = ((InputMethodService) svc).getWindow();
                                    if (d != null) {
                                        Window w = d.getWindow();
                                        if (w != null) {
                                            w.setDecorFitsSystemWindows(false);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                log("edge2edge err - " + t);
                            }
                            return r;
                        });
                        log("OK: IME window edge-to-edge hook " + name);
                    } catch (Throwable t) {
                        log("FAIL: edge-to-edge " + name + " - " + t);
                    }
                }
            }
        } catch (Throwable t) {
            log("FAIL: hookImeWindowEdgeToEdge - " + t);
        }
    }

    /**
     * 兜底补偿：若输入法窗口仍未铺到屏幕底部（系统对 legacy 应用强制收缩内容区，
     * 上面的 setDecorFitsSystemWindows 不生效时），手动把这三处修正回来：
     *   - MIUI 底栏自身的 bottomMargin 归零
     *   - DecorView 内容容器的 bottomMargin 归零（内容区延伸到底部）
     *   - 隐藏 navigationBarBackground（否则它会盖住延伸下来的底栏）
     *
     * <p>只在底栏确实没到 DecorView 底部时执行，正常输入法（如微信）不会进入该分支。
     */
    private static void compensateLegacyWindowInsets(View rootView, View bottomArea) {
        View decor = rootView.getRootView();
        if (decor == null) return;
        if (!bottomArea.isShown() || decor.getHeight() <= 0) return;
        int[] decorLoc = new int[2];
        int[] bottomLoc = new int[2];
        decor.getLocationOnScreen(decorLoc);
        bottomArea.getLocationOnScreen(bottomLoc);
        if (bottomLoc[1] + bottomArea.getHeight() >= decorLoc[1] + decor.getHeight()) return;

        ViewGroup.MarginLayoutParams lp = (bottomArea.getLayoutParams()
                instanceof ViewGroup.MarginLayoutParams)
                ? (ViewGroup.MarginLayoutParams) bottomArea.getLayoutParams() : null;
        if (lp != null && lp.bottomMargin != 0) {
            lp.bottomMargin = 0;
            bottomArea.setLayoutParams(lp);
        }
        if (decor instanceof ViewGroup) {
            ViewGroup dg = (ViewGroup) decor;
            for (int i = 0; i < dg.getChildCount(); i++) {
                View child = dg.getChildAt(i);
                if ("navigationBarBackground".equals(idName(child))) {
                    if (child.getVisibility() != View.GONE) child.setVisibility(View.GONE);
                    continue;
                }
                ViewGroup.MarginLayoutParams clp = (child.getLayoutParams()
                        instanceof ViewGroup.MarginLayoutParams)
                        ? (ViewGroup.MarginLayoutParams) child.getLayoutParams() : null;
                if (clp == null) continue;
                if (clp.bottomMargin > 0) {
                    clp.bottomMargin = 0;
                    child.setLayoutParams(clp);
                }
            }
        }
    }

    /**
     * 修复部分输入法全面屏优化后键盘异常增高的问题。
     *
     * <p>解锁全面屏优化后，MIUI 会把输入法窗口的可用区域扩展到屏幕底部（含导航栏），
     * 并在底部叠加 MIUI 底栏。部分输入法（如微信输入法 3.5.2、Gboard）会把内容视图
     * 填满整个 inputFrame，导致键盘内容顶入底栏/导航栏区域，键盘看起来异常增高。
     *
     * <p>这里检测该情况后，将内容视图底部 padding 减去导航栏 inset，同时把
     * fullscreenArea 高度加回导航栏 inset，把被顶高的空间让还给 MIUI 底栏。
     *
     * @param clazz com.miui.inputmethod.InputMethodBottomManager
     */
    private static void hookMiuiBottomInsetCompatibility(Class<?> clazz) {
        try {
            // hook 静态 addMiuiBottomView(Context, LayoutInflater, ViewGroup, ViewGroup, View, View, ...)
            Method target = null;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"addMiuiBottomView".equals(m.getName())) continue;
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 6) continue;
                if (!Context.class.isAssignableFrom(ps[0])) continue;
                if (!LayoutInflater.class.isAssignableFrom(ps[1])) continue;
                if (!ViewGroup.class.isAssignableFrom(ps[2])) continue;
                if (!ViewGroup.class.isAssignableFrom(ps[3])) continue;
                if (!View.class.isAssignableFrom(ps[4])) continue;
                if (!View.class.isAssignableFrom(ps[5])) continue;
                target = m;
                break;
            }
            if (target != null) {
                target.setAccessible(true);
                XposedInit.hook(target, chain -> {
                    Object r = chain.proceed();
                    try {
                        ViewGroup fullscreenArea = (ViewGroup) chain.getArg(2);
                        ViewGroup inputFrame = (ViewGroup) chain.getArg(3);
                        View rootView = (View) chain.getArg(4);
                        View bottomArea = (View) chain.getArg(5);
                        registerMiuiBottomFrame(fullscreenArea, inputFrame, rootView, bottomArea);
                    } catch (Throwable t) {
                        log("bottom-inset: register err - " + t);
                    }
                    return r;
                });
                log("OK: addMiuiBottomView inset hook");
            } else {
                log("SKIP: addMiuiBottomView (static,>=6 params) not found in " + clazz.getName());
            }

            // 生命周期方法：窗口显示 / 底栏视图变更后重新对齐
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"onWindowShown".equals(m.getName())
                        && !"changeViewForMiuiBottom".equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    XposedInit.hook(m, chain -> {
                        Object r = chain.proceed();
                        try {
                            reconcileCurrentImeFrame(clazz);
                        } catch (Throwable t) {
                            log("bottom-inset: lifecycle err - " + t);
                        }
                        return r;
                    });
                    log("OK: " + m.getName() + " lifecycle hook");
                } catch (Throwable t) {
                    log("FAIL: " + m.getName() + " lifecycle hook - " + t);
                }
            }
        } catch (Throwable t) {
            log("FAIL: hookMiuiBottomInsetCompatibility - " + t);
        }
    }

    private static void registerMiuiBottomFrame(ViewGroup fullscreenArea,
                                                ViewGroup inputFrame,
                                                View rootView,
                                                View bottomArea) {
        MIUI_BOTTOM_FRAMES.put(inputFrame,
                new MiuiBottomFrame(fullscreenArea, rootView, bottomArea));
        if (MONITORED_INPUT_FRAMES.add(inputFrame)) {
            inputFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or_, int ob) {
                    reconcileMiuiBottomFrame(inputFrame);
                }
            });
        }
        inputFrame.post(new Runnable() {
            @Override
            public void run() {
                reconcileMiuiBottomFrame(inputFrame);
            }
        });
    }

    private static void reconcileCurrentImeFrame(Class<?> clazz) {
        ViewGroup currentInputFrame = null;
        try {
            Object helper = Reflect.getStaticField(clazz, "sBottomViewHelper");
            currentInputFrame = (ViewGroup) Reflect.getField(helper, "mInputFrame");
        } catch (Throwable t) {
            currentInputFrame = null;
        }
        if (currentInputFrame != null) {
            scheduleReconcile(currentInputFrame);
        } else {
            for (ViewGroup f : MIUI_BOTTOM_FRAMES.keySet()) {
                scheduleReconcile(f);
            }
        }
    }

    private static void scheduleReconcile(final ViewGroup inputFrame) {
        inputFrame.post(new Runnable() {
            @Override
            public void run() {
                reconcileMiuiBottomFrame(inputFrame);
            }
        });
        inputFrame.postDelayed(new Runnable() {
            @Override
            public void run() {
                reconcileMiuiBottomFrame(inputFrame);
            }
        }, 100L);
    }

    /** 取 View 的资源 id 名，用于识别 DecorView 里的 navigationBarBackground。 */
    private static String idName(View v) {
        try {
            if (v.getId() == View.NO_ID) return "-";
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "-";
        }
    }

    private static void reconcileMiuiBottomFrame(ViewGroup inputFrame) {
        MiuiBottomFrame fv = MIUI_BOTTOM_FRAMES.get(inputFrame);
        if (fv == null) return;
        ViewGroup fullscreenArea = fv.fullscreenArea.get();
        if (fullscreenArea == null) return;
        View rootView = fv.rootView.get();
        if (rootView == null) return;
        View bottomArea = fv.bottomArea.get();
        if (bottomArea == null) return;

        // 兼容 legacy（targetSdk<35）输入法：先修正被系统收缩的窗口内容区与底栏 margin
        compensateLegacyWindowInsets(rootView, bottomArea);
        // 让候选区贴合键盘（部分输入法候选区上方留白会被误判为增高）
        anchorCandidatesAreaToKeyboard(fullscreenArea);

        View contentView = null;
        for (int i = 0; i < inputFrame.getChildCount(); i++) {
            View c = inputFrame.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE) {
                contentView = c;
                break;
            }
        }

        int navigationInset = 0;
        boolean hasInset = false;
        try {
            WindowInsets ins = rootView.getRootWindowInsets();
            if (ins != null) {
                int b = ins.getInsets(WindowInsets.Type.navigationBars()).bottom;
                if (b <= 0) b = ins.getSystemWindowInsetBottom();
                if (b > 0) {
                    navigationInset = b;
                    hasInset = true;
                }
            }
        } catch (Throwable t) {
            // 忽略
        }

        boolean bottomAreaActive = hasInset
                && isBottomAreaActive(rootView, inputFrame, bottomArea, navigationInset);

        // ---- 诊断（临时）：定位「MIUI 底栏图标间歇性被裁」故障，根因确认后整块移除 ----
        diagBottomFrame(inputFrame, fullscreenArea, rootView, bottomArea, contentView,
                navigationInset, bottomAreaActive);

        // 键盘下沉独立走「底栏几何」判据，不依赖导航 inset：
        // 手势导航下部分机型（实测小米 15 Pro）navigationBars 底部 inset 为 0，而 MIUI 底栏
        // 依旧正常注入，此时沿用上面的 hasInset 门控会让整套机制在这类机型上直接短路。
        boolean sinkDocked = contentView != null
                && isBarDockedToInputFrame(rootView, inputFrame, bottomArea);
        if (sinkDocked) {
            applyKeyboardSink(contentView);
        } else {
            restoreKeyboardSink();
        }

        if (!bottomAreaActive) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea);
            restoreNavigationPadding(inputFrame);
            // 下沉已按几何判据生效时，不能被下面这条 inset 门控的还原路径清掉：
            // 15 Pro 上 inset=0 会走到这里，而底栏其实照常注入。
            if (!sinkDocked) restoreKeyboardSink();
            return;
        }
        if (contentView == null) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea);
            restoreNavigationPadding(inputFrame);
            restoreKeyboardSink();
            return;
        }

        // 键盘下沉校准：让键盘整体下沉，消除自带底部死空间，使按键行与底栏图标行的
        // 视觉间距与系统输入法一致（未校准输入法为 0，不干预）
        applyKeyboardSink(contentView);

        WeakReference<View> adjustedRef = ADJUSTED_CONTENT_VIEWS.get(inputFrame);
        View adjustedContentView = adjustedRef != null ? adjustedRef.get() : null;
        if (adjustedRef != null && adjustedContentView != contentView) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea);
        }

        Integer originalPadding = ORIGINAL_CONTENT_BOTTOM_PADDINGS.get(contentView);
        boolean isCurrentContentAdjusted =
                ADJUSTED_CONTENT_VIEWS.get(inputFrame) != null
                        && ADJUSTED_CONTENT_VIEWS.get(inputFrame).get() == contentView;
        boolean isAlreadyAdjusted = isCurrentContentAdjusted
                && originalPadding != null
                && originalPadding.intValue() == navigationInset
                && contentView.getPaddingBottom() == 0;
        boolean fillsInputFrame = inputFrame.getPaddingBottom() == 0
                && contentView.getTop() == inputFrame.getPaddingTop()
                && contentView.getBottom() == inputFrame.getHeight();
        if (!fillsInputFrame
                || (contentView.getPaddingBottom() != navigationInset && !isAlreadyAdjusted)) {
            if (isCurrentContentAdjusted) restoreMiuiBottomFrame(inputFrame, fullscreenArea);
            // 即便内容视图未填满 inputFrame，也尝试剥离冗余导航 padding
            stripRedundantNavigationPadding(inputFrame, navigationInset);
            return;
        }

        if (!ORIGINAL_CONTENT_BOTTOM_PADDINGS.containsKey(contentView)) {
            ORIGINAL_CONTENT_BOTTOM_PADDINGS.put(contentView, contentView.getPaddingBottom());
        }
        if (!isAlreadyAdjusted) {
            contentView.setPadding(
                    contentView.getPaddingLeft(),
                    contentView.getPaddingTop(),
                    contentView.getPaddingRight(),
                    contentView.getPaddingBottom() - navigationInset);
        }
        ADJUSTED_CONTENT_VIEWS.put(inputFrame, new WeakReference<>(contentView));
        if (!expandFullscreenArea(fullscreenArea, navigationInset)) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea);
        }
        // 剥离 MIUI 工具栏注入的冗余底部安全区（键盘被抬高的真正根因之一）
        stripRedundantNavigationPadding(inputFrame, navigationInset);
    }

    private static boolean expandFullscreenArea(ViewGroup fullscreenArea, int navigationInset) {
        ViewGroup.LayoutParams params = fullscreenArea.getLayoutParams();
        if (params == null) return false;
        int[] previous = ORIGINAL_FULLSCREEN_AREA_HEIGHTS.get(fullscreenArea);
        int currentHeight = params.height;
        int baseHeight;
        if (previous == null) {
            baseHeight = currentHeight;
        } else if (currentHeight == previous[2] && navigationInset == previous[1]) {
            return true;
        } else if (currentHeight == previous[2] || currentHeight == previous[0]) {
            baseHeight = previous[0];
        } else {
            baseHeight = currentHeight;
        }
        int targetHeight = baseHeight >= 0
                ? baseHeight + navigationInset
                : fullscreenArea.getMeasuredHeight() + navigationInset;
        ORIGINAL_FULLSCREEN_AREA_HEIGHTS.put(fullscreenArea,
                new int[]{baseHeight, navigationInset, targetHeight});
        if (currentHeight != targetHeight) {
            params.height = targetHeight;
            fullscreenArea.setLayoutParams(params);
        }
        return true;
    }

    private static void restoreMiuiBottomFrame(ViewGroup inputFrame, ViewGroup fullscreenArea) {
        WeakReference<View> ref = ADJUSTED_CONTENT_VIEWS.remove(inputFrame);
        if (ref != null) {
            View view = ref.get();
            if (view != null) {
                Integer paddingBottom = ORIGINAL_CONTENT_BOTTOM_PADDINGS.remove(view);
                if (paddingBottom != null && view.getPaddingBottom() == 0) {
                    view.setPadding(
                            view.getPaddingLeft(),
                            view.getPaddingTop(),
                            view.getPaddingRight(),
                            paddingBottom);
                }
            }
        }
        int[] height = ORIGINAL_FULLSCREEN_AREA_HEIGHTS.remove(fullscreenArea);
        if (height == null) return;
        ViewGroup.LayoutParams params = fullscreenArea.getLayoutParams();
        if (params == null) return;
        if (params.height == height[2]) {
            params.height = height[0];
            fullscreenArea.setLayoutParams(params);
        }
    }

    /**
     * 让候选区贴合键盘：清零 fullscreenArea 的 top padding，并在候选区与底栏之间出现过大间隙
     * （extractArea 被 GONE 掉）时把它改成 INVISIBLE，避免被误判为「键盘增高」。
     */
    private static void anchorCandidatesAreaToKeyboard(ViewGroup fullscreenArea) {
        if (fullscreenArea.getPaddingTop() != 0) {
            fullscreenArea.setPadding(fullscreenArea.getPaddingLeft(), 0,
                    fullscreenArea.getPaddingRight(), fullscreenArea.getPaddingBottom());
        }
        View candidatesArea = null;
        View extractArea = null;
        for (int i = 0; i < fullscreenArea.getChildCount(); i++) {
            View child = fullscreenArea.getChildAt(i);
            String name = idName(child);
            if ("candidatesArea".equals(name)) {
                if (child instanceof ViewGroup && child.getVisibility() == View.VISIBLE) {
                    candidatesArea = child;
                }
            } else if ("extractArea".equals(name)) {
                extractArea = child;
            }
        }
        if (candidatesArea != null && extractArea != null) {
            int gap = fullscreenArea.getHeight() - candidatesArea.getHeight();
            if (candidatesArea.getHeight() > 0 && gap > 50) {
                if (extractArea.getVisibility() == View.GONE) {
                    log("bottom-inset: anchor gap=" + gap + " extractArea GONE->INVISIBLE");
                    extractArea.setVisibility(View.INVISIBLE);
                }
            }
        }
    }

    /**
     * 在视图树里（最多下钻 {@link #NAV_PADDING_MAX_DEPTH} 层）查找一个「冗余导航 padding 宿主」：
     * 其 bottom padding 恰好等于导航栏 inset，且其子视图并未填满该 padding 区域——即 MIUI 注入的
     * 重复底部安全区。返回该视图，找不到返回 null。
     */
    private static View findNavigationPaddingOwner(View start, int navigationInset, int depth) {
        if (depth > NAV_PADDING_MAX_DEPTH) return null;
        if (!(start instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) start;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.getPaddingBottom() != navigationInset) continue;
            if (!(child instanceof ViewGroup)) continue;
            if (child.getHeight() <= navigationInset) continue;
            ViewGroup childGroup = (ViewGroup) child;
            int maxBottom = 0;
            for (int j = 0; j < childGroup.getChildCount(); j++) {
                View grandChild = childGroup.getChildAt(j);
                if (grandChild.getVisibility() != View.VISIBLE) continue;
                int b = grandChild.getBottom();
                maxBottom = Math.max(maxBottom, b);
            }
            int h = child.getHeight();
            int p = child.getPaddingBottom();
            if ((h - p + 2) >= maxBottom) return child;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            View r = findNavigationPaddingOwner(child, navigationInset, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * 剥离 MIUI 工具栏注入的冗余底部安全区：找到 bottom padding 等于导航栏 inset、且其子视图
     * 未填满该区域的视图，把它的 bottom padding 减去导航栏 inset。修复「键盘被抬高」的真正根因之一。
     */
    private static void stripRedundantNavigationPadding(ViewGroup inputFrame, int navigationInset) {
        WeakReference<View> adjustedRef =
                (WeakReference<View>) ADJUSTED_NAV_PADDING_VIEWS.get(inputFrame);
        View adjusted = adjustedRef != null ? adjustedRef.get() : null;
        if (adjusted != null) {
            if (adjusted.getPaddingBottom() == 0) return;
            ADJUSTED_NAV_PADDING_VIEWS.remove(inputFrame);
            restoreNavigationPaddingTo(adjusted);
        }
        View target = findNavigationPaddingOwner(inputFrame, navigationInset, 0);
        if (target == null) return;
        if (!ORIGINAL_NAV_PADDING_BOTTOMS.containsKey(target)) {
            ORIGINAL_NAV_PADDING_BOTTOMS.put(target, target.getPaddingBottom());
        }
        ADJUSTED_NAV_PADDING_VIEWS.put(inputFrame, new WeakReference<>(target));
        log("bottom-inset: navPadding " + idName(target) + " paddingBottom "
                + target.getPaddingBottom() + " -> " + (target.getPaddingBottom() - navigationInset));
        target.setPadding(target.getPaddingLeft(), target.getPaddingTop(),
                target.getPaddingRight(), target.getPaddingBottom() - navigationInset);
    }

    private static void restoreNavigationPadding(ViewGroup inputFrame) {
        WeakReference<View> ref =
                (WeakReference<View>) ADJUSTED_NAV_PADDING_VIEWS.remove(inputFrame);
        if (ref != null) {
            View v = ref.get();
            if (v != null) restoreNavigationPaddingTo(v);
        }
    }

    private static void restoreNavigationPaddingTo(View view) {
        if (view == null) return;
        Integer paddingBottom = (Integer) ORIGINAL_NAV_PADDING_BOTTOMS.remove(view);
        if (paddingBottom == null) return;
        if (view.getPaddingBottom() != 0) {
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), paddingBottom);
        }
    }

    // ---- 键盘下沉校准：把键盘整体下沉，使其贴近 MIUI 底栏图标行 ----

    /**
     * 给键盘内容视图加 {@code translationY}，让键盘整体下沉校准量，消除键盘绘制区自带的
     * 底部死空间，使「按键行 → 底栏图标行」间距与系统输入法一致。
     *
     * <p>用 translation 而非 padding 是刻意的：translation 不触发重新 layout，不会与输入法
     * 自身每次 layout 重设 padding 的行为互相拉锯。幂等；底栏隐藏时由
     * {@link #restoreKeyboardSink()} 还原。
     */
    private static void applyKeyboardSink(View contentView) {
        float offsetPx = sSinkOffsetPx;
        if (offsetPx <= 0f) return;
        Float orig = ORIGINAL_CONTENT_TRANSLATIONS.get(contentView);
        if (orig == null) {
            orig = Float.valueOf(contentView.getTranslationY());
            ORIGINAL_CONTENT_TRANSLATIONS.put(contentView, orig);
        }
        float target = orig.floatValue() + offsetPx;
        if (contentView.getTranslationY() != target) {
            contentView.setTranslationY(target);
        }
    }

    /** 还原所有被下沉的键盘内容视图。 */
    private static void restoreKeyboardSink() {
        if (ORIGINAL_CONTENT_TRANSLATIONS.isEmpty()) return;
        for (Map.Entry<View, Float> e : ORIGINAL_CONTENT_TRANSLATIONS.entrySet()) {
            View v = e.getKey();
            if (v == null) continue;
            if (v.getTranslationY() != e.getValue().floatValue()) {
                v.setTranslationY(e.getValue().floatValue());
            }
        }
        ORIGINAL_CONTENT_TRANSLATIONS.clear();
    }

    // ---- 诊断（临时）：底栏图标被裁故障定位，根因确认后整块移除 ----
    /** 诊断日志节流：上次输出时间与上次签名（签名变化立即输出，否则至多 5s 一条）。 */
    private static long sDiagLastMs = 0L;
    private static String sDiagLastSig = "";

    /**
     * 转储底栏相关几何：内容视图 padding/底边、inputFrame、fullscreenArea、底栏自身
     * padding/高度/直接子级，以及底栏的祖先链（含各自 top/bottom，用于推断谁盖住了图标）。
     */
    private static void diagBottomFrame(ViewGroup inputFrame, ViewGroup fullscreenArea,
                                        View rootView, View bottomArea, View contentView,
                                        int navigationInset, boolean bottomAreaActive) {
        long now = System.currentTimeMillis();
        String sig = (contentView == null ? "n" : contentView.getClass().getSimpleName())
                + "/" + (contentView == null ? -1 : contentView.getPaddingBottom())
                + "/" + inputFrame.getHeight()
                + "/" + bottomArea.getPaddingBottom()
                + "/" + bottomArea.getHeight()
                + "/" + fullscreenArea.getHeight()
                + "/" + bottomAreaActive;
        if (sig.equals(sDiagLastSig) && now - sDiagLastMs < 5000L) {
            return;
        }
        sDiagLastSig = sig;
        sDiagLastMs = now;
        StringBuilder sb = new StringBuilder("diag: inset=").append(navigationInset)
                .append(" active=").append(bottomAreaActive)
                .append(" content=").append(contentView == null ? "null"
                        : contentView.getClass().getSimpleName())
                .append(" padB=").append(contentView == null ? -1 : contentView.getPaddingBottom())
                .append(" bottom=").append(contentView == null ? -1 : contentView.getBottom())
                .append(" inputFrame[h=").append(inputFrame.getHeight())
                .append(",padB=").append(inputFrame.getPaddingBottom())
                .append(",t=").append(inputFrame.getTop())
                .append(",b=").append(inputFrame.getBottom()).append("]")
                .append(" full[h=").append(fullscreenArea.getHeight()).append("]")
                .append(" bar[padB=").append(bottomArea.getPaddingBottom())
                .append(",h=").append(bottomArea.getHeight())
                .append(",t=").append(bottomArea.getTop())
                .append(",b=").append(bottomArea.getBottom()).append("]");
        if (bottomArea instanceof ViewGroup) {
            ViewGroup bar = (ViewGroup) bottomArea;
            int n = Math.min(bar.getChildCount(), 4);
            for (int i = 0; i < n; i++) {
                View c = bar.getChildAt(i);
                sb.append(" bar>").append(c.getClass().getSimpleName())
                        .append("[").append(c.getTop()).append(",").append(c.getBottom())
                        .append(",vis=").append(c.getVisibility()).append("]");
            }
        }
        // 底栏祖先链（各自 top/bottom，推断布局结构与覆盖关系）
        View p = bottomArea;
        int depth = 0;
        while (p != null && depth < 6) {
            sb.append(" ^").append(p.getClass().getSimpleName())
                    .append("[").append(p.getTop()).append(",").append(p.getBottom()).append("]");
            Object parent = p.getParent();
            p = (parent instanceof View) ? (View) parent : null;
            depth++;
        }
        log(sb.toString());
    }

    private static boolean isBottomAreaActive(View rootView, View inputFrame,
                                              View bottomArea, int navigationInset) {
        if (!bottomArea.isShown() || bottomArea.getHeight() < navigationInset) return false;
        return isBarDockedToInputFrame(rootView, inputFrame, bottomArea);
    }

    /**
     * MIUI 底栏是否「贴在 inputFrame 正下方、并一直延伸到窗口底边」。
     *
     * <p>纯几何判据，不依赖导航 inset——供键盘下沉单独使用：手势导航下部分机型的
     * {@code navigationBars} 底部 inset 为 0，但底栏照常注入，此时不能用 inset 判定。
     */
    private static boolean isBarDockedToInputFrame(View rootView, View inputFrame,
                                                   View bottomArea) {
        if (!bottomArea.isShown() || bottomArea.getHeight() <= 0) return false;
        int[] rootLocation = new int[2];
        int[] inputLocation = new int[2];
        int[] bottomLocation = new int[2];
        rootView.getLocationOnScreen(rootLocation);
        inputFrame.getLocationOnScreen(inputLocation);
        bottomArea.getLocationOnScreen(bottomLocation);
        return bottomLocation[1] + bottomArea.getHeight() == rootLocation[1] + rootView.getHeight()
                && inputLocation[1] + inputFrame.getHeight() == bottomLocation[1];
    }

    /** 每个 inputFrame 对应的 fullscreenArea / rootView / bottomArea 弱引用三元组。 */
    private static final class MiuiBottomFrame {
        final WeakReference<ViewGroup> fullscreenArea;
        final WeakReference<View> rootView;
        final WeakReference<View> bottomArea;

        MiuiBottomFrame(ViewGroup fullscreenArea, View rootView, View bottomArea) {
            this.fullscreenArea = new WeakReference<>(fullscreenArea);
            this.rootView = new WeakReference<>(rootView);
            this.bottomArea = new WeakReference<>(bottomArea);
        }
    }
}
