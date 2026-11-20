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
import java.util.TreeMap

/**
 * 捐赠名单的远端取数：读取公开分享页内嵌的表格单元格，按「渠道 / 昵称 / 金额」还原成名单行。
 *
 * <p>公开分享页在匿名访问时把单元格写作 ["q",["文本",x,y]]，x 区分列、y 区分行。取数在后台
 * 线程完成，结果回主线程投递；进程内保留最近一次成功结果，失败时回退内置兜底。分享页对匿名
 * 访问存在返回空壳的竞态，故以不同标识多次尝试，命中数据标记即止。(dnrl-doc-1120z)
 */
internal object DonorListLoader {

    private const val PAGE_URL =
        "https://docs.qq.com/sheet/DR1NKc0JkRURDTWNB?tab=BB08J2"
    private const val TAG = "CFDonor"
    private const val MARKER = "%22q%22"

    // 名单磁盘缓存：冷启动即可展示上次成功获取的云端数据，避免先闪内置兜底。(dnrdsk-1120w)
    private const val PREFS_NAME = "cf_donor_list"
    private const val KEY_ROWS = "rows"
    private const val KEY_AT = "saved_at"
    private const val SEP = "\u0001"

    private val UAS = arrayOf(
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
    )

    private const val COL_CHANNEL_MAX = 150.0
    private const val COL_NICK_MAX = 280.0
    private const val COL_X_MIN = 95.0
    private const val COL_X_MAX = 400.0
    private const val ROW_Y_MIN = 35.0

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastGood: List<String>? = null

    @Volatile
    private var appCtx: Context? = null

    /** 绑定应用上下文，供磁盘缓存读写使用；取不到上下文时退化为纯内存缓存。(dnrctx-1120w) */
    fun attach(context: Context) {
        appCtx = try {
            context.applicationContext
        } catch (_: Throwable) {
            null
        }
        if (lastGood == null) lastGood = readDisk()
    }

    /**
     * 返回上次成功获取的名单，供打开弹卡时优先展示：先用内存，冷启动内存为空则回读磁盘。
     * 从未成功获取过才返回 null。(dnrcache-1120w)
     */
    fun cached(): List<String>? {
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

    private fun readDisk(): List<String>? {
        val p = prefs() ?: return null
        return try {
            val raw = p.getString(KEY_ROWS, null) ?: return null
            if (raw.isEmpty()) return null
            val rows = raw.split(SEP).filter { it.isNotEmpty() }
            if (rows.isEmpty()) null else rows
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDisk(rows: List<String>) {
        val p = prefs() ?: return
        try {
            p.edit()
                .putString(KEY_ROWS, rows.joinToString(SEP))
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
            // 磁盘写入失败不影响本次展示，内存缓存仍有效。(dnrdskerr-1120w)
        }
    }

    /**
     * 异步取回名单。[onReady] 收到解析后的行列表；[onFailed] 在取数或解析失败时回调，
     * 参数为此前缓存的内容（可能为 null）。两个回调均在主线程执行。
     */
    fun fetch(
        onReady: (List<String>) -> Unit,
        onFailed: (List<String>?) -> Unit,
    ) {
        Thread({
            try {
                // 取数诊断：区分网络层失败与解析层失败，便于定位回退原因。(dfetch-1120q)
                Log.d(TAG, "fetch begin: " + PAGE_URL)
                val html = get(PAGE_URL) ?: run {
                    Log.w(TAG, "fetch abort: empty response / non-200")
                    throw IllegalStateException("empty response")
                }
                Log.d(TAG, "fetch html len=" + html.length)
                val rows = parse(html)
                Log.d(TAG, "parsed rows=" + rows.size)
                if (rows.isEmpty()) throw IllegalStateException("no usable donor row")
                lastGood = rows
                writeDisk(rows) // 成功结果落盘，供下次冷启动直接展示云端名单。(dnrsave-1120w)
                main.post { onReady(rows) }
            } catch (t: Throwable) {
                // 回退诊断：打印异常类型与摘要，缓存命中情况随回调传给调用方。(dfail-1120q)
                Log.w(TAG, "fetch failed: " + t.javaClass.simpleName + " / " + t.message)
                val hit = lastGood
                main.post { onFailed(hit) }
            }
        }, "cf-donor-fetch").start()
    }

    /** 以不同标识轮换请求，命中数据标记即返回；分享页空壳与全量随机出现，不缓存 cookie。 */
    private fun get(urlText: String): String? {
        var last: String? = null
        // 空壳竞态：换标识重试有助于拿到含数据的版本。(dretry-1120z)
        for (round in UAS.indices) {
            val conn = URL(urlText).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", UAS[round])
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val code = conn.responseCode
                Log.d(TAG, "http round=" + round + " code=" + code)
                if (code != 200) continue
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                last = body
                if (body.contains(MARKER)) return body
            } finally {
                conn.disconnect()
            }
        }
        return last
    }

    /** 还原单元格文本中的少量 URL 转义；引号以 %22 形式出现，中文为明文。 */
    private fun unescape(raw: String): String =
        raw.replace("%22", "\"").replace("%20", " ").replace("%26", "&")

    /** 单元格坐标聚类：按 y 归行、按 x 归列，舍去表头、行号列与三列不全的行。 */
    private fun parse(html: String): List<String> {
        val text = html.replace("%22", "\"")
        val cell = Regex("\\[\"q\",\\[\"([^\"]*)\",([0-9.]+),([0-9.]+)\\]\\]")
        val grid = TreeMap<Int, Array<String?>>()
        for (m in cell.findAll(text)) {
            val value = m.groupValues[1]
            val x = m.groupValues[2].toDoubleOrNull() ?: continue
            val y = m.groupValues[3].toDoubleOrNull() ?: continue
            if (x < COL_X_MIN || x > COL_X_MAX || y < ROW_Y_MIN) continue
            val col = when {
                x < COL_CHANNEL_MAX -> 0
                x < COL_NICK_MAX -> 1
                else -> 2
            }
            val row = Math.round(y.toFloat())
            val cells = grid.getOrPut(row) { arrayOfNulls(3) }
            cells[col] = unescape(value)
        }
        val out = ArrayList<String>()
        for ((_, cells) in grid) {
            val channel = cells[0] ?: continue
            val nick = cells[1] ?: continue
            val amount = cells[2] ?: continue
            if (channel == "渠道" || nick == "昵称" || amount == "金额") continue
            out.add(channel.trim() + " / " + nick.trim() + " — " + normalizeAmount(amount))
        }
        return out
    }

    /** 金额文本统一成「数字 + 空格 + 元」，与内置名单写法一致。 */
    private fun normalizeAmount(raw: String): String {
        val s = raw.trim().replace(" ", "")
        val num = if (s.endsWith("元")) s.substring(0, s.length - 1) else s
        return if (num.isEmpty()) s else num + " 元"
    }
}
