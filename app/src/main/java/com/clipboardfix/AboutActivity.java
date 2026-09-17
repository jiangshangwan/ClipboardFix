package com.clipboardfix;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

public class AboutActivity extends Activity {

    private static final String ALIAS_NAME = "com.clipboardfix.LauncherAlias";
    private Button btnHide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ImageView qrCode = findViewById(R.id.imgDonate);

        // 点击图片 → 保存到相册 + 打开微信
        qrCode.setOnClickListener(v -> {
            boolean saved = saveQrToGallery(qrCode);
            if (saved) {
                Toast.makeText(this, "二维码已保存到相册\n已打开微信，请用「扫一扫」→「相册」识别", Toast.LENGTH_LONG).show();
                openWeChat();
            }
        });

        // 长按 → 仅保存到相册
        qrCode.setOnLongClickListener(v -> {
            if (saveQrToGallery(qrCode)) {
                Toast.makeText(this, "二维码已保存到相册", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // 点击名字 → 跳转主页
        findViewById(R.id.tvCoolapk).setOnClickListener(v -> openUrl("https://www.coolapk.com/u/3019478"));
        findViewById(R.id.tvWeibo).setOnClickListener(v -> openUrl("https://weibo.com/u/3725737792"));

        // 点击 GitHub 链接 → 跳转仓库
        findViewById(R.id.tvGithub).setOnClickListener(v -> openUrl("https://github.com/jiangshangwan/ClipboardFix"));

        // 检查更新按钮
        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> {
            UpdateChecker checker = new UpdateChecker(this);
            checker.checkForUpdates();
        });

        // 导出日志按钮：仅在 debug 变体显示，release 纯净版隐藏
        Button btnExportLog = findViewById(R.id.btnExportLog);
        if (BuildConfig.ENABLE_EXPORT_LOG) {
            btnExportLog.setOnClickListener(v -> exportLog());
        } else {
            btnExportLog.setVisibility(View.GONE);
        }

        // 隐藏/显示桌面图标按钮
        btnHide = findViewById(R.id.btnHideIcon);
        updateHideButton();
        btnHide.setOnClickListener(v -> toggleLauncherIcon());

        // 静默检查更新（有新版本才弹窗）
        new UpdateChecker(this).checkSilent();
    }

    private boolean isAliasHidden() {
        PackageManager pm = getPackageManager();
        ComponentName alias = new ComponentName(this, ALIAS_NAME);
        int state = pm.getComponentEnabledSetting(alias);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    }

    /**
     * 根据当前状态更新按钮文字和颜色
     */
    private void updateHideButton() {
        if (isAliasHidden()) {
            btnHide.setText("显示桌面图标");
            btnHide.setBackgroundResource(R.drawable.btn_show_bg);
        } else {
            btnHide.setText("隐藏桌面图标");
            btnHide.setBackgroundResource(R.drawable.btn_hide_bg);
        }
    }

    /**
     * 切换桌面图标显示/隐藏
     * 操作的是 activity-alias（LauncherAlias），不是底层 Activity（AboutActivity）
     * 禁用 alias 后桌面图标消失，但 AboutActivity 仍可通过 LSPosed 的 MODULE_SETTINGS 访问
     */
    private void toggleLauncherIcon() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, ALIAS_NAME);

            if (isAliasHidden()) {
                // 显示图标
                pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                );
                Toast.makeText(this, "桌面图标已恢复\n重启后生效", Toast.LENGTH_LONG).show();
            } else {
                // 隐藏图标（仅禁用 alias，AboutActivity 仍可通过 LSPosed 访问）
                pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
                Toast.makeText(this, "桌面图标已隐藏\n（LSPosed 内仍可打开模块设置）", Toast.LENGTH_LONG).show();
            }

            // 更新按钮状态
            updateHideButton();

        } catch (Exception e) {
            Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 保存二维码图片到系统相册
     */
    private boolean saveQrToGallery(ImageView imageView) {
        try {
            BitmapDrawable drawable = (BitmapDrawable) imageView.getDrawable();
            if (drawable == null) return false;
            Bitmap bitmap = drawable.getBitmap();

            String fileName = "澎湃OS剪贴板补全_打赏二维码_" + System.currentTimeMillis() + ".png";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/澎湃OS剪贴板补全");

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;

                OutputStream os = getContentResolver().openOutputStream(uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
            } else {
                String path = android.os.Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES + "/澎湃OS剪贴板补全").getAbsolutePath();
                java.io.File dir = new java.io.File(path);
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

    /**
     * 用浏览器打开 URL
     */
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 导出日志（供排查「输入法被抬高」等问题） ----------------

    private static final int LOG_MAX_LINES = 10000;

    /**
     * 把 logcat 导出到手机存储根目录。
     *
     * <p>自 Android 4.1 起第三方应用读不到其他进程的日志，因此必须借助 root（su）抓取，
     * 否则只能拿到本应用自己的日志——那对排查输入法进程里的 hook 毫无价值。
     */
    private void exportLog() {
        Toast.makeText(this, "正在导出日志，请稍候…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                java.io.File f = new java.io.File("/sdcard/ClipboardFix_log.txt");
                // 用 root 直接写根目录；随后 chmod 放宽权限，
                // 否则文件属主是 root，文件管理器/微信读不到也就无法分享。
                String cmd = "logcat -d -v time -t " + LOG_MAX_LINES + " > "
                        + f.getAbsolutePath() + " 2>&1; chmod 666 " + f.getAbsolutePath();
                boolean ok = runSu(cmd);
                if (ok && f.exists() && f.length() > 0) {
                    msg = "日志已保存到手机存储根目录：\n/sdcard/ClipboardFix_log.txt\n大小 "
                            + (f.length() / 1024) + " KB\n请用文件管理器或微信发送给我";
                } else {
                    msg = fallbackExport();
                }
            } catch (Throwable t) {
                msg = "导出失败: " + t;
            }
            final String finalMsg = msg;
            runOnUiThread(() -> Toast.makeText(this, finalMsg, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** 以 root 执行命令；su 未授权、不可用或超时一律返回 false。 */
    private boolean runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
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

    /** 无 root 时的退路：只能拿到本应用日志，价值有限，提示用户授权 root。 */
    private String fallbackExport() {
        try {
            Process p = Runtime.getRuntime().exec("logcat -d -v time -t " + LOG_MAX_LINES);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor(20, TimeUnit.SECONDS);
            String content = sb.toString();
            if (content.trim().isEmpty()) {
                return "导出失败：未获得 root 授权，且系统不允许读取其他进程日志。\n"
                        + "请在 Magisk 中允许本应用 root 后重试，或用电脑 adb 抓取。";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String fileName = "ClipboardFix_log_" + System.currentTimeMillis() + ".txt";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return "导出失败：无法创建文件";
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return "导出失败：无法写入文件";
                os.write(content.getBytes());
                os.close();
                return "已保存到「下载」目录：" + fileName
                        + "\n（未获 root，仅含本应用日志，建议授权 root 后重新导出）";
            }
            return "导出失败：未获得 root 授权，请在 Magisk 中允许后重试。";
        } catch (Throwable t) {
            return "导出失败: " + t;
        }
    }

    /**
     * 尝试打开微信
     */
    private void openWeChat() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (intent != null) {
                startActivity(intent);
            }
        } catch (Exception e) {
            // 微信未安装，忽略
        }
    }
}
