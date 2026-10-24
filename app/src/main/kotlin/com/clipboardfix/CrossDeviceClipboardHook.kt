package com.clipboardfix

import android.os.Handler
import android.os.Looper
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
 * ============ 修复（v4 / v4.1，本类全部实现）============
 *  v4【显示层·进程无关】：hook 框架 android.widget.PopupWindow.showAtLocation，回调里用实例
 *     类名识别面板，每次面板打开主动调两次 setRemoteDataToView()（立即 + 600ms 后），把云端
 *     条目强制合进列表，绕开「必须等 /across 变更通知」的限制。该 hook 装在所有被 hook 进程，
 *     面板在哪出现就在哪生效。
 *  v4.1【打开即出】：hook MiuiClipboardManager.getCrossDeviceClipboardData 缓存云端数据；
 *     hook InputMethodClipboardAdapter.setClipboardList，在本地列表灌入时把缺失的云端条目
 *     补回列表头部（云端在顶、幂等去重），避免被整表替换冲掉，面板首帧即含云端图。
 *
 *  历史上曾尝试用「保活 /across 观察者」（定时器 + 拦截 endObserveThumbnailInfoChange）来
 *  间接刷新显示层，但观察者活着 ≠ /across 会变更，对显示层无效，相关代码已作为失效代码删除；
 *  键盘上方的云端剪贴板浮标属系统原生行为，本修复不影响它。
 */
object CrossDeviceClipboardHook {

    private const val TAG = "[CrossDevice]"

    /** 面板打开后二次合并云端的延迟，覆盖 initData 内异步填本地列表的时序。 */
    private const val MERGE_DELAY_MS = 600L

    /** 框架 PopupWindow 全限定名（任何进程都能按名拿到）。 */
    private const val POPUP_WINDOW = "android.widget.PopupWindow"

    /** 我们的剪贴板面板类名（输入法进程动态 dex 加载，实例自带真实 Class，无需按名解析）。 */
    private const val PHRASE_POPUP = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"

    /** 云端数据来源（getCrossDeviceClipboardData 所在，动态 dex，实例出现后才可解析）。 */
    private const val MIUI_CLIPBOARD_MANAGER = "com.miui.inputmethod.MiuiClipboardManager"

    /** 面板列表适配器（setClipboardList 整表替换点，动态 dex）。 */
    private const val CLIP_ADAPTER = "com.miui.inputmethod.InputMethodClipboardAdapter"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 显示层（v4）是否已安装：进程内只装一次。 */
    private var panelMergeInited = false

    /** 每个面板实例只触发一次合并（showAtLocation 可能被多次调用，按实例去重）。 */
    private val sMerged = java.util.WeakHashMap<Any, Boolean>()

    /** 按面板 Class 缓存 setRemoteDataToView，避免每次打开都反射查找。 */
    private val mergeMethodCache = java.util.WeakHashMap<Class<*>, Method?>()

    /** 云端条目缓存（getCrossDeviceClipboardData 最近一次返回），供 setClipboardList 补回。 */
    @Volatile
    private var sCloudCache: List<Any?>? = null

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
        log("force setRemoteDataToView on panel open")
    }

    /**
     * 面板类 hook（动态 dex，进程内只装一次）：
     *  1) getCrossDeviceClipboardData：缓存云端条目；
     *  2) adapter.setClipboardList：本地列表灌入时把缺失的云端条目补回头部（云端在顶、幂等）。
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
                                sCloudCache = ArrayList(r)
                                log("cloud cache updated: ${r.size} rows")
                            }
                            r
                        }
                        log("OK: hooked getCrossDeviceClipboardData")
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
                            try {
                                val arg = chain.getArg(0)
                                if (arg is ArrayList<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val list = arg as ArrayList<Any?>
                                    val cache = sCloudCache
                                    if (cache != null && cache.isNotEmpty() && !list.contains(cache[0])) {
                                        list.addAll(0, cache)
                                        log("prepend cloud rows into setClipboardList")
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
            }
        } catch (t: Throwable) {
            log("FAIL: ensurePanelClassHooks - $t")
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
