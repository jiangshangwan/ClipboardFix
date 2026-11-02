package com.clipboardfix

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method

/**
 * 修复跨设备剪贴板（云端复制的图片）在第三方输入法下「关闭面板再打开就不显示」的 bug。
 *
 * ============ 根因 ============
 * 面板 [com.miui.inputmethod.InputMethodClipboardPhrasePopupView] 打开时：
 *   - 云端条目仅由 /across 这个 ContentObserver 的 onChange（且面板 isShowing）回调
 *     setRemoteDataToView() 合并进来。跨设备云端内容是静态的，关闭再打开时内容没变 →
 *     /across 不会重新通知 → onChange 在整个显示窗口内永不触发 → 云端条目永远合不进新面板。
 *   - 另：showClipBoard() = new popup → initPopupWindow()(含 setContentView+showAtLocation)
 *     → initData()；$2.run 填完本地列表后 $2$1 调 adapter.setClipboardList(本地列表)，
 *     该方法是 mClipboardList = p1 整表替换，会把首帧合入的云端条目冲掉。
 *
 * ============ 进程归属 ============
 * 面板运行在输入法进程（如微信输入法 :hld），由 InputMethodModuleManager.loadDex 动态加载，
 * 进程刚起时不在 classpath，按名 loadClass 必抛 ClassNotFound。因此修复必须装在面板实际出现的
 * 进程，用框架 PopupWindow 的显示点（按实例类名识别面板）触发——与 PanelColorHook 同思路。
 *
 * ============ 修复（v4 / v4.1 / v4.2，本类全部实现）============
 *  v4【显示层·进程无关】：hook 框架 android.widget.PopupWindow.showAtLocation，回调里用实例
 *     类名识别面板，每次面板打开主动调两次 setRemoteDataToView()（立即 + 600ms 后），把云端
 *     条目强制合进列表，绕开「必须等 /across 变更通知」的限制。该 hook 装在所有被 hook 进程，
 *     面板在哪出现就在哪生效。
 *  v4.1【打开即出】：hook MiuiClipboardManager.getCrossDeviceClipboardData 缓存云端数据；
 *     hook InputMethodClipboardAdapter.setClipboardList，在本地列表灌入时把缺失的云端条目
 *     补回列表头部（云端在顶、幂等去重），避免被整表替换冲掉，面板首帧即含云端图。
 *  v4.3【去重键修正】：v4.1 原用 {@code list.contains(cache[0])} 判断云端条目是否已在列表里，
 *     实测恒为 true——{@code ClipboardContentModel.equals()} 是粗粒度实现（未落库条目 id 均为
 *     默认值，任意两条目即相等），导致 prepend 永远被跳过，表现为「数据查到了但不显示」
 *     （用户 2026-10-25 复测：15 Pro 重启后第三方输入法面板云端 21 条裁 20 条成功、两次
 *     setClipboardList 均实录，但 prepend 日志零触发）。改用 time 去重：同一毫秒的复制事件
 *     才可能撞键，实际不存在；云条目 time 不在入列表即缺失，补回头部。
 *  v4.2【重启就绪重试】：开机后 com.miui.phrase 进程/数据未就绪，第三方输入法进程里首帧
 *     getCrossDeviceClipboardData（经 content://com.miui.phrase.input.provider 实时查询）
 *     返回空列表，而 /across observer 只在数据「变更」时回调（provider 首次加载不算变更），
 *     云端条目再也合不进来——表现为「重启后云同步假失效」，需切到默认输入法打开面板再切回
 *     才恢复（用户 2026-10-25 反馈）。修法：面板打开后有界重试 setRemoteDataToView()
 *     （其内部每次都重新查询 provider），拿到非空云端数据即停；面板消失或超上限也停
 *     （缓存仍在，下次打开走 setClipboardList 补回）。
 *  v4.5【数量上限解锁兼容】：phrase 的 lambda$setRemoteDataToView$9 修剪循环
 *     （云端+显示-20>0 时从显示列表尾部删）会把面板总量恒定压到 20——开启「解锁剪贴板
 *     数量上限」后本地可存 20+ 条，每次开面板都被砍回 20，用户感知「上限解锁失效」。
 *     而 getCrossDeviceClipboardData 全 dex 只有 lambda$9 一个调用方（指令级扫描实证），
 *     故解锁开启时：hook 内全量缓存云端数据（不裁）后向 lambda$9 返回空列表，其 isEmpty
 *     判断直接早退——修剪与 remove(-1) 崩溃同时绕开；显示改由 setClipboardList 的全量
 *     本地 + 本 hook 的增量合入（prepend + notify）维护，面板显示全部条目。
 *     开关关闭时行为与本版之前完全一致（云端裁 20 防崩 + 原生修剪）。
 *  v4.6【云端图片重复条目】：v4.3 的 time 去重对图片条目永久失效——phrase 每次新云
 *     推送到达都会刷新图片条目的 time（DIAG 实证同图两次查询 time 相差数百 ms），
 *     同一张图每推一次插一条，用户感知「云端图片同步过来一次性出现两条」。去重键升级
 *     为稳定身份键 [entryKey]：图片条目取 {@code img:<deviceId>:<uriType>:<dataSize>}
 *     （均取自 determineContent 的 ClipDataItemModel JSON，不含 time；uriFileName 含
 *     到达时间戳也不能用），文本条目 time 稳定沿用 time 键。
 *  v4.7【关面板再开图片丢失】：用户报告「先复制图片条目、再复制一条纯文字条目、关面板再
 *     打开面板，之前复制的图片条目丢失」。实证：开面板 phrase 会下发两次 setClipboardList，
 *     第一次含全部（含图片，22 行），约 20ms 后第二次只含文字（15 行，图片被整表冲掉）。
 *     数量上限解锁开启时本 hook 让 getCrossDeviceClipboardData 返回空令 lambda$9 早退，
 *     而该空返回只截断了「最新一条云端推送」，无法提供此前已落本地库、却不是最新推送的
 *     图片（用户场景里最新推送是那条纯文字）。故新增 [sSeenCloud]：记住显示列表里出现过的
 *     图片条目（按 entryKey 索引其对象），任何一次 setClipboardList 若把它们冲掉即按原顺序
 *     补回头部。幂等于 v4.6 的 entryKey 去重，不重复；仅数量上限解锁开启时启用，关时走原逻辑。
 *
 *  历史上曾尝试用「保活 /across 观察者」（定时器 + 拦截 endObserveThumbnailInfoChange）来
 *  间接刷新显示层，但观察者活着 ≠ /across 会变更，对显示层无效，相关代码已作为失效代码删除；
 *  键盘上方的云端剪贴板浮标属系统原生行为，本修复不影响它。
 */
object CrossDeviceClipboardHook {

    private const val TAG = "[CrossDevice]"

    /** 面板打开后二次合并云端的延迟，覆盖 initData 内异步填本地列表的时序。 */
    private const val MERGE_DELAY_MS = 600L

    /** 云端就绪重试间隔：重启后 phrase provider 异步就绪，首帧合并常拿到空列表。 */
    private const val CLOUD_RETRY_MS = 1000L

    /** 云端就绪重试次数上限（约 30s 窗口，覆盖开机后 provider 进程启动与数据就绪）。 */
    private const val CLOUD_RETRY_MAX = 30

    /**
     * 剪贴板列表显示上限（phrase 的 {@code MAX_CLIPBOARD_LIST_SIZE}）。
     *
     * <p><b>为什么必须裁到这个数</b>：phrase 的云端合并逻辑
     * {@code lambda$setRemoteDataToView$9} 修剪循环有缺陷——总条数超 20 时执行
     * {@code remove((本地数-1)-i)}，云端 > 20 条时修剪量必然超过本地列表长度，索引算到
     * -1 抛 {@code IndexOutOfBoundsException} 直接崩掉输入法进程（小米15 Pro 云端 21 条，
     * 每次开剪贴板面板必闪退，用户 2026-10-25 反馈；本模块 v4 起强制开面板合并使其必触发，
     * observer 路径同样经该方法）。裁到最新 20 条后修剪量 ≤ 本地数，索引恒 ≥ 0。
     *
     * <p><b>仅在「解锁剪贴板数量上限」关闭时生效</b>：开启时走 v4.5 路径（返回空列表
     * 短路 lambda$9），不修剪也不崩，本常量不参与。
     */
    private const val MAX_CLIP_LIST = 20

    /** 框架 PopupWindow 全限定名（任何进程都能按名拿到）。 */
    private const val POPUP_WINDOW = "android.widget.PopupWindow"

    /** 我们的剪贴板面板类名（输入法进程动态 dex 加载，实例自带真实 Class，无需按名解析）。 */
    private const val PHRASE_POPUP = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"

    /** 云端数据来源（getCrossDeviceClipboardData 所在，动态 dex，实例出现后才可解析）。 */
    private const val MIUI_CLIPBOARD_MANAGER = "com.miui.inputmethod.MiuiClipboardManager"

    /** 面板列表适配器（setClipboardList 整表替换点，动态 dex）。 */
    private const val CLIP_ADAPTER = "com.miui.inputmethod.InputMethodClipboardAdapter"

    /** v4.21：面板弹出视图（单条删除的真正入口所在类）。 */
    private const val CLIP_POPUP_VIEW = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** v4.16：兜底重拉本地列表的冷却间隔（ms），避免同一条云端推送反复触发重拉。 */
    private const val RELOAD_COOLDOWN_MS = 1500L

    /** v4.16：上次兜底重拉本地列表的时刻（ms）。 */
    private var sLastReloadMs = 0L

    /** 显示层（v4）是否已安装：进程内只装一次。 */
    private var panelMergeInited = false

    /** 每个面板实例只触发一次合并（showAtLocation 可能被多次调用，按实例去重）。 */
    private val sMerged = java.util.WeakHashMap<Any, Boolean>()

    /** 按面板 Class 缓存 setRemoteDataToView，避免每次打开都反射查找。 */
    private val mergeMethodCache = java.util.WeakHashMap<Class<*>, Method?>()

    /** 云端条目缓存（getCrossDeviceClipboardData 最近一次返回），供 setClipboardList 补回。 */
    @Volatile
    private var sCloudCache: List<Any?>? = null

    /**
     * 当前面板 adapter 实例的弱引用（setClipboardList 被调时记录）。
     *
     * <p>v4.5 起用于「数量上限解锁开启」时把云端条目增量合入面板**当前显示列表**
     * （observer 路径下 $2$1 不会重跑，只能直接操作 adapter 的列表再刷新）。
     */
    @Volatile
    private var sAdapterRef: java.lang.ref.WeakReference<Any>? = null

    /**
     * 图片条目缓存（按 [entryKey] 索引其对象引用），进程生命周期内持久。
     *
     * <p>v4.7：phrase 在开面板时会下发一次「不含跨设备图片」的 setClipboardList，把图片整表冲掉。
     * 数量上限解锁开启时本 hook 让 getCrossDeviceClipboardData 返回空令 lambda$9 早退，该空返回
     * 只截断「最新一条云端推送」——用户「先图后文」场景里最新推送是文字，旧图无从获取，图片永久丢失。
     *
     * <p><b>关键</b>：旧图**不在**本地剪贴板库（getClipboardData 的 fresh 查询结果不含它），
     * 仅存在于「它刚同步那会儿是最新推送」的云端查询结果里。故必须在**它成为最新推送那一刻**
     * （getCrossDeviceClipboardData 返回时）把它存进本缓存，且**跨面板会话保留不清空**——
     * 关面板再开、它已不是最新推送时，靠本缓存把它补回头部，图片才不丢。
     * 去重按 entryKey 幂等（v4.6），不会重复；仅数量上限解锁开启时参与合并（见 [mergedCloudCache]）。
     */
    private val sSeenCloud = java.util.LinkedHashMap<String, Any?>()

    /**
     * 已被「一键清空」的条目：entryKey → 被清空那条的 time（精确屏蔽其复活）。
     *
     * <p>v4.13 曾按「清空时刻」一刀切（time <= wipeTime 全拦），结果误伤面板开着时正常到达的
     * 云端条目（时间过滤持久生效，新云条也被拦）——表现为「云端条目不实时显示、关面板重开才有」。
     * 改为只屏蔽**被清空的那些具体条目**（按 entryKey 记账）：清空时把当时显示列表/缓存里条目的
     * entryKey 记进来，之后任何补回路径（缓存残留 / 跨设备重拉）都跳过这些 key；新条目
     * （entryKey 不同）照常显示。
     *
     * <p><b>v4.16：屏蔽改为「一次复制一代」，不再永久屏蔽</b>——图片条目的 entryKey 是稳定键
     * （{@code img:<deviceId>:<uriType>:<dataSize>}，v4.6 因图片 time 每次推送都刷新而
     * 不能用 time 做键），同一张图清空后再复制一次，键与已屏蔽的完全相同 → 被永久拦掉。
     * 而云端图片**推送瞬间就落本地库**（日志实证 {@code setClipBoardDataToLocal size = 1}），
     * 关面板重开走本地列表（不过本过滤）能显示，于是表现为「面板开着时云端图片不实时显示，
     * 关掉重开才出现」——正好是用户 2026-10-26 反馈的症状（文本条目 time 键每次复制都变，
     * 天然不受影响，故只有图片中招）。修法：屏蔽值记「被清空条目的 time」，
     * 仅当补回条目的 time <= 该值时才拦（= 云端存储里残留的旧副本，v4.13 要防的复活）；
     * 新一次复制的推送 time 会刷新（变大）→ 放行，并解除该键的屏蔽。
     */
    private val sWipedKeys = java.util.LinkedHashMap<String, Long>()

    /**
     * v4.17：坏缩略图判定结果缓存（键=缩略图 base64 串），避免列表滚动时反复 base64 解码。
     * 上限 200 条，超出清空重来。
     */
    private val sBadThumbCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()



    /**
     * 本次面板打开会话是否已见过非空云端数据。
     *
     * <p>重启后 com.miui.phrase 进程/数据未就绪，第三方输入法进程里首帧
     * getCrossDeviceClipboardData 返回空列表，而 /across observer 只在数据变更时回调
     * （provider 首次加载不算变更）→ 云端条目再也合不进来，表现为「重启后云同步假
     * 失效」：需切到默认输入法打开面板（provider 就此就绪）再切回才恢复
     * （用户 2026-10-25 反馈）。重试窗口据此判断「还没拿到，继续试」。
     */
    @Volatile
    private var sCloudSeen = false

    /** 面板类（动态 dex）的两个 hook 是否已装：进程内只装一次。 */
    private var panelClassHooksInited = false

    /**
     * 显示层修复（v4）入口：装在所有被 hook 的进程（含输入法进程），面板在哪出现就在哪生效。
     * 进程内只装一次。
     */
    @JvmStatic
    fun initPanelMerge(param: PackageLoadedParam) {
        if (panelMergeInited) return
        panelMergeInited = true
        try {
            hookPanelMergeViaPopup(param.defaultClassLoader)
        } catch (t: Throwable) {
            log("FAIL: initPanelMerge - $t")
        }
    }

    /** 用框架 PopupWindow.showAtLocation 钩住面板「显示」事件，按实例类名识别我们的面板。 */
    private fun hookPanelMergeViaPopup(cl: ClassLoader) {
        try {
            val pwCls = Reflect.findClass(POPUP_WINDOW, cl)
            val showAtLocation: Method = try {
                pwCls.getDeclaredMethod(
                    "showAtLocation",
                    View::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            } catch (t: Throwable) {
                log("FAIL: resolve PopupWindow.showAtLocation - $t")
                return
            }
            showAtLocation.isAccessible = true
            XposedInit.hook(showAtLocation) { chain ->
                val self = chain.thisObject
                // v2.0.5：每次面板显示都先回到「全部」分组（用户要求：关闭面板再打开
                // 默认展示全部分组）。必须排在 proceed 之前——面板显示后会立刻灌列表，
                // 此时分组已是「全部」，快照/可见下标一次算对，不会先闪一下旧分组。
                if (isOurPanel(self) && ModulePrefs.isClipboardGroupEnabled()) {
                    try {
                        resetGroupToAll((self as? PopupWindow)?.contentView?.context)
                    } catch (t: Throwable) {
                        log("reset group on show failed - $t")
                    }
                }
                chain.proceed()
                if (isOurPanel(self)) {
                    scheduleMerge(self)
                }
            }
            log("OK: hooked framework PopupWindow.showAtLocation")
        } catch (t: Throwable) {
            log("FAIL: hook panel-merge via PopupWindow - $t")
        }
    }

    /** 实例是不是我们的剪贴板面板（按类名识别，无需按名解析面板类）。 */
    private fun isOurPanel(o: Any?): Boolean {
        return o != null && o.javaClass.name == PHRASE_POPUP
    }

    /** 每个面板实例只合并一次：立即一次 + 600ms 后再一次（覆盖本地列表异步填充时序）。 */
    private fun scheduleMerge(self: Any) {
        if (sMerged.containsKey(self)) return
        sMerged[self] = true
        // 每次打开都是新会话：重置「已见到云端数据」标志，供重试窗口判断。
        sCloudSeen = false
        // 面板打开即注册屏蔽表推送接收器 + 向 system_server 拉一次（赶在 setClipboardList 灌列表前就绪）。
        ensureDeletedKeysLoaded()
        // v4.7：sSeenCloud **不清空**——旧图关面板再开后已不是最新推送，靠本缓存补回。
        // 面板类（动态 dex）此刻已加载：装上云端缓存 + setClipboardList 补回两个 hook，
        // 保证随后 $2$1 灌本地列表时云端条目不被整表替换冲掉（打开即出的关键）。
        ensurePanelClassHooks(self.javaClass.classLoader)
        // v2.0.5：分组筛选 UI 注入（面板 PopupWindow 实例即 self）
        // 受「开启剪贴板分组功能」开关控制：关闭时不注入芯片、不加载分类选择，行为与旧版一致。
        val grpEnabled = ModulePrefs.isClipboardGroupEnabled()
        log("group gate = $grpEnabled (panel ${self.javaClass.name})")
        if (grpEnabled) {
            sPopupRef = java.lang.ref.WeakReference(self)
            // v2.0.5：不再恢复上次分组——每次打开都从「全部」开始
            // （主要重置在 showAtLocation 钩子内先于列表灌入执行，这里再兜一次保险）
            resetGroupToAll((self as? PopupWindow)?.contentView?.context)
            mainHandler.post {
                try {
                    injectGroupChip(self)
                    log("group chip inject posted & ran")
                } catch (t: Throwable) {
                    log("group chip inject failed - $t")
                }
            }
        }
        invokeMerge(self)
        mainHandler.postDelayed({
            try {
                invokeMerge(self)
            } catch (_: Throwable) {
            }
            // 二次合并后视图层级可能已被重建（芯片若在旧视图树上会被丢弃），重新注入一次。
            if (grpEnabled) {
                try {
                    injectGroupChip(self)
                } catch (t: Throwable) {
                    log("group chip re-inject failed - $t")
                }
            }
        }, MERGE_DELAY_MS)
        startCloudRetry(self)
        log("force setRemoteDataToView on panel open")
    }

    /**
     * 云端就绪重试窗口（v4.2）：开机后 phrase provider 异步就绪，首帧合并常拿到空列表，
     * 而 /across observer 不会因 provider 首次加载触发——「重启后云同步假失效」
     * （用户 2026-10-25 反馈）正源于此。每 1s 调一次 setRemoteDataToView()（其内部经
     * mSubHandler 重新查询 provider，不卡 UI 线程），拿到非空云端数据即停；
     * 面板已消失或超上限也停（sCloudCache 仍在，下次打开走 setClipboardList 补回）。
     */
    private fun startCloudRetry(self: Any) {
        val attempt = object : Runnable {
            var tries = 0

            override fun run() {
                if (sCloudSeen || tries >= CLOUD_RETRY_MAX) {
                    return
                }
                tries++
                if (!isPanelAlive(self)) {
                    // 面板已关：缓存里若有数据，下次打开由 setClipboardList 补回，无需再试。
                    return
                }
                try {
                    invokeMerge(self)
                    if (sCloudSeen) {
                        log("cloud retry got data after $tries tries")
                        return
                    }
                } catch (_: Throwable) {
                    // 单次失败不致命，下一拍继续
                }
                if (tries < CLOUD_RETRY_MAX) {
                    mainHandler.postDelayed(this, CLOUD_RETRY_MS)
                } else {
                    log("cloud retry exhausted, cloud data still empty")
                }
            }
        }
        mainHandler.postDelayed(attempt, CLOUD_RETRY_MS)
    }

    /** 面板是否还在显示（PopupWindow.isShowing 安全反射；判不了就当活着，靠超时收尾）。 */
    private fun isPanelAlive(self: Any): Boolean {
        return try {
            self.javaClass.getMethod("isShowing").invoke(self) as? Boolean ?: true
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 面板类 hook（动态 dex，进程内只装一次）：
     *  1) getCrossDeviceClipboardData：缓存云端条目；数量上限解锁开启时返回空列表
     *     短路 phrase 的合并修剪（见 v4.5）；
     *  2) adapter.setClipboardList：本地列表灌入时把缺失的云端条目补回头部（云端在顶、幂等），
     *     并记录 adapter 实例供增量合入用。
     */
    private fun ensurePanelClassHooks(cl: ClassLoader) {
        if (panelClassHooksInited) return
        panelClassHooksInited = true
        try {
            val mgr = Reflect.findClassIfExists(MIUI_CLIPBOARD_MANAGER, cl)
            if (mgr != null) {
                for (m in mgr.methods) {
                    if (m.name == "getCrossDeviceClipboardData" && m.parameterTypes.size == 1) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            val r = chain.proceed()
                            if (r is List<*>) {
                                if (r.isNotEmpty()) {
                                    // 拿到过非空云端数据：重试窗口可以收了
                                    sCloudSeen = true
                                    // v4.7：图片此刻还是「最新推送」就存进 sSeenCloud——
                                    // 之后它不再是最新时（先图后文），靠这份缓存补回，图片才不丢。
                                    recordSeenCloud(r)
                                }
                                if (ModulePrefs.isUnlimitCountEnabled()) {
                                    // v4.5：数量上限解锁开启——全量缓存（不裁）后向调用方
                                    // （全 dex 唯一的调用方就是 lambda$setRemoteDataToView$9）
                                    // 返回空列表，令其 isEmpty 判断直接早退：既绕开「总量
                                    // 压到 20」的修剪循环（解锁后本地 20+ 条不再被砍），
                                    // 也绕开 remove(-1) 崩溃。显示改由 setClipboardList 的
                                    // 全量本地 + 下面的增量合入维护。
                                    sCloudCache = ArrayList<Any?>(r)
                                    log("cloud cache updated: ${r.size} rows"
                                            + " (uncapped, count-unlock on)")
                                    mergeCloudIntoDisplay()
                                    return@hook ArrayList<Any?>()
                                }
                                if (r.size > MAX_CLIP_LIST) {
                                    // 裁到最新 20 条（列表新在前）：见 MAX_CLIP_LIST 说明——
                                    // 不裁的话 phrase 合并修剪循环 remove(-1) 崩输入法进程。
                                    val capped = ArrayList<Any?>(r.subList(0, MAX_CLIP_LIST))
                                    sCloudCache = capped
                                    log("cloud rows ${r.size} > $MAX_CLIP_LIST,"
                                            + " capped to newest $MAX_CLIP_LIST")
                                    return@hook capped
                                }
                                sCloudCache = ArrayList(r)
                                log("cloud cache updated: ${r.size} rows")
                            }
                            r
                        }
                        log("OK: hooked getCrossDeviceClipboardData")
                        break
                    }
                }
                // v4.7：另 hook getClipboardData，捕获本地库查询结果里的图片条目作备用来源
                // （覆盖「旧图已落本地库」的场景；不改返回值，只读）。
                for (m in mgr.methods) {
                    if (m.name == "getClipboardData" && m.parameterTypes.size == 1) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            val r = chain.proceed()
                            // 只读捕获：本地库查询里的图片条目汇入 sSeenCloud，作为
                            // 关面板再开被整表替换冲掉时的补回来源（不改返回值）。
                            if (r is List<*>) {
                                recordSeenCloud(r)
                            }
                            r
                        }
                        log("OK: hooked getClipboardData (image capture)")
                        break
                    }
                }
                // v4.9：hook buildRecyclerViewDisplayList，纠正「图文条目被算成纯文本」的
                // 类型误判（见 fixImageType）。
                for (m in mgr.declaredMethods) {
                    if (m.name == "buildRecyclerViewDisplayList" && m.parameterTypes.size == 1) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            val r = chain.proceed()
                            // 纠正「图文条目被算成纯文本」的类型误判：content 含图片 item
                            // 却落成纯文本类型（10/2/3）的条目改为图文(12)/纯图(11)，
                            // 否则图片行永远不出现。仅改显示列表副本类型，不影响落库。
                            if (r is List<*>) fixImageType(r)
                            // v2.0.5：从「全量构建产物」捕获 master。buildRecyclerViewDisplayList 是
                            // 由本地库构建、再流入 setClipboardList 的全量列表（我们刻意不在本 hook 过滤，
                            // 否则会把 master 误存成已过滤列表）。分组激活时 phrase 重开面板可能直接复用
                            // 已过滤的显示列表、不再全量查库，导致 setClipboardList 只拿到分组列表、
                            // master 永远收不到全量（进程重启 + 上次分组被持久化时首开即如此）。
                            // 故这里直接以全量产物并集 master，保证切到其它分组时其它内容不丢。
                            if (r is List<*> && ModulePrefs.isClipboardGroupEnabled()) {
                                @Suppress("UNCHECKED_CAST")
                                val full = ArrayList(r as List<Any?>)
                                sMasterList = unionMaster(sMasterList, full)
                            }
                            r
                        }
                        log("OK: hooked buildRecyclerViewDisplayList (type fix)")
                        break
                    }
                }
            }
            val ad = Reflect.findClassIfExists(CLIP_ADAPTER, cl)
            if (ad != null) {
                for (m in ad.methods) {
                    if (m.name == "setClipboardList" && m.parameterTypes.size == 1
                        && java.util.List::class.java.isAssignableFrom(m.parameterTypes[0])
                    ) {
                        m.isAccessible = true
                        sSetClipboardListMethod = m
                        XposedInit.hook(m) { chain ->
                            // 记录 adapter 实例（v4.5 增量合入用；弱引用不碍回收）
                            sAdapterRef = java.lang.ref.WeakReference(chain.thisObject)
                            var display: Any? = null
                            try {
                                val arg = chain.getArg(0)
                                // 用 MutableList 而非 ArrayList：phrase 各版本灌入的列表
                                // 实现类不完全一致（Arrays$ArrayList / 包装 list 均有可能），
                                // 只要可写就补，不可写静默跳过（下同）。
                                if (arg is MutableList<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val phraseList = arg as MutableList<Any?>
                                    // 记下显示列表里出现过的图片条目（下次被 strip 整表替换冲掉时补回）。
                                    recordSeenCloud(phraseList)
                                    // 构造「完整展示快照」（含云端补回、剔除已删），但【不按分组裁短】——
                                    // 分组过滤只发生在适配器展示层（getItemCount/getItem），phrase 始终
                                    // 持有完整列表，避免「按分组 retainAll 后的短列表被写回存储 →
                                    // 关面板重开丢内容」（v2.0.5 分组丢内容根因）。
                                    val d = ArrayList<Any?>(phraseList)
                                    val cache = mergedCloudCache()
                                    // 一次性清空路径：跳过云端缓存补回，快照严格等于 keep，避免与 mAll 分叉。
                                    if (cache.isNotEmpty() && !sSuppressCloudPrepend) {
                                        prependMissingCloud(d, cache)
                                    }
                                    // 已删条目在展示快照层剔除（不碰 phrase 底层列表）。
                                    dropBlockedEntries(d)
                                    if (ModulePrefs.isClipboardGroupEnabled()) {
                                        hookGroupAdapterMethods(chain.thisObject)
                                        synchronized(sGroupFullSnapshot) {
                                            sGroupFullSnapshot.clear()
                                            sGroupFullSnapshot.addAll(d)
                                            recomputeGroupVisible()
                                        }
                                    }
                                    display = d
                                }
                            } catch (t: Throwable) {
                                log("prepend error - $t")
                            }
                            if (display != null) chain.proceed(arrayOf<Any?>(display)) else chain.proceed()
                        }
                        log("OK: hooked adapter setClipboardList")
                        break
                    }
                }
                // v4.19/v4.21：单条删除记账改由「真实被删模型」钩子承担
                // （deleteClipboardListItemAndTrack / deleteSavedFile，直接拿到 ClipboardContentModel，
                //  见下方 v4.21 段）。本适配器方法只能拿到位置 pos，而分组态下
                //  getAdapterList() 返回的是【全量】列表、传入的 pos 却是【过滤后】显示位置，
                //  索引错位会误标其它条目进屏蔽表（实测：删图片却把文本条目标进 blocklist，
                //  持久化后跨会话持续「吞掉」其它内容）。故这里只透传、绝不按位置记账。
                for (m in ad.methods) {
                    if (m.name == "removeClipboardListItem" && m.parameterTypes.size == 1
                        && (m.parameterTypes[0] == Int::class.java
                                || m.parameterTypes[0] == Integer::class.java)
                    ) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            // 仅保留诊断，不做任何记账；真正记账在 deleteClipboardListItemAndTrack。
                            chain.proceed()
                        }
                        log("OK: hooked adapter removeClipboardListItem (pass-through, no mark)")
                        break
                    }
                }
                // v4.21：单条删除的<b>真正入口</b>——反编译实证
                // InputMethodClipboardPhrasePopupView.deleteClipboardListItemAndTrack
                // (ClipboardContentModel, int) 直接带着被删的模型对象（另有 deleteSavedFile
                // 清文件）。比适配器按位置取条目更直接、更可靠（不依赖 getAdapterList 索引）。
                // 在删除执行前把该条目记入屏蔽表。
                val pv = Reflect.findClassIfExists(CLIP_POPUP_VIEW, cl)
                if (pv == null) {
                    // 诊断：类都没找到就得换定位方式（类名随 phrase 版本可能变）
                } else {
                    var hooked = false
                    for (m in pv.declaredMethods) {
                        if (m.name == "deleteClipboardListItemAndTrack" && m.parameterTypes.size == 2
                            && (m.parameterTypes[1] == Int::class.java
                                    || m.parameterTypes[1] == Integer::class.java)
                        ) {
                            m.isAccessible = true
                            XposedInit.hook(m) { chain ->
                                try {
                                    val model = chain.getArg(0)
                                    val pos = chain.getArg(1) as? Int ?: 0
                                    // v2.0.5：分组视图下「单条删除」已重新启用，但必须走位置映射——
                                    // phrase 的 ViewHolder 给的是「显示位置」p，其原生删除按 p 在完整列表里取模型
                                    // （mClipboardList.get(p)），分组态下显示位置≠完整下标 → 会删错条目/删到别的分组。
                                    // 下方 sActiveCat!=CAT_ALL 分支把 p 经 sGroupVisibleIdx 映射回完整下标、用真实模型调原生删除，
                                    // 并用「删除前后差集」兜底纠错；删除后重建快照 + 本地库持久化，杜绝复活/其它分组丢失。
                                    if (ModulePrefs.isClipboardGroupEnabled()) {
                                        val adapter = chain.thisObject
                                        // 删除前留存「适配器实际列表」的条目快照：删除后再比对，
                                        // 谁真的消失了就以事实为准记账。pos→下标映射可能失准，
                                        // 但「从列表里消失的那一条」不会骗人（见 v2.0.5 复活根因）。
                                        val before = ArrayList<Any?>()
                                        adapterDataList(adapter)?.let { for (e in it) before.add(e) }
                                        var marked: Any? = null
                                        var modArgs: Array<Any?>? = null
                                        if (sActiveCat != CAT_ALL) {
                                            // phrase 点击→删除的链路：ViewHolder 拿到的是「显示位置」p，phrase 用它直接
                                            // mClipboardList.get(p) 取模型。但我们的适配器在展示层按分组过滤（显示≠完整
                                            // 列表），get(p) 取到的是过滤前的条目（多为图片/置顶项）→ 删错。必须把「显示
                                            // 位置」经 sGroupVisibleIdx 映射回完整列表下标，并用该下标对应的真实模型。
                                            // 防御：删除前用当前快照+当前分组重算一次，避免 sGroupVisibleIdx 因时序变陈旧。
                                            synchronized(sGroupFullSnapshot) { recomputeGroupVisible() }
                                            val correctIdx = if (pos in 0 until sGroupVisibleIdx.size) sGroupVisibleIdx[pos] else pos
                                            val correctModel = if (correctIdx in 0 until sGroupFullSnapshot.size) sGroupFullSnapshot[correctIdx] else model
                                            marked = correctModel
                                            modArgs = arrayOf<Any?>(correctModel, correctIdx)
                                        } else {
                                            // 全部分组：显示位置==完整下标，直接用 phrase 给的模型
                                            marked = model
                                        }
                                        // 先按映射记账：删除执行期间不能被云端补回路径捞回来
                                        markDeletedEntry(marked)
                                        if (modArgs != null) chain.proceed(modArgs) else chain.proceed()
                                        // ===== 删除后校准（顺序很关键：先纠正记账，再重建快照）=====
                                        // 1) 用「删除前后列表差集」确认真正被删的那条并补记账，
                                        //    顺带纠正可能存在的误标（误标会把无辜条目永久吞掉）。
                                        //    必须排在重建快照之前：快照重建会按屏蔽表剔条目，若记账还是错的，
                                        //    就会把没被删的条目从快照里剔掉 → 快照比真实列表少一条 →
                                        //    展示层换位整体错位（实测「删文字后链接内容跑进文字分组」）。
                                        val after = adapterDataList(adapter)
                                        var purgeKey: String? = entryKey(marked)
                                        if (before.isNotEmpty() && after != null) {
                                            val afterKeys = HashSet<String?>()
                                            for (e in after) afterKeys.add(entryKey(e))
                                            val vanished = before.filter { e ->
                                                val k = entryKey(e)
                                                k != null && !afterKeys.contains(k)
                                            }
                                            if (vanished.size == 1) {
                                                val truth = vanished[0]
                                                markDeletedEntry(truth)
                                                val tk = entryKey(truth)
                                                purgeKey = tk
                                                val mk = entryKey(marked)
                                                if (mk != null && mk != tk && afterKeys.contains(mk)) {
                                                    // 刚按映射标的条目其实还在列表里 → 误标，撤销，
                                                    // 否则这条内容此后会被当成「已删」持续吞掉。
                                                    sWipedKeys.remove(mk)
                                                    persistDeletedKeys()
                                                }
                                            } else {
                                            }
                                        }
                                        // 2) 再按适配器真实列表重建快照（严格等长同序，见函数注释）；
                                        //    顺带把本次确认被删的条目从真实列表里清掉 —— phrase 有时没能
                                        //    删到（映射偏差/对象不同实例），残留下来就会在重开面板时复活。
                                        resyncGroupSnapshotFromAdapter(
                                            adapter,
                                            if (purgeKey == null) null else setOf(purgeKey)
                                        )
                                        notifyAdapter()
                                        // 本地库持久化：phrase 原生删除往往只改内存列表、不落本地库，
                                        // 下次查库（切分组/周期同步/关面板重开）会把已删条目从库里捞回 → 复活。
                                        // 这里把「当前列表去掉所有已屏蔽条目」覆盖式写回本地库（与一键清空同机制），
                                        // 让删除真正落库、无法复活。mData 此时已是删后的干净列表。
                                        try {
                                            val ctxDel = objField(chain.thisObject, "mContext") as? Context
                                            val mgrDel = Reflect.findClassIfExists(MIUI_CLIPBOARD_MANAGER, chain.thisObject.javaClass.classLoader)
                                            if (mgrDel != null && ctxDel != null) {
                                                val cur = adapterDataList(adapter)
                                                if (cur != null) {
                                                    val cleaned = ArrayList<Any?>()
                                                    for (e in cur) {
                                                        val k = entryKey(e)
                                                        if (k != null && sWipedKeys.containsKey(k)) continue
                                                        cleaned.add(e)
                                                    }
                                                    for (sm in mgrDel.methods) {
                                                        if (sm.name == "setClipboardModelList" && sm.parameterTypes.size == 2) {
                                                            sm.isAccessible = true
                                                            sm.invoke(null, ctxDel, cleaned)
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (t: Throwable) { log("delete persist failed - $t") }
                                    } else {
                                        markDeletedEntry(model)
                                        removeFromGroupSnapshot(model)
                                        chain.proceed()
                                    }
                                } catch (t: Throwable) {
                                    log("delete track capture failed - $t")
                                    chain.proceed()
                                }
                            }
                            hooked = true
                            log("OK: hooked popup deleteClipboardListItemAndTrack")
                            break
                        }
                    }
                    if (!hooked) {
                    }
                    // 补充捕获点：deleteSavedFile(model) 同样带模型对象（图片条目删文件也走它）
                    for (m in pv.declaredMethods) {
                        if (m.name == "deleteSavedFile" && m.parameterTypes.size == 1) {
                            m.isAccessible = true
                            XposedInit.hook(m) { chain ->
                                try {
                                    markDeletedEntry(chain.getArg(0))
                                } catch (t: Throwable) {
                                    log("deleteSavedFile capture failed - $t")
                                }
                                chain.proceed()
                            }
                            log("OK: hooked popup deleteSavedFile")
                            break
                        }
                    }
                    // v2.0.5：一键清空改为「只清当前分组」。原生确认回调（lambda$clearClipBoardData$* 无参的那个，
                    // 即确认按钮真正执行清空的方法）会 mAll/mCur.clear + adapter.clear +
                    // MiuiClipboardManager.clearClipBoardData 本地库全清，不满足「分组内清分组」需求。
                    // 改为：先让原生全清执行（本地库已空），再把「非当前分组」的条目写回本地库与内存，
                    // 并对被删条目记账屏蔽（防云端/缓存补回复活）。
                    for (m in pv.declaredMethods) {
                        if (m.name.startsWith("lambda\$clearClipBoardData\$") && m.parameterTypes.isEmpty()) {
                            m.isAccessible = true
                            XposedInit.hook(m) { chain ->
                                try {
                                    val self = chain.thisObject
                                    if (ModulePrefs.isClipboardGroupEnabled() && sActiveCat != CAT_ALL) {
                                        val cat = sActiveCat
                                        val mAll = objField(self, "mAllClipboardList") as? MutableList<Any?>
                                        // 反射取不到内存列表：降级为原生全清（与原行为一致，避免误清本地库）
                                        if (mAll == null) {
                                            chain.proceed()
                                            return@hook null
                                        }
                                        val mCur = objField(self, "mCurrentImeClipboardList") as? MutableList<Any?>
                                        // 拦截原生全清：不执行 lambda$clearClipBoardData$14。原生会
                                        // MiuiClipboardManager.clearClipBoardData 整体清空本地库，导致切到其它
                                        // 分组时重新加载拿到空库、内容消失，需重开面板才从云端缓存补回
                                        // （即「切到其它分组内容没了、重开才恢复」的根因）。改为只删当前分组：
                                        // 本地库从不被整体清空，只把『非当前分组』覆盖式写回。
                                        // v2.0.5 修复「清空某分组导致其它分组/全部真实丢失」：
                                        // keep 必须以【本地库全量】为唯一权威源，绝不依赖任何 phrase 内存列表
                                        // （mAllClipboardList、sGroupFullSnapshot 在分组视图下都可能已被数据层
                                        //  按当前分组过滤，导致 keep 漏算其它分组 → setClipboardModelList
                                        //  覆盖写库把其它分组/全部抹掉，需重开面板靠云端缓存补回）。
                                        // 本地库是唯一全量真相：getClipboardData(ctx) 返回所有分组条目，
                                        // keep = 全量去掉当前分组（及已屏蔽条目），覆盖写回 → 其它分组原样保留。
                                        val dbMgr = Reflect.findClassIfExists(MIUI_CLIPBOARD_MANAGER, self.javaClass.classLoader)
                                        val dbCtx = objField(self, "mContext") as? Context
                                        var dbOk = false
                                        val dbList: List<Any?> = if (dbMgr != null && dbCtx != null) {
                                            try {
                                                val raw = Reflect.callStaticMethod(dbMgr, "getClipboardData", dbCtx)
                                                if (raw is List<*>) { dbOk = true; raw as List<Any?> } else emptyList()
                                            } catch (t: Throwable) {
                                                log("partial clear: getClipboardData failed - $t"); emptyList()
                                            }
                                        } else emptyList()
                                        if (!dbOk) {
                                            // 读不到本地库全量就不敢覆盖写库（会误清），降级为原生全清（与无模块一致）。
                                            chain.proceed()
                                            return@hook null
                                        }
                                        val removed = ArrayList<Any?>()
                                        val keep = ArrayList<Any?>()
                                        for (e in dbList) {
                                            if (isEntryBlocked(e)) continue
                                            if (classifyItem(e) == cat) removed.add(e) else keep.add(e)
                                        }
                                        // 云端分类条目不随本地库，单独屏蔽，使清空后当前分组彻底为空（防云端缓存补回）。
                                        try {
                                            for (c in mergedCloudCache()) {
                                                if (classifyItem(c) == cat) markDeletedEntry(c)
                                            }
                                        } catch (_: Throwable) {}
                                        // 1) 本地库：覆盖式写回非当前分组内容（等价于删除当前分组，其它分组不动）
                                        try {
                                            val ctx = objField(self, "mContext") as? Context
                                            val mgr = Reflect.findClassIfExists(MIUI_CLIPBOARD_MANAGER, self.javaClass.classLoader)
                                            if (mgr != null && ctx != null) {
                                                for (sm in mgr.methods) {
                                                    if (sm.name == "setClipboardModelList" && sm.parameterTypes.size == 2) {
                                                        sm.isAccessible = true
                                                        sm.invoke(null, ctx, ArrayList<Any?>(keep))
                                                        break
                                                    }
                                                }
                                            }
                                        } catch (t: Throwable) { log("partial clear: restore local failed - $t") }
                                        // 2) 内存列表：mAll 保留 keep；停留当前分组 → 该组已空，mCur 清空
                                        mAll.clear(); mAll.addAll(keep)
                                        mCur?.let { mc -> mc.clear() }
                                        // 3) 记账屏蔽被删条目，防云端/缓存补回复活
                                        ensureDeletedKeysLoaded()
                                        for (e in removed) markDeletedEntry(e)
                                        // 4) 重建展示快照 + 刷新：借 adapter.setClipboardList 重灌 keep，
                                        //    当前分组过滤后为空 → 停留在已清空的分组。
                                        //    期间抑制云端缓存补回（sSuppressCloudPrepend），使重建的快照
                                        //    严格等于 keep（其它分组内容），杜绝快照与内存主列表分叉。
                                        val adapter = sAdapterRef?.get()
                                        val setM = sSetClipboardListMethod
                                        if (adapter != null && setM != null) {
                                            try {
                                                sSuppressCloudPrepend = true
                                                setM.invoke(adapter, ArrayList<Any?>(keep))
                                            } catch (t: Throwable) { log("partial clear: rebind failed - $t") }
                                            finally { sSuppressCloudPrepend = false }
                                        }
                                        // 5) 不再强制同步 phrase 空视图：原生 $14 的空视图切换是【全局 NoItemsView】，
                                        //    强制调用会把整面板切到空视图，而 switchGroupCategory 为规避「手动刷新触发面板
                                        //    重建→输入法闪退」故意不重算空视图，于是切到其它分组也关不掉它——
                                        //    表现为「清空后所有分组空白、需重开才恢复」。当前分组已空由 RecyclerView
                                        //    0 行呈现即可，其它分组切过去 getItemCount 返回正确数量即显示内容，
                                        //    与重开面板行为一致，且不会触发闪退。
                                        return@hook null
                                    }
                                } catch (t: Throwable) {
                                    log("partial clear hook error - $t")
                                }
                                chain.proceed()
                            }
                            log("OK: hooked popup clearClipBoardData confirm (partial-clear)")
                            break
                        }
                    }
                }
                // v4.9【本机图片渲染修复】loadVideoOrImage 的 across 分支只认 thumbImage，
                // 本机条目（通用剪贴板通道 across=1、只有 fileUri、无 thumb）会落到
                // loadFiledImage 占位图/不渲染。对「无 thumb 但有 fileUri/canReadFileUri」的
                // 条目把 across 实参改 false，走本地分支——其内部 canReadFileUri 缺省回退
                // fileUri，Glide 直接加载本机图片。
                // v4.17【坏缩略图修复】across 分支的 thumbImage 解码失败或宽高比畸形时，
                // 塞进方形缩略图区域会显全黑/全白块。若本地原图（fileUri/canReadFileUri）
                // 在当前输入法进程可读，则同样把 across 改 false 改走本地原图分支，恢复真图。
                for (m in ad.declaredMethods) {
                    if (m.name == "loadVideoOrImage" && m.parameterTypes.size == 4) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            var vh: Any? = null
                            try {
                                val args = chain.getArgs()
                                if (args.isNotEmpty()) vh = args[0]
                                // v4.18【主因】：必须在 proceed() 之前清掉 imgItem 的主题 tint。
                                // 关闭面板再打开时同一图命中 Glide 内存缓存会同步 setImageDrawable，
                                // 若 proceed() 之后才清，tint 在设图瞬间已生效 → 纯色块。
                                clearImageTint(vh)
                                if (args.size == 4) {
                                    val item = args[1]
                                    val across = args[2] as? Boolean ?: false
                                    if (across && item != null) {
                                        val thumb = stringField(item, "getThumbImage")
                                        val file = stringField(item, "getFileUri").orEmpty()
                                        val canR = stringField(item, "getCanReadFileUri").orEmpty()
                                        val probeUri = if (canR.isNotEmpty()) canR else file
                                        val hasLocalRef = probeUri.isNotEmpty()
                                        if (hasLocalRef) {
                                            val forceLocal = if (thumb.isNullOrEmpty()) {
                                                // 无缩略图：沿用 v4.9 既有权限无关强制本地（已验证可用）
                                                true
                                            } else {
                                                // 缩略图存在但为坏图，且本地原图在本进程可读 → 改走本地原图分支
                                                isBadThumbnail(thumb) && ClipboardPinHook.uriReadable(probeUri)
                                            }
                                            if (forceLocal) {
                                                // 走本地渲染分支（其内部 canReadFileUri 缺省回退
                                                // fileUri，直接加载本机图片）。
                                                val mod = args.toTypedArray()
                                                mod[2] = false
                                                val r = chain.proceed(mod)
                                                // v4.18【次要】：覆盖 phrase 在 proceed 内设的夜间白卡底
                                                guardImageCardBgInNight(vh)
                                                return@hook r
                                            }
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                log("loadVideoOrImage hook error - $t")
                            }
                            val r = chain.proceed()
                            // v4.18【次要】：覆盖 phrase 在 proceed 内设的夜间白卡底
                            guardImageCardBgInNight(vh)
                            return@hook r
                        }
                        log("OK: hooked adapter loadVideoOrImage (local img render)")
                    }
                }
                // v4.9【本机图片渲染修复】getImageItemMode：across 条目只取带 thumbImage 的 item，
                // 本机图片 item（只有 fileUri）会被跳过、返回末尾无关 item（如文本），
                // 导致 loadVideoOrImage 拿错 item 渲染空白。原结果三 URI 全空时，
                // 全量扫 content 换成第一个带文件引用的 item。
                for (m in ad.declaredMethods) {
                    if (m.name == "getImageItemMode" && m.parameterTypes.size == 1) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            val r = chain.proceed()
                            try {
                                val model = chain.getArg(0)
                                val refsEmpty = r == null || (
                                    stringField(r, "getThumbImage").isNullOrEmpty()
                                        && stringField(r, "getFileUri").isNullOrEmpty()
                                        && stringField(r, "getCanReadFileUri").isNullOrEmpty()
                                )
                                if (refsEmpty && model != null) {
                                    val alt = findRenderableItem(model)
                                    if (alt != null) {
                                        return@hook alt
                                    }
                                }
                            } catch (t: Throwable) {
                                log("getImageItemMode hook error - $t")
                            }
                            r
                        }
                        log("OK: hooked adapter getImageItemMode (file-ref item)")
                    }
                }
            }
        } catch (t: Throwable) {
            log("FAIL: ensurePanelClassHooks - $t")
        }
    }

    /**
     * v4.5：数量上限解锁开启时，把缓存里的云端条目增量合入面板当前显示列表（主线程执行）。
     *
     * <p>调用点：getCrossDeviceClipboardData hook——其调用方 lambda$9 已被返回空列表短路，
     * 不会自行合并/修剪。两种场景都需要这里兜底：① 开面板首帧 $2$1 灌全量本地时缓存还没
     * 就绪（重启后 provider 异步就绪），云端条目靠这里补；② 面板开着期间 PC 来了新条目
     * （/across observer 路径），$2$1 不会重跑，只能直接操作 adapter 列表再刷新。
     * 与 setClipboardList hook 内的 prepend 走同一 time 去重逻辑，幂等。
     */
    private fun mergeCloudIntoDisplay() {
        val cache = mergedCloudCache()
        if (cache.isEmpty()) return
        val adapter = sAdapterRef?.get()
        if (adapter == null) {
            log("merge skipped: adapter not captured")
            fallbackReloadLocal()
            return
        }
        mainHandler.post {
            try {
                @Suppress("UNCHECKED_CAST")
                val list = adapter.javaClass.getMethod("getAdapterList").invoke(adapter)
                        as? MutableList<Any?>
                if (list == null) {
                    log("merge skipped: adapter list unavailable")
                    fallbackReloadLocal()
                    return@post
                }
                    if (prependMissingCloud(list, cache)) {
                        // 已删条目在展示层剔除（不按分组裁短 phrase 底层列表）。
                        dropBlockedEntries(list)
                        // v2.0.5 修复：云端条目补进后【不】再按分组 retainAll 裁短 phrase 列表
                        // （那会把短列表写回存储 → 关面板重开丢内容）。分组过滤改由适配器展示层完成，
                        // 这里仅同步完整快照并重绘。
                        if (ModulePrefs.isClipboardGroupEnabled()) {
                            synchronized(sGroupFullSnapshot) {
                                sGroupFullSnapshot.clear()
                                sGroupFullSnapshot.addAll(list)
                                recomputeGroupVisible()
                            }
                        }
                        adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
                    // 空面板时 RecyclerView 被 phrase 置 GONE，仅 notify 不会切换可见性，
                    // 补调 phrase 的空视图状态机令 RecyclerView 重新 VISIBLE（v4.15）。
                    ClipboardPinHook.syncEmptyState(list)
                    log("cloud merged into live list (prepended and/or pinned re-pinned)")
                    // 实时合入后重建置顶快照（sTimeJson / sRowTimes）。
                    // 置顶手势依赖 sTimeJson[time] 取短语原始内容；本方法只改了 adapter 列表
                    // 与 sBindMap、未走 query→reorder，故 sTimeJson 仍缺云端条目——面板开着时
                    // 给云端条目置顶会因查不到原文（sTimeJson[time]==null）而提前 return，
                    // 须关面板重开（走完整重拉）才生效。这里借 refreshFromLocal 走完整 query
                    // 重拉，reorder 会把已落本地库的云端条目原文补进 sTimeJson，置顶即可成功。
                    // prependMissingCloud 幂等：二次进入返回 false，不会递归重拉；且经
                    // setClipboardList 钩子仍受 sWipedKeys 约束，不会复活已清空条目（无 v4.14 回归）。
                    ClipboardPinHook.refreshFromLocal("cloud merge: rebuild pin snapshots")
                } else if (missingRowCount(list, cache) > 0) {
                    // v4.16：缓存里有显示列表没有的条目却一条都没补进去（被清空屏蔽等），
                    // 云端推送此刻**已经落本地库**（日志实证 setClipBoardDataToLocal），
                    // 重拉一次本地列表即可让面板实时显示——与「关面板重开」走同一数据源。
                    // 本地库里没有的陈旧云端副本不会被这次重拉带回来，v4.13 的防复活不受影响。
                    log("merge: rows missing but none prepended; reload local list")
                    fallbackReloadLocal()
                }
            } catch (t: Throwable) {
                log("merge into display error - $t")
            }
        }
    }

    /**
     * v4.16：兜底重拉本地剪贴板列表刷新面板（带冷却，避免同一推送反复重拉）。
     *
     * <p>云端条目在推送到达时会被 phrase 同步落本地库（{@code setClipBoardDataToLocal}），
     * 因此「重拉本地列表」与「关面板再打开」看到的是同一份数据、渲染链路完全一致
     * （走 buildRecyclerViewDisplayList 定型），是实时显示最稳的兜底。
     */
    private fun fallbackReloadLocal() {
        val now = System.currentTimeMillis()
        if (now - sLastReloadMs < RELOAD_COOLDOWN_MS) return
        sLastReloadMs = now
        ClipboardPinHook.refreshFromLocal("cloud merge fallback")
    }

    /** 缓存里有、但显示列表里没有的条目数（被清空屏蔽的也算），用于判断要不要兜底重拉。 */
    private fun missingRowCount(list: List<Any?>, cache: List<Any?>): Int {
        val present = HashSet<String>()
        for (e in list) {
            val k = entryKey(e)
            if (k != null) present.add(k)
        }
        var n = 0
        for (c in cache) {
            val k = entryKey(c)
            if (k == null || k !in present) n++
        }
        return n
    }

    /**
     * v4.16：把要补回显示列表的云端条目类型归一，与「关面板重开」路径一致。
     *
     * <p>重开面板时条目会经 {@code buildRecyclerViewDisplayList}——type==0 的由原生
     * {@code buildClipboardModelDataType} 定型，图文误判再由 [fixImageType] 纠正；
     * 而实时合入拿的是 {@code getCrossDeviceClipboardData} 直出模型，没走这两步，
     * 类型不对时 adapter 直接落到 {@code invalid clip type} 分支，图片行渲染不出来。
     */
    private fun normalizeEntryType(e: Any?): Any? {
        if (e == null) return null
        var cur: Any = e
        val type = try {
            (Reflect.callMethod(cur, "getType") as? Number)?.toInt()
        } catch (_: Throwable) { null }
        if (type == 0) {
            cur = rebuildModelType(cur)
        }
        fixImageType(listOf(cur))
        return cur
    }

    /** 反射调 {@code MiuiClipboardManager.buildClipboardModelDataType(model)} 给未定型条目定型。 */
    private fun rebuildModelType(e: Any): Any {
        return try {
            val cls = java.lang.Class.forName(MIUI_CLIPBOARD_MANAGER, false, e.javaClass.classLoader)
            val m = cls.getDeclaredMethod("buildClipboardModelDataType", e.javaClass)
            m.isAccessible = true
            val out = m.invoke(null, e)
            if (out == null) e else out
        } catch (t: Throwable) {
            log("rebuild type failed - $t")
            e
        }
    }

    /**
     * 把缓存里缺失的云端条目补回列表头部（云端在上、保持缓存原顺序），幂等。
     *
     * <p><b>为什么不能用 contains/equals 去重</b>：{@code ClipboardContentModel.equals()}
     * 是粗粒度实现——未落库条目 id 均为默认值，任意两条目即判等，导致「本地列表明明没有
     * 云端条目，contains 却返回 true」，prepend 永远被跳过（v4.1 实测零触发，用户
     * 2026-10-25 复测确认）。改按 time 去重：云端条目的 time 不在入参列表里才算缺失。
     * 同一毫秒的复制事件才可能撞键，实际不存在；判不了 time 的条目按缺失处理（宁可多显示）。
     *
     * <p><b>v4.6：图片条目不能用 time 去重</b>——phrase 侧每次新云推送到达都会刷新图片
     * 条目的 time（DIAG 实证：同图两次查询 time 相差数百 ms），time 键对图片永久失效，
     * 同一张图每推一次插一条（用户 2026-10-25 反馈「云端图片同步过来一次性出现两条」）。
     * 图片条目改按 [entryKey] 稳定身份（deviceId + uriType + dataSize，均不含 time）
     * 判重；文本条目 time 稳定，沿用 time 键。
     *
     * <p>原地修改入参即同步了面板的 {@code mCurrentImeClipboardList} 字段——
     * {@code $2$1} 与 reload 分支传给本方法的都是该字段本身（同一对象），
     * 点击取值按位置索引两边一致，不会错位。
     */
    private fun prependMissingCloud(list: MutableList<Any?>, cache: List<Any?>): Boolean {
        // v4.20：补回前先载入持久化屏蔽表——换输入法（新进程）后本表为空，
        // 不载入就会把别的进程里已删除的云端条目又补回来
        ensureDeletedKeysLoaded()
        val present = HashSet<String>()
        for (e in list) {
            val k = entryKey(e)
            if (k != null) {
                present.add(k)
            }
        }
        var added = 0
        var blocked = 0
        if (cache.isNotEmpty()) {
        }
        for (c in cache) {
            // v4.14：精确跳过「已被一键清空」的条目（按 entryKey 屏蔽），防复活。
            // 不再按时间一刀切——正常云端/新条目（entryKey 不同）照常实时显示，
            // 修 v4.13 回归（云端条目面板开着时不显示、关面板重开才有）。
            // v4.16：只屏蔽「被清空那一代副本」——time <= 记账值才算旧副本；
            // 新一次复制的推送 time 刷新（变大）→ 放行并解除屏蔽（图片稳定键不再被永久拦）。
            val k = entryKey(c)
            if (k != null) {
                val wt = sWipedKeys[k]
                if (wt != null) {
                    if (k.startsWith("c:")) {
                        // 云端内容指纹键：记过即永久拦，云端重复推送同内容不复活
                        blocked++
                        continue
                    }
                    val t = entryTime(c) ?: 0L
                    if (t <= wt) {
                        blocked++
                        continue
                    }
                    sWipedKeys.remove(k)
                    log("wipe block lifted: $k (new copy t=$t > wiped t=$wt)")
                }
            }
            if (k == null || k !in present) {
                // v4.16：云端条目直出（未经 buildRecyclerViewDisplayList）可能未定型或
                // 类型被误判成文本，补进列表也渲染不出图片行。这里按「重开面板」那条的
                // 处理流程归一类型（type==0 走原生定型 + 修正图文误判），保证实时合入
                // 与关面板重开显示一致。
                list.add(added, normalizeEntryType(c))
                added++
                if (k != null) {
                    present.add(k)
                }
            }
        }
        var changed = added > 0
        if (added > 0) {
            log("prepend $added cloud rows into setClipboardList (list now ${list.size} rows)")
        } else if (blocked > 0) {
            log("prepend skipped: $blocked wiped cloud rows still blocked")
        }
        // v4.11：云端条目插入头部后，把置顶条目归位到 index 0，与查询层 reorder() 一致，
        // 修复「面板开启 + 有置顶时云端来数据，置顶被挤到第二位」的缺陷。
        if (ClipboardPinHook.ensurePinnedFirst(list)) {
            changed = true
        }
        return changed
    }

    /**
     * v4.21：从显示列表里剔除已被删除/清空的条目（堵住「本地库」这条复活通道）。
     *
     * <p>此前只有 [prependMissingCloud] 会查屏蔽表，而它只管「云端缓存补回」这一条路。
     * 但云端同步会**把条目写回本地库**（实证 `setAcrossDevicesData →
     * setClipBoardDataToLocal`）——于是被删的云端条目是**从本地查询结果里**回到显示列表的，
     * 根本不经过补回路径，屏蔽表完全够不着。换输入法（新进程）开面板时正是这条链路：
     * 新进程没有内存屏蔽表 → 云端同步写回本地库 → 本地查询把它查出来 → 又显示出来。
     *
     * <p>这里在<b>列表层面</b>统一过滤，本地查询与云端补回两个来源一起拦住。
     * 沿用「一次复制一代」语义：{@code time <=} 记账值 → 剔除；更新鲜的副本
     * （用户重新复制了同一内容）→ 解除屏蔽并保留。
     *
     * @return 是否真的删掉了条目（调用方据此判断列表是否变化）
     */
    private fun dropBlockedEntries(list: MutableList<Any?>): Boolean {
        ensureDeletedKeysLoaded()
        for (i in 0 until minOf(list.size, 5)) {
        }
        if (sWipedKeys.isEmpty()) {
            return false
        }
        var removed = 0
        try {
            val it = list.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val k = entryKey(e) ?: continue
                val wt = sWipedKeys[k] ?: continue
                if (k.startsWith("c:")) {
                    it.remove()
                    removed++
                    continue
                }
                val t = entryTime(e) ?: 0L
                if (t <= wt) {
                    it.remove()
                    removed++
                } else {
                    sWipedKeys.remove(k)
                    log("wipe block lifted (display): $k (t=$t > wiped t=$wt)")
                }
            }
        } catch (t: Throwable) {
            // 列表不可写（如 Arrays$ArrayList 定长）时静默跳过，不影响显示
            log("dropBlockedEntries skipped - $t")
            return false
        }
        if (removed > 0) {
            log("drop $removed deleted rows from display list")
            persistDeletedKeys()
        }
        return removed > 0
    }

    /** 取条目 time（{@code ClipboardContentModel.getTime()}，long）；反射失败返回 null。 */
    private fun entryTime(e: Any?): Long? {
        if (e == null) {
            return null
        }
        return try {
            val m = e.javaClass.getMethod("getTime")
            (m.invoke(e) as? Number)?.toLong()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 条目稳定身份键（v4.6 去重主键）。
     *
     * <p>文本条目：time 稳定（落库/查询均保留），用 time。
     * <p>图片条目：phrase 每次新云推送到达都会刷新 time（DIAG 实证），time 不可用；
     * 改从 determineContent 的 ClipDataItemModel JSON 里取不含 time 的稳定字段组合：
     * {@code img:<deviceId>:<uriType>:<dataSize>}。同一张图的多次推送/落库副本这些
     * 字段相同 → 判重合并；不同图 dataSize 或类型不同 → 不误合并。
     * 解析失败（非图片/无 determineContent）回退 time；再失败返回 null（按缺失处理）。
     */
    private fun entryKey(e: Any?): String? {
        if (e == null) {
            return null
        }
        val dc = determineContent(e)
        if (!dc.isNullOrEmpty()) {
            try {
                val arr = org.json.JSONArray(dc)
                if (arr.length() > 0) {
                    val item = arr.getJSONObject(0)
                    val uriType = item.optString("uriType")
                    if (uriType.contains("image")) {
                        val dev = stringField(e, "getDeviceId") ?: ""
                        val size = item.optLong("dataSize", -1L)
                        // 键只取不含 time 的稳定字段；uriFileName 经 DIAG 验证若含
                        // 到达时间戳则不可用，不纳入
                        return "img:$dev:$uriType:$size"
                    }
                }
            } catch (_: Throwable) {
                // 解析失败回退 time
            }
            // v4.22：文本/链接的云端条目（isAcrossDevices）会被云端以【新 time 重复推送同内容】
            // 重新合入面板。若仍用 time 作键，重推副本 time 不同 → 不在屏蔽表 → 复活
            // （实证：删文字分组后切分组复活、重开消失）。故云端文本/链接改用「内容指纹键」
            // c:<deviceId>:<contentHash>：云端重推同内容命中同一键 → 永久拦住；
            // 用户本地手动复制（isAcrossDevices=false）仍走 time 键，复制即解封（保留 v4.16 语义）。
            if (isCloudEntry(e)) {
                val fp = contentFingerprint(e)
                if (!fp.isNullOrEmpty()) {
                    val dev = stringField(e, "getDeviceId") ?: ""
                    return "c:" + dev + ":" + if (fp.length <= 256) fp else (fp.length.toString(36) + ":" + fp.hashCode().toString(36))
                }
            }
        }
        return entryTime(e)?.toString()
    }

    /**
     * 该条目是否云端/跨设备来源（isAcrossDevices）。
     * 云端条目会被以【新 time 重复推送同内容】重新合入面板，屏蔽必须按内容指纹而非 time。
     * 反射失败回退 deviceId 非空判定（云端条目 deviceId 非空，本机条目为空）。
     */
    private fun isCloudEntry(e: Any?): Boolean {
        if (e == null) return false
        return try {
            val m = e.javaClass.getMethod("isAcrossDevices")
            (m.invoke(e) as? Boolean) ?: false
        } catch (_: Throwable) {
            !(stringField(e, "getDeviceId")?.isEmpty() ?: true)
        }
    }

    /**
     * 文本/链接条目的内容指纹（不含 time）：优先取模型自身 getContent() 文本，
     * 兜底解析 determineContent 的 ClipDataItemModel JSON 取 text/uriType。
     * 用于云端条目的稳定屏蔽键——云端重推同内容时指纹一致，命中同一屏蔽键。
     */
    private fun contentFingerprint(e: Any?): String? {
        if (e == null) return null
        val raw = stringField(e, "getContent")
        if (!raw.isNullOrEmpty()) return raw
        val dc = determineContent(e) ?: return null
        return try {
            val arr = org.json.JSONArray(dc)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val txt = o.optString("text").ifEmpty { o.optString("textItemData") }
                val uri = o.optString("uriType").ifEmpty { o.optString("uriItemType") }
                sb.append(txt).append('#').append(uri).append(';')
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (_: Throwable) { null }
    }

    /** 反射取字符串字段/无参 getter；失败返回 null。 */
    private fun stringField(e: Any, name: String): String? {
        return try {
            e.javaClass.getMethod(name).invoke(e) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /** 反射取任意对象字段（含私有），失败返回 null。用于读 phrase 私有列表/Context 字段。 */
    private fun objField(self: Any, name: String): Any? {
        return try {
            val f = self.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.get(self)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * v4.17：判定一个云端缩略图（base64 WebP）是否为「坏图」——即塞进面板方形缩略图区域
     * 后会显示成全黑/全白块的情形。
     *
     * <p>坏图判定（满足任一即判坏）：
     * <ol>
     *   <li>base64 解码或整图解码失败（如 VP8X+ICCP 本机 WebP 解码器不支持 → 全黑块）；</li>
     *   <li>宽高比畸形（横条 {@code w/h > 3} 或竖条 {@code w/h < 1/3}）→ 内容被压成一条，
     *       正方形区域其余部分显成白/黑块（实测 [0]256x83、[4]256x48 即此类）；</li>
     *   <li>整图看似正常解出，但内容是一整块<b>单一颜色</b>（全黑/全白）→ 实为渲染失败
     *       的占位块（如 VP8X+ICCP 被解码成纯色），并非真实图片内容。</li>
     * </ol>
     *
     * <p>注意：仅取 bounds 不够——VP8X 扩展格式 bounds 能解出尺寸，但整图解码会返回 null，
     * 必须用<b>整图</b>解码确认是否真能解出位图。缩略图都很小（几 KB~十几 KB），开销可忽略；
     * 结果按 base64 串缓存，避免列表滚动反复解码。
     *
     * <p>单一颜色检测只做网格采样（最多 64 点），开销极低；即便误判（真实全色图），
     * 也只会改走本地原图——而原图同样是全色，视觉无差，不会造成回归。
     */
    private fun isBadThumbnail(thumb: String?): Boolean {
        if (thumb.isNullOrEmpty()) return false
        sBadThumbCache[thumb]?.let { return it }
        val bad = try {
            val raw = Base64.decode(thumb, Base64.DEFAULT)
            if (raw.isEmpty()) {
                true
            } else {
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bmp == null) {
                    // 整图解码失败：VP8X+ICCP 等扩展格式本机不支持 → 全黑块
                    true
                } else {
                    val w = bmp.width
                    val h = bmp.height
                    if (w <= 0 || h <= 0) {
                        bmp.recycle()
                        true
                    } else {
                        val ratio = w.toFloat() / h.toFloat()
                        // 畸形横条 / 竖条：方形区域其余部分显白/黑块
                        val uglyRatio = ratio > 3f || ratio < (1f / 3f)
                        val solid = !uglyRatio && isSolidColor(bmp)
                        bmp.recycle()
                        uglyRatio || solid
                    }
                }
            }
        } catch (_: Throwable) {
            // 解码异常也视为坏图
            true
        }
        if (sBadThumbCache.size > 200) {
            sBadThumbCache.clear()
        }
        sBadThumbCache[thumb] = bad
        return bad
    }

    /**
     * 网格采样判断位图是否为「近似单一颜色」（渲染失败的占位块常表现为全黑/全白）。
     * 至多采样 64 点，任一采样点与首点差异超过容差即判为非单一色。
     */
    private fun isSolidColor(bmp: android.graphics.Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val stepX = Math.max(1, w / 8)
        val stepY = Math.max(1, h / 8)
        val first = bmp.getPixel(0, 0)
        var cx = 0
        var cy = 0
        var count = 0
        while (cy < h && count < 64) {
            cx = 0
            while (cx < w && count < 64) {
                val p = bmp.getPixel(cx, cy)
                // 任一通道差异 > 8 即视为有内容，不是纯色块
                if (Math.abs((p shr 16 and 0xFF) - (first shr 16 and 0xFF)) > 8
                    || Math.abs((p shr 8 and 0xFF) - (first shr 8 and 0xFF)) > 8
                    || Math.abs((p and 0xFF) - (first and 0xFF)) > 8
                ) {
                    return false
                }
                cx += stepX
                count++
            }
            cy += stepY
        }
        return true
    }

    // ============ v4.18 缩略图纯色块（反色）修复 ============
    /**
     * v4.18：图片缩略图在面板内被渲染成「纯色块」——浅色模式显全黑、深色模式显全白。
     *
     * <p><b>根因（关键）</b>：phrase 的缩略图 ImageView 是 {@code imgItem} 与「文件/视频图标」
     * 共用的同一个 View。布局 XML 里为该图标设了主题 tint（{@code ?attr/colorOnSurface} 一类，
     * 浅色模式=黑、深色模式=白），配合 {@code SRC_IN}/{@code src_in} 模式让单色图标随主题变色。
     * 该 tint 在 View inflate 时设一次、之后从不清除；当同一个 {@code imgItem} 用来显示<b>真实照片</b>
     * 时（不透明矩形），SRC_IN 把整张照片染成纯色块——浅色模式=黑块、深色模式=白块。
     * v4.17「强制走本地原图」之前照片根本没成功加载（显示占位/卡片），此 tint bug 被掩盖；
     * v4.17 之后照片能加载了，tint 污染才暴露（用户 2026-10-26 复测：浅色白底图变全黑、深色黑底图变全白）。
     *
     * <p><b>次要因素</b>：{@code imgItemBg.setCardBackgroundColor(getColor(2131099748))} 该资源带 -night
     * 限定，深色模式解析为白色；若照片本身是亮色截图，叠在白色卡片上对比度低、看着也像「白块」。
     * 故修复双管齐下：① 清除 imgItem 的 tint/colorFilter（主因）；② 夜间把卡片底改回深灰（次要）。
     */
    private fun isNightMode(ctx: Context): Boolean {
        return (ctx.resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun guardImageCardBgInNight(vh: Any?) {
        if (vh == null) return
        try {
            val card = Reflect.getField(vh, "imgItemBg") ?: return
            val ctx = (card as? View)?.context ?: return
            if (!isNightMode(ctx)) return
            setCardBackgroundDark(card)
        } catch (t: Throwable) {
            log("guardImageCardBgInNight error - $t")
        }
    }

    private fun setCardBackgroundDark(card: Any) {
        // 深灰卡片底，与 HyperOS 深色表面一致（A=FF，R=2A，G=2A，B=2E）
        val dark = 0xFF2A2A2E.toInt()
        try {
            val m = card.javaClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(card, dark)
        } catch (_: Throwable) {
            try {
                (card as? View)?.setBackgroundColor(dark)
            } catch (_: Throwable) {
                // 都不可调用则放弃本次校正
            }
        }
    }

    /**
     * v4.18【主因修复】：清除缩略图 ImageView（imgItem）上由布局 XML 为「图标」预设的主题 tint
     * 与 colorFilter。imgItem 是图标与真实照片共用的 View（`com.miui.view.MoreIconImage`，继承自
     * AppCompatImageView），图标靠 tint 随主题变色；显示照片时若不清除，照片会被染成纯色块
     * （浅色=黑、深色=白）。
     *
     * <p><b>关键时序</b>：必须在 Glide 实际 `setImageDrawable` <b>之前</b>清掉 tint——关闭面板再打开时，
     * 同一图片命中 Glide 内存缓存会<b>同步</b>设图，若 proceed() 之后才清，tint 已在设图瞬间生效。
     * 首开为异步加载、事后清恰巧生效，故此前表现为「开着正常、重开变黑/白」。
     *
     * <p><b>双套 tint</b>：AppCompatImageView 的 tint 由 `AppCompatImageHelper` 持有，XML 可能用
     * `app:tint` 或 `android:tint`；仅清框架 `setImageTintList` 会被 helper 在下次 setImageDrawable
     * 时重新施加，故框架与 compat（`setSupportImageTintList`）两套都清。
     */
    private fun clearImageTint(vh: Any?) {
        if (vh == null) return
        try {
            val iv = Reflect.getField(vh, "imgItem") ?: return
            val nullCsl: android.content.res.ColorStateList? = null
            val nullMode: android.graphics.PorterDuff.Mode? = null
            for (name in arrayOf("setSupportImageTintList", "setImageTintList")) {
                try {
                    val m = iv.javaClass.getMethod(name, android.content.res.ColorStateList::class.java)
                    m.isAccessible = true
                    m.invoke(iv, nullCsl)
                } catch (_: Throwable) {
                }
            }
            for (name in arrayOf("setSupportImageTintMode", "setImageTintMode")) {
                try {
                    val m = iv.javaClass.getMethod(name, android.graphics.PorterDuff.Mode::class.java)
                    m.isAccessible = true
                    m.invoke(iv, nullMode)
                } catch (_: Throwable) {
                }
            }
            try {
                val m = iv.javaClass.getMethod("clearColorFilter")
                m.isAccessible = true
                m.invoke(iv)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            log("clearImageTint error - $t")
        }
    }

    /**
     * v4.7：记住显示列表里出现过的图片条目（按 [entryKey] 索引其对象引用）。
     *
     * <p>仅记录图片（entryKey 以 {@code img:} 开头）；文本条目稳定且来自最新云端推送，
     * 无需额外记忆。内存上限 60 条，超出丢弃最早记录，防止极端情况下无限增长。
     */
    private fun recordSeenCloud(list: List<*>) {
        for (e in list) {
            if (e == null) continue
            val k = entryKey(e)
            if (k != null && k.startsWith("img:")) {
                sSeenCloud[k] = e
            }
        }
        while (sSeenCloud.size > 60) {
            val it = sSeenCloud.keys.iterator()
            if (it.hasNext()) sSeenCloud.remove(it.next())
        }
    }

    /**
     * v4.7：合并「本次云端查询缓存」与「显示列表里见过的图片」作为补回来源。
     *
     * <p>数量上限解锁开启时：除了 hook 缓存的最近一次云端查询结果（开启态全量、仅 1 条最新推送），
     * 还要并回在本会话显示列表里见到过、却不是最新推送的图片条目——否则第二次 setClipboardList
     * 把图片整表冲掉时，仅靠最新推送无法恢复它们（用户场景：先图后文，最新推送是文字）。
     * 关闭数量上限解锁时维持原行为：只返回 getCrossDeviceClipboardData 的缓存结果。
     * 去重交给 [prependMissingCloud]（按 entryKey），不重复插入。
     */
    private fun mergedCloudCache(): List<Any?> {
        val base = sCloudCache
        if (base.isNullOrEmpty()) {
            // 连基础云端缓存都没有：仍尝试用见过的图片救场（数量上限开启且列表被 strip 的场景）。
            if (!ModulePrefs.isUnlimitCountEnabled()) return emptyList()
            return sSeenCloud.values.filterNotNull()
        }
        if (!ModulePrefs.isUnlimitCountEnabled()) return base
        val out = ArrayList<Any?>(base)
        val keys = HashSet<String>()
        for (e in out) {
            val k = entryKey(e)
            if (k != null) keys.add(k)
        }
        for ((k, e) in sSeenCloud) {
            if (k !in keys) {
                out.add(e)
                keys.add(k)
            }
        }
        return out
    }

    /**
     * 诊断：一条条目的身份摘要（类名 / entryKey / time）。
     *
     * <p>用于判定「entryKey 是否取不到」——若 key 为 null，[prependMissingCloud] 会
     * 无条件把它补回列表、[markDeletedEntry] 也会跳过记账，是复活 bug 的高嫌疑点。
     */
    private fun diagEntry(e: Any?): String {
        val k = entryKey(e)
        val t = entryTime(e)
        return (e?.javaClass?.simpleName ?: "null") + "|key=" + k + "|t=" + t
    }

    /**
     * v4.28：屏蔽表跨进程共享——system_server 中枢（AMS hook 拦截 + Settings.Global 下发）。
     *
     * <p>v4.25–v4.27 的三条通道在真机上逐条被证伪：remote prefs 直写（binder 不交付）、
     * App 中继广播（MIUI 自启动拦截 + Greezer 冻结）、system_server receiver 收广播
     * （diag8 实证收不到隐式广播）。v4.28 不再依赖广播投递：
     * <ul>
     *   <li>上行：删除后仍发 [SystemRelayHook.ACTION_UPDATE] 广播，由 system_server 的
     *       {@code ActivityManagerService.broadcastIntent*} hook 在 AMS 入口拦截合并
     *       （sendBroadcast 必经之路，不靠 receiver）；</li>
     *   <li>下行：system_server 把表写进 [android.provider.Settings.Global]，本进程在
     *       面板打开时直接读取（任何 app 可读，无投递环节、无唤醒、无冻结问题）。</li>
     * </ul>
     *
     * <p>存 {@code key -> time} 的 JSON（「一次复制一代」语义：只拦 time <= 记账值的那一代副本，
     * 用户重新复制同一内容 time 变大即自动解封）。内存上限 400 条，写回时按 time 只保留最大的 200 条。
     */
    /** 中枢状态是否已打过诊断日志（每进程一次）。 */
    private var hubStatusLogged = false

    @Volatile
    private var sHookCtx: android.content.Context? = null

    @Volatile
    private var sHookCtxTried = false

    /** 取当前被 hook 进程的 Application context（与 [ClipboardPinHook] 取法一致）。 */
    private fun deletedCtx(): android.content.Context? {
        val cached = sHookCtx
        if (cached != null) {
            return cached
        }
        if (sHookCtxTried) {
            return null
        }
        sHookCtxTried = true
        val c = try {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? android.content.Context
        } catch (t: Throwable) {
            log("deleted keys: no context - $t")
            null
        }
        sHookCtx = c
        return c
    }

    /** 合并一份 {@code key -> time} JSON 进内存表（同 key 取 time 更大的一代），返回变动条数。 */
    private fun mergeIntoWipedKeys(json: String?): Int {
        if (json.isNullOrEmpty()) {
            return 0
        }
        var n = 0
        try {
            val obj = org.json.JSONObject(json)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val t = obj.optLong(k, 0L)
                if (t > 0L) {
                    val old = sWipedKeys[k]
                    if (old == null || t > old) {
                        sWipedKeys[k] = t
                        n++
                    }
                }
            }
        } catch (t: Throwable) {
            log("deleted keys merge failed - $t")
        }
        return n
    }

    /**
     * 确保屏蔽表就绪：直接从 [android.provider.Settings.Global] 读 system_server 中枢落的表。
     *
     * <p>v4.28：下行不再走广播推送（diag8 实证 system_server receiver 收不到、推送也无从谈起），
     * 改由 system_server 把屏蔽表写进 Settings.Global（见 [SystemRelayHook]），这里读回来
     * 合并进 [sWipedKeys]。任何 app 可读 Global settings，无投递环节、无唤醒、无冻结问题。
     */
    private fun ensureDeletedKeysLoaded() {
        val ctx = deletedCtx() ?: return
        try {
            val json = android.provider.Settings.Global.getString(
                ctx.contentResolver, SystemRelayHook.KEY_BLOCKLIST)
            val n = mergeIntoWipedKeys(json)
            if (n > 0) {
                log("blocklist loaded from settings: +$n -> mem ${sWipedKeys.size}")
            }
            if (!hubStatusLogged) {
                hubStatusLogged = true
                val st = android.provider.Settings.Global.getString(
                    ctx.contentResolver, SystemRelayHook.KEY_HUB_STATUS)
                log("hub status=" + (st ?: "absent") + ", blocklist=" + (json?.length ?: 0) + "B")
            }
        } catch (t: Throwable) {
            log("blocklist load failed - $t")
        }
    }

    /** 把内存表发给 system_server 中枢（隐式广播，由 AMS hook 拦截合并后落 Settings.Global）。 */
    private fun sendUpdateToSystem(json: String) {
        val ctx = deletedCtx() ?: return
        try {
            val i = android.content.Intent(SystemRelayHook.ACTION_UPDATE)
            i.putExtra(SystemRelayHook.EXTRA_JSON, json)
            i.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(i)
            log("blocklist update sent (mem ${sWipedKeys.size})")
        } catch (t: Throwable) {
            log("blocklist update failed - $t")
        }
    }

    /** 把内存表写回：发 [SystemRelayHook.ACTION_UPDATE] 给 system_server 中枢，合并后落 Settings.Global。 */
    private fun persistDeletedKeys() {
        try {
            // 只保留 time 最大的 200 条，按 time 升序淘汰
            val sorted = sWipedKeys.entries.sortedBy { it.value }
            val drop = sorted.size - 200
            if (drop > 0) {
                for (e in sorted.take(drop)) {
                    sWipedKeys.remove(e.key)
                }
            }
            val obj = org.json.JSONObject()
            for ((k, t) in sWipedKeys) {
                obj.put(k, t)
            }
            val json = obj.toString()
            sendUpdateToSystem(json)
            log("deleted keys persisted: ${sWipedKeys.size} entries")
        } catch (t: Throwable) {
            log("deleted keys persist failed - $t")
        }
    }

    /**
     * v4.19：记录「用户单条删除」的条目，精确屏蔽其复活（左滑点删除，非一键清空）。
     *
     * <p>与一键清空（[clearRestoreCaches] 整批记账）不同，单条删除此前<b>完全没有记账</b>：
     * 条目虽从本地库移除，[sCloudCache] / [sSeenCloud] 里仍留着它的对象引用；下次复制新内容
     * 触发 setClipboardList 时 [prependMissingCloud] 会把它当成「被整表替换冲掉的条目」
     * 补回头部，表现为「左滑删掉的内容，一复制新内容就又出现」。
     *
     * <p>这里在删除动作发生时就按 [entryKey] 记账，复用 [sWipedKeys] 的「一次复制一代」
     * 语义：只拦 {@code time <=} 删除时 time 的那一代副本；用户再次复制同一内容
     * （time 更大）自动解封，不会像永久屏蔽那样误杀。
     *
     * <p>同时把该条目从两份补回缓存里直接剔除，双重保险——防止缓存对象被反复补回。
     */
    fun markDeletedEntry(e: Any?) {
        // 写之前必须先载入：否则本进程的空表会把别的输入法进程已记的屏蔽项覆盖掉
        ensureDeletedKeysLoaded()
        val k = entryKey(e)
        val t = entryTime(e) ?: 0L
        if (k == null) {
            log("single delete: entryKey unresolved, skip - " + diagEntry(e))
            return
        }
        val old = sWipedKeys[k]
        if (old == null || t > old) {
            sWipedKeys[k] = t
        }
        while (sWipedKeys.size > 400) {
            val it = sWipedKeys.keys.iterator()
            if (it.hasNext()) sWipedKeys.remove(it.next())
        }
        sSeenCloud.remove(k)
        val base = sCloudCache
        if (base != null) {
            val filtered = ArrayList<Any?>()
            for (c in base) {
                val ck = entryKey(c)
                val ct = entryTime(c) ?: 0L
                // v4.22：内容指纹键（c:）记过即剔除该内容所有副本，不再限 time 代际
                if (ck == k && (ck.startsWith("c:") || ct <= t)) {
                    continue
                }
                filtered.add(c)
            }
            if (filtered.size != base.size) {
                sCloudCache = filtered
            }
        }
        persistDeletedKeys()
        log("single delete marked: " + diagEntry(e) + " (blocked ${sWipedKeys.size} keys)")
    }

    /**
     * v4.12：清空面板（clearClipboardDataMethod）时同步清空补回缓存。
     *
     * <p>sCloudCache / sSeenCloud 本是为「关面板再开图片/云条目被整表替换冲掉时补回」
     * （v4.5~v4.7）而设、跨面板会话保留。但用户「清理剪贴板」清空全部内容后，这两个缓存
     * 里仍是已清空的旧条目——下次复制新内容触发 setClipboardList / mergeCloudIntoDisplay 时
     * prependMissingCloud 会把它们当「缺失」补回头部，表现为「清空后一复制旧内容就复活」。
     * 清空动作发生即作废这两个缓存，令补回来源为空，已清空内容不再复活。
     *
     * <p>不影响 v4.7 正常补回：清空后新复制的图片会被 recordSeenCloud 重新捕获，
     * 再遇「关面板再开被冲掉」仍能从新缓存补回。
     */
    fun clearRestoreCaches() {
        // v4.20：同样要先载入——清空可能发生在别的输入法进程，本进程内存表未必有
        ensureDeletedKeysLoaded()
        // v4.14：先把「当前被清空的这些条目」的 entryKey 记入 sWipedKeys，之后任何补回路径
        // 都不得再塞回它们。关键：此时刷新尚未跑，adapter 显示列表仍是清空前内容，正是要屏蔽的集合。
        // v4.16：值记该条目的 time（屏蔽「这一代副本」，不是永久屏蔽同一内容）。
        val wiped = java.util.LinkedHashMap<String, Long>()
        fun mark(key: String?, e: Any?) {
            if (key == null) return
            val t = entryTime(e) ?: 0L
            val old = wiped[key]
            if (old == null || t > old) wiped[key] = t
        }
        try {
            val adapter = sAdapterRef?.get()
            if (adapter != null) {
                val list = adapter.javaClass.getMethod("getAdapterList").invoke(adapter) as? List<*>
                if (list != null) {
                    for (e in list) {
                        mark(entryKey(e), e)
                    }
                }
            }
        } catch (t: Throwable) {
            log("capture wiped keys (display) failed - " + t)
        }
        sCloudCache?.let { base ->
            for (e in base) {
                mark(entryKey(e), e)
            }
        }
        for ((k, v) in sSeenCloud) {
            mark(k, v)
        }
        for ((k, t) in wiped) {
            val old = sWipedKeys[k]
            if (old == null || t > old) sWipedKeys[k] = t
        }
        while (sWipedKeys.size > 400) {
            val it = sWipedKeys.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        sCloudCache = null
        sSeenCloud.clear()
        // v4.20：一键清空的屏蔽项同样落盘——否则换输入法（新进程）后已清空内容会复活
        persistDeletedKeys()
        log("panel wiped: caches cleared, blocked " + wiped.size + " keys (total " + sWipedKeys.size + ")")
    }

    /** determineContent（equals 用的内容指纹）；失败返回 null。 */
    private fun determineContent(e: Any): String? {
        return stringField(e, "getDetermineContent")
            ?: stringField(e, "getContent")
    }

    /**
     * v4.9：纠正「图文条目被算成纯文本」的类型误判。{@code buildClipboardModelDataType} 对
     * text 非空的 item 不再看其 uri 字段（图文同 item 时图标志 v5=0），图片 item 缺
     * fileName/fileUri 时也不置图标志 → 类型落 10/2/3，图片行永远不出现
     * （用户 2026-10-25：本机图片面板里完全没这一条）。凡 content 含图片 item
     * （uriType 含 image 或有 thumb）却落成纯文本类型的，改为 12（图文）/11（纯图）。
     * 仅动显示列表副本的类型，不影响落库。
     */
    private fun fixImageType(list: List<*>) {
        for (e in list) {
            if (e == null) continue
            val type = try {
                (Reflect.callMethod(e, "getType") as? Number)?.toInt()
            } catch (_: Throwable) { null }
            // v4.16：一并覆盖 type==0（云端直出、原生定型失败的未定型条目），
            // 保底不让它落到 adapter 的 invalid clip type 分支。
            if (type != 0 && type != 2 && type != 3 && type != 10) continue
            val dc = determineContent(e) ?: continue
            var hasImg = false
            var hasText = false
            try {
                val arr = org.json.JSONArray(dc)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("uriType").contains("image") || o.optString("thumbImage").isNotEmpty()) {
                        hasImg = true
                    }
                    val txt = o.optString("text").ifEmpty { o.optString("textItemData") }
                    if (txt.isNotEmpty()) hasText = true
                }
            } catch (_: Throwable) {
                continue
            }
            if (!hasImg) continue
            val newType = if (hasText) 12 else 11
            try {
                Reflect.callMethod(e, "setType", newType)
                log("retype $type -> $newType (image item in text-typed entry)")
            } catch (t: Throwable) {
                log("retype failed - $t")
            }
        }
    }

    /**
     * v4.9：从 content 里找第一个「有文件/缩略图引用」的 {@code ClipDataItemModel}。
     * 经目标应用自己的 {@code fromJSONObject} 反序列化（键名以目标实现为准），失败回 null。
     */
    private fun findRenderableItem(model: Any): Any? {
        return try {
            val dc = determineContent(model) ?: return null
            val arr = org.json.JSONArray(dc)
            val itemCls = java.lang.Class.forName(
                "com.miui.inputmethod.ClipDataItemModel", false, model.javaClass.classLoader
            )
            val fromJson = itemCls.getDeclaredMethod(
                "fromJSONObject", org.json.JSONObject::class.java
            )
            fromJson.isAccessible = true
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val item = fromJson.invoke(null, o) ?: continue
                val file = stringField(item, "getFileUri").orEmpty()
                val canR = stringField(item, "getCanReadFileUri").orEmpty()
                val thumb = stringField(item, "getThumbImage").orEmpty()
                if (file.isNotEmpty() || canR.isNotEmpty() || thumb.isNotEmpty()) return item
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 反射调一次面板的 setRemoteDataToView()（合入云端条目）。 */
    private fun invokeMerge(self: Any) {
        try {
            getMergeMethod(self.javaClass)?.invoke(self)
        } catch (t: Throwable) {
            log("force-merge error - $t")
        }
    }

    /** 按面板 Class 取 setRemoteDataToView（无参）；缓存，失败缓存 null 避免反复反射。 */
    private fun getMergeMethod(cls: Class<*>): Method? {
        synchronized(mergeMethodCache) {
            if (mergeMethodCache.containsKey(cls)) return mergeMethodCache[cls]
            val m = try {
                cls.getDeclaredMethod("setRemoteDataToView").apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
            mergeMethodCache[cls] = m
            return m
        }
    }

    // ===================== 分组筛选（v2.0.6）=====================

    internal const val CAT_ALL = 0
    private const val CAT_LINK = 1
    private const val CAT_IMAGE = 2
    private const val CAT_TEXT = 3
    private const val GROUP_FILE = "clipboardfix_group_v2.txt"

    @Volatile
    private var sActiveCat = CAT_ALL
    /** 跨类读取当前分组（ClipboardPinHook 用于判断置顶是否应在分组视图下禁用）。 */
    internal fun currentGroupCat(): Int = sActiveCat
    /**
     * 取当前分组下适配器位置 [adapterPos] 对应的完整列表条目，供 ClipboardPinHook 标「已置顶」用。
     * 分组态下适配器位置 ≠ 完整列表下标（过滤发生在展示层 sGroupVisibleIdx），必须映射，
     * 否则分组第一条会被误标已置顶（实际未置顶）。返回 null 表示取不到。
     */
    internal fun currentDisplayItem(adapterPos: Int): Any? {
        val full = synchronized(sGroupFullSnapshot) { ArrayList<Any?>(sGroupFullSnapshot) }
        if (full.isEmpty() || adapterPos < 0) return null
        val idx: Int = if (sActiveCat == CAT_ALL) {
            adapterPos
        } else {
            synchronized(sGroupVisibleIdx) {
                if (adapterPos in 0 until sGroupVisibleIdx.size) sGroupVisibleIdx[adapterPos] else -1
            }
        }
        if (idx < 0 || idx >= full.size) return null
        return full[idx]
    }

    /** 同 [currentDisplayItem]，但调用前先按当前快照+分组重算可见下标，避免手势时刻下标陈旧（与删除钩子同策略）。 */
    internal fun currentDisplayItemFresh(adapterPos: Int): Any? {
        synchronized(sGroupFullSnapshot) { recomputeGroupVisible() }
        return currentDisplayItem(adapterPos)
    }

    @Volatile
    private var sMasterList: MutableList<Any?>? = null

    /** v2.0.5 分组「展示层过滤」快照：始终保存 phrase 传入的完整列表（含云端/已剔已删），不按分组裁短。
     *  这是修复「分组态删除/切换后关面板重开丢内容」的关键——phrase 始终持有完整列表，
     *  分组过滤只发生在适配器 getItemCount/getItem 展示层，不把裁短列表写回存储。 */
    private val sGroupFullSnapshot = java.util.ArrayList<Any?>()
    /** 当前分组下可见条目在 [sGroupFullSnapshot] 中的下标（展示顺序）。 */
    private val sGroupVisibleIdx = java.util.ArrayList<Int>()
    /** 已挂上「展示层分组过滤」钩子的适配器类（按类名去重，避免重复 hook）。 */
    private val sGroupHookedClasses = java.util.HashSet<String>()
    /** 换位防重入：onBindViewHolder 两个重载互相委托（3 参→2 参）时，内层收到的位置
     *  已是换过的完整下标——绝不能再映射一次（二次换位会把行 0 错绑到别的条目，
     *  实测「两条数字+一条英文」+ 删错条目的根因）。嵌套调用一律原样放行。 */
    private val sBindRemapGuard = object : java.lang.ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }
    @Volatile
    private var sPopupRef: java.lang.ref.WeakReference<Any>? = null
    private var sTabRef: java.lang.ref.WeakReference<TextView>? = null
    private var sPhraseTabRef: java.lang.ref.WeakReference<TextView>? = null
    private var sContentRef: java.lang.ref.WeakReference<View>? = null
    private var sConfirmedPanel: java.lang.ref.WeakReference<Any>? = null
    private var sTabOverlayRef: java.lang.ref.WeakReference<View>? = null
    private var sTabOverlayBusy = false
    /** 当前是否处于「剪贴板」页面：切到「常用语」等其它页时置 false，禁用分组切换（避免误改其它页）。 */
    @Volatile
    private var sClipboardPageActive = true
    /** 捕获的 phrase adapter.setClipboardList 原方法，用于分组切换时借 phrase 原生路径重灌列表（避免手动 notify 触发面板重建）。 */
    private var sSetClipboardListMethod: java.lang.reflect.Method? = null
    /** 一次性清空路径专用：置 true 时 setClipboardList 钩子跳过云端缓存补回，
     *  使清空后重建的快照严格等于「保留列表（keep）」，杜绝快照(含缓存补回)与内存主列表(mAll=keep)
     *  分叉导致的分组显示错乱（实测：清空文字组后快照比 keep 多 2 条缓存文本）。同步 invoke 用完即清。 */
    @Volatile
    private var sSuppressCloudPrepend = false

    private fun labelFor(cat: Int): String = when (cat) {
        CAT_ALL -> "剪贴板"
        CAT_LINK -> "链接"
        CAT_IMAGE -> "图片"
        CAT_TEXT -> "文字"
        else -> "剪贴板"
    }

    /** 面板打开时应用分组位置：默认「每次打开都回全部」。
     *
     *  <p>「记忆剪贴板分组位置」开启且分组功能开启时，改为恢复 [switchGroupCategory] 记下的
     *  上次分组；否则（记忆关闭 / 分组功能关闭）强制回到「全部」。偏好文件随之写回目标分类，
     *  重启后读偏好不会误复原。
     *  重置发生在 showAtLocation 钩子里、先于列表灌入执行，因此随后的
     *  recompute（setClipboardList 钩子内）按目标分类计算可见下标，不会闪一下旧分组。
     */
    private fun resetGroupToAll(ctx: Context?) {
        val remember = ModulePrefs.isGroupRememberEnabled() && ModulePrefs.isClipboardGroupEnabled()
        val target = if (remember) loadGroupPref(ctx) else CAT_ALL
        val changed = sActiveCat != target
        sActiveCat = target
        saveGroupPref(ctx)
        if (!changed) return
        try {
            synchronized(sGroupFullSnapshot) { recomputeGroupVisible() }
            applyTabLabel()
        } catch (t: Throwable) {
            log("resetGroupToAll ui failed - $t")
        }
        log("group on panel show -> cat=$target (remember=$remember)")
    }

    /** 读回 [switchGroupCategory] 记下的上次分组（GROUP_FILE）；无效 / 读不到时回「全部」。 */
    private fun loadGroupPref(ctx: Context?): Int {
        return try {
            val raw = ctx?.openFileInput(GROUP_FILE)?.use { String(it.readBytes()) }
            when (raw?.trim()?.toIntOrNull()) {
                CAT_LINK, CAT_IMAGE, CAT_TEXT -> raw.trim().toInt()
                else -> CAT_ALL
            }
        } catch (t: Throwable) {
            CAT_ALL
        }
    }

    private fun saveGroupPref(ctx: Context?) {
        try {
            ctx?.openFileOutput(GROUP_FILE, Context.MODE_PRIVATE)?.use { it.write(sActiveCat.toString().toByteArray()) }
        } catch (t: Throwable) {
            log("group pref save failed - $t")
        }
    }

    private fun itemHasImage(item: Any?): Boolean {
        if (item == null) return false
        val dc = determineContent(item) ?: return false
        return try {
            val arr = org.json.JSONArray(dc)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("uriType").contains("image", true) || o.optString("thumbImage").isNotEmpty()) return true
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun itemText(item: Any?): String? {
        if (item == null) return null
        val dc = determineContent(item) ?: return null
        return try {
            val arr = org.json.JSONArray(dc)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("text").ifEmpty { o.optString("textItemData") }
                if (t.isNotEmpty()) sb.append(t).append("\n")
            }
            sb.toString().trim().ifEmpty { null }
        } catch (_: Throwable) { null }
    }

    /** 诊断用：取条目文本前若干字符（去空白），便于在日志里看清分类依据。 */
    private fun shortText(item: Any?): String {
        val t = itemText(item) ?: return "∅"
        val s = t.replace("\\s+".toRegex(), " ").trim()
        return if (s.length > 16) s.substring(0, 16) + "…" else s
    }

    // v2.0.5：链接判定从「整段必须纯 URL」放宽为「文本中包含 URL 即归链接」，
    // 解决带标题/描述的链接条目（如「点击查看 https://...」）被误判为文字、跑到文字分组的问题。
    private val URL_RE = java.util.regex.Pattern.compile("https?://[^\\s]+|www\\.[^\\s]+")

    private fun hasUrl(s: String): Boolean = URL_RE.matcher(s).find()

    private fun classifyItem(item: Any?): Int {
        // 图文混排（含图）归入图片；纯文字再判断是否包含 URL
        if (itemHasImage(item)) return CAT_IMAGE
        val t = itemText(item)
        if (t != null && hasUrl(t)) return CAT_LINK
        return CAT_TEXT
    }

    /** setClipboardList 钩子里调用：把 master 合并为全量集合（含云端/置顶/屏蔽已处理），再按当前分组原地过滤。 */
    private fun applyGroupFilter(list: MutableList<Any?>) {
        try {
            // v2.0.5：master 必须是「全量」集合。分组态下 phrase 可能把已被我们原地过滤过的
            // 显示列表（仅当前分组条目）原样回灌给 setClipboardList（如分组内删除条目后重开面板），
            // 若直接 sMasterList = list 会把 master 误存成「仅当前分组」，切到其它分组时其它内容丢失。
            // 故改为并集：master 只增不减，永远保留其它分组曾出现过的条目；被删条目由 sWipedKeys
            // 在显示层拦截，不会误显示，cycleGroupCategory 也按 sWipedKeys 排除已删项。
            sMasterList = unionMaster(sMasterList, list)
            val mm = sMasterList
            val mT = mm?.count { classifyItem(it) == CAT_TEXT } ?: 0
            val mL = mm?.count { classifyItem(it) == CAT_LINK } ?: 0
            val mI = mm?.count { classifyItem(it) == CAT_IMAGE } ?: 0
            filterListByGroup(list)
        } catch (t: Throwable) {
            // 不可写列表（如 Arrays$ArrayList）：过滤静默跳过，列表保持全量，不影响面板显示。
            log("applyGroupFilter error (list likely immutable) - $t")
        }
    }

    /** master 并集：保留 existing 全部条目，把 incoming 中 entryKey 不在 existing 的条目追加到末尾。
     *  不移除任何条目——删除项由 [sWipedKeys] 在显示层处理，避免分组切换时其它内容丢失。 */
    private fun unionMaster(existing: MutableList<Any?>?, incoming: MutableList<Any?>): MutableList<Any?> {
        if (existing == null) return ArrayList(incoming)
        val result = ArrayList(existing)
        val seen = java.util.HashSet<String?>()
        for (e in existing) seen.add(entryKey(e))
        for (e in incoming) {
            val k = entryKey(e)
            if (!seen.contains(k)) {
                seen.add(k)
                result.add(e)
            }
        }
        return result
    }

    /** 该条目是否已被单条删除/清空记账屏蔽（沿用 sWipedKeys 的「一次复制一代」语义）。 */
    private fun isEntryBlocked(e: Any?): Boolean {
        val k = entryKey(e) ?: return false
        val wt = sWipedKeys[k] ?: return false
        if (k.startsWith("c:")) {
            // 云端内容指纹键：记过即永久拦（云端会重复推送同内容，time 已不可用）
            return true
        }
        val t = entryTime(e) ?: 0L
        return t <= wt
    }

    /** 纯过滤（不回写 master）：按当前分组原地剔除非本组条目。用于云端合并等「已显示列表」路径。
     *  关键：mergeCloudIntoDisplay 补进云端条目后必须过一遍本过滤，否则分组开着时重开会先闪出未过滤条目。 */
    private fun filterListByGroup(list: MutableList<Any?>) {
        if (sActiveCat == CAT_ALL) return
        val cat = sActiveCat
        try {
            list.retainAll { classifyItem(it) == cat }
        } catch (t: Throwable) {
            log("filterListByGroup error (list likely immutable) - $t")
        }
    }

    /**
     * 重新计算当前分组下可见条目的下标映射。
     * 直接用 [sGroupFullSnapshot]（完整列表），不修改 phrase 底层列表——这是修复
     * 「分组态删除/切换后关面板重开丢内容」的根治点：phrase 始终持有完整列表，
     * 过滤只发生在适配器展示层（getItemCount/getItem），不再把裁短后的列表写回存储。
     */
    private fun recomputeGroupVisible() {
        sGroupVisibleIdx.clear()
        val full = sGroupFullSnapshot
        if (sActiveCat == CAT_ALL) {
            for (i in full.indices) sGroupVisibleIdx.add(i)
            return
        }
        val sb = StringBuilder()
        for (i in full.indices) {
            val e = full[i] ?: continue
            if (isEntryBlocked(e)) continue
            val c = classifyItem(e)
            val cc = when (c) { CAT_LINK -> "L"; CAT_IMAGE -> "I"; CAT_TEXT -> "T"; else -> "?" }
            val k = entryKey(e) ?: "?"
            sb.append("[$i$cc=$k=${shortText(e)}]")
            if (c == sActiveCat) sGroupVisibleIdx.add(i)
        }
    }

    /**
     * 删除后同步展示快照：按 entryKey 从完整快照移除被删条目并重算可见下标，
     * 使 getItemCount/getItem 与 phrase 删除后的实际列表保持一致，避免
     * RecyclerView 因「数量不一致」抛出 Inconsistency/IndexOutOfBounds 闪退。
     * 按 key 而非下标移除，与 phrase 内部列表顺序无关，安全。
     */
    private fun removeFromGroupSnapshot(model: Any?) {
        val k = entryKey(model) ?: return
        synchronized(sGroupFullSnapshot) {
            val it = sGroupFullSnapshot.iterator()
            while (it.hasNext()) {
                if (entryKey(it.next()) == k) { it.remove(); break }
            }
            recomputeGroupVisible()
        }
    }

    /** 通知适配器重绘（分组切换后刷新展示层过滤结果）。 */
    private fun notifyAdapter() {
        val a = sAdapterRef?.get() ?: return
        try {
            a.javaClass.getMethod("notifyDataSetChanged").invoke(a)
        } catch (t: Throwable) {
            log("group notifyDataSetChanged failed - $t")
        }
    }

    /** 诊断用：反射取适配器持有的列表在 pos 处的元素，用于对比 phrase 实际绑定的条目。 */
    private fun adapterElementAt(adapter: Any, pos: Int): Any? {
        return try {
            for (f in adapter.javaClass.declaredFields) {
                if (!java.util.List::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val v = f.get(adapter) as? java.util.List<*> ?: continue
                if (pos in 0 until v.size) return v[pos]
            }
            null
        } catch (_: Throwable) { null }
    }

    /** 反射取适配器实际数据列表（getAdapterList 优先，退回首个 List 字段）；找不到返回 null。 */
    private fun adapterDataList(adapter: Any): java.util.List<*>? {
        return try {
            try {
                val gm = adapter.javaClass.getMethod("getAdapterList")
                gm.isAccessible = true
                val v = gm.invoke(adapter) as? java.util.List<*>
                if (v != null) return v
            } catch (_: Throwable) {}
            for (f in adapter.javaClass.declaredFields) {
                if (!java.util.List::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val v = f.get(adapter) as? java.util.List<*> ?: continue
                return v
            }
            null
        } catch (_: Throwable) { null }
    }

    /** 适配器实际数据长度（phrase mData）；-1 表示拿不到（跳过失同步钳制）。 */
    private fun adapterDataListSize(adapter: Any): Int = adapterDataList(adapter)?.size ?: -1

    /** 从适配器实际列表重建展示快照（phrase 自改 mData 后调用），消除快照-列表失同步。
     *
     *  <p><b>不变量（本函数的存在意义）</b>：快照必须与 phrase 的 mClipboardList
     *  <b>严格等长、同序</b>。展示层换位是「显示位置 pos → 快照下标 idx」，phrase 随后
     *  用 idx 去 mData 里取条目；两者错一位就会整体串行（实测：全部分组删掉一条纯文字后，
     *  链接内容跑进文字分组 —— 就是快照比 mData 少一条导致的错位）。
     *
     *  <p>因此这里<b>绝不</b>在快照里单方面剔除条目；相反，若 phrase 列表里仍残留
     *  「已删条目」（屏蔽表命中，说明删除没落到真实列表上：映射偏差 / 对象不同实例），
     *  就直接从真实列表里物理移除——保证「删了就是删了」，同时两者天然等长同序。
     */
    private fun resyncGroupSnapshotFromAdapter(adapter: Any, purgeKeys: Set<String?>? = null) {
        val actual = adapterDataList(adapter)
        var purged = 0
        if (actual is MutableList<*> && !purgeKeys.isNullOrEmpty()) {
            try {
                @Suppress("UNCHECKED_CAST")
                val m = actual as MutableList<Any?>
                val it = m.iterator()
                while (it.hasNext()) {
                    val k = entryKey(it.next())
                    // 只清理「本次确认被删」的 key：屏蔽表里可能有历史误标，拿整张表删列表风险太大
                    if (k != null && purgeKeys.contains(k)) {
                        it.remove()
                        purged++
                    }
                }
            } catch (t: Throwable) {
                log("resync purge skipped - $t")
            }
        }
        synchronized(sGroupFullSnapshot) {
            sGroupFullSnapshot.clear()
            if (actual != null) sGroupFullSnapshot.addAll(actual)
            recomputeGroupVisible()
        }
    }

    /**
     * 在适配器实例上挂「展示层分组过滤」钩子（getItemCount/getItem/getItemViewType/getItemId），
     * 使 RecyclerView 只渲染当前分组条目，而 phrase 持有的列表保持完整。
     * 每次 setClipboardList 拿到适配器实例时按需挂载（按类名去重，避免重复 hook）。
     */
    private fun hookGroupAdapterMethods(adapter: Any) {
        val cls = adapter.javaClass
        if (!sGroupHookedClasses.add(cls.name)) return
        try {
            val mCount = cls.getMethod("getItemCount")
            XposedInit.hook(mCount) { chain ->
                if (ModulePrefs.isClipboardGroupEnabled() && sActiveCat != CAT_ALL) {
                    // 失同步防御：快照比 phrase 实际列表长时（清空/面板重建把 mData 清掉但快照未同步），
                    // 按快照报数量会让 phrase 内部 mData.get 越界闪退。取两者较小值兜底。
                    val actual = adapterDataListSize(chain.thisObject)
                    val n = sGroupVisibleIdx.size
                    val out = if (actual in 0 until n) actual else n
                    return@hook out
                }
                chain.proceed()
            }
            try {
                val mItem = cls.getMethod("getItem", Int::class.javaPrimitiveType ?: Int::class.java)
                XposedInit.hook(mItem) { chain ->
                    if (sBindRemapGuard.get()) {
                        // 嵌套委托：位置已是完整下标，禁止二次换位
                        return@hook chain.proceed()
                    }
                    if (ModulePrefs.isClipboardGroupEnabled() && sActiveCat != CAT_ALL) {
                        val pos = chain.getArg(0) as? Int ?: 0
                        val idx = if (pos in 0 until sGroupVisibleIdx.size) sGroupVisibleIdx[pos] else -1
                        if (idx >= 0 && idx < sGroupFullSnapshot.size) {
                            return@hook sGroupFullSnapshot[idx]
                        }
                    }
                    chain.proceed()
                }
            } catch (_: Throwable) {}
            try {
                val mType = cls.getMethod("getItemViewType", Int::class.javaPrimitiveType ?: Int::class.java)
                XposedInit.hook(mType) { chain ->
                    if (sBindRemapGuard.get()) {
                        // 嵌套委托：位置已是完整下标，禁止二次换位
                        return@hook chain.proceed()
                    }
                    if (ModulePrefs.isClipboardGroupEnabled() && sActiveCat != CAT_ALL) {
                        val pos = chain.getArg(0) as? Int ?: 0
                        val idx = if (pos in 0 until sGroupVisibleIdx.size) sGroupVisibleIdx[pos] else -1
                        // 失同步防御：换位下标必须落在 phrase 实际列表内，否则 phrase 内部
                        // mData.get(下标) 越界闪退（实测 FATAL IndexOutOfBounds）。
                        val actual = adapterDataListSize(chain.thisObject)
                        if (idx in 0 until sGroupFullSnapshot.size && (actual < 0 || idx < actual)) {
                            sBindRemapGuard.set(true)
                            try {
                                return@hook chain.proceed(arrayOf<Any?>(idx))
                            } finally {
                                sBindRemapGuard.remove()
                            }
                        }
                    }
                    chain.proceed()
                }
            } catch (_: Throwable) {}
            try {
                val mId = cls.getMethod("getItemId", Int::class.javaPrimitiveType ?: Int::class.java)
                XposedInit.hook(mId) { chain ->
                    if (sBindRemapGuard.get()) {
                        // 嵌套委托：位置已是完整下标，禁止二次换位
                        return@hook chain.proceed()
                    }
                    if (ModulePrefs.isClipboardGroupEnabled() && sActiveCat != CAT_ALL) {
                        val pos = chain.getArg(0) as? Int ?: 0
                        val idx = if (pos in 0 until sGroupVisibleIdx.size) sGroupVisibleIdx[pos] else -1
                        val actual = adapterDataListSize(chain.thisObject)
                        if (idx in 0 until sGroupFullSnapshot.size && (actual < 0 || idx < actual)) {
                            sBindRemapGuard.set(true)
                            try {
                                return@hook chain.proceed(arrayOf<Any?>(idx))
                            } finally {
                                sBindRemapGuard.remove()
                            }
                        }
                    }
                    chain.proceed()
                }
            } catch (_: Throwable) {}
            // 诊断 + 渲染修复：实测（onBind expected vs phrase）证明 phrase 的 onBindViewHolder
            // 内部用 mData.get(显示位置) 取条目，而 mData 是完整列表 → 取到过滤前的条目（文字组
            // 显示 123456 却绑到中文）。这里把「显示位置」换成完整列表下标，让 phrase 取
            // mData.get(完整下标) = 正确条目。getItemViewType/getItemId 也走同一映射保持一致。
            // 点击删除仍走 getAdapterPosition（显示位置），由 delete 钩子的 sGroupVisibleIdx 映射处理。
            try {
            } catch (_: Throwable) {}
            for (m in cls.declaredMethods) {
                if (m.name == "onBindViewHolder" && m.parameterCount >= 2) {
                    m.isAccessible = true
                    XposedInit.hook(m) { chain ->
                        // 两个 onBindViewHolder 重载互相委托（3 参→2 参）：内层收到的位置
                        // 已是换过的完整下标，必须原样放行——否则二次换位把行 0 错绑到
                        // 别的条目（实测「两条数字+一条英文」+ 删错条目的根因）。
                        if (sBindRemapGuard.get()) {
                            return@hook chain.proceed()
                        }
                        var mod: Array<Any?>? = null
                        try {
                            if (ModulePrefs.isClipboardGroupEnabled() && sActiveCat != CAT_ALL) {
                                val args = chain.getArgs()
                                val pos = args.getOrNull(1) as? Int ?: -1
                                val idx = if (pos in 0 until sGroupVisibleIdx.size) sGroupVisibleIdx[pos] else -1
                                // 失同步防御：换位下标必须落在 phrase 实际列表内，否则 mData.get 越界。
                                val actual = adapterDataListSize(chain.thisObject)
                                if (idx in 0 until sGroupFullSnapshot.size && (actual < 0 || idx < actual)) {
                                    // chain.getArgs() 是不可变 List，直接改下标会抛
                                    // UnsupportedOperationException（换位静默失败的根因）——
                                    // 必须复制成数组再改（同 loadVideoOrImage 钩子范式）。
                                    val arr = args.toTypedArray()
                                    arr[1] = idx
                                    mod = arr
                                } else {
                                }
                            }
                        } catch (t: Throwable) { log("onBind swap failed - $t") }
                        if (mod != null) {
                            sBindRemapGuard.set(true)
                            try {
                                return@hook chain.proceed(mod)
                            } finally {
                                sBindRemapGuard.remove()
                            }
                        }
                        chain.proceed()
                    }
                }
            }
            // 失同步根源防御：清空/关闭面板等路径会清空 phrase mData，但我们的展示快照
            // 不会自动跟着清 → getItemCount 报旧数量、换位下标越界（实测 FATAL
            // IndexOutOfBoundsException at getItemViewType）。在这两个方法执行后
            // 按适配器实际列表重建快照，保持两者同步。
            for (clearName in arrayOf("clearClipboardListItem", "closeAll")) {
                for (m in cls.declaredMethods) {
                    if (m.name == clearName && m.parameterCount == 0) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            val r = chain.proceed()
                            try {
                                if (ModulePrefs.isClipboardGroupEnabled()) {
                                    resyncGroupSnapshotFromAdapter(chain.thisObject)
                                }
                            } catch (t: Throwable) { log("clear resync failed - $t") }
                            r
                        }
                    }
                }
            }
            log("OK: hooked adapter group-display methods on ${cls.name}")
        } catch (t: Throwable) {
            log("group adapter hook failed on ${cls.name} - $t")
        }
    }

    /**
     * 切分组：更新当前分类后，借 phrase 自身的 setClipboardList 重新灌列表。
     * 钩子内 applyGroupFilter 会按新 sActiveCat 过滤，走 phrase 原生填充路径，
     * 不手动 notifyDataSetChanged、不碰 maybeChangeEmptyViewAndClearButtonState，
     * 从根上规避「手动刷新触发面板重建 → 输入法退/重拉键盘闪退」。
     */
    private fun switchGroupCategory(cat: Int, ctx: Context?) {
        try {
            sActiveCat = cat
            saveGroupPref(ctx)
            // 自愈：目标分组在 master 里仍有条目、但输入法内部列表已被重建丢掉（整组消失）时
            // 才补回，否则切过去一片空白（实测分组内删一条后图片组整组消失）。
            // 只判「整组为 0」而不做逐条补齐：避免把列表裁剪/过期掉的零散旧条目又捞回来。
            if (cat != CAT_ALL) {
                val inSnapshot = synchronized(sGroupFullSnapshot) {
                    sGroupFullSnapshot.count { classifyItem(it) == cat }
                }
                if (inSnapshot == 0) restoreMissingRows(cat)
            }
            // v2.0.5 修复：不再把「按分组裁短后的列表」回灌给 phrase（那会把短列表写回存储造成丢内容）。
            // 改为只在适配器展示层切换过滤：重算可见下标并通知重绘，phrase 持有的完整列表不动。
            // 切分组前先从 phrase 当前 mData 重建快照，消除「快照与内存列表分叉」（已删条目被本地库
            // 复活进 mData 后未重建快照导致的 onBind 错位）。recompute 内部已 isEntryBlocked 跳过屏蔽条目。
            val adapterForSwitch = sAdapterRef?.get()
            if (adapterForSwitch != null) {
                resyncGroupSnapshotFromAdapter(adapterForSwitch)
            } else {
                synchronized(sGroupFullSnapshot) { recomputeGroupVisible() }
            }
            notifyAdapter()
            applyTabLabel()
            log("switched group to $cat (adapter filter), visible=${sGroupVisibleIdx.size}")
        } catch (t: Throwable) {
            log("switchGroupCategory error - $t")
        }
    }

    /** 分组切换自愈：把 master 里「属于该分组、但适配器实际列表已丢」的条目补回内部列表。
     *
     *  实测（分组内删除一条后双击轮询）：输入法会重建自己的剪贴板列表，重建产物有时
     *  不再带上其它分组的条目（图片组整组消失）——只影响内存列表，关面板重开（重新全量
     *  查库）即恢复。分组态下若不补回，切到该组就是一片空白、双击轮询也会把它当空组跳过。
     *
     *  按 [entryKey] 判缺失、追加到尾部（不打乱现有渲染位置），再借输入法自身的
     *  setClipboardList 重灌——走我们自己的钩子，快照与可见下标会同步重建。
     *
     *  @return 实际补回的条数；0 表示无缺失或补不回去（不影响后续流程）
     */
    private fun restoreMissingRows(cat: Int): Int {
        try {
            val adapter = sAdapterRef?.get() ?: return 0
            val master = sMasterList ?: return 0
            val method = sSetClipboardListMethod ?: return 0
            val current = adapterDataList(adapter) ?: return 0
            // 屏蔽表必须先载入：否则本进程的空表会把「已删条目」当成缺失又补回来（复活）。
            ensureDeletedKeysLoaded()
            val have = java.util.HashSet<String?>()
            for (e in current) have.add(entryKey(e))
            val missing = ArrayList<Any?>()
            for (e in master) {
                if (e == null || isEntryBlocked(e)) continue
                if (classifyItem(e) != cat) continue
                val k = entryKey(e) ?: continue
                if (!have.add(k)) continue
                missing.add(e)
            }
            if (missing.isEmpty()) return 0
            val merged = ArrayList<Any?>(current)
            merged.addAll(missing)
            method.isAccessible = true
            method.invoke(adapter, merged)
            return missing.size
        } catch (t: Throwable) {
            log("restoreMissingRows failed - $t")
            return 0
        }
    }

    /** 把「剪贴板」标签文字改成分组名（全部→剪贴板），让标签本身就充当分组指示，无需额外小标签。
     *  始终显示当前分组名，与所处页面无关——切到常用语页也保留分组名，用户回来时一眼可见。
     *  写字前校验标签是否仍挂在当前面板下（面板重建后旧引用会指向已隐藏 View），失效则重找。 */
    private fun applyTabLabel() {
        val text = labelFor(sActiveCat)
        // 自愈：标签引用若已脱离当前面板（如面板关闭重建），改字会改到旧 View 上导致看不到刷新，故先重找。
        val content = sContentRef?.get()
        var tab = sTabRef?.get()
        if (content != null && (tab == null || !isDescendantOf(content, tab))) {
            tab = findClipboardTab(content)
            if (tab != null) sTabRef = java.lang.ref.WeakReference(tab)
        }
        try { tab?.text = text } catch (_: Throwable) {}
    }

    /** 内容树里是否真的是剪贴板面板（含「剪贴板/常用语」标签），避免把芯片注入到键盘窗口。 */
    private fun isClipboardPanel(content: View): Boolean {
        var found = false
        fun walk(v: View?) {
            if (found || v == null) return
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c is TextView) {
                        val tx = c.text?.toString() ?: ""
                        if (tx.contains("剪贴板") || tx.contains("常用语")) { found = true; return }
                    }
                    walk(c)
                }
            }
        }
        walk(content)
        return found
    }

    /** 分组 UI：把「剪贴板」标签文字直接改成当前分组名（全部→剪贴板），并盖一层透明覆盖层实现双击循环切换
     *  （文字→链接→图片→全部）。不再需要右上角额外小芯片。 */
    private fun injectGroupChip(popup: Any) {
        val pw = popup as? PopupWindow ?: return
        val content = pw.contentView as? ViewGroup ?: return
        val ctx = content.context ?: return
        // 仅在剪贴板面板内注入：面板与键盘可能复用同一 PopupWindow 类，按类名判定会误命中键盘，
        // 故用「是否含剪贴板/常用语标签」二次确认；命中后缓存面板实例，避免后续因标签被改名导致二次确认失败。
        if (sConfirmedPanel?.get() !== popup) {
            if (!isClipboardPanel(content)) {
                log("group inject skipped: not clipboard panel (likely keyboard), content=${content.javaClass.name}")
                return
            }
            sConfirmedPanel = java.lang.ref.WeakReference(popup)
        }
        sContentRef = java.lang.ref.WeakReference(content)
        // 找到「剪贴板」标签（优先可点击候选）。
        // 注意：面板关闭再打开会新建 content，旧标签的 parent 仍指向旧面板（非 null），仅判 parent==null 发现不了失效，
        // 故用「标签是否仍挂在当前 content 下」判断；失效则重找，避免改字改到已隐藏的旧 View 上。
        var tab = sTabRef?.get()
        if (tab == null || !isDescendantOf(content, tab)) {
            tab = findClipboardTab(content)
            if (tab != null) sTabRef = java.lang.ref.WeakReference(tab)
        }
        if (tab == null) {
            log("group inject: clipboard tab TextView not found")
            return
        }
        // 找「常用语」标签；切到该页时分组切换应被禁止。
        val phraseTab = findPhraseTab(content)
        if (phraseTab != null) sPhraseTabRef = java.lang.ref.WeakReference(phraseTab)
        // 按选中态判断当前所处页面（部分 ROM 用 isSelected 标记激活标签；取不到信号则保持默认）。
        phraseTab?.let { pt ->
            val phraseActive = pt.isSelected || (clickableAncestor(pt)?.isSelected ?: false)
            val clipActive = tab.isSelected || (clickableAncestor(tab)?.isSelected ?: false)
            if (phraseActive) sClipboardPageActive = false
            else if (clipActive) sClipboardPageActive = true
        }
        // 把标签文字改成分组名（始终显示，与所处页面无关）。
        applyTabLabel()
        log("group inject: content=${content.javaClass.name} tab=${tab.javaClass.name} phraseTab=${phraseTab?.javaClass?.name} activeClipboard=${isClipboardPageActive()}")
        // 双击「剪贴板」标签循环切换分组（仅剪贴板页生效）：在标签上方盖透明覆盖层，单击透传原生、双击循环。
        attachDoubleTapOverlay(content, tab, ctx)
    }

    /** 实时判断是否处于「剪贴板」页：优先看两个标签的选中态，取不到信号则回退到缓存标志 sClipboardPageActive。 */
    private fun isClipboardPageActive(): Boolean {
        val clip = sTabRef?.get()
        val phrase = sPhraseTabRef?.get()
        val clipSel = clip != null && (clip.isSelected || (clickableAncestor(clip)?.isSelected ?: false))
        val phraseSel = phrase != null && (phrase.isSelected || (clickableAncestor(phrase)?.isSelected ?: false))
        return when {
            clipSel -> true
            phraseSel -> false
            else -> sClipboardPageActive
        }
    }

    /** 在内容树中查找匹配任一给定文字的标签 TextView（中英文都匹配；按文字比按类名稳）。
     *  返回最可能是「可点击标签」的候选：优先本身就是 clickable / 有 onClick 监听 / 存在可点击祖先的；
     *  否则取文字最短的（标签通常比面板标题短），避免误选标题导致覆盖层盖错位置。 */
    private fun findTabByTexts(root: View, texts: List<String>): TextView? {
        val matches = mutableListOf<TextView>()
        fun walk(v: View?) {
            if (v == null) return
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            } else if (v is TextView) {
                val tx = v.text?.toString() ?: ""
                if (texts.any { tx.contains(it) }) matches.add(v)
            }
        }
        walk(root)
        if (matches.isEmpty()) return null
        matches.firstOrNull { it.isClickable || it.hasOnClickListeners() || clickableAncestor(it) != null }
            ?.let { return it }
        return matches.minByOrNull { it.text?.length ?: 0 }
    }

    /** 查找「剪贴板」标签（中英文都匹配）。 */
    private fun findClipboardTab(root: View): TextView? =
        findTabByTexts(root, listOf("剪贴板", "Clipboard"))

    /** 查找「常用语」标签（切到该页时禁用分组切换）。 */
    private fun findPhraseTab(root: View): TextView? =
        findTabByTexts(root, listOf("常用语", "常用"))

    /** 向上找最近的「可点击」祖先（不含自身；含自身请用调用处判断），找不到返回 null。 */
    private fun clickableAncestor(v: View): View? {
        var p = v.parent
        while (p is View) {
            if (p.isClickable || p.hasOnClickListeners()) return p
            p = p.parent
        }
        return null
    }

    /** 取标签真正的「点击目标」：内层文字 TextView 通常不可点击，切页由外层可点击按钮处理，
     *  故优先返回可点击祖先（标签按钮本体），自身可点击才返回自身。切页监听必须挂在这里，
     *  否则 setOnClickListener 会把控件变可点击、抢走父容器的切页点击。 */
    private fun clickTarget(tab: View): View = clickableAncestor(tab) ?: tab

    /** 判断 v 是否仍挂在 root 子树下（用于识别面板重建后旧标签失效）。 */
    private fun isDescendantOf(root: View, v: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    /** 在「剪贴板」标签区域盖透明覆盖层，独占该区域触摸：单击透传原生标签切换，双击循环切换分组。
     *  覆盖层方案可彻底绕开「标签触摸目标不确定 / phrase 自行消费事件」导致的双击收不到问题。 */
    private fun attachDoubleTapOverlay(content: ViewGroup, tab: TextView, ctx: Context) {
        if (sTabOverlayBusy) return
        val old = sTabOverlayRef?.get()
        if (old != null && old.parent === content) return
        if (old != null && old.parent != null) (old.parent as? ViewGroup)?.removeView(old)
        sTabOverlayBusy = true
        // 等布局完成再量坐标，否则 getGlobalVisibleRect 可能为零或偏差
        content.post {
            sTabOverlayBusy = false
            // 覆盖层盖「可点击祖先」（标签按钮本体）而非内层文字，双击在整个按钮区域都有效。
            val clickBtn = clickTarget(tab)
            val tabRect = android.graphics.Rect()
            val contentRect = android.graphics.Rect()
            if (!clickBtn.getGlobalVisibleRect(tabRect) || !content.getGlobalVisibleRect(contentRect)) {
                log("group double-tap overlay: layout not ready, skip")
                return@post
            }
            val w = tabRect.width()
            val h = tabRect.height()
            if (w <= 0 || h <= 0) { log("group double-tap overlay: zero size, skip"); return@post }
            val left = tabRect.left - contentRect.left - content.paddingLeft
            val top = tabRect.top - contentRect.top - content.paddingTop
            val overlay = View(ctx).apply {
                setBackgroundColor(0x00000000)
                isClickable = true
            }
            val lp = android.widget.FrameLayout.LayoutParams(w, h).apply {
                gravity = android.view.Gravity.NO_GRAVITY
                leftMargin = left
                topMargin = top
            }
            overlay.setOnTouchListener(object : View.OnTouchListener {
                var lastDown = 0L
                override fun onTouch(v: View?, ev: MotionEvent?): Boolean {
                    if (ev?.action == MotionEvent.ACTION_DOWN) {
                        val now = System.currentTimeMillis()
                        if (lastDown > 0 && now - lastDown < 320) {
                            // 双击：循环切换分组，并清掉单击计时避免三连击误判。
                            // 非剪贴板页（常用语等）时不循环，只透传单击切回剪贴板页。
                            lastDown = 0
                            if (isClipboardPageActive()) {
                                cycleGroupCategory(ctx)
                            } else {
                                try { clickBtn.performClick() } catch (_: Throwable) {}
                            }
                        } else {
                            lastDown = now
                            // 单击：透传原生标签切换（performClick 打到可点击祖先，触发 phrase 的切页 onClick）
                            try { clickBtn.performClick() } catch (_: Throwable) {}
                        }
                    }
                    return true
                }
            })
            content.addView(overlay, lp)
            content.requestLayout()
            sTabOverlayRef = java.lang.ref.WeakReference(overlay)
            log("group double-tap overlay attached: left=$left top=$top w=$w h=$h tab=${tab.javaClass.name}")
        }
    }

    /** 双击循环：只在「有条目」的分组间切换——没图片不轮到图片、没链接不轮到链接、没文字不轮到文字；
     *  剪贴板无任何内容时不允许切换。顺序：文字 → 链接 → 图片 → 全部 → 文字（跳过空分组）。
     *  CAT_ALL（全部）在有内容时始终可回。 */
    private fun cycleGroupCategory(ctx: Context?) {
        if (!isClipboardPageActive()) { log("group cycle blocked: not in clipboard page"); return }
        // v2.0.5 修复（「分组内删除后轮询丢图片组」根因）：
        // 可用性判定不能只看 master(DIAGGRP buildCapture)。实测在文字/链接分组里删掉一条后，
        // 输入法重建内部列表，重建产物往往不再带图片条目；master 只在全量查库路径并集更新，
        // 于是图片组被判定为空、双击直接跳过 —— 只能在文字/链接/全部之间轮询，关面板重开
        // （重新全量捕获）才恢复。改为「展示快照 ∪ master」双源判定：任一源认为有内容
        // 就允许切过去，切过去后再用实际渲染结果校验（见下方 blank 回滚）。
        val keys = java.util.HashSet<String?>()
        var hasText = false
        var hasLink = false
        var hasImage = false
        fun tally(e: Any?) {
            if (e == null) return
            if (isEntryBlocked(e)) return     // 已删条目（屏蔽表拦截）不计入分类
            val k = entryKey(e) ?: return
            if (!keys.add(k)) return          // 同一内容在快照/master 各存一份，只算一次
            when (classifyItem(e)) {
                CAT_TEXT -> hasText = true
                CAT_LINK -> hasLink = true
                CAT_IMAGE -> hasImage = true
            }
        }
        synchronized(sGroupFullSnapshot) { for (e in sGroupFullSnapshot) tally(e) }
        sMasterList?.let { for (e in it) tally(e) }
        if (!hasText && !hasLink && !hasImage) {
            log("group cycle blocked: clipboard empty")
            return
        }
        // 固定轮询顺序，跳过空分组
        val order = intArrayOf(CAT_TEXT, CAT_LINK, CAT_IMAGE, CAT_ALL)
        fun available(cat: Int): Boolean = when (cat) {
            CAT_TEXT -> hasText
            CAT_LINK -> hasLink
            CAT_IMAGE -> hasImage
            else -> true // CAT_ALL 有内容时始终可回
        }
        val curIdx = order.indexOf(sActiveCat).let { if (it < 0) order.size - 1 else it }
        val prev = sActiveCat
        for (step in 1..order.size) {
            val next = order[(curIdx + step) % order.size]
            if (!available(next)) continue
            switchGroupCategory(next, ctx)
            // 判定有内容却一行都渲染不出来（快照里确实没有该组条目）——别停在这个空白组，
            // 回滚后继续找下一个可用分组。
            if (next == CAT_ALL || sGroupVisibleIdx.isNotEmpty()) {
                log("group cycle: $prev -> $next (text=$hasText link=$hasLink image=$hasImage) visible=${sGroupVisibleIdx.size}")
                return
            }
            log("group cycle skip blank render: $next (snapshot has no such row)")
            sActiveCat = prev
        }
        // 全部落空：回到原分组，保持 sActiveCat 与已保存偏好一致
        switchGroupCategory(prev, ctx)
        log("group cycle blocked: no available group")
    }

    private fun log(msg: String) {
        XposedInit.log("$TAG $msg")
    }
}
