package com.clipboardfix;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 让剪贴板面板（{@code InputMethodClipboardPhrasePopupView}）的背景色与底部输入法功能栏
 * （{@code InputMethodBottomManager.sBottomView}）保持一致。
 *
 * <p><b>进程归属</b>：面板运行在输入法进程（hld 隔离进程形态，包名见注入点），
 * 不在 {@code com.miui.phrase} 进程。
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
     * 面板高斯模糊半径。与主流输入法美化模块的默认模糊强度一致（{@code DEFAULT_BLUR_RADIUS=60}），
     * 这样两者的玻璃观感最接近。
     */
    private static final int BLUR_RADIUS = 60;

    /** 当前显示的面板内容容器，供换色时同步刷新（面板消失即被回收）。 */
    private static volatile WeakReference<View> sPanelBodyRef = new WeakReference<>(null);

    /**
     * 面板当前是深色底还是浅色底（由实际背景亮度决定，不是系统 uiMode）。
     * 文字对比守卫按它决定「该把文字翻到哪一侧」。
     */
    private static volatile boolean sContrastDark = false;

    /** 已挂过 pre-draw 守卫的面板容器（弱引用集合，视图回收即自动移除）。 */
    private static final java.util.WeakHashMap<View, Boolean> sContrastWatched =
            new java.util.WeakHashMap<>();

    /**
     * 记录「本模块上一次写进 TextView 的颜色」。
     *
     * <p><b>为什么不用 {@code View.setTag(int,Object)}</b>：该 API 要求 key 是
     * 「应用资源 id」（高字节为包标识），而 {@code View.generateViewId()} 生成的是
     * 0x00xxxxxx，直接抛 {@code IllegalArgumentException: The key must be an
     * application-specific resource id}，会把整个上色流程打断（实测踩过）。
     * 也不用 {@code setTag(Object)}——RecyclerView 自己要用单槽 tag。
     * 这里用弱键表记账，视图回收即自动清理。
     */
    private static final java.util.WeakHashMap<TextView, Integer> sContrastApplied =
            new java.util.WeakHashMap<>();

    /**
     * 图标原色（每个 ImageView 只测一次）。
     *
     * <p>图标颜色烘焙在 drawable 里（{@code android:src} 的矢量图，没有 tint 列表），
     * 只能靠「把 drawable 画进一张小位图再采样」得到。采样必须发生在写入 ColorFilter
     * <b>之前</b>并缓存——否则采样到的是我们自己染过的色，下一帧又会翻回去，形成
     * 一帧一翻的闪烁。视图回收即自动清理。
     */
    private static final java.util.WeakHashMap<ImageView, Integer> sIconOriginal =
            new java.util.WeakHashMap<>();

    /** 本模块已写入的图标 ColorFilter 色，用于幂等与主题切换时撤销。 */
    private static final java.util.WeakHashMap<ImageView, Integer> sIconApplied =
            new java.util.WeakHashMap<>();

    /** 对比守卫日志限流（每次面板上色重置）。 */
    private static volatile int sContrastLogLeft = 0;

    /** 深色底上的文字色（纯白，保留原 alpha）。 */
    private static final int RGB_TEXT_ON_DARK = 0x00FFFFFF;

    /** 浅色底上的文字色（近似 MIUI 主文字色，保留原 alpha）。 */
    private static final int RGB_TEXT_ON_LIGHT = 0x001A1A1A;

    /**
     * 浅色面板上的条目卡片实色底。
     *
     * <p>卡片背景同样来自 phrase 资源的 {@code -night} 限定：夜间是半透明白（实测约 12%
     * alpha），叠在本模块涂成浅色的面板底上只剩 {@code #E5E6EA}，与底色 {@code #E1E2E6}
     * 几乎无差，条目看起来"糊在背景里"；日间实色底实测为 {@code #F3F3F5}。
     */
    private static final int RGB_CARD_ON_LIGHT = 0xFFF3F3F5;

    /** 已补过底的视图（按视图 + 背景实例记账，避免每帧重复处理）。 */
    private static final java.util.WeakHashMap<View, Drawable> sCardOurs =
            new java.util.WeakHashMap<>();

    /** 当前正在上色的面板根容器（根背景由 {@link #applyPanelBackground} 负责，不参与补底）。 */
    private static volatile View sCurrentBody = null;

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
        // 背景已按输入法主题着色，但面板文字色来自 phrase 资源的「系统夜间」限定符。
        // 系统深色 + 输入法浅色时文字仍是白色 → 白底白字。这里按实际底色亮度纠偏。
        applyTextContrast(body, isDarkColor(base));
        log("applied solid -> " + hex(base));
    }

    /**
     * 面板模糊层之上要叠的底色，目标是与底部功能区「同款」——而功能区自身就是
     * {@code ColorDrawable #00000000}（全透明、直接透出键盘，见 {@link #dumpBarOnce} 实证），
     * 即它的观感 = <b>裸模糊、不加任何颜色叠加</b>。所以面板也必须是裸模糊，否则一定有色差：
     * <ul>
     *   <li><b>浅色</b>：返回全透明，调用方只挂模糊层；</li>
     *   <li><b>深色</b>：同样不叠底色。仅当系统下发了<b>深色</b>半透明合成色
     *       （{@code setBottomColor} 第 4 参）时才照抄（alpha 平方折算）。</li>
     * </ul>
     *
     * <p><b>历史坑 1（浅色「脏灰面板」的根因）</b>：早期照抄系统下发的 {@code #66202020}
     * 折算成 {@code 0x28 ≈ 16%} 压了一层暗色底，实测把面板压暗了约 19%（背板 243 → 196），
     * 而功能区同期只压暗约 7%（243 → 225）。结果面板变成一块脏灰底，上面浮着条目那张近白的
     * 卡片（条目是 MIUI 原生 {@code #99ffffff} = 白色 60%），视觉上非常突兀。去掉这层叠加后
     * 面板 ≈ 纯模糊 ≈ 功能区，条目与面板的落差也回到 MIUI 设计原本的幅度。
     *
     * <p><b>历史坑 2（深色「太白面板」，用户 2026-10-18 反馈）</b>：深色分支曾退回
     * {@code #F5F5F5}（功能区透明态的<i>内容色</i>，近白）叠 16%。但内容色是给图标/文字用的，
     * 不是给背景的；功能区背景本身分文未叠。于是同样的模糊键盘，面板被提亮约 39 个灰度级
     * （15 Pro 实测面板 75 vs 功能区 34；13 Pro 57 vs 0），深色模式下泛出一层白灰，
     * 与底部功能区色差明显。修正为与浅色对称：不叠底色，面板 = 裸模糊 ≈ 功能区。
     */
    private static int barLikeTint(boolean night) {
        if (!night) {
            return Color.TRANSPARENT;
        }
        int alphaColor = sBarAlphaColor;
        int a = Color.alpha(alphaColor);
        if (alphaColor != -1 && a > 0 && a < 255 && isDarkColor(alphaColor)) {
            return withAlpha(alphaColor, a * a / 255);
        }
        return Color.TRANSPARENT;
    }

    /** 颜色是否偏暗（感知亮度 &lt; 128）：系统合成色够暗才配当作深色模式的底。 */
    private static boolean isDarkColor(int color) {
        int lum = (Color.red(color) * 299 + Color.green(color) * 587
                + Color.blue(color) * 114) / 1000;
        return lum < 128;
    }

    /**
     * 给面板套上系统级背景高斯模糊：底层是 {@code ViewRootImpl.createBackgroundBlurDrawable()}
     * 生成的真实背景模糊；仅当 {@link #barLikeTint} 给出非透明底色（系统下发的深色合成色）时
     * 才在上层补一层半透明底，否则保持与功能区一致的裸模糊；圆角沿用面板自身的圆角。
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
            // 功能区没有任何颜色叠加，面板也必须「裸模糊」才能同色（深浅色同理）。
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
        // 玻璃面板下文字同样可能处于错误的明暗侧（系统深色 + 输入法浅色）：
        // 底栏内容色（content）本身跟随输入法主题，用它判断更准，取不到再退回系统 uiMode。
        applyTextContrast(body, panelDarkFromContent(night));
        log("blur applied: radius=" + BLUR_RADIUS + " corner=" + radius
                + " tint=" + hex(tint) + (night ? " (dark)" : " (light)"));
        return true;
    }

    /**
     * 深色主题判定：优先用底栏下发的「内容色」——它跟着输入法主题走
     * （深色主题内容色偏亮，浅色主题偏暗），比系统 {@code uiMode} 更贴合实际观感。
     * 内容色未就绪时退回系统 uiMode。
     */
    private static boolean panelDarkFromContent(boolean night) {
        int c = sBarContentColor;
        if (c != -1 && Color.alpha(c) > 0) {
            return !isDarkColor(c);
        }
        return night;
    }

    /**
     * 前景对比守卫（文字 + 图标）：把「与背景同色、处于错误明暗侧」的前景色翻到可读的一侧。
     *
     * <p><b>为什么需要</b>：面板文字色和图标色都来自 {@code com.miui.phrase} 布局资源的
     * {@code -night} 限定符，跟随<b>系统</b> uiMode；而面板背景由本模块改成跟随
     * <b>输入法主题</b>（底栏色）。系统深色 + 输入法浅色时两者打架：背景被涂成
     * 浅色、文字和图标仍是白色 → 白底白字，内容完全不可见（用户 2026-10-19 反馈，100% 复现）。
     *
     * <p><b>为什么安全</b>：只在「前景与背景明暗同侧」时翻转，主题一致时判据恒不成立，
     * 一个像素都不会改。翻转保留原 alpha（次要文字的灰度层级不丢），彩色前景
     * （链接、强调色，饱和度 &gt; 24）一律不动。
     */
    private static void applyTextContrast(View body, boolean dark) {
        if (body == null) {
            return;
        }
        sContrastDark = dark;
        sCurrentBody = body;
        sContrastLogLeft = 24;
        ensureContrastWatched(body);
        contrastWalk(body, dark);
    }

    /** 给面板容器挂一次 pre-draw 守卫，覆盖 RecyclerView 回收复用的新条目。 */
    private static void ensureContrastWatched(View body) {
        if (sContrastWatched.containsKey(body)) {
            return;
        }
        sContrastWatched.put(body, Boolean.TRUE);
        ViewTreeObserver vto = body.getViewTreeObserver();
        if (vto == null || !vto.isAlive()) {
            return;
        }
        // pre-draw 在每次绘制前触发，回收出来的新条目在显示前就会被纠偏；
        // tag 记账保证同一颜色不会重复 setTextColor（否则会触发无限 invalidate）。
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (body.isAttachedToWindow()) {
                    contrastWalk(body, sContrastDark);
                }
                return true;
            }
        });
    }

    private static void contrastWalk(View v, boolean dark) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            int cur = tv.getCurrentTextColor();
            Integer prev = sContrastApplied.get(tv);
            boolean ours = prev != null && prev.intValue() == cur;
            if (!ours && needsFlip(cur, dark)) {
                int target = flipTextColor(cur, dark);
                tv.setTextColor(target);
                sContrastApplied.put(tv, target);
                if (sContrastLogLeft > 0) {
                    sContrastLogLeft--;
                    log("contrast flip " + hex(cur) + " -> " + hex(target)
                            + (dark ? " (dark bg)" : " (light bg)"));
                }
            }
        }
        if (v instanceof ImageView) {
            contrastIcon((ImageView) v, dark);
        }
        // 浅色面板上补条目卡片底：夜间半透明白叠在浅底上会与底色糊成一片
        if (!dark) {
            guardCardBackground(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                contrastWalk(g.getChildAt(i), dark);
            }
        }
    }

    /**
     * 条目卡片补底（深色系统 + 浅色输入法场景）。
     *
     * <p>面板文字、图标和卡片背景都来自 phrase 资源的 {@code -night} 限定，跟随<b>系统</b>
     * uiMode；而面板底色被本模块改成跟随<b>输入法主题</b>。系统深色 + 输入法浅色时，
     * 卡片背景仍是夜间那层半透明白（实测约 12% alpha），叠在浅底上得到 {@code #E5E6EA}，
     * 与面板底 {@code #E1E2E6} 几乎同色，条目失去卡片感（用户 2026-10-20 反馈）。
     *
     * <p>安全边界：只处理「白系 + 半透明」的背景图层，补成日间实色
     * {@link #RGB_CARD_ON_LIGHT}，圆角、描边、阴影层保持原 drawable 不动；实色背景
     * （含首页 Tab 选中胶囊）和彩色背景一律不碰；面板根容器由
     * {@link #applyPanelBackground} 单独负责，这里跳过。
     */
    private static void guardCardBackground(View v) {
        if (v == sCurrentBody) {
            return;
        }
        Drawable bg = v.getBackground();
        if (bg == null) {
            return;
        }
        if (sCardOurs.get(v) == bg) {
            return;
        }
        if (recolorNightWhite(bg, RGB_CARD_ON_LIGHT)) {
            sCardOurs.put(v, bg);
            if (sContrastLogLeft > 0) {
                sContrastLogLeft--;
                log("card bg " + viewName(v) + " -> " + hex(RGB_CARD_ON_LIGHT));
            }
        }
    }

    /**
     * 递归把「夜间半透明白」的图层改成指定实色，返回是否改动。
     *
     * <p>逐层判断（{@link LayerDrawable} 常见于 MIUI 卡片：阴影层 + 实色层）。白系判定用
     * {@link #isWhiteish}（不约束 alpha），半透明判定再叠加 {@code Color.alpha(cur) < 240 ||
     * d.getAlpha() < 240}，从而同时覆盖「颜色自带低 alpha」与「纯白 drawable 整体 alpha 被压低」
     * 两种实现。深色阴影层、彩色描边、不透明实色层（含首页 Tab 选中胶囊）都原样保留。
     */
    private static boolean recolorNightWhite(Drawable d, int solid) {
        // RippleDrawable 是 LayerDrawable 的子类，必须先判它，取静态底 content 层。
        if (d instanceof android.graphics.drawable.RippleDrawable) {
            Drawable content = rippleContent(d);
            if (content != null) {
                return recolorNightWhite(content, solid);
            }
        }
        if (d instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) d;
            boolean any = false;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                Drawable layer = ld.getDrawable(i);
                if (layer != null && recolorNightWhite(layer, solid)) {
                    any = true;
                }
            }
            return any;
        }
        // 条目卡片背景实为 StateListDrawable（各个 pressed/normal 态），取当前生效子层再判。
        if (d instanceof android.graphics.drawable.StateListDrawable) {
            Drawable cur = stateListCurrent(d);
            return cur != null && recolorNightWhite(cur, solid);
        }
        // InsetDrawable / ScaleDrawable 等包装层，拆开后同样只判内层实色。
        if (d instanceof android.graphics.drawable.DrawableWrapper) {
            Drawable inner = ((android.graphics.drawable.DrawableWrapper) d).getDrawable();
            return inner != null && recolorNightWhite(inner, solid);
        }
        int cur = solidColorOf(d);
        if (!isWhiteish(cur)) {
            return false;
        }
        // 半透明有两种实现：颜色自带低 alpha，或颜色纯白但 drawable 整体 alpha 被压低。
        boolean translucent = Color.alpha(cur) < 240 || d.getAlpha() < 240;
        if (!translucent) {
            return false;
        }
        if (d instanceof GradientDrawable) {
            ((GradientDrawable) d).setColor(solid);
            d.setAlpha(255);
            return true;
        }
        if (d instanceof ColorDrawable) {
            ((ColorDrawable) d).setColor(solid);
            d.setAlpha(255);
            return true;
        }
        return false;
    }

    /**
     * 是否「夜间半透明白」：alpha 明显不足但不为零，且 RGB 为高亮度近灰阶。
     * 实色（alpha 接近 255）、全透明、彩色、深色胶囊都不会命中。
     */
    private static boolean isNightWhite(int color) {
        int a = Color.alpha(color);
        if (a < 8 || a > 240) {
            return false;
        }
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min > 20) {
            return false;
        }
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum >= 200;
    }

    /**
     * 是否「白系近灰阶」：只看色相与亮度，不约束 alpha。
     *
     * <p>与 {@link #isNightWhite} 的区别在于不限制透明度——纯白 drawable（alpha=255）但整体
     * alpha 被 {@code setAlpha(31)} 之类压低后，旧规则会因 alpha 满格
     * 被漏判；这里先放行白系，真正的「半透明」判定交给调用方的
     * {@code Color.alpha(cur) < 240 || d.getAlpha() < 240}。
     */
    private static boolean isWhiteish(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min > 20) {
            return false;
        }
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum >= 200;
    }

    /**
     * 取 {@link android.graphics.drawable.RippleDrawable} 的内部静态底图层（content）。
     *
     * <p>RippleDrawable 的涟漪色与静态底色是分开的，静态底色在私有字段里；不同 Android 版本
     * 字段名不一（{@code mDrawable} / {@code mChildDrawable} 等），这里尽力反射，取不到就返回
     * null（此时不动该背景，绝不误伤涟漪色）。
     */
    private static Drawable rippleContent(Drawable d) {
        if (!(d instanceof android.graphics.drawable.RippleDrawable)) {
            return null;
        }
        String[] fields = {"mDrawable", "mChildDrawable"};
        for (String name : fields) {
            try {
                java.lang.reflect.Field f = d.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object o = f.get(d);
                if (o instanceof Drawable) {
                    return (Drawable) o;
                }
            } catch (Throwable ignored) {
                // 该版本无此字段，试下一个
            }
        }
        // 再试 RippleState 内部的 content
        try {
            java.lang.reflect.Field fs = d.getClass().getDeclaredField("mState");
            fs.setAccessible(true);
            Object state = fs.get(d);
            if (state != null) {
                for (String name : fields) {
                    try {
                        java.lang.reflect.Field fc = state.getClass().getDeclaredField(name);
                        fc.setAccessible(true);
                        Object o = fc.get(state);
                        if (o instanceof Drawable) {
                            return (Drawable) o;
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 取 {@link android.graphics.drawable.StateListDrawable} 当前生效的子 drawable。
     *
     * <p>条目卡片背景是 {@code StateListDrawable}（normal/pressed 等多态），静态底色在父类
     * {@code DrawableContainer} 的私有字段 {@code mCurrDrawable} 里；沿类层级向上找，
     * 取不到返回 null（此时保持原样，不动）。
     */
    private static Drawable stateListCurrent(Drawable d) {
        Class<?> c = d.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("mCurrDrawable");
                f.setAccessible(true);
                Object o = f.get(d);
                if (o instanceof Drawable) {
                    return (Drawable) o;
                }
            } catch (Throwable ignored) {
                // 该类没有此字段，继续向上
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 图标对比守卫：与 {@link #applyTextContrast} 同一套判据，只是把「字形颜色」
     * 换成「图标颜色」，用 {@link ImageView#setColorFilter} 整体染色。
     *
     * <p><b>为什么不能只看 TextView</b>：面板的返回箭头、置顶按钮、条目来源小图标都是
     * {@code ImageView}，颜色烘焙在 {@code android:src} 的矢量图里（取自 phrase 资源的
     * {@code -night} 限定符），同样跟随系统而不是输入法主题。只修文字的话，就会出现
     * 「字黑了、图标还是白的」（用户 2026-10-19 二次反馈，截图实证）。
     *
     * <p><b>三道安全闸</b>：
     * <ol>
     *   <li>已经带 ColorFilter 的图标一律不动——说明颜色由别人管（如本模块置顶蓝钮
     *       自己 {@code setColorFilter(白, SRC_IN)}，染成深色就毁了）；</li>
     *   <li>自带实色底的图标不动——蓝底圆钮那类组件自成一套配色，与面板底色无关；</li>
     *   <li>彩色图标（饱和度 &gt; 24）不动，只翻转近灰阶的单色字形。</li>
     * </ol>
     */
    private static void contrastIcon(ImageView iv, boolean dark) {
        Drawable d = iv.getDrawable();
        if (d == null) {
            return;
        }
        // 闸 1：别人在控色（含本模块自己的白箭头）→ 不碰。
        if (iv.getColorFilter() != null && !sIconApplied.containsKey(iv)) {
            return;
        }
        // 闸 2：自带实色底（蓝底圆钮等）→ 不碰。
        if (Color.alpha(solidColorOf(iv.getBackground())) > 0) {
            return;
        }
        Integer origin = sIconOriginal.get(iv);
        if (origin == null) {
            int sampled = iconColorOf(iv);
            if (sampled == -1) {
                return;
            }
            origin = sampled;
            sIconOriginal.put(iv, origin);
            if (sContrastLogLeft > 0) {
                sContrastLogLeft--;
                log("icon color sample " + viewName(iv) + " -> " + hex(origin));
            }
        }
        if (!needsFlip(origin, dark)) {
            if (sIconApplied.remove(iv) != null) {
                iv.clearColorFilter();
                iv.invalidate();
            }
            return;
        }
        int target = flipTextColor(origin, dark);
        Integer prev = sIconApplied.get(iv);
        if (prev != null && prev.intValue() == target) {
            return;
        }
        iv.setColorFilter(target, PorterDuff.Mode.SRC_IN);
        sIconApplied.put(iv, target);
        if (sContrastLogLeft > 0) {
            sContrastLogLeft--;
            log("icon flip " + viewName(iv) + " " + hex(origin) + " -> " + hex(target)
                    + (dark ? " (dark bg)" : " (light bg)"));
        }
    }

    /**
     * 取图标当前颜色：优先 {@link ImageView#getImageTintList()}（{@code android:tint}
     * 声明的图标能直接读到）；读不到就把 drawable 画进 24×24 位图采样。
     */
    private static int iconColorOf(ImageView iv) {
        ColorStateList tint = iv.getImageTintList();
        if (tint != null) {
            int c = tint.getColorForState(iv.getDrawableState(), Color.TRANSPARENT);
            if (Color.alpha(c) != 0) {
                return c;
            }
        }
        return sampleDrawable(iv.getDrawable());
    }

    /**
     * 把 drawable 画进 24×24 位图后采样出「字形色」。
     *
     * <p>只统计 alpha ≥ 25% 的像素（细线条抗锯齿边缘几乎全透明，会拉低均值），
     * RGB 取其中最不透明的一批的均值，alpha 取最大值——单色图标据此能得到干净的
     * 原色，翻色后也保持实心、不会被抗锯齿稀释成半透明灰。
     *
     * @return 采样失败或整张图全透明时返回 -1（调用方跳过，绝不写非法色值）
     */
    private static int sampleDrawable(Drawable d) {
        Bitmap bmp = null;
        try {
            int n = 24;
            bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, n, n);
            try {
                d.draw(canvas);
            } finally {
                d.setBounds(old);
            }
            int aMax = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int a = Color.alpha(bmp.getPixel(x, y));
                    if (a > aMax) {
                        aMax = a;
                    }
                }
            }
            if (aMax < 64) {
                return -1;
            }
            int cut = aMax * 3 / 4;
            long r = 0, g = 0, b = 0;
            int cnt = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < cut) {
                        continue;
                    }
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    cnt++;
                }
            }
            if (cnt == 0) {
                return -1;
            }
            return Color.argb(aMax, (int) (r / cnt), (int) (g / cnt), (int) (b / cnt));
        } catch (Throwable t) {
            return -1;
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
    }

    /** 视图名（{@code 类名#id}），仅用于日志排查。 */
    private static String viewName(View v) {
        return v.getClass().getSimpleName() + "#" + hex(v.getId());
    }

    /** 文字是否需要翻转：仅处理「近灰阶」且明暗与背景同侧的颜色。 */
    private static boolean needsFlip(int color, boolean dark) {
        if (Color.alpha(color) == 0) {
            return false;
        }
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min > 24) {
            return false; // 彩色文字（链接/强调）不动
        }
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return dark ? lum <= 90 : lum >= 200;
    }

    /** 翻到背景的对侧，保留原 alpha。 */
    private static int flipTextColor(int color, boolean dark) {
        int a = Color.alpha(color);
        return (a << 24) | (dark ? RGB_TEXT_ON_DARK : RGB_TEXT_ON_LIGHT);
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
