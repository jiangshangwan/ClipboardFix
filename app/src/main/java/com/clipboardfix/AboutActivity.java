package com.clipboardfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

public class AboutActivity extends Activity {

    private static final String ALIAS_NAME = "com.clipboardfix.LauncherAlias";

    private View pageHome, pageFeature, pageAbout;
    private LiquidGlassBarView bottomNav;
    private FrameLayout navHome, navFeature, navAbout;
    private ImageView navIconHome, navIconFeature, navIconAbout;
    private TextView navLabelHome, navLabelFeature, navLabelAbout;
    private int currentTab = 0;

    private TextView tvHideIcon, tvHideIconSub;
    private TextView mSnackView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // 浅色模式下让状态栏/导航栏文字变深，保证可读
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        bindViews();
        setupBottomNav();
        setupHome();
        setupFeature();
        setupAbout();

        selectTab(0);

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
        View.OnClickListener listener = v -> {
            if (v == navHome) selectTab(0);
            else if (v == navFeature) selectTab(1);
            else if (v == navAbout) selectTab(2);
        };
        navHome.setOnClickListener(listener);
        navFeature.setOnClickListener(listener);
        navAbout.setOnClickListener(listener);
        // 按压/抬起驱动玻璃条里液滴的滑动与放大回弹（液滴本身就是按压反馈，不画波纹）
        bottomNav.attachPress(navHome, 0);
        bottomNav.attachPress(navFeature, 1);
        bottomNav.attachPress(navAbout, 2);
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
    }

    private void applyNavItem(ImageView icon, TextView label, boolean selected) {
        int color = selected ? getColor(R.color.accent) : getColor(R.color.text_tertiary);
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    // ---------------- 主页 ----------------
    private void setupHome() {
        // 状态占位：真实「已启用」检测需 hook 侧写入跨进程标记，暂以 UI 占位显示
        TextView tvStatus = findViewById(R.id.tvStatus);
        tvStatus.setText("已启用");
        tvStatus.setTextColor(getColor(R.color.status_red));

        // 已勾选输入法计数（框架 API，无需 hook）
        TextView tvImeCount = findViewById(R.id.tvImeCount);
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            int n = imm.getEnabledInputMethodList().size();
            tvImeCount.setText("已勾选的输入法：" + n);
        } catch (Exception e) {
            tvImeCount.setText("已勾选的输入法：—");
        }

        ((TextView) findViewById(R.id.tvAppVersion)).setText(BuildConfig.VERSION_NAME);
        ((TextView) findViewById(R.id.tvBuildDate)).setText(BuildConfig.BUILD_DATE);
        ((TextView) findViewById(R.id.tvSystemVersion)).setText(Build.VERSION.INCREMENTAL);

        findViewById(R.id.rowCheckUpdate).setOnClickListener(v -> {
            UpdateChecker checker = new UpdateChecker(AboutActivity.this);
            checker.setMessageSink(this::showSnack);
            checker.checkForUpdates();
        });
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

        findViewById(R.id.rowCoolapk).setOnClickListener(v ->
                openUrl("https://www.coolapk.com/u/3019478"));
        findViewById(R.id.rowGithub).setOnClickListener(v ->
                openUrl("https://github.com/jiangshangwan/ClipboardFix"));
        findViewById(R.id.rowReleases).setOnClickListener(v ->
                openUrl("https://github.com/jiangshangwan/ClipboardFix/releases"));
        findViewById(R.id.rowTelegram);
        // 社群讨论暂不开放：UI 占位，不设置跳转
        findViewById(R.id.rowUpstream).setOnClickListener(v ->
                openUrl("https://github.com/RC1844/MIUI_IME_Unlock"));
        findViewById(R.id.rowDonate).setOnClickListener(v -> showDonateDialog());
    }

    /** 打赏二维码：以弹窗展示，可保存到相册后去微信扫一扫识别 */
    private void showDonateDialog() {
        ImageView img = new ImageView(this);
        Bitmap qr = BitmapFactory.decodeResource(getResources(), R.drawable.donate_qrcode);
        img.setImageBitmap(qr);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        img.setPadding(pad, pad, pad, pad);
        img.setAdjustViewBounds(true);

        new AlertDialog.Builder(this)
                .setTitle("为爱发电")
                .setMessage("您的支持就是我的最大动力")
                .setView(img)
                .setPositiveButton("保存到相册", (d, w) -> {
                    if (saveQrToGallery(qr)) {
                        Toast.makeText(this, "二维码已保存到相册\n已打开微信，请用「扫一扫」→「相册」识别",
                                Toast.LENGTH_LONG).show();
                        openWeChat();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
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
