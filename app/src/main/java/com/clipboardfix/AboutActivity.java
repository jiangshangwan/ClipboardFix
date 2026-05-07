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

        // ???? ? ????? + ????
        qrCode.setOnClickListener(v -> {
            boolean saved = saveQrToGallery(qrCode);
            if (saved) {
                Toast.makeText(this, "?????????\n?????,??????????????", Toast.LENGTH_LONG).show();
                openWeChat();
            }
        });

        // ?? ? ??????
        qrCode.setOnLongClickListener(v -> {
            if (saveQrToGallery(qrCode)) {
                Toast.makeText(this, "?????????", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // ???? ? ????
        findViewById(R.id.tvCoolapk).setOnClickListener(v -> openUrl("https://www.coolapk.com/u/3019478"));
        findViewById(R.id.tvWeibo).setOnClickListener(v -> openUrl("https://weibo.com/u/3725737792"));

        // ?? GitHub ?? ? ????
        findViewById(R.id.tvGithub).setOnClickListener(v -> openUrl("https://github.com/jiangshangwan/ClipboardFix"));

        // ??/????????
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
     * ???????????????
     */
    private void updateHideButton() {
        if (isAliasHidden()) {
            btnHide.setText("??????");
            btnHide.setBackgroundResource(R.drawable.btn_show_bg);
        } else {
            btnHide.setText("??????");
            btnHide.setBackgroundResource(R.drawable.btn_hide_bg);
        }
    }

    /**
     * ????????/??
     * ???? activity-alias(LauncherAlias),???? Activity(AboutActivity)
     * ?? alias ???????,? AboutActivity ???? LSPosed ? MODULE_SETTINGS ??
     */
    private void toggleLauncherIcon() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, ALIAS_NAME);

            if (isAliasHidden()) {
                // ????
                pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                );
                Toast.makeText(this, "???????\n?????", Toast.LENGTH_LONG).show();
            } else {
                // ????(??? alias,AboutActivity ???? LSPosed ??)
                pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
                Toast.makeText(this, "???????\n(LSPosed ?????????)", Toast.LENGTH_LONG).show();
            }

            // ??????
            updateHideButton();

        } catch (Exception e) {
            Toast.makeText(this, "????: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * ????????????
     */
    private boolean saveQrToGallery(ImageView imageView) {
        try {
            BitmapDrawable drawable = (BitmapDrawable) imageView.getDrawable();
            if (drawable == null) return false;
            Bitmap bitmap = drawable.getBitmap();

            String fileName = "??OS?????_?????_" + System.currentTimeMillis() + ".png";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/??OS?????");

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;

                OutputStream os = getContentResolver().openOutputStream(uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
            } else {
                String path = android.os.Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES + "/??OS?????").getAbsolutePath();
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
            Toast.makeText(this, "????: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /**
     * ?????? URL
     */
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "??????", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * ??????
     */
    private void openWeChat() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (intent != null) {
                startActivity(intent);
            }
        } catch (Exception e) {
            // ?????,??
        }
    }
}
