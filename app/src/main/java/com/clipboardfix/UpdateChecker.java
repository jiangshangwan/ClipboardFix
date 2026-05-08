package com.clipboardfix;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Release 自动更新检查器
 *
 * 1. 调用 GitHub API 获取最新 Release
 * 2. 比对 tag_name 与当前版本号
 * 3. 有新版本 → 显示更新弹窗 → 下载 APK → 调用系统安装器
 */
public class UpdateChecker {

    private static final String API_URL =
            "https://api.github.com/repos/jiangshangwan/ClipboardFix/releases/latest";
    private final Context context;
    private final Handler handler;

    public UpdateChecker(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** 主动检查更新（用户点击触发，始终显示结果） */
    public void checkForUpdates() {
        ProgressDialog dialog = new ProgressDialog(context);
        dialog.setMessage("正在检查更新...");
        dialog.setCancelable(true);
        dialog.show();

        new Thread(() -> {
            try {
                String json = httpGet(API_URL);
                if (json == null) {
                    handler.post(() -> {
                        dialog.dismiss();
                        Toast.makeText(context, "网络请求失败，请检查网络", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                ReleaseInfo release = parseRelease(json);
                handler.post(() -> {
                    dialog.dismiss();
                    showResult(release);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    dialog.dismiss();
                    Toast.makeText(context, "检查更新失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /** 静默检查（启动时调用，仅在有更新时弹窗） */
    public void checkSilent() {
        new Thread(() -> {
            try {
                String json = httpGet(API_URL);
                if (json == null) return;
                ReleaseInfo release = parseRelease(json);
                if (release.hasUpdate) {
                    handler.post(() -> showUpdateDialog(release));
                }
            } catch (Exception ignored) {}
        }).start();
    }

    // ===== 内部方法 =====

    private void showResult(ReleaseInfo release) {
        if (release.hasUpdate) {
            showUpdateDialog(release);
        } else {
            Toast.makeText(context, "当前已是最新版本 v" + release.currentVersion, Toast.LENGTH_SHORT).show();
        }
    }

    private void showUpdateDialog(ReleaseInfo release) {
        new AlertDialog.Builder(context)
                .setTitle("发现新版本 " + release.tagName)
                .setMessage("当前版本: v" + release.currentVersion
                        + "\n\n更新内容:\n" + release.body)
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(release))
                .setNegativeButton("稍后", null)
                .setCancelable(true)
                .show();
    }

    private void downloadAndInstall(ReleaseInfo release) {
        ProgressDialog dialog = new ProgressDialog(context);
        dialog.setMessage("正在下载更新...");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMax(100);
        dialog.setCancelable(false);
        dialog.show();

        new Thread(() -> {
            try {
                String apkName = "clipboardfix_update.apk";
                File apkFile = new File(context.getCacheDir(), apkName);
                downloadFile(release.downloadUrl, apkFile, dialog);
                handler.post(() -> {
                    dialog.dismiss();
                    installApk(apkFile);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    dialog.dismiss();
                    Toast.makeText(context, "下载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void downloadFile(String urlStr, File outputFile, ProgressDialog dialog) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "ClipboardFix-Updater");

        // 处理重定向（GitHub 可能返回 302）
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP) {
            String redirectUrl = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(redirectUrl).openConnection();
            conn.setRequestProperty("User-Agent", "ClipboardFix-Updater");
        }

        int contentLength = conn.getContentLength();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(outputFile);

        byte[] buffer = new byte[8192];
        int bytesRead, totalRead = 0;

        while ((bytesRead = is.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
            totalRead += bytesRead;
            if (contentLength > 0) {
                final int progress = (int) (totalRead * 100.0 / contentLength);
                handler.post(() -> dialog.setProgress(progress));
            }
        }

        fos.flush();
        fos.close();
        is.close();
        conn.disconnect();
    }

    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        context.startActivity(intent);
    }

    // ===== HTTP 工具 =====

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "ClipboardFix-Updater");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    // ===== JSON 解析（纯手写，无第三方依赖） =====

    private ReleaseInfo parseRelease(String json) {
        ReleaseInfo info = new ReleaseInfo();
        info.tagName = extractString(json, "tag_name");
        info.name = extractString(json, "name");
        info.body = extractString(json, "body");
        info.currentVersion = getCurrentVersion();

        String cleanTag = info.tagName.replace("v", "").replace("V", "").trim();
        info.hasUpdate = isNewer(cleanTag, info.currentVersion);

        // 从 assets 中找 APK 下载链接
        String assets = extractArray(json, "assets");
        if (assets != null) {
            info.downloadUrl = extractStringFromObjects(assets, "browser_download_url");
        }
        return info;
    }

    private String getCurrentVersion() {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** 简单版本号比较：1.2 > 1.1 > 1.0 */
    private boolean isNewer(String serverVersion, String localVersion) {
        String[] sv = serverVersion.split("\\.");
        String[] lv = localVersion.split("\\.");
        int len = Math.max(sv.length, lv.length);
        for (int i = 0; i < len; i++) {
            int s = i < sv.length ? parseIntSafe(sv[i]) : 0;
            int l = i < lv.length ? parseIntSafe(lv[i]) : 0;
            if (s > l) return true;
            if (s < l) return false;
        }
        return false;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).replace("\\n", "\n").replace("\\\"", "\"") : "";
    }

    private String extractArray(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start == -1) return null;
        start = json.indexOf('[', start);
        if (start == -1) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            if (c == ']') depth--;
            if (depth == 0) return json.substring(start, i + 1);
        }
        return null;
    }

    private String extractStringFromObjects(String arrayJson, String key) {
        Pattern p = Pattern.compile("\\{[^}]*\"" + key + "\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}");
        Matcher m = p.matcher(arrayJson);
        return m.find() ? m.group(1) : null;
    }

    /** Release 数据模型 */
    static class ReleaseInfo {
        String tagName = "";
        String name = "";
        String body = "";
        String downloadUrl = "";
        String currentVersion = "";
        boolean hasUpdate = false;
    }
}
