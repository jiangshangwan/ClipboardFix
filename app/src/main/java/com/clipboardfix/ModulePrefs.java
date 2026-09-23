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

    /** hook 侧读取远程偏好；不可用时返回 null（调用方回退默认值）。 */
    private static SharedPreferences hookPrefs() {
        try {
            return XposedInit.module().getRemotePreferences(GROUP);
        } catch (Throwable t) {
            return null;
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
