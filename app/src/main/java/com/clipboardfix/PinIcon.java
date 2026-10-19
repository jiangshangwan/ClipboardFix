package com.clipboardfix;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Base64;

/**
 * 剪贴板置顶按钮的图标（顶部横杠 + 上箭头）。
 *
 * <p><b>为什么用 base64 内嵌而不是放进 res/drawable</b>：本类的唯一调用方
 * {@link ClipboardPinHook} 运行在<b>被 hook 的目标进程</b>里（com.miui.phrase /
 * 各第三方输入法），那些进程只有目标应用自己的 Resources，模块 APK 的
 * {@code R.drawable.*} 资源 ID 在它们体内解析不到对应资源；而
 * {@code AssetManager.addAssetPath} 属隐藏 API、{@code createPackageContext}
 * 受 Android 11+ 包可见性限制，在现代 HyperOS 上都不可靠。
 * 故把这张仅 5227 字节的 PNG 以 base64 直接内嵌进 dex，运行时解码，任何进程都能用。
 *
 * <p>图标来源（用户 2026-10-19 提供替换）：桌面「置顶.png」378x400 纯白图形、
 * 透明底，内容 bbox 240x224。裁 bbox 后<b>等比缩放</b>至 653x609（不拉伸，
 * 避免宽高比从 1.07 压成 0.93），居中放入 653x702 透明画布（上下各留 46px，
 * 防止顶部横杠贴边被圆形按钮裁切）；LANCZOS 重采样 + 64 色量化，5.2KB。
 * 置顶按钮是蓝底正圆（#3B82F6），白色箭头天然高对比；调用方仍统一套白色
 * ColorFilter（见 {@link ClipboardPinHook#pinBtnOf}），对纯白图无副作用。
 */
public final class PinIcon {

    /** 置顶图标 PNG（653x702，透明底，纯白横杠+箭头）的 base64，运行时解码。 */
    private static final String PNG_BASE64 =
        "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";

    /** 解码结果缓存（图标全局只有一个，无需每条目重复解码）。 */
    private static volatile Bitmap cached;

    private PinIcon() {
    }

    /**
     * 取置顶按钮图标（首次调用时解码，之后走缓存）。
     *
     * @return 箭头位图；解码失败返回 {@code null}（调用方应回退到旧版文字样式）。
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
