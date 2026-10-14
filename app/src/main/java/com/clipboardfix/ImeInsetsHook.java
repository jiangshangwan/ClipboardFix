package com.clipboardfix;

import android.graphics.Insets;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 把「MIUI 工具栏所占的底部安全区」从输入法内容的 WindowInsets 里裁掉。
 *
 * <p><b>问题</b>：MIUI「全面屏优化」会往输入法窗口里注入一条底部工具栏（BottomView），
 * 它本身就占据导航栏/手势条那片区域。但输入法内容同时也从系统拿到
 * {@code navigationBars / systemGestures / tappableElement} 的底部 inset，于是**底部安全区
 * 被重复计算两次**（MIUI 工具栏一次 + 输入法自己避让一次）→ 键盘被整体抬高、底部留白。
 *
 * <p><b>解法</b>：
 * 不去改键盘视图的 padding / LayoutParams / translationY，而是**在 insets 分发链上
 * 精确剔除那几类 inset 的 bottom 值**（left/top/right 保留，以适配挖孔/刘海边距），
 * 让整棵输入法子树自然不再为「已由 MIUI 工具栏占用的区域」二次避让。
 *
 * <p>生效范围受 {@link Frame#isActive()} 严格约束：只有在「MIUI 确实注入了底部工具栏」
 * （工具栏与键盘容器同父、紧邻其后、可见且非 0 高）时才过滤，其它情况一律原样返回，
 * 因此不会误伤布局本就正确的输入法。
 */
final class ImeInsetsHook {

    private static void log(String msg) {
        XposedInit.log("[IME-insets] " + msg);
    }

    /** insets 类型：底部安全区的四个来源。 */
    private static int[] safeAreaTypes() {
        return new int[]{
                WindowInsets.Type.navigationBars(),
                WindowInsets.Type.systemGestures(),
                WindowInsets.Type.mandatorySystemGestures(),
                WindowInsets.Type.tappableElement(),
        };
    }

    /**
     * 复制一份 insets，并把上述四类 inset 的 bottom 置 0（left/top/right 保留）。
     * 若四类本来 bottom 就都是 0，直接返回原对象（避免无谓分配，也便于调用方判等）。
     */
    static WindowInsets withoutBottomSafeArea(WindowInsets source) {
        if (source == null) return null;
        try {
            WindowInsets.Builder builder = null;
            for (int type : safeAreaTypes()) {
                Insets current = source.getInsets(type);
                Insets maximum = source.getInsetsIgnoringVisibility(type);
                if (current.bottom == 0 && maximum.bottom == 0) continue;
                if (builder == null) builder = new WindowInsets.Builder(source);
                builder.setInsets(type, Insets.of(current.left, current.top, current.right, 0));
                builder.setInsetsIgnoringVisibility(type,
                        Insets.of(maximum.left, maximum.top, maximum.right, 0));
            }
            return builder == null ? source : builder.build();
        } catch (Throwable t) {
            log("withoutBottomSafeArea err - " + t);
            return source;
        }
    }

    /** 一对「键盘容器 + MIUI 底部工具栏」；只有二者构成 MIUI 注入的输入框时才认为生效。 */
    private static final class Frame {
        final WeakReference<ViewGroup> input;
        final WeakReference<ViewGroup> bottom;

        Frame(ViewGroup input, ViewGroup bottom) {
            this.input = new WeakReference<ViewGroup>(input);
            this.bottom = new WeakReference<ViewGroup>(bottom);
        }

        /** 与上游 isActive() 等价：确认 MIUI 的输入框结构成立。 */
        boolean isActive() {
            ViewGroup in = input.get();
            ViewGroup bo = bottom.get();
            if (in == null || bo == null) return false;
            ViewParent p = in.getParent();
            if (!(p instanceof LinearLayout)) return false;
            LinearLayout root = (LinearLayout) p;
            if (root.getOrientation() != LinearLayout.VERTICAL) return false;
            if (bo.getParent() != root) return false;
            if (root.indexOfChild(bo) != root.indexOfChild(in) + 1) return false;
            if (bo.getVisibility() != View.VISIBLE) return false;
            if (bo.getChildCount() <= 0) return false;
            ViewGroup.LayoutParams lp = bo.getLayoutParams();
            if (!(lp instanceof LinearLayout.LayoutParams)) return false;
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            return llp.height != 0 && llp.weight == 0f;
        }

        View rootView() {
            ViewGroup in = input.get();
            return in == null ? null : in.getRootView();
        }
    }

    /** key = 键盘容器（input）视图。 */
    private static final Map<View, Frame> FRAMES = new WeakHashMap<View, Frame>();
    /** key = 输入法窗口根视图。 */
    private static final Map<View, Frame> WINDOWS = new WeakHashMap<View, Frame>();
    /** 已处理的 manager 类，避免重复 hook。 */
    private static final Set<Class<?>> MANAGERS =
            Collections.synchronizedSet(new HashSet<Class<?>>());
    /** 已 hook 的输入法回调方法，避免重复。 */
    private static final Set<Method> SERVICE_CALLBACKS =
            Collections.synchronizedSet(new HashSet<Method>());

    /** 是否有任何已登记的 frame —— 用于在热路径上做零开销快速返回。 */
    private static volatile boolean hasFrames = false;

    /** 当前线程是否正处在「输入法自身回调」中（此时整个窗口的 insets 都应按已裁剪处理）。 */
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<Frame>();

    private static volatile boolean installed = false;

    private ImeInsetsHook() {
    }

    // ---------------------------------------------------------------
    // 登记：hook MIUI 的 addMiuiBottomView(...) 拿到 input / bottom 两个 ViewGroup
    // ---------------------------------------------------------------

    static void registerManager(Class<?> manager) {
        if (Build.VERSION.SDK_INT < 30 || manager == null) return;
        if (!MANAGERS.add(manager)) return;
        try {
            for (Method m : manager.getDeclaredMethods()) {
                if (!"addMiuiBottomView".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 6) continue;
                if (!ViewGroup.class.isAssignableFrom(ps[3])) continue;
                if (!ViewGroup.class.isAssignableFrom(ps[5])) continue;
                m.setAccessible(true);
                log("addMiuiBottomView params=" + Arrays.toString(ps));
                XposedInit.hook(m, chain -> {
                    Object r = chain.proceed();
                    try {
                        Object in = chain.getArg(3);
                        Object bo = chain.getArg(5);
                        if (in instanceof ViewGroup && bo instanceof ViewGroup) {
                            install();
                            Frame frame = new Frame((ViewGroup) in, (ViewGroup) bo);
                            synchronized (FRAMES) {
                                FRAMES.put((View) in, frame);
                            }
                            syncWindows();
                            hasFrames = true;
                            log("frame registered: input=" + in.getClass().getSimpleName()
                                    + " bottom=" + bo.getClass().getSimpleName());
                        }
                        // 顺便 hook 该输入法自身的回调，让其在 onCreateInputView/
                        // onStartInputView 阶段读到的 root insets 也已是裁剪后的。
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof InputMethodService) {
                                hookServiceCallbacks(((InputMethodService) arg).getClass());
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        log("register err - " + t);
                    }
                    return r;
                });
                log("OK: hook addMiuiBottomView (insets) on " + manager.getName());
            }
        } catch (Throwable t) {
            log("FAIL: registerManager - " + t);
        }
    }

    /** 维护 rootView → Frame 映射（对应上游的 windows 表）。 */
    private static void syncWindows() {
        synchronized (WINDOWS) {
            WINDOWS.clear();
            synchronized (FRAMES) {
                for (Frame f : FRAMES.values()) {
                    View root = f.rootView();
                    if (root != null) WINDOWS.put(root, f);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // 安装全局 hook（首次真正出现 MIUI 输入框时才装）
    // ---------------------------------------------------------------

    private static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            // 1) View.getRootWindowInsets()：输入法/键盘直接问窗口要 inset 的路径
            try {
                Method m = View.class.getDeclaredMethod("getRootWindowInsets");
                XposedInit.hook(m, chain -> {
                    Object r = chain.proceed();
                    if (!(r instanceof WindowInsets)) return r;
                    WindowInsets source = (WindowInsets) r;
                    try {
                        if (shouldFilterFor((View) chain.getThisObject())) {
                            return withoutBottomSafeArea(source);
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                });
                log("OK: hook View.getRootWindowInsets");
            } catch (Throwable t) {
                log("FAIL: getRootWindowInsets - " + t);
            }

            // 2) ViewGroup.dispatchApplyWindowInsets(WindowInsets)：子树分发入口。
            //    在键盘容器那一层把底部安全区裁掉，整棵子树都拿到裁剪后的 insets；
            //    同时把**原始** insets 作为返回值还给调用方，避免「已消费」的 inset
            //    泄漏给同为兄弟的 MIUI 工具栏。
            try {
                Method m = ViewGroup.class.getDeclaredMethod(
                        "dispatchApplyWindowInsets", WindowInsets.class);
                XposedInit.hook(m, chain -> dispatchHook(chain, 1));
                log("OK: hook ViewGroup.dispatchApplyWindowInsets");
            } catch (Throwable t) {
                log("FAIL: dispatchApplyWindowInsets - " + t);
            }

            // 3) 动画期分发（API 30+），与 2) 同处理
            try {
                Method m = ViewGroup.class.getDeclaredMethod(
                        "dispatchWindowInsetsAnimationProgress", WindowInsets.class, List.class);
                XposedInit.hook(m, chain -> dispatchHook(chain, 2));
                log("OK: hook dispatchWindowInsetsAnimationProgress");
            } catch (Throwable t) {
                log("SKIP: dispatchWindowInsetsAnimationProgress - " + t);
            }

            // 4) 输入法窗口每次显示前刷新一次，确保新的 insets 被重新分发
            try {
                Method m = InputMethodService.class.getDeclaredMethod(
                        "showWindow", boolean.class);
                XposedInit.hook(m, chain -> {
                    Object r = chain.proceed();
                    try {
                        refresh(((InputMethodService) chain.getThisObject()));
                    } catch (Throwable ignored) {
                    }
                    return r;
                });
                log("OK: hook InputMethodService.showWindow");
            } catch (Throwable t) {
                log("SKIP: showWindow - " + t);
            }

            // 5) 服务销毁时清掉登记，避免残留
            try {
                Method m = InputMethodService.class.getDeclaredMethod("onDestroy");
                XposedInit.hook(m, chain -> {
                    try {
                        Frame f = serviceFrame((InputMethodService) chain.getThisObject());
                        if (f != null && f.input.get() != null) {
                            synchronized (FRAMES) {
                                FRAMES.remove(f.input.get());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                log("OK: hook InputMethodService.onDestroy");
            } catch (Throwable t) {
                log("SKIP: onDestroy - " + t);
            }
        } catch (Throwable t) {
            log("FAIL: install - " + t);
        }
    }

    /** 分发 hook 的共用实现；insetsArgIndex = insets 在参数里的下标。 */
    private static Object dispatchHook(io.github.libxposed.api.XposedInterface.Chain chain,
                                       int insetsArgIndex) throws Throwable {
        Object self = chain.getThisObject();
        Frame frame = self instanceof View ? frameOf((View) self) : null;
        if (frame == null || !frame.isActive()) {
            return chain.proceed();
        }
        try {
            Object arg = chain.getArg(insetsArgIndex);
            if (!(arg instanceof WindowInsets)) return chain.proceed();
            WindowInsets original = (WindowInsets) arg;
            WindowInsets filtered = withoutBottomSafeArea(original);
            if (filtered == original) return chain.proceed();

            List<Object> args = new ArrayList<Object>(chain.getArgs());
            args.set(insetsArgIndex, filtered);
            chain.proceedWith(self, args.toArray());
            // 让调用方（尤其作为兄弟的 MIUI 工具栏）依旧拿到原始 insets
            return original;
        } catch (Throwable t) {
            log("dispatch err - " + t);
            return chain.proceed();
        }
    }

    /** 该 view 自己就是已登记的键盘容器（且生效）？ */
    private static Frame frameOf(View v) {
        if (!hasFrames) return null;
        synchronized (FRAMES) {
            return FRAMES.get(v);
        }
    }

    /** 该 view 是否位于「已登记且生效」的键盘容器子树内（或其本身）。 */
    private static boolean shouldFilterFor(View v) {
        if (!hasFrames || v == null) return false;
        // 输入法自身回调执行期间：整个窗口都按裁剪后处理
        Frame current = CURRENT.get();
        if (current != null && current.isActive()) {
            View root = current.rootView();
            if (root != null && v.getRootView() == root) return true;
        }
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            Frame f = frameOf(cur);
            if (f != null && f.isActive()) return true;
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** 借 showWindow 时机重新分发一次 insets / 布局。 */
    private static void refresh(InputMethodService service) {
        Frame f = serviceFrame(service);
        if (f == null) return;
        ViewGroup input = f.input.get();
        if (input == null) return;
        input.requestApplyInsets();
        input.requestLayout();
    }

    private static Frame serviceFrame(InputMethodService service) {
        try {
            if (service.getWindow() == null) return null;
            View decor = service.getWindow().getWindow().getDecorView();
            if (decor == null) return null;
            synchronized (WINDOWS) {
                return WINDOWS.get(decor);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * hook 该输入法自己重写的生命周期回调；在这些回调执行期间把当前线程标记为
     * 「处于输入法窗口作用域」，使其内部读取的 root insets 同样被裁剪。
     */
    private static void hookServiceCallbacks(Class<?> imeClass) {
        String[] names = {"onCreateInputView", "onStartInput", "onStartInputView", "onWindowShown"};
        Class<?>[][] sigs = {
                {},
                {EditorInfo.class, boolean.class},
                {EditorInfo.class, boolean.class},
                {},
        };
        for (int i = 0; i < names.length; i++) {
            try {
                Method m = imeClass.getMethod(names[i], sigs[i]);
                if (!SERVICE_CALLBACKS.add(m)) continue;
                m.setAccessible(true);
                XposedInit.hook(m, chain -> {
                    Object self = chain.getThisObject();
                    Frame prev = CURRENT.get();
                    Frame frame = (self instanceof InputMethodService)
                            ? serviceFrame((InputMethodService) self) : null;
                    try {
                        if (frame != null) CURRENT.set(frame);
                        return chain.proceed();
                    } finally {
                        if (prev == null) CURRENT.remove();
                        else CURRENT.set(prev);
                    }
                });
                log("OK: scope hook " + imeClass.getSimpleName() + "." + names[i]);
            } catch (Throwable ignored) {
                // 该输入法没重写这个回调，跳过
            }
        }
    }
}
