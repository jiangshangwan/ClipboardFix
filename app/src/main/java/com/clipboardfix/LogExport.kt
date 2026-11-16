/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断报告导出：主页状态卡连续点击 10 次触发，汇总设备/版本/开关/剪贴板内容 +
 * hook 运行日志（root 读 LSPosed 模块日志）+ logcat（含 crash 缓冲），写成文本文件走系统分享。
 *
 * <p>报告生成在后台线程：logcat 与文件 IO 可能耗时，不阻塞 UI。剪贴板内容属敏感信息，
 * 只在用户主动导出时读取一次当前 primaryClip（Android 10+ 仅前台应用可读，此时 Activity
 * 正在前台，通常可读到）；文本全文 + 图片/URI 摘要，不导出图片二进制。导出文件落在
 * [Context.getCacheDir]（file_paths.xml 已配 cache-path），经 FileProvider 授权分享。
 * cf/lx-2611
 */
object LogExport {

    /** 报告传入快照：由 Activity 收集当前 UI 状态，避免本类持有 Activity 引用。 */
    data class Snapshot(
        val statusText: String,
        val imeCountText: String,
        val statusEnabled: Boolean,
        val rootGranted: Boolean,
        val enabledFeatures: Int,
        val moduleEnabled: Boolean,
        val imeUnlock: Boolean,
        val unlimitCount: Boolean,
        val unlimitTime: Boolean,
        val panelColor: Boolean,
        val panelMaterial: Boolean,
        val clipboardPin: Boolean,
        val sinkLayout: Boolean,
        val clipboardGroup: Boolean,
        val groupRemember: Boolean,
        val settingsIconMode: Int,
        val themeMode: Int,
        val updateDisabled: Boolean,
        val badgesVisible: Boolean,
        val latestVersion: String,
        /** 主线程点击瞬间读取的剪贴板文本（后台线程可能失去焦点读不到），可为 null。 */
        val clipboardText: String? = null,
    )

    /** Hook 日志节最大行数：超出只保留最近部分，防止报告体积失控。 */
    private const val MAX_LOG_LINES = 600

    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val lineStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 后台生成报告并分享。返回值通过 Toast 提示，不阻塞调用线程。 */
    fun export(context: Context, snap: Snapshot) {
        Thread {
            try {
                val text = buildReport(context, snap)
                val f = File(context.cacheDir, "ClipboardFix_diag_" + fileStamp.format(Date()) + ".txt")
                f.writeText(text, Charsets.UTF_8)
                share(context, f)
            } catch (t: Throwable) {
                toast(context, "日志导出失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            }
        }.apply { isDaemon = true; name = "cf-log-export" }.start()
    }

    /** 汇总完整报告。 */
    private fun buildReport(ctx: Context, s: Snapshot): String {
        val sb = StringBuilder()
        sb.append("===== ClipboardFix 诊断报告 =====\n")
        sb.append("导出时间：").append(lineStamp.format(Date())).append('\n')
        sb.append("（本文件由主页状态卡连点 10 次自动生成，请原样发送给开发者）\n\n")

        section(sb, "一、设备与系统") {
            line(sb, "机型（市场名）", safe { DeviceNames.marketName() })
            line(sb, "Build.BRAND", Build.BRAND)
            line(sb, "Build.MANUFACTURER", Build.MANUFACTURER)
            line(sb, "Build.MODEL", Build.MODEL)
            line(sb, "Build.DEVICE", Build.DEVICE)
            line(sb, "Build.PRODUCT", Build.PRODUCT)
            line(sb, "Android 版本", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
            line(sb, "系统增量版本", Build.VERSION.INCREMENTAL)
            line(sb, "系统版本名", safe { SysProps.get("ro.mi.os.version.name", SysProps.get("ro.miui.ui.version.name", "")) })
            line(sb, "HyperOS 版本", safe { SysProps.get("ro.mi.os.version.code", "") })
            line(sb, "CPU 架构", safe { abiInfo() })
            line(sb, "屏幕密度", safe { ctx.resources.displayMetrics.density.toString() })
            line(sb, "分辨率", safe { "${ctx.resources.displayMetrics.widthPixels}x${ctx.resources.displayMetrics.heightPixels}" })
        }

        section(sb, "二、版本信息") {
            line(sb, "模块版本", BuildConfig.VERSION_NAME + " (vc" + BuildConfig.VERSION_CODE + ")")
            line(sb, "构建日期", BuildConfig.BUILD_DATE)
            line(sb, "云端最新版本", if (s.latestVersion.isBlank()) "（本次未检查）" else s.latestVersion)
            line(sb, "剪贴板(phrase)版本", safe { phraseVersionOf(ctx) })
            line(sb, "LSPosed API", "102")
            line(sb, "当前默认输入法", safe {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            })
            // uiImeCountText 自带「已启用输入法：」前缀，直接整行输出避免标签重复
            sb.append(s.imeCountText).append('\n')
        }

        section(sb, "三、模块状态") {
            line(sb, "激活状态", s.statusText)
            line(sb, "模块已启用", s.statusEnabled.toString())
            line(sb, "Root 授权", s.rootGranted.toString())
            line(sb, "已启用功能数", s.enabledFeatures.toString())
            line(sb, "标识显隐", s.badgesVisible.toString())
        }

        section(sb, "四、功能开关") {
            line(sb, "全面屏键盘优化", s.imeUnlock.toString())
            line(sb, "解锁剪贴板数量", s.unlimitCount.toString())
            line(sb, "解锁剪贴板时间", s.unlimitTime.toString())
            line(sb, "优化面板背景", s.panelColor.toString())
            line(sb, "高级材质背景", s.panelMaterial.toString())
            line(sb, "条目置顶", s.clipboardPin.toString())
            line(sb, "下沉方案布局优化", s.sinkLayout.toString())
            line(sb, "剪贴板分组", s.clipboardGroup.toString())
            line(sb, "记忆分组位置", s.groupRemember.toString())
            line(sb, "设置图标位置", s.settingsIconMode.toString())
            line(sb, "主题模式", s.themeMode.toString())
            line(sb, "禁用更新检查", s.updateDisabled.toString())
        }

        section(sb, "五、当前剪贴板内容（点击瞬间的系统剪贴板）") {
            appendClipboard(s, sb)
        }

        section(sb, "六、Hook 运行日志（LSPosed 模块日志，root 采集）") {
            val lsp = captureLspLogs()
            if (lsp.isBlank()) {
                sb.append("（未取得：无 root 授权、无 LSPosed 日志，或模块尚未运行）\n")
            } else {
                sb.append(lsp)
                if (!lsp.endsWith("\n")) sb.append('\n')
            }
        }

        section(sb, "七、系统日志 logcat（需 root，补充崩溃栈）") {
            val crash = suRun("logcat -b crash -d -v time -t 500")
            sb.append("-- crash 缓冲 --\n")
            sb.append(if (crash.isBlank()) "（无崩溃记录或未取得）\n" else crash)
            if (crash.isNotBlank() && !crash.endsWith("\n")) sb.append('\n')
            sb.append("\n-- main 缓冲（关键词过滤） --\n")
            val logcat = captureLogcat()
            sb.append(if (logcat.isBlank()) "（未取得：无 root 授权或 logcat 不可用）\n" else logcat)
        }

        sb.append("\n===== 报告结束 =====\n")
        return sb.toString()
    }

    private fun section(sb: StringBuilder, title: String, body: () -> Unit) {
        sb.append(title).append('\n')
        sb.append("----------------------------------------\n")
        body()
        sb.append('\n')
    }

    private fun line(sb: StringBuilder, key: String, value: Any?) {
        sb.append(key).append("：").append(value ?: "").append('\n')
    }

    /** 从快照写入剪贴板文本（点击瞬间主线程读取，保证前台焦点可读）。 */
    private fun appendClipboard(s: Snapshot, sb: StringBuilder) {
        val text = s.clipboardText
        if (text == null) {
            sb.append("（剪贴板为空，或读取时无前台权限）\n")
            return
        }
        val limited = if (text.length > 4000) text.substring(0, 4000) + "\n…(已截断，共 ${text.length} 字)" else text
        line(sb, "文本内容", limited)
        sb.append("（更完整的复制历史见下方 Hook 日志中的内容指纹/条目记录）\n")
    }

    /** 主线程点击瞬间读取系统剪贴板文本；失败返回 null。 */
    fun readClipboardText(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip == null || clip.itemCount == 0) return null
            val parts = ArrayList<String>()
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                item.text?.toString()?.let { parts.add(it) }
                    ?: item.uri?.toString()?.let { parts.add("[URI] $it") }
            }
            if (parts.isEmpty()) "[无文本内容]" else parts.joinToString("\n")
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * root 读取 LSPosed 模块日志：hook 各进程经框架输出的诊断行都汇聚在
     * `/data/adb/lspd/log/modules_*.log`（lspd 守护进程落盘，滚动保留）。
     * 取最新一个日志文件的尾部，按本模块标记过滤后截取，防止报告体积失控。
     * 失败（无 root / 无日志）返回空串，由调用方给出提示。cf/lg-2611
     */
    private fun captureLspLogs(): String {
        val files = suRun("ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null")
                .lines().map { it.trim() }.filter { it.startsWith("/") }
        if (files.isEmpty()) return ""
        val newest = files.first()
        // 尾部 256 KiB 足够覆盖一次问题复现窗口；模块行带 [com.clipboardfix,...] 作用域标记，
        // 也兼容仅带模块 TAG（[ClipboardFix]/[CrossDevice]/[PanelColor]/[Pin]/[IME]…）的行。
        val raw = suRun("tail -c 262144 '" + newest.replace("'", "'\\''") + "'")
        val kept = ArrayList<String>()
        for (l in raw.lines()) {
            if (l.isEmpty()) continue
            // 启动横幅行与报告前两节信息重复，跳过
            if (l.contains(ModulePrefs.AUTHOR_SIGNATURE) || l.contains("HyperClipFix v")) continue
            if (l.contains("com.clipboardfix") || l.contains("[ClipboardFix]") ||
                l.contains("[CrossDevice]") || l.contains("[PanelColor]") ||
                l.contains("[Pin]") || l.contains("[IME]") || l.contains("[Perm]") ||
                l.contains("[DexKit]") || l.contains("[Group]")
            ) {
                kept.add(l)
            }
        }
        if (kept.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("-- 来源：").append(newest)
                .append("，共 ").append(kept.size).append(" 行（已按模块过滤，尾部优先） --\n")
        // 行数上限：取最近 MAX_LOG_LINES 行（日志按时间追加，末尾即最新）
        val start = if (kept.size > MAX_LOG_LINES) kept.size - MAX_LOG_LINES else 0
        if (start > 0) sb.append("…(更早 ").append(start).append(" 行已省略)…\n")
        for (i in start until kept.size) sb.append(kept[i]).append('\n')
        return sb.toString()
    }

    /** root 执行单条 shell 命令并返回 stdout（UTF-8）；失败或无 root 返回空串。 */
    private fun suRun(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            p.destroy()
            out
        } catch (t: Throwable) {
            ""
        }
    }

    /** root 抓最近 logcat（main 缓冲）；失败返回空串。过滤无关噪声，保留模块与系统崩溃信息。 */
    private fun captureLogcat(): String {
        val raw = suRun("logcat -d -v time -t 3000")
        if (raw.isBlank()) return ""
        val sb = StringBuilder()
        var count = 0
        for (l in raw.lines()) {
            // 保留模块 TAG、崩溃/异常、以及系统关键行；丢弃大量无关应用日志
            if (l.contains("ClipboardFix") || l.contains("LSPosed") ||
                l.contains("AndroidRuntime") || l.contains("FATAL") ||
                l.contains("Exception") || l.contains(" ANR ")
            ) {
                sb.append(l).append('\n')
                count++
                if (count >= 3000) break
            }
        }
        return sb.toString()
    }

    /** 通过 FileProvider 授权并走系统分享（必须在主线程启动 Activity）。 */
    private fun share(ctx: Context, f: File) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ClipboardFix 诊断报告 " + BuildConfig.VERSION_NAME)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "发送诊断报告")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
                toast(ctx, "诊断报告已生成，请选择发送方式")
            } catch (t: Throwable) {
                toast(ctx, "无法打开分享：${t.javaClass.simpleName}")
            }
        }
    }

    private fun phraseVersionOf(ctx: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val info: PackageInfo = ctx.packageManager.getPackageInfo("com.miui.phrase", 0)
            info.versionName ?: info.versionCode.toString()
        } catch (t: Throwable) { "未知" }
    }

    private fun abiInfo(): String {
        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else Build.CPU_ABI
        return abi
    }

    private fun safe(block: () -> String): String = try { block() } catch (t: Throwable) { "未知" }

    private fun toast(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}
