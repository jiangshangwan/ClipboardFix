package com.clipboardfix;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.compose.runtime.MutableState;
import androidx.compose.ui.platform.ComposeView;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import com.clipboardfix.ui.AppConfig;
import com.clipboardfix.ui.ClipboardFixUi;

/**
 * 模块主界面（LSPosed 设置入口 + 桌面入口）。
 *
 * <p>UI 自 v2.0 起迁至 Compose + Miuix：本 Activity 改继承 {@link ComponentActivity}，
 * onCreate 内经 {@link com.clipboardfix.ui.launchClipboardFixUi} 挂载 Compose 界面；
 * 所有功能逻辑（模块状态查询、偏好读写/推送、重启、隐藏图标、更新检查、各弹卡）仍在此，
 * 通过可观察状态（MutableState）与动作回调暴露给 Compose。Xposed hook 逻辑不受影响。
 */
public class AboutActivity extends ComponentActivity {

    private static final String ALIAS_NAME = "com.clipboardfix.LauncherAlias";
    private static final String PREFS_NAME = "clipboardfix_prefs";
    private static final String KEY_MUST_KNOW = "must_know_agreed";
    /** 首次进入欢迎引导（三页）是否已完成。 */
    private static final String KEY_WELCOME_DONE = "welcome_done";
    /** 状态卡「标识」（Root 标识 + 已启用的功能行）显隐；隐藏后重启仍保持隐藏，需 10 连击再显示，默认显示。 */
    private static final String KEY_BADGES_VISIBLE = "status_badges_visible";
    private static final String TELEGRAM_URL =
            "https://qun.qq.com/universal-share/share?ac=1&authKey=n9wFDXy4i20n15nDlUwLTGqI8AnQ%2BiyzC2XPQxpeOs2MxMYDsqRw0MNRPXYvx9WS&busi_data=eyJncm91cENvZGUiOiIxMTI0MTgwNjM1IiwidG9rZW4iOiJTSVhYbUhsYUNnWVYvb2wzVUlRb0J6SDRQMTNCdUxBMUNocUMzeU9taFpvakJRRHhZNDFBeDJIdFVQeTNZVnRmIiwidWluIjoiOTAzNzU1MzQ1In0%3D&data=qWsg9TN3ydud1Rb7MCThG73KtRkUY7UumTdXIsBBKS2W9kcpZ-W8kSgWfZFjkdqqGSxXGkUmeL9Dmb5VYsXf3A&svctype=4&tempid=h5_group_info";
    private static final String MIUIX_URL = "https://compose-miuix-ui.github.io/miuix/zh_CN/";

    // ---- 页面切换动画参数（淡入淡出 + 方向性横移 + 轻微缩放）----
    /** 新页入场时长（略长，收尾有减速，观感更「软」）。 */
    private static final long PAGE_ANIM_IN_MS = 260L;
    /** 旧页离场时长（更短，避免两页长时间重叠发虚）。 */
    private static final long PAGE_ANIM_OUT_MS = 180L;
    /** 缩放起点：0.985 → 1，几乎不可察但足以去掉「硬切」的生硬感。 */
    private static final float PAGE_ANIM_SCALE = 0.985f;

    // ---- Compose 可观察状态（composable 读取 .getValue() 即获得响应式刷新）----
    public final MutableState<String> uiStatusText = ClipboardFixUi.stateOf("检测中…");
    /** root 标识：本模块已被授予 su 权限时为 true，状态卡在「已启用」后展示「已Root」/「未Root」。 */
    public final MutableState<Boolean> uiRootGranted = ClipboardFixUi.stateOf(false);
    /** 已启用的功能数量（四个功能开关中为「开」的个数），状态卡底部展示「已启用的功能：X」。 */
    public final MutableState<Integer> uiEnabledFeatures = ClipboardFixUi.stateOf(0);
    /** 标识显隐开关：控制 Root 标识与「已启用的功能：X」行的可见性，默认显示。 */
    public final MutableState<Boolean> uiBadgesVisible = ClipboardFixUi.stateOf(true);
    public final MutableState<String> uiImeCountText = ClipboardFixUi.stateOf("已启用输入法：—");
    public final MutableState<Boolean> uiStatusEnabled = ClipboardFixUi.stateOf(false);
    public final MutableState<Boolean> uiSwImeUnlock = ClipboardFixUi.stateOf(ModulePrefs.DEFAULT_IME_UNLOCK);
    public final MutableState<Boolean> uiSwUnlimitCount = ClipboardFixUi.stateOf(ModulePrefs.DEFAULT_UNLIMIT_COUNT);
    public final MutableState<Boolean> uiSwPanelColor = ClipboardFixUi.stateOf(ModulePrefs.DEFAULT_PANEL_COLOR);
    public final MutableState<Boolean> uiSwClipboardPin = ClipboardFixUi.stateOf(ModulePrefs.DEFAULT_CLIPBOARD_PIN);
    public final MutableState<Boolean> uiIconHidden = ClipboardFixUi.stateOf(false);
    public final MutableState<Boolean> uiUpdateDisabled = ClipboardFixUi.stateOf(false);
    /** 主题模式（跟随系统/浅色/深色）。用可观察状态而非普通 Int：系统深浅色未变时
     *  （如「默认(浅色)」再选「浅色」）不会触发 Activity 重建，行内选中值需自行刷新。 */
    public final MutableState<Integer> uiThemeMode =
            ClipboardFixUi.stateOf(AppConfig.MODE_SYSTEM);

    // ---- 欢迎引导（首次进入的三页覆盖层）----
    /** 引导当前页下标（0/1/2）。跨 Activity 重建保留：语言切换会 recreate，不能把用户踢回第一页。 */
    private static int sWelcomePage = 0;
    /** 当前页（Compose 读写同一份状态：翻页由 Compose 写，系统返回键由 Activity 写）。 */
    public final MutableState<Integer> uiWelcomePage = ClipboardFixUi.stateOf(sWelcomePage);
    /** 是否已获得超级用户权限（su 探测耗时会阻塞，故后台线程回填）。 */
    public final MutableState<Boolean> uiWelcomeRoot = ClipboardFixUi.stateOf(false);
    /** 手势提示线（小白条）是否已关闭：读 MIUI/HyperOS 的 global 设置 hide_gesture_line。 */
    public final MutableState<Boolean> uiWelcomeGestureLineHidden = ClipboardFixUi.stateOf(false);
    private ComposeView welcomeOverlay;
    private OnBackPressedCallback welcomeBackCallback;
    /** 实时监听手势提示线开关（引导展示期间注册，完成后注销）。 */
    private ContentObserver gestureLineObserver;
    /** 引导展示期间的轻量轮询：实时重查「模块激活」状态（binder IPC 廉价），让用户在 LSPosed 里启用模块后勾选立即刷新。 */
    private Runnable welcomeStatusPoller;
    private static final long WELCOME_POLL_MS = 2000;

    // ---- 页面与导航（View 外壳 + ComposeView 页面 + 液态玻璃导航）----
    private ComposeView pageHome, pageFeature, pageAbout, pageSettings;
    private View contentContainer;
    private LiquidGlassBarView bottomNav;
    private FrameLayout navHome, navFeature, navAbout, navSettings;
    private ImageView navIconHome, navIconFeature, navIconAbout, navIconSettings;
    private TextView navLabelHome, navLabelFeature, navLabelAbout, navLabelSettings;
    private int currentTab = 0;
    /**
     * selectTab 是否已初始化过。onCreate 里的首次调用是「恢复上次页签」
     * （切主题/语言导致 Activity 重建时尤其明显），必须直接落位：
     * 若走动画，设置页会从 alpha 0 滑入，期间主页（XML 默认可见）先露一面，
     * 就是用户看到的「从主页闪一下再回到设置页」。
     */
    private boolean mTabInitialized = false;
    /** 跨重建（切换语言/主题 recreate）保留当前 Tab，避免跳回主页。 */
    private static int sLastTab = 0;

    // 模块启用状态（通过 libxposed:service 实时查询 LSPosed）
    private static final String MODULE_PKG = "com.clipboardfix";
    private final Handler mStatusHandler = new Handler(Looper.getMainLooper());

    // ---- 状态卡「连续点击 10 次」隐藏/显示标识 ----
    /** 达成隐藏/显示所需的连续点击次数。 */
    private static final int TAP_TARGET = 10;
    /** 两次点击超过该间隔即视为中断，计数清零（避免误触累积）。 */
    private static final long TAP_RESET_MS = 1500L;
    private int mTapCount = 0;
    private long mLastTapTime = 0;

    // ---- 实时 root 轮询（su 已授权时无弹窗、开销极小）----
    private static final long ROOT_POLL_MS = 3000L;
    private Runnable rootPoller;

    // ---- 模块状态（进程级）----
    // 根因：LSPosed 的 binder 在进程存活期间只推送一次（经 XposedProvider.call 推给进程），
    // 且 registerListener 是静态单订阅（覆盖式）、binder 先于首次订阅到达时暂存 mCache、
    // 首次订阅即消费并清空 mCache。深浅色切换会销毁重建 Activity，旧实现把监听器/服务/
    // 绑定标志放实例字段里，重建后重挂新监听器却永远等不到 onServiceBind，2.5s 兜底即误报「未启用」。
    // 修正：服务与绑定状态提升为进程级，监听器为进程级单例（只注册一次，永不注销）；
    // Activity 重建时直接读静态状态恢复显示，不重新检测。
    private static XposedService sXposedService;
    private static boolean sModuleBound = false;
    private static boolean sListenerRegistered = false;

    /** 进程内当前前台实例（弱引用：静态作用域不得强持有 Activity）。 */
    private static WeakReference<AboutActivity> sCurrent = new WeakReference<>(null);

    /** 进程级监听器：回调只更新静态状态，并驱动当前前台实例刷新 UI。 */
    private static final XposedServiceHelper.OnServiceListener sXposedListener =
            new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    sXposedService = service;
                    sModuleBound = true;
                    AboutActivity a = sCurrent.get();
                    if (a != null) {
                        a.pushFeaturePrefs();
                        a.refreshModuleStatus();
                    }
                }

                @Override
                public void onServiceDied(XposedService service) {
                    sXposedService = null;
                    sModuleBound = false;
                    AboutActivity a = sCurrent.get();
                    if (a != null) {
                        a.mStatusHandler.post(() -> a.applyModuleDisabled("未启用"));
                    }
                }
            };

    @Override
    protected void attachBaseContext(Context base) {
        // 语言偏好：仅为上下文覆盖 Locale（主题走系统 Application Night Mode）。
        super.attachBaseContext(AppConfig.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 沉浸式（对齐 HyperIsland）：系统栏全透明且禁用系统自动加的对比度蒙层，
        // 页面背景直接透到状态栏/手势提示线后面。
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        // 浅色模式下让状态栏/导航栏文字变深，保证可读
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        // 开关状态：从本地偏好恢复
        loadSwitchState();
        // 标识显隐：从本地偏好恢复（隐藏后重启仍保持隐藏，默认显示）
        uiBadgesVisible.setValue(
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_BADGES_VISIBLE, true));
        // 桌面图标显隐：恢复当前状态
        updateHideRow();

        // View 外壳：内容区（三页 ComposeView）+ 底部液态玻璃导航
        setContentView(R.layout.activity_about);
        bindViews();
        setupBottomNav();

        // 装配四页 Compose + Miuix 界面（状态与动作回调桥接到本 Activity）
        ClipboardFixUi.installPages(
                pageHome, pageFeature, pageAbout, pageSettings,
                uiStatusText, uiImeCountText, uiStatusEnabled, uiRootGranted,
                uiEnabledFeatures, uiBadgesVisible,
                BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE,
                DeviceNames.marketName(), "Android " + Build.VERSION.RELEASE,
                Build.VERSION.INCREMENTAL, abiInfo(),
                uiSwImeUnlock, uiSwUnlimitCount, uiSwPanelColor, uiSwClipboardPin, uiIconHidden,
                this::doCheckUpdate,
                this::onStatusCardTap,
                this::onToggleImeUnlock,
                this::onToggleUnlimitCount,
                this::onTogglePanelColor,
                this::onToggleClipboardPin,
                checked -> onToggleHideIcon(checked),
                which -> onRebootOrReload(which),
                () -> openUrl("https://www.coolapk.com/u/3019478"),
                () -> openUrl("https://weibo.com/u/3725737792"),
                () -> openUrl("https://github.com/jiangshangwan/ClipboardFix"),
                this::showChangelog,
                this::showDonateSheet,
                () -> openUrl(TELEGRAM_URL),
                this::showThanksSheet,
                () -> openUrl(MIUIX_URL),
                uiThemeMode, AppConfig.getLangMode(this), uiUpdateDisabled,
                mode -> selectTheme(mode), mode -> selectLanguage(mode), this::onToggleUpdateDisabled);

        // 模块启用状态实时查询
        initModuleStatus();

        // 初始落到上次所在 Tab（重建后保留，首次为 0=主页）
        selectTab(sLastTab);

        // 首次进入：三页欢迎引导（独立覆盖层，盖住底部导航）；
        // 已完成引导的旧版本升级用户走「您需了解」底部弹卡兜底。
        if (!welcomeDone()) {
            showWelcomeOverlay();
        } else if (!mustKnowAgreed()) {
            showMustKnow(true);
        }

        // 静默检查更新（有新版本才弹窗；已停用更新检测则跳过）
        if (!AppConfig.isUpdateDisabled(this)) {
            new UpdateChecker(this).checkSilent();
        }
    }

    private void bindViews() {
        contentContainer = findViewById(R.id.contentContainer);
        pageHome = findViewById(R.id.pageHome);
        pageFeature = findViewById(R.id.pageFeature);
        pageAbout = findViewById(R.id.pageAbout);
        pageSettings = findViewById(R.id.pageSettings);
        bottomNav = findViewById(R.id.bottomNav);
        navHome = findViewById(R.id.navHome);
        navFeature = findViewById(R.id.navFeature);
        navAbout = findViewById(R.id.navAbout);
        navSettings = findViewById(R.id.navSettings);
        navIconHome = findViewById(R.id.navIconHome);
        navIconFeature = findViewById(R.id.navIconFeature);
        navIconAbout = findViewById(R.id.navIconAbout);
        navIconSettings = findViewById(R.id.navIconSettings);
        navLabelHome = findViewById(R.id.navLabelHome);
        navLabelFeature = findViewById(R.id.navLabelFeature);
        navLabelAbout = findViewById(R.id.navLabelAbout);
        navLabelSettings = findViewById(R.id.navLabelSettings);
    }

    private void setupBottomNav() {
        // 玻璃条折射的是内容区（承载四页 ComposeView）的真实内容
        bottomNav.setBackdrop(contentContainer);
        // 顺序：主页 / 功能 / 设置 / 关于
        bottomNav.setItems(navHome, navFeature, navSettings, navAbout);
        bottomNav.setAccentColor(getColor(R.color.accent));
        bottomNav.setOnItemSelectedListener(this::selectTab);
    }

    /**
     * 切换页面（带页面切换动画）。
     *
     * 动画：新页按导航方向淡入 + 轻微横向位移 + 0.985→1 缩放（260ms 减速），
     * 旧页反向淡出并位移（180ms 加速）后 GONE——对齐参考项目的页面切换观感。
     * 两种不播动画的情况：目标就是当前页；onCreate 里恢复上次页签（见 mTabInitialized）。
     */
    private void selectTab(int idx) {
        int prev = currentTab;
        // 首次调用（onCreate 恢复页签）不播动画，直接落位，避免重建时闪现主页
        boolean animate = mTabInitialized && (idx != prev);
        mTabInitialized = true;
        currentTab = idx;
        sLastTab = idx;

        View[] pages = {pageHome, pageFeature, pageSettings, pageAbout};
        // 方向：目标在右侧（idx 更大）时新页自右滑入、旧页向左滑出；反之相反
        float dir = idx > prev ? 1f : -1f;
        float shift = 26f * getResources().getDisplayMetrics().density;
        for (int i = 0; i < pages.length; i++) {
            View p = pages[i];
            if (p == null) continue;
            if (i == idx) {
                p.animate().cancel();
                p.setVisibility(View.VISIBLE);
                p.bringToFront();
                if (animate) {
                    p.setAlpha(0f);
                    p.setTranslationX(dir * shift);
                    p.setScaleX(PAGE_ANIM_SCALE);
                    p.setScaleY(PAGE_ANIM_SCALE);
                    p.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(PAGE_ANIM_IN_MS)
                            .setInterpolator(new DecelerateInterpolator(1.6f))
                            .start();
                } else {
                    p.setAlpha(1f);
                    p.setTranslationX(0f);
                    p.setScaleX(1f);
                    p.setScaleY(1f);
                }
            } else if (p.getVisibility() != View.GONE) {
                p.animate().cancel();
                if (animate) {
                    p.animate()
                            .alpha(0f)
                            .translationX(-dir * shift)
                            .scaleX(PAGE_ANIM_SCALE)
                            .scaleY(PAGE_ANIM_SCALE)
                            .setDuration(PAGE_ANIM_OUT_MS)
                            .setInterpolator(new AccelerateInterpolator(1.2f))
                            .withEndAction(() -> {
                                p.setVisibility(View.GONE);
                                p.setAlpha(1f);
                                p.setTranslationX(0f);
                                p.setScaleX(1f);
                                p.setScaleY(1f);
                            })
                            .start();
                } else {
                    p.setVisibility(View.GONE);
                    p.setAlpha(1f);
                    p.setTranslationX(0f);
                    p.setScaleX(1f);
                    p.setScaleY(1f);
                }
            }
        }

        applyNavItem(navIconHome, navLabelHome, currentTab == 0);
        applyNavItem(navIconFeature, navLabelFeature, currentTab == 1);
        applyNavItem(navIconSettings, navLabelSettings, currentTab == 2);
        applyNavItem(navIconAbout, navLabelAbout, currentTab == 3);
        // 恢复页签（首次调用）不播动画：否则重建后导航胶囊会从主页滑到目标页
        bottomNav.setSelectedIndex(idx, animate);
    }

    private void applyNavItem(ImageView icon, TextView label, boolean selected) {
        int color = selected ? getColor(R.color.accent) : getColor(R.color.text_tertiary);
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    // ---------------- 模块启用状态（libxposed:service 实时查询，进程级状态） ----------------
    private void initModuleStatus() {
        sCurrent = new WeakReference<>(this);
        if (!sListenerRegistered) {
            XposedServiceHelper.registerListener(sXposedListener);
            sListenerRegistered = true;
        }
        if (sModuleBound) {
            refreshModuleStatus();
        } else {
            scheduleStatusFallback();
        }
        // root 标识：后台探测，结果回主线程写状态（不执行 su，避免授权弹窗）
        refreshRootStatus();
        // 已启用的功能数量：随开关变化刷新
        refreshEnabledFeatures();
    }

    /** 后台探测本模块是否被授予 root 权限，并刷新 {@link #uiRootGranted}。 */
    private void refreshRootStatus() {
        new Thread(() -> {
            final boolean root = RootCheck.isModuleRootGranted();
            mStatusHandler.post(() -> uiRootGranted.setValue(root));
        }).start();
    }

    /** 统计四个功能开关中为「开」的数量，回填 {@link #uiEnabledFeatures}。 */
    private void refreshEnabledFeatures() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int n = 0;
        if (prefs.getBoolean(ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK)) n++;
        if (prefs.getBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT)) n++;
        if (prefs.getBoolean(ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR)) n++;
        if (prefs.getBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, ModulePrefs.DEFAULT_CLIPBOARD_PIN)) n++;
        uiEnabledFeatures.setValue(n);
    }

    /**
     * 状态卡连续点击 10 次切换「标识」显隐（Root 标识 + 已启用的功能行）。
     *
     * <p>每次点击给出提示：当前为显示态则提示「连续点击10次隐藏标识」，
     * 当前为隐藏态则提示「连续点击10次显示标识」；点满 10 次后翻转 {@link #uiBadgesVisible}
     * 并提示结果。两次点击超过 {@link #TAP_RESET_MS} 视为中断、计数清零，避免误触累积。
     */
    private void onStatusCardTap() {
        long now = System.currentTimeMillis();
        if (now - mLastTapTime > TAP_RESET_MS) {
            mTapCount = 0;
        }
        mLastTapTime = now;
        mTapCount++;
        boolean visible = uiBadgesVisible.getValue();
        if (mTapCount < TAP_TARGET) {
            showSnack(visible ? getString(R.string.tap_hide_hint) : getString(R.string.tap_show_hint));
        } else {
            mTapCount = 0;
            boolean shown = !visible;
            uiBadgesVisible.setValue(shown);
            // 持久化：隐藏后重启仍保持隐藏，直到再次 10 连击显示
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_BADGES_VISIBLE, shown).apply();
            showSnack(shown ? getString(R.string.badges_shown) : getString(R.string.badges_hidden));
        }
    }

    /** 实时重查本模块 root 授权：每 {@link #ROOT_POLL_MS} 轮询一次（su 已授权时无弹窗、开销极小）。 */
    private void startRootPoller() {
        stopRootPoller();
        rootPoller = new Runnable() {
            @Override
            public void run() {
                refreshRootStatus();
                mStatusHandler.postDelayed(this, ROOT_POLL_MS);
            }
        };
        mStatusHandler.postDelayed(rootPoller, ROOT_POLL_MS);
    }

    /** 停止实时 root 轮询（离开前台或销毁时调用）。 */
    private void stopRootPoller() {
        if (rootPoller != null) {
            mStatusHandler.removeCallbacks(rootPoller);
            rootPoller = null;
        }
    }

    /** 主 ABI + 内存页大小，如 arm64-v8a(4k)。 */
    private static String abiInfo() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : String.valueOf(Build.CPU_ABI);
        long pageSize = 4096L;
        try {
            long ps = Os.sysconf(OsConstants._SC_PAGE_SIZE);
            if (ps > 0) pageSize = ps;
        } catch (Throwable ignored) {
        }
        return abi + "(" + (pageSize / 1024) + "k)";
    }

    /** 延迟确认状态：超时仍未绑定即判定为未启用（每次订阅都重置计时）。 */
    private void scheduleStatusFallback() {
        mStatusHandler.removeCallbacksAndMessages(null);
        mStatusHandler.postDelayed(() -> {
            if (!sModuleBound) {
                applyModuleDisabled("未启用");
            }
        }, 2500);
    }

    /** 从 XposedService 读取本模块作用域并刷新 UI（binder 线程或主线程均可调用）。 */
    private void refreshModuleStatus() {
        if (sXposedService == null) {
            mStatusHandler.post(() -> applyModuleDisabled("未启用"));
            return;
        }
        final XposedService svc = sXposedService;
        int imeCount;
        try {
            List<String> scope = svc.getScope();
            imeCount = countInputMethodsInScope(scope);
        } catch (Exception e) {
            imeCount = -1;
        }
        final int finalCount = imeCount;
        mStatusHandler.post(() -> applyModuleEnabled(finalCount));
    }

    /** 统计作用域中属于输入法的包数量（即本模块已启用/勾选的输入法数量）。 */
    private int countInputMethodsInScope(List<String> scope) {
        if (scope == null || scope.isEmpty()) return 0;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return 0;
        try {
            List<InputMethodInfo> imis = imm.getInputMethodList();
            if (imis == null || imis.isEmpty()) return 0;
            int n = 0;
            for (InputMethodInfo imi : imis) {
                if (imi == null) continue;
                String pkg = imi.getPackageName();
                if (pkg != null && scope.contains(pkg)) n++;
            }
            return n;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void applyModuleEnabled(int imeCount) {
        uiStatusText.setValue("已启用");
        uiStatusEnabled.setValue(true);
        uiImeCountText.setValue("已启用输入法：" + (imeCount < 0 ? "—" : imeCount));
    }

    private void applyModuleDisabled(String text) {
        uiStatusText.setValue(text);
        uiStatusEnabled.setValue(false);
        uiImeCountText.setValue("已启用输入法：—");
    }

    /**
     * 提示条（Miuix Snackbar，短时长）：浮在底部导航上方，可滑动关闭。
     * 消息经 SnackbarBridge 投递，由根布局上的 Snackbar 覆盖层渲染；线程无关。
     */
    private void showSnack(String text) {
        ClipboardFixUi.showSnack(text);
    }

    /** 提示条（Miuix Snackbar，长时长），用于需停留更久的信息。 */
    private void showSnackLong(String text) {
        ClipboardFixUi.showSnackLong(text);
    }

    // ---------------- 功能开关 ----------------
    private void loadSwitchState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        uiSwImeUnlock.setValue(prefs.getBoolean(
                ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK));
        uiSwUnlimitCount.setValue(prefs.getBoolean(
                ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT));
        uiSwPanelColor.setValue(prefs.getBoolean(
                ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR));
        uiSwClipboardPin.setValue(prefs.getBoolean(
                ModulePrefs.KEY_CLIPBOARD_PIN, ModulePrefs.DEFAULT_CLIPBOARD_PIN));
        uiUpdateDisabled.setValue(prefs.getBoolean(
                AppConfig.KEY_DISABLE_UPDATE, false));
        uiThemeMode.setValue(AppConfig.getThemeMode(this));
    }

    private void onToggleImeUnlock(boolean checked) {
        uiSwImeUnlock.setValue(checked);
        saveFeaturePrefs();
    }

    private void onToggleUnlimitCount(boolean checked) {
        uiSwUnlimitCount.setValue(checked);
        saveFeaturePrefs();
    }

    private void onTogglePanelColor(boolean checked) {
        uiSwPanelColor.setValue(checked);
        saveFeaturePrefs();
    }

    private void onToggleClipboardPin(boolean checked) {
        uiSwClipboardPin.setValue(checked);
        saveFeaturePrefs();
    }

    private void saveFeaturePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(ModulePrefs.KEY_IME_UNLOCK, uiSwImeUnlock.getValue())
                .putBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, uiSwUnlimitCount.getValue())
                .putBoolean(ModulePrefs.KEY_PANEL_COLOR, uiSwPanelColor.getValue())
                .putBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, uiSwClipboardPin.getValue())
                .apply();
        pushFeaturePrefs();
        // 四个功能开关的 hook 全部装在 com.miui.phrase / 输入法进程内（进程启动时读开关），
        // 故重载进程即可生效，无需整机重启
        refreshEnabledFeatures();
        showSnack(getString(R.string.snack_changed_reload));
    }

    /**
     * 把功能开关推送到 LSPosed 远程偏好（hook 侧读这里）。
     * 服务未绑定（模块未启用）时静默跳过；绑定后会再推一次，保证最终一致。
     * 远程写是 binder 调用，放后台线程避免主线程卡顿。
     */
    private void pushFeaturePrefs() {
        XposedService svc = sXposedService;
        if (svc == null) {
            return;
        }
        SharedPreferences local = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean ime = local.getBoolean(
                ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK);
        boolean unlimit = local.getBoolean(
                ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT);
        boolean panelColor = local.getBoolean(
                ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR);
        boolean clipboardPin = local.getBoolean(
                ModulePrefs.KEY_CLIPBOARD_PIN, ModulePrefs.DEFAULT_CLIPBOARD_PIN);
        new Thread(() -> {
            try {
                svc.getRemotePreferences(ModulePrefs.GROUP).edit()
                        .putBoolean(ModulePrefs.KEY_IME_UNLOCK, ime)
                        .putBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, unlimit)
                        .putBoolean(ModulePrefs.KEY_PANEL_COLOR, panelColor)
                        .putBoolean(ModulePrefs.KEY_CLIPBOARD_PIN, clipboardPin)
                        .apply();
            } catch (Throwable t) {
                // 远程写失败不影响本地开关状态
            }
        }, "cf-prefs-push").start();
    }

    // ---------------- 检查更新 ----------------
    private void doCheckUpdate() {
        if (AppConfig.isUpdateDisabled(this)) {
            showSnack(getString(R.string.update_disabled_msg));
            return;
        }
        UpdateChecker checker = new UpdateChecker(this);
        checker.setMessageSink(this::showSnack);
        checker.checkForUpdates();
    }

    // ---------------- 设置页：主题 / 语言 / 停用更新 ----------------
    private void onToggleUpdateDisabled(boolean checked) {
        uiUpdateDisabled.setValue(checked);
        AppConfig.setUpdateDisabled(this, checked);
        // 只写本地偏好、不重建 Activity：即时生效（无需重启手机），也无弹窗提醒
    }

    /**
     * 主题选择：保存并应用（系统 Application Night Mode 触发 Activity 重建）。
     *
     * <p>先更新可观察状态再应用：当选中的主题与当前生效的深浅色相同时
     * （如「默认(浅色)」再选「浅色」），系统不会派发配置变更、Activity 不重建，
     * 行内的选中值需要靠状态自行刷新（HyperOS 系统下拉同款问题）。
     */
    private void selectTheme(int mode) {
        uiThemeMode.setValue(mode);
        AppConfig.setThemeMode(this, mode);
    }

    /** 语言选择：保存后重建 Activity 使 Locale 覆盖生效。 */
    private void selectLanguage(int mode) {
        AppConfig.setLangMode(this, mode);
        // 引导期间切语言会重建 Activity：记住当前页，避免把用户踢回第一页
        if (welcomeOverlay != null) {
            sWelcomePage = uiWelcomePage.getValue();
        }
        recreate();
    }

    // ---------------- 桌面图标显隐 ----------------
    private void updateHideRow() {
        uiIconHidden.setValue(isAliasHidden());
    }

    private boolean isAliasHidden() {
        PackageManager pm = getPackageManager();
        ComponentName alias = new ComponentName(this, ALIAS_NAME);
        int state = pm.getComponentEnabledSetting(alias);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    }

    /**
     * 桌面图标开关（设置页 SwitchPreference）。
     *
     * 语义是「置为目标状态」而非「取反」：开关的 checked 即期望的隐藏状态，
     * 若与系统实际状态一致则直接返回（避免重复弹提示）；失败时回读实际状态，
     * 让开关自动弹回正确位置。
     */
    private void onToggleHideIcon(boolean hide) {
        try {
            if (hide == isAliasHidden()) return;
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, ALIAS_NAME);
            pm.setComponentEnabledSetting(alias,
                    hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            String msg = hide ? getString(R.string.hide_icon_hidden)
                    : getString(R.string.hide_icon_restored);
            if (hide) {
                showSnackLong(msg);
            } else {
                showSnack(msg);
            }
        } catch (Exception e) {
            showSnack(getString(R.string.op_failed) + e.getMessage());
        } finally {
            updateHideRow();
        }
    }

    private void rebootDevice() {
        if (!hasRoot()) {
            showSnackLong(getString(R.string.root_required));
            return;
        }
        showSnack(getString(R.string.rebooting));
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
        } catch (Exception e) {
            showSnack(getString(R.string.reboot_fail) + e.getMessage());
        }
    }

    /**
     * 设置页「重启或者重载」下拉卡：0 = 重启手机，1 = 重载所有进程。
     */
    private void onRebootOrReload(int which) {
        if (which == 0) {
            rebootDevice();
        } else {
            reloadAllProcesses();
        }
    }

    /**
     * 重载所有进程：把剪贴板宿主 {@code com.miui.phrase} 与当前设备安装的所有输入法
     * （{@link InputMethodManager#getInputMethodList()}）进程 force-stop 掉。
     *
     * <p>LSPosed 只在进程启动时注入，所以杀掉这些进程后，下次被系统拉起时会带着最新
     * 代码重新跑一遍 hook——等效于「不整机重启就让改动生效」。system_server 内的
     * 输入法权限放行不受影响（那条本来就要重启手机）。
     */
    private void reloadAllProcesses() {
        if (!hasRoot()) {
            showSnackLong(getString(R.string.root_required));
            return;
        }
        showSnack(getString(R.string.reloading));
        new Thread(() -> {
            String err = null;
            try {
                // 目标进程：剪贴板/常用短语宿主 + 所有已安装输入法（按包名去重）
                LinkedHashSet<String> pkgs = new LinkedHashSet<>();
                pkgs.add("com.miui.phrase");
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) {
                    for (InputMethodInfo info : imm.getInputMethodList()) {
                        pkgs.add(info.getPackageName());
                    }
                }
                for (String pkg : pkgs) {
                    Runtime.getRuntime()
                            .exec(new String[]{"su", "-c", "am force-stop " + pkg})
                            .waitFor();
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fErr = err;
            runOnUiThread(() -> {
                if (fErr == null) {
                    showSnackLong(getString(R.string.reload_done));
                } else {
                    showSnackLong(getString(R.string.reload_fail) + fErr);
                }
            });
        }).start();
    }

    private boolean hasRoot() {
        return runSu("id > /dev/null 2>&1\n");
    }

    // ---------------- 弹卡：您需了解（特别说明） ----------------
    private boolean mustKnowAgreed() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_MUST_KNOW, false);
    }

    private void markMustKnowAgreed() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        e.putBoolean(KEY_MUST_KNOW, true);
        e.apply();
    }

    private void showMustKnow(boolean mandatory) {
        float density = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.sheet_must_know_body));
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(15f);
        tv.setLineSpacing(6 * density, 1f);
        tv.setPadding(0, (int) (4 * density), 0, 0);
        BottomSheet.show(this, getString(R.string.sheet_must_know_title), tv, !mandatory,
                mandatory ? getString(R.string.sheet_must_know_confirm) : null, () -> {
                    if (mandatory) markMustKnowAgreed();
                });
    }

    // ---------------- 欢迎引导（首次进入的三页覆盖层） ----------------
    private boolean welcomeDone() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_WELCOME_DONE, false);
    }

    /**
     * 把三页欢迎引导挂成根布局顶层的全屏覆盖层（Compose）。
     *
     * 为什么用覆盖层而不是独立 Activity：引导页要展示「模块是否已在 LSPosed 激活」等实时状态，
     * 这些状态由本 Activity 的 libxposed 服务查询维护；覆盖层可直接复用，无需在别处重复一套查询。
     * 覆盖层铺满整屏并隐藏底部导航，完成后移除即可露出主界面。
     */
    private void showWelcomeOverlay() {
        FrameLayout root = findViewById(R.id.rootLayout);
        if (root == null) return;
        if (welcomeOverlay != null && welcomeOverlay.getParent() == root) return;

        ComposeView cv = new ComposeView(this);
        root.addView(cv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        welcomeOverlay = cv;

        if (bottomNav != null) bottomNav.setVisibility(View.GONE);
        detectRootAsync();
        // 手势提示线状态：先读一次初值，并注册实时监听——
        // 用户在系统设置里改动该开关后，引导页的勾选会立即刷新，无需退出 APP 重开。
        uiWelcomeGestureLineHidden.setValue(isGestureLineHidden());
        registerGestureLineObserver();
        // 启动模块激活状态的轻量轮询（覆盖 LSPosed 启用模块后、进程未重建的重查缺口）
        startWelcomeStatusPoller();

        ClipboardFixUi.installWelcome(
                cv, uiWelcomePage,
                hyperOsVersion(),
                "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT,
                uiStatusEnabled, uiWelcomeRoot, uiWelcomeGestureLineHidden,
                AppConfig.getLangMode(this),
                mode -> selectLanguage(mode),
                this::finishWelcome);

        // 返回键：逐页回退，首页再按则退出（引导不可绕过，但也不把用户困死）
        welcomeBackCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int p = uiWelcomePage.getValue();
                if (p > 0) {
                    uiWelcomePage.setValue(p - 1);
                } else {
                    finish();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, welcomeBackCallback);
    }

    /** 引导完成：落下标记（同时点亮旧版「您需了解」标记，避免再弹一次老的弹卡）并移除覆盖层。 */
    private void finishWelcome() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        e.putBoolean(KEY_WELCOME_DONE, true);
        e.putBoolean(KEY_MUST_KNOW, true);
        e.apply();
        sWelcomePage = 0;
        unregisterGestureLineObserver();
        stopWelcomeStatusPoller();

        if (welcomeBackCallback != null) {
            welcomeBackCallback.setEnabled(false);
            welcomeBackCallback = null;
        }
        if (bottomNav != null) bottomNav.setVisibility(View.VISIBLE);

        // 覆盖层由 Compose 点击回调触发移除：推迟到下一帧，避免在事件分发中途拆掉 Compose 树
        final ComposeView overlay = welcomeOverlay;
        welcomeOverlay = null;
        if (overlay != null) {
            overlay.post(() -> {
                if (overlay.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
                }
            });
        }
    }

    /** 后台探测 ROOT（su 调用可能阻塞主线程，故放到工作线程回填状态）。 */
    private void detectRootAsync() {
        new Thread(() -> {
            boolean root = hasRoot();
            mStatusHandler.post(() -> uiWelcomeRoot.setValue(root));
        }, "welcome-root-check").start();
    }

    /** HyperOS 版本（属性缺失时回落到 MIUI 版本号，读到空串则由 UI 显示「未检测到」）。 */
    private static String hyperOsVersion() {
        String v = SysProps.get("ro.mi.os.version.name", "");
        if (v == null || v.isEmpty()) {
            v = SysProps.get("ro.miui.ui.version.name", "");
        }
        return v == null ? "" : v;
    }

    /**
     * 手势提示线（小白条）是否已关闭。
     *
     * <p>MIUI / HyperOS 把「手势提示线」的显隐存在 global 设置 <code>hide_gesture_line</code>：
     * 1 = 已隐藏（即「关闭手势提示线」）、0 = 显示。该键与「设置 → 桌面 → 系统导航方式 →
     * 显示手势提示线」「更多设置 → 全面屏 → 隐藏手势提示线」是同一个开关。
     *
     * <p>读不到（键不存在 / 读取异常 / 非 MIUI 系统）一律按「未关闭」处理——宁可少打一个勾，
     * 也不误报「已关闭」。
     */
    private boolean isGestureLineHidden() {
        try {
            return Settings.Global.getInt(getContentResolver(), "hide_gesture_line", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 注册对手势提示线开关的实时监听。
     *
     * <p>监听 {@link Settings.Global#CONTENT_URI}（notifyForDescendants=true），
     * 任何 global 设置变更都会回调；仅当变更键为 {@code hide_gesture_line} 时（或无法分辨具体键时）
     * 重新读取并刷新 {@link #uiWelcomeGestureLineHidden}，驱动 Compose 检查项即时重绘。
     * 监听通过主线程 Handler 派发，更新 Compose 状态天然在主线程。
     *
     * <p>仅在欢迎引导展示期间注册；对应注销见 {@link #unregisterGestureLineObserver()}。
     */
    private void registerGestureLineObserver() {
        if (gestureLineObserver != null) return;
        gestureLineObserver = new ContentObserver(mStatusHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                // 模糊匹配：拿不到具体键（部分 ROM 回调基 URI）也刷新，避免漏更；
                // 命中 hide_gesture_line 必然刷新。读取一次 int 成本极低，无性能负担。
                String seg = uri != null ? uri.getLastPathSegment() : null;
                if (seg == null || "hide_gesture_line".equals(seg)) {
                    uiWelcomeGestureLineHidden.setValue(isGestureLineHidden());
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.CONTENT_URI, true, gestureLineObserver);
    }

    /** 注销手势提示线实时监听（引导完成或 Activity 销毁时调用）。 */
    private void unregisterGestureLineObserver() {
        if (gestureLineObserver != null) {
            getContentResolver().unregisterContentObserver(gestureLineObserver);
            gestureLineObserver = null;
        }
    }

    /**
     * 启动引导期间的轻量状态轮询：每 {@link #WELCOME_POLL_MS} 重新向 libxposed 服务查询模块激活 /
     * 作用域（廉价 binder IPC），使「在 LSPosed 激活模块」一项在用户于 LSPosed 中操作后即时反映，
     * 无需退出 APP 重开。轮询仅当引导覆盖层展示期间运行，避免无谓开销；对应停止见
     * {@link #stopWelcomeStatusPoller()}。
     */
    private void startWelcomeStatusPoller() {
        stopWelcomeStatusPoller();
        welcomeStatusPoller = new Runnable() {
            @Override
            public void run() {
                refreshModuleStatus();
                // 仅在引导仍展示时继续下一轮；覆盖层移除后自然停摆
                if (welcomeOverlay != null) {
                    mStatusHandler.postDelayed(this, WELCOME_POLL_MS);
                }
            }
        };
        mStatusHandler.postDelayed(welcomeStatusPoller, WELCOME_POLL_MS);
    }

    /** 停止引导状态轮询（引导完成或 Activity 销毁时调用）。 */
    private void stopWelcomeStatusPoller() {
        if (welcomeStatusPoller != null) {
            mStatusHandler.removeCallbacks(welcomeStatusPoller);
            welcomeStatusPoller = null;
        }
    }

    // ---------------- 弹卡：模块状态（更新说明） ----------------
    private void showStatusSheet() {
        float density = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.sheet_status_body));
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(15f);
        tv.setLineSpacing(6 * density, 1f);
        tv.setPadding(0, (int) (4 * density), 0, 0);
        BottomSheet.show(this, getString(R.string.sheet_status_title), tv, true, null, null);
    }

    // ---------------- 弹卡：更新日志（最近几版） ----------------
    private void showChangelog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(BottomSheet.buildVersionBlock(this, "V2.0",
                getString(R.string.changelog_v20)));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.13",
                getString(R.string.changelog_v1413)));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.12",
                getString(R.string.changelog_v1412)));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.11",
                getString(R.string.changelog_v1411)));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.10",
                getString(R.string.changelog_v1410)));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.9",
                getString(R.string.changelog_v149)));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.8",
                getString(R.string.changelog_v148)));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.7",
                getString(R.string.changelog_v147)));
        BottomSheet.show(this, getString(R.string.sheet_changelog_title), content, true, null, null);
    }

    // ---------------- 弹卡：为爱发电（捐赠码 / 捐赠名单） ----------------
    private void showDonateSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(sheetRow(R.drawable.ic_heart, getString(R.string.donate_qr_title),
                getString(R.string.support_text), v -> showDonateQr()));
        content.addView(sheetDivider());
        content.addView(sheetRow(R.drawable.ic_books, getString(R.string.donate_list_title),
                getString(R.string.support_text), v -> showDonateList()));
        BottomSheet.show(this, getString(R.string.sheet_donate_title), content, true, null, null);
    }

    private void showDonateQr() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        Bitmap qr = BitmapFactory.decodeResource(getResources(), R.drawable.donate_qrcode);
        ImageView img = new ImageView(this);
        img.setImageBitmap(qr);
        int size = Math.round(220 * density);
        img.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        img.setAdjustViewBounds(true);
        content.addView(img);

        TextView note = new TextView(this);
        note.setText(getString(R.string.donate_note));
        note.setTextColor(getColor(R.color.text_tertiary));
        note.setTextSize(14f);
        note.setGravity(android.view.Gravity.CENTER);
        note.setPadding(0, Math.round(12 * density), 0, 0);
        content.addView(note);

        BottomSheet.show(this, getString(R.string.donate_qr_title), content, true, getString(R.string.donate_save), () -> {
            if (saveQrToGallery(qr)) {
                showSnackLong(getString(R.string.donate_qr_saved));
                openWeChat();
            }
        });
    }

    private void showDonateList() {
        float density = getResources().getDisplayMetrics().density;
        String[] donors = {
                "酷安 / 匿名网友 — 5 元",
                "酷安 / 匿名网友 — 10 元",
                "酷安 / 热心用户 — 20 元",
                "微博 / 小江 — 6.6 元",
                "酷安 / 杨旻昊 — 0.82 元"
        };
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        for (String d : donors) {
            TextView t = new TextView(this);
            t.setText(d);
            t.setTextColor(getColor(R.color.text_primary));
            t.setTextSize(16f);
            t.setPadding(0, Math.round(10 * density), 0, Math.round(10 * density));
            content.addView(t);
            content.addView(sheetDivider());
        }
        BottomSheet.show(this, getString(R.string.donate_list_title), content, true, null, null);
    }

    // ---------------- 弹卡：致谢名单（蓝字可点击） ----------------
    private void showThanksSheet() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView link = new TextView(this);
        link.setText(getString(R.string.thanks_miui_ime));
        link.setTextColor(getColor(R.color.text_link));
        link.setTextSize(16f);
        link.setTypeface(null, Typeface.BOLD);
        link.setPadding(0, Math.round(4 * density), 0, Math.round(4 * density));
        link.setOnClickListener(v -> openUrl("https://github.com/RC1844/MIUI_IME_Unlock"));
        content.addView(link);

        BottomSheet.show(this, getString(R.string.sheet_thanks_title), content, true, null, null);
    }

    // ---------------- 弹卡内通用行 / 分隔线 ----------------
    private View sheetRow(int iconRes, String title, String subtitle, View.OnClickListener onClick) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Math.round(64 * density));
        row.setPadding(Math.round(4 * density), Math.round(14 * density),
                Math.round(4 * density), Math.round(14 * density));
        row.setOnClickListener(onClick);

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Math.round(24 * density), Math.round(24 * density));
        iconLp.setMargins(0, 0, Math.round(16 * density), 0);
        icon.setLayoutParams(iconLp);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColor(R.color.text_secondary));
        row.addView(icon);

        LinearLayout textWrap = new LinearLayout(this);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(getColor(R.color.text_primary));
        t1.setTextSize(17f);
        textWrap.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(subtitle);
        t2.setTextColor(getColor(R.color.text_tertiary));
        t2.setTextSize(14f);
        t2.setPadding(0, Math.round(4 * density), 0, 0);
        textWrap.addView(t2);

        row.addView(textWrap);

        ImageView chevron = new ImageView(this);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(24 * density), Math.round(24 * density)));
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setColorFilter(getColor(R.color.text_secondary));
        row.addView(chevron);

        return row;
    }

    private View sheetDivider() {
        float density = getResources().getDisplayMetrics().density;
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(Math.round(4 * density), 0, Math.round(4 * density), 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(getColor(R.color.divider));
        return v;
    }

    @Override
    protected void onResume() {
        super.onResume();
        sCurrent = new WeakReference<>(this);
        if (sListenerRegistered) {
            if (sModuleBound) {
                refreshModuleStatus();
            } else {
                scheduleStatusFallback();
            }
        }
        // root 身份实时监听：每次回到前台立即重查，并启动周期轮询（离开前台时停止）。
        refreshRootStatus();
        startRootPoller();
        // 引导展示期间：从 LSPosed / 授权管理器等返回时，重新探测 ROOT（su 已设 4s 短超时，
        // 不会再卡 10s+），让「超级用户权限」一项即时刷新。
        if (welcomeOverlay != null) {
            detectRootAsync();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRootPoller();
    }

    @Override
    protected void onDestroy() {
        if (sCurrent.get() == this) {
            sCurrent.clear();
        }
        // 引导仍在展示时记录当前页，供配置变更 / recreate 后落回原页
        if (welcomeOverlay != null) {
            sWelcomePage = uiWelcomePage.getValue();
        }
        mStatusHandler.removeCallbacksAndMessages(null);
        stopRootPoller();
        unregisterGestureLineObserver();
        stopWelcomeStatusPoller();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (BottomSheet.dismissTop()) return;
        getOnBackPressedDispatcher().onBackPressed();
    }

    // ---------------- 工具 ----------------
    private boolean saveQrToGallery(Bitmap bitmap) {
        if (bitmap == null) return false;
        try {
            String fileName = "澎湃OS剪贴板补全_打赏二维码_" + System.currentTimeMillis() + ".png";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/澎湃OS剪贴板补全");

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;

                OutputStream os = getContentResolver().openOutputStream(uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
            } else {
                java.io.File dir = new java.io.File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES + "/澎湃OS剪贴板补全").getAbsolutePath());
                if (!dir.exists()) dir.mkdirs();

                java.io.File file = new java.io.File(dir, fileName);
                OutputStream os = new java.io.FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();

                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(file));
                sendBroadcast(scanIntent);
            }
            return true;
        } catch (Exception e) {
            showSnack(getString(R.string.save_fail) + e.getMessage());
            return false;
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showSnack(getString(R.string.open_failed));
        }
    }

    private void openWeChat() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (intent != null) startActivity(intent);
        } catch (Exception ignored) {
            // 微信未安装，忽略
        }
    }

    /** 以 root 执行命令；su 未授权、不可用或超时一律返回 false。
     *  <p>超时从 20s 收紧到 4s：曾经「等待 su 授权」会阻塞 10+ 秒才刷新引导页的 ROOT 勾选，
     *  现改为快速返回（未授权/超时即判 false），配合 onResume 重探测实现实时刷新。 */
    private boolean runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            boolean finished = p.waitFor(4, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }
}
