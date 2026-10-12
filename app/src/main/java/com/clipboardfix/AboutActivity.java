package com.clipboardfix;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AboutActivity extends Activity {

    private static final String ALIAS_NAME = "com.clipboardfix.LauncherAlias";
    private static final String PREFS_NAME = "clipboardfix_prefs";
    private static final String KEY_MUST_KNOW = "must_know_agreed";

    private View pageHome, pageFeature, pageAbout;
    private LiquidGlassBarView bottomNav;
    private FrameLayout navHome, navFeature, navAbout;
    private ImageView navIconHome, navIconFeature, navIconAbout;
    private TextView navLabelHome, navLabelFeature, navLabelAbout;
    private int currentTab = 0;

    private TextView tvHideIcon, tvHideIconSub;
    private Switch mSwImeUnlock, mSwUnlimitCount, mSwPanelColor;
    private TextView mSnackView;

    // 模块启用状态（通过 libxposed:service 实时查询 LSPosed）
    private static final String MODULE_PKG = "com.clipboardfix";
    private TextView tvStatus, tvImeCount;
    private ImageView ivStatusMark;
    private final Handler mStatusHandler = new Handler(Looper.getMainLooper());

    // 关于页：头部区高度（运行时按 @dimen/about_header_height 设置给占位与渐变带）
    private int mAboutSpacerPx = 0;
    // 关于页卡片（背景为 card_bg_about 的 LinearLayout），供滚动时按位置调透明度
    private final List<View> mAboutCards = new ArrayList<>();
    // 关于页极光背景视图：滚动时整体淡出（露出纯表面色），对齐 HyperCeiler 关于页观感
    private AboutBgView mAboutBg;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

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

        // 状态栏 inset 手动处理：根布局不吃 inset（内容区从屏幕最顶铺到最底），
        // 改为给三个页面 ScrollView 各自打顶部 padding，且 clipToPadding=false——
        // 初始时标题在状态栏图标下方，滚动后内容从透明状态栏底下穿过（对齐 HyperIsland）。
        // 底部不打 padding——玻璃条自身的 inset 已让它悬浮在手势提示线上方。
        ViewGroup content = findViewById(R.id.contentContainer);
        for (int i = 0; i < content.getChildCount(); i++) {
            View page = content.getChildAt(i);
            // 关于页单独处理：其动态渐变背景要全屏出血（顶到状态栏），页面根不能整体吃 inset。
            if (page.getId() == R.id.pageAbout) {
                continue;
            }
            page.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(v.getPaddingLeft(), insets.getSystemWindowInsetTop(),
                        v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
        }

        bindViews();
        setupBottomNav();
        setupHome();
        setupFeature();
        setupAbout();

        // 初始状态栏颜色跟随主页背景
        applyStatusBarForTab(0);

        selectTab(0);

        // 首次启动强制阅读「您需了解」弹卡（不可点遮罩关闭，须点「我已知悉」）
        if (!mustKnowAgreed()) {
            showMustKnow(true);
        }

        // 静默检查更新（有新版本才弹窗）
        new UpdateChecker(this).checkSilent();
    }

    private void bindViews() {
        pageHome = findViewById(R.id.pageHome);
        pageFeature = findViewById(R.id.pageFeature);
        pageAbout = findViewById(R.id.pageAbout);
        bottomNav = findViewById(R.id.bottomNav);
        // 玻璃条折射的是内容区（三个页面）的真实内容
        bottomNav.setBackdrop(findViewById(R.id.contentContainer));
        navHome = findViewById(R.id.navHome);
        navFeature = findViewById(R.id.navFeature);
        navAbout = findViewById(R.id.navAbout);
        navIconHome = findViewById(R.id.navIconHome);
        navIconFeature = findViewById(R.id.navIconFeature);
        navIconAbout = findViewById(R.id.navIconAbout);
        navLabelHome = findViewById(R.id.navLabelHome);
        navLabelFeature = findViewById(R.id.navLabelFeature);
        navLabelAbout = findViewById(R.id.navLabelAbout);
    }

    private void setupBottomNav() {
        // 玻璃条自己接管触摸：点击切换 + 按住左右拖动切换。
        // 子 item 不再设置 OnClickListener，避免与拖动冲突。
        bottomNav.setItems(navHome, navFeature, navAbout);
        bottomNav.setAccentColor(getColor(R.color.accent));
        bottomNav.setOnItemSelectedListener(this::selectTab);
    }

    private void selectTab(int idx) {
        currentTab = idx;
        pageHome.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageFeature.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);

        // 选中项由玻璃条内的液滴滑动指示，图标与文字转为强调蓝（对应 PPT 的选中态）
        applyNavItem(navIconHome, navLabelHome, currentTab == 0);
        applyNavItem(navIconFeature, navLabelFeature, currentTab == 1);
        applyNavItem(navIconAbout, navLabelAbout, currentTab == 2);
        bottomNav.setSelectedIndex(idx, true);

        // 切到关于页时状态栏染成渐变顶部色，与头部背景无缝衔接；其他页用页面背景色
        applyStatusBarForTab(idx);

        // 切到「关于」页后页面已可见、完成布局，重算占位/渐变带高度（用真实测量高度，居中更准）
        if (idx == 2) {
            final View aboutPage = findViewById(R.id.pageAbout);
            if (aboutPage != null) {
                aboutPage.post(() -> applyAboutInsets(statusBarTop(aboutPage)));
            }
        }
    }

    /** 状态栏保持透明（沉浸式），各 Tab 不再单独染色。 */
    private void applyStatusBarForTab(int idx) {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
    }

    private void applyNavItem(ImageView icon, TextView label, boolean selected) {
        int color = selected ? getColor(R.color.accent) : getColor(R.color.text_tertiary);
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    // ---------------- 主页 ----------------
    private void setupHome() {
        // 模块状态 + 已启用输入法数量（真实检测，见 initModuleStatus）
        tvStatus = findViewById(R.id.tvStatus);
        tvImeCount = findViewById(R.id.tvImeCount);
        ivStatusMark = findViewById(R.id.ivStatusMark);
        tvStatus.setText("检测中…");
        tvStatus.setTextColor(getColor(R.color.text_tertiary));
        tvImeCount.setText("已启用输入法：—");

        initModuleStatus();

        // 模块状态卡：整卡可按压反馈（缩放下陷 + 跟随触摸点的 3D 倾斜，弹簧回弹）。
        // 监听落在整张卡上，点左上角或右下角都生效。
        attachCardPressFeedback(findViewById(R.id.cardStatus));

        ((TextView) findViewById(R.id.tvAppVersion)).setText(BuildConfig.VERSION_NAME);
        ((TextView) findViewById(R.id.tvBuildDate)).setText(BuildConfig.BUILD_DATE);
        ((TextView) findViewById(R.id.tvDeviceModel)).setText(Build.BRAND + " " + Build.MODEL);
        ((TextView) findViewById(R.id.tvSystemVersion)).setText(Build.VERSION.INCREMENTAL);

        findViewById(R.id.rowCheckUpdate).setOnClickListener(v -> {
            UpdateChecker checker = new UpdateChecker(AboutActivity.this);
            checker.setMessageSink(this::showSnack);
            checker.checkForUpdates();
        });

        // 您需了解：打开「特别说明」弹卡（非强制，可点遮罩关闭）
        findViewById(R.id.rowMustKnow).setOnClickListener(v -> showMustKnow(false));
    }

    /**
     * 主页「模块状态」卡的按压反馈（纯 3D 倾斜、不缩放）：
     * 枢轴放在「触摸点对角」的那一角 —— 被按的一角向屏幕内下沉，对角保持不动，
     * 因此卡片的整体外缘不会位移/外凸（此前额外做了缩放，会显得「边缘被挤出去」）。
     * 触摸落在整张卡上，点左上角或右下角都有对应的倾斜。
     */
    private void attachCardPressFeedback(final View card) {
        // X/Y 各一条弹簧：临界阻尼（无回弹过冲，松开后不会反向越过原位）。
        final Spring springX = new Spring(1.0f, 400f, 0.001f, 0f);
        final Spring springY = new Spring(1.0f, 400f, 0.001f, 0f);
        // 最大倾斜角（度）：本卡较宽，绕一角旋转的摆幅正比于宽/高，取小角度避免幅度过大、越出边缘。
        final float tiltAmount = 3f;

        final Choreographer choreo = Choreographer.getInstance();
        final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
            private long mLast = 0; // 上一帧 vsync 时间（ns）
            @Override
            public void doFrame(long frameTimeNanos) {
                float dt = (mLast == 0) ? (1f / 60f) : (frameTimeNanos - mLast) / 1e9f;
                mLast = frameTimeNanos;
                boolean rx = springX.update(dt);
                boolean ry = springY.update(dt);
                card.setRotationX(springX.value());
                card.setRotationY(springY.value());
                if (rx || ry) {
                    choreo.postFrameCallback(this);
                } else {
                    mLast = 0;
                }
            }
        };

        card.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int w = card.getWidth();
                    int h = card.getHeight();
                    if (w <= 0 || h <= 0) return false;
                    // 透视相机距离：取大于卡片长边（Android 建议值），得到平缓的 3D 透视、无鱼眼外凸。
                    card.setCameraDistance(Math.max(w, h) * 3f);
                    float x = event.getX();
                    float y = event.getY();
                    // 枢轴 = 触摸点的对角：被按侧下沉、对角保持不动（卡片外缘不位移）
                    card.setPivotX((x < w * 0.5f) ? w : 0f);
                    card.setPivotY((y < h * 0.5f) ? h : 0f);
                    springX.animateTo((y < h * 0.5f) ? tiltAmount : -tiltAmount);
                    springY.animateTo((x < w * 0.5f) ? -tiltAmount : tiltAmount);
                    choreo.removeFrameCallback(frame);
                    choreo.postFrameCallback(frame);
                    return true; // 成为触摸目标，后续 move/up 继续收到
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    springX.animateTo(0f); // 倾斜回正
                    springY.animateTo(0f);
                    choreo.removeFrameCallback(frame);
                    choreo.postFrameCallback(frame);
                    return true;
                }
                default:
                    return false; // 不消费移动等事件，ScrollView 仍可正常滚动
            }
        });
    }

    // ---------------- 模块启用状态（libxposed:service 实时查询，进程级状态） ----------------
    /**
     * 模块启用状态（libxposed:service 实时查询）。
     * LSPosed 仅对已启用模块回传 binder：能拿到 XposedService 即代表已启用；
     * getScope() 返回作用域包名，过滤出输入法即「已启用输入法数量」。
     * 监听器为进程级单例（只注册一次）：binder 回调只更新静态状态并驱动前台实例刷新，
     * 捕获「在 LSPosed 内开关模块」的实时变化。
     * Activity 因深浅色切换等原因销毁重建时，直接从进程级状态恢复显示——
     * 已绑定则立即「已启用」，不重新检测，也不受超时兜底误报影响。
     */
    private void initModuleStatus() {
        sCurrent = new WeakReference<>(this);
        if (!sListenerRegistered) {
            XposedServiceHelper.registerListener(sXposedListener);
            sListenerRegistered = true;
        }
        if (sModuleBound) {
            // 进程内早已拿到 binder（Activity 重建路径，如深浅色切换）：立即恢复显示
            refreshModuleStatus();
        } else {
            // 冷启动：等待 onServiceBind；超时未绑定则给出明确结论
            scheduleStatusFallback();
        }
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
        tvStatus.setText("已启用");
        tvStatus.setTextColor(getColor(R.color.status_green_solid));
        ivStatusMark.setImageResource(R.drawable.ic_status_enabled);
        tvImeCount.setText("已启用输入法：" + (imeCount < 0 ? "—" : imeCount));
    }

    private void applyModuleDisabled(String text) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getColor(R.color.status_red));
        ivStatusMark.setImageResource(R.drawable.ic_status_disabled);
        tvImeCount.setText("已启用输入法：—");
    }

    /**
     * 深色胶囊提示（对齐 HyperIsland 的检查更新样式）：浮在导航栏上方，
     * 淡入停留 2 秒后淡出。替代系统 Toast / 进度弹窗。
     */
    private void showSnack(String text) {
        FrameLayout root = (FrameLayout) findViewById(android.R.id.content);
        if (mSnackView != null) {
            root.removeView(mSnackView);
            mSnackView = null;
        }
        float density = getResources().getDisplayMetrics().density;
        TextView snack = new TextView(this);
        snack.setText(text);
        snack.setTextColor(0xFFFFFFFF);
        snack.setTextSize(15f);
        snack.setGravity(android.view.Gravity.CENTER);
        int padV = Math.round(14 * density);
        int padH = Math.round(24 * density);
        snack.setPadding(padH, padV, padH, padV);
        snack.setBackgroundResource(R.drawable.snack_bg);
        snack.setElevation(8f * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Math.round(110 * density);
        snack.setAlpha(0f);
        root.addView(snack, lp);
        mSnackView = snack;
        snack.animate().alpha(1f).setDuration(180).start();
        snack.postDelayed(() -> snack.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> {
                    root.removeView(snack);
                    if (mSnackView == snack) {
                        mSnackView = null;
                    }
                }).start(), 2000);
    }

    // ---------------- 功能 ----------------
    private void setupFeature() {
        tvHideIcon = findViewById(R.id.tvHideIcon);
        tvHideIconSub = findViewById(R.id.tvHideIconSub);
        updateHideRow();

        findViewById(R.id.rowHideIcon).setOnClickListener(v -> toggleLauncherIcon());
        findViewById(R.id.rowReboot).setOnClickListener(v -> rebootDevice());
        setupFeatureSwitches();
    }

    /** 功能开关：本地镜像立即生效于 UI，同时推送到 LSPosed 远程偏好供 hook 侧读取。 */
    private void setupFeatureSwitches() {
        mSwImeUnlock = findViewById(R.id.switchImeUnlock);
        mSwUnlimitCount = findViewById(R.id.switchUnlimitCount);
        mSwPanelColor = findViewById(R.id.switchPanelColor);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mSwImeUnlock.setChecked(prefs.getBoolean(
                ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK));
        mSwUnlimitCount.setChecked(prefs.getBoolean(
                ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT));
        mSwPanelColor.setChecked(prefs.getBoolean(
                ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR));

        mSwImeUnlock.setOnCheckedChangeListener((v, checked) -> saveFeaturePrefs());
        mSwUnlimitCount.setOnCheckedChangeListener((v, checked) -> saveFeaturePrefs());
        mSwPanelColor.setOnCheckedChangeListener((v, checked) -> saveFeaturePrefs());
        // 整行可点，与点开关等效
        findViewById(R.id.rowImeUnlock).setOnClickListener(v -> mSwImeUnlock.toggle());
        findViewById(R.id.rowUnlimitCount).setOnClickListener(v -> mSwUnlimitCount.toggle());
        findViewById(R.id.rowPanelColor).setOnClickListener(v -> mSwPanelColor.toggle());
    }

    private void saveFeaturePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(ModulePrefs.KEY_IME_UNLOCK, mSwImeUnlock.isChecked())
                .putBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, mSwUnlimitCount.isChecked())
                .putBoolean(ModulePrefs.KEY_PANEL_COLOR, mSwPanelColor.isChecked())
                .apply();
        pushFeaturePrefs();
        showSnack("已保存，重启手机后生效");
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
        new Thread(() -> {
            try {
                svc.getRemotePreferences(ModulePrefs.GROUP).edit()
                        .putBoolean(ModulePrefs.KEY_IME_UNLOCK, ime)
                        .putBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, unlimit)
                        .putBoolean(ModulePrefs.KEY_PANEL_COLOR, panelColor)
                        .apply();
            } catch (Throwable t) {
                // 远程写失败不影响本地开关状态
            }
        }, "cf-prefs-push").start();
    }

    private void updateHideRow() {
        if (isAliasHidden()) {
            tvHideIcon.setText("显示桌面图标");
            tvHideIconSub.setText("当前已隐藏，点击恢复");
        } else {
            tvHideIcon.setText("隐藏桌面图标");
            tvHideIconSub.setText("LSPosed 内仍可打开模块设置");
        }
    }

    private void rebootDevice() {
        if (!hasRoot()) {
            Toast.makeText(this, "需要 ROOT 权限才能重启手机", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "即将重启手机…", Toast.LENGTH_SHORT).show();
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
        } catch (Exception e) {
            Toast.makeText(this, "重启失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasRoot() {
        return runSu("id > /dev/null 2>&1\n");
    }

    // ---------------- 关于 ----------------
    private void setupAbout() {
        ((TextView) findViewById(R.id.tvVersion)).setText("V" + BuildConfig.VERSION_NAME);

        // 「关于」页视觉：
        //  ① AboutBgView —— 流体极光铺满整屏（上亮下暗连续渐变），卡片半透明时透出流光；
        //  ② aboutLogoLayer —— 固定 LOGO 层（不进入滚动容器，不随页面位移），落在屏幕上部约 1/5 处；
        //  ③ aboutScroll —— 透明滚动内容，首项透明占位透出 LOGO/极光，滚动后卡片升起覆盖。
        //  滚动联动（见 applyAboutScroll）：LOGO 在卡片升到 LOGO 区前淡出完毕（不重叠）；
        //  卡片仅在覆盖 LOGO 区时半透明（透流光），离开/越过后恢复不透明。
        final AboutBgView aboutBg = findViewById(R.id.aboutBg);
        mAboutBg = aboutBg;
        final View aboutScroll = findViewById(R.id.aboutScroll);

        final TextView tvAppTitle = findViewById(R.id.tvAppTitle);
        final View logoBlock = findViewById(R.id.logoBlock);

        // 模块名「跟随流光照射变色」：把同一段极光 AGSL 挂到标题的 Paint 上，
        // 每个文字像素取到「它正下方背景极光」的颜色（而非整行一个纯色自己循环）。
        // API 33 以下无 RuntimeShader 时，AboutBgView 内部自动退回单色循环。
        if (aboutBg != null && tvAppTitle != null) {
            aboutBg.setTitleView(tvAppTitle);
        }

        // LOGO 图标同样「跟随流光」（图标与标题用同一套背景混色）：
        // 图标素材为单色剪影，由 AboutLogoView 用背景同源的极光着色器染色。
        final AboutLogoView ivLogo = findViewById(R.id.ivLogo);
        if (aboutBg != null && ivLogo != null) {
            aboutBg.setLogoView(ivLogo);
        }

        // 关于页滚动联动（对齐 HyperCeiler 关于页观感）：
        //  · 固定 LOGO 层：在卡片升到 LOGO 区之前就完全淡出（含轻微缩放），
        //    消除「半透明卡片滚动到 LOGO 处、LOGO 文字透出」的重叠感；
        //  · 卡片：背景不透明度随其相对 LOGO 区的位置变化——正覆盖 LOGO 区时降到 minCardAlpha
        //    （透出背后流光），离开 LOGO 区（初始在下方 / 继续上滑越过）恢复 1.0 不透明，
        //    解决「卡片一直处于半透明状态」的问题。
        if (aboutScroll instanceof android.widget.ScrollView) {
            final float dp = getResources().getDisplayMetrics().density;
            final int logoFadeStart = Math.round(120f * dp);
            final int logoFadeEnd = Math.round(185f * dp);
            // 背景极光淡出距离：很短（≈头部 LOGO 区高），划上去很快就没有流光（对齐 HyperCeiler：
            // 流光只在顶部，划上去即消失、背景变纯色），而不是拖到首卡升顶才淡完。
            final int auroraFadeEnd = Math.round(200f * dp);
            // 卡片在「顶部/流光区」半透明（极光透射到卡片），上滑离开后渐变为实心面
            final int cardRevealEnd = Math.round(480f * dp);   // 首卡升到屏幕顶时完成变实心
            // 顶部最低不透明度：深色下更低（更透）→ 卡片更明显地承接背后极光（「被照射」的玻璃感）；
            // 浅色维持 0.5。
            int uiNight = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            final float minCardAlpha = (uiNight == Configuration.UI_MODE_NIGHT_YES) ? 0.40f : 0.5f;

            mAboutCards.clear();
            collectCard(R.id.cardDeveloper);
            collectCard(R.id.cardProject);
            collectCard(R.id.rowDonate);
            collectCard(R.id.rowTelegram);
            collectCard(R.id.rowUpstream);
            // 卡片保持「扁平白面」：与首页 / 功能页的卡片观感一致 —— 仅靠纯白底
            // （card_bg_about=#FFFFFF）与页面底色（bg_surface=#F2F2F4）的色差区分，
            // 不设 elevation（不要立体投影 / 边缘高光）。

            ((android.widget.ScrollView) aboutScroll).setOnScrollChangeListener(
                    (v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                            applyAboutScroll(scrollY, logoBlock, logoFadeStart, logoFadeEnd,
                                    auroraFadeEnd, cardRevealEnd, minCardAlpha));
            // 初始态（scrollY=0）：卡片半透明、流光透射；LOGO 完整
            applyAboutScroll(0, logoBlock, logoFadeStart, logoFadeEnd, auroraFadeEnd,
                    cardRevealEnd, minCardAlpha);
        }

        // 关于页 inset 处理：状态栏透明、页面根不吃 inset（极光与 LOGO 直接顶到屏幕最顶，
        // 状态栏图标叠于其上）；头部度量与渐变带由 applyAboutInsets 按 @dimen/about_header_height 统一设置。
        final View aboutPage = findViewById(R.id.pageAbout);
        if (aboutPage != null) {
            aboutPage.setOnApplyWindowInsetsListener((v, insets) -> {
                applyAboutInsets(insets.getSystemWindowInsetTop());
                return insets;
            });
            // 兜底：切到「关于」页时若 inset 回调未及时触发，post 一次直接读取根窗口 inset 应用。
            aboutPage.post(() -> applyAboutInsets(statusBarTop(aboutPage)));
        }

        findViewById(R.id.rowCoolapk).setOnClickListener(v ->
                openUrl("https://www.coolapk.com/u/3019478"));
        findViewById(R.id.rowWeibo).setOnClickListener(v ->
                openUrl("https://weibo.com/u/3725737792"));
        findViewById(R.id.rowGithub).setOnClickListener(v ->
                openUrl("https://github.com/jiangshangwan/ClipboardFix"));
        // 更新日志：打开「最近三版」弹卡（不再直接跳外链）
        findViewById(R.id.rowReleases).setOnClickListener(v -> showChangelog());
        // 社群讨论：跳转 QQ 群（universal-share 链接）
        findViewById(R.id.rowTelegram).setOnClickListener(v ->
                openUrl("https://qun.qq.com/universal-share/share?ac=1&authKey=n9wFDXy4i20n15nDlUwLTGqI8AnQ%2BiyzC2XPQxpeOs2MxMYDsqRw0MNRPXYvx9WS&busi_data=eyJncm91cENvZGUiOiIxMTI0MTgwNjM1IiwidG9rZW4iOiJTSVhYbUhsYUNnWVYvb2wzVUlRb0J6SDRQMTNCdUxBMUNocUMzeU9taFpvakJRRHhZNDFBeDJIdFVQeTNZVnRmIiwidWluIjoiOTAzNzU1MzQ1In0%3D&data=qWsg9TN3ydud1Rb7MCThG73KtRkUY7UumTdXIsBBKS2W9kcpZ-W8kSgWfZFjkdqqGSxXGkUmeL9Dmb5VYsXf3A&svctype=4&tempid=h5_group_info"));
        // 为爱发电：打开含「向开发者捐赠 / 捐赠名单」的弹卡
        findViewById(R.id.rowDonate).setOnClickListener(v -> showDonateSheet());
        // 开源致谢：打开「致谢名单」弹卡（内含可点击蓝字链接）
        findViewById(R.id.rowUpstream).setOnClickListener(v -> showThanksSheet());
    }

    /**
     * 应用「关于」页布局度量（极光/LOGO 仅限顶部头部区，其余为页面底色）：
     *  · 透明占位 aboutTopSpacer 高度 = 头部高（480dp）→ 卡片从此处起，初始在头部区之下露出 LOGO/极光；
     *  · 渐变带底 = 头部高 → 极光恰好在头部区底收进页面底色，与卡片起始位对齐，无突兀接缝；
     *  · LOGO 层（layout_gravity=top、高=头部高）顶到屏幕最顶（状态栏透明叠于其上），无需额外偏移。
     * 头部高统一取自 @dimen/about_header_height（不依赖屏幕高度）；LOGO 纵向位置由
     * @dimen/about_logo_margin_top(190dp) 决定——按设计稿截图实测 LOGO 顶边距屏顶 191dp，一致。
     */
    private void applyAboutInsets(int top) {
        int header = getResources().getDimensionPixelSize(R.dimen.about_header_height);
        mAboutSpacerPx = header;

        View spacerView = findViewById(R.id.aboutTopSpacer);
        if (spacerView != null) {
            ViewGroup.LayoutParams lp = spacerView.getLayoutParams();
            if (lp.height != header) {
                lp.height = header;
                spacerView.setLayoutParams(lp);
            }
        }

        AboutBgView bg = findViewById(R.id.aboutBg);
        if (bg != null) {
            // 极光仅覆盖顶部头部区（与 LOGO 层同高），其下即页面底色，卡片从头部区底升起。
            bg.setBandBottom(header);
        }

        // LOGO 层已 layout_gravity=top 且高=头部高，顶到屏幕最顶（透明状态栏叠于其上），
        // 内部 logoBlock 以 about_logo_margin_top(190dp) 落入屏幕上部，无需额外偏移。
        View logoLayer = findViewById(R.id.aboutLogoLayer);
        if (logoLayer != null && logoLayer.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams llp = (FrameLayout.LayoutParams) logoLayer.getLayoutParams();
            if (llp.topMargin != 0) {
                llp.topMargin = 0;
                logoLayer.setLayoutParams(llp);
            }
        }
    }

    /** 把浮点值限制在 [0,1]（用于滚动进度归一化）。 */
    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** 收集「关于页卡片」到 mAboutCards（背景为 card_bg_about 的 LinearLayout）。 */
    private void collectCard(int id) {
        View v = findViewById(id);
        if (v != null) mAboutCards.add(v);
    }

    /**
     * 关于页滚动联动（对齐 HyperCeiler 关于页观感）：
     *  · 背景极光随滚动整体淡出——未划上去时流光透射到半透明卡片上；上滑后流光彻底消失，
     *    背景露出纯页面表面色（不再是深色极光糊成「全黑背景」）；
     *  · 固定 LOGO 层在卡片升到 LOGO 区之前完全淡出，避免「半透明卡片透出 LOGO 文字」的重叠；
     *  · 卡片背景不透明度随滚动位置变化——顶部（流光区）半透明，背后的极光透射到卡片上；
     *    继续上滑离开流光区后渐变为不透明实心面（白/深灰，与页面底色拉开对比），
     *    即「流光照射到卡片、上滑变不透明」。
     */
    private void applyAboutScroll(int scrollY, View logoBlock,
                                  int logoFadeStart, int logoFadeEnd,
                                  int auroraFadeEnd, int cardRevealEnd, float minCardAlpha) {
        // 背景极光：随滚动整体淡出——仅在顶部（未划上去）被流光照射；划上去流光迅速消失、
        // 背景露出纯页面表面色（对齐 HyperCeiler：未划上去被流光照射，划上去无流光、背景纯色）。
        // 淡出距离很短（auroraFadeEnd≈头部高），确保划上去后看不到残留流光。
        float ap = clamp01((float) scrollY / (float) auroraFadeEnd);
        if (mAboutBg != null) {
            mAboutBg.setGradientAlpha(1f - ap);
        }
        // LOGO 层：在卡片覆盖 LOGO 区前就淡出完毕（与卡片升起错峰，不重叠）
        float lt = clamp01((float) (scrollY - logoFadeStart) / (float) (logoFadeEnd - logoFadeStart));
        if (logoBlock != null) {
            logoBlock.setAlpha(1f - lt);
            float scale = 1f - 0.15f * lt;
            logoBlock.setScaleX(scale);
            logoBlock.setScaleY(scale);
        }
        // 卡片：顶部半透明（极光透射）、上滑渐变为不透明实心面（白/深灰，与页面底色区分）
        float p = clamp01((float) scrollY / (float) cardRevealEnd);
        int cardAlpha = (int) (255f * (minCardAlpha + (1f - minCardAlpha) * p));
        for (View card : mAboutCards) {
            Drawable bg = card.getBackground();
            if (bg != null) bg.mutate().setAlpha(cardAlpha);
        }
    }

    /** 读取状态栏高度：优先取根窗口 inset，缺失时退回系统 status_bar_height 资源。 */
    private int statusBarTop(View v) {
        android.view.WindowInsets wi = v.getRootWindowInsets();
        if (wi != null) {
            return wi.getSystemWindowInsetTop();
        }
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
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
        tv.setText("💕 特别说明\n\n" +
                "感谢您使用【HyperFixClip】模块，本模块免费使用，请勿二改及盗卖！以下信息还请仔细阅读：\n\n" +
                "1、本模块仅修改剪贴板和常用语验证逻辑，不影响数据内容，请放心使用。如果您的系统剪贴板功能正常请勿安装本模块！\n" +
                "2、从1.3版本起模块内置解锁MIUI键盘全面屏优化限制并适配HyperOS4，可能在OS3版本上存在部分异常问题，具体请自测。\n" +
                "3、本模块已适配微信输入法、搜狗输入法、讯飞输入法、QQ输入法、豆包输入法、Gboard输入法\n" +
                "4、本模块需LSPosed支持libxposed新版API（API 102）\n" +
                "5、请在LSPosed框架内勾选推荐作用域，完成后务必重启手机，否则不生效\n\n" +
                "最后温馨提醒：玩机有风险，请及时备份您的手机数据，造成损失本人不承担任何责任！");
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(15f);
        tv.setLineSpacing(6 * density, 1f);
        tv.setPadding(0, (int) (4 * density), 0, 0);
        BottomSheet.show(this, "您需了解", tv, !mandatory,
                mandatory ? "我已知悉" : null, () -> {
                    if (mandatory) markMustKnowAgreed();
                });
    }

    // ---------------- 弹卡：更新日志（最近三版） ----------------
    private void showChangelog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(BottomSheet.buildVersionBlock(this, "V2.0",
                "1. 全新 UI 重构，模块运行状态、设备与系统信息直观呈现，一眼掌握模块当前工作情况\n" +
                "2. 液态玻璃风格底部导航全面升级，页面切换过渡顺滑，触控跟手度显著提升\n" +
                "3. 新增独立功能设置面板，各项功能开关按需启闭，自定义自由度更高\n" +
                "4. 完整适配系统级深浅模式，跟随系统自动切换明暗主题，暗光场景更护眼\n" +
                "5. 新增专属关于页面，调用系统 API 实现动态渐变背景，视觉质感大幅提升\n" +
                "6. 丰富关于页内容，集成作者联系方式、更新日志入口、项目信息与打赏通道，完善项目信息展示"));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.13",
                "新增优化剪贴板面板背景功能，优化剪贴板和常用语面板与功能区的取色问题"));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.12",
                "优化界面显示效果，修复部分错误"));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.11",
                "修复部分错误"));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.10",
                "修复部分输入法开启手势提示线后被异常抬高的bug\n" +
                "新增启用全面屏优化功能开关\n" +
                "新增解锁剪贴板数量限制上限"));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.9",
                "修复部分输入法因开启手势提示线导致的键盘抬高BUG\n" +
                "新增对豆包输入法的支持\n" +
                "社群开放，QQ群：1124180635"));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.8",
                "重构模块UI，模块状态及设备信息一目了然\n" +
                "模块正式更名为HyperFixClip\n" +
                "修复因开启手势提示线后导致部分输入法被异常抬高的BUG\n" +
                "支持豆包输入法（测试）"));
        content.addView(BottomSheet.buildVersionBlock(this, "V1.4.7",
                "1、修复跨设备剪贴板同步BUG，无需通过切换系统输入法进行预热\n" +
                "2、新增对Gboard键盘的适配\n" +
                "3、使用中如果出现键盘异常抬高的问题请尝试关闭小白条"));
        BottomSheet.show(this, "更新日志", content, true, null, null);
    }

    // ---------------- 弹卡：为爱发电（捐赠码 / 捐赠名单） ----------------
    private void showDonateSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(sheetRow(R.drawable.ic_heart, "向开发者捐赠",
                "觉得有帮助到您，可以向开发者捐赠以此感谢", v -> showDonateQr()));
        content.addView(sheetDivider());
        content.addView(sheetRow(R.drawable.ic_books, "捐赠名单",
                "感谢每一位支持者", v -> showDonateList()));
        BottomSheet.show(this, "为爱发电", content, true, null, null);
    }

    private void showDonateQr() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        Bitmap qr = BitmapFactory.decodeResource(getResources(), R.drawable.donate_qrcode);
        ImageView img = new ImageView(this);
        img.setImageBitmap(qr);
        int size = Math.round(220 * density);
        img.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        img.setAdjustViewBounds(true);
        content.addView(img);

        TextView note = new TextView(this);
        note.setText("打赏请备注昵称，如：酷安/XXX，微博/XXX");
        note.setTextColor(getColor(R.color.text_tertiary));
        note.setTextSize(14f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Math.round(12 * density), 0, 0);
        content.addView(note);

        BottomSheet.show(this, "向开发者捐赠", content, true, "保存到相册", () -> {
            if (saveQrToGallery(qr)) {
                Toast.makeText(this, "二维码已保存到相册\n已打开微信，请用「扫一扫」→「相册」识别",
                        Toast.LENGTH_LONG).show();
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
        BottomSheet.show(this, "捐赠名单", content, true, null, null);
    }

    // ---------------- 弹卡：致谢名单（蓝字可点击） ----------------
    private void showThanksSheet() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView link = new TextView(this);
        link.setText("1、MIUI_IME_Unlock");
        link.setTextColor(getColor(R.color.text_link));
        link.setTextSize(16f);
        link.setTypeface(null, Typeface.BOLD);
        link.setPadding(0, Math.round(4 * density), 0, Math.round(4 * density));
        link.setOnClickListener(v -> openUrl("https://github.com/RC1844/MIUI_IME_Unlock"));
        content.addView(link);

        BottomSheet.show(this, "致谢列表", content, true, null, null);
    }

    // ---------------- 弹卡内通用行 / 分隔线 ----------------
    private View sheetRow(int iconRes, String title, String subtitle, View.OnClickListener onClick) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
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
        // 回前台时按进程级状态恢复：binder 存活（含后台期间 LSPosed 重推）直接刷新；
        // 未绑定（后台期间模块被关 / binder 死亡）等待重推 + 超时兜底。
        sCurrent = new WeakReference<>(this);
        if (sListenerRegistered) {
            if (sModuleBound) {
                refreshModuleStatus();
            } else {
                scheduleStatusFallback();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // 进程级弱引用不指向已销毁实例；清掉本实例的延迟任务，防泄漏与旧实例误刷 UI
        if (sCurrent.get() == this) {
            sCurrent.clear();
        }
        mStatusHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (BottomSheet.dismissTop()) return;
        super.onBackPressed();
    }

    // ---------------- 桌面图标显隐（沿用原逻辑，操作 LauncherAlias） ----------------
    private boolean isAliasHidden() {
        PackageManager pm = getPackageManager();
        ComponentName alias = new ComponentName(this, ALIAS_NAME);
        int state = pm.getComponentEnabledSetting(alias);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    }

    private void toggleLauncherIcon() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, ALIAS_NAME);
            if (isAliasHidden()) {
                pm.setComponentEnabledSetting(alias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
                Toast.makeText(this, "桌面图标已恢复", Toast.LENGTH_SHORT).show();
            } else {
                pm.setComponentEnabledSetting(alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                Toast.makeText(this, "桌面图标已隐藏\n（LSPosed 内仍可打开模块设置）", Toast.LENGTH_LONG).show();
            }
            updateHideRow();
        } catch (Exception e) {
            Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
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

    /** 以 root 执行命令；su 未授权、不可用或超时一律返回 false。 */
    private boolean runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
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
