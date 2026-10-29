package com.clipboardfix

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ComposeView
import com.clipboardfix.ui.AppConfig
import com.clipboardfix.ui.BoolAction
import com.clipboardfix.ui.GlassSheetAction
import com.clipboardfix.ui.GlassSheetBridge
import com.clipboardfix.ui.GlassSheetContent
import com.clipboardfix.ui.GlassSheetRequest
import com.clipboardfix.ui.IntAction
import com.clipboardfix.ui.installPages
import com.clipboardfix.ui.installWelcome
import com.clipboardfix.ui.showSnack
import com.clipboardfix.ui.showSnackLong
import com.clipboardfix.ui.stateOf
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit

/**
 * 模块主界面（LSPosed 设置入口 + 桌面入口）。
 *
 * <p>UI 自 v2.0 起迁至 Compose + Miuix：本 Activity 继承 ComponentActivity，
 * onCreate 内经 installPages 挂载 Compose 界面；
 * 所有功能逻辑（模块状态查询、偏好读写/推送、重启、隐藏图标、更新检查、各弹卡）仍在此，
 * 通过可观察状态（MutableState）与动作回调暴露给 Compose。Xposed hook 逻辑不受影响。
 */
class AboutActivity : ComponentActivity() {

    // ---- Compose 可观察状态（composable 读取 .value 即获得响应式刷新）----
    val uiStatusText: MutableState<String> = stateOf("检测中…")
    /** root 标识：本模块已被授予 su 权限时为 true。 */
    val uiRootGranted: MutableState<Boolean> = stateOf(false)
    /** 已启用的功能数量（五个功能开关中为「开」的个数）。 */
    val uiEnabledFeatures: MutableState<Int> = stateOf(0)
    /** 标识显隐开关：控制 Root 标识与「已启用的功能：X」行的可见性，默认显示。 */
    val uiBadgesVisible: MutableState<Boolean> = stateOf(true)
    val uiImeCountText: MutableState<String> = stateOf("已启用输入法：—")
    val uiStatusEnabled: MutableState<Boolean> = stateOf(false)
    val uiSwImeUnlock: MutableState<Boolean> = stateOf(ModulePrefs.DEFAULT_IME_UNLOCK)
    val uiSwUnlimitCount: MutableState<Boolean> = stateOf(ModulePrefs.DEFAULT_UNLIMIT_COUNT)
    val uiSwUnlimitTime: MutableState<Boolean> = stateOf(ModulePrefs.DEFAULT_UNLIMIT_TIME)
    val uiSwPanelColor: MutableState<Boolean> = stateOf(ModulePrefs.DEFAULT_PANEL_COLOR)
    val uiSwClipboardPin: MutableState<Boolean> = stateOf(ModulePrefs.DEFAULT_CLIPBOARD_PIN)
    val uiIconHidden: MutableState<Boolean> = stateOf(false)
    val uiUpdateDisabled: MutableState<Boolean> = stateOf(false)
    /** 主题模式（跟随系统/浅色/深色）。 */
    val uiThemeMode: MutableState<Int> = stateOf(AppConfig.MODE_SYSTEM)

    // ---- 欢迎引导（首次进入的三页覆盖层）----
    /** 当前页（Compose 读写同一份状态）。 */
    val uiWelcomePage: MutableState<Int> = stateOf(sWelcomePage)
    /** 是否已获得超级用户权限（su 探测耗时会阻塞，故后台线程回填）。 */
    val uiWelcomeRoot: MutableState<Boolean> = stateOf(false)
    /** 手势提示线（小白条）是否已关闭。 */
    val uiWelcomeGestureLineHidden: MutableState<Boolean> = stateOf(false)
    private var welcomeOverlay: ComposeView? = null
    private var welcomeBackCallback: OnBackPressedCallback? = null
    /** 实时监听手势提示线开关（引导展示期间注册，完成后注销）。 */
    private var gestureLineObserver: ContentObserver? = null
    /** 引导展示期间的轻量轮询：实时重查「模块激活」状态。 */
    private var welcomeStatusPoller: Runnable? = null

    // ---- 页面与导航（View 外壳 + ComposeView 页面 + 液态玻璃导航）----
    private var pageHome: ComposeView? = null
    private var pageFeature: ComposeView? = null
    private var pageAbout: ComposeView? = null
    private var pageSettings: ComposeView? = null
    private var contentContainer: View? = null
    private var bottomNav: LiquidGlassBarView? = null
    private var navHome: FrameLayout? = null
    private var navFeature: FrameLayout? = null
    private var navAbout: FrameLayout? = null
    private var navSettings: FrameLayout? = null
    private var navIconHome: ImageView? = null
    private var navIconFeature: ImageView? = null
    private var navIconAbout: ImageView? = null
    private var navIconSettings: ImageView? = null
    private var navLabelHome: TextView? = null
    private var navLabelFeature: TextView? = null
    private var navLabelAbout: TextView? = null
    private var navLabelSettings: TextView? = null
    private var currentTab = 0

    /**
     * selectTab 是否已初始化过。onCreate 里的首次调用是「恢复上次页签」，必须直接落位。
     */
    private var mTabInitialized = false

    // 模块启用状态（通过 libxposed:service 实时查询 LSPosed）
    private val mStatusHandler = Handler(Looper.getMainLooper())

    // ---- 状态卡「连续点击 10 次」隐藏/显示标识 ----
    private var mTapCount = 0
    private var mLastTapTime = 0L

    // ---- 实时 root 轮询（su 已授权时无弹窗、开销极小）----
    private var rootPoller: Runnable? = null

    override fun attachBaseContext(base: Context?) {
        // 语言偏好：仅为上下文覆盖 Locale（主题走系统 Application Night Mode）。
        super.attachBaseContext(AppConfig.applyLocale(base!!))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式（对齐参考项目）：系统栏全透明且禁用系统自动加的对比度蒙层。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false)
            window.setNavigationBarContrastEnforced(false)
        }
        // 浅色模式下让状态栏/导航栏文字变深，保证可读
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        // 开关状态：从本地偏好恢复
        loadSwitchState()
        // 标识显隐：从本地偏好恢复（隐藏后重启仍保持隐藏，默认显示）
        uiBadgesVisible.value =
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_BADGES_VISIBLE, true)
        // 桌面图标显隐：恢复当前状态
        updateHideRow()

        // View 外壳：内容区（三页 ComposeView）+ 底部液态玻璃导航
        setContentView(R.layout.activity_about)
        bindViews()
        setupBottomNav()

        // 装配四页 Compose + Miuix 界面（状态与动作回调桥接到本 Activity）
        installPages(
            pageHome!!, pageFeature!!, pageAbout!!, pageSettings!!,
            uiStatusText, uiImeCountText, uiStatusEnabled, uiRootGranted,
            uiEnabledFeatures, uiBadgesVisible,
            BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE,
            DeviceNames.marketName(), "Android " + Build.VERSION.RELEASE,
            Build.VERSION.INCREMENTAL, phraseVersion(), abiInfo(),
            uiSwImeUnlock, uiSwUnlimitCount, uiSwUnlimitTime, uiSwPanelColor, uiSwClipboardPin, uiIconHidden,
            Runnable { doCheckUpdate() },
            Runnable { onStatusCardTap() },
            BoolAction { onToggleImeUnlock(it) },
            BoolAction { onToggleUnlimitCount(it) },
            BoolAction { onToggleUnlimitTime(it) },
            BoolAction { onTogglePanelColor(it) },
            BoolAction { onToggleClipboardPin(it) },
            BoolAction { onToggleHideIcon(it) },
            IntAction { onRebootOrReload(it) },
            Runnable { openUrl("https://www.coolapk.com/u/3019478") },
            Runnable { openUrl("https://weibo.com/u/3725737792") },
            Runnable { openUrl("https://github.com/jiangshangwan/ClipboardFix") },
            Runnable { showChangelog() },
            Runnable { showDonateSheet() },
            Runnable { openUrl(TELEGRAM_URL) },
            Runnable { openLicenses() },
            uiThemeMode, AppConfig.getLangMode(this), uiUpdateDisabled,
            IntAction { selectTheme(it) },
            IntAction { selectLanguage(it) },
            BoolAction { onToggleUpdateDisabled(it) }
        )

        // 模块启用状态实时查询
        initModuleStatus()

        // 初始落到上次所在 Tab（重建后保留，首次为 0=主页）
        selectTab(sLastTab)

        // 首次进入：三页欢迎引导；已完成引导的旧版本升级用户走「您需了解」底部弹卡兜底。
        if (!welcomeDone()) {
            showWelcomeOverlay()
        } else if (!mustKnowAgreed()) {
            showMustKnow(true)
        }

        // 静默检查更新（有新版本才弹窗；已停用更新检测则跳过）
        if (!AppConfig.isUpdateDisabled(this)) {
            UpdateChecker(this).checkSilent()
        }
    }

    private fun bindViews() {
        contentContainer = findViewById(R.id.contentContainer)
        pageHome = findViewById(R.id.pageHome)
        pageFeature = findViewById(R.id.pageFeature)
        pageAbout = findViewById(R.id.pageAbout)
        pageSettings = findViewById(R.id.pageSettings)
        bottomNav = findViewById(R.id.bottomNav)
        navHome = findViewById(R.id.navHome)
        navFeature = findViewById(R.id.navFeature)
        navAbout = findViewById(R.id.navAbout)
        navSettings = findViewById(R.id.navSettings)
        navIconHome = findViewById(R.id.navIconHome)
        navIconFeature = findViewById(R.id.navIconFeature)
        navIconAbout = findViewById(R.id.navIconAbout)
        navIconSettings = findViewById(R.id.navIconSettings)
        navLabelHome = findViewById(R.id.navLabelHome)
        navLabelFeature = findViewById(R.id.navLabelFeature)
        navLabelAbout = findViewById(R.id.navLabelAbout)
        navLabelSettings = findViewById(R.id.navLabelSettings)
    }

    private fun setupBottomNav() {
        // 玻璃条折射的是内容区（承载四页 ComposeView）的真实内容
        bottomNav!!.setBackdrop(contentContainer)
        // 顺序：主页 / 功能 / 设置 / 关于
        bottomNav!!.setItems(navHome, navFeature, navSettings, navAbout)
        bottomNav!!.setAccentColor(getColor(R.color.accent))
        bottomNav!!.setOnItemSelectedListener { selectTab(it) }
    }

    /**
     * 切换页面（带页面切换动画）。
     *
     * 动画：新页按导航方向淡入 + 轻微横向位移 + 0.985→1 缩放（260ms 减速），
     * 旧页反向淡出并位移（180ms 加速）后 GONE。
     * 两种不播动画的情况：目标就是当前页；onCreate 里恢复上次页签（见 mTabInitialized）。
     */
    private fun selectTab(idx: Int) {
        val prev = currentTab
        val animate = mTabInitialized && idx != prev
        mTabInitialized = true
        currentTab = idx
        sLastTab = idx

        val pages = arrayOf<View?>(pageHome, pageFeature, pageSettings, pageAbout)
        // 方向：目标在右侧（idx 更大）时新页自右滑入、旧页向左滑出；反之相反
        val dir = if (idx > prev) 1f else -1f
        val shift = 26f * resources.displayMetrics.density
        for (i in pages.indices) {
            val p = pages[i] ?: continue
            if (i == idx) {
                p.animate().cancel()
                p.visibility = View.VISIBLE
                p.bringToFront()
                if (animate) {
                    p.alpha = 0f
                    p.translationX = dir * shift
                    p.scaleX = PAGE_ANIM_SCALE
                    p.scaleY = PAGE_ANIM_SCALE
                    p.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(PAGE_ANIM_IN_MS)
                        .setInterpolator(DecelerateInterpolator(1.6f))
                        .start()
                } else {
                    p.alpha = 1f
                    p.translationX = 0f
                    p.scaleX = 1f
                    p.scaleY = 1f
                }
            } else if (p.visibility != View.GONE) {
                p.animate().cancel()
                if (animate) {
                    p.animate()
                        .alpha(0f)
                        .translationX(-dir * shift)
                        .scaleX(PAGE_ANIM_SCALE)
                        .scaleY(PAGE_ANIM_SCALE)
                        .setDuration(PAGE_ANIM_OUT_MS)
                        .setInterpolator(AccelerateInterpolator(1.2f))
                        .withEndAction {
                            p.visibility = View.GONE
                            p.alpha = 1f
                            p.translationX = 0f
                            p.scaleX = 1f
                            p.scaleY = 1f
                        }
                        .start()
                } else {
                    p.visibility = View.GONE
                    p.alpha = 1f
                    p.translationX = 0f
                    p.scaleX = 1f
                    p.scaleY = 1f
                }
            }
        }

        applyNavItem(navIconHome, navLabelHome, currentTab == 0)
        applyNavItem(navIconFeature, navLabelFeature, currentTab == 1)
        applyNavItem(navIconSettings, navLabelSettings, currentTab == 2)
        applyNavItem(navIconAbout, navLabelAbout, currentTab == 3)
        // 恢复页签（首次调用）不播动画：否则重建后导航胶囊会从主页滑到目标页
        bottomNav?.setSelectedIndex(idx, animate)
    }

    private fun applyNavItem(icon: ImageView?, label: TextView?, selected: Boolean) {
        val color = if (selected) getColor(R.color.accent) else getColor(R.color.text_tertiary)
        icon?.setColorFilter(color)
        label?.setTextColor(color)
    }

    // ---------------- 模块启用状态（libxposed:service 实时查询，进程级状态） ----------------
    private fun initModuleStatus() {
        sCurrent = WeakReference(this)
        if (!sListenerRegistered) {
            XposedServiceHelper.registerListener(sXposedListener)
            sListenerRegistered = true
        }
        if (sModuleBound) {
            refreshModuleStatus()
        } else {
            scheduleStatusFallback()
        }
        // root 标识：后台探测，结果回主线程写状态（不执行 su，避免授权弹窗）
        refreshRootStatus()
        // 已启用的功能数量：随开关变化刷新
        refreshEnabledFeatures()
    }

    /** 后台探测本模块是否被授予 root 权限，并刷新 uiRootGranted。 */
    private fun refreshRootStatus() {
        Thread {
            val root = RootCheck.isModuleRootGranted()
            mStatusHandler.post { uiRootGranted.value = root }
        }.start()
    }

    /** 统计五个功能开关中为「开」的数量，回填 uiEnabledFeatures。 */
    private fun refreshEnabledFeatures() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var n = 0
        if (prefs.getBoolean(ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK)) n++
        if (prefs.getBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT)) n++
        if (prefs.getBoolean(ModulePrefs.KEY_UNLIMIT_TIME, ModulePrefs.DEFAULT_UNLIMIT_TIME)) n++
        if (prefs.getBoolean(ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR)) n++
        if (prefs.getBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, ModulePrefs.DEFAULT_CLIPBOARD_PIN)) n++
        uiEnabledFeatures.value = n
    }

    /**
     * 状态卡连续点击 10 次切换「标识」显隐（Root 标识 + 已启用的功能行）。
     */
    private fun onStatusCardTap() {
        val now = System.currentTimeMillis()
        if (now - mLastTapTime > TAP_RESET_MS) {
            mTapCount = 0
        }
        mLastTapTime = now
        mTapCount++
        val visible = uiBadgesVisible.value
        if (mTapCount < TAP_TARGET) {
            showSnack(if (visible) getString(R.string.tap_hide_hint) else getString(R.string.tap_show_hint))
        } else {
            mTapCount = 0
            val shown = !visible
            uiBadgesVisible.value = shown
            // 持久化：隐藏后重启仍保持隐藏，直到再次 10 连击显示
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BADGES_VISIBLE, shown).apply()
            showSnack(if (shown) getString(R.string.badges_shown) else getString(R.string.badges_hidden))
        }
    }

    /** 实时重查本模块 root 授权：每 ROOT_POLL_MS 轮询一次。 */
    private fun startRootPoller() {
        stopRootPoller()
        val r = object : Runnable {
            override fun run() {
                refreshRootStatus()
                mStatusHandler.postDelayed(this, ROOT_POLL_MS)
            }
        }
        rootPoller = r
        mStatusHandler.postDelayed(r, ROOT_POLL_MS)
    }

    /** 停止实时 root 轮询（离开前台或销毁时调用）。 */
    private fun stopRootPoller() {
        rootPoller?.let { mStatusHandler.removeCallbacks(it) }
        rootPoller = null
    }

    /**
     * 系统「剪贴板 / 常用语」(com.miui.phrase) 的版本号，展示在主页「系统版本」下方。
     */
    private fun phraseVersion(): String {
        val pkg = "com.miui.phrase"
        return try {
            val info: PackageInfo = packageManager.getPackageInfo(pkg, 0)
            val name = info.versionName
            if (name != null && name.trim().isNotEmpty()) {
                name.trim()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                java.lang.Long.toString(info.longVersionCode)
            } else {
                info.versionCode.toString()
            }
        } catch (t: Throwable) {
            "未知"
        }
    }

    /** 延迟确认状态：超时仍未绑定即判定为未启用（每次订阅都重置计时）。 */
    private fun scheduleStatusFallback() {
        mStatusHandler.removeCallbacksAndMessages(null)
        mStatusHandler.postDelayed({
            if (!sModuleBound) {
                applyModuleDisabled("未启用")
            }
        }, 2500)
    }

    /** 从 XposedService 读取本模块作用域并刷新 UI（binder 线程或主线程均可调用）。 */
    private fun refreshModuleStatus() {
        val svc = sXposedService
        if (svc == null) {
            mStatusHandler.post { applyModuleDisabled("未启用") }
            return
        }
        var imeCount: Int
        try {
            val scope = svc.scope
            imeCount = countInputMethodsInScope(scope)
        } catch (e: Exception) {
            imeCount = -1
        }
        val finalCount = imeCount
        mStatusHandler.post { applyModuleEnabled(finalCount) }
    }

    /** 统计作用域中属于输入法的包数量（即本模块已启用/勾选的输入法数量）。 */
    private fun countInputMethodsInScope(scope: List<String>?): Int {
        if (scope == null || scope.isEmpty()) return 0
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return 0
        return try {
            val imis = imm.inputMethodList
            if (imis == null || imis.isEmpty()) return 0
            var n = 0
            for (imi in imis) {
                val pkg = imi.packageName
                if (pkg != null && scope.contains(pkg)) n++
            }
            n
        } catch (ignored: Exception) {
            0
        }
    }

    private fun applyModuleEnabled(imeCount: Int) {
        uiStatusText.value = "已启用"
        uiStatusEnabled.value = true
        uiImeCountText.value = "已启用输入法：" + (if (imeCount < 0) "—" else imeCount)
    }

    private fun applyModuleDisabled(text: String) {
        uiStatusText.value = text
        uiStatusEnabled.value = false
        uiImeCountText.value = "已启用输入法：—"
    }

    // ---------------- 功能开关 ----------------
    private fun loadSwitchState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        uiSwImeUnlock.value = prefs.getBoolean(ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK)
        uiSwUnlimitCount.value = prefs.getBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT)
        uiSwUnlimitTime.value = prefs.getBoolean(ModulePrefs.KEY_UNLIMIT_TIME, ModulePrefs.DEFAULT_UNLIMIT_TIME)
        uiSwPanelColor.value = prefs.getBoolean(ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR)
        uiSwClipboardPin.value = prefs.getBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, ModulePrefs.DEFAULT_CLIPBOARD_PIN)
        uiUpdateDisabled.value = prefs.getBoolean(AppConfig.KEY_DISABLE_UPDATE, false)
        uiThemeMode.value = AppConfig.getThemeMode(this)
    }

    private fun onToggleImeUnlock(checked: Boolean) {
        uiSwImeUnlock.value = checked
        // 主开关由功能页按新设计弹「已启用，请重启设备 / 重启设备生效」，此处不重复弹旧提示
        saveFeaturePrefs(showReloadToast = false)
    }

    private fun onToggleUnlimitCount(checked: Boolean) {
        uiSwUnlimitCount.value = checked
        saveFeaturePrefs()
    }

    private fun onToggleUnlimitTime(checked: Boolean) {
        uiSwUnlimitTime.value = checked
        saveFeaturePrefs()
    }

    private fun onTogglePanelColor(checked: Boolean) {
        uiSwPanelColor.value = checked
        saveFeaturePrefs()
    }

    private fun onToggleClipboardPin(checked: Boolean) {
        uiSwClipboardPin.value = checked
        saveFeaturePrefs()
    }

    /**
     * 保存功能开关并推送到远程偏好。[showReloadToast] 控制是否弹「已更改，重载进程后生效」——
     * 主开关（启用全面屏键盘优化）由功能页按新设计弹「已启用，请重启设备 / 重启设备生效」，
     * 此处跳过以免重复；其余子开关仍沿用该提示（重载进程即可生效）。
     */
    private fun saveFeaturePrefs(showReloadToast: Boolean = true) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(ModulePrefs.KEY_IME_UNLOCK, uiSwImeUnlock.value)
            .putBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, uiSwUnlimitCount.value)
            .putBoolean(ModulePrefs.KEY_UNLIMIT_TIME, uiSwUnlimitTime.value)
            .putBoolean(ModulePrefs.KEY_PANEL_COLOR, uiSwPanelColor.value)
            .putBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, uiSwClipboardPin.value)
            .apply()
        pushFeaturePrefs()
        // 四个功能开关的 hook 全部装在 com.miui.phrase / 输入法进程内（进程启动时读开关），
        // 故重载进程即可生效，无需整机重启
        if (showReloadToast) {
            showSnack(getString(R.string.snack_changed_reload))
        }
        refreshEnabledFeatures()
    }

    /**
     * 把功能开关推送到 LSPosed 远程偏好（hook 侧读这里）。
     * 服务未绑定（模块未启用）时静默跳过；绑定后会再推一次，保证最终一致。
     * 远程写是 binder 调用，放后台线程避免主线程卡顿。
     */
    private fun pushFeaturePrefs() {
        val svc = sXposedService ?: return
        val local = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ime = local.getBoolean(ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK)
        val unlimit = local.getBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT)
        val unlimitTime = local.getBoolean(ModulePrefs.KEY_UNLIMIT_TIME, ModulePrefs.DEFAULT_UNLIMIT_TIME)
        val panelColor = local.getBoolean(ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR)
        val clipboardPin = local.getBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, ModulePrefs.DEFAULT_CLIPBOARD_PIN)
        Thread({
            try {
                svc.getRemotePreferences(ModulePrefs.GROUP).edit()
                    .putBoolean(ModulePrefs.KEY_IME_UNLOCK, ime)
                    .putBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, unlimit)
                    .putBoolean(ModulePrefs.KEY_UNLIMIT_TIME, unlimitTime)
                    .putBoolean(ModulePrefs.KEY_PANEL_COLOR, panelColor)
                    .putBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, clipboardPin)
                    .apply()
            } catch (t: Throwable) {
                // 远程写失败不影响本地开关状态
            }
        }, "cf-prefs-push").start()
    }

    // ---------------- 检查更新 ----------------
    private fun doCheckUpdate() {
        if (AppConfig.isUpdateDisabled(this)) {
            showSnack(getString(R.string.update_disabled_msg))
            return
        }
        val checker = UpdateChecker(this)
        checker.setMessageSink(UpdateChecker.MessageSink { showSnack(it) })
        checker.checkForUpdates()
    }

    // ---------------- 设置页：主题 / 语言 / 停用更新 ----------------
    private fun onToggleUpdateDisabled(checked: Boolean) {
        uiUpdateDisabled.value = checked
        AppConfig.setUpdateDisabled(this, checked)
    }

    /**
     * 主题选择：保存并应用（系统 Application Night Mode 触发 Activity 重建）。
     */
    private fun selectTheme(mode: Int) {
        uiThemeMode.value = mode
        AppConfig.setThemeMode(this, mode)
    }

    /** 语言选择：保存后重建 Activity 使 Locale 覆盖生效。 */
    private fun selectLanguage(mode: Int) {
        AppConfig.setLangMode(this, mode)
        // 引导期间切语言会重建 Activity：记住当前页，避免把用户踢回第一页
        if (welcomeOverlay != null) {
            sWelcomePage = uiWelcomePage.value
        }
        recreate()
    }

    // ---------------- 桌面图标显隐 ----------------
    private fun updateHideRow() {
        uiIconHidden.value = isAliasHidden()
    }

    private fun isAliasHidden(): Boolean {
        val pm = packageManager
        val alias = ComponentName(this, ALIAS_NAME)
        val state = pm.getComponentEnabledSetting(alias)
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }

    /**
     * 桌面图标开关。语义是「置为目标状态」而非「取反」。
     */
    private fun onToggleHideIcon(hide: Boolean) {
        try {
            if (hide == isAliasHidden()) return
            val pm = packageManager
            val alias = ComponentName(this, ALIAS_NAME)
            pm.setComponentEnabledSetting(
                alias,
                if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            val msg = if (hide) getString(R.string.hide_icon_hidden) else getString(R.string.hide_icon_restored)
            if (hide) {
                showSnackLong(msg)
            } else {
                showSnack(msg)
            }
        } catch (e: Exception) {
            showSnack(getString(R.string.op_failed) + e.message)
        } finally {
            updateHideRow()
        }
    }

    private fun rebootDevice() {
        if (!hasRoot()) {
            showSnackLong(getString(R.string.root_required))
            return
        }
        showSnack(getString(R.string.rebooting))
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
        } catch (e: Exception) {
            showSnack(getString(R.string.reboot_fail) + e.message)
        }
    }

    /**
     * 设置页「重启或者重载」下拉卡：0 = 重启手机，1 = 重载所有进程。
     */
    private fun onRebootOrReload(which: Int) {
        if (which == 0) {
            rebootDevice()
        } else {
            reloadAllProcesses()
        }
    }

    /**
     * 重载所有进程：把剪贴板宿主 com.miui.phrase 与当前设备安装的所有输入法进程 force-stop 掉。
     */
    private fun reloadAllProcesses() {
        if (!hasRoot()) {
            showSnackLong(getString(R.string.root_required))
            return
        }
        showSnack(getString(R.string.reloading))
        Thread {
            var err: String? = null
            try {
                // 目标进程：剪贴板/常用短语宿主 + 所有已安装输入法（按包名去重）
                val pkgs = LinkedHashSet<String>()
                pkgs.add("com.miui.phrase")
                val imm = getSystemService(InputMethodManager::class.java)
                if (imm != null) {
                    for (info in imm.inputMethodList) {
                        pkgs.add(info.packageName)
                    }
                }
                for (pkg in pkgs) {
                    Runtime.getRuntime()
                        .exec(arrayOf("su", "-c", "am force-stop " + pkg))
                        .waitFor()
                }
            } catch (e: Exception) {
                err = e.message
            }
            val fErr = err
            runOnUiThread {
                if (fErr == null) {
                    showSnackLong(getString(R.string.reload_done))
                } else {
                    showSnackLong(getString(R.string.reload_fail) + fErr)
                }
            }
        }.start()
    }

    private fun hasRoot(): Boolean {
        return runSu("id > /dev/null 2>&1\n")
    }

    // ---------------- 弹卡：您需了解（特别说明） ----------------
    private fun mustKnowAgreed(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUST_KNOW, false)
    }

    private fun markMustKnowAgreed() {
        val e = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        e.putBoolean(KEY_MUST_KNOW, true)
        e.apply()
    }

    private fun showMustKnow(mandatory: Boolean) {
        val density = resources.displayMetrics.density
        val tv = TextView(this)
        tv.text = getString(R.string.sheet_must_know_body)
        tv.setTextColor(getColor(R.color.text_primary))
        tv.textSize = 15f
        tv.setLineSpacing(6 * density, 1f)
        tv.setPadding(0, (4 * density).toInt(), 0, 0)
        BottomSheet.show(
            this, getString(R.string.sheet_must_know_title), tv, !mandatory,
            if (mandatory) getString(R.string.sheet_must_know_confirm) else null,
            Runnable { if (mandatory) markMustKnowAgreed() }
        )
    }

    // ---------------- 欢迎引导（首次进入的三页覆盖层） ----------------
    private fun welcomeDone(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WELCOME_DONE, false)
    }

    /**
     * 把三页欢迎引导挂成根布局顶层的全屏覆盖层（Compose）。
     */
    private fun showWelcomeOverlay() {
        val root = findViewById<FrameLayout>(R.id.rootLayout) ?: return
        if (welcomeOverlay != null && welcomeOverlay!!.parent === root) return

        val cv = ComposeView(this)
        root.addView(
            cv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        welcomeOverlay = cv

        bottomNav?.visibility = View.GONE
        detectRootAsync()
        // 手势提示线状态：先读一次初值，并注册实时监听
        uiWelcomeGestureLineHidden.value = isGestureLineHidden()
        registerGestureLineObserver()
        // 启动模块激活状态的轻量轮询
        startWelcomeStatusPoller()

        installWelcome(
            cv, uiWelcomePage,
            hyperOsVersion(),
            "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT,
            uiStatusEnabled, uiWelcomeRoot, uiWelcomeGestureLineHidden,
            AppConfig.getLangMode(this),
            IntAction { selectLanguage(it) },
            Runnable { finishWelcome() }
        )

        // 返回键：逐页回退，首页再按则退出
        val cb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val p = uiWelcomePage.value
                if (p > 0) {
                    uiWelcomePage.value = p - 1
                } else {
                    finish()
                }
            }
        }
        welcomeBackCallback = cb
        onBackPressedDispatcher.addCallback(this, cb)
    }

    /** 引导完成：落下标记并移除覆盖层。 */
    private fun finishWelcome() {
        val e = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        e.putBoolean(KEY_WELCOME_DONE, true)
        e.putBoolean(KEY_MUST_KNOW, true)
        e.apply()
        sWelcomePage = 0
        unregisterGestureLineObserver()
        stopWelcomeStatusPoller()

        welcomeBackCallback?.isEnabled = false
        welcomeBackCallback = null
        bottomNav?.visibility = View.VISIBLE

        // 覆盖层由 Compose 点击回调触发移除：推迟到下一帧，避免在事件分发中途拆掉 Compose 树
        val overlay = welcomeOverlay
        welcomeOverlay = null
        if (overlay != null) {
            overlay.post {
                val parent = overlay.parent
                if (parent is ViewGroup) {
                    parent.removeView(overlay)
                }
            }
        }
    }

    /** 后台探测 ROOT（su 调用可能阻塞主线程，故放到工作线程回填状态）。 */
    private fun detectRootAsync() {
        Thread({
            val root = hasRoot()
            mStatusHandler.post { uiWelcomeRoot.value = root }
        }, "welcome-root-check").start()
    }

    /**
     * 手势提示线（小白条）是否已关闭。读不到一律按「未关闭」处理。
     */
    private fun isGestureLineHidden(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, "hide_gesture_line", 0) == 1
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 注册对手势提示线开关的实时监听（仅引导展示期间）。
     */
    private fun registerGestureLineObserver() {
        if (gestureLineObserver != null) return
        val obs = object : ContentObserver(mStatusHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val seg = uri?.lastPathSegment
                if (seg == null || seg == "hide_gesture_line") {
                    uiWelcomeGestureLineHidden.value = isGestureLineHidden()
                }
            }
        }
        gestureLineObserver = obs
        contentResolver.registerContentObserver(
            Settings.Global.CONTENT_URI, true, obs
        )
    }

    /** 注销手势提示线实时监听（引导完成或 Activity 销毁时调用）。 */
    private fun unregisterGestureLineObserver() {
        gestureLineObserver?.let { contentResolver.unregisterContentObserver(it) }
        gestureLineObserver = null
    }

    /**
     * 启动引导期间的轻量状态轮询：每 WELCOME_POLL_MS 重新向 libxposed 服务查询模块激活。
     */
    private fun startWelcomeStatusPoller() {
        stopWelcomeStatusPoller()
        val r = object : Runnable {
            override fun run() {
                refreshModuleStatus()
                // 仅在引导仍展示时继续下一轮；覆盖层移除后自然停摆
                if (welcomeOverlay != null) {
                    mStatusHandler.postDelayed(this, WELCOME_POLL_MS)
                }
            }
        }
        welcomeStatusPoller = r
        mStatusHandler.postDelayed(r, WELCOME_POLL_MS)
    }

    /** 停止引导状态轮询（引导完成或 Activity 销毁时调用）。 */
    private fun stopWelcomeStatusPoller() {
        welcomeStatusPoller?.let { mStatusHandler.removeCallbacks(it) }
        welcomeStatusPoller = null
    }

    // ---------------- 弹卡：更新日志（最近几版） ----------------
    // 走柔光玻璃弹卡（GlassSheetBridge）：与功能页语言 / 主题下拉同一套玻璃材质与弹簧动画，
    // 折射源是本页内容，故必须画在页面 Compose 覆盖层里而不是独立弹窗。
    private fun showChangelog() {
        val blocks = listOf(
            "V2.0.5" to getString(R.string.changelog_v205),
            "V2.0.4" to getString(R.string.changelog_v204),
            "V2.0.3" to getString(R.string.changelog_v203),
            "V2.0.2" to getString(R.string.changelog_v202),
            "V2.0.1" to getString(R.string.changelog_v201),
            "V2.0" to getString(R.string.changelog_v20),
            "V1.4.13" to getString(R.string.changelog_v1413),
            "V1.4.12" to getString(R.string.changelog_v1412),
            "V1.4.11" to getString(R.string.changelog_v1411),
            "V1.4.10" to getString(R.string.changelog_v1410),
            "V1.4.9" to getString(R.string.changelog_v149),
            "V1.4.8" to getString(R.string.changelog_v148),
            "V1.4.7" to getString(R.string.changelog_v147),
        )
        GlassSheetBridge.show(
            GlassSheetRequest(
                title = getString(R.string.sheet_changelog_title),
                content = GlassSheetContent.Blocks(blocks),
            )
        )
    }

    // ---------------- 弹卡：向开发者捐赠（捐赠码 / 捐赠名单） ----------------
    private fun showDonateSheet() {
        // 不再带副标题：support_text 已改为「向开发者捐赠」，与行标题重复
        val actions = listOf(
            GlassSheetAction(R.drawable.ic_heart, getString(R.string.donate_qr_title)) { showDonateQr() },
            GlassSheetAction(R.drawable.ic_books, getString(R.string.donate_list_title)) { showDonateList() },
        )
        GlassSheetBridge.show(
            GlassSheetRequest(
                title = getString(R.string.sheet_donate_title),
                content = GlassSheetContent.Actions(actions),
            )
        )
    }

    private fun showDonateQr() {
        val qr = BitmapFactory.decodeResource(resources, R.drawable.donate_qrcode)
        GlassSheetBridge.show(
            GlassSheetRequest(
                title = getString(R.string.donate_qr_title),
                content = GlassSheetContent.Picture(qr, getString(R.string.donate_note)),
                actionText = getString(R.string.donate_save),
                onAction = {
                    if (saveQrToGallery(qr)) {
                        showSnackLong(getString(R.string.donate_qr_saved))
                        openWeChat()
                    }
                },
            )
        )
    }

    private fun showDonateList() {
        val donors = listOf(
            "酷安 / 匿名网友 — 5 元",
            "酷安 / 匿名网友 — 10 元",
            "酷安 / 热心用户 — 20 元",
            "微博 / 小江 — 6.6 元",
            "酷安 / 杨旻昊 — 0.82 元"
        )
        GlassSheetBridge.show(
            GlassSheetRequest(
                title = getString(R.string.donate_list_title),
                content = GlassSheetContent.Lines(donors),
            )
        )
    }

    // ---------------- 二级页面：开源许可证 ----------------
    private fun openLicenses() {
        try {
            startActivity(Intent(this, LicensesActivity::class.java))
        } catch (e: Exception) {
            showSnack(getString(R.string.open_failed))
        }
    }

    override fun onResume() {
        super.onResume()
        sCurrent = WeakReference(this)
        if (sListenerRegistered) {
            if (sModuleBound) {
                refreshModuleStatus()
            } else {
                scheduleStatusFallback()
            }
        }
        // root 身份实时监听：每次回到前台立即重查，并启动周期轮询
        refreshRootStatus()
        startRootPoller()
        // 从二级页面（如开源许可证）返回时，玻璃导航条的折射背景已被系统丢弃，
        // 主动重录一次，避免导航栏短暂「透明」直到再次点击标签才恢复
        bottomNav?.refreshBackdrop()
        // 引导展示期间：从 LSPosed / 授权管理器等返回时，重新探测 ROOT
        if (welcomeOverlay != null) {
            detectRootAsync()
        }
    }

    override fun onPause() {
        super.onPause()
        stopRootPoller()
    }

    override fun onDestroy() {
        if (sCurrent.get() === this) {
            sCurrent.clear()
        }
        // 引导仍在展示时记录当前页，供配置变更 / recreate 后落回原页
        if (welcomeOverlay != null) {
            sWelcomePage = uiWelcomePage.value
        }
        mStatusHandler.removeCallbacksAndMessages(null)
        stopRootPoller()
        unregisterGestureLineObserver()
        stopWelcomeStatusPoller()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // 玻璃弹卡优先：返回键先关弹卡，再交给 BottomSheet / 系统
        if (GlassSheetBridge.isOpen()) {
            GlassSheetBridge.close()
            return
        }
        if (BottomSheet.dismissTop()) return
        onBackPressedDispatcher.onBackPressed()
    }

    // ---------------- 工具 ----------------
    private fun saveQrToGallery(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        return try {
            val fileName = "澎湃OS剪贴板补全_打赏二维码_" + System.currentTimeMillis() + ".png"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues()
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/澎湃OS剪贴板补全"
                )

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false

                val os = contentResolver.openOutputStream(uri)!!
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                os.close()
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES + "/澎湃OS剪贴板补全"
                    ).absolutePath
                )
                if (!dir.exists()) dir.mkdirs()

                val file = File(dir, fileName)
                val os = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                os.close()

                val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                scanIntent.setData(Uri.fromFile(file))
                sendBroadcast(scanIntent)
            }
            true
        } catch (e: Exception) {
            showSnack(getString(R.string.save_fail) + e.message)
            false
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            showSnack(getString(R.string.open_failed))
        }
    }

    private fun openWeChat() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (intent != null) startActivity(intent)
        } catch (ignored: Exception) {
            // 微信未安装，忽略
        }
    }

    /** 以 root 执行命令；su 未授权、不可用或超时一律返回 false。 */
    private fun runSu(cmd: String): Boolean {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes(cmd + "\n")
            os.writeBytes("exit\n")
            os.flush()
            val finished = p.waitFor(4, TimeUnit.SECONDS)
            finished && p.exitValue() == 0
        } catch (t: Throwable) {
            false
        } finally {
            if (p != null) {
                try {
                    p.destroy()
                } catch (ignored: Throwable) {
                    // 忽略
                }
            }
        }
    }

    companion object {
        private const val ALIAS_NAME = "com.clipboardfix.LauncherAlias"
        private const val PREFS_NAME = "clipboardfix_prefs"
        private const val KEY_MUST_KNOW = "must_know_agreed"
        /** 首次进入欢迎引导（三页）是否已完成。 */
        private const val KEY_WELCOME_DONE = "welcome_done"
        /** 状态卡「标识」显隐；默认显示。 */
        private const val KEY_BADGES_VISIBLE = "status_badges_visible"
        private const val TELEGRAM_URL =
            "https://qun.qq.com/universal-share/share?ac=1&authKey=n9wFDXy4i20n15nDlUwLTGqI8AnQ%2BiyzC2XPQxpeOs2MxMYDsqRw0MNRPXYvx9WS&busi_data=eyJncm91cENvZGUiOiIxMTI0MTgwNjM1IiwidG9rZW4iOiJTSVhYbUhsYUNnWVYvb2wzVUlRb0J6SDRQMTNCdUxBMUNocUMzeU9taFpvakJRRHhZNDFBeDJIdFVQeTNZVnRmIiwidWluIjoiOTAzNzU1MzQ1In0%3D&data=qWsg9TN3ydud1Rb7MCThG73KtRkUY7UumTdXIsBBKS2W9kcpZ-W8kSgWfZFjkdqqGSxXGkUmeL9Dmb5VYsXf3A&svctype=4&tempid=h5_group_info"

        // ---- 页面切换动画参数（淡入淡出 + 方向性横移 + 轻微缩放）----
        /** 新页入场时长（略长，收尾有减速，观感更「软」）。 */
        private const val PAGE_ANIM_IN_MS = 260L
        /** 旧页离场时长（更短，避免两页长时间重叠发虚）。 */
        private const val PAGE_ANIM_OUT_MS = 180L
        /** 缩放起点：0.985 → 1。 */
        private const val PAGE_ANIM_SCALE = 0.985f

        private const val WELCOME_POLL_MS = 2000L

        /** 引导当前页下标（0/1/2）。跨 Activity 重建保留。 */
        private var sWelcomePage = 0
        /** 跨重建（切换语言/主题 recreate）保留当前 Tab。 */
        private var sLastTab = 0

        // ---- 状态卡「连续点击 10 次」隐藏/显示标识 ----
        /** 达成隐藏/显示所需的连续点击次数。 */
        private const val TAP_TARGET = 10
        /** 两次点击超过该间隔即视为中断，计数清零。 */
        private const val TAP_RESET_MS = 1500L

        // ---- 实时 root 轮询 ----
        private const val ROOT_POLL_MS = 3000L

        // ---- 模块状态（进程级）----
        private var sXposedService: XposedService? = null
        private var sModuleBound = false
        private var sListenerRegistered = false

        /** 进程内当前前台实例（弱引用：静态作用域不得强持有 Activity）。 */
        private var sCurrent: WeakReference<AboutActivity> = WeakReference(null)

        /** 进程级监听器：回调只更新静态状态，并驱动当前前台实例刷新 UI。 */
        private val sXposedListener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                sXposedService = service
                sModuleBound = true
                val a = sCurrent.get()
                if (a != null) {
                    a.pushFeaturePrefs()
                    a.refreshModuleStatus()
                }
            }

            override fun onServiceDied(service: XposedService) {
                sXposedService = null
                sModuleBound = false
                val a = sCurrent.get()
                if (a != null) {
                    a.mStatusHandler.post { a.applyModuleDisabled("未启用") }
                }
            }
        }

        /** 主 ABI + 内存页大小，如 arm64-v8a(4k)。 */
        private fun abiInfo(): String {
            val abi =
                if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.isNotEmpty())
                    Build.SUPPORTED_ABIS[0]
                else Build.CPU_ABI
            var pageSize = 4096L
            try {
                val ps = Os.sysconf(OsConstants._SC_PAGE_SIZE)
                if (ps > 0) pageSize = ps
            } catch (ignored: Throwable) {
            }
            return abi + "(" + (pageSize / 1024) + "k)"
        }

        /** HyperOS 版本（属性缺失时回落到 MIUI 版本号）。 */
        private fun hyperOsVersion(): String {
            var v = SysProps.get("ro.mi.os.version.name", "")
            if (v.isEmpty()) {
                v = SysProps.get("ro.miui.ui.version.name", "")
            }
            return v
        }
    }
}
