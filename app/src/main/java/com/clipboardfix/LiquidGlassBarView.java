package com.clipboardfix;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * 底部悬浮「液态玻璃」导航条（零新依赖，纯 framework API）。
 *
 * <p>实现参照两个开源项目：
 * <ul>
 *   <li>liuran001/WeChat-LiquidGlass（Apache-2.0）：单 pass AGSL 透镜管线，圆角矩形 SDF 折射。</li>
 *   <li>1812z/HyperIsland：条高 64dp / 内部 56dp、饱和度 1.5 → 模糊 4dp → 折射 24dp、
 *       液滴弹簧 damping 1.0 / stiffness 1000、按压放大 78/56 ≈ 1.4x、支持左右拖动切页。</li>
 * </ul>
 *
 * <p>效果链：把背后内容录进 {@link RenderNode} → 饱和度提升 → 4dp 模糊 → 边缘折射透镜，
 * 再叠一层容器色垫底 + 1dp 边缘高光；选中项是一颗跟随弹簧滑动、按压/拖动时会被拉伸变形的「液滴」。
 *
 * <p>交互：点击 item 切换；按住液滴/选中区域左右拖动可连续滑动，松手后弹簧吸附到最近项。
 *
 * <p>分级降级：Android 13(API 33)+ 走完整 AGSL 透镜；API 31-32 只做模糊+饱和；
 * 更低版本或渲染异常时退化为半透明磨砂面，不会崩。
 */
public class LiquidGlassBarView extends FrameLayout {

    /** HyperIsland: lens(refractionHeight = 24.dp, refractionAmount = 24.dp)。 */
    private static final float REFRACTION_DP = 24f;
    /** HyperIsland: blur(4.dp, 4.dp)。 */
    private static final float BLUR_DP = 4f;
    /** HyperIsland: vibrancy() -> colorControls(saturation = 1.5f)。 */
    private static final float SATURATION = 1.5f;
    /** 按压时液滴放大倍率（HyperIsland 的 78/56）。 */
    private static final float PRESS_SCALE = 78f / 56f;
    /** 液滴按压时的透明度；静止态按深浅色在 applyTheme 里设置（浅色 18% / 深色 20%）。 */
    private static final int DROPLET_ALPHA_PRESSED = 0x4D;
    private int mDropletIdleAlpha = 0x2E;

    /** 判定为拖动所需的最小位移。 */
    private static final float DRAG_THRESHOLD_DP = 12f;

    /** 圆角矩形 SDF（Kyant0 的透镜实现，Apache-2.0，经 WeChat-LiquidGlass 转引）。 */
    private static final String SDF_SOURCE = ""
            + "float radiusAt(float2 coord, float4 radii) {\n"
            + "    if (coord.x >= 0.0) {\n"
            + "        if (coord.y <= 0.0) return radii.y; else return radii.z;\n"
            + "    } else {\n"
            + "        if (coord.y <= 0.0) return radii.x; else return radii.w;\n"
            + "    }\n"
            + "}\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n";

    private static final String LENS_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + SDF_SOURCE
            + "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) { return content.eval(coord); }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize,"
            + "            gradRadius) + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    return content.eval(refractedCoord);\n"
            + "}\n";

    public interface OnItemSelectedListener {
        void onItemSelected(int index);
    }

    private WeakReference<View> mBackdrop;
    private final float mDensity;
    private final int mPad;
    private final float mDragThresholdPx;

    private RenderNode mNode;
    private RuntimeShader mLens;
    private RenderEffect mChain;
    private RenderEffect mSaturate;
    private int mChainW;
    private int mChainH;
    private boolean mGlassOn = true;

    private final Paint mSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDropletPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDropletEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private final int[] mSelf = new int[2];
    private final int[] mSrc = new int[2];

    private int mBaseColor;
    private int mItemCount = 3;
    /** 当前选中项：按压取消（手指划走）时液滴要弹回这里。 */
    private int mSelectedIndex;

    /** 液滴位置（单位为「第几个 item」）与按压缩放的弹簧。 */
    private final Spring mPosSpring = new Spring(1.0f, 1000f);
    private final Spring mScaleSpring = new Spring(0.7f, 400f);
    private boolean mFramesRunning;
    private long mLastNs;

    /** 拖动/点击状态。 */
    private float mTouchStartX;
    private float mTouchStartY;
    private boolean mIsDragging;
    private boolean mIsPressed;
    private float mLastDragPos;
    private long mLastDragMs;
    private float mDragVelocity; // positions / s, used for droplet stretch
    private OnItemSelectedListener mItemListener;

    public LiquidGlassBarView(Context ctx) {
        this(ctx, null);
    }

    public LiquidGlassBarView(Context ctx, AttributeSet attrs) {
        this(ctx, attrs, 0);
    }

    public LiquidGlassBarView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        mDensity = getResources().getDisplayMetrics().density;
        // 透镜会采样自身边界之外的内容，所以背景要多录一圈折射量。
        mPad = Math.round(REFRACTION_DP * mDensity);
        mDragThresholdPx = DRAG_THRESHOLD_DP * mDensity;
        setWillNotDraw(false);
        applyTheme();
        prepareEffects();
        setupShadow();
        // 关键：缩放弹簧初值必须是 1——Spring 的 value 默认 0，会让液滴尺寸为 0，
        // 表现为「首次进入没有选中底衬，点一下（弹簧跑过一遍）才出现」。
        mScaleSpring.setValue(1f);
        mPosSpring.setValue(0f);
    }

    /**
     * 上游（HyperIsland/KernelSU）玻璃条的投影：radius 10dp 的黑色阴影。
     * 没有它，浅色模式下玻璃会和页面底色融为一体（用户实测反馈）。
     * 用 outline + elevation 让系统渲染阴影，圆角随胶囊形状。
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
                outline.setRoundRect(0, 0, w, h, h * 0.5f);
            }
        });
        setClipToOutline(false);
        setElevation(10f * mDensity);
    }

    /** 传入需要被折射的「背后内容」（一般是承载三个页面的容器）。 */
    public void setBackdrop(View backdrop) {
        mBackdrop = new WeakReference<>(backdrop);
        invalidate();
    }

    public void setItemCount(int count) {
        mItemCount = Math.max(1, count);
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        mItemListener = listener;
    }

    /**
     * 让某个 item 的按压状态驱动液滴：按下时液滴立刻弹簧滑到该项并放大（这就是按压反馈，
     * 不再额外画波纹——玻璃条里任何圆形/矩形高亮都会显脏）；抬起只是回弹尺寸；
     * 手指划走(取消)则弹回当前选中项。
     *
     * <p>新版（v1.4.8+）由 {@link #setOnItemSelectedListener} + 全局触摸处理接管切页，
     * 本方法保留仅作兼容：仍可用来给子 View 加按下反馈，但不会触发页面切换。
     */
    public void attachPress(View item, int index) {
        item.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mPosSpring.setTarget(index);
                    setPressedState(true);
                    break;
                case MotionEvent.ACTION_UP:
                    setPressedState(false);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    mPosSpring.setTarget(mSelectedIndex);
                    setPressedState(false);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    public void setPressedState(boolean pressed) {
        mScaleSpring.setTarget(pressed ? PRESS_SCALE : 1f);
        startFrames();
    }

    /** 切页：液滴以弹簧滑动到目标位置。 */
    public void setSelectedIndex(int index, boolean animate) {
        mSelectedIndex = index;
        if (!animate) {
            mPosSpring.setValue(index);
            applyDroplet();
            return;
        }
        mPosSpring.setTarget(index);
        startFrames();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return super.onTouchEvent(event);
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchStartX = x;
                mTouchStartY = y;
                mIsDragging = false;
                mIsPressed = true;
                mLastDragPos = mPosSpring.getValue();
                mLastDragMs = SystemClock.uptimeMillis();
                mDragVelocity = 0f;
                setPressedState(true);
                startFrames();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - mTouchStartX;
                float dy = y - mTouchStartY;
                if (!mIsDragging
                        && (Math.abs(dx) > mDragThresholdPx || Math.abs(dy) > mDragThresholdPx)) {
                    // 只有在水平位移占主导时才进入拖动，避免纵向滚动误触。
                    if (Math.abs(dx) > Math.abs(dy)) {
                        mIsDragging = true;
                        ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (mIsDragging) {
                    updateDragPosition(x);
                }
                return true;

            case MotionEvent.ACTION_UP:
                mIsPressed = false;
                setPressedState(false);
                if (mIsDragging) {
                    finishDrag();
                } else {
                    int clicked = indexFromX(x);
                    if (clicked >= 0 && clicked < mItemCount) {
                        if (clicked != mSelectedIndex) {
                            mSelectedIndex = clicked;
                            mPosSpring.setTarget(clicked);
                            startFrames();
                        }
                        if (mItemListener != null) {
                            mItemListener.onItemSelected(clicked);
                        }
                    }
                }
                mIsDragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                mIsPressed = false;
                setPressedState(false);
                if (mIsDragging) {
                    mPosSpring.setTarget(mSelectedIndex);
                    startFrames();
                }
                mIsDragging = false;
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
        if (idx < 0) idx = 0;
        if (idx >= mItemCount) idx = mItemCount - 1;
        return idx;
    }

    private void updateDragPosition(float x) {
        int innerW = getWidth() - getPaddingLeft() - getPaddingRight();
        if (innerW <= 0) {
            return;
        }
        float itemW = innerW / (float) mItemCount;
        float pos = (x - getPaddingLeft()) / itemW - 0.5f;
        pos = Math.max(-0.5f, Math.min(mItemCount - 0.5f, pos));

        long now = SystemClock.uptimeMillis();
        float dt = (now - mLastDragMs) / 1000f;
        if (dt > 0f && dt < 0.2f) {
            float rawVel = (pos - mLastDragPos) / dt;
            mDragVelocity = mDragVelocity * 0.5f + rawVel * 0.5f;
        }
        mLastDragPos = pos;
        mLastDragMs = now;

        mPosSpring.setValue(pos);
        applyDroplet();
    }

    private void finishDrag() {
        int innerW = getWidth() - getPaddingLeft() - getPaddingRight();
        if (innerW <= 0) {
            return;
        }
        float currentPos = mPosSpring.getValue();
        int target = Math.round(currentPos);
        target = Math.max(0, Math.min(mItemCount - 1, target));
        mSelectedIndex = target;
        mPosSpring.setTarget(target);
        startFrames();
        if (mItemListener != null) {
            mItemListener.onItemSelected(target);
        }
        mDragVelocity = 0f;
    }

    private void applyTheme() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = night == Configuration.UI_MODE_NIGHT_YES;
        mBaseColor = dark ? 0xFF111111 : 0xFFF7F7F7;
        // HyperIsland: containerColor = surfaceContainer.copy(0.4f)。
        // surfaceContainer 比页面背景深一档（浅色 ECEDEF / 深色 2C2C2E），
        // 否则浅色模式下玻璃与底色同色，条会隐形。
        mSurfacePaint.setColor(dark ? 0x662C2C2E : 0x66ECEDEF);
        // 去掉导航条外描边，整体更干净（参考文件管理器底部胶囊）。
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(mDensity);
        mStrokePaint.setColor(0x00000000);
        // 液滴：中性半透明色块——浅色 15% 黑 / 深色 18% 白。比原先 18% 略浅一点，
        // 但在浅色玻璃上仍清晰可辨（不能做成近白色，否则与胶囊底色融为一体看不见）。
        mDropletIdleAlpha = dark ? 0x2E : 0x26;
        mDropletPaint.setColor(dark ? 0xFFFFFFFF : 0xFF000000);
        mDropletPaint.setAlpha(mDropletIdleAlpha);
        // 水滴边缘线去掉，避免选中项出现明显描边。
        mDropletEdge.setStyle(Paint.Style.STROKE);
        mDropletEdge.setStrokeWidth(mDensity);
        mDropletEdge.setColor(0x00000000);
    }

    private void prepareEffects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mGlassOn = false;
            return;
        }
        try {
            mNode = new RenderNode("cfLiquidGlass");
        } catch (Throwable t) {
            mGlassOn = false;
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(SATURATION);
                mSaturate = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
            } catch (Throwable t) {
                mSaturate = null;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                mLens = new RuntimeShader(LENS_SHADER);
            } catch (Throwable t) {
                mLens = null;
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mClip.reset();
        float r = h * 0.5f;
        mClip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        mChain = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        drawGlass(canvas, w, h);
        // 容器色垫底（承载可读性），再画液滴，最后 1dp 边缘高光
        float r = h * 0.5f;
        canvas.drawRoundRect(0, 0, w, h, r, r, mSurfacePaint);
        drawDroplet(canvas, w, h);
        float half = mStrokePaint.getStrokeWidth() * 0.5f;
        canvas.drawRoundRect(half, half, w - half, h - half, r - half, r - half, mStrokePaint);
    }

    private void drawDroplet(Canvas canvas, int w, int h) {
        int innerW = w - getPaddingLeft() - getPaddingRight();
        int innerH = h - getPaddingTop() - getPaddingBottom();
        if (innerW <= 0 || innerH <= 0) {
            return;
        }
        float itemW = innerW / (float) mItemCount;
        float scale = mScaleSpring.getValue();
        // 按压进度 0..1 → 液滴同步加深，浅色模式下也有明确的按下反馈
        float press = (scale - 1f) / (PRESS_SCALE - 1f);
        if (press < 0f) {
            press = 0f;
        } else if (press > 1f) {
            press = 1f;
        }
        mDropletPaint.setAlpha(Math.round(
                mDropletIdleAlpha + (DROPLET_ALPHA_PRESSED - mDropletIdleAlpha) * press));

        // HyperIsland 式速度形变：根据拖动速度在 X/Y 方向上拉伸/挤压液滴。
        float v = Math.max(-0.2f, Math.min(0.2f, mDragVelocity * 0.05f));
        float scaleX = scale / (1f - v * 0.75f);
        float scaleY = scale * (1f - v * 0.25f);

        // 玻璃本体已按胶囊 clip；液滴按压缩放后会超出胶囊，同样必须裁进去，
        // 否则按压时液滴"溢出"胶囊边缘（用户实测反馈的深色按下异常）。
        canvas.save();
        canvas.clipPath(mClip);
        float cx = getPaddingLeft() + (mPosSpring.getValue() + 0.5f) * itemW;
        float cy = getPaddingTop() + innerH * 0.5f;
        float halfW = itemW * 0.5f * scaleX;
        float halfH = innerH * 0.5f * scaleY;
        float dr = Math.min(halfW, halfH);
        canvas.drawRoundRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, dr, dr, mDropletPaint);
        float eh = mDropletEdge.getStrokeWidth() * 0.5f;
        canvas.drawRoundRect(cx - halfW + eh, cy - halfH + eh, cx + halfW - eh, cy + halfH - eh,
                dr - eh, dr - eh, mDropletEdge);
        canvas.restore();
    }

    private void drawGlass(Canvas canvas, int w, int h) {
        if (!mGlassOn || mNode == null || !canvas.isHardwareAccelerated()) {
            return;
        }
        View backdrop = mBackdrop == null ? null : mBackdrop.get();
        if (backdrop == null || backdrop.getWidth() <= 0 || backdrop.getHeight() <= 0) {
            return;
        }
        int nw = w + mPad * 2;
        int nh = h + mPad * 2;
        try {
            mNode.setPosition(0, 0, nw, nh);
            RecordingCanvas rc = mNode.beginRecording(nw, nh);
            try {
                // 先铺底色：没被内容覆盖的区域若留透明，进模糊后会变成纯黑。
                rc.drawColor(mBaseColor);
                getLocationOnScreen(mSelf);
                backdrop.getLocationOnScreen(mSrc);
                rc.translate(mPad - (mSelf[0] - mSrc[0]), mPad - (mSelf[1] - mSrc[1]));
                backdrop.draw(rc);
            } finally {
                mNode.endRecording();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (mChain == null || mChainW != w || mChainH != h) {
                    float blur = BLUR_DP * mDensity;
                    RenderEffect blurEffect = mSaturate == null
                            ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                            : RenderEffect.createBlurEffect(blur, blur, mSaturate,
                                    Shader.TileMode.CLAMP);
                    if (mLens != null) {
                        float radius = h * 0.5f;
                        mLens.setFloatUniform("size", w, h);
                        mLens.setFloatUniform("offset", -mPad, -mPad);
                        mLens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
                        mLens.setFloatUniform("refractionHeight", REFRACTION_DP * mDensity);
                        // HyperIsland 传入的是负值
                        mLens.setFloatUniform("refractionAmount", -REFRACTION_DP * mDensity);
                        mLens.setFloatUniform("depthEffect", 0f);
                        mChain = RenderEffect.createChainEffect(
                                RenderEffect.createRuntimeShaderEffect(mLens, "content"),
                                blurEffect);
                    } else {
                        mChain = blurEffect;
                    }
                    mChainW = w;
                    mChainH = h;
                }
                mNode.setRenderEffect(mChain);
            }

            canvas.save();
            canvas.clipPath(mClip);
            canvas.translate(-mPad, -mPad);
            canvas.drawRenderNode(mNode);
            canvas.restore();
        } catch (Throwable t) {
            // 渲染链一旦失败就永久退化为磨砂面，避免每帧抛异常
            mGlassOn = false;
            invalidate();
        }
    }

    private void applyDroplet() {
        invalidate();
    }

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
            long now = frameTimeNanos;
            float dt = Math.min((now - mLastNs) / 1_000_000_000f, 0.032f);
            mLastNs = now;
            boolean alive = mPosSpring.step(dt) | mScaleSpring.step(dt);
            applyDroplet();
            if (alive) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                mFramesRunning = false;
            }
        }
    };

    /** 阻尼弹簧（HyperIsland 用的是 dampingRatio/stiffness 同一组参数）。 */
    private static final class Spring {
        private final float damping;
        private final float stiffness;
        private float value;
        private float target;
        private float velocity;

        Spring(float damping, float stiffness) {
            this.damping = damping;
            this.stiffness = stiffness;
        }

        void setValue(float v) {
            value = v;
            target = v;
            velocity = 0f;
        }

        void setTarget(float v) {
            target = v;
        }

        void setVelocity(float v) {
            velocity = v;
        }

        float getValue() {
            return value;
        }

        float getTarget() {
            return target;
        }

        boolean step(float dt) {
            float c = 2f * damping * (float) Math.sqrt(stiffness);
            float force = -stiffness * (value - target) - c * velocity;
            velocity += force * dt;
            value += velocity * dt;
            if (Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.02f) {
                value = target;
                velocity = 0f;
                return false;
            }
            return true;
        }
    }
}
