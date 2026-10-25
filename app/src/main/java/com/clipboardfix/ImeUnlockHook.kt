package com.clipboardfix

import android.content.Context
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import dalvik.system.BaseDexClassLoader
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * 解锁 MIUI 全面屏优化（第三方输入法底部常用语/剪贴板入口）。
 *
 * <p>逻辑参考了公开的 IME 全面屏解锁方案思路，
 * 由 Kotlin 实现改写。只在第三方输入法进程内安装，
 * 小米定制版输入法跳过大部分 hook。
 *
 * <p>键盘「异常增高 / 抬高」的修复策略（2026-09-22 移植参考项目定稿）：
 * <ol>
 *   <li>{@link #hookImeWindowEdgeToEdge} ——让输入法窗口 edge-to-edge。Android 15+ 上
 *      targetSdk<35 的输入法窗口默认不是 edge-to-edge：系统会给 DecorView 内容容器设
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
object ImeUnlockHook {

    /** 小米定制输入法，这些不做 sIsImeSupport / isXiaoAiEnable 处理。 */
    private val MIUI_IME_LIST = arrayOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch",
        "com.xiaomi.type",
    )

    /** 抬高区域背景色（反色）缓存，供 setPhraseBgColor 使用。 */
    @Volatile
    private var navBarColor: Int? = null

    // ---- MIUI 底栏 inset 兼容修复的运行时状态（均为弱引用，避免泄漏）----
    /** 已安装 layout 监听的 inputFrame 集合。 */
    private val MONITORED_INPUT_FRAMES: MutableSet<ViewGroup> =
        Collections.newSetFromMap(java.util.WeakHashMap<ViewGroup, Boolean>())
    /** 内容视图被调整前原始的底部 padding，用于还原。 */
    private val ORIGINAL_CONTENT_BOTTOM_PADDINGS: MutableMap<View, Int> = java.util.WeakHashMap()
    /** fullscreenArea 的原始/目标高度三元组 [base, navInset, target]，用于还原。 */
    private val ORIGINAL_FULLSCREEN_AREA_HEIGHTS: MutableMap<ViewGroup, IntArray> =
        java.util.WeakHashMap()
    /** 当前被调整过的内容视图，按 inputFrame 记录。 */
    private val ADJUSTED_CONTENT_VIEWS: MutableMap<ViewGroup, WeakReference<View>> =
        java.util.WeakHashMap()
    /** 每个 inputFrame 对应的 fullscreenArea / rootView / bottomArea 引用。 */
    private val MIUI_BOTTOM_FRAMES: MutableMap<ViewGroup, MiuiBottomFrame> =
        java.util.WeakHashMap()
    /** 已剥离冗余导航 padding 的视图，按 inputFrame 记录（用于还原）。 */
    private val ADJUSTED_NAV_PADDING_VIEWS: MutableMap<ViewGroup, WeakReference<View>> =
        java.util.WeakHashMap()
    /** 被剥离视图的原始 bottom padding，用于还原。 */
    private val ORIGINAL_NAV_PADDING_BOTTOMS: MutableMap<View, Int> = java.util.WeakHashMap()
    /** 查找冗余导航 padding 宿主时的最大下钻深度。 */
    private const val NAV_PADDING_MAX_DEPTH = 6
    /** 冗余导航 padding 判定容差（px）。 */
    private const val NAV_PADDING_TOLERANCE = 2

    // ---- 键盘下沉校准（按输入法，让按键行贴近 MIUI 底栏图标行）----
    /**
     * 本进程的键盘下沉量（px），在 [init] 里按输入法包名查表并一次性换算好。
     *
     * <p><b>为什么不用「记住包名、用的时候再查表」</b>：同一进程内 LSPosed 会为每个被加载的
     * 包各调一次 [init]——输入法进程里还会加载 `com.google.android.webview`，
     * 包名字段会被后者覆盖，查表随即 miss，下沉时有时无（真机上表现为「切走再切回来尺寸复原」）。
     * 因此这里只在命中校准表时赋值，无关包的 [init] 不会把它清掉。
     */
    @Volatile
    private var sSinkOffsetPx: Float = 0f

    /**
     * Gboard 专用：保留其内容视图自带的底部 padding，不做「剥离冗余导航 padding」。
     *
     * <p>Gboard 的键盘视图底边自带约 1 个导航栏 inset（实测 53px@1080）的底部 padding，
     * 键帽圆角靠这段 padding 才完整显示。若按通用逻辑把它抹成 0，键帽会被整体下移 53px
     * 直接顶到 inputFrame 裁剪线（=MIUI 底栏顶边），圆角被齐切——浅色主题下键帽与底栏
     * 同色系、对比极弱看不出来，深色主题下「键帽 56 / 底 30」对比强，齐切一眼可见。
     * 因此仅对 Gboard 跳过该剥离，保留 padding 让键帽停在裁剪线上方。
     *
     * <p>只赋值不重置：同一进程内 LSPosed 会为非本包（如 webview）再调 init，不能因此清掉。
     */
    @Volatile
    private var sPreserveContentPadding: Boolean = false

    /**
     * 每输入法的键盘下沉量（dp）。
     *
     * <p>第三方输入法的键盘绘制区（自定义 KeyboardView，无子视图）底边与按键行之间存在
     * 死空间（豆包实测 19px@420dpi），叠加 MIUI 底栏图标行居中布局自带的 ~46px 顶部空间，
     * 使「按键行 → 图标行」的视觉间距（豆包实测 65px）明显大于系统输入法（小爱 47px）。
     *
     * <p>校准方式：给键盘内容视图加 `translationY`，让键盘整体下沉，把这段死空间
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
    private val KEYBOARD_SINK_DP: Map<String, Int> = Collections.unmodifiableMap(
        hashMapOf(
            // 校准基准：小米小爱（com.xiaomi.type）按键行→图标行实测 42px（@1080，密度 2.625）。
            // 各输入法实测 gap 减去 42px 即为需下沉的像素数，再换算成 dp。
            "com.bytedance.android.doubaoime" to 5,      // 死空间 25px@1440，收边带距栏顶保留 ~3px margin
            "com.tencent.wetype" to 3,                   // 死空间 20px@1440，收边带距栏顶保留 ~2px margin
            "com.iflytek.inputmethod" to 7,              // gap 61 → 下沉 19px
            "com.sohu.inputmethod.sogou" to 16,          // gap 85 → 下沉 43px
            "com.google.android.inputmethod.latin" to 12, // gap 73 → 下沉 31px
            // 未列入的输入法：
            //  · com.xiaomi.type / com.sohu.inputmethod.sogou.xiaomi —— 小米定制输入法，走
            //    customized 分支不安装底栏 hook，本机制对其不生效。
            //  · com.tencent.qqpinyin —— 其 IME 窗口未 edge-to-edge（实测 inputFrame 高度为 0、
            //    底栏位于 2036..2180 而非屏幕底部），拦不到内容视图，需先修窗口铺满问题。
        )
    )

    /** 被下沉的键盘内容视图 → 原始 translationY（弱键，视图重建后自动失效）。 */
    private val ORIGINAL_CONTENT_TRANSLATIONS: MutableMap<View, Float> = java.util.WeakHashMap()

    /**
     * @param pkg        输入法包名
     * @param cl         默认 classloader
     * @param imeUnlock  全面屏优化解锁开关（控制本类其余全部安装）
     * @param timeUnlock 剪贴板时间限制解锁开关（走 loadDex 回调装读取路径 hook）
     */
    @JvmStatic
    fun init(pkg: String, cl: ClassLoader, imeUnlock: Boolean, timeUnlock: Boolean) {
        // 按输入法包名解析下沉量并固化成像素；未校准的包不改动，避免覆盖已解析的值
        val sinkDp = KEYBOARD_SINK_DP[pkg]
        if (sinkDp != null && sinkDp > 0) {
            val density: Float = try {
                Resources.getSystem().displayMetrics.density
            } catch (t: Throwable) {
                2.625f // 420dpi 兜底
            }
            sSinkOffsetPx = sinkDp * density
        }
        // Gboard：键盘视图自带约 1 个导航栏 inset 的底部 padding（键帽圆角靠它完整显示），
        // 不按通用逻辑剥离（详见 sPreserveContentPadding 字段注释）。仅赋值不重置。
        if (pkg == "com.google.android.inputmethod.latin") {
            sPreserveContentPadding = true
        }
        val isNonCustomize = pkg !in MIUI_IME_LIST
        log(pkg + " start (customized=" + !isNonCustomize
                + ", sdk=" + Build.VERSION.SDK_INT
                + ", device=" + Build.DEVICE
                + ", miui=" + SysProps.get("ro.miui.ui.version.name", "?")
                + ", timeUnlock=" + timeUnlock + ")")

        // 让输入法窗口 edge-to-edge（Android 15+ 上 targetSdk<35 的输入法窗口默认不是
        // edge-to-edge，系统给内容容器设 marginBottom=导航栏高度，导致 MIUI 底栏被整体抬高）。
        if (imeUnlock) {
            hookImeWindowEdgeToEdge()
        }

        if (isNonCustomize && imeUnlock) {
            var injector = Reflect.findClassIfExists(
                    "android.inputmethodservice.InputMethodServiceInjector", cl)
            if (injector == null) {
                injector = Reflect.findClassIfExists(
                        "android.inputmethodservice.InputMethodServiceStubImpl",
                        cl)
            }
            if (injector != null) {
                // 键盘抬高修复统一走 reconcileMiuiBottomFrame（addMiuiBottomView 触发）：
                // 内容视图填满 inputFrame 时把其 bottom padding 减去导航栏 inset，并剥离
                // MIUI 工具栏注入的冗余导航 padding。
                hookSIsImeSupport(injector)
                hookIsXiaoAiEnable(injector)
                setPhraseBgColor(injector, cl)
            } else {
                log("WARN: InputMethodServiceInjector / InputMethodServiceStubImpl not found")
            }
        }

        if (imeUnlock) {
            hookDeleteNotSupportIme(
                    "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
                    cl)
        }

        // loadDex 拦截：imeUnlock 的底栏修复与 timeUnlock 的剪贴板时间 hook 都依赖它
        // （面板/管理类在动态 dex 里，onPackageLoaded 时按名加载必 ClassNotFound）
        if (imeUnlock || timeUnlock) {
            hookModuleManagerLoadDex(cl, isNonCustomize, imeUnlock, timeUnlock)
        }

        log(pkg + " done")
    }

    private fun log(msg: String) {
        XposedInit.log("[IME] " + msg)
    }

    /**
     * 跳过包名检查，直接开启输入法优化。
     *
     * <p>同时做两件事，互为兜底：
     * <ol>
     *   <li>改静态字段 `sIsImeSupport`（可能是 int 也可能是 boolean）——对旧版 ROM 有效；</li>
     *   <li>hook 方法 `isImeSupport()` 恒返回 true——与赋值时机无关，服务重建后依然有效。</li>
     * </ol>
     *
     * <p>第 2 点参照社区方案 v1.17（PR #34「Fix IME support hook after service
     * recreation」）。只改静态字段是一次性赋值：输入法服务重建、类被重新初始化或别处重新
     * 计算该字段时，值会被写回 false，底部常用语入口随之消失且必须重启输入法进程才恢复。
     * 改为拦截判断方法后，任何调用点拿到的恒为 true，不受重置时机影响。
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        val fieldOk = hookSIsImeSupportField(clazz)
        val methodCount = hookIsImeSupportMethod(clazz)
        val clinitOk = hookClassInitToReapply(clazz)
        if (fieldOk || methodCount > 0 || clinitOk) {
            log("OK: ime support on " + clazz.name
                    + " (field=" + fieldOk + ", method=" + methodCount
                    + ", clinit=" + clinitOk + ")")
        } else {
            log("SKIP: neither sIsImeSupport field nor isImeSupport() in " + clazz.name)
        }
    }

    private fun hookSIsImeSupportField(clazz: Class<*>): Boolean {
        return try {
            val f = Reflect.findField(clazz, "sIsImeSupport")
            if (f.type == Boolean::class.java) {
                Reflect.setStaticField(clazz, "sIsImeSupport", true)
            } else {
                Reflect.setStaticField(clazz, "sIsImeSupport", 1)
            }
            true
        } catch (t: Throwable) {
            log("SKIP: field sIsImeSupport on " + clazz.name + " - " + t)
            false
        }
    }

    /**
     * 让目标类里所有 `isImeSupport()` 恒返回 true。
     *
     * <p>沿父类链找，命中所有「无参且返回 boolean / Boolean」的同名方法。
     * 静态、实例、私有方法都 hook，避免 ROM 改动签名后漏掉。
     *
     * @return 成功 hook 的方法数量
     */
    private fun hookIsImeSupportMethod(clazz: Class<*>): Int {
        var count = 0
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            val cc = c
            for (m in cc.declaredMethods) {
                if (m.name != "isImeSupport") continue
                val rt = m.returnType
                if (rt != Boolean::class.java && rt != java.lang.Boolean::class.java) continue
                try {
                    m.isAccessible = true
                    // 系统框架里这类短方法容易被 ART 内联，内联后 hook 不会被调用，先反优化
                    XposedInit.deoptimizeMethod(m)
                    XposedInit.hook(m) { java.lang.Boolean.TRUE }
                    count++
                    log("OK: isImeSupport(" + describeParams(m) + ") on "
                            + cc.simpleName)
                } catch (t: Throwable) {
                    log("FAIL: method isImeSupport on " + cc.name + " - " + t)
                }
            }
            c = cc.superclass
        }
        return count
    }

    /**
     * 类被（重新）初始化后立刻把 support 字段改回来。
     *
     * <p>只在包加载时赋值一次是不够的：类重新初始化、或走动态加载路径稍后才初始化时，
     * `<clinit>` 会把 `sIsImeSupport` 重新算回默认值。
     * 这里挂在静态初始化之后补一刀，覆盖所有初始化时序。
     */
    private fun hookClassInitToReapply(clazz: Class<*>): Boolean {
        return try {
            XposedInit.hookClassInitializer(clazz) { chain ->
                val r = chain.proceed()   // 先跑完原始静态初始化
                hookSIsImeSupportField(clazz) // 再覆盖成我们期望的值
                log("reapplied support flag after <clinit> of " + clazz.simpleName)
                r
            }
            true
        } catch (t: Throwable) {
            log("SKIP: class initializer hook on " + clazz.name + " - " + t)
            false
        }
    }

    /** 把方法参数类型拼成可读字符串，便于在日志里确认签名差异。 */
    private fun describeParams(m: Method): String {
        val ps = m.parameterTypes
        if (ps.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in ps.indices) {
            if (i > 0) sb.append(", ")
            sb.append(ps[i].simpleName)
        }
        return sb.toString()
    }

    /** 小爱语音输入按钮失效修复。 */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        try {
            val m = clazz.getDeclaredMethod("isXiaoAiEnable")
            m.isAccessible = true
            XposedInit.hook(m) { java.lang.Boolean.FALSE }
            log("OK: isXiaoAiEnable on " + clazz.name)
        } catch (t: Throwable) {
            log("FAIL: isXiaoAiEnable on " + clazz.name + " - " + t)
        }
    }

    /** 修复切换输入法列表被裁剪的问题。 */
    private fun hookDeleteNotSupportIme(className: String, classLoader: ClassLoader) {
        try {
            val clazz = Reflect.findClassIfExists(className, classLoader)
            if (clazz == null) {
                log("SKIP: class not found $className")
                return
            }
            var target: Method? = null
            for (m in clazz.declaredMethods) {
                if (m.name == "deleteNotSupportIme") {
                    target = m
                    break
                }
            }
            if (target == null) {
                log("SKIP: no deleteNotSupportIme in $className")
                return
            }
            target.isAccessible = true
            XposedInit.hook(target) { null }
            log("OK: deleteNotSupportIme on $className")
        } catch (t: Throwable) {
            log("FAIL: deleteNotSupportIme on $className - $t")
        }
    }

    /**
     * 常用语模块是从 dex 动态加载的，这里拦截加载过程，
     * 按开关安装：imeUnlock 的底栏/列表修复 + timeUnlock 的剪贴板 72h 过滤旁路。
     *
     * @param imeUnlock  是否安装全面屏优化相关 hook
     * @param timeUnlock 是否安装剪贴板时间限制解锁 hook（读取路径，装在 dex 的 classloader 上）
     */
    private fun hookModuleManagerLoadDex(cl: ClassLoader,
                                         isNonCustomize: Boolean,
                                         imeUnlock: Boolean,
                                         timeUnlock: Boolean) {
        try {
            val managerClass = Reflect.findClassIfExists(
                    "android.inputmethodservice.InputMethodModuleManager", cl)
            if (managerClass == null) {
                log("SKIP: InputMethodModuleManager not found")
                return
            }
            var loadDex: Method? = null
            for (m in managerClass.declaredMethods) {
                val ps = m.parameterTypes
                if (m.name == "loadDex" && ps.size == 2
                        && ClassLoader::class.java.isAssignableFrom(ps[0])
                        && String::class.java == ps[1]) {
                    loadDex = m
                    break
                }
            }
            if (loadDex == null) {
                log("SKIP: loadDex(ClassLoader, String) not found")
                return
            }
            val ld = loadDex
            ld.isAccessible = true

            // 原 before hook 始终 setResult(null) 短路，这里等价于「不 proceed，直接返回 null」
            XposedInit.hook(ld) { chain ->
                val loader = chain.getArg(0) as ClassLoader
                val dexPath = chain.getArg(1) as String

                if (loader !is BaseDexClassLoader) {
                    return@hook null
                }
                // 已经加载过就别重复 hook
                try {
                    Class.forName("com.miui.inputmethod.InputMethodBottomManager", true, loader)
                    return@hook null
                } catch (ignored: ClassNotFoundException) {
                    // 首次加载，继续往下走
                }

                if (!addDexPath(loader, dexPath)) {
                    return@hook null
                }

                if (imeUnlock) {
                    hookDeleteNotSupportIme(
                            "com.miui.inputmethod.InputMethodBottomManager\$MiuiSwitchInputMethodListener",
                            loader)
                }

                val bottom = Reflect.findClassIfExists(
                        "com.miui.inputmethod.InputMethodBottomManager", loader)
                if (bottom != null) {
                    if (imeUnlock) {
                        // 键盘抬高修复：hook addMiuiBottomView，由 reconcileMiuiBottomFrame 在
                        // 内容视图填满 inputFrame、或 MIUI 工具栏注入冗余底部安全区时，把对应视图
                        // 的 bottom padding 减去导航栏 inset，并剥离冗余导航 padding。
                        hookMiuiBottomInsetCompatibility(bottom)
                        if (isNonCustomize) {
                            hookSIsImeSupport(bottom)
                            hookIsXiaoAiEnable(bottom)
                        }
                        hookGetSupportIme(bottom)
                    }
                } else if (imeUnlock) {
                    log("WARN: InputMethodBottomManager not found after addDexPath")
                }
                if (timeUnlock) {
                    // 剪贴板 72h 过滤旁路：面板跑在输入法进程，必须用 dex 的 classloader 装
                    UnlimitHook.hookClipboardTimeLimit(loader)
                }
                return@hook null
            }
            log("OK: InputMethodModuleManager.loadDex")
        } catch (t: Throwable) {
            log("FAIL: hook loadDex - " + t)
        }
    }

    private fun addDexPath(loader: ClassLoader, dexPath: String): Boolean {
        var c: Class<*>? = loader.javaClass
        while (c != null) {
            val cc = c
            try {
                val m = cc.getDeclaredMethod("addDexPath", String::class.java)
                m.isAccessible = true
                m.invoke(loader, dexPath)
                return true
            } catch (e: NoSuchMethodException) {
                c = cc.superclass
            } catch (t: Throwable) {
                log("FAIL: addDexPath - " + t)
                return false
            }
        }
        log("FAIL: addDexPath not found on " + loader.javaClass.name)
        return false
    }

    /** A11 修复切换输入法列表：直接用系统已启用的输入法列表替换。 */
    private fun hookGetSupportIme(bottomClass: Class<*>) {
        try {
            val m = bottomClass.getDeclaredMethod("getSupportIme")
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                try {
                    val helper = Reflect.getStaticField(bottomClass, "sBottomViewHelper")
                    val imm = Reflect.getField(helper, "mImm")
                    return@hook (imm as InputMethodManager).enabledInputMethodList
                } catch (t: Throwable) {
                    log("getSupportIme fallback to original - " + t)
                    return@hook chain.proceed()
                }
            }
            log("OK: getSupportIme on " + bottomClass.name)
        } catch (t: Throwable) {
            log("FAIL: getSupportIme on " + bottomClass.name + " - " + t)
        }
    }

    /** 在合适的时机把抬高区域的背景色改成导航栏颜色的反色。 */
    private fun setPhraseBgColor(injectorClass: Class<*>, classLoader: ClassLoader) {
        try {
            val phoneWindow = Reflect.findClassIfExists(
                    "com.android.internal.policy.PhoneWindow", classLoader)
            if (phoneWindow != null) {
                for (m in phoneWindow.declaredMethods) {
                    val ps = m.parameterTypes
                    if (m.name == "setNavigationBarColor"
                            && ps.size == 1 && ps[0] == Int::class.java) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            val r = chain.proceed()
                            val color = chain.getArg(0) as Int
                            if (color != 0) {
                                navBarColor = color
                                customizeBottomViewColor(injectorClass)
                            }
                            r
                        }
                        log("OK: setNavigationBarColor")
                        break
                    }
                }
            }

            for (m in injectorClass.declaredMethods) {
                if (m.name == "addMiuiBottomView") {
                    m.isAccessible = true
                    XposedInit.hook(m) { chain ->
                        val r = chain.proceed()
                        customizeBottomViewColor(injectorClass)
                        r
                    }
                    log("OK: addMiuiBottomView")
                    break
                }
            }
        } catch (t: Throwable) {
            log("FAIL: setPhraseBgColor - " + t)
        }
    }

    private fun customizeBottomViewColor(clazz: Class<*>) {
        val navBar = navBarColor ?: return
        val color = -1 - navBar
        for (m in clazz.declaredMethods) {
            if (m.name != "customizeBottomViewColor") continue
            val ps = m.parameterTypes
            if (ps.size != 4) continue
            if (ps[0] != Boolean::class.java && ps[0] != java.lang.Boolean::class.java) continue
            if (ps[1] != Int::class.java || ps[2] != Int::class.java || ps[3] != Int::class.java) continue
            try {
                m.isAccessible = true
                m.invoke(null, java.lang.Boolean.TRUE, navBar,
                        color or 0xFF000000.toInt(), color or 0x66000000)
                return
            } catch (t: Throwable) {
                log("FAIL: customizeBottomViewColor invoke - " + t)
                return
            }
        }
        log("SKIP: customizeBottomViewColor(boolean,int,int,int) not found")
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
    private fun hookImeWindowEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val ims = InputMethodService::class.java
            val names = arrayOf("onCreate", "onWindowShown")
            for (name in names) {
                for (m in ims.declaredMethods) {
                    if (m.name != name) continue
                    if (m.parameterTypes.size != 0) continue
                    try {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            val r = chain.proceed()
                            try {
                                val svc = chain.thisObject
                                if (svc is InputMethodService) {
                                    val d = svc.window
                                    if (d != null) {
                                        val w = d.window
                                        if (w != null) {
                                            w.setDecorFitsSystemWindows(false)
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                log("edge2edge err - " + t)
                            }
                            r
                        }
                        log("OK: IME window edge-to-edge hook $name")
                    } catch (t: Throwable) {
                        log("FAIL: edge-to-edge " + name + " - " + t)
                    }
                }
            }
        } catch (t: Throwable) {
            log("FAIL: hookImeWindowEdgeToEdge - " + t)
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
    private fun compensateLegacyWindowInsets(rootView: View, bottomArea: View) {
        val decor = rootView.rootView ?: return
        if (!bottomArea.isShown || decor.height <= 0) return
        val decorLoc = IntArray(2)
        val bottomLoc = IntArray(2)
        decor.getLocationOnScreen(decorLoc)
        bottomArea.getLocationOnScreen(bottomLoc)
        if (bottomLoc[1] + bottomArea.height >= decorLoc[1] + decor.height) return

        val lp = bottomArea.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null && lp.bottomMargin != 0) {
            lp.bottomMargin = 0
            bottomArea.layoutParams = lp
        }
        if (decor is ViewGroup) {
            for (i in 0 until decor.childCount) {
                val child = decor.getChildAt(i)
                if ("navigationBarBackground" == idName(child)) {
                    if (child.visibility != View.GONE) child.visibility = View.GONE
                    continue
                }
                val clp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                if (clp.bottomMargin > 0) {
                    clp.bottomMargin = 0
                    child.layoutParams = clp
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
    private fun hookMiuiBottomInsetCompatibility(clazz: Class<*>) {
        try {
            // hook 静态 addMiuiBottomView(Context, LayoutInflater, ViewGroup, ViewGroup, View, View, ...)
            var target: Method? = null
            for (m in clazz.declaredMethods) {
                if (m.name != "addMiuiBottomView") continue
                if (!Modifier.isStatic(m.modifiers)) continue
                val ps = m.parameterTypes
                if (ps.size < 6) continue
                if (!Context::class.java.isAssignableFrom(ps[0])) continue
                if (!LayoutInflater::class.java.isAssignableFrom(ps[1])) continue
                if (!ViewGroup::class.java.isAssignableFrom(ps[2])) continue
                if (!ViewGroup::class.java.isAssignableFrom(ps[3])) continue
                if (!View::class.java.isAssignableFrom(ps[4])) continue
                if (!View::class.java.isAssignableFrom(ps[5])) continue
                target = m
                break
            }
            val addBottom = target
            if (addBottom != null) {
                addBottom.isAccessible = true
                XposedInit.hook(addBottom) { chain ->
                    val r = chain.proceed()
                    try {
                        val fullscreenArea = chain.getArg(2) as ViewGroup
                        val inputFrame = chain.getArg(3) as ViewGroup
                        val rootView = chain.getArg(4) as View
                        val bottomArea = chain.getArg(5) as View
                        registerMiuiBottomFrame(fullscreenArea, inputFrame, rootView, bottomArea)
                    } catch (t: Throwable) {
                        log("bottom-inset: register err - " + t)
                    }
                    r
                }
                log("OK: addMiuiBottomView inset hook")
            } else {
                log("SKIP: addMiuiBottomView (static,>=6 params) not found in " + clazz.name)
            }

            // 生命周期方法：窗口显示 / 底栏视图变更后重新对齐
            for (m in clazz.declaredMethods) {
                if (m.name != "onWindowShown"
                        && m.name != "changeViewForMiuiBottom") continue
                try {
                    m.isAccessible = true
                    XposedInit.hook(m) { chain ->
                        val r = chain.proceed()
                        try {
                            reconcileCurrentImeFrame(clazz)
                        } catch (t: Throwable) {
                            log("bottom-inset: lifecycle err - " + t)
                        }
                        r
                    }
                    log("OK: " + m.name + " lifecycle hook")
                } catch (t: Throwable) {
                    log("FAIL: " + m.name + " lifecycle hook - " + t)
                }
            }
        } catch (t: Throwable) {
            log("FAIL: hookMiuiBottomInsetCompatibility - " + t)
        }
    }

    private fun registerMiuiBottomFrame(fullscreenArea: ViewGroup,
                                        inputFrame: ViewGroup,
                                        rootView: View,
                                        bottomArea: View) {
        MIUI_BOTTOM_FRAMES[inputFrame] =
                MiuiBottomFrame(fullscreenArea, rootView, bottomArea)
        if (MONITORED_INPUT_FRAMES.add(inputFrame)) {
            inputFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                reconcileMiuiBottomFrame(inputFrame)
            }
        }
        inputFrame.post {
            reconcileMiuiBottomFrame(inputFrame)
        }
    }

    private fun reconcileCurrentImeFrame(clazz: Class<*>) {
        var currentInputFrame: ViewGroup? = null
        try {
            val helper = Reflect.getStaticField(clazz, "sBottomViewHelper")
            currentInputFrame = Reflect.getField(helper, "mInputFrame") as? ViewGroup
        } catch (t: Throwable) {
            currentInputFrame = null
        }
        if (currentInputFrame != null) {
            scheduleReconcile(currentInputFrame)
        } else {
            for (f in MIUI_BOTTOM_FRAMES.keys) {
                scheduleReconcile(f)
            }
        }
    }

    private fun scheduleReconcile(inputFrame: ViewGroup) {
        inputFrame.post {
            reconcileMiuiBottomFrame(inputFrame)
        }
        inputFrame.postDelayed({
            reconcileMiuiBottomFrame(inputFrame)
        }, 100L)
    }

    /** 取 View 的资源 id 名，用于识别 DecorView 里的 navigationBarBackground。 */
    private fun idName(v: View): String {
        return try {
            if (v.id == View.NO_ID) "-" else v.resources.getResourceEntryName(v.id)
        } catch (t: Throwable) {
            "-"
        }
    }

    private fun reconcileMiuiBottomFrame(inputFrame: ViewGroup) {
        val fv = MIUI_BOTTOM_FRAMES[inputFrame] ?: return
        val fullscreenArea = fv.fullscreenArea.get() ?: return
        val rootView = fv.rootView.get() ?: return
        val bottomArea = fv.bottomArea.get() ?: return

        // 兼容 legacy（targetSdk<35）输入法：先修正被系统收缩的窗口内容区与底栏 margin
        compensateLegacyWindowInsets(rootView, bottomArea)
        // 让候选区贴合键盘（部分输入法候选区上方留白会被误判为增高）
        anchorCandidatesAreaToKeyboard(fullscreenArea)

        var contentView: View? = null
        for (i in 0 until inputFrame.childCount) {
            val c = inputFrame.getChildAt(i)
            if (c.visibility == View.VISIBLE) {
                contentView = c
                break
            }
        }

        var navigationInset = 0
        var hasInset = false
        try {
            val ins = rootView.rootWindowInsets
            if (ins != null) {
                var b = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                if (b <= 0) b = ins.systemWindowInsetBottom
                if (b > 0) {
                    navigationInset = b
                    hasInset = true
                }
            }
        } catch (t: Throwable) {
            // 忽略
        }

        val bottomAreaActive = hasInset
                && isBottomAreaActive(rootView, inputFrame, bottomArea, navigationInset)

        // 键盘下沉独立走「底栏几何」判据，不依赖导航 inset：
        // 手势导航下部分机型（实测小米 15 Pro）navigationBars 底部 inset 为 0，而 MIUI 底栏
        // 依旧正常注入，此时沿用上面的 hasInset 门控会让整套机制在这类机型上直接短路。
        val sinkDocked = contentView != null
                && isBarDockedToInputFrame(rootView, inputFrame, bottomArea)
        if (sinkDocked) {
            contentView?.let { applyKeyboardSink(it) }
        } else {
            restoreKeyboardSink()
        }

        if (!bottomAreaActive) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            restoreNavigationPadding(inputFrame)
            // 下沉已按几何判据生效时，不能被下面这条 inset 门控的还原路径清掉：
            // 15 Pro 上 inset=0 会走到这里，而底栏其实照常注入。
            if (!sinkDocked) restoreKeyboardSink()
            return
        }
        if (contentView == null) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            restoreNavigationPadding(inputFrame)
            restoreKeyboardSink()
            return
        }

        // 键盘下沉校准：让键盘整体下沉，消除自带底部死空间，使按键行与底栏图标行的
        // 视觉间距与系统输入法一致（未校准输入法为 0，不干预）
        applyKeyboardSink(contentView)

        val adjustedRef = ADJUSTED_CONTENT_VIEWS[inputFrame]
        val adjustedContentView = adjustedRef?.get()
        if (adjustedRef != null && adjustedContentView != contentView) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
        }

        val originalPadding = ORIGINAL_CONTENT_BOTTOM_PADDINGS[contentView]
        val isCurrentContentAdjusted =
                ADJUSTED_CONTENT_VIEWS[inputFrame]?.get() == contentView
        val isAlreadyAdjusted = isCurrentContentAdjusted
                && originalPadding != null
                && originalPadding == navigationInset
                && contentView.paddingBottom == 0
        val fillsInputFrame = inputFrame.paddingBottom == 0
                && contentView.top == inputFrame.paddingTop
                && contentView.bottom == inputFrame.height
        if (!fillsInputFrame
                || (contentView.paddingBottom != navigationInset && !isAlreadyAdjusted)) {
            if (isCurrentContentAdjusted) restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            // 即便内容视图未填满 inputFrame，也尝试剥离冗余导航 padding（Gboard 跳过）
            if (!sPreserveContentPadding) {
                stripRedundantNavigationPadding(inputFrame, navigationInset)
            }
            return
        }

        if (!ORIGINAL_CONTENT_BOTTOM_PADDINGS.containsKey(contentView)) {
            ORIGINAL_CONTENT_BOTTOM_PADDINGS[contentView] = contentView.paddingBottom
        }
        if (!isAlreadyAdjusted && !sPreserveContentPadding) {
            contentView.setPadding(
                    contentView.paddingLeft,
                    contentView.paddingTop,
                    contentView.paddingRight,
                    contentView.paddingBottom - navigationInset)
        }
        ADJUSTED_CONTENT_VIEWS[inputFrame] = WeakReference(contentView)
        if (!expandFullscreenArea(fullscreenArea, navigationInset)) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
        }
        // 剥离 MIUI 工具栏注入的冗余底部安全区（键盘被抬高的真正根因之一；Gboard 跳过）
        if (!sPreserveContentPadding) {
            stripRedundantNavigationPadding(inputFrame, navigationInset)
        }
    }

    private fun expandFullscreenArea(fullscreenArea: ViewGroup, navigationInset: Int): Boolean {
        val params = fullscreenArea.layoutParams ?: return false
        val previous = ORIGINAL_FULLSCREEN_AREA_HEIGHTS[fullscreenArea]
        val currentHeight = params.height
        val baseHeight: Int
        if (previous == null) {
            baseHeight = currentHeight
        } else if (currentHeight == previous[2] && navigationInset == previous[1]) {
            return true
        } else if (currentHeight == previous[2] || currentHeight == previous[0]) {
            baseHeight = previous[0]
        } else {
            baseHeight = currentHeight
        }
        val targetHeight = if (baseHeight >= 0) {
            baseHeight + navigationInset
        } else {
            fullscreenArea.measuredHeight + navigationInset
        }
        ORIGINAL_FULLSCREEN_AREA_HEIGHTS[fullscreenArea] =
                intArrayOf(baseHeight, navigationInset, targetHeight)
        if (currentHeight != targetHeight) {
            params.height = targetHeight
            fullscreenArea.layoutParams = params
        }
        return true
    }

    private fun restoreMiuiBottomFrame(inputFrame: ViewGroup, fullscreenArea: ViewGroup) {
        val ref = ADJUSTED_CONTENT_VIEWS.remove(inputFrame)
        if (ref != null) {
            val view = ref.get()
            if (view != null) {
                val paddingBottom = ORIGINAL_CONTENT_BOTTOM_PADDINGS.remove(view)
                if (paddingBottom != null && view.paddingBottom == 0) {
                    view.setPadding(
                            view.paddingLeft,
                            view.paddingTop,
                            view.paddingRight,
                            paddingBottom)
                }
            }
        }
        val height = ORIGINAL_FULLSCREEN_AREA_HEIGHTS.remove(fullscreenArea) ?: return
        val params = fullscreenArea.layoutParams ?: return
        if (params.height == height[2]) {
            params.height = height[0]
            fullscreenArea.layoutParams = params
        }
    }

    /**
     * 让候选区贴合键盘：清零 fullscreenArea 的 top padding，并在候选区与底栏之间出现过大间隙
     * （extractArea 被 GONE 掉）时把它改成 INVISIBLE，避免被误判为「键盘增高」。
     */
    private fun anchorCandidatesAreaToKeyboard(fullscreenArea: ViewGroup) {
        if (fullscreenArea.paddingTop != 0) {
            fullscreenArea.setPadding(fullscreenArea.paddingLeft, 0,
                    fullscreenArea.paddingRight, fullscreenArea.paddingBottom)
        }
        var candidatesArea: View? = null
        var extractArea: View? = null
        for (i in 0 until fullscreenArea.childCount) {
            val child = fullscreenArea.getChildAt(i)
            val name = idName(child)
            if ("candidatesArea" == name) {
                if (child is ViewGroup && child.visibility == View.VISIBLE) {
                    candidatesArea = child
                }
            } else if ("extractArea" == name) {
                extractArea = child
            }
        }
        if (candidatesArea != null && extractArea != null) {
            val gap = fullscreenArea.height - candidatesArea.height
            if (candidatesArea.height > 0 && gap > 50) {
                if (extractArea.visibility == View.GONE) {
                    log("bottom-inset: anchor gap=" + gap + " extractArea GONE->INVISIBLE")
                    extractArea.visibility = View.INVISIBLE
                }
            }
        }
    }

    /**
     * 在视图树里（最多下钻 [NAV_PADDING_MAX_DEPTH] 层）查找一个「冗余导航 padding 宿主」：
     * 其 bottom padding 恰好等于导航栏 inset，且其子视图并未填满该 padding 区域——即 MIUI 注入的
     * 重复底部安全区。返回该视图，找不到返回 null。
     */
    private fun findNavigationPaddingOwner(start: View, navigationInset: Int, depth: Int): View? {
        if (depth > NAV_PADDING_MAX_DEPTH) return null
        val group = start as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.paddingBottom != navigationInset) continue
            if (child !is ViewGroup) continue
            if (child.height <= navigationInset) continue
            var maxBottom = 0
            for (j in 0 until child.childCount) {
                val grandChild = child.getChildAt(j)
                if (grandChild.visibility != View.VISIBLE) continue
                maxBottom = Math.max(maxBottom, grandChild.bottom)
            }
            val h = child.height
            val p = child.paddingBottom
            if ((h - p + 2) >= maxBottom) return child
        }
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val r = findNavigationPaddingOwner(child, navigationInset, depth + 1)
            if (r != null) return r
        }
        return null
    }

    /**
     * 剥离 MIUI 工具栏注入的冗余底部安全区：找到 bottom padding 等于导航栏 inset、且其子视图
     * 未填满该区域的视图，把它的 bottom padding 减去导航栏 inset。修复「键盘被抬高」的真正根因之一。
     */
    private fun stripRedundantNavigationPadding(inputFrame: ViewGroup, navigationInset: Int) {
        val adjusted = ADJUSTED_NAV_PADDING_VIEWS[inputFrame]?.get()
        if (adjusted != null) {
            if (adjusted.paddingBottom == 0) return
            ADJUSTED_NAV_PADDING_VIEWS.remove(inputFrame)
            restoreNavigationPaddingTo(adjusted)
        }
        val target = findNavigationPaddingOwner(inputFrame, navigationInset, 0)
        if (target == null) return
        if (!ORIGINAL_NAV_PADDING_BOTTOMS.containsKey(target)) {
            ORIGINAL_NAV_PADDING_BOTTOMS[target] = target.paddingBottom
        }
        ADJUSTED_NAV_PADDING_VIEWS[inputFrame] = WeakReference(target)
        log("bottom-inset: navPadding " + idName(target) + " paddingBottom "
                + target.paddingBottom + " -> " + (target.paddingBottom - navigationInset))
        target.setPadding(target.paddingLeft, target.paddingTop,
                target.paddingRight, target.paddingBottom - navigationInset)
    }

    private fun restoreNavigationPadding(inputFrame: ViewGroup) {
        val ref = ADJUSTED_NAV_PADDING_VIEWS.remove(inputFrame)
        if (ref != null) {
            val v = ref.get()
            if (v != null) restoreNavigationPaddingTo(v)
        }
    }

    private fun restoreNavigationPaddingTo(view: View) {
        val paddingBottom = ORIGINAL_NAV_PADDING_BOTTOMS.remove(view) ?: return
        if (view.paddingBottom != 0) {
            view.setPadding(view.paddingLeft, view.paddingTop,
                    view.paddingRight, paddingBottom)
        }
    }

    // ---- 键盘下沉校准：把键盘整体下沉，使其贴近 MIUI 底栏图标行 ----

    /**
     * 给键盘内容视图加 `translationY`，让键盘整体下沉校准量，消除键盘绘制区自带的
     * 底部死空间，使「按键行 → 底栏图标行」间距与系统输入法一致。
     *
     * <p>用 translation 而非 padding 是刻意的：translation 不触发重新 layout，不会与输入法
     * 自身每次 layout 重设 padding 的行为互相拉锯。幂等；底栏隐藏时由
     * [restoreKeyboardSink] 还原。
     */
    private fun applyKeyboardSink(contentView: View) {
        val offsetPx = sSinkOffsetPx
        if (offsetPx <= 0f) return
        val stored = ORIGINAL_CONTENT_TRANSLATIONS[contentView]
        val orig = stored ?: contentView.translationY
        if (stored == null) {
            ORIGINAL_CONTENT_TRANSLATIONS[contentView] = orig
        }
        val target = orig + offsetPx
        if (contentView.translationY != target) {
            contentView.translationY = target
        }
    }

    /** 还原所有被下沉的键盘内容视图。 */
    private fun restoreKeyboardSink() {
        if (ORIGINAL_CONTENT_TRANSLATIONS.isEmpty()) return
        for ((v, value) in ORIGINAL_CONTENT_TRANSLATIONS) {
            if (v.translationY != value) {
                v.translationY = value
            }
        }
        ORIGINAL_CONTENT_TRANSLATIONS.clear()
    }

    private fun isBottomAreaActive(rootView: View, inputFrame: ViewGroup,
                                   bottomArea: View, navigationInset: Int): Boolean {
        if (!bottomArea.isShown || bottomArea.height < navigationInset) return false
        return isBarDockedToInputFrame(rootView, inputFrame, bottomArea)
    }

    /**
     * MIUI 底栏是否「贴在 inputFrame 正下方、并一直延伸到窗口底边」。
     *
     * <p>纯几何判据，不依赖导航 inset——供键盘下沉单独使用：手势导航下部分机型的
     * `navigationBars` 底部 inset 为 0，但底栏照常注入，此时不能用 inset 判定。
     */
    private fun isBarDockedToInputFrame(rootView: View, inputFrame: ViewGroup,
                                        bottomArea: View): Boolean {
        if (!bottomArea.isShown || bottomArea.height <= 0) return false
        val rootLocation = IntArray(2)
        val inputLocation = IntArray(2)
        val bottomLocation = IntArray(2)
        rootView.getLocationOnScreen(rootLocation)
        inputFrame.getLocationOnScreen(inputLocation)
        bottomArea.getLocationOnScreen(bottomLocation)
        return bottomLocation[1] + bottomArea.height == rootLocation[1] + rootView.height
                && inputLocation[1] + inputFrame.height == bottomLocation[1]
    }

    /** 每个 inputFrame 对应的 fullscreenArea / rootView / bottomArea 弱引用三元组。 */
    private class MiuiBottomFrame(
        fullscreenAreaRef: ViewGroup,
        rootViewRef: View,
        bottomAreaRef: View
    ) {
        val fullscreenArea: WeakReference<ViewGroup> = WeakReference(fullscreenAreaRef)
        val rootView: WeakReference<View> = WeakReference(rootViewRef)
        val bottomArea: WeakReference<View> = WeakReference(bottomAreaRef)
    }
}
