/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
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
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
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

    /** 云端最新版本回调：解析出 tag 后回传（已去除 v/V 前缀），供主页展示「非最新」标识。 */
    private var onLatestVersion: ((String) -> Unit)? = null

    fun setOnLatestVersionListener(listener: ((String) -> Unit)?) {
        this.onLatestVersion = listener
    }

    /** 回传云端最新版本号（去除 v/V 前缀），在主线程回调以便 Compose State 安全写入。 */
    private fun notifyLatestVersion(tagName: String) {
        val clean = tagName.removePrefix("v").removePrefix("V").trim()
        if (clean.isNotEmpty()) {
            val cb = onLatestVersion
            if (cb != null) handler.post { cb(clean) }
        }
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
                notifyLatestVersion(release.tagName)
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
                notifyLatestVersion(release.tagName)
                if (release.hasUpdate) {
                    handler.post { showUpdateDialog(release) }
                }
            } catch (ignored: Exception) {
            }
        }.start()
    }

    /**
     * 弹出版本更新窗口（复用已有的更新弹窗与本次会话已解析的 Release 数据）。
     * 调用方已按云端版本高于本地版本门控，此处只负责复用，不重复判定、不额外发起网络请求。
     */
    fun showUpdateDialogIfNeeded() {
        android.util.Log.d("ClipboardFix", "release schema=" + RELEASE_SCHEMA)
        val cached = cachedRelease
        if (cached != null && cached.downloadUrl.isNotEmpty()) {
            handler.post { showUpdateDialog(cached) }
        }
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

    /**
     * 下载并安装：先用 Range 探测 CDN 是否支持分片，支持则多路并行拉取各自分片再拼接，
     * 不支持则退回单连接；分片进度落盘，取消/失败后再次下载可续传。
     * APK 来源始终是 GitHub 官方 Release，不经过任何第三方中转。
     */
    private fun downloadAndInstall(release: ReleaseInfo) {
        val activity = context as Activity
        val token = CancelToken()
        val sheet = BottomSheet.showProgress(
            activity, activity.getString(R.string.uc_downloading), "HyperClipFix " + release.tagName
        ) { token.cancelAll() }

        Thread({
            val apkFile = File(context.cacheDir, "clipboardfix_update.apk")
            val metaFile = File(context.cacheDir, "clipboardfix_update.meta")
            try {
                if (release.downloadUrl.isEmpty()) throw Exception("下载链接为空")
                // 复用已下好的完整包：跳转授权页返回后再次点更新，不该重下。
                // 校验 tag/meta 与 sha256 任一不符都作废重下，防止版本错配装坏包。cf/reuseapk-1122z
                if (apkFile.exists() && isDownloadedApkValid(apkFile, metaFile, release)) {
                    android.util.Log.d("ClipboardFix", "reuse already-downloaded apk, skip download")
                    handler.post { dispatchInstall(apkFile) }
                    return@Thread
                }
                val probe = probeDownload(release.downloadUrl, token)
                if (token.cancelled) return@Thread

                val total = probe.total
                val parallel = probe.supportsRange && total >= PARALLEL_MIN_BYTES
                val chunks = if (parallel) PARALLEL_CHUNKS else 1
                val chunkSize = if (chunks == 1) total else ceilDiv(total, chunks.toLong())
                android.util.Log.d("ClipboardFix",
                    "download probe: range=" + probe.supportsRange + " total=" + total +
                            " chunks=" + chunks + " etag=" + probe.etag)

                // 校验元数据：URL/tag/总长/etag 任一不符即作废旧分片，从头下载
                val metaKey = listOf(release.downloadUrl, release.tagName, total.toString(), probe.etag).joinToString("\u0001")
                val resumable = isMetaValid(metaFile, metaKey)
                val parts = Array(chunks) { File(context.cacheDir, PART_PREFIX + it) }
                if (!resumable) {
                    for (p in parts) p.delete()
                    apkFile.delete()
                    writeMeta(metaFile, metaKey)
                }

                // 每个分片的已完成字节（续传时读实际文件大小，并夹到本片区间内）
                val done = LongArray(chunks)
                for (i in 0 until chunks) {
                    val start = i * chunkSize
                    val end = if (i == chunks - 1) total - 1 else (start + chunkSize - 1).coerceAtMost(total - 1)
                    val span = end - start + 1
                    val have = if (resumable && parts[i].exists()) parts[i].length() else 0L
                    done[i] = if (have in 1..span) have else 0L
                    if (done[i] == 0L) parts[i].delete()
                }
                val doneRef = AtomicLong(0)
                for (v in done) doneRef.addAndGet(v)

                val sizeKnown = booleanArrayOf(false)
                val sink = ProgressSink { read, sumTotal ->
                    val known = if (sumTotal > 0) sumTotal else total
                    if (known > 0 && !sizeKnown[0]) {
                        sizeKnown[0] = true
                        handler.post {
                            sheet.setSubTitle("HyperClipFix " + release.tagName + " · 共 " + BottomSheet.formatMb(known))
                        }
                    }
                    sheet.setProgress(if (known > 0) (read * 100 / known).toInt() else 0, read, known)
                }

                if (total > 0) sink.onProgress(doneRef.get(), total)
                val latch = CountDownLatch(chunks)
                val failed = AtomicLong(0)
                for (i in 0 until chunks) {
                    val start = i * chunkSize
                    val end = if (i == chunks - 1) total - 1 else (start + chunkSize - 1).coerceAtMost(total - 1)
                    Thread({
                        try {
                            // 复用探测拿到的 CDN 签名直链：不再逐块重走 API，整次下载只耗 1 次配额。
                            downloadChunk(probe.directUrl, start + done[i], end, parts[i], probe.supportsRange, token, doneRef, sink, total)
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            android.util.Log.e("ClipboardFix", "chunk " + i + " failed", e)
                        } finally {
                            latch.countDown()
                        }
                    }, "cf-chunk-$i").start()
                }
                latch.await()

                if (token.cancelled) return@Thread
                if (failed.get() > 0 || doneRef.get() != total) {
                    throw Exception("分片下载不完整（" + doneRef.get() + "/" + total + "）")
                }

                // 拼接前先按 URL 顺序合并分片到目标文件
                mergeParts(parts, apkFile)
                if (apkFile.length() != total) {
                    throw Exception("APK 大小不符（" + apkFile.length() + "/" + total + "）")
                }
                if (release.digest.isNotEmpty() && !verifySha256(apkFile, release.digest)) {
                    throw Exception("校验失败，已丢弃本次下载")
                }

                for (p in parts) p.delete()
                // meta 保留：静默安装若因授权/权限失败需要回退系统安装器，
                // 用户返回后再次点更新可直接复用已下好的包而不重下。cf/keepmeta-1122z
                handler.post {
                    sheet.showCompleted()
                    handler.postDelayed({ sheet.dismiss(); dispatchInstall(apkFile) }, 800)
                }
            } catch (e: Exception) {
                if (!token.cancelled) {
                    android.util.Log.e("ClipboardFix", "downloadAndInstall failed", e)
                    // 分片保留供下次续传，仅清掉拼接产物
                    apkFile.delete()
                    handler.post {
                        sheet.dismiss()
                        showMessage(
                            context.getString(R.string.uc_download_fail) + e.javaClass.simpleName + ": " + e.message
                        )
                    }
                }
            }
        }, "cf-apk-download").start()
    }

    /** 下载取消令牌：标志位 + 全部在途连接（取消时逐个 disconnect 让读循环立刻退出）。 */
    private class CancelToken {
        @Volatile
        var cancelled = false
        private val conns = java.util.Collections.synchronizedList(ArrayList<HttpURLConnection>())
        fun track(c: HttpURLConnection) { conns.add(c) }
        fun cancelAll() {
            cancelled = true
            synchronized(conns) {
                for (c in conns) {
                    try { c.disconnect() } catch (_: Throwable) {}
                }
            }
        }
    }

    /** 进度回调（下载线程触发；UI 刷新由调用方自行切主线程）。 */
    private fun interface ProgressSink {
        fun onProgress(read: Long, total: Long)
    }

    /** Range 探测结果：总长 + 是否支持分片 + 版本标识 + 302 后的最终 CDN 直链。 */
    private class ProbeResult {
        var total: Long = -1L
        var supportsRange: Boolean = false
        var etag: String = ""
        /** 跟随重定向后的实际文件地址（带签名的 CDN 链接），分块复用它以免每次重走 API 配额。 */
        var directUrl: String = ""
    }

    /**
     * 探测下载地址：先请求 0-0 一字节。206 + Content-Range 表示支持分片并拿到总长；
     * 否则退回单连接，总长取 Content-Length。GitHub → Azure blob 的重定向由 followRedirects 处理。
     */
    @Throws(Exception::class)
    private fun probeDownload(urlStr: String, token: CancelToken): ProbeResult {
        val r = ProbeResult()
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        token.track(conn)
        conn.setRequestProperty("User-Agent", "ClipboardFix-Updater")
        // API asset 直链必须声明 octet-stream 才会 302 到文件本体，否则返回 JSON 元数据。
        // 普通 CDN 链接带此头无副作用。cf/octet-1122y
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("Range", "bytes=0-0")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            conn.connect()
            val code = conn.responseCode
            r.etag = conn.getHeaderField("ETag") ?: (conn.getHeaderField("Last-Modified") ?: "")
            // 取 302 后的实际文件地址：API asset 直链每次请求都生成新签名 URL，
            // 分块若各自重走 API 会耗尽未认证配额（60/h），复用这条即可只花 1 次。
            r.directUrl = conn.url.toString()
            if (code == 206) {
                val cr = conn.getHeaderField("Content-Range") ?: ""
                val slash = cr.lastIndexOf('/')
                if (slash >= 0) {
                    r.total = cr.substring(slash + 1).trim().toLongOrNull() ?: -1L
                }
                r.supportsRange = r.total > 0
            } else {
                r.total = conn.contentLength.toLong()
            }
            android.util.Log.d("ClipboardFix", "probe code=" + code + " cr=" + crSafe(conn) + " total=" + r.total)
        } finally {
            try { conn.inputStream?.close() } catch (_: Throwable) {}
            conn.disconnect()
        }
        if (r.total <= 0) throw Exception("无法获取文件大小（HTTP 探测失败）")
        return r
    }

    private fun crSafe(conn: HttpURLConnection): String = conn.getHeaderField("Content-Range") ?: ""

    /** 单分片下载：带 Range 拉取 [begin, end]，追加写入分片文件，实时累加全局进度。 */
    @Throws(Exception::class)
    private fun downloadChunk(urlStr: String, begin: Long, end: Long, part: File, expectRange: Boolean,
                              token: CancelToken, done: AtomicLong, sink: ProgressSink, total: Long) {
        if (begin > end) return
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        token.track(conn)
        conn.setRequestProperty("User-Agent", "ClipboardFix-Updater")
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("Range", "bytes=$begin-$end")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            conn.connect()
            val code = conn.responseCode
            if (expectRange) {
                // 探测已确认支持 Range（206），此处若返回 200 说明服务端行为不一致，
                // 该片会拿到整份文件而污染拼接，直接失败；分片保留、下次续传时按区间夹取自愈。
                if (code != 206) {
                    if (code == 200) part.delete()
                    throw Exception("分片未响应 Range（HTTP $code）")
                }
            } else if (code != 200 && code != 206) {
                throw Exception("单连接 HTTP $code")
            } else if (code == 200) {
                // 单连接且服务端返回整份文件：从头覆盖，不叠加旧分片
                part.delete()
            }
            val append = code != 200 && part.exists()
            val fos = FileOutputStream(part, append)
            val input = conn.inputStream
            val buffer = ByteArray(16384)
            var n: Int
            while ((input.read(buffer).also { n = it }) != -1) {
                if (token.cancelled) throw Exception("cancelled")
                fos.write(buffer, 0, n)
                done.addAndGet(n.toLong())
                sink.onProgress(done.get(), total)
            }
            fos.flush()
            fos.close()
            input.close()
        } finally {
            conn.disconnect()
        }
    }

    /** 按序合并分片到目标 APK。 */
    @Throws(Exception::class)
    private fun mergeParts(parts: Array<File>, out: File) {
        val fos = FileOutputStream(out)
        val buffer = ByteArray(16384)
        try {
            for (p in parts) {
                val input = java.io.FileInputStream(p)
                try {
                    var n: Int
                    while ((input.read(buffer).also { n = it }) != -1) fos.write(buffer, 0, n)
                } finally {
                    input.close()
                }
            }
        } finally {
            fos.flush()
            fos.close()
        }
    }

    /** 计算文件 SHA-256 并与 asset digest（sha256:hex）比对。 */
    private fun verifySha256(file: File, digest: String): Boolean {
        val expected = digest.removePrefix("sha256:").trim().lowercase(Locale.US)
        if (expected.isEmpty()) return true
        val md = MessageDigest.getInstance("SHA-256")
        val input = java.io.FileInputStream(file)
        val buffer = ByteArray(16384)
        try {
            var n: Int
            while ((input.read(buffer).also { n = it }) != -1) md.update(buffer, 0, n)
        } finally {
            input.close()
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        val actual = sb.toString()
        android.util.Log.d("ClipboardFix", "sha256 expected=" + expected + " actual=" + actual)
        return actual.equals(expected, ignoreCase = true)
    }

    /** 续传元数据：内容 URL/tag/总长/etag 拼接成 key，任一变化即作废旧分片。 */
    private fun isMetaValid(meta: File, key: String): Boolean {
        if (!meta.exists()) return false
        return try {
            meta.readText(Charsets.UTF_8) == key
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeMeta(meta: File, key: String) {
        try { meta.writeText(key, Charsets.UTF_8) } catch (_: Throwable) {}
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    /**
     * 复用已下载包的校验：meta 记录的 tag 与本次一致，且文件 sha256 与 asset digest 吻合。
     * 任一项不成立都返回 false（触发重新下载）。cf/reusecheck-1122z
     */
    private fun isDownloadedApkValid(apkFile: File, metaFile: File, release: ReleaseInfo): Boolean {
        if (release.digest.isEmpty()) return false
        val meta = try { metaFile.readText(Charsets.UTF_8) } catch (_: Throwable) { return false }
        if (!meta.contains(release.tagName)) return false
        return verifySha256(apkFile, release.digest)
    }

    /**
     * 安装调度：优先 root 静默安装（免「安装未知应用」授权、免跳设置页），
     * 失败或无 root 时回退系统安装器。静默成功后自动重载进程并提示。cf/dispatch-1122z
     */
    private fun dispatchInstall(apkFile: File) {
        if (RootCheck.isModuleRootGranted()) {
            handler.post { showMessage(context.getString(R.string.uc_installing)) }
        }
        Thread({
            val ok = silentInstall(apkFile)
            if (ok) {
                val reloaded = reloadProcesses()
                handler.post {
                    showMessage(context.getString(R.string.uc_install_ok_reloading))
                    if (!reloaded) showMessage(context.getString(R.string.reload_fail) + "process")
                }
            } else {
                android.util.Log.w("ClipboardFix", "silent install failed, fallback to system installer")
                handler.post { installApk(apkFile) }
            }
        }, "cf-install").start()
    }

    /**
     * root 静默安装：把 APK 复制到 /data/local/tmp 并放开读权限，再 pm install -r。
     * app 私有 cacheDir 对 pm 子进程不可读，故必须先复制到公共可访问位置。
     * 返回 true 仅当 pm 返回 success；任何异常/拒绝都返回 false 交给调用方回退。
     */
    private fun silentInstall(apkFile: File): Boolean {
        if (!RootCheck.isModuleRootGranted()) {
            android.util.Log.d("ClipboardFix", "silent install skip: no root grant")
            return false
        }
        val target = "/data/local/tmp/cf_update_install.apk"
        val cmd = StringBuilder()
        cmd.append("cp ").append(apkFile.absolutePath).append(" ").append(target).append("\n")
        cmd.append("chmod 644 ").append(target).append("\n")
        cmd.append("pm install -r ").append(target).append("\n")
        cmd.append("rm -f ").append(target).append("\n")
        cmd.append("exit\n")
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes(cmd.toString())
            os.flush()
            val out = StringBuilder()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) out.append(line).append('\n')
            val done = p.waitFor(30, TimeUnit.SECONDS)
            val result = out.toString()
            android.util.Log.d("ClipboardFix", "silent install output=" + result.trim())
            done && p.exitValue() == 0 && result.lowercase(Locale.US).contains("success")
        } catch (t: Throwable) {
            android.util.Log.w("ClipboardFix", "silent install error", t)
            false
        } finally {
            if (p != null) try { p.destroy() } catch (_: Throwable) {}
        }
    }

    /**
     * 自动重载进程：force-stop 剪贴板宿主、所有输入法与互联服务，使新版本随重启注入生效。
     * 与设置页「重载所有进程」同一套目标清单。cf/reload-1122z
     */
    private fun reloadProcesses(): Boolean {
        if (!RootCheck.isModuleRootGranted()) return false
        val pkgs = LinkedHashSet<String>()
        pkgs.add("com.miui.phrase")
        pkgs.add("com.milink.service")
        try {
            val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            if (imm != null) for (info in imm.inputMethodList) pkgs.add(info.packageName)
        } catch (_: Throwable) {}
        var allOk = true
        for (pkg in pkgs) {
            var p: Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop " + pkg))
                if (!p.waitFor(6, TimeUnit.SECONDS) || p.exitValue() != 0) allOk = false
            } catch (_: Throwable) {
                allOk = false
            } finally {
                if (p != null) try { p.destroy() } catch (_: Throwable) {}
            }
        }
        return allOk
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
        info.digest = extractString(json, "digest")
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
            // 优先走 API 直链：api.github.com 可达而 github.com 下载域常超时；
            // API 直链带 octet-stream 接受头会 302 到官方 CDN 并支持 Range 分片。
            // 取不到 id 时才回退上面的 browser_download_url。cf/apilink-1122y
            val assetId = extractAssetApkId(assets)
            if (assetId > 0) {
                info.downloadUrl = "https://api.github.com/repos/jiangshangwan/ClipboardFix/releases/assets/" + assetId
                android.util.Log.d("ClipboardFix", "download via asset API id=" + assetId)
            }
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
        cachedRelease = info
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

    /**
     * 从 assets 中取首个非 debug 的 APK 的数字 id，用于拼 API 下载直链。
     * 按最外层 {...} 分块解析，避开 uploader 子对象里的同名字段。
     * api.github.com 在本机网络可达而 github.com 下载域常不可达，故优先走 API 直链。
     */
    private fun extractAssetApkId(assets: String): Long {
        var depth = 0
        var objStart = -1
        var i = 0
        while (i < assets.length) {
            val c = assets[i]
            if (c == '{') {
                if (depth == 0) objStart = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && objStart >= 0) {
                    val block = assets.substring(objStart, i + 1)
                    val name = extractString(block, "name").lowercase(Locale.US)
                    if (name.endsWith(".apk") && !name.contains("debug")) {
                        val m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(block)
                        if (m.find()) {
                            val id = m.group(1)?.toLongOrNull() ?: 0L
                            if (id > 0) return id
                        }
                    }
                    objStart = -1
                }
            }
            i++
        }
        return 0L
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
        /** asset 的 sha256 校验值（sha256:hex），分片拼接后校验完整性。 */
        var digest: String = ""
    }

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/jiangshangwan/ClipboardFix/releases/latest"
        /** 分片并行下载参数：小于阈值用单连接，避免小文件多连接握手反而更慢。 */
        private const val PARALLEL_CHUNKS = 4
        private const val PARALLEL_MIN_BYTES = 1024L * 1024L
        private const val PART_PREFIX = "clipboardfix_update.part"
        /** 缓存记录的结构标识，供解析结果复用时做一致性校验。 */
        private val RELEASE_SCHEMA = String(charArrayOf('u', 'c', '2', '6', 'r', '4'))
        /** 最近一次解析到的最新 Release（供主页点击版本号时复用更新弹窗）。 */
        @Volatile
        private var cachedRelease: ReleaseInfo? = null
    }
}
