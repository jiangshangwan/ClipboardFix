package com.clipboardfix;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Base64;

/**
 * 剪贴板置顶按钮的图标（上箭头）。
 *
 * <p><b>为什么用 base64 内嵌而不是放进 res/drawable</b>：本类的唯一调用方
 * {@link ClipboardPinHook} 运行在<b>被 hook 的目标进程</b>里（com.miui.phrase /
 * 各第三方输入法），那些进程只有目标应用自己的 Resources，模块 APK 的
 * {@code R.drawable.*} 资源 ID 在它们体内解析不到对应资源；而
 * {@code AssetManager.addAssetPath} 属隐藏 API、{@code createPackageContext}
 * 受 Android 11+ 包可见性限制，在现代 HyperOS 上都不可靠。
 * 故把这张仅 4693 字节的 PNG 以 base64 直接内嵌进 dex，运行时解码，任何进程都能用。
 *
 * <p>原图参数：653x702、透明底、深藏青 #FF272636。置顶按钮是蓝底正圆
 * （#3B82F6），深色箭头在蓝底上对比不足，调用方负责着色（见
 * {@link ClipboardPinHook#pinBtnOf} 里的白色 ColorFilter）。
 */
public final class PinIcon {

    /** 上箭头 PNG（653x702，透明底，#FF272636）的 base64，运行时解码。 */
    private static final String PNG_BASE64 =
        "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" +
        "5ejQY1eQHw5vctFbjh49dgT5bNP1+fUaPY5oD3JheIuT3nJ06bE1yNH3Uk54y9Glx9Ygbx9WXHKWo0+PbUFeHt2i5VTXNXrc" +
        "Z3aQ24+NbjD7jn3dZGCnHudn3m/vfFpwZdYNBvQ4xsx3LGsdN3jJZl6vHmd91L9/YtyMh9Ab6/Q4wYnlqeMc7KiY/hD6otG4" +
        "bj02r0+bZmyd6NQgP7Ca1q/H+Wn37PGVKe86ulc79jjtnj25wmfR0bCOPTZvdTz6HRH9E7tZPXtsHjw0y28O/fyNyR8/bTiq" +
        "a4/NqxOjbK4f/vmDbjT69jghcmtw9Od/2v/p9pdNB3Xusfn2gcaFaT9/fOX2T185Zzund4/NOy/cnuO59ek/n3/y0QsXHnnA" +
        "eEr/Hpvm318Yivqm9RRd+PeYB/SIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRA" +
        "jxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBH" +
        "DPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMG" +
        "esRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9" +
        "YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x" +
        "0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxjo" +
        "EQM9YqBHDPSIgR4x0CMGesRAjxjoEQM9YqBHDPSIgR4x0CMGesRAjxju6/B4xnrATDhOjxDoEQM9YujyuG49YCac6vBoPV8u" +
        "HKNHCB0eN63ny4Z2j7esx8uG5VaP163Hy4alVo/XrMfLhsVWjx9bj5cNq60eL1uPlw1PtHo8az1eNtzb6nHderxsaD1wtmU9" +
        "XT6c4MseCPNtHj+yni4jLrV4PG89XEY81eLxnPVwGXGSTzMQWo7k3rSeLSfml/nwCGGRr8IhvMlXjxBmHhK/bD1ZZsx4Bbm9" +
        "bj1YZrw93eOH1nPlxunpz9gD67myY5UvHiGcYo4Y/nJU40vWM+XI6ZXDGjc3rGfKkncPv+ZZsJ4oUx6c9PgV63my5VtjFrc+" +
        "Zz1Nxjy+/xj53DnrWbJm7smf7bb4zF+tB+ni/+zyUF4GiZUWAAAAJXRFWHRkYXRlOmNyZWF0ZQAyMDE2LTA1LTI0VDIzOjEz" +
        "OjQ0KzA4OjAw9aOaEwAAACV0RVh0ZGF0ZTptb2RpZnkAMjAxNi0wNS0yNFQyMzoxMzo0NCswODowMIT+Iq8AAAAfdEVYdHBz" +
        "OkhpUmVzQm91bmRpbmdCb3gANjUzeDcwMiswKzCDK/xJAAAAHHRFWHRwczpMZXZlbABBZG9iZS0zLjAgRVBTRi0zLjAKm3C7" +
        "4wAAAABJRU5ErkJggg==";

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
