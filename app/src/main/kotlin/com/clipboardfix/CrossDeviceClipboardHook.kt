package com.clipboardfix

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
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
                chain.proceed()
                val self = chain.thisObject
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
        // v4.7：sSeenCloud **不清空**——旧图关面板再开后已不是最新推送，靠本缓存补回。
        // 面板类（动态 dex）此刻已加载：装上云端缓存 + setClipboardList 补回两个 hook，
        // 保证随后 $2$1 灌本地列表时云端条目不被整表替换冲掉（打开即出的关键）。
        ensurePanelClassHooks(self.javaClass.classLoader)
        invokeMerge(self)
        mainHandler.postDelayed({
            try {
                invokeMerge(self)
            } catch (_: Throwable) {
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
                        XposedInit.hook(m) { chain ->
                            // 记录 adapter 实例（v4.5 增量合入用；弱引用不碍回收）
                            sAdapterRef = java.lang.ref.WeakReference(chain.thisObject)
                            try {
                                val arg = chain.getArg(0)
                                // 用 MutableList 而非 ArrayList：phrase 各版本灌入的列表
                                // 实现类不完全一致（Arrays$ArrayList / 包装 list 均有可能），
                                // 只要可写就补，不可写静默跳过（下同）。
                                if (arg is MutableList<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val list = arg as MutableList<Any?>
                                    // 先记下显示列表里出现过的图片条目（下次被 strip 整表替换冲掉时补回）。
                                    recordSeenCloud(list)
                                    val cache = mergedCloudCache()
                                    if (cache.isNotEmpty()) {
                                        prependMissingCloud(list, cache)
                                    }
                                }
                            } catch (t: Throwable) {
                                log("prepend error - $t")
                            }
                            chain.proceed()
                        }
                        log("OK: hooked adapter setClipboardList")
                        break
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
        val present = HashSet<String>()
        for (e in list) {
            val k = entryKey(e)
            if (k != null) {
                present.add(k)
            }
        }
        var added = 0
        var blocked = 0
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
        }
        return entryTime(e)?.toString()
    }

    /** 反射取字符串字段/无参 getter；失败返回 null。 */
    private fun stringField(e: Any, name: String): String? {
        return try {
            e.javaClass.getMethod(name).invoke(e) as? String
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

    private fun log(msg: String) {
        XposedInit.log("$TAG $msg")
    }
}
