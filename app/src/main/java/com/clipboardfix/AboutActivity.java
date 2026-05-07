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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.OutputStream;

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

        // 隐藏/显示桌面图标按钮
        btnHide = findViewById(R.id.btnHideIcon);
        updateHideButton();
        btnHide.setOnClickListener(v -> toggleLauncherIcon());
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
