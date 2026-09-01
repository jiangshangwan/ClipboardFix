package com.clipboardfix;

/**
 * 线程级通行证。
 *
 * <p>DexKit 精确校验（{@link PackageValidationHook}）需要拿到调用方的<b>真实</b>包名，
 * 但包名伪造（{@link ClipboardHook}）会把 getPackagesForUid 的返回值统一改写，
 * 导致 DexKit 那段的 contains(currentIme) 判断永远失真。
 *
 * <p>解法：DexKit 校验读取包名前后套上 begin()/end()，伪造逻辑见到标记就让行。
 * 标记是 ThreadLocal 的，只影响当前这一次调用，不会波及其他线程。
 */
public final class Bypass {

    private static final ThreadLocal<Boolean> PKG_SPOOF = new ThreadLocal<>();

    private Bypass() {
    }

    /** 标记开始：后续的同线程包名查询返回真实值。 */
    public static void begin() {
        PKG_SPOOF.set(Boolean.TRUE);
    }

    /** 标记结束，必须放在 finally 里调用。 */
    public static void end() {
        PKG_SPOOF.remove();
    }

    /** 当前线程是否处于"取真实包名"状态。 */
    public static boolean isActive() {
        return Boolean.TRUE.equals(PKG_SPOOF.get());
    }
}
