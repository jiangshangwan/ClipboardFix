package com.clipboardfix

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.clipboardfix.ui.showSnack
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import java.util.regex.Pattern

/**
 * GitHub Release 自动更新检查器
 *
 * 1. 调用 GitHub API 获取最新 Release
 * 2. 比对 tag_name 与当前版本号
 * 3. 有新版本 → 显示更新弹窗 → 下载 APK → 调用系统安装器
 */
class UpdateChecker(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /** 结果提示出口：默认走全局 Snackbar（Miuix 组件），Activity 可替换为自定义渲染。 */
    fun interface MessageSink {
        fun show(text: String)
    }

    private var sink: MessageSink? = null

    fun setMessageSink(sink: MessageSink?) {
        this.sink = sink
    }

    private fun showMessage(text: String) {
        sink?.show(text) ?: showSnack(text)
    }

    /** 主动检查更新（用户点击触发，始终显示结果） */
    fun checkForUpdates() {
        showMessage(context.getString(R.string.uc_checking))
        Thread {
            try {
                val json = httpGet(API_URL)
                if (json == null) {
                    handler.post { showMessage(context.getString(R.string.uc_net_fail)) }
                    return@Thread
                }
                val release = parseRelease(json)
                handler.post { showResult(release) }
            } catch (e: Exception) {
                handler.post { showMessage(context.getString(R.string.uc_check_fail) + e.message) }
            }
        }.start()
    }

    /** 静默检查（启动时调用，仅在有更新时弹窗） */
    fun checkSilent() {
        Thread {
            try {
                val json = httpGet(API_URL)
                if (json == null) return@Thread
                val release = parseRelease(json)
                if (release.hasUpdate) {
                    handler.post { showUpdateDialog(release) }
                }
            } catch (ignored: Exception) {
            }
        }.start()
    }

    // ===== 内部方法 =====

    private fun showResult(release: ReleaseInfo) {
        if (release.hasUpdate) {
            showUpdateDialog(release)
        } else {
            showMessage(context.getString(R.string.uc_latest) + release.currentVersion)
        }
    }

    /** 更新提醒：改为与「更新日志 / 您需了解」一致的 BottomSheet 居中圆角卡片风格。 */
    private fun showUpdateDialog(release: ReleaseInfo) {
        // 兜底：理论上 UpdateChecker 仅由 Activity 构造；若上下文非 Activity 仍用系统弹窗
        if (context !is Activity) {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.uc_new_version) + " " + release.tagName)
                .setMessage(
                    context.getString(R.string.uc_current) + release.currentVersion
                            + "\n\n" + stripMarkdown(release.body)
                )
                .setPositiveButton(context.getString(R.string.uc_update_now)) { _: DialogInterface?, _: Int ->
                    downloadAndInstall(release)
                }
                .setNegativeButton(context.getString(R.string.uc_later), null)
                .setCancelable(true)
                .show()
            return
        }
        val activity = context
        val density = activity.resources.displayMetrics.density

        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL

        val tvCurrent = TextView(activity)
        tvCurrent.text = activity.getString(R.string.uc_current) + release.currentVersion
        tvCurrent.setTextColor(activity.getColor(R.color.text_tertiary))
        tvCurrent.textSize = 14f
        tvCurrent.setPadding(0, 0, 0, Math.round(8 * density))
        content.addView(tvCurrent)

        val tvBody = TextView(activity)
        // 更新说明直接取 GitHub Release 正文（标题由发布者手写，不再加「更新内容」前缀）
        tvBody.text = stripMarkdown(release.body)
        tvBody.setTextColor(activity.getColor(R.color.text_primary))
        tvBody.textSize = 15f
        tvBody.setLineSpacing(4 * density, 1f)
        content.addView(tvBody)

        val actions: View = BottomSheet.buildTwoButtonRow(
            activity,
            activity.getString(R.string.uc_later), null,
            activity.getString(R.string.uc_update_now), Runnable { downloadAndInstall(release) }
        )

        BottomSheet.show(
            activity,
            activity.getString(R.string.uc_new_version) + " " + release.tagName,
            content, true, actions
        )
    }

    private fun downloadAndInstall(release: ReleaseInfo) {
        val activity = context as Activity
        val token = CancelToken()
        val sheet = BottomSheet.showProgress(
            activity, activity.getString(R.string.uc_downloading), "HyperClipFix " + release.tagName
        ) {
            token.cancelled = true
            val c = token.conn
            if (c != null) c.disconnect()
        }

        Thread({
            val apkFile = File(context.cacheDir, "clipboardfix_update.apk")
            try {
                val sizeKnown = booleanArrayOf(false)
                downloadFile(release.downloadUrl, apkFile, token) { read, total ->
                    if (total > 0 && !sizeKnown[0]) {
                        sizeKnown[0] = true
                        sheet.setSubTitle("HyperClipFix " + release.tagName + " · 共 " + BottomSheet.formatMb(total))
                    }
                    sheet.setProgress(if (total > 0) (read * 100 / total).toInt() else 0, read, total)
                }
                if (token.cancelled) {
                    apkFile.delete()
                    return@Thread
                }
                handler.post {
                    sheet.showCompleted()
                    handler.postDelayed({ sheet.dismiss(); installApk(apkFile) }, 800)
                }
            } catch (e: Exception) {
                apkFile.delete()
                if (token.cancelled) return@Thread
                android.util.Log.e("ClipboardFix", "downloadAndInstall failed", e)
                handler.post {
                    sheet.dismiss()
                    showMessage(
                        context.getString(R.string.uc_download_fail) + e.javaClass.simpleName + ": " + e.message
                    )
                }
            }
        }, "cf-apk-download").start()
    }

    /** 下载取消令牌：标志位 + 连接引用（取消时 disconnect 让读循环立刻抛错退出）。 */
    private class CancelToken {
        @Volatile
        var cancelled = false
        @Volatile
        var conn: HttpURLConnection? = null
    }

    /** 进度回调（下载线程触发；UI 刷新由调用方自行切主线程）。 */
    private fun interface ProgressSink {
        fun onProgress(read: Long, total: Long)
    }

    @Throws(Exception::class)
    private fun downloadFile(urlStr: String, outputFile: File, token: CancelToken, sink: ProgressSink) {
        android.util.Log.d("ClipboardFix", "downloadFile URL: $urlStr")
        if (urlStr.isEmpty()) {
            throw Exception("下载链接为空")
        }
        // 让 HttpURLConnection 自动跟重定向（GitHub → Azure blob → 最终文件）
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        token.conn = conn
        conn.setRequestProperty("User-Agent", "ClipboardFix-Updater")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.connect()
        val code = conn.responseCode
        android.util.Log.d("ClipboardFix", "HTTP response: $code")
        if (code != 200) {
            conn.disconnect()
            throw Exception("HTTP $code")
        }

        val contentLength = conn.contentLength
        val input = conn.inputStream
        val fos = FileOutputStream(outputFile)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = 0L

        while ((input.read(buffer).also { bytesRead = it }) != -1) {
            if (token.cancelled) {
                fos.close()
                input.close()
                conn.disconnect()
                throw Exception("cancelled")
            }
            fos.write(buffer, 0, bytesRead)
            totalRead += bytesRead.toLong()
            val read = totalRead
            sink.onProgress(read, contentLength.toLong())
        }

        fos.flush()
        fos.close()
        input.close()
        conn.disconnect()
    }

    private fun installApk(apkFile: File) {
        // Android 8+ 需要 REQUEST_INSTALL_PACKAGES 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                showMessage(context.getString(R.string.uc_install_perm))
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
                return
            }
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val apkUri: Uri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                apkFile
            )
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            apkUri = Uri.fromFile(apkFile)
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        context.startActivity(intent)
    }

    // ===== HTTP 工具 =====

    @Throws(Exception::class)
    private fun httpGet(urlStr: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ClipboardFix-Updater")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            return null
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
        val sb = StringBuilder()
        var line: String?
        while ((reader.readLine().also { line = it }) != null) {
            sb.append(line)
        }
        reader.close()
        conn.disconnect()
        return sb.toString()
    }

    // ===== JSON 解析（纯手写，无第三方依赖） =====

    private fun parseRelease(json: String): ReleaseInfo {
        val info = ReleaseInfo()
        info.tagName = extractString(json, "tag_name")
        info.name = extractString(json, "name")
        info.body = extractString(json, "body")
        info.currentVersion = getCurrentVersion()

        val cleanTag = info.tagName.replace("v", "").replace("V", "").trim()
        info.hasUpdate = isNewer(cleanTag, info.currentVersion)

        // 从 assets 中找 APK 下载链接（优先选 release 常规版，避免 debug 取证版被普通用户安装）
        val assets = extractArray(json, "assets")
        android.util.Log.d(
            "ClipboardFix",
            "assets: " + (if (assets == null) "null" else assets.length.toString() + " chars")
        )
        if (assets != null) {
            info.downloadUrl = selectDownloadUrl(assets)
        }
        // Fallback: 如果 API 解析失败，用 tag 构造下载 URL（保留 v 前缀）
        if (info.downloadUrl.isEmpty()) {
            val tag = info.tagName
            if (tag.isNotEmpty()) {
                // 与实际发布的 asset 命名保持一致（ClipboardFix-<tag>.apk）
                info.downloadUrl = "https://github.com/jiangshangwan/ClipboardFix/releases/download/" +
                        tag + "/ClipboardFix-" + tag + ".apk"
                android.util.Log.d("ClipboardFix", "fallback URL: " + info.downloadUrl)
            }
        }
        android.util.Log.d("ClipboardFix", "downloadUrl: " + info.downloadUrl)
        return info
    }

    private fun getCurrentVersion(): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /** 简单版本号比较：1.2 > 1.1 > 1.0 */
    private fun isNewer(serverVersion: String, localVersion: String): Boolean {
        val sv = serverVersion.split('.').toTypedArray()
        val lv = localVersion.split('.').toTypedArray()
        val len = Math.max(sv.size, lv.size)
        for (i in 0 until len) {
            val s = if (i < sv.size) parseIntSafe(sv[i]) else 0
            val l = if (i < lv.size) parseIntSafe(lv[i]) else 0
            if (s > l) return true
            if (s < l) return false
        }
        return false
    }

    private fun parseIntSafe(s: String): Int {
        return try {
            s.trim().toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun extractString(json: String, key: String): String {
        val p = Pattern.compile("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val m = p.matcher(json)
        if (!m.find()) return ""
        val raw = m.group(1) ?: return ""
        // 处理 JSON 转义
        var t = raw.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\r")
            .replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
        // 处理 \\uXXXX Unicode 转义（GitHub JSON 中反引号以 \\u0060 形式存在）
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            if (i + 5 < t.length && t[i] == '\\' && t[i + 1] == 'u') {
                try {
                    val cp = t.substring(i + 2, i + 6).toInt(16)
                    sb.append(cp.toChar())
                    i += 5
                } catch (e: NumberFormatException) {
                    sb.append(t[i])
                }
            } else {
                sb.append(t[i])
            }
            i++
        }
        return sb.toString()
    }

    private fun extractArray(json: String, key: String): String? {
        var start = json.indexOf("\"$key\"")
        if (start == -1) return null
        start = json.indexOf('[', start)
        if (start == -1) return null
        var depth = 0
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (c == '[') depth++
            if (c == ']') depth--
            if (depth == 0) return json.substring(start, i + 1)
            i++
        }
        return null
    }

    /**
     * 从 GitHub Release 的 assets 数组中选择最合适的 APK 下载链接。
     * 优先返回不含 "debug" 的 release 常规版；如果全是 debug 或没有匹配项，兜底返回第一个。
     */
    private fun selectDownloadUrl(assets: String): String {
        val urls: MutableList<String> = ArrayList()
        val key = "\"browser_download_url\""
        var idx = 0
        while (assets.indexOf(key, idx).also { idx = it } != -1) {
            val colonIdx = assets.indexOf(':', idx + key.length)
            if (colonIdx == -1) break
            val startQuote = assets.indexOf('"', colonIdx + 1)
            if (startQuote == -1) break
            val endQuote = assets.indexOf('"', startQuote + 1)
            if (endQuote == -1) break
            urls.add(assets.substring(startQuote + 1, endQuote))
            idx = endQuote + 1
        }
        // 优先选 release（非 debug）
        for (url in urls) {
            val lower = url.lowercase()
            if (lower.endsWith(".apk") && !lower.contains("-debug") && !lower.contains("debug")) {
                return url
            }
        }
        // 兜底第一个
        return if (urls.isEmpty()) "" else urls[0]
    }

    /** 剥离 markdown 标记（AlertDialog 不支持渲染 markdown） */
    private fun stripMarkdown(text: String?): String {
        if (text == null) return ""
        // 用 replace（纯字符串）而非 replaceAll（正则），避免 \u0060 转义问题
        var t = text.replace("\u0060", "")                // Java 源码反引号
        t = t.replace("\\u0060", "")               // JSON 字面 \u0060
        t = t.replace("```", "")                   // ```代码块```
        t = t.replace("##+\\s*".toRegex(), "")            // ## 标题
        t = t.replace("\\*\\*(.+?)\\*\\*".toRegex(), "$1") // **加粗**
        return t.trim()
    }

    /** Release 数据模型 */
    class ReleaseInfo {
        var tagName: String = ""
        var name: String = ""
        var body: String = ""
        var downloadUrl: String = ""
        var currentVersion: String = ""
        var hasUpdate: Boolean = false
    }

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/jiangshangwan/ClipboardFix/releases/latest"
    }
}
