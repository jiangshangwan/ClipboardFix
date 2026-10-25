package com.clipboardfix

/**
 * 线程级通行证。
 *
 * <p>DexKit 精确校验（[PackageValidationHook]）需要拿到调用方的真实包名，
 * 但包名伪造（[ClipboardHook]）会把 getPackagesForUid 的返回值统一改写，
 * 导致 DexKit 那段的 contains(currentIme) 判断永远失真。
 *
 * <p>解法：DexKit 校验读取包名前后套上 begin()/end()，伪造逻辑见到标记就让行。
 * 标记是 ThreadLocal 的，只影响当前线程，不会波及其他线程。
 *
 * <p>计数式可重入：外层窗口（如 [ClipboardHook] 的 call() 真实包名窗口）跨度
 * 较大，内层若再套一层（DexKit 校验在 call() 内被间接触发），内层 end() 不会
 * 提前清掉外层的标记；单层使用时语义与布尔版完全一致。
 */
object Bypass {

    private val PKG_SPOOF = ThreadLocal<Int>()

    /** 标记开始：后续的同线程包名查询返回真实值。可重入，嵌套调用请配对使用。 */
    @JvmStatic
    fun begin() {
        PKG_SPOOF.set((PKG_SPOOF.get() ?: 0) + 1)
    }

    /** 标记结束，必须放在 finally 里调用；与 [begin] 配对，嵌套时先内后外。 */
    @JvmStatic
    fun end() {
        val left = (PKG_SPOOF.get() ?: 1) - 1
        if (left > 0) {
            PKG_SPOOF.set(left)
        } else {
            PKG_SPOOF.remove()
        }
    }

    /** 当前线程是否处于"取真实包名"状态。 */
    @JvmStatic
    fun isActive(): Boolean {
        return (PKG_SPOOF.get() ?: 0) > 0
    }
}
