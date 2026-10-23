package com.clipboardfix

/**
 * 线程级通行证。
 *
 * <p>DexKit 精确校验（[PackageValidationHook]）需要拿到调用方的真实包名，
 * 但包名伪造（[ClipboardHook]）会把 getPackagesForUid 的返回值统一改写，
 * 导致 DexKit 那段的 contains(currentIme) 判断永远失真。
 *
 * <p>解法：DexKit 校验读取包名前后套上 begin()/end()，伪造逻辑见到标记就让行。
 * 标记是 ThreadLocal 的，只影响当前这一次调用，不会波及其他线程。
 */
object Bypass {

    private val PKG_SPOOF = ThreadLocal<Boolean>()

    /** 标记开始：后续的同线程包名查询返回真实值。 */
    @JvmStatic
    fun begin() {
        PKG_SPOOF.set(true)
    }

    /** 标记结束，必须放在 finally 里调用。 */
    @JvmStatic
    fun end() {
        PKG_SPOOF.remove()
    }

    /** 当前线程是否处于"取真实包名"状态。 */
    @JvmStatic
    fun isActive(): Boolean {
        return PKG_SPOOF.get() == true
    }
}
