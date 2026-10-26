package com.clipboardfix

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedModuleInterface
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 剪贴板条目置顶（右滑置顶 / 再滑取消，最多一条，清空时保留）。
 *
 * <p><b>功能定义</b>：
 * <ul>
 *   <li>面板条目<b>右滑</b>置顶该条；再次右滑已置顶条目则取消置顶。最多同时置顶一条，
 *       置顶新条目会自动顶掉旧置顶。</li>
 *   <li>置顶条目始终排在列表首位（查询返回时重排，不改动 provider 数据）。</li>
 *   <li>点「清理剪贴板」清空全部条目后，被置顶的条目会被自动写回并保留。</li>
 *   <li>置顶条目显示「（已置顶）」提醒：云端条目标在其来源标注（「来自我的电脑」）
 *       后；本地条目借来源标注位把提醒放到<b>正文上方</b>（避免长文本把追加在
 *       正文后的提醒 ellipsize 掉）；取消置顶后随列表刷新消失。</li>
 *   <li>本地条目正文自带上下内边距（真机实测 padT=padB=22px，是系统给「无来源标注」
 *       的本地条目留的呼吸空间），云端条目没有（padT=padB=0）。本地置顶借来源标注位
 *       加了「（已置顶）」行后，这个内边距会把整条撑高（实测 165px vs 云端 152px，
 *       用户反馈「置顶条目明显比别的条目高」）。故本地置顶时把正文内边距对齐云端
 *       （通常为 0）；该改动由 [sPadSaved] 记账，ViewHolder 复用或状态翻转时
 *       精确复位，避免残留影响非置顶条目。</li>
 * </ul>
 *
 * <p><b>方向说明</b>：系统自带的删除是<b>左滑</b>（主视图向左移、露出右侧删除按钮，
 * 见 {@code SwipeRevealLayout} dragEdge=RIGHT + {@code clampViewPositionHorizontal} 的
 * {@code [closeLeft - secWidth, closeLeft]} 区间）。左滑方向被系统删除占用，
 * 因此置顶手势做在<b>右滑</b>（系统未使用的空档方向），左滑删除流完全不动。
 *
 * <p><b>进程归属</b>：全部逻辑装在<b>默认输入法进程</b>：
 * <ol>
 *   <li><b>查询重排</b>：hook {@code android.app.ApplicationContentResolver.query}，
 *       URI 含 {@code getClipboardList} 时把 {@code time == pinnedTime} 的行挪到首位，
 *       构建 [MatrixCursor] 返回（列名从原游标拷贝，关闭原游标防泄漏）。
 *       不动 {@code com.miui.phrase} 进程的 provider 数据。</li>
 *   <li><b>清空保护</b>：hook {@code ApplicationContentResolver.call}，
 *       {@code method == "clearClipboardDataMethod"} 时先 {@code proceed()} 完成清空，
 *       再用 {@code call(uri, "saveClipboardCipherText", null, bundle)}（bundle key
 *       {@code jsonArray}）把置顶条目写回，最后刷新面板。</li>
 *   <li><b>手势</b>：hook 框架 [PopupWindow] 内容/显示点，按实例类名识别面板
 *       （面板类在动态 dex 里，进程启动时按名加载必失败，与 [PanelColorHook]
 *       同一套绕法），拿到面板 classloader 后懒挂
 *       {@code com.miui.inputmethod.SwipeRevealLayout} 的
 *       {@code onInterceptTouchEvent}/{@code onTouchEvent} 实现右滑状态机
 *       （每实例一份状态，存 [WeakHashMap]）。</li>
 * </ol>
 *
 * <p><b>条目身份</b>：{@code ClipboardContentModel.time}（JSON key {@code "time"}，
 * 毫秒时间戳）作为唯一标识。置顶条目被用户左滑删除后，下次查询匹配不到该 time，
 * 自动清置顶状态。
 *
 * <p><b>持久化</b>：置顶状态存输入法应用 {@code files/clipboardfix_pin.txt}
 * （第一行 time，其余为条目 JSON），输入法重启后仍保留。
 *
 * <p><b>开关</b>：由「功能」页「开启剪贴板条目置顶」控制，默认关闭；
 * 开关推进程启动时生效（与现有功能开关一致）。
 */
object ClipboardPinHook {

    // ---------------- 常量 ----------------

    private const val PHRASE_POPUP =
            "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val SWIPE_REVEAL =
            "com.miui.inputmethod.SwipeRevealLayout"
    private const val CLIPBOARD_ADAPTER =
            "com.miui.inputmethod.InputMethodClipboardAdapter"
    private const val CLIPBOARD_MANAGER =
            "com.miui.inputmethod.MiuiClipboardManager"
    private const val ACR =
            "android.app.ContextImpl\$ApplicationContentResolver"
    private const val POPUP_WINDOW = "android.widget.PopupWindow"
    private const val RECYCLER_VIEW =
            "androidx.recyclerview.widget.RecyclerView"

    /** 置顶状态文件（输入法应用 files 目录）。 */
    private const val PIN_FILE = "clipboardfix_pin.txt"

    /** provider 游标的内容列名（ClipboardContentModel 的 JSON 行）。 */
    private const val COL_CONTENT = "phrase_content"

    /** 清空剪贴板的 provider 方法名。 */
    private const val METHOD_CLEAR = "clearClipboardDataMethod"

    /** 保存剪贴板列表的 provider 方法名（写回置顶条目用）。 */
    private const val METHOD_SAVE = "saveClipboardCipherText"

    /** 置顶判定：查询 URI 特征串。 */
    private const val URI_LIST = "getClipboardList"

    /** 置顶按钮直径（dp），与系统删除按钮视觉规格对齐（原生删除圆实测 58dp）。 */
    private const val PIN_BTN_DP = 58f

    /** 置顶按钮与条目边缘的间距（dp）——与原生删除门一致（SRL paddingHorizontal 18dp）。 */
    private const val PIN_BTN_MARGIN_DP = 18f

    /** 置顶按钮底色（蓝，与系统删除按钮的红区分）。 */
    private val PIN_BTN_COLOR = 0xFF3B82F6.toInt()

    /**
     * 置顶按钮内图标相对圆边的内边距（dp）。
     *
     * <p>58dp 圆减去左右各 17dp，留 24dp 给箭头（约占圆径 41%）——按真机截图实测标定：
     * 系统删除按钮的垃圾桶图标约占圆径 38.5%，箭头若按 9dp 内边距会占到 66.7%，
     * 明显比删除图标大一圈（用户反馈「占满整个圆圈、与删除图标严重不协调」）。
     */
    private const val PIN_ICON_PAD_DP = 17f

    /**
     * 置顶条目标注：追加在来源标注（「来自我的电脑」）后的提醒文字。
     * 硬编码——hook 跑在输入法进程，用不到本 APK 的字符串资源。
     */
    private const val PIN_MARK = "（已置顶）"

    /**
     * 云端条目正文的上下内边距（px）——首次见到云端条目时记录。
     *
     * <p>真机实测：本地条目正文带 22px 上下内边距（系统给「无来源标注」条目留的
     * 呼吸空间），云端条目正文为 0。本地置顶借来源标注位加了「（已置顶）」行后，
     * 这个内边距会把整条撑高（本地 165px vs 云端 152px）。把本地正文的内边距对齐
     * 云端（实测为 0），本地置顶条目即与云端条目等高。默认 0（未捕获时按 0）。
     */
    private var sCloudContentPadT = 0
    private var sCloudContentPadB = 0

    /** 是否已捕获云端正文内边距（抓一次即可）。 */
    private var sCloudContentPadCaptured = false

    /**
     * 云端「来源标注」的实测字号（px）与颜色——首次见到云端条目时记录。
     *
     * <p>本地置顶复用同一个 tip 容器，若不套用云端那套「小字 + 灰色」，标记会以 tip
     * TextView 的默认大字渲染，整行比云端条目高一截（用户实拍：本地置顶条目明显高于
     * 云端条目）。故记下云端来源标注样式，本地标记套用同款，使行高一致。
     */
    private var sSrcLabelSizePx = 0f
    private var sSrcLabelColor = 0xFFFFFFFF.toInt()

    /**
     * 诊断开关：置 true 时打印置顶标记几何（字号/行高/条目高），真机校准用，稳定后关掉。
     *
     * <p>已用它定位并修掉「本地置顶条目被撑高」：真机实测本地正文 padT=padB=22px、
     * 云端 0px，正文字内边距差 44px 正是条目高差（165px vs 152px）的来源。
     */
    private const val PIN_DIAG = false

    /** 来源标注容器（phrase 包 id 名）：云条目显示「来自我的电脑」+云图标，本地条目 GONE。 */
    private const val ID_TIP_BOX = "clipboard_across_devices_tip"

    /** 来源标注图标（phrase 包 id 名）：云条目的电脑/云图标。 */
    private const val ID_TIP_ICON = "clipboard_across_devices_tip_image"

    /** 来源标注 TextView（phrase 包 id 名）：云端条目显示「来自我的电脑」。 */
    private const val ID_TIP_TEXT = "clipboard_across_devices_tip_text"

    /**
     * 正文 TextView（phrase 包 id 名）：文本条目用 top、图片条目用 bottom。
     * 仅作 id 解析失败时的兜底标注位——正常路径本地条目把提醒放到来源标注位
     * （[ID_TIP_BOX]），即正文上方。
     */
    private const val ID_TEXT_TOP = "clipboard_text_item_top"
    private const val ID_TEXT_BOTTOM = "clipboard_text_item_bottom"

    // ---------------- 置顶门状态 ----------------

    /** 全关：左滑走系统删除，右滑开置顶门。 */
    private const val MODE_NONE = 0

    /** 正在右划开置顶门（主视图跟手）。 */
    private const val MODE_OPENING = 1

    /** 置顶门已开（按钮可见，等点击）。 */
    private const val MODE_PIN_OPEN = 2

    // ---------------- 置顶状态（进程级） ----------------

    /** 置顶条目的 time；0 表示无置顶。 */
    @Volatile
    private var sPinnedTime = 0L

    /** 置顶条目的原始 JSON（写回 / 重排都靠它）。 */
    @Volatile
    private var sPinnedJson: String? = null

    /** 持久化文件是否已加载过（只试一次，失败保持内存态）。 */
    @Volatile
    private var sPinLoaded = false

    // ---------------- 数据快照（每次查询更新） ----------------

    /** 最近一次查询结果的 time 序列（已是重排后的显示顺序，按位置对应条目）。 */
    @Volatile
    private var sRowTimes: List<Long> = ArrayList()

    /** time -> 原始 JSON，供置顶时取写回内容。 */
    @Volatile
    private var sTimeJson: Map<Long, String> = HashMap()

    /** 面板当前显示的列表（hook adapter.setClipboardList 捕获，兜底取行身份用）。 */
    @Volatile
    private var sDisplayListRef: WeakReference<List<*>> = WeakReference(null)

    /** 面板列表适配器实例（hook adapter.setClipboardList 捕获，刷新面板用）。 */
    @Volatile
    private var sAdapterRef: WeakReference<Any> = WeakReference(null)

    // ---------------- 面板上下文 ----------------

    /** 面板实例（识别到即存，消失后自然回收）。 */
    @Volatile
    private var sPopupRef: WeakReference<PopupWindow> = WeakReference(null)

    /** 输入法 context（取 contentResolver / files 目录）。 */
    @Volatile
    private var sImeCtxRef: WeakReference<Context> = WeakReference(null)

    /** 面板实例所在 classloader（能解析 com.miui.inputmethod.*）。 */
    @Volatile
    private var sPanelCl: ClassLoader? = null

    /** 进程默认 classloader（兜底）。 */
    @Volatile
    private var sDefaultCl: ClassLoader? = null

    /** 当前宿主包名（仅日志用）。 */
    @Volatile
    private var sHostPkg: String? = null

    /** 防重复装配（onPackageLoaded 可能多次回调）。 */
    @Volatile
    private var sInitDone = false

    /** 手势 hook 是否已挂上（懒挂 + 有界重试）。 */
    @Volatile
    private var sGestureHooked = false

    /** {@code mRectMainClose} 字段（懒解析，失败置 null 退 0 基准）。 */
    @Volatile
    private var sRectFieldTried = false
    private var sRectMainCloseField: Field? = null

    /**
     * 主视图「关门位」left —— 系统 {@code mRectMainClose.left}（onLayout →
     * initRects 用主视图自然布局位填充，删除门关闭时主视图 layout 在此）。
     *
     * <p><b>为什么不能拿 0 当基准</b>：真机 trace 实证 clipboard 条目的主视图
     * 关闭位是 {@code left=47}（行内 18dp 左边距），删除门开度 =
     * {@code 关门位 - 当前 left}。早期判据写 {@code left < -1}（等价于门开
     * ≥153px 才认门开），微滑只把门拖开几 px~21px 时完全漏判——而我们消费
     * 手势后系统收不到 UP（没有 onViewReleased 兜底），门就冻结在小开度，
     * 留下一点红边（症状1）。反射失败退 0（旧行为，仍能抓全开门）。
     */
    private fun closedLeftOf(swipe: Any): Int {
        if (!sRectFieldTried) {
            sRectFieldTried = true
            try {
                sRectMainCloseField = Reflect.findField(swipe.javaClass, "mRectMainClose")
                // 私有字段必须显式放开可访问性，否则 get() 抛
                // IllegalAccessException（Reflect.findField 不代劳）——
                // 症状1 修复首版实测：异常被 catch(ignored) 静默吞掉，
                // closedLeftOf 恒返回 0，判据退化成旧版 left<=-2，
                // 微开门（mainL=38 级）继续漏判，红边依旧。
                sRectMainCloseField?.setAccessible(true)
                log("mRectMainClose resolved on " + swipe.javaClass.name)
            } catch (t: Throwable) {
                sRectMainCloseField = null
                log("mRectMainClose unresolvable, fallback 0 - " + t)
            }
        }
        if (sRectMainCloseField != null) {
            try {
                val r = sRectMainCloseField?.get(swipe)
                if (r is Rect) {
                    return r.left
                }
                log("mRectMainClose not a Rect: " + r)
            } catch (t: Throwable) {
                // 读数失败本次退 0（旧行为，仍能抓全开门）
                log("mRectMainClose read failed, fallback 0 - " + t)
            }
        }
        return 0
    }

    /**
     * 系统删除门是否开着（主视图 left 低于关门位 2px 以上）。
     *
     * <p>反编译实证系统 M.e 在每次 DOWN 就被 tryCaptureView 的副作用武装成
     * DRAGGING 且无逐事件 slop——我们 proceed 掉的每个左向 MOVE 都会 1:1
     * 拖动主视图 left；2px 容差内视为噪声。
     */
    private fun systemDoorOpen(swipe: Any, main: View?): Boolean {
        return main != null && main.left <= closedLeftOf(swipe) - 2
    }

    /** 每实例手势状态。 */
    private val sStates: MutableMap<Any, GestureState> = java.util.WeakHashMap()

    /**
     * 当前开着置顶门的 swipe（全局单开策略：同时只允许一条条目开着置顶门，
     * 与原生删除门一致——开第二条时第一条自动关）。
     *
     * <p>弱引用：条目视图被 RV 回收时不拖内存。只作「哪条开着」的记账，
     * 真正的显隐/位移仍由各实例的 mode + translationX + 按钮状态决定。
     */
    private var sOpenSwipeRef: WeakReference<Any> = WeakReference(null)

    /**
     * 单开互斥：关掉除 [keep] 外所有开着置顶门的条目。
     *
     * <p>调用点只有 [dragPinDoor] 入口（某条门开始跟手即意味着用户
     * 选中了它）。快路：无其他门开着时零开销；关过后记录置空，同一次
     * 跟手的后续 MOVE 不会重复干活（也不会刷日志）。
     */
    private fun closeOtherPinDoors(keep: Any) {
        val other = sOpenSwipeRef.get()
        if (other == null || other === keep) {
            return
        }
        val ost = sStates[other]
        if (ost != null) {
            ost.mode = MODE_NONE
            ost.declined = false
        }
        val omain = mainViewOf(other)
        // 复用统一关门路径：记账清理 + 主视图/按钮 150ms 归位动画 + GONE，
        // 与被用户主动拖关的视觉完全一致
        closePinDoor(other, omain, pinDoorWidth(other as View))
        // 被关的门若系统删除门也微开着（上回合 UP 兜底漏网的极端情况），
        // 一并 close(false) 修回关门位，保证互斥切换后画面只剩新门
        neutralizeSystemDoor(other, omain)
        log("other pin door auto-closed (single-open policy)")
    }

    /** 记下当前开着置顶门的 swipe（dragPinDoor 跟手即记账）。 */
    private fun markPinDoorOpen(swipe: Any) {
        if (sOpenSwipeRef.get() !== swipe) {
            sOpenSwipeRef = WeakReference(swipe)
        }
    }

    /** 清理单开记账（关门/硬复位时调用；非当前记录则无操作）。 */
    private fun clearPinDoorOpen(swipe: Any) {
        if (sOpenSwipeRef.get() === swipe) {
            sOpenSwipeRef = WeakReference(null)
        }
    }

    /**
     * ViewHolder.itemView → 适配器位置映射（hook {@code onBindViewHolder} 建立）。
     *
     * <p>面板的 RecyclerView 是 MIUI 定制版（dex 优化裁掉了
     * {@code getChildAdapterPosition}/{@code getChildLayoutPosition}/{@code getChildViewHolder}
     * 等位置 API，实测 NoSuchMethodException），无法向 RV 问位置；
     * 而 adapter 的 {@code onBindViewHolder} 必然被调用，参数里就带 position，
     * 用它建映射最稳（View 复用重绑时会自动覆盖旧值）。
     */
    private val sBindMap: MutableMap<View, Int> = java.util.WeakHashMap()

    /**
     * 记录「我们」把哪些 itemView 的正文内边距改过，及其被改前的原值。
     *
     * <p>键为 itemView，值为 {@code {top.padT, top.padB, bottom.padT, bottom.padB}}。
     * ViewHolder 复用或状态翻转时用它精确复位——否则残留的「已归零内边距」会让
     * 下一个复用此 itemView 的非置顶条目少了呼吸空间，与其它条目高度不一致。
     */
    private val sPadSaved: MutableMap<View, IntArray> = java.util.WeakHashMap()

    // ---------------- 装配 ----------------

    @JvmStatic
    fun init(param: XposedModuleInterface.PackageLoadedParam) {
        if (sInitDone) {
            return
        }
        val cl: ClassLoader
        try {
            cl = param.defaultClassLoader
        } catch (t: Throwable) {
            log("init: no classloader - " + t)
            return
        }
        sInitDone = true
        sDefaultCl = cl
        sHostPkg = param.packageName
        log("init enter: pkg=$sHostPkg")

        // 数据面：客户端 query 重排 + 清空写回。对作用域内所有包生效，
        // 内部按 URI / method 过滤，非剪贴板流量原样放行。
        try {
            val acr = resolveAcr()
            if (acr != null) {
                hookQuery(acr, arrayOf<Class<*>>(
                        Uri::class.java, Array<String>::class.java, String::class.java,
                        Array<String>::class.java, String::class.java))
                hookQuery(acr, arrayOf<Class<*>>(
                        Uri::class.java, Array<String>::class.java, String::class.java,
                        Array<String>::class.java, String::class.java,
                        CancellationSignal::class.java))
                hookCall(acr, arrayOf<Class<*>>(
                        Uri::class.java, String::class.java, String::class.java, Bundle::class.java))
                hookAcrMutations(acr)
            } else {
                log("WARN: ApplicationContentResolver unresolvable on any classloader")
            }
        } catch (t: Throwable) {
            log("WARN: ApplicationContentResolver hook - " + t)
        }

        // 手势面：框架 PopupWindow 按实例类名识别面板（面板类在动态 dex 里，
        // 此刻按名加载必 ClassNotFound，必须走实例识别绕法）。
        try {
            val pwCls = Reflect.findClass(POPUP_WINDOW, cl)
            hookPopupLifecycle(pwCls)
            log("OK: hooked framework PopupWindow content/show")
        } catch (t: Throwable) {
            log("WARN: PopupWindow hook - " + t)
        }
    }

    /**
     * 解析 {@code android.app.ApplicationContentResolver}（framework @hide 类）。
     *
     * <p>注意：app 默认 classloader 在部分 HyperOS 输入法进程里解析不到该隐藏类
     * （实测 ClassNotFoundException，原因待查），而 system classloader 可以
     * （[PanelColorHook] 解析 {@code ViewRootImplStub} 同款路径）。
     * 因此按「system → app 默认 → boot」顺序多重尝试。
     */
    private fun resolveAcr(): Class<*>? {
        val loaders = arrayOf(ClassLoader.getSystemClassLoader(), sDefaultCl, null)
        for (cl in loaders) {
            try {
                val c = Class.forName(ACR, false, cl)
                log("ACR resolved from " + cl)
                return c
            } catch (ignored: Throwable) {
                // 换下一个 classloader
            }
        }
        return null
    }

    // ---------------- Hook: query 重排 ----------------

    private fun hookQuery(acr: Class<*>, sig: Array<Class<*>>) {
        try {
            val m = Reflect.findMethod(acr, "query", *sig)
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                try {
                    val uri = chain.getArg(0) as? Uri
                    if (uri == null) {
                        return@hook r
                    }
                    log("query: " + uri)
                    if (!uri.toString().contains(URI_LIST)) {
                        return@hook r
                    }
                    log("query hit: " + uri + " cursor="
                            + (if (r == null) "null" else r.javaClass.name))
                    if (r !is Cursor) {
                        return@hook r
                    }
                    return@hook reorder(r)
                } catch (t: Throwable) {
                    log("query reorder failed - " + t)
                    return@hook r
                }
            }
            log("OK: hooked query(" + sig.size + " args)")
        } catch (t: Throwable) {
            log("WARN: query(" + sig.size + " args) hook - " + t)
        }
    }

    /**
     * 把置顶行挪到首位并返回新游标；无置顶（或置顶行已不在列表）时原样透传。
     *
     * <p>同时刷新 [sRowTimes] / [sTimeJson] 快照（手势取行身份用）。
     * 置顶条目不在结果里（被删除）时自动清置顶状态——此处严禁再触发刷新，避免递归。
     */
    private fun reorder(src: Cursor): Any {
        val cols = src.columnNames
        var contentIdx = -1
        for (i in cols.indices) {
            if (COL_CONTENT == cols[i]) {
                contentIdx = i
                break
            }
        }
        if (contentIdx < 0) {
            // 不是剪贴板列表数据（列结构不符），原样放行
            return src
        }
        val rows: MutableList<Array<Any?>> = ArrayList()
        val times: MutableList<Long> = ArrayList()
        val timeJson: MutableMap<Long, String> = HashMap()
        if (src.moveToFirst()) {
            do {
                val row = arrayOfNulls<Any>(cols.size)
                for (i in cols.indices) {
                    try {
                        row[i] = src.getString(i)
                    } catch (t: Throwable) {
                        // 非字符串列：读不出来就放弃整体重排，保持原游标语义
                        src.moveToPosition(-1)
                        return src
                    }
                }
                rows.add(row)
                val time = extractTime(row[contentIdx] as String)
                times.add(time)
                if (time != 0L) {
                    timeJson[time] = row[contentIdx] as String
                }
            } while (src.moveToNext())
        }
        // 原游标读取完毕，关闭防泄漏（调用方拿到的是新 MatrixCursor）
        try {
            src.close()
        } catch (ignored: Throwable) {
            // 关闭失败不影响功能
        }
        sTimeJson = timeJson

        val pin = sPinnedTime
        var pinIdx = -1
        if (pin != 0L) {
            for (i in times.indices) {
                if (times[i] == pin) {
                    pinIdx = i
                    break
                }
            }
        }
        log("reorder: rows=" + rows.size + " cols=" + java.util.Arrays.toString(cols)
                + " pin=" + pin + " pinIdx=" + pinIdx)
        if (pin != 0L && pinIdx < 0) {
            // 置顶条目已被删除：清状态（不刷新、不写回，静默降级）
            log("pinned row gone, clearing pin state")
            clearPinState(ctxOf())
        }
        val displayTimes: List<Long>
        if (pinIdx > 0) {
            val pinnedRow = rows.removeAt(pinIdx)
            rows.add(0, pinnedRow)
            val dt: MutableList<Long> = ArrayList(times.size)
            dt.add(times[pinIdx])
            for (i in times.indices) {
                if (i != pinIdx) {
                    dt.add(times[i])
                }
            }
            displayTimes = dt
            log("pinned to top: time=" + pin + " from pos " + pinIdx)
        } else {
            displayTimes = times
        }
        sRowTimes = displayTimes

        val out = MatrixCursor(cols)
        for (row in rows) {
            out.addRow(row)
        }
        return out
    }

    // ---------------- Hook: call 清空保护 ----------------

    private fun hookCall(acr: Class<*>, sig: Array<Class<*>>) {
        try {
            val m = Reflect.findMethod(acr, "call", *sig)
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                try {
                    val methodArg = chain.getArg(1)
                    log("provider call: method=" + methodArg + " uri=" + chain.getArg(0))
                    if (METHOD_CLEAR != methodArg) {
                        return@hook r
                    }
                    // v4.12：清空已执行——先作废补回缓存（云端/图片旧条目不再复活），
                    // 再写回置顶条目并刷新面板。顺序要紧：先清缓存，否则随后的刷新会
                    // 走 prependMissingCloud 把刚清掉的旧条目又补回头部。
                    CrossDeviceClipboardHook.clearRestoreCaches()
                    // 清空已执行：写回置顶条目并刷新面板
                    val uri = chain.getArg(0) as? Uri
                    restorePinnedAfterClear(uri)
                } catch (t: Throwable) {
                    log("clear protect failed - " + t)
                }
                return@hook r
            }
            log("OK: hooked call(" + sig.size + " args)")
        } catch (t: Throwable) {
            log("WARN: call(" + sig.size + " args) hook - " + t)
        }
    }

    /**
     * 诊断埋点：provider 的 update / delete / insert 全量打日志。
     *
     * <p>滑动删除走的可能不是 {@code call} 而是这三条路（云条目疑似软删除
     * {@code deleted=1}，dex 里见过 {@code sourceid IS NOT NULL AND deleted=0}
     * 这类 WHERE 片段）。要定位「删除后面板重开又复活」的真实链路，必须把
     * 所有写路径都打出来。{@code Reflect.findMethod} 沿继承链查找，ACR 自己
     * 没声明的方法会落到 [ContentResolver] 基类上，两种情形都能挂上。
     */
    private fun hookAcrMutations(acr: Class<*>) {
        hookMutation(acr, "update",
                arrayOf<Class<*>>(Uri::class.java, ContentValues::class.java, String::class.java,
                        Array<String>::class.java))
        hookMutation(acr, "delete",
                arrayOf<Class<*>>(Uri::class.java, String::class.java, Array<String>::class.java))
        hookMutation(acr, "insert",
                arrayOf<Class<*>>(Uri::class.java, ContentValues::class.java))
    }

    private fun hookMutation(acr: Class<*>, name: String, sig: Array<Class<*>>) {
        try {
            val m = Reflect.findMethod(acr, name, *sig)
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                try {
                    val a1 = chain.getArg(1)
                    log("provider " + name + ": uri=" + chain.getArg(0)
                            + " arg=" + (a1?.toString() ?: "null"))
                } catch (ignored: Throwable) {
                    // 诊断日志失败不影响主流程
                }
                r
            }
            log("OK: hooked ACR." + name)
        } catch (t: Throwable) {
            log("WARN: " + name + " hook - " + t)
        }
    }

    /** 清空后用 saveClipboardCipherText 写回置顶条目，再刷新面板。 */
    private fun restorePinnedAfterClear(uri: Uri?) {
        val pin = sPinnedTime
        val json = sPinnedJson
        if (pin == 0L || json == null || json.isEmpty()) {
            return
        }
        val cr = resolverOf()
        if (cr == null || uri == null) {
            log("restore skipped: no resolver/uri")
            return
        }
        try {
            val b = Bundle()
            b.putString("jsonArray", "[" + json + "]")
            cr.call(uri, METHOD_SAVE, null, b)
            log("pin restored after clear: time=" + pin)
        } catch (t: Throwable) {
            log("restore after clear failed - " + t)
            return
        }
        refreshPanel()
    }

    // ---------------- 面板刷新 ----------------

    /**
     * 重拉剪贴板数据并刷新面板：
     * {@code MiuiClipboardManager.getClipboardData(ctx)} →
     * {@code buildRecyclerViewDisplayList(list)} →
     * {@code adapter.setClipboardList(display)}，并同步 popup 的空视图/清空按钮状态。
     *
     * <p>拉数走的 query 会经过 [reorder]，因此置顶条目自然回到首位。
     */
    private fun refreshPanel() {
        val ctx = ctxOf()
        val pw = popupOf()
        if (ctx == null || pw == null) {
            log("refresh skipped: no panel context")
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                val cl = clOf() ?: return@post
                val mgr = Reflect.findClass(CLIPBOARD_MANAGER, cl)
                val data = Reflect.callStaticMethod(mgr, "getClipboardData", ctx)
                if (data !is List<*>) {
                    log("refresh: getClipboardData returned non-list")
                    return@post
                }
                val adapter = adapterOf()
                if (adapter == null) {
                    log("refresh: adapter not captured yet")
                    return@post
                }
                val display = Reflect.callStaticMethod(
                        mgr, "buildRecyclerViewDisplayList", data)
                Reflect.callMethod(adapter, "setClipboardList", display)
                // 同步 popup 内部状态，保证空视图 / 清空按钮显示正确。
                // 【关键不变量】mCurrentImeClipboardList 必须与 adapter 持有的列表是
                // **同一个对象**：maybeChangeEmptyViewAndClearButtonState 在判定入参列表
                // 非空后，还要再看 mCurrentImeClipboardList.size() 才决定 RecyclerView
                // 可见性（popup_view:697-719）。写 data（另一份列表）会让两者分叉——
                // 之后实时合入补进 adapter 列表的条目，会因该字段为空而让 RecyclerView
                // 一直 GONE、条目全部不可见（v4.16 回归：日志显示补入成功但面板空白）。
                try {
                    Reflect.setField(pw, "mCurrentImeClipboardList", display)
                } catch (ignored: Throwable) {
                    // 字段不存在或类型不符时忽略
                }
                try {
                    Reflect.callMethod(pw, "maybeChangeEmptyViewAndClearButtonState", display)
                } catch (t2: Throwable) {
                    try {
                        Reflect.callMethod(pw, "maybeChangeEmptyViewAndClearButtonState", data)
                    } catch (ignored: Throwable) {
                        // 两种参数形态都不匹配时忽略
                    }
                }
                val content = pw.contentView
                val rvObj = if (content == null) null else findRecyclerView(content)
                if (rvObj is View) {
                    // 不用 RecyclerView.scrollToPosition——MIUI 定制 RV 把它裁了
                    // （实测 NoSuchMethodException），改用 View 基类 scrollBy 滚回顶部。
                    val sy = rvObj.scrollY
                    if (sy > 0) {
                        rvObj.scrollBy(0, -sy)
                        log("rv scrolled to top, was y=" + sy)
                    }
                }
                log("panel refreshed after pin change")
            } catch (t: Throwable) {
                log("refresh failed - " + t)
            }
        }
    }

    /**
     * v4.15：把云端条目合入显示列表后，同步 popup 的空视图/列表可见性。
     *
     * <p>空面板时 phrase 的 {@code maybeChangeEmptyViewAndClearButtonState} 已把 RecyclerView 置 GONE、
     * NoItemsView 置 VISIBLE；{@code mergeCloudIntoDisplay} 直接改 adapter 列表 + notifyDataSetChanged
     * 并不切换可见性，导致条目进了列表却看不见（关面板重开走 setClipboardList 重设才显示）。
     * 补完后调用本方法令 RecyclerView 重新 VISIBLE，云端条目即实时可见（修「空面板云端不实时显示」）。
     */
    /**
     * v4.16：供云端合并模块调用的面板重拉入口。
     *
     * <p>云端条目推送到达时会被 phrase 同步落本地库，因此「重拉本地列表」得到的就是
     * 「关面板再打开」所见的同一份数据、同一条渲染链路。当增量合入补不进条目时
     * （例如该条目在清空屏蔽名单里）用它兜底，保证面板开着也能实时显示云端内容。
     */
    @JvmStatic
    fun refreshFromLocal(reason: String) {
        log("refresh from local: $reason")
        refreshPanel()
    }

    @JvmStatic
    fun syncEmptyState(list: List<Any?>) {
        val pw = popupOf() ?: return
        // 先保证空视图状态机所看的 mCurrentImeClipboardList 就是当前显示列表本身
        // （见 refreshPanel 里的不变量说明），否则列表非空也会被判成空、RecyclerView 保持 GONE。
        try {
            Reflect.setField(pw, "mCurrentImeClipboardList", list)
        } catch (ignored: Throwable) {
            // 字段不存在或类型不符时忽略
        }
        try {
            Reflect.callMethod(pw, "maybeChangeEmptyViewAndClearButtonState", list)
        } catch (t: Throwable) {
            log("sync empty state failed - " + t)
        }
    }

    // ---------------- Hook: PopupWindow 识别面板 + 懒挂手势 ----------------

    private fun hookPopupLifecycle(pwCls: Class<*>) {
        hookPopupIfPanel(pwCls, "setContentView", arrayOf<Class<*>>(View::class.java))
        hookPopupIfPanel(pwCls, "showAsDropDown",
                arrayOf<Class<*>>(View::class.java, Int::class.java, Int::class.java))
        hookPopupIfPanel(pwCls, "showAsDropDown",
                arrayOf<Class<*>>(View::class.java, Int::class.java, Int::class.java, Int::class.java))
        hookPopupIfPanel(pwCls, "showAtLocation",
                arrayOf<Class<*>>(View::class.java, Int::class.java, Int::class.java, Int::class.java))
    }

    private fun hookPopupIfPanel(pwCls: Class<*>, name: String, sig: Array<Class<*>>) {
        try {
            val m = Reflect.findMethod(pwCls, name, *sig)
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                val self = chain.thisObject
                if (isOurPanel(self)) {
                    if (sPanelCl == null) {
                        sPanelCl = self.javaClass.classLoader
                        log("panel classloader captured")
                    }
                    if (self is PopupWindow) {
                        sPopupRef = WeakReference(self)
                        val content = self.contentView
                        if (content != null) {
                            sImeCtxRef = WeakReference(content.context)
                        }
                        // 用输入法进程自己的 context 读写 pin 状态文件
                        //（面板 context 实际是 phrase 包的 context，目录不对）
                        loadPinIfNeeded(ctxOf())
                    }
                    ensureGestureHooks()
                    log("panel detected via " + name)
                }
                r
            }
        } catch (t: Throwable) {
            log("WARN: " + name + " hook - " + t)
        }
    }

    private fun isOurPanel(o: Any?): Boolean {
        return o != null && PHRASE_POPUP == o.javaClass.name
    }

    /**
     * 懒挂 {@code SwipeRevealLayout} 触摸事件与 {@code InputMethodClipboardAdapter.setClipboardList}。
     * 动态 dex 里按名解析，失败不置位，由 [scheduleGestureRetry] 有界重试兜底。
     */
    private fun ensureGestureHooks(): Boolean {
        if (sGestureHooked) {
            return true
        }
        var any = false
        val srl = resolve(SWIPE_REVEAL)
        if (srl != null) {
            log("SwipeRevealLayout resolved from " + srl.classLoader)
            any = hookTouch(srl, "onInterceptTouchEvent", MotionEvent::class.java) || any
            any = hookTouch(srl, "onTouchEvent", MotionEvent::class.java) || any
            any = hookOnLayout(srl) || any
        } else {
            log("SwipeRevealLayout not resolvable yet (panelCl=" + sPanelCl
                    + ", defaultCl=" + sDefaultCl + ")")
        }
        val ad = resolve(CLIPBOARD_ADAPTER)
        if (ad != null) {
            log("adapter resolved from " + ad.classLoader)
            any = hookSetClipboardList(ad) || any
            any = hookOnBindViewHolder(ad) || any
        } else {
            log("adapter not resolvable yet (panelCl=" + sPanelCl
                    + ", defaultCl=" + sDefaultCl + ")")
        }
        sGestureHooked = any
        if (!any) {
            scheduleGestureRetry()
        } else {
            log("gesture hooks ready")
        }
        return any
    }

    /** 有界重试懒挂手势（面板 classloader 就绪前动态 dex 可能还没解析出来）。 */
    private fun scheduleGestureRetry() {
        val h = Handler(Looper.getMainLooper())
        val attempt = object : Runnable {
            var tries = 0

            override fun run() {
                if (sGestureHooked || ensureGestureHooks()) {
                    return
                }
                if (++tries < 20) {
                    h.postDelayed(this, 500L)
                } else {
                    log("gesture hooks gave up after retries")
                }
            }
        }
        h.postDelayed(attempt, 300L)
    }

    // ---------------- Hook: SwipeRevealLayout 右滑置顶门 ----------------

    /**
     * 右滑「置顶门」状态机（onInterceptTouchEvent / onTouchEvent 共用），
     * 交互与系统删除门对称：轻划露出按钮 → 点按钮才置顶。
     *
     * <p>三态：
     * <ul>
     *   <li><b>MODE_NONE（全关）</b>：左滑 = 系统删除（proceed 不劫持）；
     *       右滑超阈值 = 开置顶门（劫持，主视图跟手右移）；系统删除门开着
     *       （主视图 translationX &lt; -1f）时右滑一律 proceed 交还系统
     *       ——「右滑取消删除」是系统原生行为，绝不拦。</li>
     *   <li><b>MODE_OPENING（正在划开置顶门）</b>：主视图 translationX 跟随手指
     *       （0..门宽），松手超过门宽一半则保持打开。</li>
     *   <li><b>MODE_PIN_OPEN（置顶门已开）</b>：按钮可见；水平拖动（任一方向）
     *       一律劫持——左滑跟手往关的方向拖，右滑钳位在门宽（无操作）；
     *       松手或点击条目其他区域关门；<b>点击置顶按钮</b>才触发置顶/取消置顶。
     *       右滑也必须劫持的原因见 {@code ACTION_MOVE} 分支注释：系统 DragHelper
     *       在 DOWN 即被武装且无逐事件 slop，放行右滑会激活系统删除门。</li>
     * </ul>
     *
     * <p><b>为什么不能用 {@code isOpened()} 判断系统删除门</b>：MIUI 版
     * {@code SwipeRevealLayout.isOpened()} 实测恒返回 false，导致右滑被误劫持、
     * 系统「右滑取消删除」失效（code 84 实机 bug）。改用主视图 translationX
     * 符号做物理判断：&lt; -1f = 系统删除门开着，&gt; +1f = 置顶门开着。
     *
     * <p><b>方向判据与防抢手势（镜像原生）</b>：反编译 {@code com.miui.inputmethod
     * .SwipeRevealLayout}（phrase 5.7.4）确认，条目的 ViewDragHelper 垂直拖动
     * 范围为 0、只有水平范围，按 {@code checkTouchSlop} 的 horizontal-only 分支，
     * 捕获条件仅有 {@code |dx| > 触摸阈值}，<b>dy 不参与判定</b>；且其手势监听器
     * 在首次滚动超阈值时即 {@code getParent().requestDisallowInterceptTouchEvent(true)}
     * 禁止父级 RecyclerView 抢走手势。故置顶门同样：开门/回拖只比水平位移量，
     * 一旦接管拖动立即 disallow 父级（UP/CANCEL 及下一个 DOWN 时解除）——
     * 否则稍有垂直偏移就被 proceed 掉或中途被抢，门永远拉不出来。
     */
    private fun hookTouch(cls: Class<*>, name: String, vararg sig: Class<*>): Boolean {
        try {
            val m = Reflect.findMethod(cls, name, *sig)
            XposedInit.hook(m) { chain ->
                val self = chain.thisObject ?: return@hook chain.proceed()
                val ev = chain.getArg(0) as? MotionEvent ?: return@hook chain.proceed()
                val sv = self as View
                val st = stateOf(self)
                val main = mainViewOf(self)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        st.downX = ev.x
                        st.downY = ev.y
                        st.declined = false
                        // 新手势开始：解除上一手势可能残留的「禁止父级劫持」标记。
                        // 父级 RecyclerView 的 disallow flag 若不清零，本手势期间
                        // 列表抢不走手势、无法正常滚动
                        sv.parent?.requestDisallowInterceptTouchEvent(false)
                        // 新手势开始：按「视图实际态」纠偏，不信任 mode 标志——
                        // 取消置顶后 mode 已回 NONE 但 150ms 关门动画仍在途、或
                        // 上一手势的 UP 被我们消费导致系统删除门冻结开着，都可能
                        // 让 mode 与视图脱钩。判据（任一命中即硬复位）：
                        //   ① mode 停在中间态 OPENING（正常结束的手势不会停留在此）；
                        //   ② 置顶门视觉开着（translationX>1 / 按钮可见）但 mode≠PIN_OPEN；
                        //   ③ mode=PIN_OPEN 但系统删除门实际开着（两门同显）。
                        // 全干净时不动：系统删除门正常开着（原生删除流）时不打扰。
                        val btn0 = sPinBtns[self]
                        val stale = st.mode == MODE_OPENING
                                || (st.mode == MODE_PIN_OPEN && systemDoorOpen(self, main))
                                || (st.mode != MODE_PIN_OPEN && main != null
                                        && main.translationX > 1f)
                                || (st.mode != MODE_PIN_OPEN && btn0 != null
                                        && btn0.visibility == View.VISIBLE)
                        if (stale) {
                            // 先中和系统侧（删除门冻结开着则 close(false) 关门），
                            // 再硬复位置顶侧（取消在途动画，避免 end-action 误触发）
                            neutralizeSystemDoor(self, main)
                            clearPinDoorOpen(self)
                            st.mode = MODE_NONE
                            st.declined = false
                            if (main != null) {
                                main.animate().cancel()
                                main.translationX = 0f
                            }
                            showPinBtn(self, false)
                            log("stale pin door cleared at down")
                        }
                        return@hook chain.proceed()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (st.declined) {
                            return@hook chain.proceed()
                        }
                        val dx = ev.x - st.downX
                        val slop = slopOf(sv)
                        val bw = pinDoorWidth(sv)
                        if (st.mode == MODE_PIN_OPEN) {
                            // 置顶门已开：水平拖动（任一方向）一律劫持——
                            // 左滑 = 回拖往关的方向；右滑 = 钳位在门宽（无操作）。
                            // 为什么右滑也不能 proceed：反编译实证系统的 M.e 在
                            // 每次 DOWN 就被 tryCaptureView 的副作用武装成 DRAGGING
                            //（拖动分支无逐事件 slop），proceed 掉的事件会实时拖动
                            // 主视图 left；此前只有左滑劫持，「先右划再左划」时右段
                            // 激活系统拖动、回程中低于 -slop 的左滑事件立刻把删除门
                            // 拖开，松手冻结成「两门同显」。垂直拖动（|dx|<slop）
                            // 仍 proceed，保证列表滚动不被拦。
                            //
                            // 左滑零容差（症状1 收尾）：|dx|<slop 的微左滑此前
                            // 放行 proceed，系统门被拖开 2~20px——手指按着就露
                            // 红边/按钮边缘（瞬态残留，UP 兜底只在松手后修复）。
                            // 故左滑只要水平占优（|dx|>=|dy|）且超过半像素立即
                            // 劫持，不等触摸阈值；右滑被系统 clamp 在关门位
                            // （left 不越过 close.left）不出界，维持 slop 判据，
                            // 保证 PIN_OPEN 时垂直滚动列表仍可放行。
                            val dy = ev.y - st.downY
                            val horiz = Math.abs(dx) >= Math.abs(dy)
                            if (dx < -0.5f && horiz) {
                                return@hook dragPinDoor(sv, main, st, bw, dx)
                            }
                            if (Math.abs(dx) >= slop) {
                                return@hook dragPinDoor(sv, main, st, bw, dx)
                            }
                            return@hook chain.proceed()
                        }
                        if (st.mode == MODE_OPENING) {
                            return@hook dragPinDoor(sv, main, st, bw, dx)
                        }
                        // 全关态：先判方向。
                        // 判据镜像原生 ViewDragHelper：条目只做水平拖动（垂直拖动
                        // 范围为 0），按 checkTouchSlop 的 horizontal-only 分支，
                        // 捕获条件只有 |dx| > 触摸阈值，dy 不参与判定——原生删除门
                        // 允许手指上下偏移照样拉开。若这里苛求「水平必须占优」，
                        // 稍有垂直偏移就 proceed 掉，父级 RecyclerView 随即抢走
                        // 整段手势，置顶门永远拉不出来
                        if (Math.abs(dx) < slop) {
                            // 未超触摸阈值：交给系统 / RecyclerView
                            return@hook chain.proceed()
                        }
                        if (dx < 0f) {
                            // 左滑 = 系统删除，绝不劫持
                            st.declined = true
                            return@hook chain.proceed()
                        }
                        if (systemDoorOpen(self, main) || main != null && main.translationX < -1f) {
                            // 系统删除门开着（主视图被 ViewDragHelper 推到左侧：left 低于关门位）。
                            // 此时右滑是系统原生「取消删除」，必须原样交还系统——
                            // 只查 translationX 会漏判（系统 drag 改的是 left，不是 translationX），
                            // 漏判的后果：右滑被误劫持成置顶门，系统关门失效（code 86 实机 bug）。
                            // 判据以 mRectMainClose.left 为基准而非 0：真机关门位 left=47，
                            // 微滑漂移只把门拖开 2~21px 时 left 仍是正的，旧判据 left<-1 漏判。
                            st.declined = true
                            return@hook chain.proceed()
                        }
                        // 右滑 = 开置顶门
                        st.mode = MODE_OPENING
                        log("pin door opening: dx=" + dx)
                        // 跟手阶段即显示按钮：主视图移多少、按钮露多少，
                        // 与系统删除门露出删除按钮的机制一致
                        showPinBtn(self, true)
                        return@hook dragPinDoor(sv, main, st, bw, dx)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 手势结束：恢复父级的劫持权，避免列表后续无法滚动
                        sv.parent?.requestDisallowInterceptTouchEvent(false)
                        val mode = st.mode
                        val up = ev.actionMasked == MotionEvent.ACTION_UP
                        val dx = ev.x - st.downX
                        st.declined = false
                        if (mode == MODE_NONE) {
                            return@hook chain.proceed()
                        }
                        // 我们消费了本手势（OPENING/PIN_OPEN）：UP 不会送达系统
                        // DragHelper（没有 onViewReleased 兜底），若期间 proceed 过的
                        // 事件已把系统删除门拖开，此刻已冻结——先 close(false) 关门，
                        // 否则残留红边、甚至与置顶门同显（两门同显 BUG）
                        neutralizeSystemDoor(self, main)
                        val bw = pinDoorWidth(sv)
                        if (mode == MODE_OPENING) {
                            // 只要划过触摸阈值就算「有意划出」：轻划即留门，
                            // 松手后主视图补一个动画滑到全开，按钮完整露出
                            //（阈值用门宽一半会导致轻划被弹回，实测用户只滑 30~40px）
                            if (up && dx >= slopOf(sv)) {
                                st.mode = MODE_PIN_OPEN
                                if (main != null) {
                                    main.animate().translationX(bw).setDuration(150L).start()
                                }
                                showPinBtn(self, true)
                                // 按钮随主视图一起滑到全开位置（translationX = 0）
                                val ob = sPinBtns[self]
                                if (ob != null) {
                                    ob.animate().cancel()
                                    ob.animate().translationX(0f).setDuration(150L).start()
                                }
                                log("pin door opened (dx=" + dx + ", bw=" + bw + ")")
                            } else {
                                // 划出不够：关门，按钮跟着条目滑回门外被裁掉
                                st.mode = MODE_NONE
                                closePinDoor(self, main, bw)
                            }
                            return@hook java.lang.Boolean.TRUE
                        }
                        // MODE_PIN_OPEN
                        if (up && isInPinBtn(sv, ev)) {
                            // 点中置顶按钮 → 置顶 / 取消置顶，随后关门
                            log("pin button clicked")
                            togglePinAt(self)
                            st.mode = MODE_NONE
                            closePinDoor(self, main, bw)
                            return@hook java.lang.Boolean.TRUE
                        }
                        val cur = main?.translationX ?: 0f
                        if (up && cur >= bw * 0.5f) {
                            // 仍停在开门位置（未点按钮、未往回拖）：保持打开。
                            // 补一个动画滑到全开，按钮同步回到全开位置
                            if (main != null) {
                                main.animate().translationX(bw).setDuration(150L).start()
                            }
                            val kb = sPinBtns[self]
                            if (kb != null) {
                                kb.animate().cancel()
                                kb.animate().translationX(0f).setDuration(150L).start()
                            }
                            return@hook java.lang.Boolean.TRUE
                        }
                        // 回拖过半：关门，按钮跟着条目滑回门外被裁掉
                        st.mode = MODE_NONE
                        closePinDoor(self, main, bw)
                        return@hook java.lang.Boolean.TRUE
                    }
                    else -> return@hook chain.proceed()
                }
            }
            return true
        } catch (t: Throwable) {
            log("WARN: " + name + " hook - " + t)
            return false
        }
    }

    /**
     * 置顶门拖动中：主视图 translationX 跟手（开门 0..bw；已开门则 bw+dx）。
     */
    private fun dragPinDoor(
            sv: View, main: View?, st: GestureState, bw: Float, dx: Float): Any {
        // 单开互斥：用户开始拖动本条，先关掉其他开着置顶门的条目
        // （原生删除门同样只开一条——开第二条时第一条自动收起）
        closeOtherPinDoors(sv)
        markPinDoorOpen(sv)
        // 接管前先中和系统删除门：反编译实证 M.e 在每次 DOWN 即被
        // tryCaptureView 的副作用武装成 DRAGGING（无逐事件 slop），开门途中
        // proceed 过的亚阈值左滑事件会把删除门拖开；先 close(false) 关门
        // 再走 translationX，保证任一时刻只有一扇门在位
        neutralizeSystemDoor(sv, main)
        // 接管拖动的同时禁止父级（RecyclerView）劫持本手势：跟手过程中 dy
        // 一旦超过列表滑动阈值，父级会把整段手势抢走、门卡在半路——原生
        // SwipeRevealLayout 靠手势监听器 onScroll → requestDisallowInterceptTouchEvent
        // 达到同样效果，此处镜像。flag 在 UP/CANCEL 与下一个 DOWN 时解除
        sv.parent?.requestDisallowInterceptTouchEvent(true)
        var tx = if (st.mode == MODE_PIN_OPEN) bw + dx else dx
        tx = Math.max(0f, Math.min(bw, tx))
        if (main != null) {
            main.translationX = tx
        }
        // 按钮与主视图同向等量位移（偏移 -bw）：门全关时被父容器左边界裁掉，
        // 门全开时正好落在露出区——与原生删除按钮「被条目推着走、越界被裁」同构
        syncPinBtn(sv, bw, tx)
        return java.lang.Boolean.TRUE
    }

    /**
     * hook {@code SwipeRevealLayout.onLayout}，在系统摆完 child0/child1 后
     * 手动把置顶按钮摆到条目左侧垂直居中。
     *
     * <p>MIUI 的 onLayout 不认识我们 addView 进去的按钮（不摆它），且 RV 复用
     * 条目时会反复触发 onLayout，正好每次都为按钮重新定位。
     */
    private fun hookOnLayout(cls: Class<*>): Boolean {
        try {
            val m = Reflect.findMethod(cls, "onLayout",
                    Boolean::class.java, Int::class.java, Int::class.java, Int::class.java,
                    Int::class.java)
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                try {
                    val self = chain.thisObject
                    val btn = sPinBtns[self]
                    if (btn != null && btn.parent === self) {
                        layoutPinBtn(self, btn)
                    }
                } catch (t: Throwable) {
                    log("pin btn layout failed - " + t)
                }
                r
            }
            log("OK: hooked SwipeRevealLayout.onLayout")
            return true
        } catch (t: Throwable) {
            log("WARN: onLayout hook - " + t)
            return false
        }
    }

    /** 置顶 / 取消置顶当前手势所在条目，然后刷新面板。 */
    private fun togglePinAt(swipe: Any) {
        val time = timeOfRow(swipe)
        if (time == 0L) {
            log("toggle pin: row time unresolved (swipe="
                    + swipe.javaClass.name + ")")
            return
        }
        val ctx = ctxOf()
        if (sPinnedTime == time) {
            // 已置顶 → 取消
            clearPinState(ctx)
            log("pin cancelled: time=" + time)
        } else {
            val json = sTimeJson[time]
            if (json == null || json.isEmpty()) {
                log("toggle pin: no json snapshot for time=" + time
                        + " (snapshot keys=" + sTimeJson.size + ")")
                return
            }
            sPinnedTime = time
            sPinnedJson = json
            persistPin(ctx, time, json)
            log("pinned: time=" + time)
        }
        refreshPanel()
    }

    // ---------------- Hook: adapter.setClipboardList 捕获列表 ----------------

    private fun hookSetClipboardList(ad: Class<*>): Boolean {
        try {
            for (m in ad.methods) {
                if (m.name != "setClipboardList") {
                    continue
                }
                val ps = m.parameterTypes
                if (ps.size != 1 || !List::class.java.isAssignableFrom(ps[0])) {
                    continue
                }
                XposedInit.hook(m) { chain ->
                    val r = chain.proceed()
                    val self = chain.thisObject
                    if (self != null) {
                        // adapter 实例弱引用在下方统一维护（反射字段成本高，直接存）
                        sAdapterRef = WeakReference(self)
                    }
                    val list = chain.getArg(0)
                    if (list is List<*>) {
                        sDisplayListRef = WeakReference(list)
                        log("setClipboardList captured: " + list.size + " rows, elem="
                                + (if (list.isEmpty()) "?"
                                else list[0]?.javaClass?.name))
                    } else {
                        log("setClipboardList arg not a List: "
                                + (if (list == null) "null" else list.javaClass.name))
                    }
                    r
                }
                log("OK: hooked " + ad.name + ".setClipboardList")
                return true
            }
            log("WARN: setClipboardList not found on " + ad.name)
            return false
        } catch (t: Throwable) {
            log("WARN: setClipboardList hook - " + t)
            return false
        }
    }

    // ---------------- Hook: adapter.onBindViewHolder 建立 View→position 映射 ----------------

    /**
     * hook {@code onBindViewHolder(ViewHolder, int)}，把 {@code ViewHolder.itemView}
     * 与适配器位置的对应关系记进 [sBindMap]，供手势时反查条目位置。
     *
     * <p>面板的 RecyclerView 是 MIUI 定制版，位置查询 API 全被 dex 优化裁掉
     * （实测 {@code getChildAdapterPosition} 等均 NoSuchMethodException），
     * 只能走这条不依赖 RV API 的路。
     */
    private fun hookOnBindViewHolder(ad: Class<*>): Boolean {
        try {
            for (m in ad.declaredMethods) {
                if (m.name != "onBindViewHolder") {
                    continue
                }
                val ps = m.parameterTypes
                // 只要 (ViewHolder, int) 两参版本；(vh, pos, payloads) 三参版不接
                if (ps.size != 2) {
                    continue
                }
                if (ps[1] != Int::class.java && ps[1] != Integer::class.java) {
                    continue
                }
                XposedInit.hook(m) { chain ->
                    val r = chain.proceed()
                    try {
                        val vh = chain.getArg(0)
                        val posArg = chain.getArg(1)
                        if (vh == null || posArg !is Int) {
                            return@hook r
                        }
                        val itemView = Reflect.getField(vh, "itemView")
                        if (itemView is View) {
                            synchronized(sBindMap) {
                                sBindMap[itemView] = posArg
                            }
                            // 条目复用时清掉可能残留的置顶门状态（开门态被滑出屏幕
                            // 或列表刷新后，主视图位移/按钮必须归位，否则显示错乱）
                            resetPinDoorIfNeeded(itemView)
                            // 诊断：前 3 次 bind 打一次条目视图树（确认布局 id 映射）
                            dumpTreeOnce(itemView)
                            // 置顶条目追加「（已置顶）」提醒
                            markPinnedSource(itemView, rowTimeAt(posArg))
                        }
                    } catch (t: Throwable) {
                        log("bind map record failed - " + t)
                    }
                    r
                }
                log("OK: hooked " + ad.name + ".onBindViewHolder("
                        + ps[0].simpleName + ", int)")
                return true
            }
            log("WARN: onBindViewHolder not found on " + ad.name)
            return false
        } catch (t: Throwable) {
            log("WARN: onBindViewHolder hook - " + t)
            return false
        }
    }

    // ---------------- 行身份解析 ----------------

    /**
     * 取手势所在行的条目 time。优先走 adapter 当前列表（按适配器位置），
     * 失败退回最近一次查询快照（同样按位置）。两者都失败返回 0（放弃本次置顶）。
     *
     * <p>位置来源是 [sBindMap]（hook {@code onBindViewHolder} 建立的
     * itemView→position 映射）：从手势 view 向上遍历祖先，命中映射即得适配器位置。
     * 不依赖 RecyclerView 的位置 API——面板的 MIUI 定制 RV 把这些方法全裁了。
     */
    private fun timeOfRow(swipe: Any): Long {
        try {
            val sv = swipe as View
            var pos: Int? = null
            var cur: View? = sv
            var i = 0
            while (i < 6 && cur != null) {
                val c = cur
                synchronized(sBindMap) {
                    pos = sBindMap[c]
                }
                if (pos != null) {
                    break
                }
                val pp = c.parent
                cur = pp as? View
                i++
            }
            val p = pos ?: run {
                log("timeOfRow: no bind map entry for swipe view, ancestors scanned, "
                        + "mapSize=" + sBindMap.size)
                return 0L
            }
            val list = sDisplayListRef.get()
            log("timeOfRow: pos=" + p + " list="
                    + (if (list == null) "null"
                    else list.size.toString() + "x" + (if (list.isEmpty()) "?"
                    else list[0]?.javaClass?.simpleName))
                    + " snapshot=" + sRowTimes.size)
            if (list != null && p >= 0 && p < list.size) {
                val t = timeOfItem(list[p])
                if (t != 0L) {
                    return t
                }
            }
            val times = sRowTimes
            if (p >= 0 && p < times.size) {
                return times[p]
            }
            log("timeOfRow: pos=" + p + " beyond snapshot size=" + times.size)
            return 0L
        } catch (t: Throwable) {
            log("timeOfRow failed - " + t)
            return 0L
        }
    }

    /** 反射取列表元素的 time 字段 / getTime()；display item 包一层 model 也试；最后兜底 toString 提取。 */
    private fun timeOfItem(item: Any?): Long {
        if (item == null) {
            return 0L
        }
        val cls = item.javaClass.name
        try {
            return Reflect.getField(item, "time") as Long
        } catch (ignored: Throwable) {
            // 字段不存在，继续试方法
        }
        try {
            return Reflect.callMethod(item, "getTime") as Long
        } catch (ignored: Throwable) {
            // 方法不存在，继续试包装字段
        }
        for (f in arrayOf("model", "mModel", "clipboardContentModel", "mClipboardContentModel",
                "data", "mData")) {
            try {
                val inner = Reflect.getField(item, f)
                if (inner == null) {
                    continue
                }
                try {
                    return Reflect.getField(inner, "time") as Long
                } catch (ignored2: Throwable) {
                    // 内层也没有 time 字段
                }
                try {
                    return Reflect.callMethod(inner, "getTime") as Long
                } catch (ignored2: Throwable) {
                    // 内层也没有 getTime
                }
                try {
                    return extractTime(inner.toString())
                } catch (ignored2: Throwable) {
                    // 内层 toString 也提取不到
                }
            } catch (ignored: Throwable) {
                // 字段不存在，试下一个
            }
        }
        // 最终兜底：item 自身 toString 里可能带 time=...（调试信息常见）
        try {
            val t = extractTime(item.toString())
            if (t != 0L) {
                log("timeOfItem: time from toString fallback")
                return t
            }
        } catch (ignored: Throwable) {
            // toString 提取失败
        }
        var s: String
        try {
            s = item.toString()
        } catch (t: Throwable) {
            s = "<toString failed>"
        }
        log("timeOfItem: unresolved for " + cls + " -> "
                + (if (s.length > 240) s.substring(0, 240) + "…" else s))
        return 0L
    }

    /**
     * v4.11：把显示列表里的置顶条目归位到 index 0（原地修改）。
     *
     * <p>面板开着期间云端来新数据（[mergeCloudIntoDisplay] / setClipboardList hook 经
     * prependMissingCloud 把云端条目插到头部）会覆盖查询层 reorder() 设置的置顶首位，
     * 表现为「被置顶的条目排到第二位，关面板重开才恢复」。此函数在云端合并完成后调用，
     * 把 time == [sPinnedTime] 的条目移到列表头部，与重新查询时 reorder() 的效果一致。
     *
     * @return 是否发生了移动（用于决定是否触发 adapter 刷新）
     */
    @JvmStatic
    fun ensurePinnedFirst(list: MutableList<Any?>): Boolean {
        val pin = sPinnedTime
        if (pin == 0L || list.isEmpty()) {
            return false
        }
        var idx = -1
        for (i in list.indices) {
            if (timeOfItem(list[i]) == pin) {
                idx = i
                break
            }
        }
        if (idx > 0) {
            // 从当前位置取走、插到头部，保持其余条目相对顺序
            val it = list.removeAt(idx)
            list.add(0, it)
            log("ensurePinnedFirst: moved pinned time=$pin from pos $idx to 0")
            return true
        }
        return false
    }

    // ---------------- 置顶条目标注（「（已置顶）」提醒） ----------------

    /**
     * 给置顶条目标注「（已置顶）」提醒——统一放在<b>正文上方</b>：
     * <ul>
     *   <li>云端条目：追加在来源标注（{@code clipboard_across_devices_tip_text}，
     *       「来自我的电脑」）后，本就在正文上方；</li>
     *   <li>本地条目：没有来源标注（容器 {@code clipboard_across_devices_tip} 被
     *       置 GONE）。把它显出来、写上「（已置顶）」并隐藏云图标，借同一个位置把
     *       提醒放到正文上方——长文本把正文 ellipsize 掉也不会吞掉提醒。</li>
     * </ul>
     *
     * <p>每次绑定先剥掉复用残留（View 回收带着旧文字），非置顶行只剥不贴——
     * 取消置顶后列表刷新复用它，标记不会错留。点击条目插入的是模型数据、
     * 不读 TextView 文字，标记不会污染粘贴内容。
     *
     * <p>位置 id 按名从 phrase 资源包解析（面板布局来自 {@code com.miui.phrase}，
     * 输入法进程自己的资源里查不到）；解析失败时退回「正文后追加」兜底。
     */
    private fun markPinnedSource(itemView: View, rowTime: Long) {
        try {
            val pinned = rowTime != 0L && rowTime == sPinnedTime
            val tipBox = findViewByPhraseId(itemView, ID_TIP_BOX)
            val tipIcon = findViewByPhraseId(itemView, ID_TIP_ICON)
            val tip = findText(itemView, ID_TIP_TEXT)
            val top = findText(itemView, ID_TEXT_TOP)
            val bottom = findText(itemView, ID_TEXT_BOTTOM)
            // 先剥掉复用残留（View 回收带着旧文字）
            stripMark(tip)
            stripMark(top)
            stripMark(bottom)
            // MIUI 刚 bind 完：来源容器可见即云条目（本地条目被它置 GONE）
            val cloud = tip != null && effectivelyVisible(tip)
            if (cloud && tipIcon != null) {
                // 云条目图标恒显示——恢复可能被上一次「本地置顶」改成的 GONE
                tipIcon.visibility = View.VISIBLE
            }
            // 首次见到云端来源标注：记下它的字号与颜色（此刻 tip 是系统原文，未被我们改过）。
            // 本地置顶复用同一容器时套用这套「小字 + 灰色」，标记行高才与云端条目一致。
            if (cloud && tip != null && sSrcLabelSizePx <= 0f) {
                val sz = tip.textSize
                if (sz > 0f) {
                    sSrcLabelSizePx = sz
                    sSrcLabelColor = tip.currentTextColor
                    log("captured source-label style: sizePx=" + sz
                            + " color=" + Integer.toHexString(sSrcLabelColor))
                }
            }
            // 首次见到云端正文：记下它的上下内边距（本地条目正文自带 22px 呼吸内边距，
            // 云端为 0；本地置顶借来源标注位加行后靠对齐这个值才能与云端等高）。
            if (cloud && !sCloudContentPadCaptured) {
                val cv = if (hasText(top)) top else (if (hasText(bottom)) bottom else null)
                if (cv != null) {
                    sCloudContentPadT = cv.paddingTop
                    sCloudContentPadB = cv.paddingBottom
                    sCloudContentPadCaptured = true
                    log("captured cloud content padding: t=" + sCloudContentPadT
                            + " b=" + sCloudContentPadB)
                }
            }
            // 复位上一轮「我们」改过的正文内边距：ViewHolder 复用或状态翻转后，若不还原，
            // 残留的「已归零内边距」会让复用此 itemView 的非置顶本地条目少了呼吸空间。
            // 只在本地条目上复位——云端条目的内边距是系统自管的（实测为 0），绝不触碰。
            val restored = !cloud && restoreContentPadding(itemView, top, bottom)
            if (PIN_DIAG) {
                log("pin-diag: pinned=" + pinned + " cloud=" + cloud
                        + " tipSizePx=" + (if (tip != null) tip.textSize else -1f)
                        + " tipColor=" + (if (tip != null) Integer.toHexString(tip.currentTextColor) else "-")
                        + " tipVis=" + visibilityOf(tip)
                        + " tipBoxVis=" + visibilityOf(tipBox)
                        + " iconVis=" + visibilityOf(tipIcon)
                        + " itemH=" + itemView.height)
                itemView.post {
                    try {
                        log("pin-diag(G): pinned=" + pinned + " cloud=" + cloud
                                + viewDiag("item", itemView)
                                + viewDiag("tipBox", tipBox)
                                + tvDiag("tip", tip)
                                + tvDiag("top", top)
                                + tvDiag("bot", bottom)
                                + " tipBoxParent=" + (if (tipBox != null
                                    && tipBox.parent is View)
                                    (tipBox.parent as View).javaClass.simpleName else "-")
                                + " topParent=" + (if (top != null
                                    && top.parent is View)
                                    (top.parent as View).javaClass.simpleName else "-"))
                    } catch (ignored: Throwable) {
                    }
                }
            }
            if (!pinned) {
                return
            }
            if (cloud) {
                // 云条目：提醒追加在来源标注（「来自我的电脑」）后——本就在正文上方；
                // 顺手把字号/颜色锁回云端原款（可能被上一轮本地标记改过）。
                if (sSrcLabelSizePx > 0f) {
                    tip?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sSrcLabelSizePx)
                    tip?.setTextColor(sSrcLabelColor)
                }
                appendMark(tip)
                log("pinned source marked: tip time=" + rowTime + (if (restored) " restored" else ""))
            } else if (tipBox != null && tip != null) {
                // 本地条目：借来源标注容器把提醒放到正文上方，隐藏云图标；
                // 并套用云端来源标注的字号/颜色 —— 否则标记会以 tip 默认大字渲染，整行被撑高。
                if (tipIcon != null) {
                    tipIcon.visibility = View.GONE
                }
                tip.text = PIN_MARK
                if (sSrcLabelSizePx > 0f) {
                    tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sSrcLabelSizePx)
                    tip.setTextColor(sSrcLabelColor)
                }
                tipBox.visibility = View.VISIBLE
                // 对齐正文内边距：本地正文自带上下内边距（真机实测 padT=padB=22px），
                // 云端为 0——本地置顶借来源标注位加了「（已置顶）」行后，这段内边距会把
                // 整条撑高（实测本地 165px vs 云端 152px）。把本地正文内边距对齐云端
                // （通常归零）后，本地置顶条目即与云端条目等高。改前原值记账，供复用/
                // 翻转时复位。
                val saved = intArrayOf(
                        if (top != null) top.paddingTop else 0,
                        if (top != null) top.paddingBottom else 0,
                        if (bottom != null) bottom.paddingTop else 0,
                        if (bottom != null) bottom.paddingBottom else 0)
                setContentPadding(top, sCloudContentPadT, sCloudContentPadB)
                setContentPadding(bottom, sCloudContentPadT, sCloudContentPadB)
                synchronized(sPadSaved) {
                    sPadSaved[itemView] = saved
                }
                log("pinned source marked: header time=" + rowTime
                        + " contentPad=" + sCloudContentPadT + "/" + sCloudContentPadB
                        + " markSizePx=" + (if (sSrcLabelSizePx > 0f) sSrcLabelSizePx else -1f))
            } else if (top != null && effectivelyVisible(top)) {
                // 兜底（id 解析失败）：退回正文后追加
                appendMark(top)
                log("pinned source marked: top time=" + rowTime)
            } else if (bottom != null && effectivelyVisible(bottom)) {
                appendMark(bottom)
                log("pinned source marked: bottom time=" + rowTime)
            }
        } catch (t: Throwable) {
            log("mark pinned source failed - " + t)
        }
    }

    /**
     * 有效可见性：视图自身与整条父链都不能有 GONE/INVISIBLE。
     *
     * <p>输入法面板的来源标注容器 {@code clipboard_across_devices_tip} 对本地条目
     * 整体设 GONE，但其子 TextView（{@code clipboard_across_devices_tip_text}）
     * 自身仍报 {@code VISIBLE}——只判自身可见性会把「（已置顶）」打到看不见的
     * 视图上（code 90 bind tree 实锤：本地条目标记标到了 GONE 容器里的子视图），
     * 必须沿父链一起判。
     */
    private fun effectivelyVisible(v: View?): Boolean {
        if (v == null || v.visibility != View.VISIBLE) {
            return false
        }
        var p: ViewParent? = v.parent
        while (p is View) {
            if (p.visibility != View.VISIBLE) {
                return false
            }
            p = p.parent
        }
        return true
    }

    /** 诊断用：视图几何（高度/内边距/layout_height/类名）。 */
    private fun viewDiag(name: String, v: View?): String {
        if (v == null) {
            return " " + name + "=null"
        }
        val lp = v.layoutParams
        val lph = if (lp == null) "?"
                else (if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) "MP"
                else (if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) "WC"
                else lp.height.toString()))
        return " " + name + "[h=" + v.height +
                ",padT=" + v.paddingTop + ",padB=" + v.paddingBottom +
                ",lph=" + lph + ",cls=" + v.javaClass.simpleName + "]"
    }

    /** 诊断用：TextView 几何 + 文本 + 字号 + 行数（定位行高被撑高的真因）。 */
    private fun tvDiag(name: String, v: TextView?): String {
        if (v == null) {
            return " " + name + "=null"
        }
        val cs = v.text
        var t = cs?.toString() ?: ""
        if (t.length > 20) {
            t = t.substring(0, 20) + "…"
        }
        val lp = v.layoutParams
        val lph = if (lp == null) "?"
                else (if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) "MP"
                else (if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) "WC"
                else lp.height.toString()))
        return " " + name + "[t=" + v.top + ",h=" + v.height +
                ",mt=" + marginTopOf(v) + ",sz=" + v.textSize +
                ",ln=" + v.lineCount +
                ",padT=" + v.paddingTop + ",padB=" + v.paddingBottom +
                ",lph=" + lph + ",txt=\"" + t + "\"]"
    }

    /** 诊断用：可见性枚举转可读字符串。 */
    private fun visibilityOf(v: View?): String {
        if (v == null) {
            return "null"
        }
        return when (v.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "?"
        }
    }

    /** 按 phrase 包 id 名找 TextView；解析不到返回 null（调用方走兜底）。 */
    private fun findText(root: View, idName: String): TextView? {
        val id = phraseIdOf(idName)
        if (id == 0) {
            return null
        }
        val v = root.findViewById<View>(id)
        return v as? TextView
    }

    /** 按 phrase 包 id 名找 View；解析不到返回 null。 */
    private fun findViewByPhraseId(root: View, idName: String): View? {
        val id = phraseIdOf(idName)
        return if (id == 0) null else root.findViewById(id)
    }

    /** 剥掉 TextView 末尾的置顶提醒（复用残留清理）。 */
    private fun stripMark(tv: TextView?) {
        if (tv == null) {
            return
        }
        val cs = tv.text
        if (cs == null) {
            return
        }
        val s = cs.toString()
        if (s.endsWith(PIN_MARK)) {
            tv.text = s.substring(0, s.length - PIN_MARK.length)
        }
    }

    /** 追加置顶提醒（已有时不重复追加）。 */
    private fun appendMark(tv: TextView?) {
        if (tv == null) {
            return
        }
        val cs = tv.text
        val s = cs?.toString() ?: ""
        if (!s.endsWith(PIN_MARK)) {
            tv.text = s + PIN_MARK
        }
    }

    /** 读正文 View 的 {@code topMargin}（无 MarginLayoutParams 时按 0）。 */
    private fun marginTopOf(v: TextView?): Int {
        if (v == null) {
            return 0
        }
        val p = v.layoutParams
        if (p is ViewGroup.MarginLayoutParams) {
            return p.topMargin
        }
        return 0
    }

    /** 写正文 TextView 的上下内边距（只改上下，左右保持；变了才 setPadding）。 */
    private fun setContentPadding(v: TextView?, top: Int, bottom: Int) {
        if (v == null || top < 0 || bottom < 0) {
            return
        }
        if (v.paddingTop != top || v.paddingBottom != bottom) {
            v.setPadding(v.paddingLeft, top, v.paddingRight, bottom)
        }
    }

    /** TextView 是否有非空文本（找到云端「正文」那一格用）。 */
    private fun hasText(v: TextView?): Boolean {
        if (v == null) {
            return false
        }
        val cs = v.text
        return cs != null && cs.length > 0
    }

    /**
     * 复位上一条目遗留的正文内边距改动：若该 itemView 曾被我们改过
     * {@code clipboard_text_item_top/bottom} 的上下内边距，则还原成改前的原值。
     *
     * <p>只处理 [sPadSaved] 里记录过的 View——系统自管的内边距绝不动，
     * 因此对云端条目的正常布局零影响。ViewHolder 复用或状态翻转后调用即可避免
     * 「非置顶条目少了呼吸空间、与其它条目高度不一致」。
     *
     * @return 是否发生过复位
     */
    private fun restoreContentPadding(itemView: View, top: TextView?, bottom: TextView?): Boolean {
        val saved: IntArray?
        synchronized(sPadSaved) {
            saved = sPadSaved.remove(itemView)
        }
        if (saved == null) {
            return false
        }
        setContentPadding(top, saved[0], saved[1])
        setContentPadding(bottom, saved[2], saved[3])
        log("content padding restored: top=" + saved[0] + "/" + saved[1]
                + " bottom=" + saved[2] + "/" + saved[3])
        return true
    }

    /** 来源标注前缀（系统 phrase 包字符串；id 解析失败时的兜底匹配）。 */
    private fun isSourceLabel(cs: CharSequence?): Boolean {
        if (cs == null) {
            return false
        }
        val s = cs.toString()
        return s.startsWith("来自我的电脑")
                || s.startsWith("来自我的手机")
                || s.startsWith("来自我的平板")
    }

    /** 递归找来源标注 TextView（深度优先，取第一个命中的）。 */
    private fun findSourceLabel(root: View): TextView? {
        if (root is TextView) {
            return if (isSourceLabel(root.text)) root else null
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val hit = findSourceLabel(root.getChildAt(i))
                if (hit != null) {
                    return hit
                }
            }
        }
        return null
    }

    /** phrase 包 id 名 → id 值缓存（面板布局资源来自 com.miui.phrase）。 */
    private val sPhraseIdCache: MutableMap<String, Int> = HashMap()

    /**
     * 按名解析 phrase 包的 id。输入法进程里 {@code view.getResources()} 拿到的是
     * 输入法自己的资源，按名查必然落空，必须经 PackageManager 取 phrase 的资源包
     * （与 [PanelColorHook] 同款路径）。
     */
    private fun phraseIdOf(name: String): Int {
        val cached = sPhraseIdCache[name]
        if (cached != null) {
            return cached
        }
        var id = 0
        try {
            val ctx = ctxOf()
            if (ctx != null) {
                val res = ctx.packageManager.getResourcesForApplication(XposedInit.PKG_PHRASE)
                id = res.getIdentifier(name, "id", XposedInit.PKG_PHRASE)
            }
        } catch (ignored: Throwable) {
            // 解析失败：id 保持 0，调用方走兜底
        }
        sPhraseIdCache[name] = id
        return id
    }

    /** 诊断：前 3 次 bind 各打一次条目视图树（类名 + id 名 + 可见性 + 文本）。 */
    private var sTreeDumps = 0

    private fun dumpTreeOnce(root: View?) {
        if (sTreeDumps >= 3 || root == null) {
            return
        }
        sTreeDumps++
        val sb = StringBuilder("bind tree #" + sTreeDumps + ":")
        dumpTree(root, sb, 0)
        log(sb.toString())
    }

    private fun dumpTree(v: View, sb: StringBuilder, depth: Int) {
        try {
            sb.append('\n')
            for (i in 0 until depth) {
                sb.append(' ')
            }
            sb.append(v.javaClass.simpleName)
            val id = v.id
            if (id != View.NO_ID) {
                var name: String? = null
                try {
                    name = v.resources.getResourceName(id)
                } catch (ignored: Throwable) {
                    // 解析不了只打 hex
                }
                sb.append(" id=").append(name ?: Integer.toHexString(id))
            }
            sb.append(" vis=").append(v.visibility)
            if (v is TextView) {
                val t = v.text
                if (t != null) {
                    val s = t.toString()
                    sb.append(" text=\"")
                            .append(if (s.length > 40) s.substring(0, 40) + "…" else s)
                            .append('"')
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    dumpTree(v.getChildAt(i), sb, depth + 2)
                }
            }
        } catch (t: Throwable) {
            sb.append(" <dump failed: ").append(t).append('>')
        }
    }

    /** 取适配器位置对应条目的 time（优先当前列表，退回查询快照）；取不到返回 0。 */
    private fun rowTimeAt(pos: Int): Long {
        val list = sDisplayListRef.get()
        if (list != null && pos >= 0 && pos < list.size) {
            val t = timeOfItem(list[pos])
            if (t != 0L) {
                return t
            }
        }
        if (pos >= 0 && pos < sRowTimes.size) {
            return sRowTimes[pos]
        }
        return 0L
    }

    // ---------------- 置顶状态持久化 ----------------

    private fun persistPin(ctx: Context?, time: Long, json: String) {
        if (ctx == null) {
            return
        }
        var w: FileWriter? = null
        try {
            val f = File(ctx.filesDir, PIN_FILE)
            w = FileWriter(f)
            w.write(time.toString() + "\n" + json)
            w.flush()
        } catch (t: Throwable) {
            log("persistPin failed - " + t)
        } finally {
            if (w != null) {
                try {
                    w.close()
                } catch (ignored: Throwable) {
                    // 关闭失败忽略
                }
            }
        }
    }

    /** 清内存态并删除持久化文件。query 重排路径调用（严禁再触发刷新）。 */
    private fun clearPinState(ctx: Context?) {
        sPinnedTime = 0L
        sPinnedJson = null
        if (ctx != null) {
            try {
                File(ctx.filesDir, PIN_FILE).delete()
            } catch (ignored: Throwable) {
                // 删除失败忽略
            }
        }
    }

    /** 从输入法 files 目录恢复置顶状态（只试一次）。 */
    private fun loadPinIfNeeded(ctx: Context?) {
        if (sPinLoaded || ctx == null) {
            return
        }
        sPinLoaded = true
        var r: BufferedReader? = null
        try {
            val f = File(ctx.filesDir, PIN_FILE)
            if (!f.exists()) {
                return
            }
            r = BufferedReader(FileReader(f))
            val first = r.readLine()
            if (first == null) {
                return
            }
            val sb = StringBuilder()
            var line = r.readLine()
            while (line != null) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                sb.append(line)
                line = r.readLine()
            }
            val time = java.lang.Long.parseLong(first.trim())
            val json = sb.toString()
            if (time != 0L && json.length > 2) {
                sPinnedTime = time
                sPinnedJson = json
                log("pin restored from disk: time=" + time)
            }
        } catch (t: Throwable) {
            log("loadPinIfNeeded failed - " + t)
        } finally {
            if (r != null) {
                try {
                    r.close()
                } catch (ignored: Throwable) {
                    // 关闭失败忽略
                }
            }
        }
    }

    // ---------------- 手势辅助 ----------------

    private fun stateOf(key: Any): GestureState {
        synchronized(sStates) {
            var st = sStates[key]
            if (st == null) {
                st = GestureState()
                sStates[key] = st
            }
            return st
        }
    }

    /** SwipeRevealLayout 的主内容视图（child1）。 */
    private fun mainViewOf(swipe: Any): View? {
        try {
            val v = Reflect.getField(swipe, "mMainView")
            if (v is View) {
                return v
            }
        } catch (t: Throwable) {
            log("mainViewOf failed - " + t)
        }
        return null
    }

    private fun slopOf(v: View): Float {
        return try {
            ViewConfiguration.get(v.context).scaledTouchSlop.toFloat()
        } catch (t: Throwable) {
            24f
        }
    }

    /**
     * 置顶门宽（dp→px）：按钮直径 + 单侧边距。
     *
     * <p>与原生删除门同规格：删除门 = 18dp 边距 + 58dp 圆 = 76dp；置顶门镜像取同值。
     * 按钮布于 SRL 左缘 18dp 处，右缘顶在门宽边界（主视图归位后的左缘）。
     */
    private fun pinDoorWidth(v: View): Float {
        var density = 3f
        try {
            density = v.resources.displayMetrics.density
        } catch (ignored: Throwable) {
            // 用默认密度
        }
        return (PIN_BTN_DP + PIN_BTN_MARGIN_DP) * density
    }

    // ---------------- 置顶按钮（每条目一个，懒创建） ----------------

    /** SwipeRevealLayout → 置顶按钮视图。 */
    private val sPinBtns: MutableMap<Any, View> = java.util.WeakHashMap()

    /**
     * 取（没有则创建）置顶按钮并挂到 SwipeRevealLayout 上。
     *
     * <p>按钮 addView 到<b>末尾</b>（child2）：MIUI 的 onLayout 只摆它认识的
     * child0（删除按钮）/ child1（主视图），且 {@code onFinishInflate} 早已跑完
     * （mSecondaryView/mMainView 是缓存字段），不会因多一个 child 而错位；
     * 按钮自身位置由 [hookOnLayout] 在每次 layout 后手动摆到左侧。
     * 按钮<b>不可点击</b>（不消费触摸），点击命中由手势状态机在 UP 时按矩形判断，
     * 这样从按钮上起滑关门也不会被按钮吞掉事件。
     *
     * <p>图标：蓝底正圆内居中放一枚<b>上箭头</b>（[PinIcon]，base64 内嵌、
     * 运行时解码），替代旧版的白色「顶」字。原图是深藏青、在蓝底上对比不足，
     * 故按 SRC_IN 染成白色，与旧版白字视觉一致。解码失败时回退为旧版文字样式。
     */
    private fun pinBtnOf(swipe: Any): View? {
        synchronized(sPinBtns) {
            val btn = sPinBtns[swipe]
            if (btn != null) {
                return btn
            }
            val sv = swipe as View
            val density = sv.resources.displayMetrics.density
            val size = (PIN_BTN_DP * density + 0.5f).toInt()

            // 图标优先；解码失败退回旧版「顶」字，保证按钮永远有内容。
            // 具体图标（置顶上箭头 / 取消置顶「不」符号）不在这里选，
            // 由 [applyPinIcon] 按条目当前置顶态填充——RV 复用
            // 条目时 swipe 实例不变、对应条目会变，创建时选一次会错。
            val content: View
            val icon = PinIcon.get()
            if (icon != null) {
                val iv = ImageView(sv.context)
                val pad = (PIN_ICON_PAD_DP * density + 0.5f).toInt()
                iv.setPadding(pad, pad, pad, pad)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                // 原图 #FF272636 深藏青，蓝底圆上对比不足；染白与旧版白色「顶」字一致
                // （取消置顶图标是 alpha 图 RGB 纯白，同滤色器下无副作用）
                iv.setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                content = iv
            } else {
                val tv = TextView(sv.context)
                tv.text = "顶"
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                tv.gravity = android.view.Gravity.CENTER
                content = tv
            }
            content.layoutParams = ViewGroup.LayoutParams(size, size)
            content.isClickable = false
            content.isFocusable = false
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(PIN_BTN_COLOR)
            content.background = bg
            // 初始位置在「门外」：整体向左移出一个门宽，被父容器左边界裁掉（不可见）。
            // 开门时由 [syncPinBtn] 让它随主视图一起滑进露出区。
            content.translationX = -pinDoorWidth(sv)
            try {
                (sv as ViewGroup).addView(content)
                sPinBtns[swipe] = content
                log("pin button created for " + sv.javaClass.simpleName)
                // 创建即按当前条目置顶态选图标（RV 复用视图时也不会错）
                applyPinIcon(swipe, content)
            } catch (t: Throwable) {
                log("pin button addView failed - " + t)
                return null
            }
            return content
        }
    }

    /**
     * 按条目当前置顶态填按钮图标：已置顶 → 「取消置顶」符号
     * （[PinCancelIcon]，横杠 + 斜杠）；未置顶 → 置顶上箭头
     * （[PinIcon]）。用户 2026-10-19 需求：置顶与取消置顶用不同图标，
     * 已置顶条目右滑时按钮应表达「再点一次=取消」的语义。
     *
     * <p>每次按钮显示（[showPinBtn] true）都重新判定，不用 tag 记账
     * 以外的缓存——RV 复用条目时 swipe 实例不变、对应条目会变。
     * 取图标失败时保留上一个图标（ImageView 空图标会被父圆底衬着，比闪回
     * 「顶」字好）；两个图标都解码失败时按钮本就是 TextView 回退，不会到这。
     */
    private fun applyPinIcon(swipe: Any, btn: View) {
        if (btn !is ImageView) {
            return
        }
        val pinned = isRowPinned(swipe)
        var icon = if (pinned) PinCancelIcon.get() else PinIcon.get()
        if (icon == null && pinned) {
            // 取消置顶图标解码失败：退回置顶图标（功能不丢，视觉降级）
            icon = PinIcon.get()
        }
        if (icon == null) {
            return
        }
        btn.setImageBitmap(icon)
    }

    /** 当前 swipe 对应条目是否处于置顶态（无置顶条目时零开销快路）。 */
    private fun isRowPinned(swipe: Any): Boolean {
        if (sPinnedTime == 0L) {
            return false
        }
        return try {
            timeOfRow(swipe) == sPinnedTime
        } catch (t: Throwable) {
            false
        }
    }

    /** 手动把置顶按钮摆到条目左侧垂直居中（MIUI onLayout 不认识它）。 */
    private fun layoutPinBtn(swipe: Any, btn: View) {
        val sv = swipe as View
        val w = sv.width
        val h = sv.height
        if (w <= 0 || h <= 0) {
            return
        }
        val density = sv.resources.displayMetrics.density
        val size = (PIN_BTN_DP * density + 0.5f).toInt()
        val margin = (PIN_BTN_MARGIN_DP * density + 0.5f).toInt()
        val top = (h - size) / 2
        btn.layout(margin, top, margin + size, top + size)
    }

    /**
     * 置顶按钮显隐。
     *
     * <p>不涉及其位移：位移由 [syncPinBtn]（跟手）与开关门动画负责。
     * 隐藏时把按钮放回「门外」起始位，供下次开门滑入；显示时不动位移，
     * 由调用方紧接着同步（开门瞬间按钮还在门外被裁着，不会闪）。
     */
    private fun showPinBtn(swipe: Any, visible: Boolean) {
        var btn = sPinBtns[swipe]
        if (btn == null) {
            if (!visible) {
                return
            }
            btn = pinBtnOf(swipe)
            if (btn == null) {
                return
            }
        }
        val want = if (visible) View.VISIBLE else View.GONE
        // 先取消在途动画：否则上一次关门动画的 withEndAction 可能把刚重新打开的按钮又置 GONE
        btn.animate().cancel()
        if (!visible) {
            btn.translationX = -pinDoorWidth(swipe as View)
        }
        if (visible) {
            // 每次显示都按条目当前置顶态刷新图标（RV 复用会换条目，
            // 且 togglePin 后面板刷新会重排，不能靠创建时的一次选择）
            applyPinIcon(swipe, btn)
        }
        if (btn.visibility != want) {
            btn.visibility = want
        }
    }

    /**
     * 按主视图位移同步置顶按钮位置：{@code 按钮 translationX = 主视图 translationX - 门宽}。
     *
     * <p><b>为什么这样算</b>：真机实测（对用户取消删除的过程截图逐帧量红圆坐标）发现，
     * 系统删除按钮并不是「原地不动等着被主视图盖住」，而是<b>被条目推着同向等量位移</b>，
     * 右缘始终顶在面板边界上被裁掉、左缘一路右移（693→742→770→794→820→消失），
     * 位移量恰为一个门宽时整体越界不见。置顶按钮在左侧，做镜像即可：
     * 门全关时按钮移出父容器<b>左</b>边界被裁掉（不可见），门全开时正好落在露出区，
     * 且按钮右缘恒定贴在主视图左缘旁——视觉上就是「条目推着按钮一起走、越界被裁」。
     *
     * <p>相比早期用透明度渐隐的方案，这个方案与原生删除按钮的机制完全同构，
     * 也不会出现「按钮浮在条目上」的问题（它始终在露出区里）。
     *
     * <p>几何（与原生删除门对齐）：门全开时按钮占据 SRL 左缘 18dp..76dp，
     * 右缘恒定贴在主视图左缘（主视图位移量 = 门宽 76dp）。
     */
    private fun syncPinBtn(swipe: Any, bw: Float, mainTx: Float) {
        val btn = sPinBtns[swipe]
        if (btn == null) {
            return
        }
        btn.translationX = mainTx - bw
    }

    /**
     * 中和系统删除门：调用系统自己的 {@code SwipeRevealLayout.close(false)}——
     * {@code resetClose()} 把主视图/删除钮 layout 回关门位、
     * {@code mDragHelper.a()} 取消拖动（清指针 + 中断滚动 + 置 IDLE）、
     * {@code mState=0}。相当于系统的「硬关门」，可作两轨属性的中和器。
     *
     * <p><b>为什么需要它</b>（本会话反编译实证）：系统 {@code M.e.tryCaptureView}
     * 有副作用——每次 DOWN 都会 {@code captureChildView(mMainView)} 把系统拖动
     * 武装成 DRAGGING，且拖动分支没有逐事件 slop 校验；我们 proceed 掉的每个
     * MOVE 都会 1:1 拖动主视图 {@code left}（删除门）。一旦 UP 被我们消费
     *（系统收不到 onViewReleased，没有关门兜底），删除门就冻结在被拖开的
     * 位置——轻则残留红边，重则与置顶门同显。凡我们接管/消费手势的前后，
     * 都先中和掉系统门，保证任一时刻只有一扇门。
     *
     * <p>删除门关着（主视图 left 未低于关门位）时零开销返回；系统方法反射失败时
     * 仅记日志不崩（手势状态机自身仍有实际态兜底）。
     */
    private fun neutralizeSystemDoor(swipe: Any, main: View?) {
        if (!systemDoorOpen(swipe, main)) {
            return
        }
        // 先记触发前 left：close(false) 会把门修回关门位，
        // 事后再读只能看到回位值（恒 47），无法验证判据是否抓对
        val before = main?.left ?: 0
        val closedLeft = closedLeftOf(swipe)
        try {
            Reflect.callMethod(swipe, "close", false)
            log("system delete door neutralized (left " + before + " -> "
                    + (main?.left ?: 0) + ", closedLeft=" + closedLeft + ")")
        } catch (t: Throwable) {
            log("WARN: neutralizeSystemDoor - " + t)
        }
    }

    /**
     * 关门：主视图归位 + 按钮同向等量滑回「门外」并被父容器裁掉（150ms，与主视图回弹同步）。
     *
     * <p>机制同原生删除门：删除按钮是被条目推着走、越界被裁；置顶按钮做镜像——
     * 跟着条目滑回左边，整体移出父容器左边界后被裁掉，动画结束置 GONE。
     */
    private fun closePinDoor(swipe: Any, main: View?, bw: Float) {
        // 清理单开记账：本条关门后不再占用「唯一开门条」名额
        clearPinDoorOpen(swipe)
        if (main != null) {
            main.animate().translationX(0f).setDuration(150L).start()
        }
        val btn = sPinBtns[swipe]
        if (btn == null) {
            return
        }
        btn.animate().cancel()
        if (btn.visibility == View.VISIBLE) {
            btn.animate().translationX(-bw).setDuration(150L).withEndAction {
                if (btn.translationX <= -bw + 0.5f) {
                    btn.visibility = View.GONE
                }
            }.start()
        } else {
            btn.translationX = -bw
        }
    }

    /** 触摸点是否落在置顶按钮矩形内。 */
    private fun isInPinBtn(sv: View, ev: MotionEvent): Boolean {
        val btn = sPinBtns[sv]
        if (btn == null || btn.visibility != View.VISIBLE) {
            return false
        }
        val x = ev.x
        val y = ev.y
        // 按钮位置 = layout 位置 + translationX（跟手/动画中位移 nontrivial，必须算进去）
        val off = btn.translationX
        return x >= btn.left + off && x <= btn.right + off
                && y >= btn.top && y <= btn.bottom
    }

    /**
     * 条目被复用时重置其置顶门状态：主视图归位、按钮隐藏、状态回 MODE_NONE。
     *
     * <p>RV 复用 item 不会自动清理 translationX/自定义子 view 显隐，开门态条目
     * 被滑出屏幕再复用会带着 +bw 位移和可见按钮显示成错乱态。
     *
     * <p><b>判据看「视图实际态」，不只看 mode</b>：取消置顶后 mode 已回 NONE，
     * 但 150ms 关门动画仍在途（translationX/按钮可见），此时
     * {@code refreshPanel → setClipboardList} 触发 rebind 必须硬复位——
     * 否则在途动画的 end-action 或残留按钮会挂到（可能已绑定别的条目的）
     * 复用视图上。系统删除门冻结开着（left 低于关门位）一并中和。
     */
    private fun resetPinDoorIfNeeded(itemView: View) {
        val swipe = findSwipeReveal(itemView, 0) ?: return
        val st = sStates[swipe]
        val main = mainViewOf(swipe)
        val btn = sPinBtns[swipe]
        val dirty = (st != null && st.mode != MODE_NONE)
                || (main != null && (main.translationX > 1f || systemDoorOpen(swipe, main)))
                || (btn != null && btn.visibility == View.VISIBLE)
        if (!dirty) {
            return
        }
        clearPinDoorOpen(swipe)
        if (st != null) {
            st.mode = MODE_NONE
            st.declined = false
        }
        if (main != null) {
            main.animate().cancel()
            main.translationX = 0f
            neutralizeSystemDoor(swipe, main)
        }
        showPinBtn(swipe, false)
        log("pin door reset on rebind")
    }

    /** 在视图树里找 SwipeRevealLayout 实例（条目根通常就是它）。 */
    private fun findSwipeReveal(v: View?, depth: Int): Any? {
        if (v == null || depth > 3) {
            return null
        }
        if (SWIPE_REVEAL == v.javaClass.name) {
            return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findSwipeReveal(v.getChildAt(i), depth + 1)
                if (r != null) {
                    return r
                }
            }
        }
        return null
    }

    // ---------------- 上下文 / 反射工具 ----------------

    private val atClass: Class<*>? = try {
        Class.forName("android.app.ActivityThread")
    } catch (ignored: Throwable) {
        // ActivityThread 不可用，走兜底
        null
    }

    private val atCurrentApp: Method? = try {
        atClass?.getMethod("currentApplication")
    } catch (ignored: Throwable) {
        // currentApplication 不可用，走兜底
        null
    }

    /**
     * 取输入法进程自己的 Application context。
     *
     * <p><b>不要用面板 popup 的 contentView context</b>——面板要加载 {@code com.miui.phrase}
     * 的资源，其 context 实际是 phrase 包的 context（实测 files 目录落在
     * {@code /data/user/0/com.miui.phrase/} 下，写入失败）。持久化目录与
     * ContentResolver 的 callingPackage 都应归属当前输入法进程，故优先取
     * {@code ActivityThread.currentApplication()}；取不到再退回面板 context 兜底。
     */
    private fun ctxOf(): Context? {
        if (atCurrentApp != null) {
            try {
                val app = atCurrentApp.invoke(null)
                if (app is Context) {
                    return app
                }
            } catch (ignored: Throwable) {
                // ActivityThread 不可用，走兜底
            }
        }
        return sImeCtxRef.get()
    }

    private fun popupOf(): PopupWindow? {
        return sPopupRef.get()
    }

    private fun resolverOf(): ContentResolver? {
        val c = ctxOf()
        return c?.contentResolver
    }

    /**
     * v4.17：判断某个 content/file uri 在当前进程（输入法进程）是否真的能打开读到内容。
     *
     * <p>用于判断「缩略图坏了、改走本地原图」是否可行：云端图片的 fileUri 指向小米互联
     * provider（{@code com.xiaomi.continuity.universal.clipboard}），输入法进程持有剪贴板
     * 访问能力时可以读到原图；读不到就保持缩略图路径，避免把原本能显示的条目录成黑块。
     * 结果按 uri 做个小缓存，避免列表滚动时反复开流。
     */
    private val sUriReadableCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @JvmStatic
    fun uriReadable(uri: String): Boolean {
        if (uri.isEmpty()) {
            return false
        }
        sUriReadableCache[uri]?.let { return it }
        val ok = try {
            val ctx = ctxOf()
            if (ctx == null) {
                false
            } else {
                val input = ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))
                var readOk = input != null
                try {
                    if (input != null) {
                        val one = ByteArray(1)
                        readOk = input.read(one) >= 0
                    }
                } catch (_: Throwable) {
                    readOk = false
                } finally {
                    try {
                        input?.close()
                    } catch (_: Throwable) {
                        // 关闭失败忽略
                    }
                }
                readOk
            }
        } catch (_: Throwable) {
            false
        }
        if (sUriReadableCache.size > 200) {
            sUriReadableCache.clear()
        }
        sUriReadableCache[uri] = ok
        return ok
    }

    private fun adapterOf(): Any? {
        return sAdapterRef.get()
    }

    private fun clOf(): ClassLoader? {
        return sPanelCl ?: sDefaultCl
    }

    /** 面板 classloader 优先、默认 classloader 兜底解析类。 */
    private fun resolve(name: String): Class<*>? {
        val loaders = arrayOf(sPanelCl, sDefaultCl)
        for (cl in loaders) {
            if (cl == null) {
                continue
            }
            try {
                return Reflect.findClass(name, cl)
            } catch (ignored: Throwable) {
                // 换下一个 classloader
            }
        }
        return null
    }

    private val sRvCache: MutableMap<ClassLoader, Class<*>> =
            java.util.concurrent.ConcurrentHashMap()

    private fun isRecyclerView(o: Any?): Boolean {
        if (o == null) {
            return false
        }
        val cl = o.javaClass.classLoader
        var c = sRvCache[cl]
        if (c == null) {
            c = try {
                Class.forName(RECYCLER_VIEW, false, cl)
            } catch (t: Throwable) {
                return false
            }
            sRvCache[cl] = c
        }
        return c.isInstance(o)
    }

    /** 深度优先找视图树里第一个 RecyclerView（面板内容容器内的列表）。 */
    private fun findRecyclerView(root: View?): Any? {
        if (root == null) {
            return null
        }
        if (isRecyclerView(root)) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findRecyclerView(root.getChildAt(i))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    // ---------------- 其他 ----------------

    /** 从 ClipboardContentModel JSON 里取 "time" 数值；取不到返回 0。 */
    private fun extractTime(json: String?): Long {
        if (json == null) {
            return 0L
        }
        try {
            var i = json.indexOf("\"time\"")
            if (i < 0) {
                return 0L
            }
            i = json.indexOf(':', i)
            if (i < 0) {
                return 0L
            }
            i++
            while (i < json.length && json[i] == ' ') {
                i++
            }
            val start = i
            while (i < json.length && Character.isDigit(json[i])) {
                i++
            }
            if (start == i) {
                return 0L
            }
            return java.lang.Long.parseLong(json.substring(start, i))
        } catch (t: Throwable) {
            return 0L
        }
    }

    /** 每实例手势状态。 */
    private class GestureState {
        var downX = 0f
        var downY = 0f
        /** [MODE_NONE] / [MODE_OPENING] / [MODE_PIN_OPEN]。 */
        var mode = 0
        var declined = false
    }

    private fun log(msg: String) {
        XposedInit.log("[Pin] " + msg)
    }
}
