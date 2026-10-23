package com.clipboardfix

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RecordingCanvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * 底部悬浮「液态玻璃」导航条（零新依赖，纯 framework API）。
 *
 * <p>效果与参数完整对照两个参考项目：
 * <ul>
 *   <li><b>liuran001/WeChat-LiquidGlass</b>（MIT）：单 pass AGSL 透镜管线、圆角矩形 SDF 折射、
 *       液滴色散/内阴影、标签副本 CombinedBackdrop、弹簧物理。</li>
 *   <li><b>1812z/HyperIsland</b>（Apache-2.0）：条高 64dp / 内高 56dp、item 最小 76dp、
 *       vibrancy 饱和 1.5 → blur 4dp → lens 24dp、液滴 lens(10dp·p, 14dp·p, 景深 + 色散 0.5)、
 *       按压放大 78/56、标签副本 lerp(1, 1.2, p)。</li>
 * </ul>
 *
 * <p><b>玻璃条</b>：把背后内容录进 RenderNode → 饱和度提升 → 4dp 模糊 → 边缘折射透镜，
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
class LiquidGlassBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    // ===== 背景与内容 =====
    private var mBackdrop: WeakReference<View>? = null
    private val mItems: MutableList<View> = ArrayList(4)
    private var mItemCount = 3
    private var mAccent = 0
    private var mNight = false

    // ===== 尺寸 =====
    private val mDensity: Float
    private val mGlassPad: Int
    private val mDropPad: Int
    private val mDragThresholdPx: Float
    /** View 边界相对胶囊的内缩量（px）：胶囊 = View 边界内缩 inset 后的矩形。 */
    private val mInsetX: Int
    private val mInsetY: Int

    // ===== 渲染对象 =====
    private var mGlassNode: RenderNode? = null
    private var mDropPageNode: RenderNode? = null
    private var mDropNode: RenderNode? = null
    private var mGlassLens: RuntimeShader? = null
    private var mDropLens: RuntimeShader? = null
    private var mInnerShader: RuntimeShader? = null
    private var mRimShader: RuntimeShader? = null
    private var mBloomShader: RuntimeShader? = null
    private var mSaturate: RenderEffect? = null
    private var mBlurEffect: RenderEffect? = null
    private var mChain: RenderEffect? = null
    private var mChainW = 0
    private var mChainH = 0
    private var mGlassOn = true

    // ===== 画笔 =====
    private val mSurfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mDropWashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPressTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mInnerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mRimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mWashPlusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBarClip = Path()
    private val mDropClip = Path()
    private val mSelf = IntArray(2)
    private val mSrc = IntArray(2)

    private var mBaseColor = 0

    // ===== 弹簧（HyperIsland DampedDragAnimation 的五条，参数照搬）=====
    // 刚度按实机手感上调：位置 1000→1800、按压 1000→1600、缩放 250→420，
    // 切页的水滴滑动明显更跟手（阻尼比保持原值，仍为欠阻尼/临界的液态手感）。
    private val mValue = Spring(1.0f, 1800f, 0.001f, 0f)
    private val mVelocity = Spring(0.5f, 300f, 0.01f, 0f)
    private val mPress = Spring(1.0f, 1600f, 0.001f, 0f)
    private val mScaleX = Spring(0.6f, 420f, 0.001f, 1f)
    private val mScaleY = Spring(0.7f, 420f, 0.001f, 1f)
    private var mFramesRunning = false
    private var mLastNs = 0L
    private var mReleasePending = false

    // ===== 手势状态 =====
    private var mSelectedIndex = 0
    private var mGestureActive = false
    private var mIsDragging = false
    private var mTouchStartX = 0f
    private var mTouchStartY = 0f
    private var mItemListener: OnItemSelectedListener? = null

    // ===== 重力高光 =====
    private var mSensorManager: SensorManager? = null
    private var mGravitySensor: Sensor? = null
    private var mLightX = 0f
    private var mLightY = -1f

    // ===== 背景实时跟随 =====
    private val mScrollListener = ViewTreeObserver.OnScrollChangedListener { invalidateBackdrop() }
    private val mLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { invalidateBackdrop() }
    private var mBgListenersAttached = false

    /**
     * 背景（折射源）是否需要重录。动画期间每帧都重录整个内容区是掉帧的元凶，
     * 只有背景真的变了（滚动/布局/切页/尺寸/主题）才重录。
     */
    private var mBackdropDirty = true

    /** 背景内容变了：标记重录并重绘（比 invalidate 更贵但必要）。 */
    private fun invalidateBackdrop() {
        mBackdropDirty = true
        invalidate()
    }

    init {
        mDensity = resources.displayMetrics.density
        // 透镜会采样自身边界之外的内容，所以背景要多录一圈折射量
        mGlassPad = Math.round(GLASS_REFRACTION_DP * mDensity)
        mDropPad = Math.round((DROP_AMOUNT_DP + 4f) * mDensity)
        mDragThresholdPx = DRAG_THRESHOLD_DP * mDensity
        mInsetX = Math.round(BAR_INSET_DP * mDensity)
        mInsetY = Math.round(BAR_INSET_DP * mDensity)
        setWillNotDraw(false)
        applyTheme()
        prepareEffects()
        setupShadow()
    }

    /**
     * 上游（HyperIsland/KernelSU）玻璃条的投影：radius 10dp 的黑色阴影。
     * 没有它，浅色模式下玻璃会和页面底色融为一体（用户实测反馈）。
     */
    private fun setupShadow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                if (w <= 0 || h <= 0) {
                    return
                }
                // 阴影跟随胶囊而不是放大的 View 边界
                val pw = w - 2 * mInsetX
                val ph = h - 2 * mInsetY
                if (pw <= 0 || ph <= 0) {
                    return
                }
                outline.setRoundRect(mInsetX, mInsetY, mInsetX + pw, mInsetY + ph, ph * 0.5f)
            }
        }
        clipToOutline = false
        elevation = 10f * mDensity
    }

    // ---------------------------------------------------------------- 公开 API

    /** 传入需要被折射的「背后内容」（一般是承载各页面的容器）。 */
    fun setBackdrop(backdrop: View?) {
        detachBackgroundListeners()
        mBackdrop = WeakReference(backdrop)
        attachBackgroundListeners()
        invalidateBackdrop()
    }

    private fun attachBackgroundListeners() {
        if (mBgListenersAttached || mBackdrop == null) {
            return
        }
        val observer = viewObserver()
        if (observer != null) {
            // 背景实时跟随：内容滚动/布局变化时重录折射背景
            observer.addOnScrollChangedListener(mScrollListener)
            observer.addOnGlobalLayoutListener(mLayoutListener)
            mBgListenersAttached = true
        }
    }

    private fun detachBackgroundListeners() {
        if (!mBgListenersAttached) {
            return
        }
        val observer = viewObserver()
        if (observer != null) {
            observer.removeOnScrollChangedListener(mScrollListener)
            observer.removeOnGlobalLayoutListener(mLayoutListener)
        }
        mBgListenersAttached = false
    }

    /** 显式指定各标签 View（用于液滴里的 1.2× 标签副本）；不调用时自动从子 View 发现。 */
    fun setItems(vararg items: View?) {
        mItems.clear()
        for (v in items) {
            if (v != null) {
                mItems.add(v)
            }
        }
        if (mItems.isNotEmpty()) {
            mItemCount = mItems.size
        }
        invalidate()
    }

    fun setItemCount(count: Int) {
        mItemCount = Math.max(1, count)
    }

    /** 液滴里标签副本的染色（即「选中色」，HyperIsland 的 LocalContentColor = accentColor）。 */
    fun setAccentColor(color: Int) {
        mAccent = color or 0xFF000000.toInt()
        invalidate()
    }

    fun setOnItemSelectedListener(listener: OnItemSelectedListener?) {
        mItemListener = listener
    }

    /**
     * 外部切页：液滴以弹簧滑动到目标位置。
     *
     * <p>手势进行中只记录选中项——此时液滴位置由手势驱动，避免两套动画打架。
     */
    fun setSelectedIndex(index: Int, animate: Boolean) {
        var index = index
        index = Math.max(0, Math.min(mItemCount - 1, index))
        mSelectedIndex = index
        if (mGestureActive) {
            return
        }
        if (!animate) {
            mValue.snapTo(index.toFloat())
            mVelocity.snapTo(0f)
            mPress.snapTo(0f)
            mScaleX.snapTo(1f)
            mScaleY.snapTo(1f)
            applyBarScale()
            // 切页 = 背后内容变了，必须重录折射背景
            invalidateBackdrop()
            return
        }
        mValue.animateTo(index.toFloat())
        mVelocity.animateTo(0f)
        releaseDroplet()
        // 切页 = 背后内容变了，必须重录
        mBackdropDirty = true
        startFrames()
    }

    /**
     * 兼容保留：给子 View 加按下反馈（不再触发页面切换）。
     */
    fun attachPress(item: View, index: Int) {
        item.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressDroplet()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseDroplet()
                else -> {}
            }
            false
        }
    }

    /** 兼容保留：直接驱动液滴的按压/收拢。 */
    fun setPressedState(pressed: Boolean) {
        if (pressed) {
            pressDroplet()
        } else {
            releaseDroplet()
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachBackgroundListeners()
        registerTiltSensor()
    }

    override fun onDetachedFromWindow() {
        unregisterTiltSensor()
        detachBackgroundListeners()
        super.onDetachedFromWindow()
    }

    private fun viewObserver(): ViewTreeObserver? {
        val backdrop = mBackdrop?.get()
        val anchor = backdrop ?: this
        val observer = anchor.viewTreeObserver
        return if (observer != null && observer.isAlive) observer else null
    }

    // ---------------------------------------------------------------- 主题

    private fun applyTheme() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        mNight = night == Configuration.UI_MODE_NIGHT_YES
        mBaseColor = if (mNight) 0xFF111111.toInt() else 0xFFF7F7F7.toInt()
        // 再调透：浅色白 18% / 深色容器 15%，以「能看清背后内容」为准
        mSurfacePaint.color = if (mNight) 0x262C2C2E else 0x2EFFFFFF
        // 液滴静止色调（10% 档），按压时按 press 进度淡出
        mDropWashPaint.color = if (mNight) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        mDropWashPaint.alpha = if (mNight) DROP_WASH_DARK else DROP_WASH_LIGHT
        mPressTintPaint.color = 0xFF000000.toInt()
        mPressTintPaint.alpha = 0
        mInnerShadowPaint.style = Paint.Style.FILL
        mWashPlusPaint.color = 0xFFFFFFFF.toInt()
        mWashPlusPaint.xfermode = PLUS
        mBloomPaint.xfermode = PLUS
        mRimPaint.xfermode = PLUS
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        mAccent = 0
        // 主题变了：录进玻璃的底色也要换，必须重录
        invalidateBackdrop()
    }

    // ---------------------------------------------------------------- 渲染准备

    private fun prepareEffects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mGlassOn = false
            return
        }
        try {
            mGlassNode = RenderNode("cfGlassBar")
            mDropPageNode = RenderNode("cfDropPage")
            mDropNode = RenderNode("cfDrop")
        } catch (t: Throwable) {
            mGlassOn = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cm = ColorMatrix()
                cm.setSaturation(GLASS_SATURATION)
                mSaturate = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm))
                val sat = mSaturate
                val blur = GLASS_BLUR_DP * mDensity
                mBlurEffect = if (sat == null)
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                else
                    RenderEffect.createBlurEffect(blur, blur, sat, Shader.TileMode.CLAMP)
            } catch (t: Throwable) {
                mSaturate = null
                mBlurEffect = null
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                mGlassLens = RuntimeShader(GlassShaders.LENS)
                mDropLens = RuntimeShader(GlassShaders.LENS_DISPERSION)
                mInnerShader = RuntimeShader(GlassShaders.INNER_SHADOW)
                mRimShader = RuntimeShader(GlassShaders.RIM_HIGHLIGHT)
                mBloomShader = RuntimeShader(GlassShaders.BLOOM)
            } catch (t: Throwable) {
                mGlassLens = null
                mDropLens = null
                mInnerShader = null
                mRimShader = null
                mBloomShader = null
            }
        }
    }

    // ---------------------------------------------------------------- 尺寸/布局

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBarClip.reset()
        val pw = w - 2 * mInsetX
        val ph = h - 2 * mInsetY
        val r = ph * 0.5f
        // 胶囊坐标系（View 内缩 inset 后），所有绘制都在这个坐标系里进行
        mBarClip.addRoundRect(0f, 0f, pw.toFloat(), ph.toFloat(), r, r, Path.Direction.CW)
        mChain = null
        mBackdropDirty = true
        discoverItems()
    }

    /** 胶囊宽度/高度 = View 尺寸 - 2×inset。 */
    private fun pillWidth(): Int {
        return width - 2 * mInsetX
    }

    private fun pillHeight(): Int {
        return height - 2 * mInsetY
    }

    /**
     * 内容边距（不含 inset）。触摸侧（View 坐标）用 padding；
     * 绘制侧（胶囊坐标）用这组——XML 的 padding = 4dp 内容 + 16dp inset。
     */
    private fun contentPadLeft(): Int {
        return paddingLeft - mInsetX
    }

    private fun contentPadRight(): Int {
        return paddingRight - mInsetX
    }

    private fun contentPadTop(): Int {
        return paddingTop - mInsetY
    }

    private fun contentPadBottom(): Int {
        return paddingBottom - mInsetY
    }

    /** 未显式 setItems 时，从「第一个子 ViewGroup 的子 View」推断标签列表。 */
    private fun discoverItems() {
        if (mItems.isNotEmpty()) {
            return
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    mItems.add(child.getChildAt(j))
                }
                break
            }
        }
        if (mItems.isNotEmpty()) {
            mItemCount = mItems.size
        }
    }

    // ---------------------------------------------------------------- 绘制：玻璃条

    override fun onDraw(canvas: Canvas) {
        val pw = pillWidth()
        val ph = pillHeight()
        if (pw <= 0 || ph <= 0) {
            return
        }
        // 之后所有绘制都在「胶囊坐标系」里（View 内缩 inset 后的矩形）
        canvas.save()
        canvas.translate(mInsetX.toFloat(), mInsetY.toFloat())
        val r = ph * 0.5f
        drawGlass(canvas, pw, ph)
        // 容器色垫底（承载可读性）
        canvas.drawRoundRect(0f, 0f, pw.toFloat(), ph.toFloat(), r, r, mSurfacePaint)
        drawInteractiveHighlight(canvas, pw, ph)
        drawRimHighlight(canvas, pw, ph, r, GLASS_RIM_ALPHA)
        canvas.restore()
    }

    private fun drawGlass(canvas: Canvas, w: Int, h: Int) {
        val node = mGlassNode ?: return
        if (!mGlassOn || !canvas.isHardwareAccelerated) {
            return
        }
        val backdrop = mBackdrop?.get() ?: return
        if (backdrop.width <= 0 || backdrop.height <= 0) {
            return
        }
        val nw = w + mGlassPad * 2
        val nh = h + mGlassPad * 2
        try {
            // 只有背景真的变了才重录（滚动/布局/切页/尺寸变化时置脏）。
            // 动画期间每帧重录整个内容区是「切页反应慢」的主因。
            if (mBackdropDirty) {
                node.setPosition(0, 0, nw, nh)
                val rc = node.beginRecording(nw, nh)
                try {
                    // 先铺底色：没被内容覆盖的区域若留透明，进模糊后会变成纯黑
                    rc.drawColor(mBaseColor)
                    getLocationOnScreen(mSelf)
                    backdrop.getLocationOnScreen(mSrc)
                    // 胶囊左上角在屏幕上位于 self + inset（View 比胶囊大一圈）
                    rc.translate(
                        (mGlassPad - (mSelf[0] - mSrc[0]) - mInsetX).toFloat(),
                        (mGlassPad - (mSelf[1] - mSrc[1]) - mInsetY).toFloat()
                    )
                    backdrop.draw(rc)
                } finally {
                    node.endRecording()
                }
                mBackdropDirty = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (mChain == null || mChainW != w || mChainH != h) {
                    val lens = mGlassLens
                    val blurEffect = mBlurEffect
                    if (lens != null && blurEffect != null) {
                        val radius = h * 0.5f
                        lens.setFloatUniform("size", w.toFloat(), h.toFloat())
                        lens.setFloatUniform("offset", -mGlassPad.toFloat(), -mGlassPad.toFloat())
                        lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                        lens.setFloatUniform("refractionHeight", GLASS_REFRACTION_DP * mDensity)
                        // HyperIsland 传入的是负值；depthEffect=1 让中心产生透镜凸起，
                        // 这是从「磨砂胶囊」进阶到「液态玻璃」的关键。
                        lens.setFloatUniform("refractionAmount", -GLASS_REFRACTION_DP * mDensity)
                        lens.setFloatUniform("depthEffect", 1f)
                        mChain = RenderEffect.createChainEffect(
                            RenderEffect.createRuntimeShaderEffect(lens, "content"),
                            blurEffect
                        )
                    } else {
                        mChain = blurEffect
                    }
                    mChainW = w
                    mChainH = h
                }
                mChain?.let { node.setRenderEffect(it) }
            }

            canvas.save()
            canvas.clipPath(mBarClip)
            canvas.translate(-mGlassPad.toFloat(), -mGlassPad.toFloat())
            canvas.drawRenderNode(node)
            canvas.restore()
        } catch (t: Throwable) {
            // 渲染链一旦失败就永久退化，避免每帧抛异常
            mGlassOn = false
            invalidate()
        }
    }

    /**
     * KernelSU/HyperIsland 的 InteractiveHighlight：白色 wash + 跟随液滴的径向 bloom，
     * PLUS 混合，按压进度淡入——「手指压住玻璃时的聚光」。
     */
    private fun drawInteractiveHighlight(canvas: Canvas, w: Int, h: Int) {
        val bloom = mBloomShader ?: return
        val p = mPress.value()
        if (p <= 0.01f) {
            return
        }
        val save = canvas.save()
        canvas.clipPath(mBarClip)
        // 按压辉光压到很轻：参考项目按压的读感来自「整条放大 + 液滴溢出」，
        // 而不是整条被刷白（原 6%/12% 会明显泛白）。
        mWashPlusPaint.alpha = Math.round(0x06 * p)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mWashPlusPaint)
        // 径向 bloom：White @ 6% × p，radius = min(size) × 1.2，跟随液滴位置
        bloom.setFloatUniform("size", w.toFloat(), h.toFloat())
        bloom.setFloatUniform("alpha", 0.06f * p)
        bloom.setFloatUniform("radius", Math.min(w.toFloat(), h.toFloat()) * 1.2f)
        val cx = dropletCentreX()
        bloom.setFloatUniform("position", Math.max(0f, Math.min(cx, w.toFloat())), h * 0.5f)
        mBloomPaint.shader = bloom
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mBloomPaint)
        canvas.restoreToCount(save)
    }

    /**
     * 边缘镜面高光（HyperIsland IndicatorSpecular / BloomStroke 的近似）：
     * 顶边主光 + 底边副光的双高点白色辉光，主光方向跟随重力传感器滑动。
     */
    private fun drawRimHighlight(canvas: Canvas, w: Int, h: Int, radius: Float, alpha: Float) {
        paintRim(canvas, w.toFloat(), h.toFloat(), radius, alpha)
    }

    /** 液滴的镜面高光：同一条 shader，平移到液滴局部坐标再画（shader 按绘制坐标采样）。 */
    private fun drawDropRim(canvas: Canvas, left: Float, top: Float,
                            w: Float, h: Float, radius: Float, alpha: Float) {
        val save = canvas.save()
        canvas.translate(left, top)
        paintRim(canvas, w, h, radius, alpha)
        canvas.restoreToCount(save)
    }

    private fun paintRim(canvas: Canvas, w: Float, h: Float, radius: Float, alpha: Float) {
        val rim = mRimShader ?: return
        if (alpha <= 0.01f) {
            return
        }
        rim.setFloatUniform("size", w, h)
        rim.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        rim.setFloatUniform("light1", mLightX, mLightY)
        rim.setFloatUniform("light2", -mLightX, -mLightY)
        // 1dp 描边 + 2dp 内辉光 ≈ 3dp 的衰减带
        rim.setFloatUniform("blur", 3f * mDensity)
        rim.setFloatUniform("alpha", alpha)
        mRimPaint.shader = rim
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, mRimPaint)
    }

    // ---------------------------------------------------------------- 绘制：液滴

    /**
     * 液滴画在真实标签<b>之上</b>：它折射的是「页面 + 玻璃材质 + 一份 1.2× 标签副本」，
     * 拖动时看到的放大图标正是这份副本经折射后的样子。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // 液滴在胶囊坐标系里画（子 View 布局仍按 View 坐标，由 padding 含 inset 定位）
        canvas.save()
        canvas.translate(mInsetX.toFloat(), mInsetY.toFloat())
        drawDroplet(canvas, pillWidth(), pillHeight())
        canvas.restore()
    }

    private fun drawDroplet(canvas: Canvas, w: Int, h: Int) {
        val innerW = w - contentPadLeft() - contentPadRight()
        val innerH = h - contentPadTop() - contentPadBottom()
        if (innerW <= 0 || innerH <= 0 || mItemCount <= 0) {
            return
        }
        discoverItems()
        val itemW = innerW / mItemCount.toFloat()
        // 速度形变：HyperIsland 的原样公式（速度按标签数归一化后除 10）
        val v = mVelocity.value() / STRETCH_DIVISOR
        val c1 = clamp(v * STRETCH_X, STRETCH_LIMIT)
        val c2 = clamp(v * STRETCH_Y, STRETCH_LIMIT)
        val scaleX = mScaleX.value() / (1f - c1)
        val scaleY = mScaleY.value() * (1f - c2)
        val halfW = itemW * 0.5f * scaleX
        val halfH = innerH * 0.5f * scaleY
        val cx = contentPadLeft() + (mValue.value() + 0.5f) * itemW
        val cy = contentPadTop() + innerH * 0.5f
        val left = cx - halfW
        val top = cy - halfH
        val right = cx + halfW
        val bottom = cy + halfH
        val radius = Math.min(halfW, halfH)
        val p = mPress.value()

        var drewGlass = false
        if (mGlassOn && canvas.isHardwareAccelerated) {
            try {
                drewGlass = drawDropGlass(canvas, left, top, right, bottom, radius, p)
            } catch (t: Throwable) {
                mGlassOn = false
            }
        }
        if (!drewGlass) {
            drawDropFallback(canvas, left, top, right, bottom, radius, p)
        }
        if (p > 0f) {
            // 镜面高光：与整条同步压到最小；内阴影保持禁用（参考项目无黑边）
            drawDropRim(canvas, left, top, right - left, bottom - top, radius, 0.12f * p)
            drawDropInnerShadow(canvas, left, top, right - left, bottom - top, radius, p)
        }
    }

    /** 液滴的完整玻璃管线：模糊页面 + 玻璃材质 + 表面色调 + 1.2× 标签副本 → 折射透镜。 */
    private fun drawDropGlass(canvas: Canvas, left: Float, top: Float,
                              right: Float, bottom: Float, radius: Float, p: Float): Boolean {
        val pageNode = mDropPageNode ?: return false
        val node = mDropNode ?: return false
        val dropLens = mDropLens
        val pw = Math.round(right - left)
        val ph = Math.round(bottom - top)
        if (pw <= 0 || ph <= 0) {
            return false
        }
        val nw = pw + mDropPad * 2
        val nh = ph + mDropPad * 2
        // 节点原点对应的本 View 坐标
        val dx0 = left - mDropPad
        val dy0 = top - mDropPad
        // 副本缩放与真实标签的 LocalLiquidTabScale 用同一系数，玻璃泡内外放大连续一致
        val zoom = 1f + tabZoomBase() * p

        // 1) 页面层：单独录制并模糊——标签副本必须是清晰的，不能跟着一起糊
        pageNode.setPosition(0, 0, nw, nh)
        val pc = pageNode.beginRecording(nw, nh)
        try {
            pc.drawColor(mBaseColor)
            val backdrop = mBackdrop?.get()
            if (backdrop != null && backdrop.width > 0) {
                getLocationOnScreen(mSelf)
                backdrop.getLocationOnScreen(mSrc)
                // 胶囊左上角在屏幕上位于 self + inset（View 比胶囊大一圈）
                pc.translate(
                    (mSrc[0] - mSelf[0] - dx0 - mInsetX),
                    (mSrc[1] - mSelf[1] - dy0 - mInsetY)
                )
                backdrop.draw(pc)
            }
        } finally {
            pageNode.endRecording()
        }
        if (mBlurEffect != null) {
            pageNode.setRenderEffect(mBlurEffect)
        }

        // 2) 合成层
        node.setPosition(0, 0, nw, nh)
        val rc = node.beginRecording(nw, nh)
        try {
            rc.drawColor(mBaseColor)
            rc.drawRenderNode(pageNode)
            // 玻璃条的容器色：液滴与条身材质一致
            rc.drawRoundRect(
                mDropPad.toFloat(), mDropPad.toFloat(), (mDropPad + pw).toFloat(),
                (mDropPad + ph).toFloat(), radius, radius, mSurfacePaint
            )
            drawDropSurfaceTints(rc, mDropPad.toFloat(), mDropPad.toFloat(),
                (mDropPad + pw).toFloat(), (mDropPad + ph).toFloat(), radius, p)
            // 清晰的标签副本（放大 + 强调色），折射后即「放大并弯曲的图标」
            drawItemCopies(rc, dx0, dy0, zoom)
        } finally {
            node.endRecording()
        }

        if (p > 0.01f && dropLens != null) {
            val band = Math.min(
                DROP_REFRACTION_DP * mDensity,
                Math.min(pw.toFloat(), ph.toFloat()) * 0.5f * 0.45f
            )
            dropLens.setFloatUniform("size", pw.toFloat(), ph.toFloat())
            dropLens.setFloatUniform("offset", -mDropPad.toFloat(), -mDropPad.toFloat())
            dropLens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            dropLens.setFloatUniform("refractionHeight", band * p)
            dropLens.setFloatUniform(
                "refractionAmount",
                -band * (DROP_AMOUNT_DP / DROP_REFRACTION_DP) * p
            )
            dropLens.setFloatUniform("depthEffect", 1f)
            dropLens.setFloatUniform("chromaticAberration", DROP_ABERRATION)
            node.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(dropLens, "content")
            )
        } else {
            node.setRenderEffect(null)
        }

        // 按压时液滴鼓出胶囊是液态玻璃的标志性形变（参考项目均不裁剪）
        canvas.save()
        mDropClip.reset()
        mDropClip.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
        canvas.clipPath(mDropClip)
        canvas.translate(dx0, dy0)
        canvas.drawRenderNode(node)
        canvas.restore()
        return true
    }

    /**
     * 液滴表面色调，压在标签副本<b>之下</b>——
     * 副本盖在色调上可去掉「按下变暗 / 松手变亮」的颜色跳变（WeChat-LiquidGlass 的结论）。
     */
    private fun drawDropSurfaceTints(c: Canvas, l: Float, t: Float, r: Float, b: Float,
                                     radius: Float, p: Float) {
        val resting = if (mNight) DROP_WASH_DARK else DROP_WASH_LIGHT
        val washAlpha = Math.round(resting * (1f - p))
        if (washAlpha > 0) {
            mDropWashPaint.alpha = washAlpha
            c.drawRoundRect(l, t, r, b, radius, radius, mDropWashPaint)
        }
        // black @ 3% × p
        val pressAlpha = Math.round(DROP_PRESS_TINT * p)
        if (pressAlpha > 0) {
            mPressTintPaint.alpha = pressAlpha
            c.drawRoundRect(l, t, r, b, radius, radius, mPressTintPaint)
        }
    }

    /**
     * 标签副本：每个 item 绕自身中心缩放 lerp(1, 1.2, p)（HyperIsland 的
     * LocalLiquidTabScale），整体染成强调色（LocalContentColor = accentColor）。
     *
     * <p>染色必须是盖在 item 自己 draw() 之上的一整层——逐个叶子染色会漏掉
     * 容器自己画的内容（WeChat-LiquidGlass 踩过的坑）。
     */
    private fun drawItemCopies(c: Canvas, dx0: Float, dy0: Float, zoom: Float) {
        if (mItems.isEmpty()) {
            return
        }
        val accent = accentColor()
        getLocationOnScreen(mSelf)
        for (i in mItems.indices) {
            val item = mItems[i]
            if (item.visibility != View.VISIBLE
                || item.width <= 0 || item.height <= 0
            ) {
                continue
            }
            item.getLocationOnScreen(mSrc)
            val save = c.save()
            // item 在屏幕上的位置含 inset（item 布局在 View 坐标里），
            // 而本画布（液滴节点/胶囊坐标系）不含 inset，所以要减掉
            c.translate(
                mSrc[0] - mSelf[0] - dx0 - mInsetX,
                mSrc[1] - mSelf[1] - dy0 - mInsetY
            )
            c.scale(zoom, zoom, item.width * 0.5f, item.height * 0.5f)
            // 图层边界要盖过放大后的副本，否则 zoom>1 时边缘会被图层裁掉
            val iw = item.width.toFloat()
            val ih = item.height.toFloat()
            val layer = c.saveLayer(-iw, -ih, iw * 2f, ih * 2f, null)
            item.draw(c)
            mTintPaint.color = accent
            mTintPaint.xfermode = SRC_ATOP
            c.drawRect(-iw, -ih, iw * 2f, ih * 2f, mTintPaint)
            mTintPaint.xfermode = null
            c.restoreToCount(layer)
            c.restoreToCount(save)
        }
    }

    /** 无渲染管线时的降级：直接画色调 + 标签副本（不折射）。 */
    private fun drawDropFallback(canvas: Canvas, left: Float, top: Float,
                                 right: Float, bottom: Float, radius: Float, p: Float) {
        val save = canvas.save()
        mDropClip.reset()
        mDropClip.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
        canvas.clipPath(mDropClip)
        drawDropSurfaceTints(canvas, left, top, right, bottom, radius, p)
        // 直接画在主画布上：副本偏移基准是本 View 原点（dx0 = 0, dy0 = 0）
        drawItemCopies(canvas, 0f, 0f, 1f + tabZoomBase() * p)
        canvas.restoreToCount(save)
    }

    /** 液滴内阴影：已禁用（DROP_INNER_SHADOW_ALPHA=0）——参考项目的水滴没有黑色内圈。 */
    private fun drawDropInnerShadow(canvas: Canvas, left: Float, top: Float,
                                    w: Float, h: Float, radius: Float, p: Float) {
        val shader = mInnerShader ?: return
        val alpha = DROP_INNER_SHADOW_ALPHA * p
        if (alpha <= 0.01f || DROP_INNER_SHADOW_DP <= 0f) {
            return
        }
        val blur = DROP_INNER_SHADOW_DP * mDensity * p
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("blur", blur)
        shader.setFloatUniform("alpha", alpha)
        mInnerShadowPaint.shader = shader
        val save = canvas.save()
        canvas.translate(left, top)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, mInnerShadowPaint)
        canvas.restoreToCount(save)
    }

    // ---------------------------------------------------------------- 手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            return super.onTouchEvent(event)
        }
        val x = event.x
        val y = event.y
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // View 比胶囊大一圈（inset）：落在胶囊外的触摸不响应，透给下层内容
                if (x < mInsetX || y < mInsetY || x > width - mInsetX || y > height - mInsetY) {
                    return false
                }
                mTouchStartX = x
                mTouchStartY = y
                mIsDragging = false
                mGestureActive = true
                // 按下即按压：液滴放大 + 折射淡入（与拖动同一条路径）
                pressDroplet()
                startFrames()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - mTouchStartX
                val dy = y - mTouchStartY
                if (!mIsDragging && (Math.abs(dx) > mDragThresholdPx || Math.abs(dy) > mDragThresholdPx)) {
                    // 只有水平位移占主导时才进入拖动，避免纵向滚动误触
                    if (Math.abs(dx) > Math.abs(dy)) {
                        mIsDragging = true
                        val parent: ViewParent? = parent
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (mIsDragging) {
                    dragTo(x)
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                var fired = -1
                if (mIsDragging) {
                    var target = Math.round(mValue.target())
                    target = Math.max(0, Math.min(mItemCount - 1, target))
                    if (target != mSelectedIndex) {
                        mSelectedIndex = target
                    }
                    settleTo(target)
                    fired = target
                } else {
                    val clicked = indexFromX(x)
                    if (clicked >= 0 && clicked < mItemCount) {
                        // 点击也走 press → 移动 → release 的同一路径
                        mSelectedIndex = clicked
                        settleTo(clicked)
                        fired = clicked
                    }
                }
                releaseDroplet()
                mIsDragging = false
                mGestureActive = false
                if (fired >= 0) {
                    mItemListener?.onItemSelected(fired)
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                settleTo(mSelectedIndex)
                releaseDroplet()
                mIsDragging = false
                mGestureActive = false
                true
            }

            else -> super.onTouchEvent(event)
        }
    }

    private fun indexFromX(x: Float): Int {
        val innerW = width - paddingLeft - paddingRight
        if (innerW <= 0) {
            return -1
        }
        var idx = ((x - paddingLeft) / (innerW / mItemCount.toFloat())).toInt()
        if (idx < 0) {
            idx = 0
        }
        if (idx >= mItemCount) {
            idx = mItemCount - 1
        }
        return idx
    }

    private fun dragTo(x: Float) {
        val innerW = width - paddingLeft - paddingRight
        if (innerW <= 0) {
            return
        }
        val itemW = innerW / mItemCount.toFloat()
        var pos = (x - paddingLeft) / itemW - 0.5f
        pos = Math.max(0f, Math.min((mItemCount - 1).toFloat(), pos))
        // 液滴由弹簧追踪目标（轻微滞后 = 液体的跟手感）
        mValue.animateTo(pos)
        // 参考项目行为：拖动过程只移动液滴，不切页；松手后才切换（见 ACTION_UP）
        startFrames()
    }

    private fun settleTo(target: Int) {
        mValue.animateTo(target.toFloat())
        mVelocity.animateTo(0f)
        startFrames()
    }

    private fun pressDroplet() {
        mPress.animateTo(1f)
        mScaleX.animateTo(PRESS_SCALE)
        mScaleY.animateTo(PRESS_SCALE)
        startFrames()
    }

    /**
     * 松手收拢：等液滴接近目标位置后再回落缩放（HyperIsland 的 release() 语义）——
     * 这样甩动读起来是一个连贯动作，而不是「滑动 + 单独缩回」。
     */
    private fun releaseDroplet() {
        mReleasePending = true
        startFrames()
    }

    private fun dropletCentreX(): Float {
        val innerW = pillWidth() - contentPadLeft() - contentPadRight()
        if (innerW <= 0 || mItemCount <= 0) {
            return pillWidth() * 0.5f
        }
        val itemW = innerW / mItemCount.toFloat()
        return contentPadLeft() + (mValue.value() + 0.5f) * itemW
    }

    /** 液滴内标签副本的放大基数（参考项目「标签副本 lerp(1, 1.2, p)」，16dp/标签宽 ≈ 0.2）。 */
    private fun tabZoomBase(): Float {
        val innerW = pillWidth() - contentPadLeft() - contentPadRight()
        if (innerW <= 0 || mItemCount <= 0) {
            return TAB_ZOOM
        }
        val itemW = innerW / mItemCount.toFloat()
        return if (itemW > 0f) Math.min(0.35f, 16f * mDensity / itemW) else TAB_ZOOM
    }

    /**
     * HyperIsland layerBlock 的整体放大：整个导航栏随按压放大 lerp(1, 1+16dp/宽, p)——
     * 是「整条导航栏变大」，图标作为整体的一部分随之等比放大，松手后回缩。
     * 以胶囊（pill）宽度为基准，view 中心 = 胶囊中心。
     */
    private fun applyBarScale() {
        val p = mPress.value()
        val w = pillWidth().toFloat()
        val base = if (w > 0f) Math.min(0.2f, 16f * mDensity / w) else 0.1f
        val s = 1f + base * p
        if (Math.abs(scaleX - s) > 0.0005f || Math.abs(scaleY - s) > 0.0005f) {
            scaleX = s
            scaleY = s
        }
    }

    // ---------------------------------------------------------------- 动画循环

    private fun startFrames() {
        if (mFramesRunning) {
            return
        }
        mFramesRunning = true
        mLastNs = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(mFrame)
    }

    private val mFrame: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val dt = Math.max(
            0f,
            Math.min((frameTimeNanos - mLastNs) / 1_000_000_000f, 0.032f)
        )
        mLastNs = frameTimeNanos

        val alive: Boolean
        if (mIsDragging && dt > 0f) {
            // 拖动中：速度 = 位置变化率按标签数归一化（不同屏幕密度手感一致）
            val before = mValue.value()
            alive = mValue.update(dt)
            val measured = (mValue.value() - before) / dt / Math.max(1, mItemCount - 1)
            mVelocity.animateTo(measured)
        } else {
            alive = mValue.update(dt)
        }
        var aliveAll = alive
        aliveAll = aliveAll or mVelocity.update(dt)
        aliveAll = aliveAll or mPress.update(dt)
        aliveAll = aliveAll or mScaleX.update(dt)
        aliveAll = aliveAll or mScaleY.update(dt)

        if (mReleasePending && !mValue.isRunning()) {
            mReleasePending = false
            mPress.animateTo(0f)
            mScaleX.animateTo(1f)
            mScaleY.animateTo(1f)
            aliveAll = true
        }

        applyBarScale()
        invalidate()
        if (aliveAll) {
            Choreographer.getInstance().postFrameCallback(mFrame)
        } else {
            mFramesRunning = false
        }
    }

    // ---------------------------------------------------------------- 重力高光

    private fun registerTiltSensor() {
        if (mSensorManager != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        try {
            mSensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sm = mSensorManager ?: return
            mGravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            if (mGravitySensor == null) {
                mGravitySensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            }
            val sensor = mGravitySensor
            if (sensor != null) {
                sm.registerListener(mTiltListener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (ignored: Throwable) {
            mGravitySensor = null
        }
    }

    private fun unregisterTiltSensor() {
        val sm = mSensorManager
        if (sm != null) {
            try {
                sm.unregisterListener(mTiltListener)
            } catch (ignored: Throwable) {
                // 个别 ROM 上注销会抛，忽略
            }
        }
        mSensorManager = null
        mGravitySensor = null
    }

    /**
     * 重力方向 → 高光主光方向（屏幕坐标，y 向下）。
     *
     * <p>重力传感器读数近似指向「上」；手机直立时主光即屏幕上方（顶边高光），
     * 倾斜设备时高光随之滑动——参考项目的「重力传感器高光」。
     */
    private val mTiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            val gx = e.values[0]
            val gy = e.values[1]
            var nx = gx
            var ny = -gy
            val len = Math.sqrt((nx * nx + ny * ny).toDouble()).toFloat()
            if (len > 1f) {
                nx /= len
                ny /= len
            } else {
                nx = 0f
                ny = -1f
            }
            // 低通，避免高光抖动
            val lx = mLightX + (nx - mLightX) * 0.15f
            val ly = mLightY + (ny - mLightY) * 0.15f
            if (Math.abs(lx - mLightX) > 0.004f || Math.abs(ly - mLightY) > 0.004f) {
                mLightX = lx
                mLightY = ly
                invalidate()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 不关心
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 强调色：显式设置优先；否则从选中项标签的文字色推断；再不然用品牌蓝。 */
    private fun accentColor(): Int {
        if (mAccent != 0) {
            return mAccent
        }
        if (mSelectedIndex >= 0 && mSelectedIndex < mItems.size) {
            val c = firstLabelColor(mItems[mSelectedIndex], 0)
            if (c != 0 && !isNeutral(c)) {
                mAccent = c or 0xFF000000.toInt()
                return mAccent
            }
        }
        return 0xFF00B0F0.toInt()
    }

    /** 选中项回调（外部切页/点击/拖动静置后触发）。 */
    fun interface OnItemSelectedListener {
        fun onItemSelected(index: Int)
    }

    companion object {
        // ===== 玻璃条参数（HyperIsland: vibrancy() → blur(4dp) → lens(24dp, 24dp)）=====
        private const val GLASS_REFRACTION_DP = 24f
        /** 模糊半径（dp）：实机反馈 4dp 太糊，降到 2.5dp 让背后内容更清楚。 */
        private const val GLASS_BLUR_DP = 2.5f
        private const val GLASS_SATURATION = 1.5f
        /** 镜面边缘高光：实机反馈偏狠，压到最小（只留极淡一圈）。 */
        private const val GLASS_RIM_ALPHA = 0.12f

        // ===== 液滴参数（KernelSU/HyperIsland 的 droplet 效果栈）=====
        /** lens(refractionHeight = 10dp × p, refractionAmount = 14dp × p)。 */
        private const val DROP_REFRACTION_DP = 10f
        private const val DROP_AMOUNT_DP = 14f
        /** chromaticAberration = 0.5，depthEffect = true。 */
        private const val DROP_ABERRATION = 0.5f
        /** innerShadow 已禁用：参考项目的水滴没有黑色内圈，我们之前的「黑边」就是它。 */
        private const val DROP_INNER_SHADOW_DP = 8f
        private const val DROP_INNER_SHADOW_ALPHA = 0f
        /** 标签副本缩放 lerp(1, 1.2, p)。 */
        private const val TAB_ZOOM = 0.2f
        /** 按压时液滴放大倍率（HyperIsland 的 78/56）。 */
        private const val PRESS_SCALE = 78f / 56f

        /**
         * View 边界比胶囊大一圈的量（dp）。硬件加速下 View 自身绘制会被裁剪到 View 边界，
         * 按压后鼓出条外的液滴必须画在更大的 View 里才能「溢出」。布局里：
         * 高 96 = 64 + 2×16、padding 20 = 4 + 16、边距 0。
         */
        private const val BAR_INSET_DP = 16f

        // ===== 拖动速度形变（DampedDragAnimation.layerBlock 的原样公式）=====
        private const val STRETCH_DIVISOR = 10f
        private const val STRETCH_X = 0.75f
        private const val STRETCH_Y = 0.25f
        private const val STRETCH_LIMIT = 0.2f

        /** 判定为拖动所需的最小位移。 */
        private const val DRAG_THRESHOLD_DP = 12f
        /** 松手后等液滴接近目标再收拢缩放的阈值（HyperIsland: range × 2.5%）。 */
        private const val SETTLE_FRACTION = 0.025f

        /**
         * 液滴静止色调：比导航栏<b>深一点</b>（浅色）/ <b>亮一点</b>（深色）的一档，
         * 让玻璃泡从条里「浮」出来（参考项目的水滴就是这样与条区分的）。
         */
        private const val DROP_WASH_LIGHT = 0x0A
        private const val DROP_WASH_DARK = 0x14
        /** 按压附加色：black @ 2% × p（原 3% 会读出「按压变白/变灰」的遮罩感）。 */
        private const val DROP_PRESS_TINT = 0x05

        private val SRC_ATOP = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        /** Skia 的 Plus 混合；API 28 的枚举里没有 ADD，取不到时退化为普通混合。 */
        private val PLUS = plusXfermode()

        private fun plusXfermode(): PorterDuffXfermode? {
            return try {
                PorterDuffXfermode(PorterDuff.Mode.ADD)
            } catch (t: Throwable) {
                null
            }
        }

        private fun firstLabelColor(v: View?, depth: Int): Int {
            if (depth > 4 || v == null || v.visibility != View.VISIBLE) {
                return 0
            }
            if (v is TextView) {
                return v.currentTextColor or 0xFF000000.toInt()
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = firstLabelColor(v.getChildAt(i), depth + 1)
                    if (c != 0) {
                        return c
                    }
                }
            }
            return 0
        }

        private fun isNeutral(c: Int): Boolean {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val max = Math.max(r, Math.max(g, b))
            val min = Math.min(r, Math.min(g, b))
            return max - min < 24
        }

        private fun clamp(v: Float, limit: Float): Float {
            return Math.max(-limit, Math.min(limit, v))
        }
    }
}
