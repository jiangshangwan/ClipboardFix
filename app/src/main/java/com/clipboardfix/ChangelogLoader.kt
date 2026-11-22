/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本说明的远端取数：读取仓库 Release 列表，按「版本标签 + 发布说明」组成弹卡分段。
 *
 * <p>列表按发布时间倒序返回；草稿、预发布以及没有说明的记录会被跳过。解析只做顶层对象
 * 切分与字段取值，不引入 JSON 库。结果一律回主线程投递，进程内保留最近一次成功结果，
 * 供网络不可达时复用——离线展示的仍是上次从远端取得的内容。(cl26b)
 */
internal object ChangelogLoader {

    private const val LIST_URL =
        "https://api.github.com/repos/jiangshangwan/ClipboardFix/releases?per_page=100"
    private const val CLIENT = "ClipboardFix-Changelog/2.1"
    // 取数诊断 TAG：与名单读取对称，落 logcat 供诊断导出采集失败细节。(clglog-1122h)
    private const val TAG = "ChangelogLoader"

    // 更新日志磁盘缓存：冷启动即可直接展示上次成功取得的内容，不再先闪加载占位。(clg-dsk-1121w)
    private const val PREFS_NAME = "cf_changelog_cache"
    private const val KEY_BLOCKS = "blocks"
    private const val KEY_AT = "saved_at"
    private const val SEP = "\u0001"
    private const val KV = "\u0002"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastGood: List<Pair<String, String>>? = null

    @Volatile
    private var appCtx: Context? = null

    /** 绑定上下文并回读磁盘缓存；进关于页时调用，使取数失败或冷启动仍能立即展示。(clg-ctx-1121w) */
    fun attach(context: Context) {
        appCtx = try {
            context.applicationContext
        } catch (_: Throwable) {
            null
        }
        if (lastGood == null) lastGood = readDisk()
    }

    /** 返回上次成功的版本说明：内存优先，冷启动内存为空则回读磁盘。(clg-cache-1121w) */
    fun cached(): List<Pair<String, String>>? {
        val mem = lastGood
        if (mem != null) return mem
        val disk = readDisk()
        if (disk != null) lastGood = disk
        return disk
    }

    private fun prefs(): SharedPreferences? {
        val c = appCtx ?: return null
        return try {
            c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readDisk(): List<Pair<String, String>>? {
        val p = prefs() ?: return null
        return try {
            val raw = p.getString(KEY_BLOCKS, null) ?: return null
            if (raw.isEmpty()) return null
            val out = ArrayList<Pair<String, String>>()
            for (part in raw.split(SEP)) {
                if (part.isEmpty()) continue
                val k = part.indexOf(KV)
                if (k <= 0) continue
                out.add(part.substring(0, k) to part.substring(k + 1))
            }
            if (out.isEmpty()) null else out
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDisk(blocks: List<Pair<String, String>>) {
        val p = prefs() ?: return
        try {
            val raw = blocks.joinToString(SEP) { it.first + KV + it.second }
            p.edit()
                .putString(KEY_BLOCKS, raw)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
            // 落盘失败不影响本次展示，内存缓存仍有效。(clg-dskerr-1121w)
        }
    }

    /**
     * 异步取回版本说明。[onReady] 收到可直接投递给弹卡的分段；[onFailed] 在取数或解析
     * 失败时回调，参数为此前缓存的内容（可能为 null）。两个回调均在主线程执行。
     */
    fun fetch(
        onReady: (List<Pair<String, String>>) -> Unit,
        onFailed: (List<Pair<String, String>>?) -> Unit,
    ) {
        Thread({
            try {
                // 取数诊断：区分网络层空响应、HTTP 非 200 与解析层无有效版本。(clgdiag-1122h)
                Log.d(TAG, "fetch begin: " + LIST_URL)
                val text = get(LIST_URL) ?: run {
                    Log.w(TAG, "fetch abort: empty response / non-200")
                    throw IllegalStateException("empty response")
                }
                Log.d(TAG, "fetch json len=" + text.length)
                val blocks = parse(text)
                Log.d(TAG, "parsed blocks=" + blocks.size)
                if (blocks.isEmpty()) throw IllegalStateException("no usable release")
                lastGood = blocks
                writeDisk(blocks) // 成功结果落盘，供下次冷启动直接展示。(clg-save-1121w)
                main.post { onReady(blocks) }
            } catch (t: Throwable) {
                // 失败细节落 logcat：异常类型与摘要，缓存命中情况随回调传给调用方。(clgfail-1122h)
                Log.w(TAG, "fetch failed: " + t.javaClass.simpleName + " / " + t.message)
                val hit = lastGood
                main.post { onFailed(hit) }
            }
        }, "cf-log-fetch").start()
    }

    private fun get(urlText: String): String? {
        val conn = URL(urlText).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", CLIENT)
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val code = conn.responseCode
            // 状态码入诊断日志：配额耗尽(403)、限流(429) 与网络失败据此区分。(clghttp-1122h)
            Log.d(TAG, "http code=" + code)
            if (code != 200) {
                null
            } else {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 顶层对象切分：字符串内部的花括号不参与层级计数。 */
    private fun objects(json: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var quoted = false
        var escaped = false
        for (i in json.indices) {
            val c = json[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (quoted && c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                quoted = !quoted
                continue
            }
            if (quoted) continue
            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && start >= 0) {
                    out.add(json.substring(start, i + 1))
                    start = -1
                }
            }
        }
        return out
    }

    private fun field(obj: String, key: String): String {
        val pattern = Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val m = pattern.find(obj) ?: return ""
        return unescape(m.groupValues[1])
    }

    /** JSON 字符串转义还原：常见控制符与 \uXXXX 均在此处理。 */
    private fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c)
                i++
                continue
            }
            val n = raw[i + 1]
            when (n) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    if (i + 5 < raw.length) {
                        val cp = raw.substring(i + 2, i + 6).toIntOrNull(16)
                        if (cp != null) {
                            sb.append(cp.toChar())
                            i += 6
                            continue
                        }
                    }
                    sb.append(n)
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    /** 纯文本弹卡不渲染 markdown：去掉标题记号、强调符与重复空行，保留编号列表。 */
    private fun plain(body: String): String {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val kept = ArrayList<String>()
        for (line in lines) {
            var s = line.trim()
            if (s.isEmpty()) {
                if (kept.isNotEmpty() && kept.last().isNotEmpty()) kept.add("")
                continue
            }
            s = s.replace(Regex("^#{1,6}\\s*"), "")
            if (s.equals("更新说明", true) || s.equals("Changelog", true)) continue
            s = s.replace("**", "").replace("`", "")
            s = s.replace(Regex("^[-*]\\s+"), "· ")
            kept.add(s.trimEnd())
        }
        while (kept.isNotEmpty() && kept.first().isEmpty()) kept.removeAt(0)
        while (kept.isNotEmpty() && kept.last().isEmpty()) kept.removeAt(kept.size - 1)
        return kept.joinToString("\n")
    }

    private fun parse(json: String): List<Pair<String, String>> {
        val blocks = ArrayList<Pair<String, String>>()
        for (obj in objects(json)) {
            if (Regex("\"draft\"\\s*:\\s*true").containsMatchIn(obj)) continue
            if (Regex("\"prerelease\"\\s*:\\s*true").containsMatchIn(obj)) continue
            val tag = field(obj, "tag_name").ifEmpty { field(obj, "name") }
            if (tag.isEmpty()) continue
            val body = plain(field(obj, "body"))
            if (body.isEmpty()) continue
            val clean = tag.removePrefix("v").removePrefix("V").trim()
            // 只展示不高于当前安装版本的发布记录：更高版本升级后才能解锁可见。
            // 解析不出数字的旧式 tag 保守保留，避免误删历史说明。cf/clgcap-1122s
            if (notHigherThanCurrent(clean)) {
                blocks.add(("V" + clean) to body)
            }
        }
        return blocks
    }

    /**
     * 版本 [candidate] 是否不高于当前安装版本。按点分段取前导数字逐段比较，段数不足补 0；
     * 任一段判出大小即返回，全等视为不高于。tag 带 v/V 前缀或非数字尾巴时先归一。
     * cf/clgcmp-1122s
     */
    private fun notHigherThanCurrent(candidate: String): Boolean {
        val cur = BuildConfig.VERSION_NAME.trim().trimStart('v', 'V')
        val seg = { s: String ->
            s.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        }
        val c = seg(candidate)
        val l = seg(cur)
        for (i in 0 until Math.max(c.size, l.size)) {
            val cv = c.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (cv > lv) return false
            if (cv < lv) return true
        }
        return true
    }
}
