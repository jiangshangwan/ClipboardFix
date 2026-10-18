package com.clipboardfix;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 剪贴板条目置顶（右滑置顶 / 再滑取消，最多一条，清空时保留）。
 *
 * <p><b>功能定义</b>：
 * <ul>
 *   <li>面板条目<b>右滑</b>置顶该条；再次右滑已置顶条目则取消置顶。最多同时置顶一条，
 *       置顶新条目会自动顶掉旧置顶。</li>
 *   <li>置顶条目始终排在列表首位（查询返回时重排，不改动 provider 数据）。</li>
 *   <li>点「清理剪贴板」清空全部条目后，被置顶的条目会被自动写回并保留。</li>
 *   <li>置顶条目显示「（已置顶）」提醒：云端条目标在其来源标注（「来自我的电脑」）
 *       后；本地条目借来源标注位把提醒放到<b>正文上方</b>（避免长文本把追加在
 *       正文后的提醒 ellipsize 掉）；取消置顶后随列表刷新消失。</li>
 *   <li>本地条目正文自带上下内边距（真机实测 padT=padB=22px，是系统给「无来源标注」
 *       的本地条目留的呼吸空间），云端条目没有（padT=padB=0）。本地置顶借来源标注位
 *       加了「（已置顶）」行后，这个内边距会把整条撑高（实测 165px vs 云端 152px，
 *       用户反馈「置顶条目明显比别的条目高」）。故本地置顶时把正文内边距对齐云端
 *       （通常为 0）；该改动由 {@link #sPadSaved} 记账，ViewHolder 复用或状态翻转时
 *       精确复位，避免残留影响非置顶条目。</li>
 * </ul>
 *
 * <p><b>方向说明</b>：系统自带的删除是<b>左滑</b>（主视图向左移、露出右侧删除按钮，
 * 见 {@code SwipeRevealLayout} dragEdge=RIGHT + {@code clampViewPositionHorizontal} 的
 * {@code [closeLeft - secWidth, closeLeft]} 区间）。左滑方向被系统删除占用，
 * 因此置顶手势做在<b>右滑</b>（系统未使用的空档方向），左滑删除流完全不动。
 *
 * <p><b>进程归属</b>：全部逻辑装在<b>默认输入法进程</b>：
 * <ol>
 *   <li><b>查询重排</b>：hook {@code android.app.ApplicationContentResolver.query}，
 *       URI 含 {@code getClipboardList} 时把 {@code time == pinnedTime} 的行挪到首位，
 *       构建 {@link MatrixCursor} 返回（列名从原游标拷贝，关闭原游标防泄漏）。
 *       不动 {@code com.miui.phrase} 进程的 provider 数据。</li>
 *   <li><b>清空保护</b>：hook {@code ApplicationContentResolver.call}，
 *       {@code method == "clearClipboardDataMethod"} 时先 {@code proceed()} 完成清空，
 *       再用 {@code call(uri, "saveClipboardCipherText", null, bundle)}（bundle key
 *       {@code jsonArray}）把置顶条目写回，最后刷新面板。</li>
 *   <li><b>手势</b>：hook 框架 {@code PopupWindow} 内容/显示点，按实例类名识别面板
 *       （面板类在动态 dex 里，进程启动时按名加载必失败，与 {@link PanelColorHook}
 *       同一套绕法），拿到面板 classloader 后懒挂
 *       {@code com.miui.inputmethod.SwipeRevealLayout} 的
 *       {@code onInterceptTouchEvent}/{@code onTouchEvent} 实现右滑状态机
 *       （每实例一份状态，存 {@link WeakHashMap}）。</li>
 * </ol>
 *
 * <p><b>条目身份</b>：{@code ClipboardContentModel.time}（JSON key {@code "time"}，
 * 毫秒时间戳）作为唯一标识。置顶条目被用户左滑删除后，下次查询匹配不到该 time，
 * 自动清置顶状态。
 *
 * <p><b>持久化</b>：置顶状态存输入法应用 {@code files/clipboardfix_pin.txt}
 * （第一行 time，其余为条目 JSON），输入法重启后仍保留。
 *
 * <p><b>开关</b>：由「功能」页「开启剪贴板条目置顶」控制，默认关闭；
 * 开关推进程启动时生效（与现有功能开关一致）。
 */
public final class ClipboardPinHook {

    // ---------------- 常量 ----------------

    private static final String PHRASE_POPUP =
            "com.miui.inputmethod.InputMethodClipboardPhrasePopupView";
    private static final String SWIPE_REVEAL =
            "com.miui.inputmethod.SwipeRevealLayout";
    private static final String CLIPBOARD_ADAPTER =
            "com.miui.inputmethod.InputMethodClipboardAdapter";
    private static final String CLIPBOARD_MANAGER =
            "com.miui.inputmethod.MiuiClipboardManager";
    private static final String ACR =
            "android.app.ContextImpl$ApplicationContentResolver";
    private static final String POPUP_WINDOW = "android.widget.PopupWindow";
    private static final String RECYCLER_VIEW =
            "androidx.recyclerview.widget.RecyclerView";

    /** 置顶状态文件（输入法应用 files 目录）。 */
    private static final String PIN_FILE = "clipboardfix_pin.txt";

    /** provider 游标的内容列名（ClipboardContentModel 的 JSON 行）。 */
    private static final String COL_CONTENT = "phrase_content";

    /** 清空剪贴板的 provider 方法名。 */
    private static final String METHOD_CLEAR = "clearClipboardDataMethod";

    /** 保存剪贴板列表的 provider 方法名（写回置顶条目用）。 */
    private static final String METHOD_SAVE = "saveClipboardCipherText";

    /** 置顶判定：查询 URI 特征串。 */
    private static final String URI_LIST = "getClipboardList";

    /** 置顶按钮直径（dp），与系统删除按钮视觉规格对齐（原生删除圆实测 58dp）。 */
    private static final float PIN_BTN_DP = 58f;

    /** 置顶按钮与条目边缘的间距（dp）——与原生删除门一致（SRL paddingHorizontal 18dp）。 */
    private static final float PIN_BTN_MARGIN_DP = 18f;

    /** 置顶按钮底色（蓝，与系统删除按钮的红区分）。 */
    private static final int PIN_BTN_COLOR = 0xFF3B82F6;

    /**
     * 置顶按钮内图标相对圆边的内边距（dp）。
     *
     * <p>58dp 圆减去左右各 17dp，留 24dp 给箭头（约占圆径 41%）——按真机截图实测标定：
     * 系统删除按钮的垃圾桶图标约占圆径 38.5%，箭头若按 9dp 内边距会占到 66.7%，
     * 明显比删除图标大一圈（用户反馈「占满整个圆圈、与删除图标严重不协调」）。
     */
    private static final float PIN_ICON_PAD_DP = 17f;

    /**
     * 置顶条目标注：追加在来源标注（「来自我的电脑」）后的提醒文字。
     * 硬编码——hook 跑在输入法进程，用不到本 APK 的字符串资源。
     */
    private static final String PIN_MARK = "（已置顶）";

    /**
     * 云端条目正文的上下内边距（px）——首次见到云端条目时记录。
     *
     * <p>真机实测：本地条目正文带 22px 上下内边距（系统给「无来源标注」条目留的
     * 呼吸空间），云端条目正文为 0。本地置顶借来源标注位加了「（已置顶）」行后，
     * 这个内边距会把整条撑高（本地 165px vs 云端 152px）。把本地正文的内边距对齐
     * 云端（实测为 0），本地置顶条目即与云端条目等高。默认 0（未捕获时按 0）。
     */
    private static int sCloudContentPadT = 0;
    private static int sCloudContentPadB = 0;

    /** 是否已捕获云端正文内边距（抓一次即可）。 */
    private static boolean sCloudContentPadCaptured = false;

    /**
     * 云端「来源标注」的实测字号（px）与颜色——首次见到云端条目时记录。
     *
     * <p>本地置顶复用同一个 tip 容器，若不套用云端那套「小字 + 灰色」，标记会以 tip
     * TextView 的默认大字渲染，整行比云端条目高一截（用户实拍：本地置顶条目明显高于
     * 云端条目）。故记下云端来源标注样式，本地标记套用同款，使行高一致。
     */
    private static float sSrcLabelSizePx = 0f;
    private static int sSrcLabelColor = 0xFFFFFFFF;

    /**
     * 诊断开关：置 true 时打印置顶标记几何（字号/行高/条目高），真机校准用，稳定后关掉。
     *
     * <p>已用它定位并修掉「本地置顶条目被撑高」：真机实测本地正文 padT=padB=22px、
     * 云端 0px，正文字内边距差 44px 正是条目高差（165px vs 152px）的来源。
     */
    private static final boolean PIN_DIAG = false;

    /** 来源标注容器（phrase 包 id 名）：云条目显示「来自我的电脑」+云图标，本地条目 GONE。 */
    private static final String ID_TIP_BOX = "clipboard_across_devices_tip";

    /** 来源标注图标（phrase 包 id 名）：云条目的电脑/云图标。 */
    private static final String ID_TIP_ICON = "clipboard_across_devices_tip_image";

    /** 来源标注 TextView（phrase 包 id 名）：云端条目显示「来自我的电脑」。 */
    private static final String ID_TIP_TEXT = "clipboard_across_devices_tip_text";

    /**
     * 正文 TextView（phrase 包 id 名）：文本条目用 top、图片条目用 bottom。
     * 仅作 id 解析失败时的兜底标注位——正常路径本地条目把提醒放到来源标注位
     * （{@link #ID_TIP_BOX}），即正文上方。
     */
    private static final String ID_TEXT_TOP = "clipboard_text_item_top";
    private static final String ID_TEXT_BOTTOM = "clipboard_text_item_bottom";

    // ---------------- 置顶门状态 ----------------

    /** 全关：左滑走系统删除，右滑开置顶门。 */
    private static final int MODE_NONE = 0;

    /** 正在右划开置顶门（主视图跟手）。 */
    private static final int MODE_OPENING = 1;

    /** 置顶门已开（按钮可见，等点击）。 */
    private static final int MODE_PIN_OPEN = 2;

    // ---------------- 置顶状态（进程级） ----------------

    /** 置顶条目的 time；0 表示无置顶。 */
    private static volatile long sPinnedTime = 0L;

    /** 置顶条目的原始 JSON（写回 / 重排都靠它）。 */
    private static volatile String sPinnedJson = null;

    /** 持久化文件是否已加载过（只试一次，失败保持内存态）。 */
    private static volatile boolean sPinLoaded = false;

    // ---------------- 数据快照（每次查询更新） ----------------

    /** 最近一次查询结果的 time 序列（已是重排后的显示顺序，按位置对应条目）。 */
    private static volatile List<Long> sRowTimes = new ArrayList<>();

    /** time -> 原始 JSON，供置顶时取写回内容。 */
    private static volatile Map<Long, String> sTimeJson = new HashMap<>();

    /** 面板当前显示的列表（hook adapter.setClipboardList 捕获，兜底取行身份用）。 */
    private static volatile WeakReference<List<?>> sDisplayListRef = new WeakReference<>(null);

    /** 面板列表适配器实例（hook adapter.setClipboardList 捕获，刷新面板用）。 */
    private static volatile WeakReference<Object> sAdapterRef = new WeakReference<>(null);

    // ---------------- 面板上下文 ----------------

    /** 面板实例（识别到即存，消失后自然回收）。 */
    private static volatile WeakReference<PopupWindow> sPopupRef = new WeakReference<>(null);

    /** 输入法 context（取 contentResolver / files 目录）。 */
    private static volatile WeakReference<Context> sImeCtxRef = new WeakReference<>(null);

    /** 面板实例所在 classloader（能解析 com.miui.inputmethod.*）。 */
    private static volatile ClassLoader sPanelCl = null;

    /** 进程默认 classloader（兜底）。 */
    private static volatile ClassLoader sDefaultCl = null;

    /** 当前宿主包名（仅日志用）。 */
    private static volatile String sHostPkg = null;

    /** 防重复装配（onPackageLoaded 可能多次回调）。 */
    private static volatile boolean sInitDone = false;

    /** 手势 hook 是否已挂上（懒挂 + 有界重试）。 */
    private static volatile boolean sGestureHooked = false;

    /** {@code mRectMainClose} 字段（懒解析，失败置 null 退 0 基准）。 */
    private static volatile boolean sRectFieldTried = false;
    private static Field sRectMainCloseField;

    /**
     * 主视图「关门位」left —— 系统 {@code mRectMainClose.left}（onLayout →
     * initRects 用主视图自然布局位填充，删除门关闭时主视图 layout 在此）。
     *
     * <p><b>为什么不能拿 0 当基准</b>：真机 trace 实证 clipboard 条目的主视图
     * 关闭位是 {@code left=47}（行内 18dp 左边距），删除门开度 =
     * {@code 关门位 - 当前 left}。早期判据写 {@code left < -1}（等价于门开
     * ≥153px 才认门开），微滑只把门拖开几 px~21px 时完全漏判——而我们消费
     * 手势后系统收不到 UP（没有 onViewReleased 兜底），门就冻结在小开度，
     * 留下一点红边（症状1）。反射失败退 0（旧行为，仍能抓全开门）。
     */
    private static int closedLeftOf(Object swipe) {
        if (!sRectFieldTried) {
            sRectFieldTried = true;
            try {
                sRectMainCloseField = Reflect.findField(swipe.getClass(), "mRectMainClose");
                // 私有字段必须显式放开可访问性，否则 get() 抛
                // IllegalAccessException（Reflect.findField 不代劳）——
                // 症状1 修复首版实测：异常被 catch(ignored) 静默吞掉，
                // closedLeftOf 恒返回 0，判据退化成旧版 left<=-2，
                // 微开门（mainL=38 级）继续漏判，红边依旧。
                sRectMainCloseField.setAccessible(true);
                log("mRectMainClose resolved on " + swipe.getClass().getName());
            } catch (Throwable t) {
                sRectMainCloseField = null;
                log("mRectMainClose unresolvable, fallback 0 - " + t);
            }
        }
        if (sRectMainCloseField != null) {
            try {
                Object r = sRectMainCloseField.get(swipe);
                if (r instanceof android.graphics.Rect) {
                    return ((android.graphics.Rect) r).left;
                }
                log("mRectMainClose not a Rect: " + r);
            } catch (Throwable t) {
                // 读数失败本次退 0（旧行为，仍能抓全开门）
                log("mRectMainClose read failed, fallback 0 - " + t);
            }
        }
        return 0;
    }

    /**
     * 系统删除门是否开着（主视图 left 低于关门位 2px 以上）。
     *
     * <p>反编译实证系统 M.e 在每次 DOWN 就被 tryCaptureView 的副作用武装成
     * DRAGGING 且无逐事件 slop——我们 proceed 掉的每个左向 MOVE 都会 1:1
     * 拖动主视图 left；2px 容差内视为噪声。
     */
    private static boolean systemDoorOpen(Object swipe, View main) {
        return main != null && main.getLeft() <= closedLeftOf(swipe) - 2;
    }

    /** 每实例手势状态。 */
    private static final WeakHashMap<Object, GestureState> sStates = new WeakHashMap<>();

    /**
     * 当前开着置顶门的 swipe（全局单开策略：同时只允许一条条目开着置顶门，
     * 与原生删除门一致——开第二条时第一条自动关）。
     *
     * <p>弱引用：条目视图被 RV 回收时不拖内存。只作「哪条开着」的记账，
     * 真正的显隐/位移仍由各实例的 mode + translationX + 按钮状态决定。
     */
    private static WeakReference<Object> sOpenSwipeRef = new WeakReference<>(null);

    /**
     * 单开互斥：关掉除 {@code keep} 外所有开着置顶门的条目。
     *
     * <p>调用点只有 {@link #dragPinDoor} 入口（某条门开始跟手即意味着用户
     * 选中了它）。快路：无其他门开着时零开销；关过后记录置空，同一次
     * 跟手的后续 MOVE 不会重复干活（也不会刷日志）。
     */
    private static void closeOtherPinDoors(Object keep) {
        Object other = sOpenSwipeRef.get();
        if (other == null || other == keep) {
            return;
        }
        GestureState ost = sStates.get(other);
        if (ost != null) {
            ost.mode = MODE_NONE;
            ost.declined = false;
        }
        View omain = mainViewOf(other);
        // 复用统一关门路径：记账清理 + 主视图/按钮 150ms 归位动画 + GONE，
        // 与被用户主动拖关的视觉完全一致
        closePinDoor(other, omain, pinDoorWidth((View) other));
        // 被关的门若系统删除门也微开着（上回合 UP 兜底漏网的极端情况），
        // 一并 close(false) 修回关门位，保证互斥切换后画面只剩新门
        neutralizeSystemDoor(other, omain);
        log("other pin door auto-closed (single-open policy)");
    }

    /** 记下当前开着置顶门的 swipe（dragPinDoor 跟手即记账）。 */
    private static void markPinDoorOpen(Object swipe) {
        if (sOpenSwipeRef.get() != swipe) {
            sOpenSwipeRef = new WeakReference<>(swipe);
        }
    }

    /** 清理单开记账（关门/硬复位时调用；非当前记录则无操作）。 */
    private static void clearPinDoorOpen(Object swipe) {
        if (sOpenSwipeRef.get() == swipe) {
            sOpenSwipeRef = new WeakReference<>(null);
        }
    }

    /**
     * ViewHolder.itemView → 适配器位置映射（hook {@code onBindViewHolder} 建立）。
     *
     * <p>面板的 RecyclerView 是 MIUI 定制版（dex 优化裁掉了
     * {@code getChildAdapterPosition}/{@code getChildLayoutPosition}/{@code getChildViewHolder}
     * 等位置 API，实测 NoSuchMethodException），无法向 RV 问位置；
     * 而 adapter 的 {@code onBindViewHolder} 必然被调用，参数里就带 position，
     * 用它建映射最稳（View 复用重绑时会自动覆盖旧值）。
     */
    private static final WeakHashMap<View, Integer> sBindMap = new WeakHashMap<>();

    /**
     * 记录「我们」把哪些 itemView 的正文内边距改过，及其被改前的原值。
     *
     * <p>键为 itemView，值为 {@code {top.padT, top.padB, bottom.padT, bottom.padB}}。
     * ViewHolder 复用或状态翻转时用它精确复位——否则残留的「已归零内边距」会让
     * 下一个复用此 itemView 的非置顶条目少了呼吸空间，与其它条目高度不一致。
     */
    private static final WeakHashMap<View, int[]> sPadSaved = new WeakHashMap<>();

    private ClipboardPinHook() {
    }

    // ---------------- 装配 ----------------

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
        sInitDone = true;
        sDefaultCl = cl;
        sHostPkg = param.getPackageName();
        log("init enter: pkg=" + sHostPkg);

        // 数据面：客户端 query 重排 + 清空写回。对作用域内所有包生效，
        // 内部按 URI / method 过滤，非剪贴板流量原样放行。
        try {
            Class<?> acr = resolveAcr();
            if (acr != null) {
                hookQuery(acr, new Class<?>[]{
                        Uri.class, String[].class, String.class, String[].class, String.class});
                hookQuery(acr, new Class<?>[]{
                        Uri.class, String[].class, String.class, String[].class, String.class,
                        android.os.CancellationSignal.class});
                hookCall(acr, new Class<?>[]{
                        Uri.class, String.class, String.class, Bundle.class});
                hookAcrMutations(acr);
            } else {
                log("WARN: ApplicationContentResolver unresolvable on any classloader");
            }
        } catch (Throwable t) {
            log("WARN: ApplicationContentResolver hook - " + t);
        }

        // 手势面：框架 PopupWindow 按实例类名识别面板（面板类在动态 dex 里，
        // 此刻按名加载必 ClassNotFound，必须走实例识别绕法）。
        try {
            Class<?> pwCls = Reflect.findClass(POPUP_WINDOW, cl);
            hookPopupLifecycle(pwCls);
            log("OK: hooked framework PopupWindow content/show");
        } catch (Throwable t) {
            log("WARN: PopupWindow hook - " + t);
        }
    }

    /**
     * 解析 {@code android.app.ApplicationContentResolver}（framework @hide 类）。
     *
     * <p>注意：app 默认 classloader 在部分 HyperOS 输入法进程里解析不到该隐藏类
     * （实测 ClassNotFoundException，原因待查），而 system classloader 可以
     * （{@link PanelColorHook} 解析 {@code ViewRootImplStub} 同款路径）。
     * 因此按「system → app 默认 → boot」顺序多重尝试。
     */
    private static Class<?> resolveAcr() {
        ClassLoader[] loaders = {
                ClassLoader.getSystemClassLoader(), sDefaultCl, null
        };
        for (ClassLoader cl : loaders) {
            try {
                Class<?> c = Class.forName(ACR, false, cl);
                log("ACR resolved from " + cl);
                return c;
            } catch (Throwable ignored) {
                // 换下一个 classloader
            }
        }
        return null;
    }

    // ---------------- Hook: query 重排 ----------------

    private static void hookQuery(Class<?> acr, Class<?>[] sig) {
        try {
            Method m = Reflect.findMethod(acr, "query", sig);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                try {
                    Uri uri = (Uri) chain.getArg(0);
                    if (uri == null) {
                        return r;
                    }
                    log("query: " + uri);
                    if (!uri.toString().contains(URI_LIST)) {
                        return r;
                    }
                    log("query hit: " + uri + " cursor="
                            + (r == null ? "null" : r.getClass().getName()));
                    if (!(r instanceof Cursor)) {
                        return r;
                    }
                    return reorder((Cursor) r);
                } catch (Throwable t) {
                    log("query reorder failed - " + t);
                    return r;
                }
            });
            log("OK: hooked query(" + sig.length + " args)");
        } catch (Throwable t) {
            log("WARN: query(" + sig.length + " args) hook - " + t);
        }
    }

    /**
     * 把置顶行挪到首位并返回新游标；无置顶（或置顶行已不在列表）时原样透传。
     *
     * <p>同时刷新 {@link #sRowTimes} / {@link #sTimeJson} 快照（手势取行身份用）。
     * 置顶条目不在结果里（被删除）时自动清置顶状态——此处严禁再触发刷新，避免递归。
     */
    private static Object reorder(Cursor src) {
        String[] cols = src.getColumnNames();
        int contentIdx = -1;
        for (int i = 0; i < cols.length; i++) {
            if (COL_CONTENT.equals(cols[i])) {
                contentIdx = i;
                break;
            }
        }
        if (contentIdx < 0) {
            // 不是剪贴板列表数据（列结构不符），原样放行
            return src;
        }
        List<Object[]> rows = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        Map<Long, String> timeJson = new HashMap<>();
        if (src.moveToFirst()) {
            do {
                Object[] row = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    try {
                        row[i] = src.getString(i);
                    } catch (Throwable t) {
                        // 非字符串列：读不出来就放弃整体重排，保持原游标语义
                        src.moveToPosition(-1);
                        return src;
                    }
                }
                rows.add(row);
                long time = extractTime((String) row[contentIdx]);
                times.add(time);
                if (time != 0L) {
                    timeJson.put(time, (String) row[contentIdx]);
                }
            } while (src.moveToNext());
        }
        // 原游标读取完毕，关闭防泄漏（调用方拿到的是新 MatrixCursor）
        try {
            src.close();
        } catch (Throwable ignored) {
            // 关闭失败不影响功能
        }
        sTimeJson = timeJson;

        long pin = sPinnedTime;
        int pinIdx = -1;
        if (pin != 0L) {
            for (int i = 0; i < times.size(); i++) {
                if (times.get(i) == pin) {
                    pinIdx = i;
                    break;
                }
            }
        }
        log("reorder: rows=" + rows.size() + " cols=" + java.util.Arrays.toString(cols)
                + " pin=" + pin + " pinIdx=" + pinIdx);
        if (pin != 0L && pinIdx < 0) {
            // 置顶条目已被删除：清状态（不刷新、不写回，静默降级）
            log("pinned row gone, clearing pin state");
            clearPinState(ctxOf());
        }
        List<Long> displayTimes;
        if (pinIdx > 0) {
            Object[] pinnedRow = rows.remove(pinIdx);
            rows.add(0, pinnedRow);
            displayTimes = new ArrayList<>(times.size());
            displayTimes.add(times.get(pinIdx));
            for (int i = 0; i < times.size(); i++) {
                if (i != pinIdx) {
                    displayTimes.add(times.get(i));
                }
            }
            log("pinned to top: time=" + pin + " from pos " + pinIdx);
        } else {
            displayTimes = times;
        }
        sRowTimes = displayTimes;

        MatrixCursor out = new MatrixCursor(cols);
        for (Object[] row : rows) {
            out.addRow(row);
        }
        return out;
    }

    // ---------------- Hook: call 清空保护 ----------------

    private static void hookCall(Class<?> acr, Class<?>[] sig) {
        try {
            Method m = Reflect.findMethod(acr, "call", sig);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                try {
                    Object methodArg = chain.getArg(1);
                    log("provider call: method=" + methodArg + " uri=" + chain.getArg(0));
                    if (!METHOD_CLEAR.equals(methodArg)) {
                        return r;
                    }
                    // 清空已执行：写回置顶条目并刷新面板
                    Uri uri = (Uri) chain.getArg(0);
                    restorePinnedAfterClear(uri);
                } catch (Throwable t) {
                    log("clear protect failed - " + t);
                }
                return r;
            });
            log("OK: hooked call(" + sig.length + " args)");
        } catch (Throwable t) {
            log("WARN: call(" + sig.length + " args) hook - " + t);
        }
    }

    /**
     * 诊断埋点：provider 的 update / delete / insert 全量打日志。
     *
     * <p>滑动删除走的可能不是 {@code call} 而是这三条路（云条目疑似软删除
     * {@code deleted=1}，dex 里见过 {@code sourceid IS NOT NULL AND deleted=0}
     * 这类 WHERE 片段）。要定位「删除后面板重开又复活」的真实链路，必须把
     * 所有写路径都打出来。{@code Reflect.findMethod} 沿继承链查找，ACR 自己
     * 没声明的方法会落到 {@link ContentResolver} 基类上，两种情形都能挂上。
     */
    private static void hookAcrMutations(Class<?> acr) {
        hookMutation(acr, "update",
                new Class<?>[]{Uri.class, ContentValues.class, String.class, String[].class});
        hookMutation(acr, "delete",
                new Class<?>[]{Uri.class, String.class, String[].class});
        hookMutation(acr, "insert",
                new Class<?>[]{Uri.class, ContentValues.class});
    }

    private static void hookMutation(Class<?> acr, String name, Class<?>[] sig) {
        try {
            Method m = Reflect.findMethod(acr, name, sig);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                try {
                    Object a1 = chain.getArg(1);
                    log("provider " + name + ": uri=" + chain.getArg(0)
                            + " arg=" + (a1 == null ? "null" : a1.toString()));
                } catch (Throwable ignored) {
                    // 诊断日志失败不影响主流程
                }
                return r;
            });
            log("OK: hooked ACR." + name);
        } catch (Throwable t) {
            log("WARN: " + name + " hook - " + t);
        }
    }

    /** 清空后用 saveClipboardCipherText 写回置顶条目，再刷新面板。 */
    private static void restorePinnedAfterClear(Uri uri) {
        long pin = sPinnedTime;
        String json = sPinnedJson;
        if (pin == 0L || json == null || json.isEmpty()) {
            return;
        }
        ContentResolver cr = resolverOf();
        if (cr == null || uri == null) {
            log("restore skipped: no resolver/uri");
            return;
        }
        try {
            Bundle b = new Bundle();
            b.putString("jsonArray", "[" + json + "]");
            cr.call(uri, METHOD_SAVE, null, b);
            log("pin restored after clear: time=" + pin);
        } catch (Throwable t) {
            log("restore after clear failed - " + t);
            return;
        }
        refreshPanel();
    }

    // ---------------- 面板刷新 ----------------

    /**
     * 重拉剪贴板数据并刷新面板：
     * {@code MiuiClipboardManager.getClipboardData(ctx)} →
     * {@code buildRecyclerViewDisplayList(list)} →
     * {@code adapter.setClipboardList(display)}，并同步 popup 的空视图/清空按钮状态。
     *
     * <p>拉数走的 query 会经过 {@link #reorder}，因此置顶条目自然回到首位。
     */
    private static void refreshPanel() {
        final Context ctx = ctxOf();
        final PopupWindow pw = popupOf();
        if (ctx == null || pw == null) {
            log("refresh skipped: no panel context");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClassLoader cl = clOf();
                if (cl == null) {
                    return;
                }
                Class<?> mgr = Reflect.findClass(CLIPBOARD_MANAGER, cl);
                Object data = Reflect.callStaticMethod(mgr, "getClipboardData", ctx);
                if (!(data instanceof List)) {
                    log("refresh: getClipboardData returned non-list");
                    return;
                }
                Object adapter = adapterOf();
                if (adapter == null) {
                    log("refresh: adapter not captured yet");
                    return;
                }
                Object display = Reflect.callStaticMethod(
                        mgr, "buildRecyclerViewDisplayList", data);
                Reflect.callMethod(adapter, "setClipboardList", display);
                // 同步 popup 内部状态，保证空视图 / 清空按钮显示正确
                try {
                    Reflect.setField(pw, "mCurrentImeClipboardList", data);
                } catch (Throwable ignored) {
                    // 字段不存在或类型不符时忽略
                }
                try {
                    Reflect.callMethod(pw, "maybeChangeEmptyViewAndClearButtonState", data);
                } catch (Throwable t2) {
                    try {
                        Reflect.callMethod(pw, "maybeChangeEmptyViewAndClearButtonState", display);
                    } catch (Throwable ignored) {
                        // 两种参数形态都不匹配时忽略
                    }
                }
                View content = pw.getContentView();
                Object rvObj = content == null ? null : findRecyclerView(content);
                if (rvObj instanceof View) {
                    // 不用 RecyclerView.scrollToPosition——MIUI 定制 RV 把它裁了
                    // （实测 NoSuchMethodException），改用 View 基类 scrollBy 滚回顶部。
                    View rv = (View) rvObj;
                    int sy = rv.getScrollY();
                    if (sy > 0) {
                        rv.scrollBy(0, -sy);
                        log("rv scrolled to top, was y=" + sy);
                    }
                }
                log("panel refreshed after pin change");
            } catch (Throwable t) {
                log("refresh failed - " + t);
            }
        });
    }

    // ---------------- Hook: PopupWindow 识别面板 + 懒挂手势 ----------------

    private static void hookPopupLifecycle(Class<?> pwCls) {
        hookPopupIfPanel(pwCls, "setContentView", new Class<?>[]{View.class});
        hookPopupIfPanel(pwCls, "showAsDropDown",
                new Class<?>[]{View.class, int.class, int.class});
        hookPopupIfPanel(pwCls, "showAsDropDown",
                new Class<?>[]{View.class, int.class, int.class, int.class});
        hookPopupIfPanel(pwCls, "showAtLocation",
                new Class<?>[]{View.class, int.class, int.class, int.class});
    }

    private static void hookPopupIfPanel(Class<?> pwCls, String name, Class<?>[] sig) {
        try {
            Method m = Reflect.findMethod(pwCls, name, sig);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                Object self = chain.getThisObject();
                if (isOurPanel(self)) {
                    if (sPanelCl == null) {
                        sPanelCl = self.getClass().getClassLoader();
                        log("panel classloader captured");
                    }
                    if (self instanceof PopupWindow) {
                        sPopupRef = new WeakReference<>((PopupWindow) self);
                        View content = ((PopupWindow) self).getContentView();
                        if (content != null) {
                            sImeCtxRef = new WeakReference<>(content.getContext());
                        }
                        // 用输入法进程自己的 context 读写 pin 状态文件
                        //（面板 context 实际是 phrase 包的 context，目录不对）
                        loadPinIfNeeded(ctxOf());
                    }
                    ensureGestureHooks();
                    log("panel detected via " + name);
                }
                return r;
            });
        } catch (Throwable t) {
            log("WARN: " + name + " hook - " + t);
        }
    }

    private static boolean isOurPanel(Object o) {
        return o != null && PHRASE_POPUP.equals(o.getClass().getName());
    }

    /**
     * 懒挂 {@code SwipeRevealLayout} 触摸事件与 {@code InputMethodClipboardAdapter.setClipboardList}。
     * 动态 dex 里按名解析，失败不置位，由 {@link #scheduleGestureRetry} 有界重试兜底。
     */
    private static boolean ensureGestureHooks() {
        if (sGestureHooked) {
            return true;
        }
        boolean any = false;
        Class<?> srl = resolve(SWIPE_REVEAL);
        if (srl != null) {
            log("SwipeRevealLayout resolved from " + srl.getClassLoader());
            any |= hookTouch(srl, "onInterceptTouchEvent", MotionEvent.class);
            any |= hookTouch(srl, "onTouchEvent", MotionEvent.class);
            any |= hookOnLayout(srl);
        } else {
            log("SwipeRevealLayout not resolvable yet (panelCl=" + sPanelCl
                    + ", defaultCl=" + sDefaultCl + ")");
        }
        Class<?> ad = resolve(CLIPBOARD_ADAPTER);
        if (ad != null) {
            log("adapter resolved from " + ad.getClassLoader());
            any |= hookSetClipboardList(ad);
            any |= hookOnBindViewHolder(ad);
        } else {
            log("adapter not resolvable yet (panelCl=" + sPanelCl
                    + ", defaultCl=" + sDefaultCl + ")");
        }
        sGestureHooked = any;
        if (!any) {
            scheduleGestureRetry();
        } else {
            log("gesture hooks ready");
        }
        return any;
    }

    /** 有界重试懒挂手势（面板 classloader 就绪前动态 dex 可能还没解析出来）。 */
    private static void scheduleGestureRetry() {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                if (sGestureHooked || ensureGestureHooks()) {
                    return;
                }
                if (++tries < 20) {
                    h.postDelayed(this, 500L);
                } else {
                    log("gesture hooks gave up after retries");
                }
            }
        }, 300L);
    }

    // ---------------- Hook: SwipeRevealLayout 右滑置顶门 ----------------

    /**
     * 右滑「置顶门」状态机（onInterceptTouchEvent / onTouchEvent 共用），
     * 交互与系统删除门对称：轻划露出按钮 → 点按钮才置顶。
     *
     * <p>三态：
     * <ul>
     *   <li><b>MODE_NONE（全关）</b>：左滑 = 系统删除（proceed 不劫持）；
     *       右滑超阈值 = 开置顶门（劫持，主视图跟手右移）；系统删除门开着
     *       （主视图 translationX &lt; -1f）时右滑一律 proceed 交还系统
     *       ——「右滑取消删除」是系统原生行为，绝不拦。</li>
     *   <li><b>MODE_OPENING（正在划开置顶门）</b>：主视图 translationX 跟随手指
     *       （0..门宽），松手超过门宽一半则保持打开。</li>
     *   <li><b>MODE_PIN_OPEN（置顶门已开）</b>：按钮可见；水平拖动（任一方向）
     *       一律劫持——左滑跟手往关的方向拖，右滑钳位在门宽（无操作）；
     *       松手或点击条目其他区域关门；<b>点击置顶按钮</b>才触发置顶/取消置顶。
     *       右滑也必须劫持的原因见 {@code ACTION_MOVE} 分支注释：系统 DragHelper
     *       在 DOWN 即被武装且无逐事件 slop，放行右滑会激活系统删除门。</li>
     * </ul>
     *
     * <p><b>为什么不能用 {@code isOpened()} 判断系统删除门</b>：MIUI 版
     * {@code SwipeRevealLayout.isOpened()} 实测恒返回 false，导致右滑被误劫持、
     * 系统「右滑取消删除」失效（code 84 实机 bug）。改用主视图 translationX
     * 符号做物理判断：&lt; -1f = 系统删除门开着，&gt; +1f = 置顶门开着。
     *
     * <p><b>方向判据与防抢手势（镜像原生）</b>：反编译 {@code com.miui.inputmethod
     * .SwipeRevealLayout}（phrase 5.7.4）确认，条目的 ViewDragHelper 垂直拖动
     * 范围为 0、只有水平范围，按 {@code checkTouchSlop} 的 horizontal-only 分支，
     * 捕获条件仅有 {@code |dx| > 触摸阈值}，<b>dy 不参与判定</b>；且其手势监听器
     * 在首次滚动超阈值时即 {@code getParent().requestDisallowInterceptTouchEvent(true)}
     * 禁止父级 RecyclerView 抢走手势。故置顶门同样：开门/回拖只比水平位移量，
     * 一旦接管拖动立即 disallow 父级（UP/CANCEL 及下一个 DOWN 时解除）——
     * 否则稍有垂直偏移就被 proceed 掉或中途被抢，门永远拉不出来。
     */
    private static boolean hookTouch(Class<?> cls, String name, Class<?>... sig) {
        try {
            Method m = Reflect.findMethod(cls, name, sig);
            XposedInit.hook(m, chain -> {
                Object self = chain.getThisObject();
                if (self == null) {
                    return chain.proceed();
                }
                MotionEvent ev = (MotionEvent) chain.getArg(0);
                if (ev == null) {
                    return chain.proceed();
                }
                View sv = (View) self;
                GestureState st = stateOf(self);
                View main = mainViewOf(self);
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        st.downX = ev.getX();
                        st.downY = ev.getY();
                        st.declined = false;
                        // 新手势开始：解除上一手势可能残留的「禁止父级劫持」标记。
                        // 父级 RecyclerView 的 disallow flag 若不清零，本手势期间
                        // 列表抢不走手势、无法正常滚动
                        if (sv.getParent() != null) {
                            sv.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        // 新手势开始：按「视图实际态」纠偏，不信任 mode 标志——
                        // 取消置顶后 mode 已回 NONE 但 150ms 关门动画仍在途、或
                        // 上一手势的 UP 被我们消费导致系统删除门冻结开着，都可能
                        // 让 mode 与视图脱钩。判据（任一命中即硬复位）：
                        //   ① mode 停在中间态 OPENING（正常结束的手势不会停留在此）；
                        //   ② 置顶门视觉开着（translationX>1 / 按钮可见）但 mode≠PIN_OPEN；
                        //   ③ mode=PIN_OPEN 但系统删除门实际开着（两门同显）。
                        // 全干净时不动：系统删除门正常开着（原生删除流）时不打扰。
                        View btn0 = sPinBtns.get(self);
                        boolean stale = st.mode == MODE_OPENING
                                || (st.mode == MODE_PIN_OPEN && systemDoorOpen(self, main))
                                || (st.mode != MODE_PIN_OPEN && main != null
                                        && main.getTranslationX() > 1f)
                                || (st.mode != MODE_PIN_OPEN && btn0 != null
                                        && btn0.getVisibility() == View.VISIBLE);
                        if (stale) {
                            // 先中和系统侧（删除门冻结开着则 close(false) 关门），
                            // 再硬复位置顶侧（取消在途动画，避免 end-action 误触发）
                            neutralizeSystemDoor(self, main);
                            clearPinDoorOpen(self);
                            st.mode = MODE_NONE;
                            st.declined = false;
                            if (main != null) {
                                main.animate().cancel();
                                main.setTranslationX(0f);
                            }
                            showPinBtn(self, false);
                            log("stale pin door cleared at down");
                        }
                        return chain.proceed();
                    case MotionEvent.ACTION_MOVE: {
                        if (st.declined) {
                            return chain.proceed();
                        }
                        float dx = ev.getX() - st.downX;
                        float slop = slopOf(sv);
                        float bw = pinDoorWidth(sv);
                        if (st.mode == MODE_PIN_OPEN) {
                            // 置顶门已开：水平拖动（任一方向）一律劫持——
                            // 左滑 = 回拖往关的方向；右滑 = 钳位在门宽（无操作）。
                            // 为什么右滑也不能 proceed：反编译实证系统的 M.e 在
                            // 每次 DOWN 就被 tryCaptureView 的副作用武装成 DRAGGING
                            //（拖动分支无逐事件 slop），proceed 掉的事件会实时拖动
                            // 主视图 left；此前只有左滑劫持，「先右划再左划」时右段
                            // 激活系统拖动、回程中低于 -slop 的左滑事件立刻把删除门
                            // 拖开，松手冻结成「两门同显」。垂直拖动（|dx|<slop）
                            // 仍 proceed，保证列表滚动不被拦。
                            //
                            // 左滑零容差（症状1 收尾）：|dx|<slop 的微左滑此前
                            // 放行 proceed，系统门被拖开 2~20px——手指按着就露
                            // 红边/按钮边缘（瞬态残留，UP 兜底只在松手后修复）。
                            // 故左滑只要水平占优（|dx|>=|dy|）且超过半像素立即
                            // 劫持，不等触摸阈值；右滑被系统 clamp 在关门位
                            // （left 不越过 close.left）不出界，维持 slop 判据，
                            // 保证 PIN_OPEN 时垂直滚动列表仍可放行。
                            float dy = ev.getY() - st.downY;
                            boolean horiz = Math.abs(dx) >= Math.abs(dy);
                            if (dx < -0.5f && horiz) {
                                return dragPinDoor(sv, main, st, bw, dx);
                            }
                            if (Math.abs(dx) >= slop) {
                                return dragPinDoor(sv, main, st, bw, dx);
                            }
                            return chain.proceed();
                        }
                        if (st.mode == MODE_OPENING) {
                            return dragPinDoor(sv, main, st, bw, dx);
                        }
                        // 全关态：先判方向。
                        // 判据镜像原生 ViewDragHelper：条目只做水平拖动（垂直拖动
                        // 范围为 0），按 checkTouchSlop 的 horizontal-only 分支，
                        // 捕获条件只有 |dx| > 触摸阈值，dy 不参与判定——原生删除门
                        // 允许手指上下偏移照样拉开。若这里苛求「水平必须占优」，
                        // 稍有垂直偏移就 proceed 掉，父级 RecyclerView 随即抢走
                        // 整段手势，置顶门永远拉不出来
                        if (Math.abs(dx) < slop) {
                            // 未超触摸阈值：交给系统 / RecyclerView
                            return chain.proceed();
                        }
                        if (dx < 0f) {
                            // 左滑 = 系统删除，绝不劫持
                            st.declined = true;
                            return chain.proceed();
                        }
                        if (systemDoorOpen(self, main) || main != null && main.getTranslationX() < -1f) {
                            // 系统删除门开着（主视图被 ViewDragHelper 推到左侧：left 低于关门位）。
                            // 此时右滑是系统原生「取消删除」，必须原样交还系统——
                            // 只查 translationX 会漏判（系统 drag 改的是 left，不是 translationX），
                            // 漏判的后果：右滑被误劫持成置顶门，系统关门失效（code 86 实机 bug）。
                            // 判据以 mRectMainClose.left 为基准而非 0：真机关门位 left=47，
                            // 微滑漂移只把门拖开 2~21px 时 left 仍是正的，旧判据 left<-1 漏判。
                            st.declined = true;
                            return chain.proceed();
                        }
                        // 右滑 = 开置顶门
                        st.mode = MODE_OPENING;
                        log("pin door opening: dx=" + dx);
                        // 跟手阶段即显示按钮：主视图移多少、按钮露多少，
                        // 与系统删除门露出删除按钮的机制一致
                        showPinBtn(self, true);
                        return dragPinDoor(sv, main, st, bw, dx);
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        // 手势结束：恢复父级的劫持权，避免列表后续无法滚动
                        if (sv.getParent() != null) {
                            sv.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        int mode = st.mode;
                        boolean up = ev.getActionMasked() == MotionEvent.ACTION_UP;
                        float dx = ev.getX() - st.downX;
                        st.declined = false;
                        if (mode == MODE_NONE) {
                            return chain.proceed();
                        }
                        // 我们消费了本手势（OPENING/PIN_OPEN）：UP 不会送达系统
                        // DragHelper（没有 onViewReleased 兜底），若期间 proceed 过的
                        // 事件已把系统删除门拖开，此刻已冻结——先 close(false) 关门，
                        // 否则残留红边、甚至与置顶门同显（两门同显 BUG）
                        neutralizeSystemDoor(self, main);
                        float bw = pinDoorWidth(sv);
                        if (mode == MODE_OPENING) {
                            // 只要划过触摸阈值就算「有意划出」：轻划即留门，
                            // 松手后主视图补一个动画滑到全开，按钮完整露出
                            //（阈值用门宽一半会导致轻划被弹回，实测用户只滑 30~40px）
                            if (up && dx >= slopOf(sv)) {
                                st.mode = MODE_PIN_OPEN;
                                if (main != null) {
                                    main.animate().translationX(bw).setDuration(150L).start();
                                }
                                showPinBtn(self, true);
                                // 按钮随主视图一起滑到全开位置（translationX = 0）
                                View ob = sPinBtns.get(self);
                                if (ob != null) {
                                    ob.animate().cancel();
                                    ob.animate().translationX(0f).setDuration(150L).start();
                                }
                                log("pin door opened (dx=" + dx + ", bw=" + bw + ")");
                            } else {
                                // 划出不够：关门，按钮跟着条目滑回门外被裁掉
                                st.mode = MODE_NONE;
                                closePinDoor(self, main, bw);
                            }
                            return Boolean.TRUE;
                        }
                        // MODE_PIN_OPEN
                        if (up && isInPinBtn(sv, ev)) {
                            // 点中置顶按钮 → 置顶 / 取消置顶，随后关门
                            log("pin button clicked");
                            togglePinAt(self);
                            st.mode = MODE_NONE;
                            closePinDoor(self, main, bw);
                            return Boolean.TRUE;
                        }
                        float cur = main != null ? main.getTranslationX() : 0f;
                        if (up && cur >= bw * 0.5f) {
                            // 仍停在开门位置（未点按钮、未往回拖）：保持打开。
                            // 补一个动画滑到全开，按钮同步回到全开位置
                            if (main != null) {
                                main.animate().translationX(bw).setDuration(150L).start();
                            }
                            View kb = sPinBtns.get(self);
                            if (kb != null) {
                                kb.animate().cancel();
                                kb.animate().translationX(0f).setDuration(150L).start();
                            }
                            return Boolean.TRUE;
                        }
                        // 回拖过半：关门，按钮跟着条目滑回门外被裁掉
                        st.mode = MODE_NONE;
                        closePinDoor(self, main, bw);
                        return Boolean.TRUE;
                    }
                    default:
                        return chain.proceed();
                }
            });
            return true;
        } catch (Throwable t) {
            log("WARN: " + name + " hook - " + t);
            return false;
        }
    }

    /**
     * 置顶门拖动中：主视图 translationX 跟手（开门 0..bw；已开门则 bw+dx）。
     */
    private static Object dragPinDoor(
            View sv, View main, GestureState st, float bw, float dx) {
        // 单开互斥：用户开始拖动本条，先关掉其他开着置顶门的条目
        // （原生删除门同样只开一条——开第二条时第一条自动收起）
        closeOtherPinDoors(sv);
        markPinDoorOpen(sv);
        // 接管前先中和系统删除门：反编译实证 M.e 在每次 DOWN 即被
        // tryCaptureView 的副作用武装成 DRAGGING（无逐事件 slop），开门途中
        // proceed 过的亚阈值左滑事件会把删除门拖开；先 close(false) 关门
        // 再走 translationX，保证任一时刻只有一扇门在位
        neutralizeSystemDoor(sv, main);
        // 接管拖动的同时禁止父级（RecyclerView）劫持本手势：跟手过程中 dy
        // 一旦超过列表滑动阈值，父级会把整段手势抢走、门卡在半路——原生
        // SwipeRevealLayout 靠手势监听器 onScroll → requestDisallowInterceptTouchEvent
        // 达到同样效果，此处镜像。flag 在 UP/CANCEL 与下一个 DOWN 时解除
        if (sv.getParent() != null) {
            sv.getParent().requestDisallowInterceptTouchEvent(true);
        }
        float tx = st.mode == MODE_PIN_OPEN ? bw + dx : dx;
        tx = Math.max(0f, Math.min(bw, tx));
        if (main != null) {
            main.setTranslationX(tx);
        }
        // 按钮与主视图同向等量位移（偏移 -bw）：门全关时被父容器左边界裁掉，
        // 门全开时正好落在露出区——与原生删除按钮「被条目推着走、越界被裁」同构
        syncPinBtn(sv, bw, tx);
        return Boolean.TRUE;
    }

    /**
     * hook {@code SwipeRevealLayout.onLayout}，在系统摆完 child0/child1 后
     * 手动把置顶按钮摆到条目左侧垂直居中。
     *
     * <p>MIUI 的 onLayout 不认识我们 addView 进去的按钮（不摆它），且 RV 复用
     * 条目时会反复触发 onLayout，正好每次都为按钮重新定位。
     */
    private static boolean hookOnLayout(Class<?> cls) {
        try {
            Method m = Reflect.findMethod(cls, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    View btn = sPinBtns.get(self);
                    if (btn != null && btn.getParent() == self) {
                        layoutPinBtn(self, btn);
                    }
                } catch (Throwable t) {
                    log("pin btn layout failed - " + t);
                }
                return r;
            });
            log("OK: hooked SwipeRevealLayout.onLayout");
            return true;
        } catch (Throwable t) {
            log("WARN: onLayout hook - " + t);
            return false;
        }
    }

    /** 置顶 / 取消置顶当前手势所在条目，然后刷新面板。 */
    private static void togglePinAt(Object swipe) {
        long time = timeOfRow(swipe);
        if (time == 0L) {
            log("toggle pin: row time unresolved (swipe="
                    + swipe.getClass().getName() + ")");
            return;
        }
        Context ctx = ctxOf();
        if (sPinnedTime == time) {
            // 已置顶 → 取消
            clearPinState(ctx);
            log("pin cancelled: time=" + time);
        } else {
            String json = sTimeJson.get(time);
            if (json == null || json.isEmpty()) {
                log("toggle pin: no json snapshot for time=" + time
                        + " (snapshot keys=" + sTimeJson.size() + ")");
                return;
            }
            sPinnedTime = time;
            sPinnedJson = json;
            persistPin(ctx, time, json);
            log("pinned: time=" + time);
        }
        refreshPanel();
    }

    // ---------------- Hook: adapter.setClipboardList 捕获列表 ----------------

    private static boolean hookSetClipboardList(Class<?> ad) {
        try {
            for (Method m : ad.getMethods()) {
                if (!"setClipboardList".equals(m.getName())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || !List.class.isAssignableFrom(ps[0])) {
                    continue;
                }
                XposedInit.hook(m, chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self != null) {
                        // adapter 实例弱引用在下方统一维护（反射字段成本高，直接存）
                        sAdapterRef = new WeakReference<>(self);
                    }
                    Object list = chain.getArg(0);
                    if (list instanceof List) {
                        sDisplayListRef = new WeakReference<>((List<?>) list);
                        List<?> l = (List<?>) list;
                        log("setClipboardList captured: " + l.size() + " rows, elem="
                                + (l.isEmpty() ? "?"
                                : l.get(0).getClass().getName()));
                    } else {
                        log("setClipboardList arg not a List: "
                                + (list == null ? "null" : list.getClass().getName()));
                    }
                    return r;
                });
                log("OK: hooked " + ad.getName() + ".setClipboardList");
                return true;
            }
            log("WARN: setClipboardList not found on " + ad.getName());
            return false;
        } catch (Throwable t) {
            log("WARN: setClipboardList hook - " + t);
            return false;
        }
    }

    // ---------------- Hook: adapter.onBindViewHolder 建立 View→position 映射 ----------------

    /**
     * hook {@code onBindViewHolder(ViewHolder, int)}，把 {@code ViewHolder.itemView}
     * 与适配器位置的对应关系记进 {@link #sBindMap}，供手势时反查条目位置。
     *
     * <p>面板的 RecyclerView 是 MIUI 定制版，位置查询 API 全被 dex 优化裁掉
     * （实测 {@code getChildAdapterPosition} 等均 NoSuchMethodException），
     * 只能走这条不依赖 RV API 的路。
     */
    private static boolean hookOnBindViewHolder(Class<?> ad) {
        try {
            for (Method m : ad.getDeclaredMethods()) {
                if (!"onBindViewHolder".equals(m.getName())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                // 只要 (ViewHolder, int) 两参版本；(vh, pos, payloads) 三参版不接
                if (ps.length != 2) {
                    continue;
                }
                if (!int.class.equals(ps[1]) && !Integer.class.equals(ps[1])) {
                    continue;
                }
                final Method target = m;
                XposedInit.hook(target, chain -> {
                    Object r = chain.proceed();
                    try {
                        Object vh = chain.getArg(0);
                        Object posArg = chain.getArg(1);
                        if (vh == null || !(posArg instanceof Integer)) {
                            return r;
                        }
                        Object itemView = Reflect.getField(vh, "itemView");
                        if (itemView instanceof View) {
                            synchronized (sBindMap) {
                                sBindMap.put((View) itemView, (Integer) posArg);
                            }
                            // 条目复用时清掉可能残留的置顶门状态（开门态被滑出屏幕
                            // 或列表刷新后，主视图位移/按钮必须归位，否则显示错乱）
                            resetPinDoorIfNeeded((View) itemView);
                            // 诊断：前 3 次 bind 打一次条目视图树（确认布局 id 映射）
                            dumpTreeOnce((View) itemView);
                            // 置顶条目追加「（已置顶）」提醒
                            markPinnedSource((View) itemView, rowTimeAt((Integer) posArg));
                        }
                    } catch (Throwable t) {
                        log("bind map record failed - " + t);
                    }
                    return r;
                });
                log("OK: hooked " + ad.getName() + ".onBindViewHolder("
                        + ps[0].getSimpleName() + ", int)");
                return true;
            }
            log("WARN: onBindViewHolder not found on " + ad.getName());
            return false;
        } catch (Throwable t) {
            log("WARN: onBindViewHolder hook - " + t);
            return false;
        }
    }

    // ---------------- 行身份解析 ----------------

    /**
     * 取手势所在行的条目 time。优先走 adapter 当前列表（按适配器位置），
     * 失败退回最近一次查询快照（同样按位置）。两者都失败返回 0（放弃本次置顶）。
     *
     * <p>位置来源是 {@link #sBindMap}（hook {@code onBindViewHolder} 建立的
     * itemView→position 映射）：从手势 view 向上遍历祖先，命中映射即得适配器位置。
     * 不依赖 RecyclerView 的位置 API——面板的 MIUI 定制 RV 把这些方法全裁了。
     */
    private static long timeOfRow(Object swipe) {
        try {
            View sv = (View) swipe;
            Integer pos = null;
            View cur = sv;
            for (int i = 0; i < 6 && cur != null; i++) {
                synchronized (sBindMap) {
                    pos = sBindMap.get(cur);
                }
                if (pos != null) {
                    break;
                }
                ViewParent pp = cur.getParent();
                cur = pp instanceof View ? (View) pp : null;
            }
            if (pos == null) {
                log("timeOfRow: no bind map entry for swipe view, ancestors scanned, "
                        + "mapSize=" + sBindMap.size());
                return 0L;
            }
            List<?> list = sDisplayListRef.get();
            log("timeOfRow: pos=" + pos + " list="
                    + (list == null ? "null"
                    : (list.size() + "x" + (list.isEmpty() ? "?"
                    : list.get(0).getClass().getSimpleName())))
                    + " snapshot=" + sRowTimes.size());
            if (list != null && pos >= 0 && pos < list.size()) {
                long t = timeOfItem(list.get(pos));
                if (t != 0L) {
                    return t;
                }
            }
            List<Long> times = sRowTimes;
            if (pos >= 0 && pos < times.size()) {
                return times.get(pos);
            }
            log("timeOfRow: pos=" + pos + " beyond snapshot size=" + times.size());
            return 0L;
        } catch (Throwable t) {
            log("timeOfRow failed - " + t);
            return 0L;
        }
    }

    /** 反射取列表元素的 time 字段 / getTime()；display item 包一层 model 也试；最后兜底 toString 提取。 */
    private static long timeOfItem(Object item) {
        if (item == null) {
            return 0L;
        }
        String cls = item.getClass().getName();
        try {
            return (Long) Reflect.getField(item, "time");
        } catch (Throwable ignored) {
            // 字段不存在，继续试方法
        }
        try {
            return (Long) Reflect.callMethod(item, "getTime");
        } catch (Throwable ignored) {
            // 方法不存在，继续试包装字段
        }
        for (String f : new String[]{"model", "mModel", "clipboardContentModel", "mClipboardContentModel", "data", "mData"}) {
            try {
                Object inner = Reflect.getField(item, f);
                if (inner == null) {
                    continue;
                }
                try {
                    return (Long) Reflect.getField(inner, "time");
                } catch (Throwable ignored2) {
                    // 内层也没有 time 字段
                }
                try {
                    return (Long) Reflect.callMethod(inner, "getTime");
                } catch (Throwable ignored2) {
                    // 内层也没有 getTime
                }
                try {
                    return extractTime(inner.toString());
                } catch (Throwable ignored2) {
                    // 内层 toString 也提取不到
                }
            } catch (Throwable ignored) {
                // 字段不存在，试下一个
            }
        }
        // 最终兜底：item 自身 toString 里可能带 time=...（调试信息常见）
        try {
            long t = extractTime(item.toString());
            if (t != 0L) {
                log("timeOfItem: time from toString fallback");
                return t;
            }
        } catch (Throwable ignored) {
            // toString 提取失败
        }
        String s;
        try {
            s = item.toString();
        } catch (Throwable t) {
            s = "<toString failed>";
        }
        log("timeOfItem: unresolved for " + cls + " -> "
                + (s.length() > 240 ? s.substring(0, 240) + "…" : s));
        return 0L;
    }

    // ---------------- 置顶条目标注（「（已置顶）」提醒） ----------------

    /**
     * 给置顶条目标注「（已置顶）」提醒——统一放在<b>正文上方</b>：
     * <ul>
     *   <li>云端条目：追加在来源标注（{@code clipboard_across_devices_tip_text}，
     *       「来自我的电脑」）后，本就在正文上方；</li>
     *   <li>本地条目：没有来源标注（容器 {@code clipboard_across_devices_tip} 被
     *       置 GONE）。把它显出来、写上「（已置顶）」并隐藏云图标，借同一个位置把
     *       提醒放到正文上方——长文本把正文 ellipsize 掉也不会吞掉提醒。</li>
     * </ul>
     *
     * <p>每次绑定先剥掉复用残留（View 回收带着旧文字），非置顶行只剥不贴——
     * 取消置顶后列表刷新复用它，标记不会错留。点击条目插入的是模型数据、
     * 不读 TextView 文字，标记不会污染粘贴内容。
     *
     * <p>位置 id 按名从 phrase 资源包解析（面板布局来自 {@code com.miui.phrase}，
     * 输入法进程自己的资源里查不到）；解析失败时退回「正文后追加」兜底。
     */
    private static void markPinnedSource(View itemView, long rowTime) {
        try {
            boolean pinned = rowTime != 0L && rowTime == sPinnedTime;
            View tipBox = findViewByPhraseId(itemView, ID_TIP_BOX);
            View tipIcon = findViewByPhraseId(itemView, ID_TIP_ICON);
            TextView tip = findText(itemView, ID_TIP_TEXT);
            TextView top = findText(itemView, ID_TEXT_TOP);
            TextView bottom = findText(itemView, ID_TEXT_BOTTOM);
            // 先剥掉复用残留（View 回收带着旧文字）
            stripMark(tip);
            stripMark(top);
            stripMark(bottom);
            // MIUI 刚 bind 完：来源容器可见即云条目（本地条目被它置 GONE）
            boolean cloud = tip != null && effectivelyVisible(tip);
            if (cloud && tipIcon != null) {
                // 云条目图标恒显示——恢复可能被上一次「本地置顶」改成的 GONE
                tipIcon.setVisibility(View.VISIBLE);
            }
            // 首次见到云端来源标注：记下它的字号与颜色（此刻 tip 是系统原文，未被我们改过）。
            // 本地置顶复用同一容器时套用这套「小字 + 灰色」，标记行高才与云端条目一致。
            if (cloud && tip != null && sSrcLabelSizePx <= 0f) {
                float sz = tip.getTextSize();
                if (sz > 0f) {
                    sSrcLabelSizePx = sz;
                    sSrcLabelColor = tip.getCurrentTextColor();
                    log("captured source-label style: sizePx=" + sz
                            + " color=" + Integer.toHexString(sSrcLabelColor));
                }
            }
            // 首次见到云端正文：记下它的上下内边距（本地条目正文自带 22px 呼吸内边距，
            // 云端为 0；本地置顶借来源标注位加行后靠对齐这个值才能与云端等高）。
            if (cloud && !sCloudContentPadCaptured) {
                TextView cv = hasText(top) ? top : (hasText(bottom) ? bottom : null);
                if (cv != null) {
                    sCloudContentPadT = cv.getPaddingTop();
                    sCloudContentPadB = cv.getPaddingBottom();
                    sCloudContentPadCaptured = true;
                    log("captured cloud content padding: t=" + sCloudContentPadT
                            + " b=" + sCloudContentPadB);
                }
            }
            // 复位上一轮「我们」改过的正文内边距：ViewHolder 复用或状态翻转后，若不还原，
            // 残留的「已归零内边距」会让复用此 itemView 的非置顶本地条目少了呼吸空间。
            // 只在本地条目上复位——云端条目的内边距是系统自管的（实测为 0），绝不触碰。
            boolean restored = !cloud && restoreContentPadding(itemView, top, bottom);
            if (PIN_DIAG) {
                log("pin-diag: pinned=" + pinned + " cloud=" + cloud
                        + " tipSizePx=" + (tip != null ? tip.getTextSize() : -1f)
                        + " tipColor=" + (tip != null ? Integer.toHexString(tip.getCurrentTextColor()) : "-")
                        + " tipVis=" + visibilityOf(tip)
                        + " tipBoxVis=" + visibilityOf(tipBox)
                        + " iconVis=" + visibilityOf(tipIcon)
                        + " itemH=" + itemView.getHeight());
                final View fItem = itemView;
                final View fTipBox = tipBox;
                final TextView fTip = tip, fTop = top, fBot = bottom;
                final boolean fCloud = cloud;
                final boolean fPinned = pinned;
                itemView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            log("pin-diag(G): pinned=" + fPinned + " cloud=" + fCloud
                                    + viewDiag("item", fItem)
                                    + viewDiag("tipBox", fTipBox)
                                    + tvDiag("tip", fTip)
                                    + tvDiag("top", fTop)
                                    + tvDiag("bot", fBot)
                                    + " tipBoxParent=" + (fTipBox != null
                                        && fTipBox.getParent() instanceof View
                                        ? ((View) fTipBox.getParent()).getClass().getSimpleName()
                                        : "-")
                                    + " topParent=" + (fTop != null
                                        && fTop.getParent() instanceof View
                                        ? ((View) fTop.getParent()).getClass().getSimpleName()
                                        : "-"));
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            if (!pinned) {
                return;
            }
            if (cloud) {
                // 云条目：提醒追加在来源标注（「来自我的电脑」）后——本就在正文上方；
                // 顺手把字号/颜色锁回云端原款（可能被上一轮本地标记改过）。
                if (sSrcLabelSizePx > 0f) {
                    tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sSrcLabelSizePx);
                    tip.setTextColor(sSrcLabelColor);
                }
                appendMark(tip);
                log("pinned source marked: tip time=" + rowTime + (restored ? " restored" : ""));
            } else if (tipBox != null && tip != null) {
                // 本地条目：借来源标注容器把提醒放到正文上方，隐藏云图标；
                // 并套用云端来源标注的字号/颜色 —— 否则标记会以 tip 默认大字渲染，整行被撑高。
                if (tipIcon != null) {
                    tipIcon.setVisibility(View.GONE);
                }
                tip.setText(PIN_MARK);
                if (sSrcLabelSizePx > 0f) {
                    tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sSrcLabelSizePx);
                    tip.setTextColor(sSrcLabelColor);
                }
                tipBox.setVisibility(View.VISIBLE);
                // 对齐正文内边距：本地正文自带上下内边距（真机实测 padT=padB=22px），
                // 云端为 0——本地置顶借来源标注位加了「（已置顶）」行后，这段内边距会把
                // 整条撑高（实测本地 165px vs 云端 152px）。把本地正文内边距对齐云端
                // （通常归零）后，本地置顶条目即与云端条目等高。改前原值记账，供复用/
                // 翻转时复位。
                int[] saved = new int[]{
                        top != null ? top.getPaddingTop() : 0,
                        top != null ? top.getPaddingBottom() : 0,
                        bottom != null ? bottom.getPaddingTop() : 0,
                        bottom != null ? bottom.getPaddingBottom() : 0};
                setContentPadding(top, sCloudContentPadT, sCloudContentPadB);
                setContentPadding(bottom, sCloudContentPadT, sCloudContentPadB);
                synchronized (sPadSaved) {
                    sPadSaved.put(itemView, saved);
                }
                log("pinned source marked: header time=" + rowTime
                        + " contentPad=" + sCloudContentPadT + "/" + sCloudContentPadB
                        + " markSizePx=" + (sSrcLabelSizePx > 0f ? sSrcLabelSizePx : -1f));
            } else if (top != null && effectivelyVisible(top)) {
                // 兜底（id 解析失败）：退回正文后追加
                appendMark(top);
                log("pinned source marked: top time=" + rowTime);
            } else if (bottom != null && effectivelyVisible(bottom)) {
                appendMark(bottom);
                log("pinned source marked: bottom time=" + rowTime);
            }
        } catch (Throwable t) {
            log("mark pinned source failed - " + t);
        }
    }

    /**
     * 有效可见性：视图自身与整条父链都不能有 GONE/INVISIBLE。
     *
     * <p>输入法面板的来源标注容器 {@code clipboard_across_devices_tip} 对本地条目
     * 整体设 GONE，但其子 TextView（{@code clipboard_across_devices_tip_text}）
     * 自身仍报 {@code VISIBLE}——只判自身可见性会把「（已置顶）」打到看不见的
     * 视图上（code 90 bind tree 实锤：本地条目标记标到了 GONE 容器里的子视图），
     * 必须沿父链一起判。
     */
    private static boolean effectivelyVisible(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (((View) p).getVisibility() != View.VISIBLE) {
                return false;
            }
            p = p.getParent();
        }
        return true;
    }

    /** 诊断用：视图几何（高度/内边距/layout_height/类名）。 */
    private static String viewDiag(String name, View v) {
        if (v == null) {
            return " " + name + "=null";
        }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        String lph = lp == null ? "?"
                : (lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? "MP"
                : (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT ? "WC"
                : String.valueOf(lp.height)));
        return " " + name + "[h=" + v.getHeight()
                + ",padT=" + v.getPaddingTop() + ",padB=" + v.getPaddingBottom()
                + ",lph=" + lph + ",cls=" + v.getClass().getSimpleName() + "]";
    }

    /** 诊断用：TextView 几何 + 文本 + 字号 + 行数（定位行高被撑高的真因）。 */
    private static String tvDiag(String name, TextView v) {
        if (v == null) {
            return " " + name + "=null";
        }
        CharSequence cs = v.getText();
        String t = cs == null ? "" : cs.toString();
        if (t.length() > 20) {
            t = t.substring(0, 20) + "…";
        }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        String lph = lp == null ? "?"
                : (lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? "MP"
                : (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT ? "WC"
                : String.valueOf(lp.height)));
        return " " + name + "[t=" + v.getTop() + ",h=" + v.getHeight()
                + ",mt=" + marginTopOf(v) + ",sz=" + v.getTextSize()
                + ",ln=" + v.getLineCount()
                + ",padT=" + v.getPaddingTop() + ",padB=" + v.getPaddingBottom()
                + ",lph=" + lph + ",txt=\"" + t + "\"]";
    }

    /** 诊断用：可见性枚举转可读字符串。 */
    private static String visibilityOf(View v) {
        if (v == null) {
            return "null";
        }
        switch (v.getVisibility()) {
            case View.VISIBLE:
                return "VISIBLE";
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "?";
        }
    }

    /** 按 phrase 包 id 名找 TextView；解析不到返回 null（调用方走兜底）。 */
    private static TextView findText(View root, String idName) {
        int id = phraseIdOf(idName);
        if (id == 0) {
            return null;
        }
        View v = root.findViewById(id);
        return v instanceof TextView ? (TextView) v : null;
    }

    /** 按 phrase 包 id 名找 View；解析不到返回 null。 */
    private static View findViewByPhraseId(View root, String idName) {
        int id = phraseIdOf(idName);
        return id == 0 ? null : root.findViewById(id);
    }

    /** 剥掉 TextView 末尾的置顶提醒（复用残留清理）。 */
    private static void stripMark(TextView tv) {
        if (tv == null) {
            return;
        }
        CharSequence cs = tv.getText();
        if (cs == null) {
            return;
        }
        String s = cs.toString();
        if (s.endsWith(PIN_MARK)) {
            tv.setText(s.substring(0, s.length() - PIN_MARK.length()));
        }
    }

    /** 追加置顶提醒（已有时不重复追加）。 */
    private static void appendMark(TextView tv) {
        CharSequence cs = tv.getText();
        String s = cs == null ? "" : cs.toString();
        if (!s.endsWith(PIN_MARK)) {
            tv.setText(s + PIN_MARK);
        }
    }

    /** 读正文 View 的 {@code topMargin}（无 MarginLayoutParams 时按 0）。 */
    private static int marginTopOf(TextView v) {
        if (v == null) {
            return 0;
        }
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            return ((ViewGroup.MarginLayoutParams) p).topMargin;
        }
        return 0;
    }

    /** 写正文 TextView 的上下内边距（只改上下，左右保持；变了才 setPadding）。 */
    private static void setContentPadding(TextView v, int top, int bottom) {
        if (v == null || top < 0 || bottom < 0) {
            return;
        }
        if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
        }
    }

    /** TextView 是否有非空文本（找到云端「正文」那一格用）。 */
    private static boolean hasText(TextView v) {
        if (v == null) {
            return false;
        }
        CharSequence cs = v.getText();
        return cs != null && cs.length() > 0;
    }

    /**
     * 复位上一条目遗留的正文内边距改动：若该 itemView 曾被我们改过
     * {@code clipboard_text_item_top/bottom} 的上下内边距，则还原成改前的原值。
     *
     * <p>只处理 {@link #sPadSaved} 里记录过的 View——系统自管的内边距绝不动，
     * 因此对云端条目的正常布局零影响。ViewHolder 复用或状态翻转后调用即可避免
     * 「非置顶条目少了呼吸空间、与其它条目高度不一致」。
     *
     * @return 是否发生过复位
     */
    private static boolean restoreContentPadding(View itemView, TextView top, TextView bottom) {
        int[] saved;
        synchronized (sPadSaved) {
            saved = sPadSaved.remove(itemView);
        }
        if (saved == null) {
            return false;
        }
        setContentPadding(top, saved[0], saved[1]);
        setContentPadding(bottom, saved[2], saved[3]);
        log("content padding restored: top=" + saved[0] + "/" + saved[1]
                + " bottom=" + saved[2] + "/" + saved[3]);
        return true;
    }

    /** 来源标注前缀（系统 phrase 包字符串；id 解析失败时的兜底匹配）。 */
    private static boolean isSourceLabel(CharSequence cs) {
        if (cs == null) {
            return false;
        }
        String s = cs.toString();
        return s.startsWith("来自我的电脑")
                || s.startsWith("来自我的手机")
                || s.startsWith("来自我的平板");
    }

    /** 递归找来源标注 TextView（深度优先，取第一个命中的）。 */
    private static TextView findSourceLabel(View root) {
        if (root instanceof TextView) {
            return isSourceLabel(((TextView) root).getText()) ? (TextView) root : null;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                TextView hit = findSourceLabel(vg.getChildAt(i));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** phrase 包 id 名 → id 值缓存（面板布局资源来自 com.miui.phrase）。 */
    private static final Map<String, Integer> sPhraseIdCache = new HashMap<>();

    /**
     * 按名解析 phrase 包的 id。输入法进程里 {@code view.getResources()} 拿到的是
     * 输入法自己的资源，按名查必然落空，必须经 PackageManager 取 phrase 的资源包
     * （与 {@link PanelColorHook} 同款路径）。
     */
    private static int phraseIdOf(String name) {
        Integer cached = sPhraseIdCache.get(name);
        if (cached != null) {
            return cached;
        }
        int id = 0;
        try {
            Context ctx = ctxOf();
            if (ctx != null) {
                android.content.res.Resources res = ctx.getPackageManager()
                        .getResourcesForApplication(XposedInit.PKG_PHRASE);
                id = res.getIdentifier(name, "id", XposedInit.PKG_PHRASE);
            }
        } catch (Throwable ignored) {
            // 解析失败：id 保持 0，调用方走兜底
        }
        sPhraseIdCache.put(name, id);
        return id;
    }

    /** 诊断：前 3 次 bind 各打一次条目视图树（类名 + id 名 + 可见性 + 文本）。 */
    private static int sTreeDumps = 0;

    private static void dumpTreeOnce(View root) {
        if (sTreeDumps >= 3 || root == null) {
            return;
        }
        sTreeDumps++;
        StringBuilder sb = new StringBuilder("bind tree #" + sTreeDumps + ":");
        dumpTree(root, sb, 0);
        log(sb.toString());
    }

    private static void dumpTree(View v, StringBuilder sb, int depth) {
        try {
            sb.append('\n');
            for (int i = 0; i < depth; i++) {
                sb.append(' ');
            }
            sb.append(v.getClass().getSimpleName());
            int id = v.getId();
            if (id != View.NO_ID) {
                String name = null;
                try {
                    name = v.getResources().getResourceName(id);
                } catch (Throwable ignored) {
                    // 解析不了只打 hex
                }
                sb.append(" id=").append(name != null ? name : Integer.toHexString(id));
            }
            sb.append(" vis=").append(v.getVisibility());
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null) {
                    String s = t.toString();
                    sb.append(" text=\"")
                            .append(s.length() > 40 ? s.substring(0, 40) + "…" : s)
                            .append('"');
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    dumpTree(vg.getChildAt(i), sb, depth + 2);
                }
            }
        } catch (Throwable t) {
            sb.append(" <dump failed: ").append(t).append('>');
        }
    }

    /** 取适配器位置对应条目的 time（优先当前列表，退回查询快照）；取不到返回 0。 */
    private static long rowTimeAt(int pos) {
        List<?> list = sDisplayListRef.get();
        if (list != null && pos >= 0 && pos < list.size()) {
            long t = timeOfItem(list.get(pos));
            if (t != 0L) {
                return t;
            }
        }
        if (pos >= 0 && pos < sRowTimes.size()) {
            return sRowTimes.get(pos);
        }
        return 0L;
    }

    // ---------------- 置顶状态持久化 ----------------

    private static void persistPin(Context ctx, long time, String json) {
        if (ctx == null) {
            return;
        }
        FileWriter w = null;
        try {
            File f = new File(ctx.getFilesDir(), PIN_FILE);
            w = new FileWriter(f);
            w.write(time + "\n" + json);
            w.flush();
        } catch (Throwable t) {
            log("persistPin failed - " + t);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                    // 关闭失败忽略
                }
            }
        }
    }

    /** 清内存态并删除持久化文件。query 重排路径调用（严禁再触发刷新）。 */
    private static void clearPinState(Context ctx) {
        sPinnedTime = 0L;
        sPinnedJson = null;
        if (ctx != null) {
            try {
                new File(ctx.getFilesDir(), PIN_FILE).delete();
            } catch (Throwable ignored) {
                // 删除失败忽略
            }
        }
    }

    /** 从输入法 files 目录恢复置顶状态（只试一次）。 */
    private static void loadPinIfNeeded(Context ctx) {
        if (sPinLoaded || ctx == null) {
            return;
        }
        sPinLoaded = true;
        BufferedReader r = null;
        try {
            File f = new File(ctx.getFilesDir(), PIN_FILE);
            if (!f.exists()) {
                return;
            }
            r = new BufferedReader(new FileReader(f));
            String first = r.readLine();
            if (first == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            long time = Long.parseLong(first.trim());
            String json = sb.toString();
            if (time != 0L && json.length() > 2) {
                sPinnedTime = time;
                sPinnedJson = json;
                log("pin restored from disk: time=" + time);
            }
        } catch (Throwable t) {
            log("loadPinIfNeeded failed - " + t);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                    // 关闭失败忽略
                }
            }
        }
    }

    // ---------------- 手势辅助 ----------------

    private static GestureState stateOf(Object key) {
        synchronized (sStates) {
            GestureState st = sStates.get(key);
            if (st == null) {
                st = new GestureState();
                sStates.put(key, st);
            }
            return st;
        }
    }

    /** SwipeRevealLayout 的主内容视图（child1）。 */
    private static View mainViewOf(Object swipe) {
        try {
            Object v = Reflect.getField(swipe, "mMainView");
            if (v instanceof View) {
                return (View) v;
            }
        } catch (Throwable t) {
            log("mainViewOf failed - " + t);
        }
        return null;
    }

    /** 删除按钮是否已露出（打开态）。读不到方法时退回主视图位移判断。
     *
     * <p>注意：MIUI 版 {@code isOpened()} 实测恒返回 false（见 code 84 实机 bug），
     * 手势状态机已改用主视图 translationX 符号判断，本方法仅作工具保留。 */
    private static boolean isOpened(Object swipe) {
        try {
            Object r = Reflect.callMethod(swipe, "isOpened");
            if (r instanceof Boolean) {
                return (Boolean) r;
            }
        } catch (Throwable ignored) {
            // 方法不存在，走位移兜底
        }
        View main = mainViewOf(swipe);
        return main != null && Math.abs(main.getTranslationX()) > 1f;
    }

    private static float slopOf(View v) {
        try {
            return android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        } catch (Throwable t) {
            return 24f;
        }
    }

    /**
     * 置顶门宽（dp→px）：按钮直径 + 单侧边距。
     *
     * <p>与原生删除门同规格：删除门 = 18dp 边距 + 58dp 圆 = 76dp；置顶门镜像取同值。
     * 按钮布于 SRL 左缘 18dp 处，右缘顶在门宽边界（主视图归位后的左缘）。
     */
    private static float pinDoorWidth(View v) {
        float density = 3f;
        try {
            density = v.getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            // 用默认密度
        }
        return (PIN_BTN_DP + PIN_BTN_MARGIN_DP) * density;
    }

    // ---------------- 置顶按钮（每条目一个，懒创建） ----------------

    /** SwipeRevealLayout → 置顶按钮视图。 */
    private static final WeakHashMap<Object, View> sPinBtns = new WeakHashMap<>();

    /**
     * 取（没有则创建）置顶按钮并挂到 SwipeRevealLayout 上。
     *
     * <p>按钮 addView 到<b>末尾</b>（child2）：MIUI 的 onLayout 只摆它认识的
     * child0（删除按钮）/ child1（主视图），且 {@code onFinishInflate} 早已跑完
     * （mSecondaryView/mMainView 是缓存字段），不会因多一个 child 而错位；
     * 按钮自身位置由 {@link #hookOnLayout} 在每次 layout 后手动摆到左侧。
     * 按钮<b>不可点击</b>（不消费触摸），点击命中由手势状态机在 UP 时按矩形判断，
     * 这样从按钮上起滑关门也不会被按钮吞掉事件。
     *
     * <p>图标：蓝底正圆内居中放一枚<b>上箭头</b>（{@link PinIcon}，base64 内嵌、
     * 运行时解码），替代旧版的白色「顶」字。原图是深藏青、在蓝底上对比不足，
     * 故按 SRC_IN 染成白色，与旧版白字视觉一致。解码失败时回退为旧版文字样式。
     */
    private static View pinBtnOf(Object swipe) {
        synchronized (sPinBtns) {
            View btn = sPinBtns.get(swipe);
            if (btn != null) {
                return btn;
            }
            View sv = (View) swipe;
            float density = sv.getResources().getDisplayMetrics().density;
            int size = (int) (PIN_BTN_DP * density + 0.5f);

            // 图标优先；解码失败退回旧版「顶」字，保证按钮永远有内容。
            // 具体图标（置顶上箭头 / 取消置顶「不」符号）不在这里选，
            // 由 {@link #applyPinIcon} 按条目当前置顶态填充——RV 复用
            // 条目时 swipe 实例不变、对应条目会变，创建时选一次会错。
            View content;
            Bitmap icon = PinIcon.get();
            if (icon != null) {
                ImageView iv = new ImageView(sv.getContext());
                int pad = (int) (PIN_ICON_PAD_DP * density + 0.5f);
                iv.setPadding(pad, pad, pad, pad);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                // 原图 #FF272636 深藏青，蓝底圆上对比不足；染白与旧版白色「顶」字一致
                // （取消置顶图标是 alpha 图 RGB 纯白，同滤色器下无副作用）
                iv.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
                content = iv;
            } else {
                TextView tv = new TextView(sv.getContext());
                tv.setText("顶");
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setGravity(android.view.Gravity.CENTER);
                content = tv;
            }
            content.setLayoutParams(new ViewGroup.LayoutParams(size, size));
            content.setClickable(false);
            content.setFocusable(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(PIN_BTN_COLOR);
            content.setBackground(bg);
            // 初始位置在「门外」：整体向左移出一个门宽，被父容器左边界裁掉（不可见）。
            // 开门时由 {@link #syncPinBtn} 让它随主视图一起滑进露出区。
            content.setTranslationX(-pinDoorWidth(sv));
            try {
                ((ViewGroup) sv).addView(content);
                sPinBtns.put(swipe, content);
                log("pin button created for " + sv.getClass().getSimpleName());
                // 创建即按当前条目置顶态选图标（RV 复用视图时也不会错）
                applyPinIcon(swipe, content);
            } catch (Throwable t) {
                log("pin button addView failed - " + t);
                return null;
            }
            return content;
        }
    }

    /**
     * 按条目当前置顶态填按钮图标：已置顶 → 「取消置顶」符号
     * （{@link PinCancelIcon}，横杠 + 斜杠）；未置顶 → 置顶上箭头
     * （{@link PinIcon}）。用户 2026-10-19 需求：置顶与取消置顶用不同图标，
     * 已置顶条目右滑时按钮应表达「再点一次=取消」的语义。
     *
     * <p>每次按钮显示（{@link #showPinBtn} true）都重新判定，不用 tag 记账
     * 以外的缓存——RV 复用条目时 swipe 实例不变、对应条目会变。
     * 取图标失败时保留上一个图标（ImageView 空图标会被父圆底衬着，比闪回
     * 「顶」字好）；两个图标都解码失败时按钮本就是 TextView 回退，不会到这。
     */
    private static void applyPinIcon(Object swipe, View btn) {
        if (!(btn instanceof ImageView)) {
            return;
        }
        boolean pinned = isRowPinned(swipe);
        Bitmap icon = pinned ? PinCancelIcon.get() : PinIcon.get();
        if (icon == null && pinned) {
            // 取消置顶图标解码失败：退回置顶图标（功能不丢，视觉降级）
            icon = PinIcon.get();
        }
        if (icon == null) {
            return;
        }
        ((ImageView) btn).setImageBitmap(icon);
    }

    /** 当前 swipe 对应条目是否处于置顶态（无置顶条目时零开销快路）。 */
    private static boolean isRowPinned(Object swipe) {
        if (sPinnedTime == 0L) {
            return false;
        }
        try {
            return timeOfRow(swipe) == sPinnedTime;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手动把置顶按钮摆到条目左侧垂直居中（MIUI onLayout 不认识它）。 */
    private static void layoutPinBtn(Object swipe, View btn) {
        View sv = (View) swipe;
        int w = sv.getWidth();
        int h = sv.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float density = sv.getResources().getDisplayMetrics().density;
        int size = (int) (PIN_BTN_DP * density + 0.5f);
        int margin = (int) (PIN_BTN_MARGIN_DP * density + 0.5f);
        int top = (h - size) / 2;
        btn.layout(margin, top, margin + size, top + size);
    }

    /**
     * 置顶按钮显隐。
     *
     * <p>不涉及其位移：位移由 {@link #syncPinBtn}（跟手）与开关门动画负责。
     * 隐藏时把按钮放回「门外」起始位，供下次开门滑入；显示时不动位移，
     * 由调用方紧接着同步（开门瞬间按钮还在门外被裁着，不会闪）。
     */
    private static void showPinBtn(Object swipe, boolean visible) {
        View btn = sPinBtns.get(swipe);
        if (btn == null) {
            if (!visible) {
                return;
            }
            btn = pinBtnOf(swipe);
            if (btn == null) {
                return;
            }
        }
        int want = visible ? View.VISIBLE : View.GONE;
        // 先取消在途动画：否则上一次关门动画的 withEndAction 可能把刚重新打开的按钮又置 GONE
        btn.animate().cancel();
        if (!visible) {
            btn.setTranslationX(-pinDoorWidth((View) swipe));
        }
        if (visible) {
            // 每次显示都按条目当前置顶态刷新图标（RV 复用会换条目，
            // 且 togglePin 后面板刷新会重排，不能靠创建时的一次选择）
            applyPinIcon(swipe, btn);
        }
        if (btn.getVisibility() != want) {
            btn.setVisibility(want);
        }
    }

    /**
     * 按主视图位移同步置顶按钮位置：{@code 按钮 translationX = 主视图 translationX - 门宽}。
     *
     * <p><b>为什么这样算</b>：真机实测（对用户取消删除的过程截图逐帧量红圆坐标）发现，
     * 系统删除按钮并不是「原地不动等着被主视图盖住」，而是<b>被条目推着同向等量位移</b>，
     * 右缘始终顶在面板边界上被裁掉、左缘一路右移（693→742→770→794→820→消失），
     * 位移量恰为一个门宽时整体越界不见。置顶按钮在左侧，做镜像即可：
     * 门全关时按钮移出父容器<b>左</b>边界被裁掉（不可见），门全开时正好落在露出区，
     * 且按钮右缘恒定贴在主视图左缘旁——视觉上就是「条目推着按钮一起走、越界被裁」。
     *
     * <p>相比早期用透明度渐隐的方案，这个方案与原生删除按钮的机制完全同构，
     * 也不会出现「按钮浮在条目上」的问题（它始终在露出区里）。
     *
     * <p>几何（与原生删除门对齐）：门全开时按钮占据 SRL 左缘 18dp..76dp，
     * 右缘恒定贴在主视图左缘（主视图位移量 = 门宽 76dp）。
     */
    private static void syncPinBtn(Object swipe, float bw, float mainTx) {
        View btn = sPinBtns.get(swipe);
        if (btn == null) {
            return;
        }
        btn.setTranslationX(mainTx - bw);
    }

    /**
     * 中和系统删除门：调用系统自己的 {@code SwipeRevealLayout.close(false)}——
     * {@code resetClose()} 把主视图/删除钮 layout 回关门位、
     * {@code mDragHelper.a()} 取消拖动（清指针 + 中断滚动 + 置 IDLE）、
     * {@code mState=0}。相当于系统的「硬关门」，可作两轨属性的中和器。
     *
     * <p><b>为什么需要它</b>（本会话反编译实证）：系统 {@code M.e.tryCaptureView}
     * 有副作用——每次 DOWN 都会 {@code captureChildView(mMainView)} 把系统拖动
     * 武装成 DRAGGING，且拖动分支没有逐事件 slop 校验；我们 proceed 掉的每个
     * MOVE 都会 1:1 拖动主视图 {@code left}（删除门）。一旦 UP 被我们消费
     *（系统收不到 onViewReleased，没有关门兜底），删除门就冻结在被拖开的
     * 位置——轻则残留红边，重则与置顶门同显。凡我们接管/消费手势的前后，
     * 都先中和掉系统门，保证任一时刻只有一扇门。
     *
     * <p>删除门关着（主视图 left 未低于关门位）时零开销返回；系统方法反射失败时
     * 仅记日志不崩（手势状态机自身仍有实际态兜底）。
     */
    private static void neutralizeSystemDoor(Object swipe, View main) {
        if (!systemDoorOpen(swipe, main)) {
            return;
        }
        // 先记触发前 left：close(false) 会把门修回关门位，
        // 事后再读只能看到回位值（恒 47），无法验证判据是否抓对
        int before = main.getLeft();
        int closedLeft = closedLeftOf(swipe);
        try {
            Reflect.callMethod(swipe, "close", Boolean.FALSE);
            log("system delete door neutralized (left " + before + " -> "
                    + main.getLeft() + ", closedLeft=" + closedLeft + ")");
        } catch (Throwable t) {
            log("WARN: neutralizeSystemDoor - " + t);
        }
    }

    /**
     * 关门：主视图归位 + 按钮同向等量滑回「门外」并被父容器裁掉（150ms，与主视图回弹同步）。
     *
     * <p>机制同原生删除门：删除按钮是被条目推着走、越界被裁；置顶按钮做镜像——
     * 跟着条目滑回左边，整体移出父容器左边界后被裁掉，动画结束置 GONE。
     */
    private static void closePinDoor(Object swipe, View main, float bw) {
        // 清理单开记账：本条关门后不再占用「唯一开门条」名额
        clearPinDoorOpen(swipe);
        if (main != null) {
            main.animate().translationX(0f).setDuration(150L).start();
        }
        View btn = sPinBtns.get(swipe);
        if (btn == null) {
            return;
        }
        btn.animate().cancel();
        if (btn.getVisibility() == View.VISIBLE) {
            btn.animate().translationX(-bw).setDuration(150L).withEndAction(() -> {
                if (btn.getTranslationX() <= -bw + 0.5f) {
                    btn.setVisibility(View.GONE);
                }
            }).start();
        } else {
            btn.setTranslationX(-bw);
        }
    }

    /** 触摸点是否落在置顶按钮矩形内。 */
    private static boolean isInPinBtn(View sv, MotionEvent ev) {
        View btn = sPinBtns.get(sv);
        if (btn == null || btn.getVisibility() != View.VISIBLE) {
            return false;
        }
        float x = ev.getX();
        float y = ev.getY();
        // 按钮位置 = layout 位置 + translationX（跟手/动画中位移 nontrivial，必须算进去）
        float off = btn.getTranslationX();
        return x >= btn.getLeft() + off && x <= btn.getRight() + off
                && y >= btn.getTop() && y <= btn.getBottom();
    }

    /**
     * 条目被复用时重置其置顶门状态：主视图归位、按钮隐藏、状态回 MODE_NONE。
     *
     * <p>RV 复用 item 不会自动清理 translationX/自定义子 view 显隐，开门态条目
     * 被滑出屏幕再复用会带着 +bw 位移和可见按钮显示成错乱态。
     *
     * <p><b>判据看「视图实际态」，不只看 mode</b>：取消置顶后 mode 已回 NONE，
     * 但 150ms 关门动画仍在途（translationX/按钮可见），此时
     * {@code refreshPanel → setClipboardList} 触发 rebind 必须硬复位——
     * 否则在途动画的 end-action 或残留按钮会挂到（可能已绑定别的条目的）
     * 复用视图上。系统删除门冻结开着（left 低于关门位）一并中和。
     */
    private static void resetPinDoorIfNeeded(View itemView) {
        Object swipe = findSwipeReveal(itemView, 0);
        if (swipe == null) {
            return;
        }
        GestureState st = sStates.get(swipe);
        View main = mainViewOf(swipe);
        View btn = sPinBtns.get(swipe);
        boolean dirty = (st != null && st.mode != MODE_NONE)
                || (main != null && (main.getTranslationX() > 1f || systemDoorOpen(swipe, main)))
                || (btn != null && btn.getVisibility() == View.VISIBLE);
        if (!dirty) {
            return;
        }
        clearPinDoorOpen(swipe);
        if (st != null) {
            st.mode = MODE_NONE;
            st.declined = false;
        }
        if (main != null) {
            main.animate().cancel();
            main.setTranslationX(0f);
            neutralizeSystemDoor(swipe, main);
        }
        showPinBtn(swipe, false);
        log("pin door reset on rebind");
    }

    /** 在视图树里找 SwipeRevealLayout 实例（条目根通常就是它）。 */
    private static Object findSwipeReveal(View v, int depth) {
        if (v == null || depth > 3) {
            return null;
        }
        if (SWIPE_REVEAL.equals(v.getClass().getName())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Object r = findSwipeReveal(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // ---------------- 上下文 / 反射工具 ----------------

    /**
     * 取输入法进程自己的 Application context。
     *
     * <p><b>不要用面板 popup 的 contentView context</b>——面板要加载 {@code com.miui.phrase}
     * 的资源，其 context 实际是 phrase 包的 context（实测 files 目录落在
     * {@code /data/user/0/com.miui.phrase/} 下，写入失败）。持久化目录与
     * ContentResolver 的 callingPackage 都应归属当前输入法进程，故优先取
     * {@code ActivityThread.currentApplication()}；取不到再退回面板 context 兜底。
     */
    private static Context ctxOf() {
        // ActivityThread 是 @hide 类，只能反射取当前 Application
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // ActivityThread 不可用，走兜底
        }
        return sImeCtxRef.get();
    }

    private static PopupWindow popupOf() {
        return sPopupRef.get();
    }

    private static ContentResolver resolverOf() {
        Context c = ctxOf();
        return c == null ? null : c.getContentResolver();
    }

    private static Object adapterOf() {
        return sAdapterRef.get();
    }

    private static ClassLoader clOf() {
        return sPanelCl != null ? sPanelCl : sDefaultCl;
    }

    /** 面板 classloader 优先、默认 classloader 兜底解析类。 */
    private static Class<?> resolve(String name) {
        ClassLoader[] loaders = {sPanelCl, sDefaultCl};
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Reflect.findClass(name, cl);
            } catch (Throwable ignored) {
                // 换下一个 classloader
            }
        }
        return null;
    }

    private static boolean isRecyclerView(Object o) {
        if (o == null) {
            return false;
        }
        try {
            Class<?> c = Class.forName(RECYCLER_VIEW, false, o.getClass().getClassLoader());
            return c.isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 深度优先找视图树里第一个 RecyclerView（面板内容容器内的列表）。 */
    private static Object findRecyclerView(View root) {
        if (root == null) {
            return null;
        }
        if (isRecyclerView(root)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                Object found = findRecyclerView(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ---------------- 其他 ----------------

    /** 从 ClipboardContentModel JSON 里取 "time" 数值；取不到返回 0。 */
    private static long extractTime(String json) {
        if (json == null) {
            return 0L;
        }
        try {
            int i = json.indexOf("\"time\"");
            if (i < 0) {
                return 0L;
            }
            i = json.indexOf(':', i);
            if (i < 0) {
                return 0L;
            }
            i++;
            while (i < json.length() && json.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < json.length() && Character.isDigit(json.charAt(i))) {
                i++;
            }
            if (start == i) {
                return 0L;
            }
            return Long.parseLong(json.substring(start, i));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 每实例手势状态。 */
    private static final class GestureState {
        float downX;
        float downY;
        /** {@link #MODE_NONE} / {@link #MODE_OPENING} / {@link #MODE_PIN_OPEN}。 */
        int mode;
        boolean declined;
    }

    private static void log(String msg) {
        XposedInit.log("[Pin] " + msg);
    }
}
