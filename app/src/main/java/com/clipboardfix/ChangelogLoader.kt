/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.os.Handler
import android.os.Looper
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

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastGood: List<Pair<String, String>>? = null

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
                val text = get(LIST_URL) ?: throw IllegalStateException("empty response")
                val blocks = parse(text)
                if (blocks.isEmpty()) throw IllegalStateException("no usable release")
                lastGood = blocks
                main.post { onReady(blocks) }
            } catch (ignored: Throwable) {
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
            if (conn.responseCode != 200) {
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
            blocks.add(("V" + clean) to body)
        }
        return blocks
    }
}
