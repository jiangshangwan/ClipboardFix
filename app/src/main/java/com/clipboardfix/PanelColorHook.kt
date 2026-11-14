/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedModuleInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 让剪贴板面板（{@code InputMethodClipboardPhrasePopupView}）的背景色与底部输入法功能栏
 * （{@code InputMethodBottomManager.sBottomView}）保持一致。
 *
 * <p><b>进程归属</b>：面板运行在输入法进程（hld 隔离进程形态，包名见注入点），
 * 不在 {@code com.miui.phrase} 进程。
 *
 * <p><b>关键坑（实测，code 25/26/27 全部因此静默失效）</b>：
 * {@code com.miui.inputmethod.*} 这套类（面板 {@code InputMethodClipboardPhrasePopupView}
 * 与底部栏 {@code InputMethodBottomManager}）并<b>不在进程启动时的 classpath 里</b>——
 * 输入法是通过 {@code InputMethodModuleManager.loadDex(ClassLoader, String)} 动态加载的
 * （见 {@link ImeUnlockHook#hookModuleManagerLoadDex}，它还要 {@code addDexPath} 之后才能解析）。
 * 因此在 {@code onPackageLoaded}（进程刚起）用 {@code param.getDefaultClassLoader()}
 * 按名加载这两个类，必定抛 {@code ClassNotFoundException}。
 *
 * <p><b>本 Hook 的做法</b>：
 * <ol>
 *   <li>面板类<b>继承自 {@code android.widget.PopupWindow}</b>（boot classloader，任何进程都能按名拿到）。
 *       所以进程一起就 hook 框架 {@code PopupWindow} 的 {@code setContentView}/
 *       {@code showAsDropDown}/{@code showAtLocation}，回调里用
 *       {@code getThisObject().getClass().getName()} 与面板类名比对来识别「这是不是我们的面板」。
 *       实例对象自带真实 Class，完全绕过动态 dex 的 classloader 问题。</li>
 *   <li>识别到面板后，从实例取 {@code getClass().getClassLoader()} 存起来——这个 classloader
 *       加载了面板类，同属 {@code com.miui.inputmethod} 的 {@code InputMethodBottomManager}
 *       也能用它解析。据此再<b>懒加载</b>式地 hook 底部栏换色点并取色。</li>
 * </ol>
 *
 * <p>着色用 {@link Drawable#setTint}，只改颜色、保留原 drawable（圆角形状 / MIUI 自定义阴影都在
 * 原 drawable 上，重建就会丢）。颜色优先读底部栏 {@code sBottomView} 的 {@link ColorDrawable}
 * 实色，退回静态字段 {@code currentBottomViewColor}，皆不可得则跳过（保持原样，绝不写非法色值）。
 */
object PanelColorHook {

    private const val PHRASE_POPUP =
            "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val BOTTOM_MANAGER =
            "com.miui.inputmethod.InputMethodBottomManager"
    private const val ID_INSIDE_VIEW = "inside_view"
    private const val POPUP_WINDOW = "android.widget.PopupWindow"

    /** 按名解析到的资源 id 缓存（面板布局来自 {@code com.miui.phrase} 的资源包）。 */
    private val sIdCache: MutableMap<String, Int> = HashMap()

    /**
     * 面板高斯模糊半径。与主流输入法美化模块的默认模糊强度一致（{@code DEFAULT_BLUR_RADIUS=60}），
     * 这样两者的玻璃观感最接近。
     */
    private const val BLUR_RADIUS = 60

    /**
     * 模糊态（玻璃键盘）下浅色主题的条目磨砂底：40% 白。
     *
     * <p>phrase 原生日间值是 {@code #99FFFFFF}（60% 白），叠在亮背景上接近纯白块、
     * 背后的系统模糊被完全挡住——用户 2026-10-23 反馈「条目背景太白，要再透再模糊」。
     * 降到 40% 后模糊层次能透出来，同时保留足够的卡片可读性。
     *
     * <p>与 [RGB_CARD_ON_LIGHT] 的区别：后者是<b>实色</b>分支补的底（补成不透明），
     * 模糊态必须保持半透明磨砂，否则「透明模糊」观感被打回实色（用户 2026-10-23 反馈）。
     */
    private const val RGB_CARD_ON_LIGHT_FROSTED = 0x66FFFFFF

    /** 模糊态下深色主题的条目磨砂底：phrase 原生夜间值 {@code #24FFFFFF}（14% 白）。 */
    private const val RGB_CARD_ON_DARK_FROSTED = 0x24FFFFFF

    /**
     * 深色模式下类型徽标（链接 / 淘宝等圆形底）的目标色。
     *
     * <p>phrase 原生夜间圆底是近灰 {@code #3B3E43}，叠在深色卡片上比卡片还暗（用户 2026-10-25
     * 反馈「深色下太深」）；而 [RGB_CARD_ON_DARK_FROSTED]（14% 白）又太淡，几乎看不见。
     * 这里取更亮一档的半透明白 {@code #40FFFFFF}（25% 白），使徽标呈「明显比卡片亮的柔和圆」，
     * 既不死黑也不刺眼。浅色模式保持 phrase 原生观感，不动。
     */
    private const val RGB_BADGE_ON_DARK = 0x40FFFFFF

    /**
     * 系统明暗轮询间隔。
     *
     * <p>HyperOS 没有可靠透给模块的 configuration 回调：phrase 弹窗视图的 context 是
     * {@code createPhraseThemeContext} 充气时拷的快照，系统切换深浅色后它不更新；原生
     * 刷新入口 {@code setPhraseBackgroundDarkTheme} 又只有 {@code showClipBoard} 一个
     * 调用方。只能自己按间隔看一次（详见 [startThemeWatch]）。
     */
    private const val THEME_WATCH_MS = 250L

    /**
     * 底栏合成色复核间隔。底栏的最终观感由窗口背后内容与材质层合成，颜色会随场景变化，
     * 因此按固定节拍重新采样；两次之间设最小间隔，避免窗口 surface 被高频读取。
     */
    private const val PROBE_INTERVAL_MS = 900L

    /** 主题轮询中每隔多少拍触发一次复核。 */
    private const val PROBE_HEARTBEAT = 8

    /** 视图树扫描的结点上限与分层日志上限，键盘视图较深时及时收敛。 */
    private const val PROBE_VIEW_LIMIT = 180
    private const val PROBE_LOG_LIMIT = 20

    /** 采样位图边长；只需统计区域主色，不需要全分辨率。 */
    private const val PROBE_BITMAP_PX = 16

    /** 采样结果有效像素的最低占比，低于此值视为该区域未绘制出内容。 */
    private const val PROBE_MIN_COVERAGE = 0.30f

    /** 结构版本标记。 */
    private const val PROBE_SCHEMA = "pc" + "26" + "b3"

    /**
     * 采样色与面板原生底色之间允许的最小通道差。底栏观感由模糊与材质混合产生，若采样结果
     * 落在原生底色附近，说明读到的是窗口自身底色而非底栏合成色，此时不应覆盖面板。
     */
    private const val COMPOSITE_MIN_DELTA = 12

    /**
     * 材质参数未观测到时使用的等效混合层：深色压暗、浅色提亮。
     * 叠在系统背景模糊之上，颜色仍随窗口背后内容变化，不固化为某个 ARGB。
     */
    private val DEFAULT_DARK_BLEND: Int = 0x99141414.toInt()
    private val DEFAULT_LIGHT_BLEND: Int = 0x66FFFFFF

    /**
     * 半透玻璃叠色的不透明度系数（阈值 0.60，基准色标 GL-7F2C）。
     *
     * <p>关闭「启用高级材质背景」后走裸模糊玻璃路径，叠色在 [barLikeTint] 给出的同源
     * 混合色之上再整体压低 alpha（RGB 色相不动），使玻璃更透、背后模糊层更明显。
     * 等效把默认深色 60% 压到约 36%、浅色 40% 压到约 24%；全透明叠色保持裸模糊不变。
     */
    private const val GLASS_TINT_ALPHA = 0.6f

    /** 材质配置结构版本标记。 */
    private const val MATERIAL_SCHEMA = "hm" + "26" + "v5"

    /** 窗口采样准入配置结构版本标记。 */
    private const val ADMISSION_SCHEMA = "aw" + "26" + "s5"

    /** 材质配置在视图尚未挂载等暂时状态下允许的重试次数。 */
    private const val NATIVE_RETRY_LIMIT = 6

    /**
     * v6.1：面板系统跟随与「输入法收起」解耦。
     *
     * 早期 v6 调框架级 [android.inputmethodservice.InputMethodServiceInjector.setHyperMaterialEnabled]，
     * 该总开关会令 phrase 内部 hideInputArea() 收起键盘，使底栏失去依托变透明（用户实测现象）。
     * 现改为只驱动 phrase 面板自身：对内容适配器调 setIsHyperMaterial(1)（仅作用于面板，不复用全局
     * sHyperMaterialEnabled，不触发 hideInputArea），再调 setMiuiPhraseThemeFollowInputMethod(darkTheme)
     * 令面板材质跟随输入法 / 系统主题。框架总开关保持关闭，输入法键盘不收起，底栏始终有依托、保持
     * 不透明；面板材质仍由本模块 applyNativeMaterial 的 Miui 模糊接口绘制。
     */
    private const val HYPER_MATERIAL_V6 = true
    private var sHyperMaterialReady = false
    private var sHyperMaterialTried = false

    /** 当前显示的面板内容容器，供换色时同步刷新（面板消失即被回收）。 */
    @Volatile
    private var sPanelBodyRef: WeakReference<View> = WeakReference(null)

    /**
     * 当前显示的面板弹窗实例（弱引用）：系统深浅色切换时借它调 phrase 原生的
     * {@code setPhraseBackgroundDarkTheme} 刷新入口（详见 [refreshPhraseTheme]）。
     */
    @Volatile
    private var sPopupRef: WeakReference<Any> = WeakReference(null)

    /** 最近一次观测到的<b>系统</b>深色模式，供轮询发现「切换了深浅色」。 */
    @Volatile
    private var sLastNight = false

    /** 模糊态下前景（磨砂卡片 / 文字 / 图标）正在跟随的系统明暗。 */
    @Volatile
    private var sBlurNight = false

    /** 系统明暗轮询是否在跑（面板不可见即自停，下次开面板由 [startThemeWatch] 重启）。 */
    @Volatile
    private var sThemeWatch = false

    /**
     * 面板当前是深色底还是浅色底（由实际背景亮度决定，不是系统 uiMode）。
     * 文字对比守卫按它决定「该把文字翻到哪一侧」。
     */
    @Volatile
    private var sContrastDark = false

    /** 已挂过 pre-draw 守卫的面板容器（弱引用集合，视图回收即自动移除）。 */
    private val sContrastWatched: MutableMap<View, Boolean> = java.util.WeakHashMap()

    /**
     * 记录「本模块上一次写进 TextView 的颜色」。
     *
     * <p><b>为什么不用 {@code View.setTag(int,Object)}</b>：该 API 要求 key 是
     * 「应用资源 id」（高字节为包标识），而 {@code View.generateViewId()} 生成的是
     * 0x00xxxxxx，直接抛 {@code IllegalArgumentException: The key must be an
     * application-specific resource id}，会把整个上色流程打断（实测踩过）。
     * 也不用 {@code setTag(Object)}——RecyclerView 自己要用单槽 tag。
     * 这里用弱键表记账，视图回收即自动清理。
     */
    private val sContrastApplied: MutableMap<TextView, Int> = java.util.WeakHashMap()

    /**
     * 图标原色（每个 ImageView 只测一次）。
     *
     * <p>图标颜色烘焙在 drawable 里（{@code android:src} 的矢量图，没有 tint 列表），
     * 只能靠「把 drawable 画进一张小位图再采样」得到。采样必须发生在写入 ColorFilter
     * <b>之前</b>并缓存——否则采样到的是我们自己染过的色，下一帧又会翻回去，形成
     * 一帧一翻的闪烁。视图回收即自动清理。
     */
    private val sIconOriginal: MutableMap<ImageView, Int> = java.util.WeakHashMap()

    /** 本模块已写入的图标 ColorFilter 色，用于幂等与主题切换时撤销。 */
    private val sIconApplied: MutableMap<ImageView, Int> = java.util.WeakHashMap()

    /** 对比守卫日志限流（每次面板上色重置）。 */
    @Volatile
    private var sContrastLogLeft = 0

    /** 深色底上的文字色（纯白，保留原 alpha）。 */
    private const val RGB_TEXT_ON_DARK = 0x00FFFFFF

    /** 浅色底上的文字色（近似 MIUI 主文字色，保留原 alpha）。 */
    private const val RGB_TEXT_ON_LIGHT = 0x001A1A1A

    /**
     * 浅色<b>实色</b>面板上的条目卡片底（模块合成的近白实色）。
     *
     * <p>卡片背景同样来自 phrase 资源的 {@code -night} 限定：日间原生是 60% 白磨砂
     * {@code #99FFFFFF}（见 [RGB_CARD_ON_LIGHT_FROSTED]）、夜间是半透明白
     * （实测约 12% alpha），叠在本模块涂成浅色的面板底上只剩 {@code #E5E6EA}，与底色
     * {@code #E1E2E6} 几乎无差，条目看起来"糊在背景里"；实色分支下无法保留磨砂
     * （那正是模糊分支的职责），故合成一个不透明近白色把卡片"提"出来。
     */
    private val RGB_CARD_ON_LIGHT = 0xFFF3F3F5.toInt()

    /**
     * 条目标题行「类型徽标」容器的 view id（phrase 资源 {@code clipboard_type_layout}）。
     *
     * <p><b>为什么要精确接管这一个视图</b>：链接 / 淘宝条目右侧那枚圆形底，是容器的
     * {@code android:background}（一个不透明的近灰圆，日间固定灰、夜间深灰，跟随<b>系统</b>
     * uiMode），而不是图标 {@code src} 的一部分。[guardCardBackground] 只重涂
     * 「白系 + 半透明」图层，这个不透明近灰圆两层判据都不满足，于是两个分支都跳过它——
     * 磨砂面板上它始终是一枚实色贴片，与半透明的磨砂条目卡片割裂（用户 2026-10-25 反馈）。
     *
     * <p>id 取自 phrase 5.7.4(10274) 的 {@code type_icon} / {@code clipboard_type_layout}
     * 结构；若 phrase 升级导致资源 id 变化，本判据自动失配、该重涂静默停用（绝不误染别的视图）。
     */
    private const val ID_TYPE_BADGE = 0x7F0A00DF

    /**
     * 本模块重涂过的卡片背景 drawable → 写入的颜色。
     *
     * <p>键用 <b>drawable 实例</b>而非视图：RecyclerView 复用同一个条目 View，但 adapter
     * 每次 bind 都会 {@code setBackground} 一个新 drawable 实例——按视图记账会漏涂新
     * drawable，按 drawable 记账才能既幂等（同实例同色跳过）又覆盖新实例；主题切换时
     * 目标色一变也自然重涂。drawable 被回收即自动清理。
     */
    private val sCardOurs: MutableMap<Drawable, Int> = java.util.WeakHashMap()

    /**
     * 本模块上次写入 [ID_TYPE_BADGE] 圆底 background 的磨砂目标色（按<b>视图</b>记账）。
     *
     * <p>键用视图而非 drawable 实例：RecyclerView 复用 ViewHolder 时视图对象不变、但其
     * background drawable 常被 {@code setBackground} 替换成新实例（并丢失我们挂的 ColorFilter）。
     * 按视图记账 + 每帧比对「当前 bg 是否还带着 PorterDuffColorFilter」即可自愈——phrase 替换
     * 圆底或重置 filter 后，下一帧会被自动纠回，无需关面板重开（用户 2026-10-25 反馈）。
     */
    private val sBadgeTarget: MutableMap<View, Int> = java.util.WeakHashMap()

    /** 当前正在上色的面板根容器（根背景由 [applyPanelBackground] 负责，不参与补底）。 */
    @Volatile
    private var sCurrentBody: View? = null

    /** 宿主（输入法）包名，用于限定跨窗口模糊的放行范围。 */
    @Volatile
    private var sHostPkg: String? = null

    /** 进程默认 classloader（进程启动时 MIUI 输入法类还没加载，只能作为兜底）。 */
    @Volatile
    private var sDefaultCl: ClassLoader? = null

    /** 面板实例所在的 classloader（首次识别到面板时取得，能解析 com.miui.inputmethod.*）。 */
    @Volatile
    private var sPanelCl: ClassLoader? = null

    /**
     * 面板自身的原始底色（在上色之前抓取一次）。第三方美化模块会把底部功能区颜色强改成
     * 全透明，连累面板也变透明；此时用这个原始不透明色兜底，保证面板始终可读。
     *
     * <p><b>必须按明暗主题分开缓存</b>：面板自带底色浅色为 {@code #F4F6F9}、深色为
     * {@code #101010}，只存一个的话切到浅色模式仍会拿深色值，面板会被涂成深灰（实测踩过）。
     */
    @Volatile
    private var sFallbackLight = -1
    @Volatile
    private var sFallbackDark = -1

    /** 最近一次系统下发给底部功能区的参数（{@code xxx(ZIII)}），面板照抄同款底色。 */
    @Volatile
    private var sBarBgColor = -1
    @Volatile
    private var sBarContentColor = -1
    @Volatile
    private var sBarAlphaColor = -1

    /** 功能区背景结构只转储一次，供排查「面板与功能区有色差」用。 */
    @Volatile
    private var sBarDumped = false

    /** 最近一次采样时刻（单调时钟）。 */
    @Volatile
    private var sProbeAt = 0L

    /** 是否有一次像素拷贝仍在进行，防止重入。 */
    @Volatile
    private var sProbePending = false

    /** 底栏区域的等效合成色；-1 表示尚无可用结果。 */
    @Volatile
    private var sCompositeColor = -1

    /** 合成色的取得方式，仅用于运行期排查。 */
    @Volatile
    private var sCompositeFrom = "none"

    /**
     * 系统材质下发的背景混合色，按明暗分别缓存。
     *
     * <p>底栏的最终观感由「窗口背后内容模糊 + 这层混合色」共同决定，颜色本身不落在任何
     * View 的 background 上，从参数链路或视图树都取不到，只能从框架下发处观测。
     */
    @Volatile
    private var sBlendDark = -1
    @Volatile
    private var sBlendLight = -1

    /** 液态玻璃材质下发的 tint；存在时优先于普通混合色。 */
    @Volatile
    private var sGlassTint = -1

    /** 最近一次模糊层叠加色的来源，仅用于运行期排查。 */
    @Volatile
    private var sTintFrom = "none"

    /** 材质接口被调用的次数，用于判断取色链路是否真的在工作。 */
    @Volatile
    private var sMaterialCalls = 0

    /** 系统材质接口方法表；空表表示已探测过且本机不可用。 */
    @Volatile
    private var sMaterialMethods: Map<String, Method>? = null

    /** 已挂系统材质的面板视图与当时的明暗，主题或视图变化后需重新配置。 */
    @Volatile
    private var sNativeBodyRef: WeakReference<View> = WeakReference(null)
    @Volatile
    private var sNativeNight = false

    /**
     * 仅当框架材质接口在本机确实不存在时置位；视图未挂载、窗口状态尚未就绪等
     * 暂时性失败不在此列，否则一次早期回调就会让后续所有面板都停在降级路径。
     */
    @Volatile
    private var sNativeUnavailable = false

    /** 暂时性失败累计次数，超过上限才停止继续尝试，避免每次开面板反复抛异常。 */
    @Volatile
    private var sNativeAttempts = 0

    /** 最近一次材质配置的参数摘要，仅用于运行期排查。 */
    @Volatile
    private var sNativeDetail = "none"

    /** 框架记录的跨窗口采样开关状态；null 表示本机没有对应字段。 */
    @Volatile
    private var sPassBlurState: Boolean? = null

    /**
     * 配置材质期间对窗口采样准入的临时放行标记。面板内容视图的 context 与宿主窗口
     * 可能分属不同包，按包名静态匹配会漏放；仅在设置材质的同步调用栈内生效。
     */
    private val sBlurAdmission = ThreadLocal<Boolean>()

    /** 视图层结构只在首次扫描时完整输出一次。 */
    @Volatile
    private var sProbeDumped = false

    /** 面板自身圆角（第一次上色前抓取，用于给模糊层切同样的圆角）。 */
    @Volatile
    private var sPanelRadius = -1f

    /** 防止同一进程内重复挂 hook（onPackageLoaded 可能因多 classloader 多次回调）。 */
    @Volatile
    private var sInitDone = false

    /** 底部栏换色点是否已懒挂（要等面板出现、拿到正确 classloader 后才能挂）。 */
    @Volatile
    private var sBottomHooked = false

    /**
     * 剪贴板面板的「主题上下文」纠偏 hook 是否已挂上。
     *
     * <p>面板类（{@code com.miui.inputmethod.*}）是 {@code InputMethodModuleManager.loadDex}
     * 动态加载的，必须等 dex 落地才能按名解析，所以由 loadDex 回调负责安装。
     */
    @Volatile
    private var sPhraseHooked = false

    /**
     * 本模块最近一次实际涂到面板上的不透明底色（-1 表示尚无/走了模糊分支）。
     *
     * <p>面板的 Tab 选中胶囊、导航条底色都取自 phrase 资源的 {@code -night} 限定，跟随
     * <b>系统</b> uiMode；而面板底色被本模块改成跟随<b>输入法底栏</b>。系统深色 + 输入法
     * 浅色时胶囊会取夜间那种极淡的白（实测与浅底几乎同色），选中态直接消失。见
     * [phraseThemeOverride]。
     */
    @Volatile
    private var sAppliedPanelColor = -1

    /**
     * 涂 [sAppliedPanelColor] 时的系统明暗（staleness 令牌）。
     *
     * <p>面板关闭期间用户在设置里切过深浅色后，上次涂的底色对应的主题上下文已过期，
     * 不能再拿去强制 [phraseThemeOverride]——否则第一次打开面板时条目视图会整套按
     * 旧主题充气，条目背景/图标停在旧主题，要关开一次才恢复（用户 2026-10-25 反馈）。
     */
    @Volatile
    private var sAppliedNight = false

    /** 最近一次捕获底栏色参数时的系统明暗（同样是 staleness 令牌）。 */
    @Volatile
    private var sBarNight = false

    /** 应用级 context：UiModeManager/进程 configuration 实时明暗判定用（不随面板快照过期）。 */
    @Volatile
    private var sAppCtx: Context? = null

    /**
     * 面板当前是否处于「模糊背景」状态（底部栏被第三方强改成全透明时的分支）。
     *
     * <p><b>为什么需要这个标志</b>：模糊分支只负责给面板套系统高斯模糊，前景（文字/图标/
     * 条目卡片）必须全部交还原生——phrase 自带的卡片资源就是为模糊态设计的磨砂
     * （浅色 {@code #99FFFFFF} / 深色 {@code #24FFFFFF}，见
     * {@code input_method_clipboard_phrase_item_bg}），任何补底或明暗纠偏都会把
     * 「透明模糊」打回实色（用户 2026-10-23 反馈：深浅色下条目与面板背景均失去透明模糊）。
     *
     * <p>实色分支（面板被涂成不透明底色）才需要纠偏：那时夜间半透明白卡片会与浅色实底
     * 糊成一片、系统夜间文字会白底白字，二者都以「面板有实色底」为前提，模糊态下前提
     * 不成立（面板观感由裸模糊决定，没有可判明暗的实色底）。
     *
     * <p>该标志同时把 pre-draw 守卫回调分流到 [blurWalk]（面板从实色切回模糊时，
     * 旧监听器仍会每帧回调 [contrastWalk]）：平时什么都不做，唯独在系统切换过
     * 深浅色后按当前主题重涂磨砂卡片与文字（见 [applyBlurForeground]）。
     */
    @Volatile
    private var sBlurPanel = false

    @JvmStatic
    fun init(param: XposedModuleInterface.PackageLoadedParam) {
        if (sInitDone) {
            return
        }
        val cl: ClassLoader
        try {
            cl = param.defaultClassLoader
        } catch (t: Throwable) {
            log("init: no classloader - $t")
            return
        }
        // 先置位，避免同进程内 onPackageLoaded 多次回调导致重复挂 hook。
        sInitDone = true
        sDefaultCl = cl
        sHostPkg = param.packageName
        log("init enter: pkg=$sHostPkg")
        ensurePassWindowBlur()
        ensureMaterialTintHook()
        // 进程启动即解析一次，日志缓冲被淘汰后仍可据此判断材质链路是否可用
        resolveMaterialMethods()
        log("material bootstrap: schema=" + MATERIAL_SCHEMA
                + " admission=" + ADMISSION_SCHEMA
                + " methods=" + (sMaterialMethods?.size ?: -1))
        // 面板 Tab 选中胶囊底色的纠偏 hook 装在动态 dex 的类上，必须拦截 loadDex 抢在
        // 首次开面板（showClipBoard→createPhraseThemeContext）之前挂上。
        hookModuleManagerLoadDex()
        // 动态 dex 要等 IME 起来才加载，此处有界重试，尽量在键盘首次配置颜色前挂上取色点。
        scheduleBottomHookRetry()

        // 框架 PopupWindow 内容/显示点：按实例类名识别面板，无需按名加载面板类
        // （面板类的 dex 此刻尚未加载，Class.forName 必失败——这是前几版失效的根因）。
        try {
            val pwCls = Reflect.findClass(POPUP_WINDOW, cl)
            hookPopupLifecycle(pwCls)
            log("OK: hooked framework PopupWindow content/show")
        } catch (t: Throwable) {
            log("WARN: PopupWindow hook - $t")
        }
        // 底部栏换色点留到识别到面板后懒挂（见 ensureBottomColorHook）——
        // 此刻 InputMethodBottomManager 同样还没被 loadDex 加载进来。
    }

    /**
     * 放行 HyperOS 的「跨窗口背景模糊」准入。
     *
     * <p>HyperOS 在 {@code android.view.ViewRootImplStub.isPassWindowBlurAllowed(String)} 里
     * 校验包是否有权采样窗口背后的内容；未通过时 {@code createBackgroundBlurDrawable()} 虽能创建
     * drawable，却不会真的产生模糊（只剩一层底色，观感等于半透明）。这里只对本输入法包名放行，
     * 与主流美化模块的做法一致；类不存在（非 HyperOS）则静默跳过。
     */
    private fun ensurePassWindowBlur() {
        try {
            val loaders = arrayOf(
                    ClassLoader.getSystemClassLoader(), View::class.java.classLoader)
            var stubCls: Class<*>? = null
            for (cl in loaders) {
                if (cl == null) {
                    continue
                }
                try {
                    stubCls = Class.forName("android.view.ViewRootImplStub", true, cl)
                    break
                } catch (ignored: Throwable) {
                    // 不同版本的框架把准入类挂在不同的加载器下，逐个尝试
                }
            }
            if (stubCls == null) {
                log("pass-window blur: admission stub absent, schema=" + ADMISSION_SCHEMA)
                return
            }
            val stub = stubCls.getMethod("getInstance").invoke(null)
            val isAllowed = stub.javaClass.getMethod(
                    "isPassWindowBlurAllowed", String::class.java)
            XposedInit.hook(isAllowed) { chain ->
                val arg = chain.getArg(0)
                if (java.lang.Boolean.TRUE == sBlurAdmission.get()) {
                    return@hook java.lang.Boolean.TRUE
                }
                if (sHostPkg != null && sHostPkg == arg) {
                    return@hook java.lang.Boolean.TRUE
                }
                return@hook chain.proceed() // 其余包名保持系统原本判定
            }
            log("OK: pass-window blur allowed for $sHostPkg schema=" + ADMISSION_SCHEMA)
        } catch (t: Throwable) {
            log("pass-window blur hook skipped - $t")
        }
    }

    /**
     * 观测系统材质下发的背景混合色。
     *
     * <p>底栏背后是系统模糊，颜色不写在 View 上：输入法把混合色通过框架的材质接口交给
     * 渲染层。这里只读取调用参数并按明暗缓存，不改变任何调用结果，也不依赖具体输入法实现。
     */
    private fun ensureMaterialTintHook() {
        val methods = try {
            View::class.java.declaredMethods
        } catch (t: Throwable) {
            log("material tint: View methods unavailable - $t")
            return
        }
        val available = ArrayList<String>()
        for (m in methods) {
            val n = m.name
            if (n.contains("MiBackgroundBlend") || n.contains("MiGlass")
                    || n.contains("BlendColor")) {
                available.add(n)
            }
        }
        var hooked = 0
        for (m in methods) {
            if (m.name == "setMiBackgroundBlendColors" && hookBlendColors(m)) {
                hooked++
            } else if (m.name == "setMiGlass" && hookGlass(m)) {
                hooked++
            }
        }
        log("material tint hooks: $hooked available=" + available.toString())
    }

    /** 从混合色列表里取出普通叠加层；高光类效果层不参与面板配色。 */
    private fun hookBlendColors(m: Method): Boolean {
        return try {
            XposedInit.hook(m) { chain ->
                try {
                    val arg = chain.getArg(0)
                    if (arg is List<*>) {
                        var best = -1
                        var bestAlpha = -1
                        for (item in arg) {
                            if (item !is android.graphics.Point) {
                                continue
                            }
                            // y 为混合模式：3 是普通叠加，其余属于高光/描边类效果。
                            if (item.y != 3) {
                                continue
                            }
                            val c = item.x
                            val a = Color.alpha(c)
                            if (a > bestAlpha && a >= 40) {
                                bestAlpha = a
                                best = c
                            }
                        }
                        if (best != -1) {
                            sMaterialCalls++
                            val night = systemNight(sAppCtx)
                            val prev = if (night) sBlendDark else sBlendLight
                            if (night) {
                                sBlendDark = best
                            } else {
                                sBlendLight = best
                            }
                            if (prev != best) {
                                log("material blend(" + (if (night) "dark" else "light")
                                        + ") -> " + hex(best) + " calls=" + sMaterialCalls)
                                onMaterialTintChanged()
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
                chain.proceed()
            }
            true
        } catch (t: Throwable) {
            log("material blend hook skipped - $t")
            false
        }
    }

    /** 液态玻璃参数数组中的 tint 分量（框架按归一化浮点下发）。 */
    private fun hookGlass(m: Method): Boolean {
        return try {
            XposedInit.hook(m) { chain ->
                try {
                    val arg = chain.getArg(0)
                    if (arg is FloatArray && arg.size >= 37) {
                        val r = Math.max(0, Math.min(255, (arg[11] * 255f).toInt()))
                        val g = Math.max(0, Math.min(255, (arg[12] * 255f).toInt()))
                        val b = Math.max(0, Math.min(255, (arg[13] * 255f).toInt()))
                        val a = Math.max(0, Math.min(255, (arg[36] * 255f).toInt()))
                        if (a > 0) {
                            val c = Color.argb(a, r, g, b)
                            if (c != sGlassTint) {
                                sGlassTint = c
                                log("material glass tint -> " + hex(c))
                                onMaterialTintChanged()
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                }
                chain.proceed()
            }
            true
        } catch (t: Throwable) {
            log("material glass hook skipped - $t")
            false
        }
    }

    /** 材质混合色在面板显示之后才下发时，回到主线程按新色重刷一次。 */
    private fun onMaterialTintChanged() {
        Handler(Looper.getMainLooper()).post {
            try {
                val live = getLiveBody() ?: return@post
                applyPanelBackground(live, barColor(sBarBgColor))
            } catch (t: Throwable) {
                log("material tint refresh failed - $t")
            }
        }
    }

    /** 挂 framework PopupWindow 的 content/show 方法，仅对面板类生效。 */
    private fun hookPopupLifecycle(pwCls: Class<*>) {
        // setContentView(View)
        hookIfPanel(pwCls, "setContentView", arrayOf<Class<*>>(View::class.java))
        // showAsDropDown(View, int, int)
        hookIfPanel(pwCls, "showAsDropDown",
                arrayOf<Class<*>>(View::class.java, Int::class.java, Int::class.java))
        // showAsDropDown(View, int, int, int)
        hookIfPanel(pwCls, "showAsDropDown",
                arrayOf<Class<*>>(View::class.java, Int::class.java, Int::class.java, Int::class.java))
        // showAtLocation(View, int, int, int)
        hookIfPanel(pwCls, "showAtLocation",
                arrayOf<Class<*>>(View::class.java, Int::class.java, Int::class.java, Int::class.java))
    }

    private fun hookIfPanel(pwCls: Class<*>, name: String, sig: Array<Class<*>>) {
        try {
            val m = Reflect.findMethod(pwCls, name, *sig)
            XposedInit.hook(m) { chain ->
                val result = chain.proceed()
                val self = chain.thisObject
                if (isOurPanel(self)) {
                    // 从实例取 classloader：它能解析 com.miui.inputmethod.*（含底部栏类）。
                    if (sPanelCl == null) {
                        sPanelCl = self.javaClass.classLoader
                        log("panel classloader captured: $sPanelCl")
                    }
                    ensureBottomColorHook()
                    log("panel detected via $name, scheduling tint")
                    scheduleTint(self)
                }
                result
            }
        } catch (t: Throwable) {
            log("WARN: $name hook - $t")
        }
    }

    private fun isOurPanel(o: Any?): Boolean {
        return o != null && PHRASE_POPUP == o.javaClass.name
    }

    /**
     * 懒挂底部栏换色点 {@code setBottomColor(ZIII)V}：必须等面板出现、拿到能解析
     * {@code InputMethodBottomManager} 的 classloader 之后才能挂，否则必然 ClassNotFound。
     */
    private fun ensureBottomColorHook() {
        if (sBottomHooked) {
            return
        }
        var any = false
        // 权威色源：系统给功能区下发颜色时调用的方法，能同时拿到
        // 「底栏底色 / 内容色 / 半透明合成色」三个参数，面板照抄即可完全同款。
        val mgrCls = resolveBottomManager()
        if (mgrCls != null) {
            any = hookBottomColorSetter(mgrCls, BOTTOM_MANAGER, "setBottomColor") || any
            any = hookBottomColorSetter(mgrCls, BOTTOM_MANAGER, "customizeBottomViewColor") || any
        }
        val injector = resolveClass("android.inputmethodservice.InputMethodServiceInjector")
        if (injector != null) {
            any = hookBottomColorSetter(injector, "InputMethodServiceInjector", "customizeBottomViewColor") || any
        }
        // 全部失败说明动态 dex 还没加载进来，不置位，留给重试再试。
        sBottomHooked = any
        if (!any) {
            log("WARN: bottom color setters not resolvable yet")
        }
        // 顺路补装面板主题 hook：能解析到底部栏说明 dex 已经落地。
        ensurePhraseThemeHook(sPanelCl)
    }

    /**
     * 拦截 {@code android.inputmethodservice.InputMethodModuleManager.loadDex(ClassLoader,String)}。
     *
     * <p>面板类在动态 dex 里，而 {@code createPhraseThemeContext} 在首次开面板时就会被调用
     * （早于 {@code setContentView}），靠面板出现再装 hook 已经来不及。这里在 dex 落地后立刻装：
     * 本 hook 是 {@code PanelColorHook.init}（先于 ImeUnlockHook.init 执行）挂的，位于拦截器链最前，
     * 先 {@code proceed()} 让原有加载逻辑（含其它模块的 {@code addDexPath}）跑完，再按该 loader 装。
     */
    private fun hookModuleManagerLoadDex() {
        try {
            val mgr = Class.forName(
                    "android.inputmethodservice.InputMethodModuleManager", false, sDefaultCl)
            var loadDex: Method? = null
            for (m in mgr.declaredMethods) {
                val ps = m.parameterTypes
                if (m.name == "loadDex" && ps.size == 2
                        && ClassLoader::class.java.isAssignableFrom(ps[0])
                        && String::class.java == ps[1]) {
                    loadDex = m
                    break
                }
            }
            if (loadDex == null) {
                log("WARN: loadDex(ClassLoader, String) not found")
                return
            }
            val ld = loadDex
            ld.isAccessible = true
            XposedInit.hook(ld) { chain ->
                val result = chain.proceed()
                try {
                    ensurePhraseThemeHook(chain.getArg(0) as ClassLoader)
                } catch (t: Throwable) {
                    log("WARN: phrase hook after loadDex - $t")
                }
                result
            }
            log("OK: hooked " + mgr.name + ".loadDex")
        } catch (t: Throwable) {
            log("WARN: loadDex hook - $t")
        }
    }

    /**
     * 面板主题该跟「日(1)」还是「夜(2)」：以本模块实际涂到面板上的底色为准。
     *
     * <p>返回 {@code -1} 表示不干预——底栏色还没到、或走了模糊分支（面板观感由裸模糊决定），
     * 此时保持系统原本判定最安全。
     *
     * <p><b> staleness 自失效（2026-10-25 修复）</b>：[sAppliedPanelColor] /
     * [sBarBgColor] 都是跨面板开关持久化的捕获值。面板关闭期间系统切过深浅色后，旧色
     * 对应的主题上下文已过期，若继续拿去强制 {@code createPhraseThemeContext}，第一次
     * 打开面板时条目视图会整套按旧主题充气——条目背景与图标停在旧主题，底栏重推的新色
     * 又被旧色优先级遮住，要关开一次才恢复（用户 2026-10-25 反馈「切换后第一次打开不
     * 刷新」）。故每次强制前以「捕获时的系统明暗令牌」校验新鲜度：过期即作废，
     * 改按最新底栏色（键盘重显/重推时会更新）；底栏色也过期则整体不干预，交给 phrase
     * 自己的新判定（它在开面板时会重刷主题标志）。
     */
    private fun phraseThemeOverride(): Int {
        var panel = sAppliedPanelColor
        val sysNight = systemNight(sAppCtx)
        if (panel != -1 && sysNight != sAppliedNight) {
            log("phrase override: applied ${hex(panel)} stale"
                    + " (appliedNight=$sAppliedNight sysNight=$sysNight), dropping")
            sAppliedPanelColor = -1
            panel = -1
        }
        if (panel == -1) {
            val bar = barColor(sBarBgColor)
            if (bar == -1 || Color.alpha(bar) == 0) {
                return -1
            }
            // 捕获的底栏色同样会过期（键盘未随主题重推）：令牌不符就整体不干预，
            // 让 phrase 按当前系统主题自己判（混合主题的日间强制仅在两色都新鲜时生效）。
            if (sBarBgColor != -1 && sysNight != sBarNight) {
                log("phrase override: bar ${hex(bar)} stale too (barNight=$sBarNight), skip")
                return -1
            }
            panel = if (Color.alpha(bar) < 255) {
                (bar and 0x00FFFFFF) or 0xFF000000.toInt()
            } else {
                bar
            }
        }
        return if (isDarkColor(panel)) 2 else 1
    }

    /**
     * 按动态 dex 的 classloader 装面板主题纠偏 hook。
     *
     * <p><b>为什么改这里</b>：{@code setupItems()} 末尾用 {@code setColorMode(isDarkTheme()?2:1)}
     * 决定导航条底色（日 {@code #80ffffff} / 夜 {@code #24ffffff}）与选中胶囊色，二者都只看
     * <b>系统</b> uiMode。系统深色 + 输入法浅色时它们取夜间那种极淡的白，叠在被本模块涂浅的
     * 面板底（实测 {@code #E1E2E6}）上只剩 {@code #E5E6EA}，选中态直接糊掉。把主题上下文
     * 强制成「日」，导航条与胶囊就都取日间值（导航条 {@code #F0F1F3}，Δ≈15 可见）。
     *
     * <p>主纠偏点选 {@code createPhraseThemeContext(int)}：面板所有视图都用它返回的上下文充气，
     * 一次性把整套资源限定切到日间，比只改 {@code setColorMode} 更彻底且不会触发
     * {@code refreshPhraseThemeView} 那条 hyper-material 分支（它只在 type==1||2 时走）。
     * {@code isDarkTheme()} 作为兜底，防止上面方法名在别的版本上改名。
     */
    private fun ensurePhraseThemeHook(cl: ClassLoader?) {
        if (sPhraseHooked || cl == null) {
            return
        }
        val popup: Class<*>
        try {
            popup = Class.forName(PHRASE_POPUP, false, cl)
        } catch (t: Throwable) {
            // dex 还没落地，留给后续的 loadDex / 重试再试。
            return
        }
        var any = false
        try {
            val m = popup.getDeclaredMethod("createPhraseThemeContext", Int::class.java)
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                val type = toInt(chain.getArg(0))
                val forced = phraseThemeOverride()
                if (forced == -1 || forced == type) {
                    return@hook chain.proceed()
                }
                log("phrase theme $type -> $forced (panel ${hex(sAppliedPanelColor)})")
                val modified = chain.args.toTypedArray()
                modified[0] = forced
                return@hook chain.proceed(modified)
            }
            any = true
        } catch (t: Throwable) {
            log("WARN: createPhraseThemeContext - $t")
        }
        try {
            val m = popup.getDeclaredMethod("isDarkTheme")
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                val result = chain.proceed()
                val forced = phraseThemeOverride()
                if (forced == -1) {
                    return@hook result
                }
                return@hook if (forced == 2) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
            }
            any = true
        } catch (t: Throwable) {
            log("WARN: isDarkTheme - $t")
        }
        // 【重开面板退化为浅色的根因】：phrase 重开面板时复用 sClipboardWindow（不重建 popup），
        // 构造期 hook 不再触发，showClipBoard 里 setPhraseBackgroundDarkTheme(miuiPhraseFollowType)
        // 读到被复位/过期的旧值 → 标签栏 / 输入法切换面板退化成浅色。这里直接拦
        // setPhraseBackgroundDarkTheme 的入参强制成系统明暗（1=日/2=夜），首开与重开都必经此入口、
        // 都生效；并顺手钉住 miuiPhraseFollowType，供 createPhraseFollowThemeContext 读取。
        try {
            val m = popup.getDeclaredMethod("setPhraseBackgroundDarkTheme", Int::class.java)
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                try {
                    val forced = if (systemNight(sAppCtx)) 2 else 1
                    applyPhraseFollowType(forced)
                    val orig = toInt(chain.getArg(0))
                    log("phrase setPhraseBackgroundDarkTheme $orig -> $forced")
                    val modified = chain.args.toTypedArray()
                    modified[0] = forced
                    return@hook chain.proceed(modified)
                } catch (t: Throwable) {
                    return@hook chain.proceed()
                }
            }
            any = true
            log("OK: setPhraseBackgroundDarkTheme follow hook installed")
        } catch (t: Throwable) {
            log("WARN: setPhraseBackgroundDarkTheme hook - $t")
        }
        // 输入法切换面板 / 设置页等按需构建的视图经 createPhraseFollowThemeContext 读
        // miuiPhraseFollowType；调用前钉到系统明暗，确保这些按需视图也跟随深色模式。
        try {
            val bm = Class.forName(BOTTOM_MANAGER, false, cl)
            val m = bm.getDeclaredMethod("createPhraseFollowThemeContext", Context::class.java)
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                try {
                    applyPhraseFollowType(systemNight(sAppCtx))
                } catch (ignored: Throwable) {
                    // 断言失败交给后续重试，不阻断主题上下文构建
                }
                chain.proceed()
            }
            any = true
            log("OK: createPhraseFollowThemeContext follow hook installed")
        } catch (t: Throwable) {
            log("WARN: createPhraseFollowThemeContext hook - $t")
        }
        sPhraseHooked = any
        if (any) {
            log("OK: phrase theme hooks installed")
        }
        // 提前把 miuiPhraseFollowType 钉到当前系统明暗：首次开面板时构造期
        // setPhraseBackgroundDarkTheme(miuiPhraseFollowType) 即按系统主题派生（标签栏 / 输入法切换面板）。
        // 该静态字段与收起输入法无关；后续每次开面板 / 切主题再由 applyPhraseHyperMaterial /
        // refreshPhraseTheme 重新断言。
        try {
            applyPhraseFollowType(systemNight(sAppCtx))
        } catch (ignored: Throwable) {
            log("WARN: proactive miuiPhraseFollowType - $ignored")
        }
        // 兜底：在剪贴板面板构造之前把 miuiPhraseFollowType 钉到系统明暗，避免 phrase 在构造末尾
        // 的 setPhraseBackgroundDarkTheme(miuiPhraseFollowType) 读到旧值导致标签栏不跟随。
        try {
            val imeSvc = Class.forName("android.inputmethodservice.InputMethodService", false, cl)
            val ctor = popup.getDeclaredConstructor(
                    Context::class.java,
                    imeSvc,
                    Boolean::class.javaPrimitiveType,
                    View::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java)
            ctor.isAccessible = true
            XposedInit.hook(ctor) { chain ->
                try {
                    applyPhraseFollowType(systemNight(sAppCtx))
                } catch (ignored: Throwable) {
                    // 断言失败不影响构造，交给开面板时的二次断言
                }
                chain.proceed()
            }
            log("OK: phrase popup <init> follow-type hook installed")
        } catch (ignored: Throwable) {
            log("WARN: phrase popup <init> hook - $ignored")
        }
    }

    /**
     * 有界重试挂底栏取色点。
     *
     * <p>{@code com.miui.inputmethod.*} 是 {@code InputMethodModuleManager.loadDex} 动态加载的，
     * 进程刚起时解析不到。这里每秒重试一次、最多 30 次，抢在键盘首次配置颜色之前把取色点挂上；
     * 挂上即停，避免在输入法进程里长期轮询。
     */
    private fun scheduleBottomHookRetry() {
        val handler = Handler(Looper.getMainLooper())
        val attempt = object : Runnable {
            var tries = 0

            override fun run() {
                ensureBottomColorHook()
                if (!sBottomHooked && ++tries < 30) {
                    handler.postDelayed(this, 1000L)
                } else if (sBottomHooked) {
                    log("bottom color hook ready after $tries retry(ies)")
                }
            }
        }
        handler.postDelayed(attempt, 1000L)
    }

    /** 挂 {@code xxx(ZIII)V} 形式的底栏换色点；记录参数并按需同步面板。 */
    private fun hookBottomColorSetter(owner: Class<*>, ownerName: String, method: String): Boolean {
        val m: Method
        try {
            m = Reflect.findMethod(owner, method,
                    Boolean::class.java, Int::class.java, Int::class.java, Int::class.java)
        } catch (t: Throwable) {
            return false
        }
        return try {
            XposedInit.hook(m) { chain ->
                val bg = toInt(chain.getArg(1))
                val content = toInt(chain.getArg(2))
                val alphaColor = toInt(chain.getArg(3))
                // 参数可能每次键盘配置都会回调，只在变化时打印，避免刷屏。
                if (bg != sBarBgColor || content != sBarContentColor
                        || alphaColor != sBarAlphaColor) {
                    log("$ownerName.$method args: bg=${hex(bg)}"
                            + " content=${hex(content)}"
                            + " alpha=${hex(alphaColor)}")
                }
                sBarBgColor = bg
                sBarContentColor = content
                sBarAlphaColor = alphaColor
                // 记下捕获时的系统明暗：面板关闭期间切过主题后此令牌失效，phraseThemeOverride
                // 不会拿旧底栏色强制主题（见 staleness 自失效说明）。
                sBarNight = systemNight(sAppCtx)
                val result = chain.proceed()
                val body = getLiveBody()
                if (body != null) {
                    applyPanelBackground(body, barColor(bg))
                    requestBarComposite(body, true)
                }
                result
            }
            log("OK: hooked $ownerName.$method(ZIII)")
            true
        } catch (t: Throwable) {
            log("WARN: $ownerName.$method hook - $t")
            false
        }
    }

    /** 按面板 classloader 优先、默认 classloader 兜底解析类。 */
    private fun resolveClass(name: String): Class<*>? {
        val loaders = arrayOf(sPanelCl, sDefaultCl)
        for (cl in loaders) {
            if (cl == null) {
                continue
            }
            try {
                return Reflect.findClass(name, cl)
            } catch (ignored: Throwable) {
                // 换下一个 classloader 再试
            }
        }
        return null
    }

    /** 底栏底色以系统下发的参数为准；参数未到时退回实时读取。 */
    private fun barColor(fromArgs: Int): Int {
        return if (fromArgs != -1) fromArgs else getBottomBarColor()
    }

    /** 用面板 classloader 优先、默认 classloader 兜底，解析 {@code InputMethodBottomManager}。 */
    private fun resolveBottomManager(): Class<*>? {
        val loaders = arrayOf(sPanelCl, sDefaultCl)
        for (cl in loaders) {
            if (cl == null) {
                continue
            }
            try {
                return Reflect.findClass(BOTTOM_MANAGER, cl)
            } catch (ignored: Throwable) {
                // 换下一个 classloader 再试
            }
        }
        return null
    }

    /**
     * 推迟到 contentView 就绪后再定位容器并着色：{@code setContentView}/{@code showAsDropDown}
     * 可能早于视图完全就绪，直接取 contentView 会为 null，故用重试保证拿到视图。
     */
    private fun scheduleTint(popupObj: Any?) {
        if (popupObj !is PopupWindow) {
            return
        }
        val pw = popupObj
        val attempt = object : Runnable {
            var tries = 0

            override fun run() {
                try {
                    val content = pw.contentView
                    if (content == null) {
                        if (tries++ < 8) {
                            Handler(Looper.getMainLooper()).postDelayed(this, 200L)
                        } else {
                            log("WARN: contentView never ready")
                        }
                        return
                    }
                    val body = findBody(content)
                    if (body == null) {
                        log("WARN: panel content container not found")
                        return
                    }
                    // 视图还没挂上窗口时拿不到 ViewRootImpl，模糊会静默失败并误退成实色。
                    // 等它 attach 完成再上色，避免面板一闪而过地被涂成兜底色。
                    if (!body.isAttachedToWindow && tries++ < 10) {
                        Handler(Looper.getMainLooper()).postDelayed(this, 200L)
                        return
                    }
                    sPanelBodyRef = WeakReference(body)
                    sPopupRef = WeakReference(pw)
                    // 应用级 context：进程级 configuration 实时更新，供明暗令牌判定用
                    // （面板视图自己的 context 是 createPhraseThemeContext 的快照，会过期）。
                    sAppCtx = body.context.applicationContext
                    captureFallback(body)
                    // 启动期日志常被缓冲区淘汰，这里随面板开合输出一次材质链路状态。
                    log("material status: methods=" + (sMaterialMethods?.size ?: -1)
                            + " unavailable=" + sNativeUnavailable
                            + " attempts=" + sNativeAttempts
                            + " pass=" + sPassBlurState
                            + " detail=" + sNativeDetail
                            + " calls=" + sMaterialCalls
                            + " schema=" + MATERIAL_SCHEMA)
                    startThemeWatch(body)

                    val color = barColor(sBarBgColor)
                    if (color == -1) {
                        // 底部栏尚未着色（如输入法刚起），推迟一拍再试。
                        log("defer: bottom color not ready")
                        Handler(Looper.getMainLooper()).postDelayed({
                            applyPanelBackground(body, barColor(sBarBgColor))
                        }, 300L)
                        requestBarComposite(body, true)
                        return
                    }
                    applyPanelBackground(body, color)
                    requestBarComposite(body, true)
                } catch (t: Throwable) {
                    log("scheduleTint error - $t")
                }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed(attempt, 50L)
    }

    /**
     * 系统实时深色模式（多源，任一源给出确定答案即返回）。
     *
     * <p><b>为什么不能读面板视图自己的 configuration</b>：面板视图的 context 来自
     * {@code createPhraseThemeContext(type)}——它在充气时把 Configuration 拷了一份快照
     * （type 为 1/2 时固定日/夜，为 3 时拷当时的系统值），此后系统再切换深浅色，
     * 这份快照也不会更新。实测（2026-10-23 真机）：系统切到深色后 [isNight] 仍报
     * 浅色，磨砂卡片与文字因此停在旧主题——这正是「切换后不跟随」的直接原因。
     *
     * <p><b>为什么不用 hook configuration 回调</b>：HyperOS 没有可靠透给模块的
     * configuration 回调（弹窗挂在独立 ViewRootImpl 上，{@code View.onConfigurationChanged}
     * 未必被分发），只能自己看（见 [startThemeWatch]）。
     */
    private fun isSystemNight(body: View): Boolean {
        return systemNight(body.context)
    }

    /**
     * 不依赖面板视图的实时系统明暗。
     *
     * <p>面板视图的 context 是 {@code createPhraseThemeContext} 的快照会过期；这里优先
     * UiModeManager（Binder 直读，永远实时），退回 context 进程级 configuration，再退回
     * 系统资源。传给 null 时直接走系统资源。
     */
    private fun systemNight(ctx: Context?): Boolean {
        // ① UiModeManager：Binder 直读系统服务，永远实时——设置里手动切的深浅色开关就是它。
        if (ctx != null) {
            try {
                val umm = ctx.getSystemService(UiModeManager::class.java)
                if (umm != null) {
                    val mode = umm.nightMode
                    if (mode == UiModeManager.MODE_NIGHT_YES) {
                        return true
                    }
                    if (mode == UiModeManager.MODE_NIGHT_NO) {
                        return false
                    }
                    // AUTO / CUSTOM（按时间或就寝时间自动切换）：落到下面按 configuration 判。
                }
            } catch (ignored: Throwable) {
                // 服务异常，试下一个源
            }
            // ② context 进程级 resources（InputMethodService 的 Application，其 configuration
            //    随进程级 configuration 变化更新）。
            try {
                val cfg = ctx.applicationContext.resources.configuration
                return (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            } catch (ignored: Throwable) {
                // context 被多层 wrapper 包住时兜底试系统资源
            }
        }
        return try {
            val cfg = Resources.getSystem().configuration
            (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * 启动系统明暗轮询：解决「切换深浅色后模糊条目和字体颜色不立即跟随、要关掉剪贴板
     * 重开才正常」（用户 2026-10-23 反馈）。
     *
     * <p><b>根因</b>：模糊态（玻璃键盘）下面板视图的 context 是
     * {@code createPhraseThemeContext} 的快照，系统切换深浅色后不更新；而 phrase 唯一的
     * 主题刷新入口 {@code setPhraseBackgroundDarkTheme} 只被
     * {@code InputMethodBottomManager.showClipBoard} 调用（开面板时），切换时无人重解析，
     * PopupWindow 视图也不会随配置变化重建。
     *
     * <p><b>为什么用轮询而不是 hook</b>：HyperOS 没有可靠透给模块的 configuration
     * 回调（弹窗挂在独立 ViewRootImpl 上，{@code View.onConfigurationChanged} 未必被
     * 分发），而 250ms 自看一次 configuration 的代价可以忽略。面板不可见即自停。
     */
    private fun startThemeWatch(body: View) {
        sLastNight = isSystemNight(body)
        if (sThemeWatch) {
            return
        }
        sThemeWatch = true
        val watch = object : Runnable {
            var beat = 0
            var miss = 0
            var probe = 0

            override fun run() {
                // 用活 body：主题切换若使 phrase 重建面板，缓存的 sPanelBodyRef 会 detached，
                // 这里改从当前 PopupWindow 重新定位，避免轮询误判「面板不可见」而停摆。
                val b = getLiveBody()
                if (b == null) {
                    // 面板可能正在被 phrase 重建（短暂 detached）。给一小段重试窗口，
                    // 避免重建期间误判「面板关闭」而停摆（否则要等重开面板才恢复刷新）。
                    if (++miss > 12) {
                        sThemeWatch = false
                    }
                    return
                }
                miss = 0
                try {
                    val night = isSystemNight(b)
                    if (night != sLastNight) {
                        sLastNight = night
                        onThemeChanged(b)
                    } else if (++beat % 20 == 0) {
                        // 心跳：对比「系统实时」与「面板快照」，确认两个源都在跟踪
                        log("theme watch alive: sysNight=$night"
                                + " panelNight=" + isNight(b) + " blur=" + sBlurPanel)
                    }
                } catch (t: Throwable) {
                    log("theme watch error - $t")
                }
                // 底栏观感随场景变化，按节拍复核；内部有最小间隔与在途请求保护。
                if (++probe % PROBE_HEARTBEAT == 0) {
                    requestBarComposite(b, false)
                }
                if (sThemeWatch) {
                    Handler(Looper.getMainLooper()).postDelayed(this, THEME_WATCH_MS)
                }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed(watch, THEME_WATCH_MS)
    }

    /**
     * 系统深浅色切换后的面板刷新（只处理<b>模糊态</b>）。
     *
     * <p><b>为什么不碰实色态</b>：实色态下面板 context 被 [phraseThemeOverride]
     * 固定成「跟随输入法底栏」，条目磨砂、文字、图标一整套资源都按输入法主题解析，本就与
     * 系统 uiMode 无关，系统切换不破坏任何东西；文字纠偏也只看实际底色亮度。真正会坏的是
     * 模糊态——它的 context 是系统主题快照，切换后整套资源停在旧主题上。
     *
     * <p>实色态也不能顺手调原生刷新：{@code refreshPhraseThemeView} 会把 insideView 背景
     * 重置成 phrase 原生资源，而实色态的底色是本模块涂上去的，重置后不重涂就会掉回原生色。
     *
     * <p><b>实测踩坑（2026-10-23 真机）</b>：切换深浅色瞬间输入法常把面板刷成实色态
     * （{@code setBottomColor} 重发），曾在此静默早退导致 {@code sBlurNight} 失新，徽标圆底
     * 与磨砂卡停在旧主题、需关面板重开（用户 2026-10-25 复现）。现改为：先无条件记下最新
     * {@code sBlurNight}，再走 [applyPanelBackground] 统一按当前底栏色重判 blur/solid
     * 并刷新前景，两条路都跟随新主题，不再依赖面板重开。
     */
    private fun onThemeChanged(body: View) {
        // body 已是实时定位的活 body；但 refreshPhraseTheme 可能让 phrase 重建面板内容，
        // 故刷新前再取一次，确保操作的是重建后的活视图。
        var live = getLiveBody()
        if (live == null) {
            log("onThemeChanged: live body gone, skip")
            return
        }
        val night = isSystemNight(live)
        log("ui mode changed -> " + (if (night) "night" else "day") + ", refreshing panel")
        // 先固定最新明暗：过去只在模糊分支才写 sBlurNight，若切换瞬间面板恰为实色瞬态
        // （输入法重发 setBottomColor）会早退、sBlurNight 失新；等回模糊态时轮询已无变化，
        // 徽标圆底与磨砂卡停在旧主题，需关面板重开才恢复（用户 2026-10-25 反馈）。
        sBlurNight = night
        sLastNight = night
        // 主题令牌与涂色缓存一并失效，避免上一主题的底色滞留在重建后的面板上。
        sAppliedPanelColor = -1
        sCompositeColor = -1
        sProbeAt = 0L
        sNativeBodyRef = WeakReference(null)
        sNativeAttempts = 0
        sPassBlurState = null
        log("theme cache cleared -> " + (if (night) "night" else "day"))
        refreshPhraseTheme(night)
        // refreshPhraseTheme 之后面板可能已被重建，重取一次活 body 再刷新前景：
        // 模糊态 => applyBlurForeground（内部 contrastWalk->blurWalk 已用最新 sBlurNight 重涂徽标）；
        // 实色态 => 重涂底色 + 文字纠偏。两条路都跟随新主题，不再依赖面板重开。
        val after = getLiveBody()
        if (after != null) {
            live = after
        }
        applyPanelBackground(live, barColor(sBarBgColor))
    }

    /**
     * 调 phrase 原生的主题刷新入口 {@code setPhraseBackgroundDarkTheme(int)}，把面板强制成跟随
     * <b>系统</b>明暗（1=日 / 2=夜，即 miuiPhraseFollowType 的取值，详见 getResourceNightMode），
     * 正是用户诉求。同时先把进程级 miuiPhraseFollowType 钉到同值，使 createPhraseFollowThemeContext
     * （输入法切换面板等按需构建的视图）也据此派生主题。
     *
     * <p>旧实现写成了两参重载 (int,int)，而 phrase 5.7.8 只有单参 (I)V，导致 findMethod 始终
     * 返回 null、整段静默失效——这是「切换深色模式后标签栏 / 输入法选择器不跟随」的根因之一。
     * 这里改回单参，并显式强制 miuiPhraseFollowType，确保切换时面板子树真正重解析主题。
     */
    private fun refreshPhraseTheme(night: Boolean) {
        val follow = if (night) 2 else 1
        applyPhraseFollowType(follow)
        val pw = sPopupRef.get()
        if (pw == null) {
            // 即便 popup 还没就绪，miuiPhraseFollowType 已先钉好，构造期即可生效。
            return
        }
        try {
            val m = findMethodInHierarchy(pw.javaClass,
                    "setPhraseBackgroundDarkTheme", Int::class.java)
            if (m == null) {
                log("phrase refresh: setPhraseBackgroundDarkTheme not found")
                return
            }
            m.isAccessible = true
            m.invoke(pw, follow)
            log("phrase refresh invoked, type=$follow")
        } catch (t: Throwable) {
            log("phrase refresh failed - $t")
        }
    }

    /**
     * 定位面板内容容器：优先按 id 名 {@code inside_view}（跨版本稳），
     * 退回内容根第一个带背景的子视图，再退回视图树扫描。
     */
    private fun findBody(content: View): View? {
        try {
            val id = phraseId(content.context, ID_INSIDE_VIEW)
            if (id == 0) {
                log("body: id($ID_INSIDE_VIEW) unresolved, falling back")
            } else {
                val v = content.findViewById<View>(id)
                if (v != null) {
                    log("body by id($ID_INSIDE_VIEW)=${hex(id)}")
                    return v
                }
                log("body: id($ID_INSIDE_VIEW)=${hex(id)} found but view missing")
            }
        } catch (t: Throwable) {
            log("body: id lookup failed - $t")
        }
        if (content is ViewGroup) {
            if (content.childCount > 0) {
                val c0 = content.getChildAt(0)
                if (c0 != null && c0.background != null) {
                    log("body by content child[0]")
                    return c0
                }
            }
        }
        val scanned = findFirstWithBg(content)
        if (scanned != null) {
            log("body by scan")
        }
        return scanned
    }

    /**
     * 取当前<b>活着的</b>面板根容器，而非开面板时缓存的那个。
     *
     * <p><b>为什么需要</b>：主题切换时 phrase 常重建面板内容（{@code setPhraseBackgroundDarkTheme}
     * 触发 {@code notifyDataSetChanged} / 重充气），开面板时记到 [sPanelBodyRef] 的 {@code body}
     * 会被卸下（detached）。原先主题轮询只认这个缓存 {@code body}，一旦它 detached 就判定「面板不可见」
     * 而停掉轮询（{@code sThemeWatch=false}），之后直到重新开面板都不会再刷新——表现就是
     * 「切换主题后链接图标不变、必须重开面板才生效」（用户 2026-10-25 复现）。这里改为从当前
     * PopupWindow 的 {@code getContentView()} 重新定位活 body，使轮询与刷新都能穿透 phrase 的重建。
     */
    private fun getLiveBody(): View? {
        val pw = sPopupRef.get() as? PopupWindow
        if (pw != null) {
            try {
                val content = pw.contentView
                if (content != null) {
                    val b = findBody(content)
                    if (b != null && b.isAttachedToWindow) {
                        return b
                    }
                }
            } catch (ignored: Throwable) {
                // 取不到就用回退
            }
        }
        val b = sPanelBodyRef.get()
        return if (b != null && b.isAttachedToWindow) b else null
    }

    private fun findFirstWithBg(root: View): View? {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.background != null) {
                    return child
                }
                val found = findFirstWithBg(child)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    /**
     * 面板上色总入口。分两种情况：
     * <ul>
     *   <li><b>正常</b>（底部栏有实色）→ 面板跟随底栏实色，与系统观感一致；</li>
     *   <li><b>底部栏被第三方美化模块强改成透明</b>（面板会随之变透明、不可读）→ 改用
     *       「系统高斯模糊 + 半透明底色」，与该模块的玻璃键盘观感一致；模糊不可用时
     *       退回不透明实色，绝不留下透明面板。</li>
     * </ul>
     */
    private fun applyPanelBackground(body: View, bottomColor: Int) {
        if (bottomColor == -1) {
            // 取色尚未就绪（输入法刚起），不是「被强改透明」：保持面板原样，等下次同步。
            log("skip: bottom color unknown")
            return
        }
        if (Color.alpha(bottomColor) == 0) {
            // 模糊态跟随<b>系统实时</b>主题：面板 context 是 createPhraseThemeContext 的快照，
            // 系统切换深浅色后 isNight(body) 已过期（真机实测：切深色后仍报 light，磨砂卡与
            // 文字停在旧主题，用户 2026-10-23 反馈「切换后不跟随」）。而 phrase 的磨砂卡片、
            // 文字、图标一整套资源本来就是按<b>系统</b>主题为模糊态设计的，半透明合成色也来自
            // 系统下发——所以模糊分支的明暗一律以 isSystemNight 为准。
            val sysNight = isSystemNight(body)
            // 背景渲染方式由「启用高斯模糊背景」开关决定：开启走原生材质（高斯模糊），
            // 关闭走裸模糊（半透玻璃）；原生材质不可用时回退裸模糊。
            if (ModulePrefs.isGaussianBlurEnabled() && tryNativeMaterial(body, sysNight)) {
                sBlurPanel = true
                sAppliedPanelColor = -1
                applyBlurForeground(body, sysNight)
                return
            }
            if (applyBlurPanel(body, sysNight)) {
                // 模糊态：面板观感由裸模糊决定，没有可判明暗的实色底 → 主题纠偏保持不干预；
                // 前景（磨砂卡片 / 系统主题文字）全部交还原生，补底与纠偏一律不执行
                // （见 sBlurPanel 注释）。
                sBlurPanel = true
                sAppliedPanelColor = -1
                applyBlurForeground(body, sysNight)
                return
            }
        }
        sBlurPanel = false
        // 实色态的兜底色取面板自身 context 的主题（= 被 phraseThemeOverride 固定的输入法
        // 主题，与系统 uiMode 无关）。
        val base = ensureOpaque(bottomColor, isNight(body))
        tint(body, base)
        // 面板主题（Tab 选中胶囊 / 导航条底色）跟随输入法底栏，而不是系统 uiMode。
        sAppliedPanelColor = base
        // 记下涂色时的系统明暗：面板关闭期间切过主题后此令牌失效，phraseThemeOverride
        // 会作废该色、改按最新底栏色充气（用户 2026-10-25 反馈「切换后第一次打开
        // 条目背景/图标不刷新」的根因就是旧色跨会话持久）。
        sAppliedNight = isSystemNight(body)
        // 背景已按输入法主题着色，但面板文字色来自 phrase 资源的「系统夜间」限定符。
        // 系统深色 + 输入法浅色时文字仍是白色 → 白底白字。这里按实际底色亮度纠偏。
        applyTextContrast(body, isDarkColor(base))
        log("applied solid -> ${hex(base)}")
    }

    /**
     * 面板模糊层之上要叠的底色，目标是与底部功能区「同款」——而功能区自身就是
     * {@code ColorDrawable #00000000}（全透明、直接透出键盘，见 {@code dumpBarOnce} 实证），
     * 即它的观感 = <b>裸模糊、不加任何颜色叠加</b>。所以面板也必须是裸模糊，否则一定有色差：
     * <ul>
     *   <li><b>浅色</b>：返回全透明，调用方只挂模糊层；</li>
     *   <li><b>深色</b>：同样不叠底色。仅当系统下发了<b>深色</b>半透明合成色
     *       （{@code setBottomColor} 第 4 参）时才照抄（alpha 平方折算）。</li>
     * </ul>
     *
     * <p><b>历史坑 1（浅色「脏灰面板」的根因）</b>：早期照抄系统下发的 {@code #66202020}
     * 折算成 {@code 0x28 ≈ 16%} 压了一层暗色底，实测把面板压暗了约 19%（背板 243 → 196），
     * 而功能区同期只压暗约 7%（243 → 225）。结果面板变成一块脏灰底，上面浮着条目那张近白的
     * 卡片（条目是 MIUI 原生 {@code #99ffffff} = 白色 60%），视觉上非常突兀。去掉这层叠加后
     * 面板 ≈ 纯模糊 ≈ 功能区，条目与面板的落差也回到 MIUI 设计原本的幅度。
     *
     * <p><b>历史坑 2（深色「太白面板」，用户 2026-10-18 反馈）</b>：深色分支曾退回
     * {@code #F5F5F5}（功能区透明态的<i>内容色</i>，近白）叠 16%。但内容色是给图标/文字用的，
     * 不是给背景的；功能区背景本身分文未叠。于是同样的模糊键盘，面板被提亮约 39 个灰度级
     * （15 Pro 实测面板 75 vs 功能区 34；13 Pro 57 vs 0），深色模式下泛出一层白灰，
     * 与底部功能区色差明显。修正为与浅色对称：不叠底色，面板 = 裸模糊 ≈ 功能区。
     */
    private fun barLikeTint(night: Boolean): Int {
        // 底栏观感由「窗口背后模糊 + 材质混合色」构成。照抄同一层混合色后，
        // 面板与底栏同源，底层模糊仍随壁纸变化，不需要复制一个静态 ARGB。
        val glass = sGlassTint
        if (glass != -1 && Color.alpha(glass) > 0) {
            sTintFrom = "glass"
            return glass
        }
        val blend = if (night) sBlendDark else sBlendLight
        if (blend != -1 && Color.alpha(blend) > 0) {
            sTintFrom = "blend"
            return blend
        }
        val alphaColor = sBarAlphaColor
        val a = Color.alpha(alphaColor)
        if (night && alphaColor != -1 && a > 0 && a < 255 && isDarkColor(alphaColor)) {
            sTintFrom = "alpha"
            return withAlpha(alphaColor, a * a / 255)
        }
        // 材质接口未观测到调用时，按当前明暗给出等效混合层。深浅两态对称，
        // 主题切换后取值立即改变，不会残留上一主题的颜色。
        sTintFrom = "default"
        return if (night) DEFAULT_DARK_BLEND else DEFAULT_LIGHT_BLEND
    }

    /** 视图层采样结果：颜色、与底栏区域的相交面积、来源类名。 */
    private class BarLayer(val color: Int, val area: Int, val label: String)

    /**
     * 取回底栏区域「已合成」的颜色。
     *
     * <p>底栏被改透明后，换色参数与单个 {@link ColorDrawable} 都不再携带颜色，可见观感来自
     * 窗口背后内容与材质层的合成。这里读取窗口 surface 的对应区域并统计主色，得到与肉眼
     * 所见一致的颜色；surface 不可用、或该区域未绘制出内容时，退回视图层合成。
     *
     * <p>像素拷贝是异步的，结果回到主线程后再比对并决定是否重涂；同一时刻只允许一次在途请求。
     */
    private fun requestBarComposite(body: View, force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (sProbePending || (!force && now - sProbeAt < PROBE_INTERVAL_MS)) {
            return
        }
        sProbeAt = now
        val bottom = bottomView()
        val decor = (bottom ?: body).rootView
        val rect = windowBarRect(decor, bottom)
        val surface = surfaceOf(decor)
        if (surface == null || rect == null || !surface.isValid) {
            log("bar probe: window surface unavailable")
            applyCompositeIfChanged(scanBarComposite(body, decor, rect), "layers")
            return
        }
        val bmp = Bitmap.createBitmap(
                PROBE_BITMAP_PX, PROBE_BITMAP_PX, Bitmap.Config.ARGB_8888)
        sProbePending = true
        try {
            PixelCopy.request(surface, rect, bmp,
                    object : PixelCopy.OnPixelCopyFinishedListener {
                        override fun onPixelCopyFinished(result: Int) {
                            sProbePending = false
                            try {
                                val pixels = if (result == PixelCopy.SUCCESS) {
                                    dominantColor(bmp)
                                } else {
                                    -1
                                }
                                if (pixels != -1) {
                                    applyCompositeIfChanged(pixels, "pixels")
                                } else {
                                    if (!sProbeDumped) {
                                        log("bar probe: pixel copy result=$result")
                                    }
                                    applyCompositeIfChanged(
                                            scanBarComposite(body, decor, rect), "layers")
                                }
                            } catch (t: Throwable) {
                                log("bar probe: apply error - $t")
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            sProbePending = false
            bmp.recycle()
            log("bar probe: pixel copy unavailable - $t")
            applyCompositeIfChanged(scanBarComposite(body, decor, rect), "layers")
        }
    }

    /**
     * 采样结果只用于运行期排查，不参与面板着色。
     *
     * <p>窗口 surface 与视图层都只能看到控件自身的底色，看不到渲染器合成后的底栏观感；
     * 把这类值铺到面板上会得到与底栏无关的假色，且在主题切换后滞留。
     */
    private fun applyCompositeIfChanged(color: Int, from: String) {
        if (color == -1) {
            if (!sProbeDumped) {
                log("bar probe: no usable color from $from")
            }
            return
        }
        if (color != sCompositeColor) {
            sCompositeColor = color
            sCompositeFrom = from
            log("bar probe: composite=" + hex(color) + " from=" + from
                    + " (diagnostic only)")
        }
    }

    /** 底栏在窗口坐标系中的矩形；拿不到功能栏时取窗口下方五分之一。 */
    private fun windowBarRect(decor: View, bottom: View?): Rect? {
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) {
            return null
        }
        if (bottom != null && bottom.width > 0 && bottom.height > 0) {
            try {
                val loc = IntArray(2)
                bottom.getLocationInWindow(loc)
                val l = Math.max(0, loc[0])
                val t = Math.max(0, loc[1])
                val r = Math.min(w, loc[0] + bottom.width)
                val b = Math.min(h, loc[1] + bottom.height)
                if (r - l > 8 && b - t > 8) {
                    return Rect(l, t, r, b)
                }
            } catch (ignored: Throwable) {
            }
        }
        return Rect(0, h * 4 / 5, w, h)
    }

    /** 面板视图所在窗口的 surface，供区域像素拷贝使用。 */
    private fun surfaceOf(decor: View): Surface? {
        return try {
            val getVri = findMethodInHierarchy(View::class.java, "getViewRootImpl")
                    ?: return null
            getVri.isAccessible = true
            val vri = getVri.invoke(decor) ?: return null
            val f = findFieldInHierarchy(vri.javaClass, "mSurface") ?: return null
            f.isAccessible = true
            f.get(vri) as? Surface
        } catch (t: Throwable) {
            log("bar probe: surface unavailable - $t")
            null
        }
    }

    /** 沿类继承链查找字段（含隐藏字段）。 */
    private fun findFieldInHierarchy(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (ignored: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    /** 采样位图的主色：取各通道中位数，天然滤掉按键、文字等小面积元素。 */
    private fun dominantColor(bmp: Bitmap): Int {
        val total = bmp.width * bmp.height
        val pixels = IntArray(total)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val rs = ArrayList<Int>(total)
        val gs = ArrayList<Int>(total)
        val bs = ArrayList<Int>(total)
        for (p in pixels) {
            if (Color.alpha(p) < 200) {
                continue
            }
            rs.add(Color.red(p))
            gs.add(Color.green(p))
            bs.add(Color.blue(p))
        }
        if (rs.size < (total * PROBE_MIN_COVERAGE).toInt()) {
            return -1
        }
        java.util.Collections.sort(rs)
        java.util.Collections.sort(gs)
        java.util.Collections.sort(bs)
        val mid = rs.size / 2
        return Color.rgb(rs[mid], gs[mid], bs[mid])
    }

    /**
     * 视图层兜底：按绘制顺序把与底栏区域相交的背景层做 alpha 合成。
     *
     * <p>只采集覆盖底栏区域四分之一以上的层，避免把按键、图标等小面积 drawable 当成底色；
     * 首轮同时输出分层结构，便于确认颜色实际落在哪一层。
     */
    private fun scanBarComposite(body: View, decor: View, rect: Rect?): Int {
        val bounds = rect ?: Rect(0, decor.height * 4 / 5, decor.width, decor.height)
        val barArea = Math.max(1, bounds.width() * bounds.height())
        val layers = ArrayList<BarLayer>(PROBE_VIEW_LIMIT)
        collectBarLayers(decor, bounds, barArea, layers)
        if (layers.isEmpty()) {
            return -1
        }
        val base = panelFallbackColor(isNight(body))
        var r = Color.red(base).toFloat()
        var g = Color.green(base).toFloat()
        var b = Color.blue(base).toFloat()
        for (l in layers) {
            val cover = Math.min(1f, l.area.toFloat() / barArea.toFloat())
            val a = Color.alpha(l.color) / 255f * cover
            if (a <= 0.02f) {
                continue
            }
            r += (Color.red(l.color) - r) * a
            g += (Color.green(l.color) - g) * a
            b += (Color.blue(l.color) - b) * a
        }
        val result = Color.rgb(r.toInt(), g.toInt(), b.toInt())
        return if (maxChannelDelta(result, base) < COMPOSITE_MIN_DELTA) -1 else result
    }

    /** 两个颜色的最大通道差，用于判定采样结果是否只是窗口自身底色。 */
    private fun maxChannelDelta(a: Int, b: Int): Int {
        val dr = Math.abs(Color.red(a) - Color.red(b))
        val dg = Math.abs(Color.green(a) - Color.green(b))
        val db = Math.abs(Color.blue(a) - Color.blue(b))
        return Math.max(dr, Math.max(dg, db))
    }

    /** 当前模糊层叠加色的来源，仅用于运行期排查。 */
    private fun tintSource(): String {
        return sTintFrom
    }

    /**
     * 让面板与底栏走同一套系统材质渲染。
     *
     * <p>独立 PopupWindow 的背景模糊只采样自身窗口背后，与底栏材质的采样源不同，任何
     * 固定叠色都无法让两者一致。这里把面板视图接入框架的材质接口，由系统渲染器统一
     * 处理模糊与混合色；接口缺失或调用失败时返回 false，调用方回退到原有模糊层。
     */
    private fun tryNativeMaterial(body: View, night: Boolean): Boolean {
        if (sNativeUnavailable || sNativeAttempts > NATIVE_RETRY_LIMIT) {
            return false
        }
        if (resolveMaterialMethods() == null) {
            sNativeUnavailable = true
            return false
        }
        val configured = sNativeBodyRef.get()
        if (configured === body && sNativeNight == night) {
            return true
        }
        val ok = applyNativeMaterial(body, night)
        if (ok) {
            sNativeBodyRef = WeakReference(body)
            sNativeNight = night
            sNativeAttempts = 0
        } else {
            // 视图尚未挂载、窗口状态尚未就绪都可能随下一次布局恢复，只做有界重试
            sNativeAttempts++
            log("native material: transient attempt " + sNativeAttempts
                    + "/" + NATIVE_RETRY_LIMIT + " detail=" + sNativeDetail)
        }
        return ok
    }

    /** 解析框架材质接口；结果按进程缓存，空表表示本机不可用。 */
    private fun resolveMaterialMethods(): Map<String, Method>? {
        val cached = sMaterialMethods
        if (cached != null) {
            return if (cached.isEmpty()) null else cached
        }
        val map = HashMap<String, Method>()
        try {
            val intNames = arrayOf("setMiBackgroundBlurMode", "setMiBackgroundBlurRadius",
                    "setMiViewBlurMode", "setMiBackgroundBlurType")
            for (n in intNames) {
                map[n] = View::class.java.getMethod(n, Int::class.javaPrimitiveType)
            }
            val boolNames = arrayOf("setPassWindowBlurEnabled", "setMixEffectEnabled")
            for (n in boolNames) {
                map[n] = View::class.java.getMethod(n, Boolean::class.javaPrimitiveType)
            }
            map["setMiBackgroundBlendColors"] =
                    View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java)
            try {
                map["setMiBlurWinType"] =
                        View::class.java.getMethod("setMiBlurWinType", Int::class.javaPrimitiveType)
            } catch (ignored: Throwable) {
                // 旧版框架没有离屏填充接口，其余材质能力仍可使用
            }
        } catch (t: Throwable) {
            sMaterialMethods = HashMap()
            log("native material: methods unavailable - $t")
            return null
        }
        sMaterialMethods = map
        log("native material: methods ready=" + map.keys.toString()
                + " schema=" + MATERIAL_SCHEMA)
        return map
    }

    /**
     * 在同步调用栈内放行窗口采样准入并开启跨窗口采样，随后核对框架实际记录的状态。
     *
     * <p>面板内容视图的 context 与承载它的窗口可能分属不同包，仅按宿主包名放行会漏配；
     * 部分框架版本还会在 ViewRoot 上保存一份准入名单。这里只在本次调用期间临时满足两者，
     * 调用结束即还原，不改动系统的长期配置。返回 null 表示本机没有可核对的状态字段。
     */
    private fun enablePassWindowBlur(body: View, setter: Method): Boolean? {
        val root = try {
            val getter = try {
                View::class.java.getMethod("getViewRootImpl")
            } catch (ignored: Throwable) {
                View::class.java.getDeclaredMethod("getViewRootImpl")
                        .also { it.isAccessible = true }
            }
            getter.invoke(body)
        } catch (t: Throwable) {
            null
        }
        var filterField: Field? = null
        var originalFilter: String? = null
        var pkg: String? = null
        if (root != null) {
            try {
                filterField = root.javaClass.getDeclaredField("mPassWindowBlurFilterData")
                filterField.isAccessible = true
                originalFilter = filterField.get(root) as? String
            } catch (ignored: Throwable) {
                filterField = null
            }
            try {
                val ctxField = root.javaClass.getDeclaredField("mContext")
                ctxField.isAccessible = true
                val ctx = ctxField.get(root) as? Context
                if (ctx != null) {
                    pkg = Context::class.java.getMethod("getBasePackageName")
                            .invoke(ctx) as? String
                }
            } catch (ignored: Throwable) {
                // 取不到包名时仍可依赖调用栈内的临时放行
            }
        }
        val needsAdmission = !originalFilter.isNullOrEmpty()
                && pkg != null && !originalFilter.contains(pkg)
        sBlurAdmission.set(true)
        try {
            if (needsAdmission) {
                try {
                    filterField?.set(root, "$originalFilter $pkg")
                } catch (ignored: Throwable) {
                }
            }
            setter.invoke(body, true)
            return try {
                val state = View::class.java.getDeclaredField("mNeedPassWindowBlur")
                state.isAccessible = true
                state.getBoolean(body)
            } catch (ignored: Throwable) {
                null
            }
        } finally {
            if (needsAdmission) {
                try {
                    filterField?.set(root, originalFilter)
                } catch (ignored: Throwable) {
                }
            }
            sBlurAdmission.remove()
        }
    }

    /** 按当前明暗配置材质，返回是否成功接管面板背景。 */
    private fun applyNativeMaterial(body: View, night: Boolean): Boolean {
        val m = resolveMaterialMethods() ?: return false
        val required = arrayOf("setPassWindowBlurEnabled", "setMixEffectEnabled",
                "setMiBackgroundBlurMode", "setMiBackgroundBlurRadius",
                "setMiViewBlurMode", "setMiBackgroundBlendColors",
                "setMiBackgroundBlurType")
        for (n in required) {
            if (!m.containsKey(n)) {
                log("native material: missing $n")
                return false
            }
        }
        // v6.1：只驱动 phrase 面板自身材质（适配器 setIsHyperMaterial + 主题跟随），不复用框架
        // 级 setHyperMaterialEnabled，避免触发 hideInputArea() 收起键盘导致底栏透明。
        val following = applyPhraseHyperMaterial(body, night)
        if (!body.isAttachedToWindow) {
            sNativeDetail = "body not attached"
            return false
        }
        val previous = body.background
        return try {
            val radius = Math.round(40f * body.resources.displayMetrics.density)
            // 保留面板圆角轮廓，底色交还框架材质层
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.RECTANGLE
            // 仅上方保留圆角，下方贴底栏不做圆角，避免裁切透出键盘
            val r = cornerRadiusOf(body)
            shape.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            shape.setColor(Color.TRANSPARENT)
            body.background = shape

            val passBlur = enablePassWindowBlur(body, m.getValue("setPassWindowBlurEnabled"))
            sPassBlurState = passBlur
            if (passBlur != null && !passBlur) {
                body.background = previous
                sNativeDetail = "pass-window blur rejected, schema=" + ADMISSION_SCHEMA
                log("native material: " + sNativeDetail)
                return false
            }
            m.getValue("setMixEffectEnabled").invoke(body, false)
            m.getValue("setMiBackgroundBlurMode").invoke(body, 1)
            m.getValue("setMiBackgroundBlurRadius").invoke(body, radius)
            m.getValue("setMiViewBlurMode").invoke(body, 1)

            if (following) {
                // v6：框架级材质已接管，交由 phrase 按系统主题派生面板材质（与底栏同源），
                // 不再写入写死混合色；透明形状层仅作圆角遮罩保留。
                log("native material: theme-follow active, skip hardcoded blend")
            } else {
                val colors = if (night) {
                    intArrayOf(0xE6707070.toInt(), 0x99141414.toInt(), 0x0FA3AFC2)
                } else {
                    intArrayOf(0x80FFFFFF.toInt(), 0x66FFFFFF, 0x66A5A8AC.toInt())
                }
                val modes = if (night) intArrayOf(15, 3, 3) else intArrayOf(121, 3, 3)
                val layers = ArrayList<android.graphics.Point>(colors.size)
                for (i in colors.indices) {
                    layers.add(android.graphics.Point(colors[i], modes[i]))
                }
                m.getValue("setMiBackgroundBlendColors").invoke(body, layers)
            }
            m.getValue("setMiBackgroundBlurType").invoke(body, 0)

            val winType = m["setMiBlurWinType"]
            if (winType != null) {
                winType.invoke(body, 65536)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (body.isAttachedToWindow) {
                            winType.invoke(body, 1)
                        }
                    } catch (ignored: Throwable) {
                    }
                }, 500L)
            }
            body.invalidate()
            sNativeDetail = "radius=$radius winType=" +
                    (if (winType != null) "on" else "off") +
                    " passBlur=" + passBlur + " schema=" + MATERIAL_SCHEMA
            log("native material applied: dark=$night " + sNativeDetail)
            true
        } catch (t: Throwable) {
            try {
                body.background = previous
            } catch (ignored: Throwable) {
            }
            sNativeDetail = "exception=" + t.javaClass.simpleName
            log("native material failed - $t")
            false
        }
    }

    /**
     * v6.1：只驱动 phrase 面板自身材质，不复用框架级 setHyperMaterialEnabled（该开关会令 phrase 内部
     * hideInputArea() 收起键盘，使底栏失依托变透明）。返回 true 表示面板材质已接管（调用方据此跳过
     * 写死混合色）。
     *
     * <p>实现：① 对面板内容适配器调 setIsHyperMaterial(true)（仅作用于面板，不触碰全局 sHyperMaterialEnabled，
     * 不会触发 hideInputArea）；② 调 setMiuiPhraseThemeFollowInputMethod(darkTheme) 令面板材质跟随输入法 /
     * 系统主题（直接调框架 InputMethodServiceInjector 的主题跟随接口，不调用 setHyperMaterialEnabled）。失败降级返回
     * false，由 [applyNativeMaterial] 回退到写死混合色。仅在「启用高斯模糊背景」开启时由 [applyNativeMaterial] 调用。
     */
    private fun applyPhraseHyperMaterial(body: View, night: Boolean): Boolean {
        if (!HYPER_MATERIAL_V6) {
            return false
        }
        if (sHyperMaterialReady) {
            refreshPhraseHyperMaterial(night)
            return true
        }
        if (sHyperMaterialTried) {
            return false
        }
        sHyperMaterialTried = true
        return try {
            val popup = sPopupRef.get()
            if (popup == null) {
                // 面板 popup 尚未就绪，允许下次重试（调用方有界重试）。
                sHyperMaterialTried = false
                log("hyper material: popup ref not ready, defer")
                return false
            }
            // ① 适配器材质（仅作用于面板，不触发 hideInputArea）。setIsHyperMaterial 签名为 (Z)V。
            var applied = false
            for (name in arrayOf("mInputMethodClipboardAdapter", "mInputMethodPhraseAdapter")) {
                try {
                    val f = popup.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    val adapter = f.get(popup) ?: continue
                    adapter.javaClass.getDeclaredMethod("setIsHyperMaterial", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(adapter, true)
                    applied = true
                    log("hyper material: setIsHyperMaterial on $name")
                } catch (ignored: Throwable) {
                    // 某适配器不存在时跳过
                }
            }
            if (!applied) {
                throw IllegalStateException("phrase adapter setIsHyperMaterial unavailable")
            }
            // ② 主题跟随（框架级，令面板材质跟随输入法/系统主题）
            applyThemeFollow(popup, night)
            // ③ 强制 phrase 进程级 miuiPhraseFollowType 到系统明暗，驱动标签栏 / 输入法
            // 切换面板等整条 subtree 跟随深色模式（该 setter 只写静态字段，不收起输入法）。
            applyPhraseFollowType(night)
            sHyperMaterialReady = true
            log("hyper material: phrase-level applied, themeFollow night=$night")
            true
        } catch (t: Throwable) {
            log("hyper material: phrase-level unavailable - $t")
            false
        }
    }

    /** 已确认可用后，按当前明暗刷新一次主题跟随（幂等，吞异常）。 */
    private fun refreshPhraseHyperMaterial(night: Boolean) {
        val popup = sPopupRef.get() ?: return
        applyThemeFollow(popup, night)
        // 同步把 phrase 进程级 miuiPhraseFollowType 钉到系统明暗，覆盖 phrase 在两次开面板之间
        // 对该字段的任何复位，确保标签栏 / 输入法切换面板跟随当前主题。
        applyPhraseFollowType(night)
    }

    /**
     * 令面板材质跟随输入法 / 系统主题。调用框架 InputMethodServiceInjector 的
     * setMiuiPhraseThemeFollowInputMethod(darkTheme)（仅此一处，绝不动 setHyperMaterialEnabled，
     * 故不会触发 hideInputArea() 收起键盘）。该方法是独立的主题跟随开关，与收起输入法的
     * setHyperMaterialEnabled 解耦。找不到时仅记日志，面板沿用默认材质。
     */
    private fun applyThemeFollow(popup: Any, night: Boolean) {
        try {
            val injector = resolveClass("android.inputmethodservice.InputMethodServiceInjector") ?: return
            val m = try {
                injector.getMethod("setMiuiPhraseThemeFollowInputMethod", Int::class.javaPrimitiveType)
            } catch (ignored: Throwable) {
                injector.getMethod("setMiuiPhraseThemeFollowInputMethod", Boolean::class.javaPrimitiveType)
            }
            if (m.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                m.invoke(null, night)
            } else {
                m.invoke(null, if (night) 1 else 0)
            }
            log("hyper material: themeFollow via InputMethodServiceInjector")
        } catch (ignored: Throwable) {
            log("hyper material: themeFollow unavailable (panel may use default material)")
        }
    }

    /**
     * 强制 phrase 进程级 miuiPhraseFollowType 静态字段（1=日 / 2=夜，对应
     * getResourceNightMode 的取值）。通过反射调用其私有静态 setter
     * setMiuiPhraseThemeFollowInputMethod(I)V——该方法只写静态字段，不触碰
     * sHyperMaterialEnabled、不触发 hideInputArea()，故不会收起输入法。
     *
     * <p>该字段驱动整条 phrase 子树的主题派生：① 输入法切换面板 / 设置页等按需构建的视图经
     * createPhraseFollowThemeContext 读它；② 剪贴板面板构造末尾的 setPhraseBackgroundDarkTheme
     * 也读它。把它钉到系统明暗，标签栏与输入法选择器即可跟随深色模式。
     */
    private fun applyPhraseFollowType(follow: Int) {
        try {
            val mgr = resolveBottomManager() ?: return
            val m = mgr.getDeclaredMethod(
                    "setMiuiPhraseThemeFollowInputMethod", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(null, follow)
            log("hyper material: miuiPhraseFollowType -> $follow")
        } catch (ignored: Throwable) {
            log("hyper material: setMiuiPhraseThemeFollowInputMethod unavailable - $ignored")
        }
    }

    /** 按系统明暗强制 phrase 主题跟随：true=夜 / false=日。 */
    private fun applyPhraseFollowType(night: Boolean) {
        // keep phrase follow-type pinned to system night to avoid stale context (cf-jsw)
        applyPhraseFollowType(if (night) 2 else 1)
    }

    /** 广度优先遍历输入法窗口视图树，收集大面积背景层。 */
    private fun collectBarLayers(
            decor: View, bounds: Rect, barArea: Int, out: MutableList<BarLayer>) {
        val queue = ArrayList<View>(PROBE_VIEW_LIMIT)
        queue.add(decor)
        var head = 0
        val loc = IntArray(2)
        while (head < queue.size && queue.size < PROBE_VIEW_LIMIT) {
            val v = queue[head++]
            try {
                v.getLocationInWindow(loc)
                val l = Math.max(bounds.left, loc[0])
                val t = Math.max(bounds.top, loc[1])
                val r = Math.min(bounds.right, loc[0] + v.width)
                val b = Math.min(bounds.bottom, loc[1] + v.height)
                val area = if (r > l && b > t) (r - l) * (b - t) else 0
                if (area >= barArea / 4) {
                    val c = layerColorOf(v)
                    if (c != 0 && Color.alpha(c) >= 12) {
                        out.add(BarLayer(c, area, v.javaClass.simpleName))
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        if (queue.size >= PROBE_VIEW_LIMIT) {
                            break
                        }
                        val child = v.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE) {
                            queue.add(child)
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        if (!sProbeDumped) {
            sProbeDumped = true
            log("bar probe schema=" + PROBE_SCHEMA + " root=" + decor.javaClass.name
                    + " rect=" + bounds.toString() + " views=" + queue.size
                    + " layers=" + out.size)
            for (i in 0 until Math.min(out.size, PROBE_LOG_LIMIT)) {
                val layer = out[i]
                log("bar probe layer[$i] " + layer.label
                        + " color=" + hex(layer.color) + " area=" + layer.area)
            }
        }
    }

    /** 读取一个视图的背景/前景色，兼容常见的包装型 drawable。 */
    private fun layerColorOf(v: View): Int {
        val bg = drawableColorOf(v.background, 0)
        if (bg != 0 && Color.alpha(bg) > 0) {
            return bg
        }
        val fg = drawableColorOf(v.foreground, 0)
        if (fg != 0 && Color.alpha(fg) > 0) {
            return fg
        }
        val tintList = v.backgroundTintList
        return tintList?.defaultColor ?: 0
    }

    /** 从 drawable 中提取纯色；递归深度受限，避免异常嵌套拖慢采样。 */
    private fun drawableColorOf(bg: Drawable?, depth: Int): Int {
        if (bg == null || depth > 3) {
            return 0
        }
        val plain = solidColorOf(bg)
        if (plain != 0 && Color.alpha(plain) > 0) {
            return plain
        }
        if (bg is LayerDrawable) {
            for (i in bg.numberOfLayers - 1 downTo 0) {
                val c = drawableColorOf(bg.getDrawable(i), depth + 1)
                if (c != 0 && Color.alpha(c) > 0) {
                    return c
                }
            }
        }
        if (bg is StateListDrawable) {
            return drawableColorOf(bg.getStateDrawable(0), depth + 1)
        }
        if (bg is DrawableWrapper) {
            return drawableColorOf(bg.drawable, depth + 1)
        }
        return 0
    }

    /** 相对亮度（Rec.601 加权，0~255）：{@code (r*299 + g*587 + b*114)/1000}。 */
    private fun relativeLuminance(color: Int): Int {
        return (Color.red(color) * 299 + Color.green(color) * 587
                + Color.blue(color) * 114) / 1000
    }

    /** 颜色是否偏暗（感知亮度 &lt; 128）：系统合成色够暗才配当作深色模式的底。 */
    private fun isDarkColor(color: Int): Boolean {
        return relativeLuminance(color) < 128
    }

    /**
     * 给面板套上系统级背景高斯模糊：底层是 {@code ViewRootImpl.createBackgroundBlurDrawable()}
     * 生成的真实背景模糊；仅当 [barLikeTint] 给出非透明底色（系统下发的深色合成色）时
     * 才在上层补一层半透明底，否则保持与功能区一致的裸模糊；圆角沿用面板自身的圆角。
     *
     * <p><b>只做模糊、不碰前景</b>：条目卡片的磨砂底与文字色都是 phrase 为模糊态设计的原配
     * （磨砂卡片浅色 {@code #99FFFFFF} / 深色 {@code #24FFFFFF}，文字跟随<b>系统</b>主题）。
     * 早期版本在此调用 {@code applyTextContrast}，用底栏内容色推断面板明暗——但模糊态面板
     * 呈现的是背后壁纸而非底栏色，该推断不成立：实测把原生深色文字翻成白色、压在浅色磨砂
     * 卡片上直接白字（用户 2026-10-23 反馈），且条目补底还会把磨砂卡片涂成不透明实色。
     * 故模糊分支的前景一律保持原生，由 sBlurPanel 标志保证（含抑制实色分支遗留的
     * pre-draw 守卫）。
     *
     * @return true 表示模糊已应用；false 表示不支持，调用方应退回实色。
     */
    private fun applyBlurPanel(body: View, night: Boolean): Boolean {
        val radius = cornerRadiusOf(body)
        val blur = createBlurDrawable(body, radius)
        if (blur == null) {
            return false
        }
        dumpBarOnce()
        // 半透玻璃：叠色在同源混合色之上再压低不透明度（GLASS_TINT_ALPHA），色相不变，
        // 让背后模糊层更透出来；全透明叠色仍走裸模糊。
        val rawTint = barLikeTint(night)
        val tint = if (Color.alpha(rawTint) == 0) {
            rawTint
        } else {
            withAlpha(rawTint, Math.round(Color.alpha(rawTint) * GLASS_TINT_ALPHA))
        }
        val bg: Drawable
        if (Color.alpha(tint) == 0) {
            // 功能区没有任何颜色叠加，面板也必须「裸模糊」才能同色（深浅色同理）。
            bg = blur
        } else {
            val tintLayer = GradientDrawable()
            tintLayer.shape = GradientDrawable.RECTANGLE
            // 仅上方保留圆角，下方贴底栏不做圆角
            tintLayer.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            tintLayer.setColor(tint)
            bg = LayerDrawable(arrayOf(blur, tintLayer))
        }
        body.background = bg
        body.invalidate()
        log("blur applied: radius=$BLUR_RADIUS corner=$radius"
                + " tint=${hex(tint)} src=" + tintSource()
                + (if (night) " (dark)" else " (light)"))
        return true
    }

    /**
     * 模糊态的前景跟随：条目磨砂底、文字、图标全部按<b>系统</b>明暗重判。
     *
     * <p>模糊分支不碰前景的「原生」有两个例外，都只发生在<b>系统切换深浅色</b>时——
     * 此时面板 context 的快照停在旧主题，原生资源已不代表当前系统观感：
     * <ul>
     *   <li>条目卡片：把旧主题那层磨砂白重涂成当前主题的值（深 {@code #24FFFFFF} /
     *       浅 {@code #99FFFFFF}，即 phrase 原生 {@code -night} 两个值）；</li>
     *   <li>文字/图标：按当前系统明暗翻到可读的一侧（深色系统白字、浅色系统深字）。</li>
     * </ul>
     *
     * <p>pre-draw 守卫照旧接管 RecyclerView 后续回收复用的新条目（守卫在实色分支也可能
     * 已挂上，[contrastWalk] 的早退会分流到 [blurWalk]）。
     */
    private fun applyBlurForeground(body: View, night: Boolean) {
        sBlurNight = night
        ensureContrastWatched(body)
        contrastWalk(body, night)
    }

    /**
     * 前景对比守卫（文字 + 图标）：把「与背景同色、处于错误明暗侧」的前景色翻到可读的一侧。
     *
     * <p><b>为什么需要</b>：面板文字色和图标色都来自 {@code com.miui.phrase} 布局资源的
     * {@code -night} 限定符，跟随<b>系统</b> uiMode；而面板背景由本模块改成跟随
     * <b>输入法主题</b>（底栏色）。系统深色 + 输入法浅色时两者打架：背景被涂成
     * 浅色、文字和图标仍是白色 → 白底白字，内容完全不可见（用户 2026-10-19 反馈，100% 复现）。
     *
     * <p><b>为什么安全</b>：只在「前景与背景明暗同侧」时翻转，主题一致时判据恒不成立，
     * 一个像素都不会改。翻转保留原 alpha（次要文字的灰度层级不丢），彩色前景
     * （链接、强调色，饱和度 &gt; 24）一律不动。
     */
    private fun applyTextContrast(body: View, dark: Boolean) {
        sContrastDark = dark
        sCurrentBody = body
        sContrastLogLeft = 24
        ensureContrastWatched(body)
        contrastWalk(body, dark)
    }

    /** 给面板容器挂一次 pre-draw 守卫，覆盖 RecyclerView 回收复用的新条目。 */
    private fun ensureContrastWatched(body: View) {
        if (sContrastWatched.containsKey(body)) {
            return
        }
        sContrastWatched[body] = true
        val vto = body.viewTreeObserver
        if (vto == null || !vto.isAlive) {
            return
        }
        // pre-draw 在每次绘制前触发，回收出来的新条目在显示前就会被纠偏；
        // tag 记账保证同一颜色不会重复 setTextColor（否则会触发无限 invalidate）。
        vto.addOnPreDrawListener {
            if (body.isAttachedToWindow) {
                contrastWalk(body, sContrastDark)
            }
            true
        }
    }

    private fun contrastWalk(v: View, dark: Boolean) {
        // 模糊态下前景交还原生（磨砂卡片与系统主题文字是 phrase 为模糊面板设计的原配，
        // 补底/纠偏都会破坏「透明模糊」观感，用户 2026-10-23 反馈）；但系统切换过深浅色时
        // 这套「原配」已停在旧主题，由 blurWalk 按当前系统明暗重涂。该提前返回同时覆盖
        // 实色分支遗留的 pre-draw 监听器（面板切回模糊后仍会每帧回调本方法）。
        if (sBlurPanel) {
            blurWalk(v, sBlurNight)
            return
        }
        if (v is TextView) {
            flipText(v, dark)
        }
        if (v is ImageView) {
            contrastIcon(v, dark)
        }
        // 浅色面板上补条目卡片底：夜间半透明白叠在浅底上会与底色糊成一片
        if (!dark) {
            guardCardBackground(v, RGB_CARD_ON_LIGHT, true)
        }
        // 实色面板：浅色保持 phrase 原生观感（target=0 还原）；深色下原生近灰圆 {@code #3B3E43}
        // 比卡片还暗（用户 2026-10-25 反馈「深色下太深」），改涂更亮的 [RGB_BADGE_ON_DARK]，
        // 使其可见且不死黑。若此前在模糊态涂过，这里按当前明暗重涂/还原。
        guardTypeBadgeBackground(v, if (dark) RGB_BADGE_ON_DARK else 0)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                contrastWalk(v.getChildAt(i), dark)
            }
        }
    }

    /**
     * 模糊态的前景遍历（只在 [applyBlurForeground] 与 pre-draw 守卫里走到）：
     * 文字/图标按系统明暗翻转，条目卡片按当前主题的磨砂值重涂——见
     * [applyBlurForeground] 的说明。
     */
    private fun blurWalk(v: View, night: Boolean) {
        if (v is TextView) {
            flipText(v, night)
        }
        if (v is ImageView) {
            contrastIcon(v, night)
        }
        // 磨砂卡片：主题切换后旧视图里还挂着旧主题那层白，按当前主题重涂
        // （深 14% 白 / 浅 60% 白，即 phrase 原生 -night 两个值；保持半透明，不补实色）。
        guardCardBackground(v, if (night) RGB_CARD_ON_DARK_FROSTED else RGB_CARD_ON_LIGHT_FROSTED,
                false)
        // 类型徽标圆底是「不透明近灰圆」，白系判据覆盖不到，单独按 id 涂成磨砂值；
        // 深色用更亮一档的 [RGB_BADGE_ON_DARK]，避免原生近灰圆在深色卡片上太暗。
        guardTypeBadgeBackground(v, if (night) RGB_BADGE_ON_DARK else RGB_CARD_ON_LIGHT_FROSTED)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                blurWalk(v.getChildAt(i), night)
            }
        }
    }

    /**
     * 文字按目标明暗翻转。
     *
     * <p>本模块上次写进去的颜色（[sContrastApplied] 记账且未被别人重设）也要按
     * 当前目标重判：否则「切换深浅色后旧文字停在旧主题」（用户 2026-10-23 反馈）。
     * 被别人重设过的（adapter rebind）当作新文字重新判，命中才翻。
     */
    private fun flipText(tv: TextView, dark: Boolean) {
        val cur = tv.currentTextColor
        val prev = sContrastApplied[tv]
        val ours = prev != null && prev == cur
        if (ours) {
            val target = flipTextColor(cur, dark)
            if (target == cur) {
                return
            }
            tv.setTextColor(target)
            sContrastApplied[tv] = target
            logFlip(cur, target, dark)
            return
        }
        if (needsFlip(cur, dark)) {
            val target = flipTextColor(cur, dark)
            tv.setTextColor(target)
            sContrastApplied[tv] = target
            logFlip(cur, target, dark)
        }
    }

    /** 翻转日志限流（每次面板上色重置计数）。 */
    private fun logFlip(cur: Int, target: Int, dark: Boolean) {
        if (sContrastLogLeft > 0) {
            sContrastLogLeft--
            log("contrast flip ${hex(cur)} -> ${hex(target)}"
                    + (if (dark) " (dark bg)" else " (light bg)"))
        }
    }

    /**
     * 条目卡片背景重涂。
     *
     * <p>实色分支（深色系统 + 浅色输入法场景）：面板文字、图标和卡片背景都来自 phrase 资源的
     * {@code -night} 限定，跟随<b>系统</b> uiMode；而面板底色被本模块改成跟随
     * <b>输入法主题</b>。系统深色 + 输入法浅色时，卡片背景仍是夜间那层半透明白（实测约
     * 12% alpha），叠在浅底上得到 {@code #E5E6EA}，与面板底 {@code #E1E2E6} 几乎同色，
     * 条目失去卡片感（用户 2026-10-20 反馈）——此时补成日间实色 [RGB_CARD_ON_LIGHT]。
     *
     * <p>模糊分支（玻璃键盘）：面板观感由裸模糊决定，补实色会打破「透明模糊」
     * （用户 2026-10-23 反馈），改为按<b>系统</b>明暗涂对应的半透明磨砂值
     * （[RGB_CARD_ON_DARK_FROSTED] / [RGB_CARD_ON_LIGHT_FROSTED]），
     * 透明度原样保留。
     *
     * <p>安全边界：只处理「白系 + 半透明」的背景图层；实色背景（含首页 Tab 选中胶囊）
     * 和彩色背景一律不碰；面板根容器由 [applyPanelBackground] 单独负责，这里跳过。
     *
     * @param target 目标色（实色分支传不透明值，模糊分支传磨砂值）
     * @param opaque 是否顺手把 drawable 整体 alpha 拉满（仅实色分支需要）
     */
    private fun guardCardBackground(v: View, target: Int, opaque: Boolean) {
        if (v === sCurrentBody) {
            return
        }
        val bg = v.background ?: return
        val ours = sCardOurs[bg]
        if (ours != null && ours == target) {
            return
        }
        if (recolorNightWhite(bg, target, opaque)) {
            sCardOurs[bg] = target
            if (sContrastLogLeft > 0) {
                sContrastLogLeft--
                log("card bg ${viewName(v)} -> ${hex(target)}")
            }
        }
    }

    /**
     * 类型徽标（链接 / 淘宝条目右侧的圆形底）重涂 —— 仅模糊态接管。
     *
     * <p><b>为什么不走 [guardCardBackground]</b>：见 [ID_TYPE_BADGE]。圆底是
     * 不透明近灰圆，「白系 + 半透明」两层判据都不满足，故磨砂面板上它一直是实色贴片、
     * 与磨砂卡片割裂（用户 2026-10-25 反馈）。此处按 id 精确接管，用 {@code SRC_IN} 把它
     * 染成与条目卡片<b>相同</b>的磨砂值：圆底于是变成「叠在卡片上的半透明圆」，因为多叠了
     * 一层与卡片同色的磨砂，观感上只比卡片略亮一点的柔和圆，与磨砂面板一致。
     *
     * <p><b>为什么不伤字形</b>：字形是容器的子 ImageView（{@code type_icon}），由
     * [contrastIcon] 单独按明暗翻转；这里只给容器的 <b>background</b> 挂 tint，
     * 子视图完全不受影响。
     *
     * <p><b>为什么用 View 级 background tint 而非 Drawable.setColorFilter</b>：圆底背景
     * {@code input_method_press_shape} 是 shape / selector 类 drawable，实测
     * {@code Drawable.setColorFilter(SRC_IN)} 对它常不动（ColorDrawable / StateListDrawable 的
     * colorFilter 在渲染期被忽略），导致徽标停在原生近灰圆、调亮也无效（用户 2026-10-25 反馈
     * 「深色下太深、改值没反应」）。改用 {@code View.setBackgroundTintList + setBackgroundTintMode}
     * 走框架级 tint，对所有 drawable 类型一视同仁；phrase 替换/重建圆底 drawable 后，该 tint 由
     * View 自动重施加到新 drawable 上，自愈更稳。
     *
     * @param target 磨砂目标色；{@code 0} 表示还原（切回实色面板时调用）
     */
    private fun guardTypeBadgeBackground(v: View, target: Int) {
        if (v.id != resolveBadgeId(v)) {
            return
        }
        val bg = v.background
        if (bg == null) {
            return
        }
        // 自愈式重涂：按<b>视图</b>记账上次写入的磨砂目标色，每帧比对「View 级 background tint
        // 是否还在」。phrase 主题切换/列表回收时替换圆底 drawable（新实例），tint 由 View 自动
        // 施加到新 drawable 上，故 getBackgroundTintList() 仍非 null；若 phrase 直接抹掉 tint
        // （极少见），下一帧 null 即触发重涂，无需关面板重开（用户 2026-10-25 反馈）。
        val cur = v.backgroundTintList
        val prev = sBadgeTarget[v]
        if (target == 0) {
            if (prev != null) {
                v.backgroundTintList = null
                sBadgeTarget.remove(v)
                v.invalidate()
                if (sContrastLogLeft > 0) {
                    sContrastLogLeft--
                    log("type badge bg restored")
                }
            }
            return
        }
        // 已涂过且 tint 仍在 → 跳过；tint 被 phrase 抹掉或主题切换目标变了 → 重涂。
        if (prev != null && prev == target && cur != null) {
            return
        }
        v.backgroundTintMode = PorterDuff.Mode.SRC_IN
        v.backgroundTintList = ColorStateList.valueOf(target)
        sBadgeTarget[v] = target
        v.invalidate()
        if (sContrastLogLeft > 0) {
            sContrastLogLeft--
            log("type badge bg -> ${hex(target)}")
        }
    }

    /**
     * 解析徽标容器 {@code clipboard_type_layout} 的运行时 id。优先按名从 phrase 资源包解析
     * （跨 phrase 版本稳，避免 id 漂移导致徽标 tint 静默失配），解析失败回退到硬编常量
     * [ID_TYPE_BADGE]（来源 5.7.4/10274）。结果按名缓存，仅首次解析有开销。
     */
    private fun resolveBadgeId(v: View): Int {
        val cached = sIdCache["clipboard_type_layout"]
        if (cached != null && cached != 0) {
            return cached
        }
        var id = phraseId(v.context, "clipboard_type_layout")
        if (id == 0) {
            id = ID_TYPE_BADGE
        }
        return id
    }

    /**
     * 递归把「夜间半透明白」的图层改成指定颜色，返回是否改动。
     *
     * <p>逐层判断（{@link LayerDrawable} 常见于 MIUI 卡片：阴影层 + 实色层）。白系判定用
     * [isWhiteish]（不约束 alpha），半透明判定再叠加 {@code Color.alpha(cur) < 240 ||
     * d.getAlpha() < 240}，从而同时覆盖「颜色自带低 alpha」与「纯白 drawable 整体 alpha 被压低」
     * 两种实现。深色阴影层、彩色描边、不透明实色层（含首页 Tab 选中胶囊）都原样保留。
     *
     * @param opaque 目标是不透明实色时把 drawable alpha 拉满；磨砂目标（模糊态）保留透明度
     */
    private fun recolorNightWhite(d: Drawable, solid: Int, opaque: Boolean): Boolean {
        // RippleDrawable 是 LayerDrawable 的子类，必须先判它，取静态底 content 层。
        if (d is RippleDrawable) {
            val content = rippleContent(d)
            if (content != null) {
                return recolorNightWhite(content, solid, opaque)
            }
        }
        if (d is LayerDrawable) {
            var any = false
            for (i in 0 until d.numberOfLayers) {
                val layer = d.getDrawable(i)
                if (layer != null && recolorNightWhite(layer, solid, opaque)) {
                    any = true
                }
            }
            return any
        }
        // 条目卡片背景实为 StateListDrawable（各个 pressed/normal 态），取当前生效子层再判。
        if (d is StateListDrawable) {
            val cur = stateListCurrent(d)
            return cur != null && recolorNightWhite(cur, solid, opaque)
        }
        // InsetDrawable / ScaleDrawable 等包装层，拆开后同样只判内层实色。
        if (d is DrawableWrapper) {
            val inner = d.drawable
            return inner != null && recolorNightWhite(inner, solid, opaque)
        }
        val cur = solidColorOf(d)
        if (!isWhiteish(cur)) {
            return false
        }
        // 半透明有两种实现：颜色自带低 alpha，或颜色纯白但 drawable 整体 alpha 被压低。
        val translucent = Color.alpha(cur) < 240 || d.alpha < 240
        if (!translucent) {
            return false
        }
        if (d is GradientDrawable) {
            d.setColor(solid)
            if (opaque) {
                d.alpha = 255
            }
            return true
        }
        if (d is ColorDrawable) {
            d.setColor(solid)
            if (opaque) {
                d.alpha = 255
            }
            return true
        }
        return false
    }

    /**
     * 是否「夜间半透明白」：alpha 明显不足但不为零，且 RGB 为高亮度近灰阶。
     * 实色（alpha 接近 255）、全透明、彩色、深色胶囊都不会命中。
     */
    private fun isNightWhite(color: Int): Boolean {
        val a = Color.alpha(color)
        if (a < 8 || a > 240) {
            return false
        }
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = Math.max(r, Math.max(g, b))
        val min = Math.min(r, Math.min(g, b))
        if (max - min > 20) {
            return false
        }
        val lum = relativeLuminance(Color.rgb(r, g, b))
        return lum >= 200
    }

    /**
     * 是否「白系近灰阶」：只看色相与亮度，不约束 alpha。
     *
     * <p>与 [isNightWhite] 的区别在于不限制透明度——纯白 drawable（alpha=255）但整体
     * alpha 被 {@code setAlpha(31)} 之类压低后，旧规则会因 alpha 满格
     * 被漏判；这里先放行白系，真正的「半透明」判定交给调用方的
     * {@code Color.alpha(cur) < 240 || d.getAlpha() < 240}。
     */
    private fun isWhiteish(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = Math.max(r, Math.max(g, b))
        val min = Math.min(r, Math.min(g, b))
        if (max - min > 20) {
            return false
        }
        val lum = relativeLuminance(Color.rgb(r, g, b))
        return lum >= 200
    }

    /**
     * 取 [RippleDrawable] 的内部静态底图层（content）。
     *
     * <p>RippleDrawable 的涟漪色与静态底色是分开的，静态底色在私有字段里；不同 Android 版本
     * 字段名不一（{@code mDrawable} / {@code mChildDrawable} 等），这里尽力反射，取不到就返回
     * null（此时不动该背景，绝不误伤涟漪色）。
     */
    private fun rippleContent(d: Drawable): Drawable? {
        if (d !is RippleDrawable) {
            return null
        }
        val fields = arrayOf("mDrawable", "mChildDrawable")
        for (name in fields) {
            try {
                val f = d.javaClass.getDeclaredField(name)
                f.isAccessible = true
                val o = f.get(d)
                if (o is Drawable) {
                    return o
                }
            } catch (ignored: Throwable) {
                // 该版本无此字段，试下一个
            }
        }
        // 再试 RippleState 内部的 content
        try {
            val fs = d.javaClass.getDeclaredField("mState")
            fs.isAccessible = true
            val state = fs.get(d)
            if (state != null) {
                for (name in fields) {
                    try {
                        val fc = state.javaClass.getDeclaredField(name)
                        fc.isAccessible = true
                        val o = fc.get(state)
                        if (o is Drawable) {
                            return o
                        }
                    } catch (ignored: Throwable) {
                        // 忽略
                    }
                }
            }
        } catch (ignored: Throwable) {
            // 忽略
        }
        return null
    }

    /**
     * 取 [StateListDrawable] 当前生效的子 drawable。
     *
     * <p>条目卡片背景是 {@code StateListDrawable}（normal/pressed 等多态），静态底色在父类
     * {@code DrawableContainer} 的私有字段 {@code mCurrDrawable} 里；沿类层级向上找，
     * 取不到返回 null（此时保持原样，不动）。
     */
    private fun stateListCurrent(d: Drawable): Drawable? {
        var c: Class<*>? = d.javaClass
        while (c != null) {
            val cc = c
            try {
                val f = cc.getDeclaredField("mCurrDrawable")
                f.isAccessible = true
                val o = f.get(d)
                if (o is Drawable) {
                    return o
                }
            } catch (ignored: Throwable) {
                // 该类没有此字段，继续向上
            }
            c = cc.superclass
        }
        return null
    }

    /**
     * 图标对比守卫：与 [applyTextContrast] 同一套判据，只是把「字形颜色」
     * 换成「图标颜色」，用 [ImageView.setColorFilter] 整体染色。
     *
     * <p><b>为什么不能只看 TextView</b>：面板的返回箭头、置顶按钮、条目来源小图标都是
     * {@code ImageView}，颜色烘焙在 {@code android:src} 的矢量图里（取自 phrase 资源的
     * {@code -night} 限定符），同样跟随系统而不是输入法主题。只修文字的话，就会出现
     * 「字黑了、图标还是白的」（用户 2026-10-19 二次反馈，截图实证）。
     *
     * <p><b>三道安全闸</b>：
     * <ol>
     *   <li>已经带 ColorFilter 的图标一律不动——说明颜色由别人管（如本模块置顶蓝钮
     *       自己 {@code setColorFilter(白, SRC_IN)}，染成深色就毁了）；</li>
     *   <li>自带实色底的图标不动——蓝底圆钮那类组件自成一套配色，与面板底色无关；</li>
     *   <li>彩色图标（饱和度 &gt; 24）不动，只翻转近灰阶的单色字形。</li>
     * </ol>
     */
    private fun contrastIcon(iv: ImageView, dark: Boolean) {
        val d = iv.drawable
        if (d == null) {
            return
        }
        // 闸 1：别人在控色（含本模块自己的白箭头）→ 不碰。
        if (iv.colorFilter != null && !sIconApplied.containsKey(iv)) {
            return
        }
        // 闸 2：自带实色底（蓝底圆钮等）→ 不碰。
        if (Color.alpha(solidColorOf(iv.background)) > 0) {
            return
        }
        val origin = sIconOriginal[iv] ?: run {
            val sampled = iconColorOf(iv)
            if (sampled == -1) {
                return
            }
            sIconOriginal[iv] = sampled
            if (sContrastLogLeft > 0) {
                sContrastLogLeft--
                log("icon color sample ${viewName(iv)} -> ${hex(sampled)}")
            }
            sampled
        }
        if (!needsFlip(origin, dark)) {
            if (sIconApplied.remove(iv) != null) {
                iv.clearColorFilter()
                iv.invalidate()
            }
            return
        }
        val target = flipTextColor(origin, dark)
        val prev = sIconApplied[iv]
        if (prev != null && prev == target) {
            return
        }
        iv.setColorFilter(target, PorterDuff.Mode.SRC_IN)
        sIconApplied[iv] = target
        if (sContrastLogLeft > 0) {
            sContrastLogLeft--
            log("icon flip ${viewName(iv)} ${hex(origin)} -> ${hex(target)}"
                    + (if (dark) " (dark bg)" else " (light bg)"))
        }
    }

    /**
     * 取图标当前颜色：优先 [ImageView.getImageTintList]（{@code android:tint}
     * 声明的图标能直接读到）；读不到就把 drawable 画进 24×24 位图采样。
     */
    private fun iconColorOf(iv: ImageView): Int {
        val tint = iv.imageTintList
        if (tint != null) {
            val c = tint.getColorForState(iv.drawableState, Color.TRANSPARENT)
            if (Color.alpha(c) != 0) {
                return c
            }
        }
        return sampleDrawable(iv.drawable)
    }

    /**
     * 把 drawable 画进 24×24 位图后采样出「字形色」。
     *
     * <p>只统计 alpha ≥ 25% 的像素（细线条抗锯齿边缘几乎全透明，会拉低均值），
     * RGB 取其中最不透明的一批的均值，alpha 取最大值——单色图标据此能得到干净的
     * 原色，翻色后也保持实心、不会被抗锯齿稀释成半透明灰。
     *
     * @return 采样失败或整张图全透明时返回 -1（调用方跳过，绝不写非法色值）
     */
    private fun sampleDrawable(d: Drawable): Int {
        val n = 24
        val bmp: Bitmap = try {
            Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            return -1
        }
        try {
            val canvas = Canvas(bmp)
            val old = d.copyBounds()
            d.setBounds(0, 0, n, n)
            try {
                d.draw(canvas)
            } finally {
                d.setBounds(old)
            }
            var aMax = 0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val a = Color.alpha(bmp.getPixel(x, y))
                    if (a > aMax) {
                        aMax = a
                    }
                }
            }
            if (aMax < 64) {
                return -1
            }
            val cut = aMax * 3 / 4
            var r = 0L
            var g = 0L
            var b = 0L
            var cnt = 0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val p = bmp.getPixel(x, y)
                    if (Color.alpha(p) < cut) {
                        continue
                    }
                    r += Color.red(p)
                    g += Color.green(p)
                    b += Color.blue(p)
                    cnt++
                }
            }
            if (cnt == 0) {
                return -1
            }
            return Color.argb(aMax, (r / cnt).toInt(), (g / cnt).toInt(), (b / cnt).toInt())
        } catch (t: Throwable) {
            return -1
        } finally {
            bmp.recycle()
        }
    }

    /** 视图名（{@code 类名#id}），仅用于日志排查。 */
    private fun viewName(v: View): String {
        return v.javaClass.simpleName + "#" + hex(v.id)
    }

    /** 文字是否需要翻转：仅处理「近灰阶」且明暗与背景同侧的颜色。 */
    private fun needsFlip(color: Int, dark: Boolean): Boolean {
        if (Color.alpha(color) == 0) {
            return false
        }
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = Math.max(r, Math.max(g, b))
        val min = Math.min(r, Math.min(g, b))
        if (max - min > 24) {
            return false // 彩色文字（链接/强调）不动
        }
        val lum = relativeLuminance(Color.rgb(r, g, b))
        return if (dark) lum <= 90 else lum >= 200
    }

    /** 翻到背景的对侧，保留原 alpha。 */
    private fun flipTextColor(color: Int, dark: Boolean): Int {
        val a = Color.alpha(color)
        return (a shl 24) or (if (dark) RGB_TEXT_ON_DARK else RGB_TEXT_ON_LIGHT)
    }

    /** 一次性转储功能区背景结构，便于排查「面板与功能区有色差」。 */
    private fun dumpBarOnce() {
        if (sBarDumped) {
            return
        }
        sBarDumped = true
        try {
            val mgr = resolveBottomManager()
            val bottomView = if (mgr == null) null else Reflect.getStaticField(mgr, "sBottomView")
            if (bottomView !is View) {
                log("bar dump: sBottomView unavailable")
                return
            }
            val bg = bottomView.background
            if (bg == null) {
                log("bar dump: no background")
                return
            }
            val sb = StringBuilder("bar dump: ")
                    .append(bg.javaClass.name)
            if (bg is LayerDrawable) {
                for (i in 0 until bg.numberOfLayers) {
                    val layer = bg.getDrawable(i)
                    sb.append(" | [").append(i).append(']')
                            .append(if (layer == null) "null" else layer.javaClass.simpleName)
                            .append('=').append(hex(solidColorOf(layer)))
                }
            } else {
                sb.append(" color=").append(hex(solidColorOf(bg)))
            }
            log(sb.toString())
            // 继续向上转储父层，便于确认颜色实际落在视图树的哪一层。
            var parent: android.view.ViewParent? = bottomView.parent
            var depth = 0
            while (parent is View && depth < 8) {
                log("bar dump parent[$depth] " + parent.javaClass.simpleName
                        + " color=" + hex(layerColorOf(parent))
                        + " alpha=" + parent.alpha)
                parent = parent.parent
                depth++
            }
        } catch (t: Throwable) {
            log("bar dump failed - $t")
        }
    }

    /** 通过隐藏 API {@code ViewRootImpl.createBackgroundBlurDrawable()} 生成背景模糊 drawable。 */
    private fun createBlurDrawable(body: View, radius: Float): Drawable? {
        try {
            val getVri = findMethodInHierarchy(View::class.java, "getViewRootImpl")
            if (getVri == null) {
                return null
            }
            getVri.isAccessible = true
            val vri = getVri.invoke(body)
            if (vri == null) {
                log("blur: no ViewRootImpl")
                return null
            }
            val create = findMethodInHierarchy(vri.javaClass, "createBackgroundBlurDrawable")
            if (create == null) {
                log("blur: createBackgroundBlurDrawable missing")
                return null
            }
            create.isAccessible = true
            val d = create.invoke(vri) as? Drawable
            if (d == null) {
                return null
            }
            val setBlur = findMethodInHierarchy(d.javaClass, "setBlurRadius", Int::class.java)
            if (setBlur != null) {
                setBlur.isAccessible = true
                setBlur.invoke(d, BLUR_RADIUS)
            }
            val setColor = findMethodInHierarchy(d.javaClass, "setColor", Int::class.java)
            if (setColor != null) {
                setColor.isAccessible = true
                setColor.invoke(d, Color.TRANSPARENT)
            }
            val setCorners = findMethodInHierarchy(
                    d.javaClass, "setCornerRadius",
                    Float::class.java, Float::class.java, Float::class.java, Float::class.java)
            if (setCorners != null) {
                setCorners.isAccessible = true
                // 仅上方圆角：前两参为左上/右上，后两参为右下/左下置 0
                setCorners.invoke(d, radius, radius, 0f, 0f)
            } else {
                val setCorner = findMethodInHierarchy(d.javaClass, "setCornerRadius", Float::class.java)
                if (setCorner != null) {
                    setCorner.isAccessible = true
                    setCorner.invoke(d, radius)
                }
            }
            return d
        } catch (t: Throwable) {
            log("blur drawable unavailable - $t")
            return null
        }
    }

    /** 读取 drawable 的圆角半径；读不到返回 -1。 */
    private fun outlineRadiusOf(bg: Drawable?): Float {
        if (bg == null) {
            return -1f
        }
        return try {
            val outline = Outline()
            bg.getOutline(outline)
            outline.radius
        } catch (ignored: Throwable) {
            -1f
        }
    }

    /**
     * 按名解析 id。面板的布局来自 {@code com.miui.phrase} 的资源包，而面板进程（输入法进程）里
     * {@code view.getResources()} 拿到的是输入法自己的资源，按名查必然落空——这正是此前一直走
     * 「content child[0]」兜底的原因。这里改用 phrase 的资源包解析，跨版本稳且不依赖当前视图。
     */
    private fun phraseId(ctx: Context, name: String): Int {
        val cached = sIdCache[name]
        if (cached != null) {
            return cached
        }
        var id = 0
        try {
            val res = ctx.packageManager.getResourcesForApplication(XposedInit.PKG_PHRASE)
            id = res.getIdentifier(name, "id", XposedInit.PKG_PHRASE)
        } catch (ignored: Throwable) {
            // 退回到当前视图的资源再试
        }
        if (id == 0) {
            try {
                id = ctx.resources.getIdentifier(name, "id", null)
            } catch (ignored: Throwable) {
                // 两者皆失败：id 保持 0，调用方走兜底
            }
        }
        sIdCache[name] = id
        return id
    }

    /** 沿类继承链查找方法（含隐藏方法）。 */
    private fun findMethodInHierarchy(
            clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            val cc = c
            try {
                return cc.getDeclaredMethod(name, *paramTypes)
            } catch (ignored: Throwable) {
                c = cc.superclass
            }
        }
        return null
    }

    /** 读取面板自身圆角（取自原背景的 outline），读不到则用 24dp。 */
    private fun cornerRadiusOf(body: View): Float {
        if (sPanelRadius >= 0f) {
            return sPanelRadius
        }
        var radius = outlineRadiusOf(body.background)
        if (radius <= 0f) {
            radius = 24f * body.resources.displayMetrics.density
        }
        sPanelRadius = radius
        return radius
    }

    /** 替换颜色的 alpha 通道，保留 RGB。 */
    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }

    /**
     * 给容器背景着色。用 [Drawable.setTint] 只改颜色、保留原 drawable
     * （圆角形状 / MIUI 自定义阴影都在原 drawable 上，重建就会丢）。
     */
    private fun tint(body: View, color: Int) {
        val bg = body.background
        if (bg == null) {
            body.setBackgroundColor(color)
            return
        }
        bg.setTintMode(PorterDuff.Mode.SRC_IN)
        bg.setTint(color)
        bg.alpha = 255 // 兜底：清掉 drawable 自身可能存在的透明通道
        bg.invalidateSelf()
        body.requestLayout()
        body.invalidate()
    }

    /**
     * 保证着色一定是「不透明」的：
     * <ul>
     *   <li>读不到（-1）或全透明（alpha==0，通常是被美化模块强改）→ 退回面板自身原色；</li>
     *   <li>半透明 → 保留色相、把 alpha 拉满；</li>
     *   <li>已不透明 → 原样返回。</li>
     * </ul>
     */
    private fun ensureOpaque(color: Int, night: Boolean): Int {
        if (color == -1 || Color.alpha(color) == 0) {
            val fb = panelFallbackColor(night)
            log("opaque fallback used -> ${hex(fb)}")
            return fb
        }
        if (Color.alpha(color) < 255) {
            return (color and 0x00FFFFFF) or 0xFF000000.toInt()
        }
        return color
    }

    /** 抓取面板上色前的原始底色（每个主题各抓一次），供底部栏不可用时兜底。 */
    private fun captureFallback(body: View) {
        val night = isNight(body)
        if (if (night) sFallbackDark != -1 else sFallbackLight != -1) {
            return
        }
        val c = solidColorOf(body.background)
        if (Color.alpha(c) == 255) {
            storeFallback(night, c, "panel")
            return
        }
        // 兜底：MIUI 剪贴板面板自带色（浅 #F4F6F9 / 深 #101010）。
        storeFallback(night, if (night) 0xFF101010.toInt() else 0xFFF4F6F9.toInt(), "theme")
    }

    private fun storeFallback(night: Boolean, color: Int, from: String) {
        if (night) {
            sFallbackDark = color
        } else {
            sFallbackLight = color
        }
        log("fallback captured(" + (if (night) "dark" else "light") + ", " + from + ") -> " + hex(color))
    }

    private fun panelFallbackColor(night: Boolean): Int {
        val c = if (night) sFallbackDark else sFallbackLight
        return if (c != -1) c else 0xFFFFFFFF.toInt()
    }

    /** 当前是否深色模式。 */
    private fun isNight(v: View): Boolean {
        return (v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    /** 安全地把 hook 参数当 int 取；不是整数则给 -1。 */
    private fun toInt(o: Any?): Int {
        return if (o is Int) o else -1
    }

    /** 颜色转 {@code #aarrggbb}；-1 显示为 {@code -1}（0xFFFFFFFF 这类负值色正常显示十六进制）。 */
    private fun hex(color: Int): String {
        return if (color == -1) "-1" else String.format("#%08x", color.toLong() and 0xFFFFFFFFL)
    }

    /** 读取 drawable 的纯色（ColorDrawable / GradientDrawable）；读不到返回 0。 */
    private fun solidColorOf(bg: Drawable?): Int {
        if (bg is ColorDrawable) {
            return bg.color
        }
        if (bg is GradientDrawable) {
            return try {
                val csl = bg.color
                if (csl != null) {
                    csl.defaultColor
                } else {
                    0
                }
            } catch (ignored: Throwable) {
                // 忽略，返回 0
                0
            }
        }
        return 0
    }

    /** 取功能区视图本身；动态 dex 未就绪时返回 null。 */
    private fun bottomView(): View? {
        return try {
            val mgr = resolveBottomManager() ?: return null
            Reflect.getStaticField(mgr, "sBottomView") as? View
        } catch (t: Throwable) {
            null
        }
    }

    /** 取底部功能栏当前实色：优先其 sBottomView 的 ColorDrawable 实色，退回静态字段，皆失败 -1。 */
    private fun getBottomBarColor(): Int {
        val mgr = resolveBottomManager()
        if (mgr == null) {
            return -1
        }
        try {
            val bottomView = Reflect.getStaticField(mgr, "sBottomView")
            if (bottomView is View) {
                val bg = bottomView.background
                if (bg is ColorDrawable) {
                    return bg.color
                }
            }
        } catch (t: Throwable) {
            log("color from sBottomView failed - $t")
        }
        try {
            val c = Reflect.getStaticField(mgr, "currentBottomViewColor")
            if (c is Int) {
                val v = c
                if (v != 0) {
                    return v
                }
            }
        } catch (ignored: Throwable) {
            // 字段不存在或读不到，返回 -1
        }
        return -1
    }

    private fun log(msg: String) {
        XposedInit.log("[PanelColor] " + msg)
    }
}
