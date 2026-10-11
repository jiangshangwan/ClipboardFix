package com.clipboardfix;

import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import java.lang.ref.WeakReference;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 让剪贴板面板（{@code InputMethodClipboardPhrasePopupView}）的背景色与底部输入法功能栏
 * （{@code InputMethodBottomManager.sBottomView}）保持一致。
 *
 * <p><b>进程归属（关键）</b>：该面板并不运行在 {@code com.miui.phrase} 进程里——{@code phrase}
 * 只是数据/UI 配套 app，真正承载键盘、底部栏与剪贴板面板的是<b>输入法进程</b>（实测为微信输入法
 * {@code com.tencent.wetype} 的 {@code :hld} 子进程，也会借 {@code com.xiaomi.type} 等系统输入法）。
 * 因此本 Hook 必须挂在这些输入法进程上，而非 {@code com.miui.phrase}。调用方（{@code XposedInit}）
 * 会对每一个能加载到面板类的进程调用 {@link #init}，本类再用类存在性自检兜底。
 *
 * <p>面板内容容器（布局 {@code clipboard_pop_view} 里的 {@code @id/inside_view}）默认背景是固定主题色
 * 的实心 shape（{@code pad_floating_clipboard_bg} / {@code _gray} / {@code _gray_round}），深浅色下都
 * 与底部功能栏的实际配色存在差异。本 Hook 在面板构建（{@code initPopupWindow}）与显示
 * （{@code PopupWindow} 的 {@code setContentView}/{@code showAsDropDown}/{@code showAtLocation}）后，
 * 把这个容器的背景着色成底部功能栏当前实色，并监听底部栏换色（{@code setBottomColor}）做同步刷新。
 *
 * <p>定位方式：{@code com.miui.inputmethod.InputMethodClipboardPhrasePopupView} /
 * {@code InputMethodBottomManager} 类名在 MIUI/HyperOS 系统应用里保持稳定，按名反射（与项目内其它
 * Hook 一致）。面板容器优先按 id 名 {@code inside_view} 找（跨版本稳），退回内容根首个带背景的子视图、
 * 再退回视图树扫描。颜色优先取底部栏背景的 {@link ColorDrawable} 实色，退回静态字段
 * {@code currentBottomViewColor}，两者皆不可得则跳过（保持原样，绝不写非法色值）。
 */
public final class PanelColorHook {

    private static final String PHRASE_POPUP =
            "com.miui.inputmethod.InputMethodClipboardPhrasePopupView";
    private static final String BOTTOM_MANAGER =
            "com.miui.inputmethod.InputMethodBottomManager";
    private static final String ID_INSIDE_VIEW = "inside_view";
    private static final String POPUP_WINDOW = "android.widget.PopupWindow";

    /** 当前显示的面板内容容器，供换色时同步刷新（面板消失即被回收）。 */
    private static volatile WeakReference<View> sPanelBodyRef = new WeakReference<>(null);

    /** 防止同一进程内重复挂 hook（onPackageLoaded 可能因多 classloader 多次回调）。 */
    private static volatile boolean sInitDone = false;

    private PanelColorHook() {
    }

    public static void init(XposedModuleInterface.PackageLoadedParam param) {
        if (sInitDone) {
            return;
        }
        ClassLoader cl;
        try {
            cl = param.getDefaultClassLoader();
        } catch (Throwable t) {
            log("FAIL: no classloader - " + t);
            return;
        }
        // 该进程必须能加载到面板类，否则不是承载面板的输入法进程，跳过。
        try {
            if (Reflect.findClass(PHRASE_POPUP, cl) == null) {
                return;
            }
        } catch (Throwable t) {
            log("FAIL: panel class not found - " + t);
            return;
        }

        // 1) 面板构建点：initPopupWindow(IIIII)V（PUBLIC 实例方法）
        try {
            Class<?> popupCls = Reflect.findClass(PHRASE_POPUP, cl);
            java.lang.reflect.Method initPopup = Reflect.findMethod(
                    popupCls, "initPopupWindow",
                    int.class, int.class, int.class, int.class, int.class);
            XposedInit.hook(initPopup, chain -> {
                Object result = chain.proceed();
                scheduleTint(chain.getThisObject(), cl);
                return result;
            });
            log("OK: hooked " + PHRASE_POPUP + ".initPopupWindow");
        } catch (Throwable t) {
            log("WARN: initPopupWindow hook - " + t);
        }

        // 2) 框架 PopupWindow 内容/显示点：面板显示时 contentView 必然已就绪，最可靠。
        //    过滤只作用于面板类自身，不影响其它 PopupWindow。
        try {
            Class<?> pwCls = Reflect.findClass(POPUP_WINDOW, cl);
            hookPopupLifecycle(pwCls, cl);
            log("OK: hooked framework PopupWindow content/show");
        } catch (Throwable t) {
            log("WARN: PopupWindow hook - " + t);
        }

        // 3) 底部栏换色点：setBottomColor(ZIII)V（PRIVATE STATIC）——
        //    面板处于显示状态时，跟随底部栏新色同步刷新。
        try {
            Class<?> mgrCls = Reflect.findClass(BOTTOM_MANAGER, cl);
            java.lang.reflect.Method setBottomColor = Reflect.findMethod(
                    mgrCls, "setBottomColor",
                    boolean.class, int.class, int.class, int.class);
            XposedInit.hook(setBottomColor, chain -> {
                Object result = chain.proceed();
                View body = sPanelBodyRef.get();
                if (body != null) {
                    int color = getBottomBarColor(cl);
                    if (color != -1) {
                        tint(body, color);
                        log("synced on setBottomColor -> #" + Integer.toHexString(color));
                    }
                }
                return result;
            });
            log("OK: hooked " + BOTTOM_MANAGER + ".setBottomColor");
        } catch (Throwable t) {
            log("WARN: setBottomColor hook - " + t);
        }

        sInitDone = true;
    }

    /** 挂 framework PopupWindow 的 content/show 方法，仅对面板类生效。 */
    private static void hookPopupLifecycle(Class<?> pwCls, ClassLoader cl) {
        // setContentView(View)
        hookIfPanel(pwCls, "setContentView", new Class<?>[]{View.class}, cl);
        // showAsDropDown(View, int, int)
        hookIfPanel(pwCls, "showAsDropDown",
                new Class<?>[]{View.class, int.class, int.class}, cl);
        // showAsDropDown(View, int, int, int)
        hookIfPanel(pwCls, "showAsDropDown",
                new Class<?>[]{View.class, int.class, int.class, int.class}, cl);
        // showAtLocation(View, int, int, int)
        hookIfPanel(pwCls, "showAtLocation",
                new Class<?>[]{View.class, int.class, int.class, int.class}, cl);
    }

    private static void hookIfPanel(Class<?> pwCls, String name, Class<?>[] sig, ClassLoader cl) {
        try {
            java.lang.reflect.Method m = Reflect.findMethod(pwCls, name, sig);
            XposedInit.hook(m, chain -> {
                Object result = chain.proceed();
                if (isOurPanel(chain.getThisObject())) {
                    scheduleTint(chain.getThisObject(), cl);
                }
                return result;
            });
        } catch (Throwable t) {
            log("WARN: " + name + " hook - " + t);
        }
    }

    private static boolean isOurPanel(Object o) {
        return o != null && PHRASE_POPUP.equals(o.getClass().getName());
    }

    /**
     * 推迟到 contentView 就绪后再定位容器并着色：{@code initPopupWindow} 可能早于
     * {@code setContentView}，直接取 contentView 会为 null，故用重试保证拿到视图。
     */
    private static void scheduleTint(Object popupObj, ClassLoader cl) {
        if (!(popupObj instanceof PopupWindow)) {
            return;
        }
        final PopupWindow pw = (PopupWindow) popupObj;
        final Runnable attempt = new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                try {
                    View content = pw.getContentView();
                    if (content == null) {
                        if (tries++ < 8) {
                            new Handler(Looper.getMainLooper()).postDelayed(this, 200L);
                        } else {
                            log("WARN: contentView never ready");
                        }
                        return;
                    }
                    View body = findBody(content);
                    if (body == null) {
                        log("WARN: panel content container not found");
                        return;
                    }
                    sPanelBodyRef = new WeakReference<>(body);

                    final ClassLoader cl2 = cl;
                    int color = getBottomBarColor(cl2);
                    if (color == -1) {
                        // 底部栏尚未着色（如输入法刚起），推迟一拍再试。
                        log("defer: bottom color not ready");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            int c = getBottomBarColor(cl2);
                            if (c != -1) {
                                tint(body, c);
                                log("retry ok -> #" + Integer.toHexString(c));
                            } else {
                                log("retry still -1");
                            }
                        }, 300L);
                        return;
                    }
                    tint(body, color);
                    log("applied -> #" + Integer.toHexString(color));
                } catch (Throwable t) {
                    log("scheduleTint error - " + t);
                }
            }
        };
        new Handler(Looper.getMainLooper()).postDelayed(attempt, 50L);
    }

    /**
     * 定位面板内容容器：优先按 id 名 {@code inside_view}（跨版本稳），
     * 退回内容根第一个带背景的子视图，再退回视图树扫描。
     */
    private static View findBody(View content) {
        try {
            int id = content.getResources().getIdentifier(ID_INSIDE_VIEW, "id", null);
            if (id != 0) {
                View v = content.findViewById(id);
                if (v != null) {
                    log("body by id(" + ID_INSIDE_VIEW + ")=" + Integer.toHexString(id));
                    return v;
                }
            }
        } catch (Throwable ignored) {
            // 解析失败则走兜底
        }
        if (content instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) content;
            if (vg.getChildCount() > 0) {
                View c0 = vg.getChildAt(0);
                if (c0 != null && c0.getBackground() != null) {
                    log("body by content child[0]");
                    return c0;
                }
            }
        }
        View scanned = findFirstWithBg(content);
        if (scanned != null) {
            log("body by scan");
        }
        return scanned;
    }

    private static View findFirstWithBg(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child.getBackground() != null) {
                    return child;
                }
                View found = findFirstWithBg(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 给容器背景着色。用 {@link Drawable#setTint} 只改颜色、保留原 drawable
     * （圆角形状 / MIUI 自定义阴影都在原 drawable 上，重建就会丢）。
     */
    private static void tint(View body, int color) {
        Drawable bg = body.getBackground();
        if (bg == null) {
            body.setBackgroundColor(color);
            return;
        }
        bg.setTintMode(PorterDuff.Mode.SRC_IN);
        bg.setTint(color);
        bg.invalidateSelf();
        body.requestLayout();
        body.invalidate();
    }

    /** 取底部功能栏当前实色；优先读其 ColorDrawable 背景，退回静态字段，皆失败返回 -1。 */
    private static int getBottomBarColor(ClassLoader cl) {
        try {
            Class<?> mgr = Reflect.findClass(BOTTOM_MANAGER, cl);
            Object bottomView = Reflect.getStaticField(mgr, "sBottomView");
            if (bottomView instanceof View) {
                Drawable bg = ((View) bottomView).getBackground();
                if (bg instanceof ColorDrawable) {
                    return ((ColorDrawable) bg).getColor();
                }
            }
        } catch (Throwable t) {
            log("color from sBottomView failed - " + t);
        }
        try {
            Class<?> mgr = Reflect.findClass(BOTTOM_MANAGER, cl);
            Object c = Reflect.getStaticField(mgr, "currentBottomViewColor");
            if (c instanceof Integer) {
                int v = (Integer) c;
                if (v != 0) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
            // 字段不存在或读不到，返回 -1
        }
        return -1;
    }

    private static void log(String msg) {
        XposedInit.log("[PanelColor] " + msg);
    }
}
