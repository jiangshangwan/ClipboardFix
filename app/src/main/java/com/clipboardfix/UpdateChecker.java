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
import android.provider.Settings;
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
                        + "\n\n更新内容:\n" + stripMarkdown(release.body))
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
                android.util.Log.e("ClipboardFix", "downloadAndInstall failed", e);
                handler.post(() -> {
                    dialog.dismiss();
                    Toast.makeText(context, "下载失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void downloadFile(String urlStr, File outputFile, ProgressDialog dialog) throws Exception {
        android.util.Log.d("ClipboardFix", "downloadFile URL: " + urlStr);
        if (urlStr == null || urlStr.isEmpty()) {
            throw new Exception("下载链接为空");
        }
        // 让 HttpURLConnection 自动跟重定向（GitHub → Azure blob → 最终文件）
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "ClipboardFix-Updater");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();
        int code = conn.getResponseCode();
        android.util.Log.d("ClipboardFix", "HTTP response: " + code);
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
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
        // Android 8+ 需要 REQUEST_INSTALL_PACKAGES 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(context, "请先开启「安装未知应用」权限", Toast.LENGTH_LONG).show();
                Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
                return;
            }
        }

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
        android.util.Log.d("ClipboardFix", "assets: " + (assets == null ? "null" : assets.length() + " chars"));
        if (assets != null) {
            info.downloadUrl = extractStringFromObjects(assets, "browser_download_url");
        }
        // Fallback: 如果 API 解析失败，用 tag 构造下载 URL（保留 v 前缀）
        if (info.downloadUrl == null || info.downloadUrl.isEmpty()) {
            String tag = info.tagName;
            if (tag != null && !tag.isEmpty()) {
                info.downloadUrl = "https://github.com/jiangshangwan/ClipboardFix/releases/download/"
                        + tag + "/OS3.0." + tag + ".apk";
                android.util.Log.d("ClipboardFix", "fallback URL: " + info.downloadUrl);
            }
        }
        android.util.Log.d("ClipboardFix", "downloadUrl: " + info.downloadUrl);
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
        if (!m.find()) return "";
        String raw = m.group(1);
        // 处理 JSON 转义
        raw = raw.replace("\\r\n", "\n").replace("\\n", "\n").replace("\\r", "\r")
                 .replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
        // 处理 \\uXXXX Unicode 转义（GitHub JSON 中反引号以 \\u0060 形式存在）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            if (i + 5 < raw.length() && raw.charAt(i) == '\\' && raw.charAt(i + 1) == 'u') {
                try {
                    int cp = Integer.parseInt(raw.substring(i + 2, i + 6), 16);
                    sb.append((char) cp);
                    i += 5;
                } catch (NumberFormatException e) {
                    sb.append(raw.charAt(i));
                }
            } else {
                sb.append(raw.charAt(i));
            }
        }
        return sb.toString();
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
        // 用 indexOf 替代正则，更可靠地从 JSON 对象数组中提取值
        int keyIdx = arrayJson.indexOf("\"" + key + "\"");
        if (keyIdx == -1) return null;
        int colonIdx = arrayJson.indexOf(':', keyIdx + key.length() + 2);
        if (colonIdx == -1) return null;
        int startQuote = arrayJson.indexOf('"', colonIdx + 1);
        if (startQuote == -1) return null;
        int endQuote = arrayJson.indexOf('"', startQuote + 1);
        if (endQuote == -1) return null;
        return arrayJson.substring(startQuote + 1, endQuote);
    }

    /** 剥离 markdown 标记（AlertDialog 不支持渲染 markdown） */
    private String stripMarkdown(String text) {
        if (text == null) return "";
        // 用 replace（纯字符串）而非 replaceAll（正则），避免 \u0060 转义问题
        text = text.replace("\u0060", "");                // Java 源码反引号
        text = text.replace("\\u0060", "");               // JSON 字面 \u0060
        text = text.replace("```", "");                   // ```代码块```
        text = text.replaceAll("##+\\s*", "");            // ## 标题
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1"); // **加粗**
        return text.trim();
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
