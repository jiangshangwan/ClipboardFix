package com.clipboardfix;

import android.content.SharedPreferences;

/**
 * 功能开关的跨进程配置（LSPosed 远程偏好）。
 *
 * <p>App 侧经 {@code XposedService.getRemotePreferences} 写入，hook 侧经
 * {@code XposedInterface.getRemotePreferences} 读取，两侧共享同一份数据。
 * 开关在目标进程下次加载时生效（重启输入法 / 重启手机）。
 *
 * <p>默认值即「还原到加开关之前的行为」：
 * 全面屏优化解锁默认开启，数量上限解锁默认关闭。
 */
public final class ModulePrefs {

    /** 远程偏好组名。 */
    public static final String GROUP = "clipboardfix_settings";

    /** 启用全面屏优化功能（解锁 MIUI 键盘全面屏优化 + 放行输入法权限），默认开启。 */
    public static final String KEY_IME_UNLOCK = "ime_fullscreen_unlock";

    /** 解锁剪贴板/常用语数量上限，默认关闭。 */
    public static final String KEY_UNLIMIT_COUNT = "clipboard_unlimit_count";

    public static final boolean DEFAULT_IME_UNLOCK = true;
    public static final boolean DEFAULT_UNLIMIT_COUNT = false;

    private ModulePrefs() {
    }

    /** hook 侧读取远程偏好；不可用时返回 null（调用方回退默认值）。
     *
     * <p><b>引导安全</b>：system_server 进程内一律不发起跨进程调用——开机早期
     * AMS/对端进程可能尚未就绪，阻塞式 binder 调用会卡死引导线程导致无法开机
     * （已知真实事故），故系统进程永远走默认值；功能开关只在普通应用进程
     * （输入法 / 剪贴板和常用语）内生效，那里最坏只会崩应用自身，不影响开机。
     */
    private static SharedPreferences hookPrefs() {
        if (isSystemServerProcess()) {
            return null;
        }
        try {
            return XposedInit.module().getRemotePreferences(GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 进程名判定；判定失败按最保守处理（视为系统进程，不发起跨进程调用）。 */
    private static boolean isSystemServerProcess() {
        try {
            return "system_server".equals(android.app.Application.getProcessName());
        } catch (Throwable t) {
            return true;
        }
    }

    /** 全面屏优化解锁是否启用（hook 侧）。 */
    public static boolean isImeUnlockEnabled() {
        SharedPreferences p = hookPrefs();
        return p == null || p.getBoolean(KEY_IME_UNLOCK, DEFAULT_IME_UNLOCK);
    }

    /** 数量上限解锁是否启用（hook 侧）。 */
    public static boolean isUnlimitCountEnabled() {
        SharedPreferences p = hookPrefs();
        return p != null && p.getBoolean(KEY_UNLIMIT_COUNT, DEFAULT_UNLIMIT_COUNT);
    }
}
