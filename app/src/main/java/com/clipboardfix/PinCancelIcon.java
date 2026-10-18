package com.clipboardfix;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Base64;

/**
 * 剪贴板「取消置顶」按钮的图标：置顶上箭头 + 一条从右上到左下的斜线。
 *
 * <p>与 {@link PinIcon} 同款内嵌理由：调用方 {@link ClipboardPinHook} 运行在
 * 被 hook 的目标进程（com.miui.phrase / 各第三方输入法），模块 APK 的
 * {@code R.drawable.*} 在那些进程解析不到；故 base64 内嵌进 dex，运行时解码。
 *
 * <p><b>为什么不用用户提供的「不」符号原图</b>（用户 2026-10-19 反馈）：
 * 原图 1024² 白底灰符号，四周大量透明留白导致 FIT_CENTER 后视觉偏小，
 * 且线条细、放大后发糊，质量不佳。改为在 {@link PinIcon} 的上箭头位图
 * （653x702，深藏青 #FF272636）上 2x 超采样叠加同色圆头斜线
 * （归一化 (0.74,0.10)→(0.26,0.90)，线宽 62px），再缩回原尺寸抗锯齿。
 * 与置顶图标同源同尺寸（653x702），笔画粗细、视觉占比完全一致；
 * FASTOCTREE 量化 64 色控体积（6.3KB）。置顶按钮是蓝底正圆
 * （#3B82F6），调用方负责染白（见 {@link ClipboardPinHook#applyPinIcon}
 * 的白色 ColorFilter）。
 */
public final class PinCancelIcon {

    /** 「取消置顶」PNG（653x702，上箭头 + 斜线，深藏青）的 base64，运行时解码。 */
    private static final String PNG_BASE64 =
        "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" +
        "/A+EfhDqBghf3wAAAABJRU5ErkJggg==";

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
