package com.clipboardfix

import android.util.Log

import java.lang.reflect.Executable

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口（libxposed Modern API）。
 *
 * <p>替代旧版 {@code IXposedHookLoadPackage}。模块被框架注入后，
 * 由框架 new 一个本类实例并回调生命周期方法：
 * <ul>
 *   <li>{@link #onSystemServerStarting} → system_server 里放行输入法权限</li>
 *   <li>{@link #onPackageLoaded} → 按包名分发：剪贴板修复 / 全面屏优化解锁</li>
 * </ul>
 */
class XposedInit : XposedModule() {

    init {
        instance = this
    }

    /**
     * system_server 启动：放行第三方输入法的「获取应用列表」权限。
     *
     * <p><b>引导安全</b>：此处无条件安装、不读任何配置——开机早期禁止跨进程调用
     * （读远程偏好曾导致无法开机）。放行本身无副作用；功能开关在输入法进程内
     * 决定底栏是否出现（见 {@link ModulePrefs#hookPrefs}）。
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log("system server starting: v" + BuildConfig.VERSION_NAME)
        if (imeBottomSupported()) {
            ImePermissionHook.init(param.getClassLoader())
        } else {
            log("skip permission hook: " + PROP_MIUI_IME_BOTTOM + " != 1")
        }
    }

    /** 作用域内每个包加载时：按包名分发。 */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg: String? = param.getPackageName()
        if (pkg == null) return

        // 剪贴板面板背景跟随底部功能栏配色：面板实际运行在输入法进程（如微信输入法 :hld、
        // 系统 MIUI 输入法），与 com.miui.phrase 不是同一进程。故凡是能加载到该面板类的进程
        // 都要挂（用类存在性自检兜底），不再限定 com.miui.phrase。
        // 由「优化剪贴板面板背景」开关控制，默认关闭。
        if (ModulePrefs.isPanelColorEnabled()) {
            PanelColorHook.init(param)
        }

        // 剪贴板条目置顶（右滑置顶 / 清空保留）：同样装在输入法进程
        // （查询重排 + 右滑手势 + 清空写回全部发生在默认输入法侧）。
        // 由「开启剪贴板条目置顶」开关控制，默认关闭。
        if (ModulePrefs.isClipboardPinEnabled()) {
            ClipboardPinHook.init(param)
        }

        // 跨设备剪贴板云端图片「关闭面板再打开消失」的显示层修复：面板实际运行在输入法进程，
        // 必须装在输入法进程才生效，故放全包区域（进程无关，内部自有去重）。
        CrossDeviceClipboardHook.initPanelMerge(param)

        if (PKG_PHRASE == pkg) {
            log("phrase: v" + BuildConfig.VERSION_NAME)
            ClipboardHook.init()
            // DexKit 需要扫描 dex，耗时较长，放到最后执行，
            // 保证剪贴板相关的 hook 已经装好，不会拖慢 com.miui.phrase 启动。
            PackageValidationHook.init(param)
            // 解除常用语/剪贴板条数、时间与字数限制（两个功能开关独立控制，默认关/开）
            val countUnlock = ModulePrefs.isUnlimitCountEnabled()
            val timeUnlock = ModulePrefs.isUnlimitTimeEnabled()
            if (countUnlock || timeUnlock) {
                UnlimitHook.init(param, countUnlock, timeUnlock)
            }
            return
        }

        // 其余按第三方输入法处理（system / android 等无关包直接忽略）
        if (!imeBottomSupported()) {
            log(pkg + " skip: " + PROP_MIUI_IME_BOTTOM + " != 1")
            return
        }

        val cl: ClassLoader
        try {
            cl = param.getDefaultClassLoader() // API 29+
        } catch (t: Throwable) {
            log(pkg + " skip: no classloader - " + t)
            return
        }
        // 全面屏优化：由功能开关控制，默认开启（还原到加入开关之前的行为）；
        // 剪贴板时间限制解锁同样经 loadDex 回调在输入法进程内安装（面板跑在这里）
        val imeUnlock = ModulePrefs.isImeUnlockEnabled()
        val timeUnlock = ModulePrefs.isUnlimitTimeEnabled()
        if (imeUnlock || timeUnlock) {
            ImeUnlockHook.init(pkg, cl, imeUnlock, timeUnlock)
        }
    }

    companion object {
        const val TAG = "[ClipboardFix]"

        const val PKG_PHRASE = "com.miui.phrase"

        /** 系统是否支持 MIUI 输入法底部栏，不支持就没必要 hook 全面屏优化。 */
        private const val PROP_MIUI_IME_BOTTOM = "ro.miui.support_miui_ime_bottom"

        /** 框架 new 出来的唯一入口实例，供静态工具类转发日志和 hook。 */
        @Volatile
        private var instance: XposedInit? = null

        // ---------------- 供各 Hook 类使用的静态入口 ----------------

        /** 统一打日志。 */
        @JvmStatic
        fun log(msg: String) {
            val inst = instance
            if (inst != null) {
                inst.log(Log.INFO, TAG, msg)
            } else {
                Log.i(TAG, msg)
            }
        }

        /** 统一挂 hook：拦截器链模型，异常保护模式（hooker 异常不崩目标进程）。 */
        @JvmStatic
        fun hook(target: Executable, hooker: XposedInterface.Hooker) {
            module().hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker)
        }

        /** 统一挂 hook：异常透传模式。用于「故意抛异常改变行为」的场景。 */
        @JvmStatic
        fun hookPassthrough(target: Executable, hooker: XposedInterface.Hooker) {
            module().hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hooker)
        }

        /**
         * hook 类的静态初始化块（{@code <clinit>}）。
         *
         * <p>{@code chain.proceed()} 之前是静态初始化执行前，之后是执行完毕。
         * 用于在类被（重新）初始化后立刻重设静态字段——服务重建导致类重新加载时，
         * 之前一次性赋的值会丢失，靠这个回调补回来。
         */
        @JvmStatic
        fun hookClassInitializer(clazz: Class<*>, hooker: XposedInterface.Hooker) {
            module().hookClassInitializer(clazz)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker)
        }

        /**
         * 反优化指定方法，绕过 ART 的内联。
         *
         * <p>系统框架里被频繁调用的短方法（如 {@code isImeSupport()}）容易被 ART 内联到调用点，
         * 一旦内联，hook 就不会被调用。新版本 Android 的 ART 优化更激进，需要先反优化。
         */
        @JvmStatic
        fun deoptimizeMethod(target: Executable) {
            try {
                module().deoptimize(target)
            } catch (t: Throwable) {
                log("deoptimize failed on " + target + " - " + t)
            }
        }

        internal fun module(): XposedInit =
                instance ?: throw IllegalStateException("XposedInit not initialized yet")

        private fun imeBottomSupported(): Boolean = "1" == SysProps.get(PROP_MIUI_IME_BOTTOM, "0")
    }
}
