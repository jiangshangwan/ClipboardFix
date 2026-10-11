package com.clipboardfix;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
 * <p><b>进程归属</b>：面板运行在输入法进程（实测微信输入法 {@code com.tencent.wetype:hld}，
 * 也会借 {@code com.xiaomi.type} 等系统输入法），不在 {@code com.miui.phrase} 进程。
 *
 * <p><b>关键坑（实测，code 25/26/27 全部因此静默失效）</b>：
 * {@code com.miui.inputmethod.*} 这套类（面板 {@code InputMethodClipboardPhrasePopupView}
 * 与底部栏 {@code InputMethodBottomManager}）并<b>不在进程启动时的 classpath 里</b>——
 * 输入法是通过 {@code InputMethodModuleManager.loadDex(ClassLoader, String)} 动态加载的
 * （见 {@link ImeUnlockHook#hookModuleManagerLoadDex}，它还要 {@code addDexPath} 之后才能解析）。
 * 因此在 {@code onPackageLoaded}（进程刚起）用 {@code param.getDefaultClassLoader()}
 * 按名加载这两个类，必定抛 {@code ClassNotFoundException}。
 *
 * <p><b>本 Hook 的做法</b>：
 * <ol>
 *   <li>面板类<b>继承自 {@code android.widget.PopupWindow}</b>（boot classloader，任何进程都能按名拿到）。
 *       所以进程一起就 hook 框架 {@code PopupWindow} 的 {@code setContentView}/
 *       {@code showAsDropDown}/{@code showAtLocation}，回调里用
 *       {@code getThisObject().getClass().getName()} 与面板类名比对来识别「这是不是我们的面板」。
 *       实例对象自带真实 Class，完全绕过动态 dex 的 classloader 问题。</li>
 *   <li>识别到面板后，从实例取 {@code getClass().getClassLoader()} 存起来——这个 classloader
 *       加载了面板类，同属 {@code com.miui.inputmethod} 的 {@code InputMethodBottomManager}
 *       也能用它解析。据此再<b>懒加载</b>式地 hook 底部栏换色点并取色。</li>
 * </ol>
 *
 * <p>着色用 {@link Drawable#setTint}，只改颜色、保留原 drawable（圆角形状 / MIUI 自定义阴影都在
 * 原 drawable 上，重建就会丢）。颜色优先读底部栏 {@code sBottomView} 的 {@link ColorDrawable}
 * 实色，退回静态字段 {@code currentBottomViewColor}，皆不可得则跳过（保持原样，绝不写非法色值）。
 */
public final class PanelColorHook {

    private static final String PHRASE_POPUP =
            "com.miui.inputmethod.InputMethodClipboardPhrasePopupView";
    private static final String BOTTOM_MANAGER =
            "com.miui.inputmethod.InputMethodBottomManager";
    private static final String ID_INSIDE_VIEW = "inside_view";
    private static final String POPUP_WINDOW = "android.widget.PopupWindow";

    /** 按名解析到的资源 id 缓存（面板布局来自 {@code com.miui.phrase} 的资源包）。 */
    private static final java.util.Map<String, Integer> sIdCache = new java.util.HashMap<>();

    /**
     * 面板高斯模糊半径。与主流微信输入法美化模块的默认模糊强度一致（{@code DEFAULT_BLUR_RADIUS=60}），
     * 这样两者的玻璃观感最接近。
     */
    private static final int BLUR_RADIUS = 60;

    /**
     * 深色模式下，模糊之上叠加底色的不透明度（0x28 ≈ 16%）。仅在拿不到系统下发的合成色时兜底。
     *
     * <p>浅色模式不使用这个兜底值——功能区自身没有任何颜色叠加，详见
     * {@link #barLikeTint(boolean)}。
     */
    private static final int BLUR_TINT_ALPHA = 0x28;

    /** 功能区透明态的内容色（深色模式近白，与系统下发给功能区的取值一致）。 */
    private static final int CONTENT_COLOR_DARK = 0xFFF5F5F5;

    /** 当前显示的面板内容容器，供换色时同步刷新（面板消失即被回收）。 */
    private static volatile WeakReference<View> sPanelBodyRef = new WeakReference<>(null);

    /** 宿主（输入法）包名，用于限定跨窗口模糊的放行范围。 */
    private static volatile String sHostPkg = null;

    /** 进程默认 classloader（进程启动时 MIUI 输入法类还没加载，只能作为兜底）。 */
    private static volatile ClassLoader sDefaultCl = null;

    /** 面板实例所在的 classloader（首次识别到面板时取得，能解析 com.miui.inputmethod.*）。 */
    private static volatile ClassLoader sPanelCl = null;

    /**
     * 面板自身的原始底色（在上色之前抓取一次）。第三方美化模块会把底部功能区颜色强改成
     * 全透明，连累面板也变透明；此时用这个原始不透明色兜底，保证面板始终可读。
     *
     * <p><b>必须按明暗主题分开缓存</b>：面板自带底色浅色为 {@code #F4F6F9}、深色为
     * {@code #101010}，只存一个的话切到浅色模式仍会拿深色值，面板会被涂成深灰（实测踩过）。
     */
    private static volatile int sFallbackLight = -1;
    private static volatile int sFallbackDark = -1;

    /** 最近一次系统下发给底部功能区的参数（{@code xxx(ZIII)}），面板照抄同款底色。 */
    private static volatile int sBarBgColor = -1;
    private static volatile int sBarContentColor = -1;
    private static volatile int sBarAlphaColor = -1;

    /** 功能区背景结构只转储一次，供排查「面板与功能区有色差」用。 */
    private static volatile boolean sBarDumped = false;

    /** 面板自身圆角（第一次上色前抓取，用于给模糊层切同样的圆角）。 */
    private static volatile float sPanelRadius = -1f;

    /** 防止同一进程内重复挂 hook（onPackageLoaded 可能因多 classloader 多次回调）。 */
    private static volatile boolean sInitDone = false;

    /** 底部栏换色点是否已懒挂（要等面板出现、拿到正确 classloader 后才能挂）。 */
    private static volatile boolean sBottomHooked = false;

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
            log("init: no classloader - " + t);
            return;
        }
        // 先置位，避免同进程内 onPackageLoaded 多次回调导致重复挂 hook。
        sInitDone = true;
        sDefaultCl = cl;
        sHostPkg = param.getPackageName();
        log("init enter: pkg=" + sHostPkg);
        ensurePassWindowBlur();
        // 动态 dex 要等 IME 起来才加载，此处有界重试，尽量在键盘首次配置颜色前挂上取色点。
        scheduleBottomHookRetry();

        // 框架 PopupWindow 内容/显示点：按实例类名识别面板，无需按名加载面板类
        // （面板类的 dex 此刻尚未加载，Class.forName 必失败——这是前几版失效的根因）。
        try {
            Class<?> pwCls = Reflect.findClass(POPUP_WINDOW, cl);
            hookPopupLifecycle(pwCls);
            log("OK: hooked framework PopupWindow content/show");
        } catch (Throwable t) {
            log("WARN: PopupWindow hook - " + t);
        }
        // 底部栏换色点留到识别到面板后懒挂（见 ensureBottomColorHook）——
        // 此刻 InputMethodBottomManager 同样还没被 loadDex 加载进来。
    }

    /**
     * 放行 HyperOS 的「跨窗口背景模糊」准入。
     *
     * <p>HyperOS 在 {@code android.view.ViewRootImplStub.isPassWindowBlurAllowed(String)} 里
     * 校验包是否有权采样窗口背后的内容；未通过时 {@code createBackgroundBlurDrawable()} 虽能创建
     * drawable，却不会真的产生模糊（只剩一层底色，观感等于半透明）。这里只对本输入法包名放行，
     * 与主流美化模块的做法一致；类不存在（非 HyperOS）则静默跳过。
     */
    private static void ensurePassWindowBlur() {
        try {
            Class<?> stubCls = Class.forName(
                    "android.view.ViewRootImplStub", true, ClassLoader.getSystemClassLoader());
            java.lang.reflect.Method getInstance = stubCls.getMethod("getInstance");
            Object stub = getInstance.invoke(null);
            java.lang.reflect.Method isAllowed = stub.getClass().getMethod(
                    "isPassWindowBlurAllowed", String.class);
            XposedInit.hook(isAllowed, chain -> {
                Object arg = chain.getArg(0);
                if (sHostPkg != null && sHostPkg.equals(arg)) {
                    return Boolean.TRUE; // 与美化模块一致：只放行宿主自身
                }
                return chain.proceed(); // 其余包名保持系统原本判定
            });
            log("OK: pass-window blur allowed for " + sHostPkg);
        } catch (Throwable t) {
            log("pass-window blur hook skipped - " + t);
        }
    }

    /** 挂 framework PopupWindow 的 content/show 方法，仅对面板类生效。 */
    private static void hookPopupLifecycle(Class<?> pwCls) {
        // setContentView(View)
        hookIfPanel(pwCls, "setContentView", new Class<?>[]{View.class});
        // showAsDropDown(View, int, int)
        hookIfPanel(pwCls, "showAsDropDown",
                new Class<?>[]{View.class, int.class, int.class});
        // showAsDropDown(View, int, int, int)
        hookIfPanel(pwCls, "showAsDropDown",
                new Class<?>[]{View.class, int.class, int.class, int.class});
        // showAtLocation(View, int, int, int)
        hookIfPanel(pwCls, "showAtLocation",
                new Class<?>[]{View.class, int.class, int.class, int.class});
    }

    private static void hookIfPanel(Class<?> pwCls, String name, Class<?>[] sig) {
        try {
            java.lang.reflect.Method m = Reflect.findMethod(pwCls, name, sig);
            XposedInit.hook(m, chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (isOurPanel(self)) {
                    // 从实例取 classloader：它能解析 com.miui.inputmethod.*（含底部栏类）。
                    if (sPanelCl == null) {
                        sPanelCl = self.getClass().getClassLoader();
                        log("panel classloader captured: " + sPanelCl);
                    }
                    ensureBottomColorHook();
                    log("panel detected via " + name + ", scheduling tint");
                    scheduleTint(self);
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
     * 懒挂底部栏换色点 {@code setBottomColor(ZIII)V}：必须等面板出现、拿到能解析
     * {@code InputMethodBottomManager} 的 classloader 之后才能挂，否则必然 ClassNotFound。
     */
    private static void ensureBottomColorHook() {
        if (sBottomHooked) {
            return;
        }
        boolean any = false;
        // 权威色源：系统给功能区下发颜色时调用的方法，能同时拿到
        // 「底栏底色 / 内容色 / 半透明合成色」三个参数，面板照抄即可完全同款。
        Class<?> mgrCls = resolveBottomManager();
        if (mgrCls != null) {
            any |= hookBottomColorSetter(mgrCls, BOTTOM_MANAGER, "setBottomColor");
            any |= hookBottomColorSetter(mgrCls, BOTTOM_MANAGER, "customizeBottomViewColor");
        }
        Class<?> injector = resolveClass("android.inputmethodservice.InputMethodServiceInjector");
        if (injector != null) {
            any |= hookBottomColorSetter(injector, "InputMethodServiceInjector", "customizeBottomViewColor");
        }
        // 全部失败说明动态 dex 还没加载进来，不置位，留给重试再试。
        sBottomHooked = any;
        if (!any) {
            log("WARN: bottom color setters not resolvable yet");
        }
    }

    /**
     * 有界重试挂底栏取色点。
     *
     * <p>{@code com.miui.inputmethod.*} 是 {@code InputMethodModuleManager.loadDex} 动态加载的，
     * 进程刚起时解析不到。这里每秒重试一次、最多 30 次，抢在键盘首次配置颜色之前把取色点挂上；
     * 挂上即停，避免在输入法进程里长期轮询。
     */
    private static void scheduleBottomHookRetry() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                ensureBottomColorHook();
                if (!sBottomHooked && ++tries < 30) {
                    handler.postDelayed(this, 1000L);
                } else if (sBottomHooked) {
                    log("bottom color hook ready after " + tries + " retry(ies)");
                }
            }
        }, 1000L);
    }

    /** 挂 {@code xxx(ZIII)V} 形式的底栏换色点；记录参数并按需同步面板。 */
    private static boolean hookBottomColorSetter(Class<?> owner, String ownerName, String method) {
        java.lang.reflect.Method m;
        try {
            m = Reflect.findMethod(owner, method, boolean.class, int.class, int.class, int.class);
            if (m == null) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        try {
            XposedInit.hook(m, chain -> {
                int bg = toInt(chain.getArg(1));
                int content = toInt(chain.getArg(2));
                int alphaColor = toInt(chain.getArg(3));
                // 参数可能每次键盘配置都会回调，只在变化时打印，避免刷屏。
                if (bg != sBarBgColor || content != sBarContentColor
                        || alphaColor != sBarAlphaColor) {
                    log(ownerName + "." + method + " args: bg=" + hex(bg)
                            + " content=" + hex(content)
                            + " alpha=" + hex(alphaColor));
                }
                sBarBgColor = bg;
                sBarContentColor = content;
                sBarAlphaColor = alphaColor;
                Object result = chain.proceed();
                View body = sPanelBodyRef.get();
                if (body != null) {
                    applyPanelBackground(body, barColor(bg));
                }
                return result;
            });
            log("OK: hooked " + ownerName + "." + method + "(ZIII)");
            return true;
        } catch (Throwable t) {
            log("WARN: " + ownerName + "." + method + " hook - " + t);
            return false;
        }
    }

    /** 按面板 classloader 优先、默认 classloader 兜底解析类。 */
    private static Class<?> resolveClass(String name) {
        ClassLoader[] loaders = {sPanelCl, sDefaultCl};
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Reflect.findClass(name, cl);
            } catch (Throwable ignored) {
                // 换下一个 classloader 再试
            }
        }
        return null;
    }

    /** 底栏底色以系统下发的参数为准；参数未到时退回实时读取。 */
    private static int barColor(int fromArgs) {
        return fromArgs != -1 ? fromArgs : getBottomBarColor();
    }

    /** 用面板 classloader 优先、默认 classloader 兜底，解析 {@code InputMethodBottomManager}。 */
    private static Class<?> resolveBottomManager() {
        ClassLoader[] loaders = {sPanelCl, sDefaultCl};
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Reflect.findClass(BOTTOM_MANAGER, cl);
            } catch (Throwable ignored) {
                // 换下一个 classloader 再试
            }
        }
        return null;
    }

    /**
     * 推迟到 contentView 就绪后再定位容器并着色：{@code setContentView}/{@code showAsDropDown}
     * 可能早于视图完全就绪，直接取 contentView 会为 null，故用重试保证拿到视图。
     */
    private static void scheduleTint(Object popupObj) {
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
                    // 视图还没挂上窗口时拿不到 ViewRootImpl，模糊会静默失败并误退成实色。
                    // 等它 attach 完成再上色，避免面板一闪而过地被涂成兜底色。
                    if (!body.isAttachedToWindow() && tries++ < 10) {
                        new Handler(Looper.getMainLooper()).postDelayed(this, 200L);
                        return;
                    }
                    sPanelBodyRef = new WeakReference<>(body);
                    captureFallback(body);

                    int color = barColor(sBarBgColor);
                    if (color == -1) {
                        // 底部栏尚未着色（如输入法刚起），推迟一拍再试。
                        log("defer: bottom color not ready");
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> applyPanelBackground(body, barColor(sBarBgColor)), 300L);
                        return;
                    }
                    applyPanelBackground(body, color);
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
            int id = phraseId(content.getContext(), ID_INSIDE_VIEW);
            if (id == 0) {
                log("body: id(" + ID_INSIDE_VIEW + ") unresolved, falling back");
            } else {
                View v = content.findViewById(id);
                if (v != null) {
                    log("body by id(" + ID_INSIDE_VIEW + ")=" + hex(id));
                    return v;
                }
                log("body: id(" + ID_INSIDE_VIEW + ")=" + hex(id) + " found but view missing");
            }
        } catch (Throwable t) {
            log("body: id lookup failed - " + t);
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
     * 面板上色总入口。分两种情况：
     * <ul>
     *   <li><b>正常</b>（底部栏有实色）→ 面板跟随底栏实色，与系统观感一致；</li>
     *   <li><b>底部栏被第三方美化模块强改成透明</b>（面板会随之变透明、不可读）→ 改用
     *       「系统高斯模糊 + 半透明底色」，与该模块的玻璃键盘观感一致；模糊不可用时
     *       退回不透明实色，绝不留下透明面板。</li>
     * </ul>
     */
    private static void applyPanelBackground(View body, int bottomColor) {
        boolean night = isNight(body);
        if (bottomColor == -1) {
            // 取色尚未就绪（输入法刚起），不是「被强改透明」：保持面板原样，等下次同步。
            log("skip: bottom color unknown");
            return;
        }
        if (Color.alpha(bottomColor) == 0) {
            // 底部栏被第三方美化模块强改成全透明 → 改用「系统高斯模糊 + 半透明底色」，
            // 与该模块的玻璃键盘观感一致；模糊不可用时再退回不透明实色。
            if (applyBlurPanel(body, night)) {
                return;
            }
        }
        int base = ensureOpaque(bottomColor, night);
        tint(body, base);
        log("applied solid -> " + hex(base));
    }

    /**
     * 面板模糊层之上要叠的底色，目标是与底部功能区「同款」：
     * <ol>
     *   <li><b>浅色</b>：功能区自身背景就是全透明（{@code ColorDrawable #00000000}），
     *       即它的观感 = <b>纯模糊、不加任何颜色叠加</b>。所以面板也必须是纯模糊，
     *       否则两者一定有色差。这里直接返回全透明，让调用方只挂模糊层。</li>
     *   <li><b>深色</b>：系统下发的合成色（{@code setBottomColor} 第 4 参）能拿到就用它，
     *       拿不到再退回内容色 / 主题色。</li>
     * </ol>
     *
     * <p><b>历史坑（浅色「脏灰面板」的根因）</b>：早期照抄系统下发的 {@code #66202020}
     * 折算成 {@link #BLUR_TINT_ALPHA}（{@code 0x28 ≈ 16%}）压了一层暗色底，
     * 实测把面板压暗了约 19%（背板 243 → 196），而功能区同期只压暗约 7%（243 → 225）。
     * 结果面板变成一块脏灰底，上面浮着条目那张近白的卡片（条目是 MIUI 原生
     * {@code #99ffffff} = 白色 60%），视觉上非常突兀。去掉这层叠加后面板 ≈ 纯模糊
     * ≈ 功能区，条目与面板的落差也回到 MIUI 设计原本的幅度。
     */
    private static int barLikeTint(boolean night) {
        if (!night) {
            return Color.TRANSPARENT;
        }
        int alphaColor = sBarAlphaColor;
        int a = Color.alpha(alphaColor);
        if (alphaColor != -1 && a > 0 && a < 255) {
            return withAlpha(alphaColor, a * a / 255);
        }
        int content = sBarContentColor;
        if (content != -1 && Color.alpha(content) == 255) {
            return withAlpha(content, BLUR_TINT_ALPHA);
        }
        return withAlpha(CONTENT_COLOR_DARK, BLUR_TINT_ALPHA);
    }

    /**
     * 给面板套上系统级背景高斯模糊：底层是 {@code ViewRootImpl.createBackgroundBlurDrawable()}
     * 生成的真实背景模糊，必要时（深色）再叠一层半透明底色保证文字可读；圆角沿用面板自身的圆角。
     *
     * @return true 表示模糊已应用；false 表示不支持，调用方应退回实色。
     */
    private static boolean applyBlurPanel(View body, boolean night) {
        float radius = cornerRadiusOf(body);
        Drawable blur = createBlurDrawable(body, radius);
        if (blur == null) {
            return false;
        }
        dumpBarOnce();
        int tint = barLikeTint(night);
        Drawable bg;
        if (Color.alpha(tint) == 0) {
            // 浅色：功能区没有任何颜色叠加，面板也必须「裸模糊」才能同色。
            bg = blur;
        } else {
            GradientDrawable tintLayer = new GradientDrawable();
            tintLayer.setShape(GradientDrawable.RECTANGLE);
            tintLayer.setCornerRadius(radius);
            tintLayer.setColor(tint);
            bg = new LayerDrawable(new Drawable[]{blur, tintLayer});
        }
        body.setBackground(bg);
        body.invalidate();
        log("blur applied: radius=" + BLUR_RADIUS + " corner=" + radius
                + " tint=" + hex(tint) + (night ? " (dark)" : " (light)"));
        return true;
    }

    /** 一次性转储功能区背景结构，便于排查「面板与功能区有色差」。 */
    private static void dumpBarOnce() {
        if (sBarDumped) {
            return;
        }
        sBarDumped = true;
        try {
            Class<?> mgr = resolveBottomManager();
            Object bottomView = mgr == null ? null : Reflect.getStaticField(mgr, "sBottomView");
            if (!(bottomView instanceof View)) {
                log("bar dump: sBottomView unavailable");
                return;
            }
            Drawable bg = ((View) bottomView).getBackground();
            if (bg == null) {
                log("bar dump: no background");
                return;
            }
            StringBuilder sb = new StringBuilder("bar dump: ")
                    .append(bg.getClass().getName());
            if (bg instanceof LayerDrawable) {
                LayerDrawable ld = (LayerDrawable) bg;
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    Drawable layer = ld.getDrawable(i);
                    sb.append(" | [").append(i).append(']')
                            .append(layer == null ? "null" : layer.getClass().getSimpleName())
                            .append('=').append(hex(solidColorOf(layer)));
                }
            } else {
                sb.append(" color=").append(hex(solidColorOf(bg)));
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("bar dump failed - " + t);
        }
    }

    /** 通过隐藏 API {@code ViewRootImpl.createBackgroundBlurDrawable()} 生成背景模糊 drawable。 */
    private static Drawable createBlurDrawable(View body, float radius) {
        try {
            java.lang.reflect.Method getVri =
                    findMethodInHierarchy(View.class, "getViewRootImpl");
            if (getVri == null) {
                return null;
            }
            getVri.setAccessible(true);
            Object vri = getVri.invoke(body);
            if (vri == null) {
                log("blur: no ViewRootImpl");
                return null;
            }
            java.lang.reflect.Method create =
                    findMethodInHierarchy(vri.getClass(), "createBackgroundBlurDrawable");
            if (create == null) {
                log("blur: createBackgroundBlurDrawable missing");
                return null;
            }
            create.setAccessible(true);
            Drawable d = (Drawable) create.invoke(vri);
            if (d == null) {
                return null;
            }
            java.lang.reflect.Method setBlur =
                    findMethodInHierarchy(d.getClass(), "setBlurRadius", int.class);
            if (setBlur != null) {
                setBlur.setAccessible(true);
                setBlur.invoke(d, BLUR_RADIUS);
            }
            java.lang.reflect.Method setColor =
                    findMethodInHierarchy(d.getClass(), "setColor", int.class);
            if (setColor != null) {
                setColor.setAccessible(true);
                setColor.invoke(d, Color.TRANSPARENT);
            }
            java.lang.reflect.Method setCorners = findMethodInHierarchy(
                    d.getClass(), "setCornerRadius",
                    float.class, float.class, float.class, float.class);
            if (setCorners != null) {
                setCorners.setAccessible(true);
                setCorners.invoke(d, radius, radius, radius, radius);
            } else {
                java.lang.reflect.Method setCorner =
                        findMethodInHierarchy(d.getClass(), "setCornerRadius", float.class);
                if (setCorner != null) {
                    setCorner.setAccessible(true);
                    setCorner.invoke(d, radius);
                }
            }
            return d;
        } catch (Throwable t) {
            log("blur drawable unavailable - " + t);
            return null;
        }
    }

    /** 读取 drawable 的圆角半径；读不到返回 -1。 */
    private static float outlineRadiusOf(Drawable bg) {
        if (bg == null) {
            return -1f;
        }
        try {
            Outline outline = new Outline();
            bg.getOutline(outline);
            return outline.getRadius();
        } catch (Throwable ignored) {
            return -1f;
        }
    }

    /**
     * 按名解析 id。面板的布局来自 {@code com.miui.phrase} 的资源包，而面板进程（输入法进程）里
     * {@code view.getResources()} 拿到的是输入法自己的资源，按名查必然落空——这正是此前一直走
     * 「content child[0]」兜底的原因。这里改用 phrase 的资源包解析，跨版本稳且不依赖当前视图。
     */
    private static int phraseId(android.content.Context ctx, String name) {
        Integer cached = sIdCache.get(name);
        if (cached != null) {
            return cached;
        }
        int id = 0;
        try {
            android.content.res.Resources res = ctx.getPackageManager()
                    .getResourcesForApplication(XposedInit.PKG_PHRASE);
            id = res.getIdentifier(name, "id", XposedInit.PKG_PHRASE);
        } catch (Throwable ignored) {
            // 退回到当前视图的资源再试
        }
        if (id == 0) {
            try {
                id = ctx.getResources().getIdentifier(name, "id", null);
            } catch (Throwable ignored) {
                // 两者皆失败：id 保持 0，调用方走兜底
            }
        }
        sIdCache.put(name, id);
        return id;
    }

    /** 沿类继承链查找方法（含隐藏方法）。 */
    private static java.lang.reflect.Method findMethodInHierarchy(
            Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 读取面板自身圆角（取自原背景的 outline），读不到则用 24dp。 */
    private static float cornerRadiusOf(View body) {
        if (sPanelRadius >= 0f) {
            return sPanelRadius;
        }
        float radius = outlineRadiusOf(body.getBackground());
        if (radius <= 0f) {
            radius = 24f * body.getResources().getDisplayMetrics().density;
        }
        sPanelRadius = radius;
        return radius;
    }

    /** 替换颜色的 alpha 通道，保留 RGB。 */
    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
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
        bg.setAlpha(255); // 兜底：清掉 drawable 自身可能存在的透明通道
        bg.invalidateSelf();
        body.requestLayout();
        body.invalidate();
    }

    /**
     * 保证着色一定是「不透明」的：
     * <ul>
     *   <li>读不到（-1）或全透明（alpha==0，通常是被美化模块强改）→ 退回面板自身原色；</li>
     *   <li>半透明 → 保留色相、把 alpha 拉满；</li>
     *   <li>已不透明 → 原样返回。</li>
     * </ul>
     */
    private static int ensureOpaque(int color, boolean night) {
        if (color == -1 || Color.alpha(color) == 0) {
            int fb = panelFallbackColor(night);
            log("opaque fallback used -> " + hex(fb));
            return fb;
        }
        if (Color.alpha(color) < 255) {
            return (color & 0x00FFFFFF) | 0xFF000000;
        }
        return color;
    }

    /** 抓取面板上色前的原始底色（每个主题各抓一次），供底部栏不可用时兜底。 */
    private static void captureFallback(View body) {
        boolean night = isNight(body);
        if (night ? sFallbackDark != -1 : sFallbackLight != -1) {
            return;
        }
        int c = solidColorOf(body.getBackground());
        if (Color.alpha(c) == 255) {
            storeFallback(night, c, "panel");
            return;
        }
        // 兜底：MIUI 剪贴板面板自带色（浅 #F4F6F9 / 深 #101010）。
        storeFallback(night, night ? 0xFF101010 : 0xFFF4F6F9, "theme");
    }

    private static void storeFallback(boolean night, int color, String from) {
        if (night) {
            sFallbackDark = color;
        } else {
            sFallbackLight = color;
        }
        log("fallback captured(" + (night ? "dark" : "light") + ", " + from + ") -> " + hex(color));
    }

    private static int panelFallbackColor(boolean night) {
        int c = night ? sFallbackDark : sFallbackLight;
        return c != -1 ? c : 0xFFFFFFFF;
    }

    /** 当前是否深色模式。 */
    private static boolean isNight(View v) {
        return (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 安全地把 hook 参数当 int 取；不是整数则给 -1。 */
    private static int toInt(Object o) {
        return o instanceof Integer ? (Integer) o : -1;
    }

    /** 颜色转 {@code #aarrggbb}；-1 显示为 {@code -1}。 */
    private static String hex(int color) {
        return color == -1 ? "-1" : String.format("#%08x", color);
    }

    /** 读取 drawable 的纯色（ColorDrawable / GradientDrawable）；读不到返回 0。 */
    private static int solidColorOf(Drawable bg) {
        if (bg instanceof ColorDrawable) {
            return ((ColorDrawable) bg).getColor();
        }
        if (bg instanceof GradientDrawable) {
            try {
                ColorStateList csl = ((GradientDrawable) bg).getColor();
                if (csl != null) {
                    return csl.getDefaultColor();
                }
            } catch (Throwable ignored) {
                // 忽略，返回 0
            }
        }
        return 0;
    }

    /** 取底部功能栏当前实色：优先其 sBottomView 的 ColorDrawable 实色，退回静态字段，皆失败 -1。 */
    private static int getBottomBarColor() {
        Class<?> mgr = resolveBottomManager();
        if (mgr == null) {
            return -1;
        }
        try {
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
