/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 写入系统剪贴板时的内容快照。cf/cp-2611
 *
 * <p>只记录含 content:// 引用的 ClipData，避免普通文本复制刷屏。
 * 每条快照含：调用栈前几帧、每个 item 的 URI/MIME/文本摘要、目标应用是否持有读授权。
 * 用于区分两类问题：目标应用缺读授权（写入侧未带 grant），或复制走了不含文件的分支。
 */
object ClipboardCopyDiag {
    private val installed = AtomicBoolean(false)
    private const val TARGET_APP = "com.tencent.mm"
    private const val GRANT_READ = Intent.FLAG_GRANT_READ_URI_PERMISSION

    /** 在每个需要观察的进程里调用一次；重复调用安全。 */
    @JvmStatic
    fun init() {
        XposedInit.log("[CopyDiag] init called")
        if (!installed.compareAndSet(false, true)) {
            XposedInit.log("[CopyDiag] already installed")
            return
        }
        try {
            val methods: List<Executable> = ClipboardManager::class.java.declaredMethods
                .filter { it.name == "setPrimaryClip" && it.parameterTypes.isNotEmpty()
                        && ClipData::class.java.isAssignableFrom(it.parameterTypes[0]) }
            if (methods.isEmpty()) {
                XposedInit.log("[CopyDiag] no setPrimaryClip method found")
                return
            }
            for (m in methods) {
                XposedInit.hook(m) { chain ->
                    try {
                        val clip = chain.getArg(0) as? ClipData
                        if (clip != null) snapshot(clip)
                    } catch (t: Throwable) {
                        XposedInit.log("[CopyDiag] snapshot err - " + t)
                    }
                    chain.proceed()
                }
            }
            XposedInit.log("[CopyDiag] installed " + methods.size + " setter(s)")
        } catch (t: Throwable) {
            XposedInit.log("[CopyDiag] init failed - " + t)
        }
    }

    /** 当前 Application；hook 触发时通常已就绪，取不到返回 null。 */
    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    /** 记录一次写入的摘要；关心 item.uri 为 content，或 text 里带 content:// 的条目。cf/cp-2611 */
    private fun snapshot(clip: ClipData) {
        val n = clip.itemCount
        if (n <= 0) return
        val uris = ArrayList<Uri>()
        var textCarriedUri = false
        for (i in 0 until n) {
            val item = try { clip.getItemAt(i) } catch (_: Throwable) { continue }
            val u = try { item.uri } catch (_: Throwable) { null }
            if (u != null && "content" == u.scheme) uris.add(u)
            val t = try { item.text?.toString() } catch (_: Throwable) { null }
            if (t != null && t.contains("content://")) {
                textCarriedUri = true
                val parsed = try { Uri.parse(t.trim()) } catch (_: Throwable) { null }
                if (parsed != null && "content" == parsed.scheme) uris.add(parsed)
            }
        }
        if (uris.isEmpty() && !textCarriedUri) return
        XposedInit.log("[CopyDiag] raw items=" + n + " label=" + brief(clip.description?.label?.toString()))

        val ctx = appContext()
        val stack = Thread.currentThread().stackTrace
            .take(10)
            .filter { !it.className.startsWith("java.") && !it.className.startsWith("kotlin.") }
            .take(6)
            .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }

        val sb = StringBuilder("[CopyDiag] HIT items=").append(n)
            .append(" uris=").append(uris.size)
            .append(" ctx=").append(if (ctx == null) "null" else "ok")
            .append(" stack=").append(stack)
        XposedInit.log(sb.toString())

        for (i in 0 until n) {
            val item = try { clip.getItemAt(i) } catch (_: Throwable) { continue }
            val u = try { item.uri } catch (_: Throwable) { null }
            val intentFlags = try { item.intent?.flags } catch (_: Throwable) { null }
            val b = StringBuilder("[CopyDiag] item[").append(i).append(']')
                .append(" mime=").append(safe { clip.description.getMimeType(i) })
                .append(" uri=").append(u?.toString() ?: "-")
                .append(" grantInIntent=").append(if (intentFlags == null) "-" else
                    ((intentFlags and GRANT_READ) != 0).toString())
                .append(" text=").append(briefUri(safe { item.text?.toString() }))
                .append(" providerRead=").append(if (ctx == null) "-" else safe { probeRead(ctx, u) })
            XposedInit.log(b.toString())
        }
        XposedInit.log("[CopyDiag] " + TARGET_APP + " uidGrant=" +
                (if (ctx == null) "-" else safe { checkTargetGrant(ctx, uris) }))
    }

    /** 当前进程自身能否读到该 URI（只探测，不消费数据）。 */
    private fun probeRead(ctx: Context, uri: Uri?): String {
        if (uri == null) return "-"
        return try {
            val fd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
            try {
                if (fd == null) "no" else "yes"
            } finally {
                fd?.close()
            }
        } catch (t: Throwable) {
            "err:" + t.javaClass.simpleName
        }
    }

    /** 微信是否持有这些 URI 的持久/运行期读授权。 */
    private fun checkTargetGrant(ctx: Context, uris: List<Uri>): String {
        val uid = try {
            ctx.packageManager.getPackageUid(TARGET_APP, 0)
        } catch (_: Throwable) {
            return "noUid"
        }
        val out = StringBuilder()
        for (u in uris) {
            val granted = try {
                ctx.checkUriPermission(u, -1, uid, GRANT_READ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
            out.append(if (granted) "1" else "0")
        }
        return out.toString()
    }

    private fun brief(s: String?): String = briefUri(s, 28)

    /** URI 文本保留较长前缀，确保 authority + itemId 可见。 */
    private fun briefUri(s: String?, max: Int = 96): String {
        if (s.isNullOrEmpty()) return "-"
        val one = s.replace('\n', ' ').replace('\r', ' ').trim()
        return if (one.length <= max) one else one.substring(0, max) + "…"
    }

    private inline fun safe(block: () -> Any?): String {
        return try {
            block()?.toString() ?: "-"
        } catch (t: Throwable) {
            "err:" + t.javaClass.simpleName
        }
    }
}
