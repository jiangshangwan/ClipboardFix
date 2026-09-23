package com.clipboardfix;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 底部悬浮「液态玻璃」导航条（零新依赖，纯 framework API）。
 *
 * <p>效果与参数完整对照两个参考项目：
 * <ul>
 *   <li><b>liuran001/WeChat-LiquidGlass</b>（MIT）：单 pass AGSL 透镜管线、圆角矩形 SDF 折射、
 *       液滴色散/内阴影、标签副本 CombinedBackdrop、弹簧物理。</li>
 *   <li><b>1812z/HyperIsland</b>（Apache-2.0）：条高 64dp / 内高 56dp、item 最小 76dp、
 *       vibrancy 饱和 1.5 → blur 4dp → lens 24dp、液滴 lens(10dp·p, 14dp·p, 景深 + 色散 0.5)、
 *       按压放大 78/56、标签副本 lerp(1, 1.2, p)、五条弹簧（位置 1.0/1000、速度 0.5/300、
 *       按压 1.0/1000、横向缩放 0.6/250、纵向缩放 0.7/250）、交互高光、BloomStroke 边缘镜面高光。</li>
 * </ul>
 *
 * <p><b>玻璃条</b>：把背后内容录进 {@link RenderNode} → 饱和度提升 → 4dp 模糊 → 边缘折射透镜，
 * 再叠容器色垫底、按压交互高光（白色 bloom 跟随液滴）、边缘镜面高光（顶/底双高点，
 * 主光方向跟随重力传感器滑动）。
 *
 * <p><b>液滴</b>：折射的是「页面 + 玻璃材质 + 一份单独绘制的 1.2× 强调色标签副本」
 * （即参考项目的 CombinedBackdrop）——拖动时看到被放大的图标正是这份副本经透镜折射后的样子，
 * 因此液滴层压在真实标签之上，而真实标签保持原尺寸（两边都缩放会叠加两次）。
 * 按压时淡入折射透镜（含边缘色散与景深），叠加内阴影与镜面高光。
 *
 * <p><b>交互</b>：点击切换走 press → 移动 → release 的同一路径；按住可左右拖动连续滑动，
 * 拖动速度驱动液滴拉伸形变（跟手拉伸、松手吸附）；跨过标签时即时切换页面。
 *
 * <p><b>分级降级</b>：Android 13(API 33)+ 走完整 AGSL 管线；API 31-32 只做模糊+饱和；
 * 更低版本或渲染异常时退化为纯色液滴/磨砂面，不会崩。
 */
public class LiquidGlassBarView extends FrameLayout {

    // ===== 玻璃条参数（HyperIsland: vibrancy() → blur(4dp) → lens(24dp, 24dp)）=====
    private static final float GLASS_REFRACTION_DP = 24f;
    /** 模糊半径（dp）：实机反馈 4dp 太糊，降到 2.5dp 让背后内容更清楚。 */
    private static final float GLASS_BLUR_DP = 2.5f;
    private static final float GLASS_SATURATION = 1.5f;
    /** 镜面边缘高光：实机反馈偏狠，压到最小（只留极淡一圈）。 */
    private static final float GLASS_RIM_ALPHA = 0.12f;

    // ===== 液滴参数（KernelSU/HyperIsland 的 droplet 效果栈）=====
    /** lens(refractionHeight = 10dp × p, refractionAmount = 14dp × p)。 */
    private static final float DROP_REFRACTION_DP = 10f;
    private static final float DROP_AMOUNT_DP = 14f;
    /** chromaticAberration = 0.5，depthEffect = true。 */
    private static final float DROP_ABERRATION = 0.5f;
    /** innerShadow 已禁用：参考项目的水滴没有黑色内圈，我们之前的「黑边」就是它。 */
    private static final float DROP_INNER_SHADOW_DP = 8f;
    private static final float DROP_INNER_SHADOW_ALPHA = 0f;
    /** 标签副本缩放 lerp(1, 1.2, p)。 */
    private static final float TAB_ZOOM = 0.2f;
    /** 按压时液滴放大倍率（HyperIsland 的 78/56）。 */
    private static final float PRESS_SCALE = 78f / 56f;

    /**
     * View 边界比胶囊大一圈的量（dp）。硬件加速下 View 自身绘制会被裁剪到 View 边界，
     * 按压后鼓出条外的液滴必须画在更大的 View 里才能「溢出」。布局里：
     * 高 96 = 64 + 2×16、padding 20 = 4 + 16、边距 0。
     */
    private static final float BAR_INSET_DP = 16f;

    // ===== 拖动速度形变（DampedDragAnimation.layerBlock 的原样公式）=====
    private static final float STRETCH_DIVISOR = 10f;
    private static final float STRETCH_X = 0.75f;
    private static final float STRETCH_Y = 0.25f;
    private static final float STRETCH_LIMIT = 0.2f;

    /** 判定为拖动所需的最小位移。 */
    private static final float DRAG_THRESHOLD_DP = 12f;
    /** 松手后等液滴接近目标再收拢缩放的阈值（HyperIsland: range × 2.5%）。 */
    private static final float SETTLE_FRACTION = 0.025f;

    /**
     * 液滴静止色调：比导航栏<b>深一点</b>（浅色）/ <b>亮一点</b>（深色）的一档，
     * 让玻璃泡从条里「浮」出来（参考项目的水滴就是这样与条区分的）。
     */
    private static final int DROP_WASH_LIGHT = 0x0A;
    private static final int DROP_WASH_DARK = 0x14;
    /** 按压附加色：black @ 2% × p（原 3% 会读出「按压变白/变灰」的遮罩感）。 */
    private static final int DROP_PRESS_TINT = 0x05;

    private static final PorterDuffXfermode SRC_ATOP =
            new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);
    /** Skia 的 Plus 混合；API 28 的枚举里没有 ADD，取不到时退化为普通混合。 */
    private static final PorterDuffXfermode PLUS = plusXfermode();

    private static PorterDuffXfermode plusXfermode() {
        try {
            return new PorterDuffXfermode(PorterDuff.Mode.ADD);
        } catch (Throwable t) {
            return null;
        }
    }

    public interface OnItemSelectedListener {
        void onItemSelected(int index);
    }

    // ===== 背景与内容 =====
    private WeakReference<View> mBackdrop;
    private final List<View> mItems = new ArrayList<>(4);
    private int mItemCount = 3;
    private int mAccent;
    private boolean mNight;

    // ===== 尺寸 =====
    private final float mDensity;
    private final int mGlassPad;
    private final int mDropPad;
    private final float mDragThresholdPx;
    /** View 边界相对胶囊的内缩量（px）：胶囊 = View 边界内缩 inset 后的矩形。 */
    private final int mInsetX;
    private final int mInsetY;

    // ===== 渲染对象 =====
    private RenderNode mGlassNode;
    private RenderNode mDropPageNode;
    private RenderNode mDropNode;
    private RuntimeShader mGlassLens;
    private RuntimeShader mDropLens;
    private RuntimeShader mInnerShader;
    private RuntimeShader mRimShader;
    private RuntimeShader mBloomShader;
    private RenderEffect mSaturate;
    private RenderEffect mBlurEffect;
    private RenderEffect mChain;
    private int mChainW;
    private int mChainH;
    private boolean mGlassOn = true;

    // ===== 画笔 =====
    private final Paint mSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDropWashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPressTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWashPlusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBarClip = new Path();
    private final Path mDropClip = new Path();
    private final int[] mSelf = new int[2];
    private final int[] mSrc = new int[2];

    private int mBaseColor;

    // ===== 弹簧（HyperIsland DampedDragAnimation 的五条，参数照搬）=====
    // 刚度按实机手感上调：位置 1000→1800、按压 1000→1600、缩放 250→420，
    // 切页的水滴滑动明显更跟手（阻尼比保持原值，仍为欠阻尼/临界的液态手感）。
    private final Spring mValue = new Spring(1.0f, 1800f, 0.001f, 0f);
    private final Spring mVelocity = new Spring(0.5f, 300f, 0.01f, 0f);
    private final Spring mPress = new Spring(1.0f, 1600f, 0.001f, 0f);
    private final Spring mScaleX = new Spring(0.6f, 420f, 0.001f, 1f);
    private final Spring mScaleY = new Spring(0.7f, 420f, 0.001f, 1f);
    private boolean mFramesRunning;
    private long mLastNs;
    private boolean mReleasePending;

    // ===== 手势状态 =====
    private int mSelectedIndex;
    private boolean mGestureActive;
    private boolean mIsDragging;
    private float mTouchStartX;
    private float mTouchStartY;
    private OnItemSelectedListener mItemListener;

    // ===== 重力高光 =====
    private SensorManager mSensorManager;
    private Sensor mGravitySensor;
    private float mLightX;
    private float mLightY = -1f;

    // ===== 背景实时跟随 =====
    private final ViewTreeObserver.OnScrollChangedListener mScrollListener =
            this::invalidateBackdrop;
    private final ViewTreeObserver.OnGlobalLayoutListener mLayoutListener =
            this::invalidateBackdrop;
    private boolean mBgListenersAttached;
    /**
     * 背景（折射源）是否需要重录。动画期间每帧都重录整个内容区是掉帧的元凶，
     * 只有背景真的变了（滚动/布局/切页/尺寸/主题）才重录。
     */
    private boolean mBackdropDirty = true;

    /** 背景内容变了：标记重录并重绘（比 invalidate 更贵但必要）。 */
    private void invalidateBackdrop() {
        mBackdropDirty = true;
        invalidate();
    }

    public LiquidGlassBarView(Context ctx) {
        this(ctx, null);
    }

    public LiquidGlassBarView(Context ctx, AttributeSet attrs) {
        this(ctx, attrs, 0);
    }

    public LiquidGlassBarView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        mDensity = getResources().getDisplayMetrics().density;
        // 透镜会采样自身边界之外的内容，所以背景要多录一圈折射量
        mGlassPad = Math.round(GLASS_REFRACTION_DP * mDensity);
        mDropPad = Math.round((DROP_AMOUNT_DP + 4f) * mDensity);
        mDragThresholdPx = DRAG_THRESHOLD_DP * mDensity;
        mInsetX = Math.round(BAR_INSET_DP * mDensity);
        mInsetY = Math.round(BAR_INSET_DP * mDensity);
        setWillNotDraw(false);
        applyTheme();
        prepareEffects();
        setupShadow();
    }

    /**
     * 上游（HyperIsland/KernelSU）玻璃条的投影：radius 10dp 的黑色阴影。
     * 没有它，浅色模式下玻璃会和页面底色融为一体（用户实测反馈）。
     */
    private void setupShadow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w <= 0 || h <= 0) {
                    return;
                }
                // 阴影跟随胶囊而不是放大的 View 边界
                int pw = w - 2 * mInsetX;
                int ph = h - 2 * mInsetY;
                if (pw <= 0 || ph <= 0) {
                    return;
                }
                outline.setRoundRect(mInsetX, mInsetY, mInsetX + pw, mInsetY + ph, ph * 0.5f);
            }
        });
        setClipToOutline(false);
        setElevation(10f * mDensity);
    }

    // ---------------------------------------------------------------- 公开 API

    /** 传入需要被折射的「背后内容」（一般是承载各页面的容器）。 */
    public void setBackdrop(View backdrop) {
        detachBackgroundListeners();
        mBackdrop = new WeakReference<>(backdrop);
        attachBackgroundListeners();
        invalidateBackdrop();
    }

    private void attachBackgroundListeners() {
        if (mBgListenersAttached || mBackdrop == null) {
            return;
        }
        ViewTreeObserver observer = viewObserver();
        if (observer != null) {
            // 背景实时跟随：内容滚动/布局变化时重录折射背景
            observer.addOnScrollChangedListener(mScrollListener);
            observer.addOnGlobalLayoutListener(mLayoutListener);
            mBgListenersAttached = true;
        }
    }

    private void detachBackgroundListeners() {
        if (!mBgListenersAttached) {
            return;
        }
        ViewTreeObserver observer = viewObserver();
        if (observer != null) {
            observer.removeOnScrollChangedListener(mScrollListener);
            observer.removeOnGlobalLayoutListener(mLayoutListener);
        }
        mBgListenersAttached = false;
    }

    /** 显式指定各标签 View（用于液滴里的 1.2× 标签副本）；不调用时自动从子 View 发现。 */
    public void setItems(View... items) {
        mItems.clear();
        if (items != null) {
            for (View v : items) {
                if (v != null) {
                    mItems.add(v);
                }
            }
        }
        if (!mItems.isEmpty()) {
            mItemCount = mItems.size();
        }
        invalidate();
    }

    public void setItemCount(int count) {
        mItemCount = Math.max(1, count);
    }

    /** 液滴里标签副本的染色（即「选中色」，HyperIsland 的 LocalContentColor = accentColor）。 */
    public void setAccentColor(int color) {
        mAccent = color | 0xFF000000;
        invalidate();
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        mItemListener = listener;
    }

    /**
     * 外部切页：液滴以弹簧滑动到目标位置。
     *
     * <p>手势进行中只记录选中项——此时液滴位置由手势驱动，避免两套动画打架。
     */
    public void setSelectedIndex(int index, boolean animate) {
        index = Math.max(0, Math.min(mItemCount - 1, index));
        mSelectedIndex = index;
        if (mGestureActive) {
            return;
        }
        if (!animate) {
            mValue.snapTo(index);
            mVelocity.snapTo(0f);
            mPress.snapTo(0f);
            mScaleX.snapTo(1f);
            mScaleY.snapTo(1f);
            applyBarScale();
            // 切页 = 背后内容变了，必须重录折射背景
            invalidateBackdrop();
            return;
        }
        mValue.animateTo(index);
        mVelocity.animateTo(0f);
        releaseDroplet();
        // 切页 = 背后内容变了，必须重录折射背景
        mBackdropDirty = true;
        startFrames();
    }

    /**
     * 兼容保留：给子 View 加按下反馈（不再触发页面切换）。
     */
    public void attachPress(View item, int index) {
        item.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressDroplet();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseDroplet();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** 兼容保留：直接驱动液滴的按压/收拢。 */
    public void setPressedState(boolean pressed) {
        if (pressed) {
            pressDroplet();
        } else {
            releaseDroplet();
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachBackgroundListeners();
        registerTiltSensor();
    }

    @Override
    protected void onDetachedFromWindow() {
        unregisterTiltSensor();
        detachBackgroundListeners();
        super.onDetachedFromWindow();
    }

    private ViewTreeObserver viewObserver() {
        View backdrop = mBackdrop == null ? null : mBackdrop.get();
        View anchor = backdrop != null ? backdrop : this;
        ViewTreeObserver observer = anchor.getViewTreeObserver();
        return observer != null && observer.isAlive() ? observer : null;
    }

    // ---------------------------------------------------------------- 主题

    private void applyTheme() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mNight = night == Configuration.UI_MODE_NIGHT_YES;
        mBaseColor = mNight ? 0xFF111111 : 0xFFF7F7F7;
        // 再调透：浅色白 18% / 深色容器 15%，以「能看清背后内容」为准
        mSurfacePaint.setColor(mNight ? 0x262C2C2E : 0x2EFFFFFF);
        // 液滴静止色调（10% 档），按压时按 press 进度淡出
        mDropWashPaint.setColor(mNight ? 0xFFFFFFFF : 0xFF000000);
        mDropWashPaint.setAlpha(mNight ? DROP_WASH_DARK : DROP_WASH_LIGHT);
        mPressTintPaint.setColor(0xFF000000);
        mPressTintPaint.setAlpha(0);
        mInnerShadowPaint.setStyle(Paint.Style.FILL);
        mWashPlusPaint.setColor(0xFFFFFFFF);
        mWashPlusPaint.setXfermode(PLUS);
        mBloomPaint.setXfermode(PLUS);
        mRimPaint.setXfermode(PLUS);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTheme();
        mAccent = 0;
        // 主题变了：录进玻璃的底色也要换，必须重录
        invalidateBackdrop();
    }

    // ---------------------------------------------------------------- 渲染准备

    private void prepareEffects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mGlassOn = false;
            return;
        }
        try {
            mGlassNode = new RenderNode("cfGlassBar");
            mDropPageNode = new RenderNode("cfDropPage");
            mDropNode = new RenderNode("cfDrop");
        } catch (Throwable t) {
            mGlassOn = false;
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(GLASS_SATURATION);
                mSaturate = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                float blur = GLASS_BLUR_DP * mDensity;
                mBlurEffect = mSaturate == null
                        ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                        : RenderEffect.createBlurEffect(blur, blur, mSaturate,
                                Shader.TileMode.CLAMP);
            } catch (Throwable t) {
                mSaturate = null;
                mBlurEffect = null;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                mGlassLens = new RuntimeShader(GlassShaders.LENS);
                mDropLens = new RuntimeShader(GlassShaders.LENS_DISPERSION);
                mInnerShader = new RuntimeShader(GlassShaders.INNER_SHADOW);
                mRimShader = new RuntimeShader(GlassShaders.RIM_HIGHLIGHT);
                mBloomShader = new RuntimeShader(GlassShaders.BLOOM);
            } catch (Throwable t) {
                mGlassLens = null;
                mDropLens = null;
                mInnerShader = null;
                mRimShader = null;
                mBloomShader = null;
            }
        }
    }

    // ---------------------------------------------------------------- 尺寸/布局

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mBarClip.reset();
        int pw = w - 2 * mInsetX;
        int ph = h - 2 * mInsetY;
        float r = ph * 0.5f;
        // 胶囊坐标系（View 内缩 inset 后），所有绘制都在这个坐标系里进行
        mBarClip.addRoundRect(0, 0, pw, ph, r, r, Path.Direction.CW);
        mChain = null;
        mBackdropDirty = true;
        discoverItems();
    }

    /** 胶囊宽度/高度 = View 尺寸 - 2×inset。 */
    private int pillWidth() {
        return getWidth() - 2 * mInsetX;
    }

    private int pillHeight() {
        return getHeight() - 2 * mInsetY;
    }

    /**
     * 内容边距（不含 inset）。触摸侧（View 坐标）用 getPadding*；
     * 绘制侧（胶囊坐标）用这组——XML 的 padding = 4dp 内容 + 16dp inset。
     */
    private int contentPadLeft() {
        return getPaddingLeft() - mInsetX;
    }

    private int contentPadRight() {
        return getPaddingRight() - mInsetX;
    }

    private int contentPadTop() {
        return getPaddingTop() - mInsetY;
    }

    private int contentPadBottom() {
        return getPaddingBottom() - mInsetY;
    }

    /** 未显式 setItems 时，从「第一个子 ViewGroup 的子 View」推断标签列表。 */
    private void discoverItems() {
        if (!mItems.isEmpty()) {
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) child;
                for (int j = 0; j < group.getChildCount(); j++) {
                    mItems.add(group.getChildAt(j));
                }
                break;
            }
        }
        if (!mItems.isEmpty()) {
            mItemCount = mItems.size();
        }
    }

    // ---------------------------------------------------------------- 绘制：玻璃条

    @Override
    protected void onDraw(Canvas canvas) {
        int pw = pillWidth();
        int ph = pillHeight();
        if (pw <= 0 || ph <= 0) {
            return;
        }
        // 之后所有绘制都在「胶囊坐标系」里（View 内缩 inset 后的矩形）
        canvas.save();
        canvas.translate(mInsetX, mInsetY);
        float r = ph * 0.5f;
        drawGlass(canvas, pw, ph);
        // 容器色垫底（承载可读性）
        canvas.drawRoundRect(0, 0, pw, ph, r, r, mSurfacePaint);
        drawInteractiveHighlight(canvas, pw, ph);
        drawRimHighlight(canvas, pw, ph, r, GLASS_RIM_ALPHA);
        canvas.restore();
    }

    private void drawGlass(Canvas canvas, int w, int h) {
        if (!mGlassOn || mGlassNode == null || !canvas.isHardwareAccelerated()) {
            return;
        }
        View backdrop = mBackdrop == null ? null : mBackdrop.get();
        if (backdrop == null || backdrop.getWidth() <= 0 || backdrop.getHeight() <= 0) {
            return;
        }
        int nw = w + mGlassPad * 2;
        int nh = h + mGlassPad * 2;
        try {
            // 只有背景真的变了才重录（滚动/布局/切页/尺寸变化时置脏）。
            // 动画期间每帧重录整个内容区是「切页反应慢」的主因。
            if (mBackdropDirty) {
                mGlassNode.setPosition(0, 0, nw, nh);
                RecordingCanvas rc = mGlassNode.beginRecording(nw, nh);
                try {
                    // 先铺底色：没被内容覆盖的区域若留透明，进模糊后会变成纯黑
                    rc.drawColor(mBaseColor);
                    getLocationOnScreen(mSelf);
                    backdrop.getLocationOnScreen(mSrc);
                    // 胶囊左上角在屏幕上位于 self + inset（View 比胶囊大一圈）
                    rc.translate(mGlassPad - (mSelf[0] - mSrc[0]) - mInsetX,
                            mGlassPad - (mSelf[1] - mSrc[1]) - mInsetY);
                    backdrop.draw(rc);
                } finally {
                    mGlassNode.endRecording();
                }
                mBackdropDirty = false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (mChain == null || mChainW != w || mChainH != h) {
                    if (mGlassLens != null && mBlurEffect != null) {
                        float radius = h * 0.5f;
                        mGlassLens.setFloatUniform("size", w, h);
                        mGlassLens.setFloatUniform("offset", -mGlassPad, -mGlassPad);
                        mGlassLens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
                        mGlassLens.setFloatUniform("refractionHeight",
                                GLASS_REFRACTION_DP * mDensity);
                        // HyperIsland 传入的是负值；depthEffect=1 让中心产生透镜凸起，
                        // 这是从「磨砂胶囊」进阶到「液态玻璃」的关键。
                        mGlassLens.setFloatUniform("refractionAmount",
                                -GLASS_REFRACTION_DP * mDensity);
                        mGlassLens.setFloatUniform("depthEffect", 1f);
                        mChain = RenderEffect.createChainEffect(
                                RenderEffect.createRuntimeShaderEffect(mGlassLens, "content"),
                                mBlurEffect);
                    } else {
                        mChain = mBlurEffect;
                    }
                    mChainW = w;
                    mChainH = h;
                }
                if (mChain != null) {
                    mGlassNode.setRenderEffect(mChain);
                }
            }

            canvas.save();
            canvas.clipPath(mBarClip);
            canvas.translate(-mGlassPad, -mGlassPad);
            canvas.drawRenderNode(mGlassNode);
            canvas.restore();
        } catch (Throwable t) {
            // 渲染链一旦失败就永久退化，避免每帧抛异常
            mGlassOn = false;
            invalidate();
        }
    }

    /**
     * KernelSU/HyperIsland 的 InteractiveHighlight：白色 wash + 跟随液滴的径向 bloom，
     * PLUS 混合，按压进度淡入——「手指压住玻璃时的聚光」。
     */
    private void drawInteractiveHighlight(Canvas canvas, int w, int h) {
        float p = mPress.value();
        if (p <= 0.01f || mBloomShader == null) {
            return;
        }
        int save = canvas.save();
        canvas.clipPath(mBarClip);
        // 按压辉光压到很轻：参考项目按压的读感来自「整条放大 + 液滴溢出」，
        // 而不是整条被刷白（原 6%/12% 会明显泛白）。
        mWashPlusPaint.setAlpha(Math.round(0x06 * p));
        canvas.drawRect(0, 0, w, h, mWashPlusPaint);
        // 径向 bloom：White @ 6% × p，radius = min(size) × 1.2，跟随液滴位置
        mBloomShader.setFloatUniform("size", w, h);
        mBloomShader.setFloatUniform("alpha", 0.06f * p);
        mBloomShader.setFloatUniform("radius", Math.min(w, h) * 1.2f);
        float cx = dropletCentreX();
        mBloomShader.setFloatUniform("position",
                Math.max(0f, Math.min(cx, w)), h * 0.5f);
        mBloomPaint.setShader(mBloomShader);
        canvas.drawRect(0, 0, w, h, mBloomPaint);
        canvas.restoreToCount(save);
    }

    /**
     * 边缘镜面高光（HyperIsland IndicatorSpecular / BloomStroke 的近似）：
     * 顶边主光 + 底边副光的双高点白色辉光，主光方向跟随重力传感器滑动。
     */
    private void drawRimHighlight(Canvas canvas, int w, int h, float radius, float alpha) {
        paintRim(canvas, w, h, radius, alpha);
    }

    /** 液滴的镜面高光：同一条 shader，平移到液滴局部坐标再画（shader 按绘制坐标采样）。 */
    private void drawDropRim(Canvas canvas, float left, float top,
                             float w, float h, float radius, float alpha) {
        int save = canvas.save();
        canvas.translate(left, top);
        paintRim(canvas, w, h, radius, alpha);
        canvas.restoreToCount(save);
    }

    private void paintRim(Canvas canvas, float w, float h, float radius, float alpha) {
        if (mRimShader == null || alpha <= 0.01f) {
            return;
        }
        mRimShader.setFloatUniform("size", w, h);
        mRimShader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        mRimShader.setFloatUniform("light1", mLightX, mLightY);
        mRimShader.setFloatUniform("light2", -mLightX, -mLightY);
        // 1dp 描边 + 2dp 内辉光 ≈ 3dp 的衰减带
        mRimShader.setFloatUniform("blur", 3f * mDensity);
        mRimShader.setFloatUniform("alpha", alpha);
        mRimPaint.setShader(mRimShader);
        canvas.drawRoundRect(0, 0, w, h, radius, radius, mRimPaint);
    }

    // ---------------------------------------------------------------- 绘制：液滴

    /**
     * 液滴画在真实标签<b>之上</b>：它折射的是「页面 + 玻璃材质 + 一份 1.2× 标签副本」，
     * 拖动时看到的放大图标正是这份副本经折射后的样子。
     */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // 液滴在胶囊坐标系里画（子 View 布局仍按 View 坐标，由 padding 含 inset 定位）
        canvas.save();
        canvas.translate(mInsetX, mInsetY);
        drawDroplet(canvas, pillWidth(), pillHeight());
        canvas.restore();
    }

    private void drawDroplet(Canvas canvas, int w, int h) {
        int innerW = w - contentPadLeft() - contentPadRight();
        int innerH = h - contentPadTop() - contentPadBottom();
        if (innerW <= 0 || innerH <= 0 || mItemCount <= 0) {
            return;
        }
        discoverItems();
        float itemW = innerW / (float) mItemCount;
        // 速度形变：HyperIsland 的原样公式（速度按标签数归一化后除 10）
        float v = mVelocity.value() / STRETCH_DIVISOR;
        float c1 = clamp(v * STRETCH_X, STRETCH_LIMIT);
        float c2 = clamp(v * STRETCH_Y, STRETCH_LIMIT);
        float scaleX = mScaleX.value() / (1f - c1);
        float scaleY = mScaleY.value() * (1f - c2);
        float halfW = itemW * 0.5f * scaleX;
        float halfH = innerH * 0.5f * scaleY;
        float cx = contentPadLeft() + (mValue.value() + 0.5f) * itemW;
        float cy = contentPadTop() + innerH * 0.5f;
        float left = cx - halfW;
        float top = cy - halfH;
        float right = cx + halfW;
        float bottom = cy + halfH;
        float radius = Math.min(halfW, halfH);
        float p = mPress.value();

        boolean drewGlass = false;
        if (mGlassOn && canvas.isHardwareAccelerated()) {
            try {
                drewGlass = drawDropGlass(canvas, left, top, right, bottom, radius, p);
            } catch (Throwable t) {
                mGlassOn = false;
            }
        }
        if (!drewGlass) {
            drawDropFallback(canvas, left, top, right, bottom, radius, p);
        }
        if (p > 0f) {
            // 镜面高光：与整条同步压到最小；内阴影保持禁用（参考项目无黑边）
            drawDropRim(canvas, left, top, right - left, bottom - top, radius, 0.12f * p);
            drawDropInnerShadow(canvas, left, top, right - left, bottom - top, radius, p);
        }
    }

    /** 液滴的完整玻璃管线：模糊页面 + 玻璃材质 + 表面色调 + 1.2× 标签副本 → 折射透镜。 */
    private boolean drawDropGlass(Canvas canvas, float left, float top,
                                  float right, float bottom, float radius, float p) {
        int pw = Math.round(right - left);
        int ph = Math.round(bottom - top);
        if (pw <= 0 || ph <= 0) {
            return false;
        }
        int nw = pw + mDropPad * 2;
        int nh = ph + mDropPad * 2;
        // 节点原点对应的本 View 坐标
        float dx0 = left - mDropPad;
        float dy0 = top - mDropPad;
        // 副本缩放与真实标签的 LocalLiquidTabScale 用同一系数，玻璃泡内外放大连续一致
        float zoom = 1f + tabZoomBase() * p;

        // 1) 页面层：单独录制并模糊——标签副本必须是清晰的，不能跟着一起糊
        mDropPageNode.setPosition(0, 0, nw, nh);
        RecordingCanvas pc = mDropPageNode.beginRecording(nw, nh);
        try {
            pc.drawColor(mBaseColor);
            View backdrop = mBackdrop == null ? null : mBackdrop.get();
            if (backdrop != null && backdrop.getWidth() > 0) {
                getLocationOnScreen(mSelf);
                backdrop.getLocationOnScreen(mSrc);
                // 胶囊左上角在屏幕上位于 self + inset（View 比胶囊大一圈）
                pc.translate(mSrc[0] - mSelf[0] - dx0 - mInsetX,
                        mSrc[1] - mSelf[1] - dy0 - mInsetY);
                backdrop.draw(pc);
            }
        } finally {
            mDropPageNode.endRecording();
        }
        if (mBlurEffect != null) {
            mDropPageNode.setRenderEffect(mBlurEffect);
        }

        // 2) 合成层
        mDropNode.setPosition(0, 0, nw, nh);
        RecordingCanvas rc = mDropNode.beginRecording(nw, nh);
        try {
            rc.drawColor(mBaseColor);
            rc.drawRenderNode(mDropPageNode);
            // 玻璃条的容器色：液滴与条身材质一致
            rc.drawRoundRect(mDropPad, mDropPad, mDropPad + pw, mDropPad + ph,
                    radius, radius, mSurfacePaint);
            drawDropSurfaceTints(rc, mDropPad, mDropPad, mDropPad + pw, mDropPad + ph,
                    radius, p);
            // 清晰的标签副本（放大 + 强调色），折射后即「放大并弯曲的图标」
            drawItemCopies(rc, dx0, dy0, zoom);
        } finally {
            mDropNode.endRecording();
        }

        if (p > 0.01f && mDropLens != null) {
            float band = Math.min(DROP_REFRACTION_DP * mDensity,
                    Math.min(pw, ph) * 0.5f * 0.45f);
            mDropLens.setFloatUniform("size", pw, ph);
            mDropLens.setFloatUniform("offset", -mDropPad, -mDropPad);
            mDropLens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            mDropLens.setFloatUniform("refractionHeight", band * p);
            mDropLens.setFloatUniform("refractionAmount",
                    -band * (DROP_AMOUNT_DP / DROP_REFRACTION_DP) * p);
            mDropLens.setFloatUniform("depthEffect", 1f);
            mDropLens.setFloatUniform("chromaticAberration", DROP_ABERRATION);
            mDropNode.setRenderEffect(
                    RenderEffect.createRuntimeShaderEffect(mDropLens, "content"));
        } else {
            mDropNode.setRenderEffect(null);
        }

        // 按压时液滴鼓出胶囊是液态玻璃的标志性形变（参考项目均不裁剪）
        canvas.save();
        mDropClip.reset();
        mDropClip.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
        canvas.clipPath(mDropClip);
        canvas.translate(dx0, dy0);
        canvas.drawRenderNode(mDropNode);
        canvas.restore();
        return true;
    }

    /**
     * 液滴表面色调，压在标签副本<b>之下</b>——
     * 副本盖在色调上可去掉「按下变暗 / 松手变亮」的颜色跳变（WeChat-LiquidGlass 的结论）。
     */
    private void drawDropSurfaceTints(Canvas c, float l, float t, float r, float b,
                                      float radius, float p) {
        int resting = mNight ? DROP_WASH_DARK : DROP_WASH_LIGHT;
        int washAlpha = Math.round(resting * (1f - p));
        if (washAlpha > 0) {
            mDropWashPaint.setAlpha(washAlpha);
            c.drawRoundRect(l, t, r, b, radius, radius, mDropWashPaint);
        }
        // black @ 3% × p
        int pressAlpha = Math.round(DROP_PRESS_TINT * p);
        if (pressAlpha > 0) {
            mPressTintPaint.setAlpha(pressAlpha);
            c.drawRoundRect(l, t, r, b, radius, radius, mPressTintPaint);
        }
    }

    /**
     * 标签副本：每个 item 绕自身中心缩放 lerp(1, 1.2, p)（HyperIsland 的
     * LocalLiquidTabScale），整体染成强调色（LocalContentColor = accentColor）。
     *
     * <p>染色必须是盖在 item 自己 draw() 之上的一整层——逐个叶子染色会漏掉
     * 容器自己画的内容（WeChat-LiquidGlass 踩过的坑）。
     */
    private void drawItemCopies(Canvas c, float dx0, float dy0, float zoom) {
        if (mItems.isEmpty()) {
            return;
        }
        int accent = accentColor();
        getLocationOnScreen(mSelf);
        for (int i = 0; i < mItems.size(); i++) {
            View item = mItems.get(i);
            if (item == null || item.getVisibility() != VISIBLE
                    || item.getWidth() <= 0 || item.getHeight() <= 0) {
                continue;
            }
            item.getLocationOnScreen(mSrc);
            int save = c.save();
            // item 在屏幕上的位置含 inset（item 布局在 View 坐标里），
            // 而本画布（液滴节点/胶囊坐标系）不含 inset，所以要减掉
            c.translate(mSrc[0] - mSelf[0] - dx0 - mInsetX,
                    mSrc[1] - mSelf[1] - dy0 - mInsetY);
            c.scale(zoom, zoom, item.getWidth() * 0.5f, item.getHeight() * 0.5f);
            // 图层边界要盖过放大后的副本，否则 zoom>1 时边缘会被图层裁掉
            float iw = item.getWidth();
            float ih = item.getHeight();
            int layer = c.saveLayer(-iw, -ih, iw * 2f, ih * 2f, null);
            item.draw(c);
            mTintPaint.setColor(accent);
            mTintPaint.setXfermode(SRC_ATOP);
            c.drawRect(-iw, -ih, iw * 2f, ih * 2f, mTintPaint);
            mTintPaint.setXfermode(null);
            c.restoreToCount(layer);
            c.restoreToCount(save);
        }
    }

    /** 无渲染管线时的降级：直接画色调 + 标签副本（不折射）。 */
    private void drawDropFallback(Canvas canvas, float left, float top,
                                  float right, float bottom, float radius, float p) {
        int save = canvas.save();
        mDropClip.reset();
        mDropClip.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
        canvas.clipPath(mDropClip);
        drawDropSurfaceTints(canvas, left, top, right, bottom, radius, p);
        // 直接画在主画布上：副本偏移基准是本 View 原点（dx0 = 0, dy0 = 0）
        drawItemCopies(canvas, 0f, 0f, 1f + tabZoomBase() * p);
        canvas.restoreToCount(save);
    }

    /** 液滴内阴影：已禁用（DROP_INNER_SHADOW_ALPHA=0）——参考项目的水滴没有黑色内圈。 */
    private void drawDropInnerShadow(Canvas canvas, float left, float top,
                                     float w, float h, float radius, float p) {
        float alpha = DROP_INNER_SHADOW_ALPHA * p;
        if (alpha <= 0.01f || DROP_INNER_SHADOW_DP <= 0f || mInnerShader == null) {
            return;
        }
        float blur = DROP_INNER_SHADOW_DP * mDensity * p;
        mInnerShader.setFloatUniform("size", w, h);
        mInnerShader.setFloatUniform("radius", radius);
        mInnerShader.setFloatUniform("blur", blur);
        mInnerShader.setFloatUniform("alpha", alpha);
        mInnerShadowPaint.setShader(mInnerShader);
        int save = canvas.save();
        canvas.translate(left, top);
        canvas.drawRoundRect(0, 0, w, h, radius, radius, mInnerShadowPaint);
        canvas.restoreToCount(save);
    }

    // ---------------------------------------------------------------- 手势

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return super.onTouchEvent(event);
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // View 比胶囊大一圈（inset）：落在胶囊外的触摸不响应，透给下层内容
                if (x < mInsetX || y < mInsetY
                        || x > getWidth() - mInsetX || y > getHeight() - mInsetY) {
                    return false;
                }
                mTouchStartX = x;
                mTouchStartY = y;
                mIsDragging = false;
                mGestureActive = true;
                // 按下即按压：液滴放大 + 折射淡入（与拖动同一条路径）
                pressDroplet();
                startFrames();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - mTouchStartX;
                float dy = y - mTouchStartY;
                if (!mIsDragging
                        && (Math.abs(dx) > mDragThresholdPx
                        || Math.abs(dy) > mDragThresholdPx)) {
                    // 只有水平位移占主导时才进入拖动，避免纵向滚动误触
                    if (Math.abs(dx) > Math.abs(dy)) {
                        mIsDragging = true;
                        ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (mIsDragging) {
                    dragTo(x);
                }
                return true;

            case MotionEvent.ACTION_UP:
                int fired = -1;
                if (mIsDragging) {
                    int target = Math.round(mValue.target());
                    target = Math.max(0, Math.min(mItemCount - 1, target));
                    if (target != mSelectedIndex) {
                        mSelectedIndex = target;
                    }
                    settleTo(target);
                    fired = target;
                } else {
                    int clicked = indexFromX(x);
                    if (clicked >= 0 && clicked < mItemCount) {
                        // 点击也走 press → 移动 → release 的同一路径
                        mSelectedIndex = clicked;
                        settleTo(clicked);
                        fired = clicked;
                    }
                }
                releaseDroplet();
                mIsDragging = false;
                mGestureActive = false;
                if (fired >= 0 && mItemListener != null) {
                    mItemListener.onItemSelected(fired);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                settleTo(mSelectedIndex);
                releaseDroplet();
                mIsDragging = false;
                mGestureActive = false;
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private int indexFromX(float x) {
        int innerW = getWidth() - getPaddingLeft() - getPaddingRight();
        if (innerW <= 0) {
            return -1;
        }
        int idx = (int) ((x - getPaddingLeft()) / (innerW / (float) mItemCount));
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= mItemCount) {
            idx = mItemCount - 1;
        }
        return idx;
    }

    private void dragTo(float x) {
        int innerW = getWidth() - getPaddingLeft() - getPaddingRight();
        if (innerW <= 0) {
            return;
        }
        float itemW = innerW / (float) mItemCount;
        float pos = (x - getPaddingLeft()) / itemW - 0.5f;
        pos = Math.max(0f, Math.min(mItemCount - 1, pos));
        // 液滴由弹簧追踪目标（轻微滞后 = 液体的跟手感）
        mValue.animateTo(pos);
        // 参考项目行为：拖动过程只移动液滴，不切页；松手后才切换（见 ACTION_UP）
        startFrames();
    }

    private void settleTo(int target) {
        mValue.animateTo(target);
        mVelocity.animateTo(0f);
        startFrames();
    }

    private void pressDroplet() {
        mPress.animateTo(1f);
        mScaleX.animateTo(PRESS_SCALE);
        mScaleY.animateTo(PRESS_SCALE);
        startFrames();
    }

    /**
     * 松手收拢：等液滴接近目标位置后再回落缩放（HyperIsland 的 release() 语义）——
     * 这样甩动读起来是一个连贯动作，而不是「滑动 + 单独缩回」。
     */
    private void releaseDroplet() {
        mReleasePending = true;
        startFrames();
    }

    private float dropletCentreX() {
        int innerW = pillWidth() - contentPadLeft() - contentPadRight();
        if (innerW <= 0 || mItemCount <= 0) {
            return pillWidth() * 0.5f;
        }
        float itemW = innerW / (float) mItemCount;
        return contentPadLeft() + (mValue.value() + 0.5f) * itemW;
    }

    /** 液滴内标签副本的放大基数（参考项目「标签副本 lerp(1, 1.2, p)」，16dp/标签宽 ≈ 0.2）。 */
    private float tabZoomBase() {
        int innerW = pillWidth() - contentPadLeft() - contentPadRight();
        if (innerW <= 0 || mItemCount <= 0) {
            return TAB_ZOOM;
        }
        float itemW = innerW / (float) mItemCount;
        return itemW > 0f ? Math.min(0.35f, 16f * mDensity / itemW) : TAB_ZOOM;
    }

    /**
     * HyperIsland layerBlock 的整体放大：整个导航栏随按压放大 lerp(1, 1+16dp/宽, p)——
     * 是「整条导航栏变大」，图标作为整体的一部分随之等比放大，松手后回缩。
     * 以胶囊（pill）宽度为基准，view 中心 = 胶囊中心。
     */
    private void applyBarScale() {
        float p = mPress.value();
        float w = pillWidth();
        float base = w > 0f ? Math.min(0.2f, 16f * mDensity / w) : 0.1f;
        float s = 1f + base * p;
        if (Math.abs(getScaleX() - s) > 0.0005f
                || Math.abs(getScaleY() - s) > 0.0005f) {
            setScaleX(s);
            setScaleY(s);
        }
    }

    // ---------------------------------------------------------------- 动画循环

    private void startFrames() {
        if (mFramesRunning) {
            return;
        }
        mFramesRunning = true;
        mLastNs = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(mFrame);
    }

    private final Choreographer.FrameCallback mFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            float dt = Math.max(0f,
                    Math.min((frameTimeNanos - mLastNs) / 1_000_000_000f, 0.032f));
            mLastNs = frameTimeNanos;

            boolean alive;
            if (mIsDragging && dt > 0f) {
                // 拖动中：速度 = 位置变化率按标签数归一化（不同屏幕密度手感一致）
                float before = mValue.value();
                alive = mValue.update(dt);
                float measured = (mValue.value() - before) / dt
                        / Math.max(1, mItemCount - 1);
                mVelocity.animateTo(measured);
            } else {
                alive = mValue.update(dt);
            }
            alive |= mVelocity.update(dt);
            alive |= mPress.update(dt);
            alive |= mScaleX.update(dt);
            alive |= mScaleY.update(dt);

            if (mReleasePending && !mValue.isRunning()) {
                mReleasePending = false;
                mPress.animateTo(0f);
                mScaleX.animateTo(1f);
                mScaleY.animateTo(1f);
                alive = true;
            }

            applyBarScale();
            invalidate();
            if (alive) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                mFramesRunning = false;
            }
        }
    };

    // ---------------------------------------------------------------- 重力高光

    private void registerTiltSensor() {
        if (mSensorManager != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            mSensorManager = (SensorManager) getContext()
                    .getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager == null) {
                return;
            }
            mGravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (mGravitySensor == null) {
                mGravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (mGravitySensor != null) {
                mSensorManager.registerListener(mTiltListener, mGravitySensor,
                        SensorManager.SENSOR_DELAY_UI);
            }
        } catch (Throwable ignored) {
            mGravitySensor = null;
        }
    }

    private void unregisterTiltSensor() {
        if (mSensorManager != null) {
            try {
                mSensorManager.unregisterListener(mTiltListener);
            } catch (Throwable ignored) {
                // 个别 ROM 上注销会抛，忽略
            }
        }
        mSensorManager = null;
        mGravitySensor = null;
    }

    /**
     * 重力方向 → 高光主光方向（屏幕坐标，y 向下）。
     *
     * <p>重力传感器读数近似指向「上」；手机直立时主光即屏幕上方（顶边高光），
     * 倾斜设备时高光随之滑动——参考项目的「重力传感器高光」。
     */
    private final SensorEventListener mTiltListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float gx = event.values[0];
            float gy = event.values[1];
            float nx = gx;
            float ny = -gy;
            float len = (float) Math.sqrt(nx * nx + ny * ny);
            if (len > 1f) {
                nx /= len;
                ny /= len;
            } else {
                nx = 0f;
                ny = -1f;
            }
            // 低通，避免高光抖动
            float lx = mLightX + (nx - mLightX) * 0.15f;
            float ly = mLightY + (ny - mLightY) * 0.15f;
            if (Math.abs(lx - mLightX) > 0.004f || Math.abs(ly - mLightY) > 0.004f) {
                mLightX = lx;
                mLightY = ly;
                invalidate();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // 不关心
        }
    };

    // ---------------------------------------------------------------- 小工具

    /** 强调色：显式设置优先；否则从选中项标签的文字色推断；再不然用品牌蓝。 */
    private int accentColor() {
        if (mAccent != 0) {
            return mAccent;
        }
        if (mSelectedIndex >= 0 && mSelectedIndex < mItems.size()) {
            int c = firstLabelColor(mItems.get(mSelectedIndex), 0);
            if (c != 0 && !isNeutral(c)) {
                mAccent = c | 0xFF000000;
                return mAccent;
            }
        }
        return 0xFF00B0F0;
    }

    private static int firstLabelColor(View v, int depth) {
        if (depth > 4 || v == null || v.getVisibility() != VISIBLE) {
            return 0;
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            return tv.getCurrentTextColor() | 0xFF000000;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                int c = firstLabelColor(g.getChildAt(i), depth + 1);
                if (c != 0) {
                    return c;
                }
            }
        }
        return 0;
    }

    private static boolean isNeutral(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min < 24;
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
