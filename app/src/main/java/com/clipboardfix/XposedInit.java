package com.clipboardfix;

import android.util.Log;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

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
public class XposedInit extends XposedModule {

    public static final String TAG = "[ClipboardFix]";

    static final String PKG_PHRASE = "com.miui.phrase";

    /** 系统是否支持 MIUI 输入法底部栏，不支持就没必要 hook 全面屏优化。 */
    private static final String PROP_MIUI_IME_BOTTOM = "ro.miui.support_miui_ime_bottom";

    /** 框架 new 出来的唯一入口实例，供静态工具类转发日志和 hook。 */
    private static volatile XposedInit instance;

    public XposedInit() {
        super();
        instance = this;
    }

    // ---------------- 供各 Hook 类使用的静态入口 ----------------

    /** 统一打日志。 */
    public static void log(String msg) {
        XposedInit inst = instance;
        if (inst != null) {
            inst.log(Log.INFO, TAG, msg);
        } else {
            Log.i(TAG, msg);
        }
    }

    /** 统一挂 hook：拦截器链模型，异常保护模式（hooker 异常不崩目标进程）。 */
    public static void hook(Executable target, XposedInterface.Hooker hooker) {
        module().hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /** 统一挂 hook：异常透传模式。用于「故意抛异常改变行为」的场景。 */
    public static void hookPassthrough(Executable target, XposedInterface.Hooker hooker) {
        module().hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(hooker);
    }

    /**
     * hook 类的静态初始化块（{@code <clinit>}）。
     *
     * <p>{@code chain.proceed()} 之前是静态初始化执行前，之后是执行完毕。
     * 用于在类被（重新）初始化后立刻重设静态字段——服务重建导致类重新加载时，
     * 之前一次性赋的值会丢失，靠这个回调补回来。
     */
    public static void hookClassInitializer(Class<?> clazz, XposedInterface.Hooker hooker) {
        module().hookClassInitializer(clazz)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /**
     * 反优化指定方法，绕过 ART 的内联。
     *
     * <p>系统框架里被频繁调用的短方法（如 {@code isImeSupport()}）容易被 ART 内联到调用点，
     * 一旦内联，hook 就不会被调用。新版本 Android 的 ART 优化更激进，需要先反优化。
     */
    public static void deoptimizeMethod(Executable target) {
        try {
            module().deoptimize(target);
        } catch (Throwable t) {
            log("deoptimize failed on " + target + " - " + t);
        }
    }

    private static XposedInit module() {
        XposedInit inst = instance;
        if (inst == null) {
            throw new IllegalStateException("XposedInit not initialized yet");
        }
        return inst;
    }

    // ---------------- 生命周期回调 ----------------

    /** system_server 启动：放行第三方输入法的「获取应用列表」权限。 */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        log("system server starting: v" + BuildConfig.VERSION_NAME);
        if (imeBottomSupported()) {
            ImePermissionHook.init(param.getClassLoader());
        } else {
            log("skip permission hook: " + PROP_MIUI_IME_BOTTOM + " != 1");
        }
    }

    /** 作用域内每个包加载时：按包名分发。 */
    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (pkg == null) return;

        if (PKG_PHRASE.equals(pkg)) {
            log("phrase: v" + BuildConfig.VERSION_NAME);
            ClipboardHook.init();
            // DexKit 需要扫描 dex，耗时较长，放到最后执行，
            // 保证剪贴板相关的 hook 已经装好，不会拖慢 com.miui.phrase 启动。
            PackageValidationHook.init(param);
            return;
        }

        // 其余按第三方输入法处理（system / android 等无关包直接忽略）
        if (!imeBottomSupported()) {
            log(pkg + " skip: " + PROP_MIUI_IME_BOTTOM + " != 1");
            return;
        }

        ClassLoader cl;
        try {
            cl = param.getDefaultClassLoader(); // API 29+
        } catch (Throwable t) {
            log(pkg + " skip: no classloader - " + t);
            return;
        }
        ImeUnlockHook.init(pkg, cl);
    }

    private static boolean imeBottomSupported() {
        return "1".equals(SysProps.get(PROP_MIUI_IME_BOTTOM, "0"));
    }
}
