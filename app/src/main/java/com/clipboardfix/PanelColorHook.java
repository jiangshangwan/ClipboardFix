package com.clipboardfix;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import java.lang.ref.WeakReference;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 让剪贴板面板（{@code InputMethodClipboardPhrasePopupView}）的背景色与底部输入法功能栏
 * （{@code InputMethodBottomManager.sBottomView}）保持一致。
 *
 * <p>默认情况下，面板内容区使用一张固定主题色的「顶部圆角实心」drawable，与底部功能栏的实际颜色
 * 在深色 / 浅色模式下都存在差异（深色面板偏暗、浅色面板偏亮）。本 Hook 在面板创建
 * （{@code initPopupWindow}）后，把面板「顶部圆角实心」背景重绘成底部功能栏当前的实色，
 * 并完整保留原来的圆角形状；同时监听底部栏换色（{@code setBottomColor}），面板处于显示状态时同步刷新。
 *
 * <p>定位方式：{@code com.miui.inputmethod.InputMethodClipboardPhrasePopupView} 与
 * {@code com.miui.inputmethod.InputMethodBottomManager} 类名在 MIUI/HyperOS 系统应用里保持稳定，
 * 直接按名反射（与项目内其它 Hook 一致）。颜色来源优先取底部栏背景的 {@link ColorDrawable} 实色，
 * 失败再退回静态字段 {@code currentBottomViewColor}，两者皆不可得则跳过（保持原样，绝不写非法色值）。
 */
public final class PanelColorHook {

    private static final String PHRASE_POPUP =
            "com.miui.inputmethod.InputMethodClipboardPhrasePopupView";
    private static final String BOTTOM_MANAGER =
            "com.miui.inputmethod.InputMethodBottomManager";

    /** 当前显示的面板「顶部圆角」内容容器，供换色时同步刷新（面板消失即被回收）。 */
    private static volatile WeakReference<View> sPanelBodyRef = new WeakReference<>(null);

    private PanelColorHook() {
    }

    public static void init(XposedModuleInterface.PackageLoadedParam param) {
        ClassLoader cl;
        try {
            cl = param.getDefaultClassLoader();
        } catch (Throwable t) {
            log("FAIL: no classloader - " + t);
            return;
        }

        // 1) 面板创建点：initPopupWindow(IIIII)V（PUBLIC 实例方法）
        try {
            Class<?> popupCls = Reflect.findClass(PHRASE_POPUP, cl);
            java.lang.reflect.Method initPopup = Reflect.findMethod(
                    popupCls, "initPopupWindow",
                    int.class, int.class, int.class, int.class, int.class);
            XposedInit.hook(initPopup, chain -> {
                Object result = chain.proceed();
                applyPanelColor(chain.getThisObject());
                return result;
            });
            log("OK: hooked " + PHRASE_POPUP + ".initPopupWindow");
        } catch (Throwable t) {
            log("WARN: initPopupWindow hook - " + t);
        }

        // 2) 底部栏换色点：setBottomColor(ZIII)V（PRIVATE STATIC）——
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
                        recolor(body, color);
                        log("panel color synced on setBottomColor -> #"
                                + Integer.toHexString(color));
                    }
                }
                return result;
            });
            log("OK: hooked " + BOTTOM_MANAGER + ".setBottomColor");
        } catch (Throwable t) {
            log("WARN: setBottomColor hook - " + t);
        }
    }

    /** 从面板实例取到内容视图，定位顶部圆角容器并重绘成底部栏颜色。 */
    private static void applyPanelColor(Object popupObj) {
        try {
            if (!(popupObj instanceof PopupWindow)) return;
            PopupWindow popup = (PopupWindow) popupObj;
            View content = popup.getContentView();
            if (content == null) return;

            int color = getBottomBarColor(content.getClass().getClassLoader());
            if (color == -1) {
                log("skip: bottom bar color unavailable");
                return;
            }

            View body = findTopRoundedBody(content);
            if (body == null) {
                log("WARN: panel top-rounded body not found");
                return;
            }
            recolor(body, color);
            sPanelBodyRef = new WeakReference<>(body);
            log("panel color applied -> #" + Integer.toHexString(color));
        } catch (Throwable t) {
            log("applyPanelColor error - " + t);
        }
    }

    /**
     * 在内容视图树里找「仅顶部圆角」的实心容器——它正是面板主体背景层。
     * 深度优先、命中即返回（面板主体通常是内容根的直接子级，最先被遍历到）。
     */
    private static View findTopRoundedBody(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (isTopRoundedBody(child)) {
                    return child;
                }
                View found = findTopRoundedBody(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isTopRoundedBody(View v) {
        Drawable bg = v.getBackground();
        if (!(bg instanceof GradientDrawable)) {
            return false;
        }
        GradientDrawable gd = (GradientDrawable) bg;
        float r = gd.getCornerRadius();
        if (r >= 0f) {
            // 统一圆角（非「仅顶部」），不是面板主体背景层。
            return false;
        }
        float[] cr = gd.getCornerRadii();
        if (cr == null || cr.length < 8) {
            return false;
        }
        boolean topRounded = cr[0] > 1f || cr[2] > 1f;
        boolean bottomFlat = cr[4] < 1f && cr[6] < 1f;
        return topRounded && bottomFlat;
    }

    /** 用底部栏实色重建背景，保留原圆角形状。 */
    private static void recolor(View body, int color) {
        Drawable bg = body.getBackground();
        GradientDrawable nd = new GradientDrawable();
        nd.setShape(GradientDrawable.RECTANGLE);
        nd.setColor(color);
        if (bg instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) bg;
            float r = gd.getCornerRadius();
            if (r >= 0f) {
                nd.setCornerRadius(r);
            } else {
                float[] cr = gd.getCornerRadii();
                if (cr != null) {
                    nd.setCornerRadii(cr);
                }
            }
        }
        body.setBackground(nd);
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
            log("getBottomBarColor from sBottomView failed - " + t);
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
