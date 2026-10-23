package com.clipboardfix

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 检测「本模块应用（com.clipboardfix）是否已被 root 管理器授予 su 权限」。
 *
 * <p><b>语义</b>：不是「设备是否 root」，而是「HyperClipFix 自身是否被授权」。
 * 做法：在模块自己的进程里实际尝试 su -c id，成功拿到 uid=0 才认为已授权。
 * 只有先通过快速预检（存在 su 二进制 / KernelSU / APatch / test-keys）才会真正执行 su，
 * 避免在未 root 的设备上无意义地拉起 su；执行带超时，超时或失败都判定为未授权，绝不弹窗卡住。
 */
object RootCheck {

    /** 快速预检：这些线索任一存在才尝试执行 su（未 root 设备直接返回 false）。 */
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/magisk/.core/bin/su",
        "/data/adb/magisk/su"
    )

    /** 本模块已被授予 root 权限则 true（su 可用且返回 uid=0）。 */
    @JvmStatic
    fun isModuleRootGranted(): Boolean {
        if (!hasRootIndicator()) return false
        return trySu()
    }

    /** 快速预检：su 二进制 / KernelSU / APatch / 工程机 test-keys。 */
    private fun hasRootIndicator(): Boolean {
        for (p in SU_PATHS) {
            try {
                if (File(p).exists()) return true
            } catch (_: Throwable) {
                // 权限/IO 异常忽略
            }
        }
        try {
            if (File("/data/adb/ksu").exists()) return true
        } catch (_: Throwable) {
            // 忽略
        }
        try {
            if (File("/data/adb/ap").exists()) return true
        } catch (_: Throwable) {
            // 忽略
        }
        try {
            val tags = Build.TAGS
            if (tags != null && tags.contains("test-keys")) return true
        } catch (_: Throwable) {
            // 忽略
        }
        return false
    }

    /** 真正尝试 su：uid=0 视为已授权；超时/失败/异常均返回 false。 */
    private fun trySu(): Boolean {
        var p: Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    sb.append(line)
                }
            }
            val finished = p.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return false
            }
            if (p.exitValue() != 0) return false
            val out = sb.toString()
            return out.contains("uid=0") || out.contains("uid 0")
        } catch (_: Throwable) {
            // su 不存在 / 被拒 / 权限异常：一律视为未授权
            p?.destroy()
            return false
        }
    }
}
