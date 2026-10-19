package com.clipboardfix;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Base64;

/**
 * 剪贴板「取消置顶」按钮的图标：顶部横杠 + 上箭头 + 一条从右上到左下的斜线。
 *
 * <p>与 {@link PinIcon} 同款内嵌理由：调用方 {@link ClipboardPinHook} 运行在
 * 被 hook 的目标进程（com.miui.phrase / 各第三方输入法），模块 APK 的
 * {@code R.drawable.*} 在那些进程解析不到；故 base64 内嵌进 dex，运行时解码。
 *
 * <p>图标来源（用户 2026-10-19 提供替换）：桌面「取消.png」378x400
 * 纯白图形、透明底，纹样与置顶图标同源（同款横杠 + 箭头 + 同粗细斜线），
 * 内容 bbox 240x224。处理方式与 {@link PinIcon} 完全一致：裁 bbox 后
 * 等比缩放至 653x609 居中放入 653x702 透明画布（上下各留 46px），
 * LANCZOS + 64 色量化 8.2KB，两个图标视觉权重严格一致。
 * 置顶按钮是蓝底正圆（#3B82F6），
 * 白色斜线天然高对比；调用方统一套白色 ColorFilter（见
 * {@link ClipboardPinHook#applyPinIcon}），对纯白图无副作用。
 */
public final class PinCancelIcon {

    /** 「取消置顶」PNG（653x702，透明底，纯白横杠+箭头+斜线）的 base64，运行时解码。 */
    private static final String PNG_BASE64 =
        "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";

    /** 解码结果缓存（图标全局只有一个，无需每条目重复解码）。 */
    private static volatile Bitmap cached;

    private PinCancelIcon() {
    }

    /**
     * 取「取消置顶」按钮图标（首次调用时解码，之后走缓存）。
     *
     * @return 符号位图；解码失败返回 {@code null}（调用方应回退为置顶图标）。
     */
    public static Bitmap get() {
        Bitmap b = cached;
        if (b != null) {
            return b;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(PNG_BASE64);
            b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            b = null;
        }
        cached = b;
        return b;
    }
}
