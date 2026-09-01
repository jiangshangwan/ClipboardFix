package com.clipboardfix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口，只负责按包名分发。
 *
 * <p>三条支路：
 * <ul>
 *   <li>android（system_server）→ 输入法列表权限放行
 *   <li>com.miui.phrase → 剪贴板修复 + DexKit 精确校验
 *   <li>第三方输入法进程 → 全面屏优化解锁
 * </ul>
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "[ClipboardFix]";

    static final String PKG_ANDROID = "android";
    static final String PKG_PHRASE = "com.miui.phrase";

    /** 系统是否支持 MIUI 输入法底部栏，不支持就没必要 hook 全面屏优化。 */
    private static final String PROP_MIUI_IME_BOTTOM = "ro.miui.support_miui_ime_bottom";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;

        if (PKG_ANDROID.equals(pkg)) {
            XposedBridge.log(TAG + " android: v" + BuildConfig.VERSION_NAME);
            if (imeBottomSupported()) {
                ImePermissionHook.init(lpparam);
            } else {
                XposedBridge.log(TAG + " skip permission hook: " + PROP_MIUI_IME_BOTTOM + " != 1");
            }
            return;
        }

        if (PKG_PHRASE.equals(pkg)) {
            XposedBridge.log(TAG + " phrase: v" + BuildConfig.VERSION_NAME);
            ClipboardHook.init();
            // DexKit 需要扫描 dex，耗时较长，放到最后执行，
            // 保证剪贴板相关的 hook 已经装好，不会拖慢 com.miui.phrase 启动。
            PackageValidationHook.init(lpparam);
            return;
        }

        // 作用域内的其他进程，按第三方输入法处理
        if (!imeBottomSupported()) {
            XposedBridge.log(TAG + " " + pkg + " skip: " + PROP_MIUI_IME_BOTTOM + " != 1");
            return;
        }
        ImeUnlockHook.init(lpparam);
    }

    private static boolean imeBottomSupported() {
        return "1".equals(SysProps.get(PROP_MIUI_IME_BOTTOM, "0"));
    }
}
