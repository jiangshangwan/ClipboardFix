package com.clipboardfix;

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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.system.Os;
import android.system.OsConstants;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.compose.runtime.MutableState;
import androidx.compose.ui.platform.ComposeView;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

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
    private static final String TELEGRAM_URL =
            "https://qun.qq.com/universal-share/share?ac=1&authKey=n9wFDXy4i20n15nDlUwLTGqI8AnQ%2BiyzC2XPQxpeOs2MxMYDsqRw0MNRPXYvx9WS&busi_data=eyJncm91cENvZGUiOiIxMTI0MTgwNjM1IiwidG9rZW4iOiJTSVhYbUhsYUNnWVYvb2wzVUlRb0J6SDRQMTNCdUxBMUNocUMzeU9taFpvakJRRHhZNDFBeDJIdFVQeTNZVnRmIiwidWluIjoiOTAzNzU1MzQ1In0%3D&data=qWsg9TN3ydud1Rb7MCThG73KtRkUY7UumTdXIsBBKS2W9kcpZ-W8kSgWfZFjkdqqGSxXGkUmeL9Dmb5VYsXf3A&svctype=4&tempid=h5_group_info";
    private static final String MIUIX_URL = "https://compose-miuix-ui.github.io/miuix/zh_CN/";

    // ---- Compose 可观察状态（composable 读取 .getValue() 即获得响应式刷新）----
    public final MutableState<String> uiStatusText = ClipboardFixUi.stateOf("检测中…");
    public final MutableState<String> uiImeCountText = ClipboardFixUi.stateOf("已启用输入法：—");
    public final MutableState<Boolean> uiStatusEnabled = ClipboardFixUi.stateOf(false);
    public final MutableState<Boolean> uiSwImeUnlock = ClipboardFixUi.stateOf(ModulePrefs.DEFAULT_IME_UNLOCK);
    public final MutableState<Boolean> uiSwUnlimitCount = ClipboardFixUi.stateOf(ModulePrefs.DEFAULT_UNLIMIT_COUNT);
    public final MutableState<Boolean> uiSwPanelColor = ClipboardFixUi.stateOf(ModulePrefs.DEFAULT_PANEL_COLOR);
    public final MutableState<Boolean> uiIconHidden = ClipboardFixUi.stateOf(false);

    // ---- 页面与导航（View 外壳 + ComposeView 页面 + 液态玻璃导航）----
    private ComposeView pageHome, pageFeature, pageAbout;
    private View contentContainer;
    private LiquidGlassBarView bottomNav;
    private FrameLayout navHome, navFeature, navAbout;
    private ImageView navIconHome, navIconFeature, navIconAbout;
    private TextView navLabelHome, navLabelFeature, navLabelAbout;
    private int currentTab = 0;

    private TextView mSnackView;

    // 模块启用状态（通过 libxposed:service 实时查询 LSPosed）
    private static final String MODULE_PKG = "com.clipboardfix";
    private final Handler mStatusHandler = new Handler(Looper.getMainLooper());

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
        // 桌面图标显隐：恢复当前状态
        updateHideRow();

        // View 外壳：内容区（三页 ComposeView）+ 底部液态玻璃导航
        setContentView(R.layout.activity_about);
        bindViews();
        setupBottomNav();

        // 装配三页 Compose + Miuix 界面（状态与动作回调桥接到本 Activity）
        ClipboardFixUi.installPages(
                pageHome, pageFeature, pageAbout,
                uiStatusText, uiImeCountText, uiStatusEnabled,
                BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE,
                DeviceNames.marketName(), "Android " + Build.VERSION.RELEASE,
                Build.VERSION.INCREMENTAL, abiInfo(),
                uiSwImeUnlock, uiSwUnlimitCount, uiSwPanelColor, uiIconHidden,
                this::doCheckUpdate,
                () -> showMustKnow(false),
                this::showStatusSheet,
                this::onToggleImeUnlock,
                this::onToggleUnlimitCount,
                this::onTogglePanelColor,
                this::toggleLauncherIcon,
                this::rebootDevice,
                () -> openUrl("https://www.coolapk.com/u/3019478"),
                () -> openUrl("https://weibo.com/u/3725737792"),
                () -> openUrl("https://github.com/jiangshangwan/ClipboardFix"),
                this::showChangelog,
                this::showDonateSheet,
                () -> openUrl(TELEGRAM_URL),
                this::showThanksSheet,
                () -> openUrl(MIUIX_URL));

        // 模块启用状态实时查询
        initModuleStatus();

        // 初始落到主页
        selectTab(0);

        // 首次启动强制阅读「您需了解」弹卡（不可点遮罩关闭，须点「我已知悉」）
        if (!mustKnowAgreed()) {
            showMustKnow(true);
        }

        // 静默检查更新（有新版本才弹窗）
        new UpdateChecker(this).checkSilent();
    }

    private void bindViews() {
        contentContainer = findViewById(R.id.contentContainer);
        pageHome = findViewById(R.id.pageHome);
        pageFeature = findViewById(R.id.pageFeature);
        pageAbout = findViewById(R.id.pageAbout);
        bottomNav = findViewById(R.id.bottomNav);
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
        // 玻璃条折射的是内容区（承载三页 ComposeView）的真实内容
        bottomNav.setBackdrop(contentContainer);
        bottomNav.setItems(navHome, navFeature, navAbout);
        bottomNav.setAccentColor(getColor(R.color.accent));
        bottomNav.setOnItemSelectedListener(this::selectTab);
    }

    private void selectTab(int idx) {
        currentTab = idx;
        pageHome.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageFeature.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);

        applyNavItem(navIconHome, navLabelHome, currentTab == 0);
        applyNavItem(navIconFeature, navLabelFeature, currentTab == 1);
        applyNavItem(navIconAbout, navLabelAbout, currentTab == 2);
        bottomNav.setSelectedIndex(idx, true);
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
     * 深色胶囊提示（对齐 HyperIsland 的检查更新样式）：浮在导航栏上方，
     * 淡入停留 2 秒后淡出。替代系统 Toast / 进度弹窗。
     */
    private void showSnack(String text) {
        FrameLayout root = (FrameLayout) findViewById(android.R.id.content);
        if (root == null) return;
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

    // ---------------- 功能开关 ----------------
    private void loadSwitchState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        uiSwImeUnlock.setValue(prefs.getBoolean(
                ModulePrefs.KEY_IME_UNLOCK, ModulePrefs.DEFAULT_IME_UNLOCK));
        uiSwUnlimitCount.setValue(prefs.getBoolean(
                ModulePrefs.KEY_UNLIMIT_COUNT, ModulePrefs.DEFAULT_UNLIMIT_COUNT));
        uiSwPanelColor.setValue(prefs.getBoolean(
                ModulePrefs.KEY_PANEL_COLOR, ModulePrefs.DEFAULT_PANEL_COLOR));
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

    private void saveFeaturePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(ModulePrefs.KEY_IME_UNLOCK, uiSwImeUnlock.getValue())
                .putBoolean(ModulePrefs.KEY_UNLIMIT_COUNT, uiSwUnlimitCount.getValue())
                .putBoolean(ModulePrefs.KEY_PANEL_COLOR, uiSwPanelColor.getValue())
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

    // ---------------- 检查更新 ----------------
    private void doCheckUpdate() {
        UpdateChecker checker = new UpdateChecker(this);
        checker.setMessageSink(this::showSnack);
        checker.checkForUpdates();
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

    // ---------------- 弹卡：模块状态（更新说明） ----------------
    private void showStatusSheet() {
        float density = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText("bug 要一个一个修，代码要一行一行敲，催更无效，调试靠熬，不保证哪天更新，" +
                "在这里感谢ROG、小米MiMo大模型、中国电信、国家电网对本项目的大力支持");
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(15f);
        tv.setLineSpacing(6 * density, 1f);
        tv.setPadding(0, (int) (4 * density), 0, 0);
        BottomSheet.show(this, "友情提示", tv, true, null, null);
    }

    // ---------------- 弹卡：更新日志（最近三版） ----------------
    private void showChangelog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(BottomSheet.buildVersionBlock(this, "V2.0",
                "1. 全面重构模块 UI，界面简洁清爽，视觉体验升级\n" +
                "2. 搭载液态玻璃导航，滑动手感更丝滑、触控更跟手\n" +
                "3. 修复 HyperOS 剪贴板对第三方输入法适配异常问题\n" +
                "4. 解决剪贴板与键盘功能区色差不一致问题\n" +
                "5. 扩充信息展示面板，设备信息、模块运行状态直观可见\n" +
                "6. 全新改版关于页面，内容丰富，使用便捷\n" +
                "7. 关于页卡片布局重新排布，分区间距更舒展\n" +
                "8. 关于页上滑时新增居中头部标题\n" +
                "9. 关于页头部标题加渐隐背景板，上滑时卡片不再与标题重叠\n" +
                "10. 关于页头部背景板下边缘改为柔和渐隐，滑动不再出现拖影"));
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
        content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

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
        note.setGravity(android.view.Gravity.CENTER);
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
    }

    @Override
    protected void onDestroy() {
        if (sCurrent.get() == this) {
            sCurrent.clear();
        }
        mStatusHandler.removeCallbacksAndMessages(null);
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
