/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.app.Application
import android.content.SharedPreferences

/**
 * 功能开关的跨进程配置（LSPosed 远程偏好）。
 *
 * <p>App 侧经 [XposedInit.module].getRemotePreferences 写入，hook 侧经
 * XposedInterface.getRemotePreferences 读取，两侧共享同一份数据。
 * 开关在目标进程下次加载时生效（重启输入法 / 重启手机）。
 *
 * <p>默认值即「还原到加开关之前的行为」：
 * 全面屏优化解锁默认开启，数量上限解锁默认关闭；
 * 剪贴板面板背景优化属于新特性，默认关闭，由用户按需开启。
 * 剪贴板时间限制解锁默认关闭——72 小时自动清除属于待修复缺陷，
 * 开关仅用于在需要时恢复原生行为（默认即恢复原生 72 小时自动清除）。
 */
object ModulePrefs {

    /** 远程偏好组名。 */
    const val GROUP = "clipboardfix_settings"

    /** 启用全面屏优化功能（解锁 MIUI 键盘全面屏优化 + 放行输入法权限），默认开启。 */
    const val KEY_IME_UNLOCK = "ime_fullscreen_unlock"

    /** 解锁剪贴板/常用语数量上限，默认关闭。 */
    const val KEY_UNLIMIT_COUNT = "clipboard_unlimit_count"

    /** 解锁剪贴板时间限制（72 小时自动清除），默认关闭。 */
    const val KEY_UNLIMIT_TIME = "clipboard_unlimit_time"

    /** 剪贴板/常用语面板背景跟随底部功能栏取色，默认关闭。 */
    const val KEY_PANEL_COLOR = "clipboard_panel_color"

    /** 剪贴板条目置顶（右滑置顶、清空保留），默认关闭。 */
    const val KEY_CLIPBOARD_PIN = "clipboard_pin_entry"

    /** 剪贴板分组筛选（面板内「全部 / 链接 / 图片 / 文字」切换），默认关闭。 */
    const val KEY_CLIPBOARD_GROUP = "clipboard_group_filter"

    /** 版权标识：被启动日志引用故进 dex 字符串池——抄袭副本原样携带即自证来源。
     *  尾段 CF-JSW-2026 为隐蔽指纹（举证抄袭用，勿删勿改）。 */
    const val AUTHOR_SIGNATURE = "Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com> · GPL-3.0 · CF-JSW-2026"

    /** 构建序列标记（随启动日志输出）。 */
    const val BUILD_MARK = "261102-jsW"

    /** 记忆剪贴板分组位置（关闭面板再打开停留上次分组），默认关闭。 */
    const val KEY_CLIPBOARD_GROUP_REMEMBER = "clipboard_group_remember"

    const val DEFAULT_IME_UNLOCK = true
    const val DEFAULT_UNLIMIT_COUNT = false
    const val DEFAULT_UNLIMIT_TIME = false
    const val DEFAULT_PANEL_COLOR = false
    const val DEFAULT_CLIPBOARD_PIN = false
    const val DEFAULT_CLIPBOARD_GROUP = false
    const val DEFAULT_CLIPBOARD_GROUP_REMEMBER = false

    /** hook 侧读取远程偏好；不可用时返回 null（调用方回退默认值）。 */
    private fun hookPrefs(): SharedPreferences? {
        if (isSystemServerProcess()) return null
        return try {
            XposedInit.module().getRemotePreferences(GROUP)
        } catch (_: Throwable) {
            null
        }
    }

    /** 进程名判定；判定失败按最保守处理（视为系统进程，不发起跨进程调用）。 */
    private fun isSystemServerProcess(): Boolean {
        return try {
            "system_server" == Application.getProcessName()
        } catch (_: Throwable) {
            true
        }
    }

    /** 全面屏优化解锁是否启用（hook 侧）。 */
    @JvmStatic
    fun isImeUnlockEnabled(): Boolean {
        val p = hookPrefs()
        return p == null || p.getBoolean(KEY_IME_UNLOCK, DEFAULT_IME_UNLOCK)
    }

    /** 数量上限解锁是否启用（hook 侧）。 */
    @JvmStatic
    fun isUnlimitCountEnabled(): Boolean {
        val p = hookPrefs()
        return p != null && p.getBoolean(KEY_UNLIMIT_COUNT, DEFAULT_UNLIMIT_COUNT)
    }

    /** 时间限制解锁是否启用（hook 侧）。默认关闭。 */
    @JvmStatic
    fun isUnlimitTimeEnabled(): Boolean {
        val p = hookPrefs()
        return p == null || p.getBoolean(KEY_UNLIMIT_TIME, DEFAULT_UNLIMIT_TIME)
    }

    /** 剪贴板面板背景优化是否启用（hook 侧）。默认关闭，需用户在「功能设置」里手动开启。 */
    @JvmStatic
    fun isPanelColorEnabled(): Boolean {
        val p = hookPrefs()
        return p != null && p.getBoolean(KEY_PANEL_COLOR, DEFAULT_PANEL_COLOR)
    }

    /** 剪贴板条目置顶是否启用（hook 侧）。默认关闭，需用户在「功能设置」里手动开启。 */
    @JvmStatic
    fun isClipboardPinEnabled(): Boolean {
        val p = hookPrefs()
        return p != null && p.getBoolean(KEY_CLIPBOARD_PIN, DEFAULT_CLIPBOARD_PIN)
    }

    /** 剪贴板分组筛选是否启用（hook 侧）。默认关闭，需用户在「功能设置」里手动开启。 */
    @JvmStatic
    fun isClipboardGroupEnabled(): Boolean {
        val p = hookPrefs()
        val raw = p?.getBoolean(KEY_CLIPBOARD_GROUP, DEFAULT_CLIPBOARD_GROUP)
        android.util.Log.d("ClipboardFixGroup", "isClipboardGroupEnabled: prefsNull=${p == null} raw=$raw default=$DEFAULT_CLIPBOARD_GROUP")
        return p != null && p.getBoolean(KEY_CLIPBOARD_GROUP, DEFAULT_CLIPBOARD_GROUP)
    }

    /** 记忆剪贴板分组位置是否启用（hook 侧）。默认关闭。 */
    @JvmStatic
    fun isGroupRememberEnabled(): Boolean {
        val p = hookPrefs()
        return p != null && p.getBoolean(KEY_CLIPBOARD_GROUP_REMEMBER, DEFAULT_CLIPBOARD_GROUP_REMEMBER)
    }
}
