package com.clipboardfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.annotation.Nullable

/**
 * 「关于」页的 LOGO 图标 —— 底板随背景流光变色、图形恒定纯白的观感。
 *
 * 要做到「底板取其后背景色」，目标是让「圆角方形底板 + 高饱和紫渐变 + 纯白图形」的底板吃进背后颜色。
 * 常规原生做法是调用小米私有的 MiuiBlurUtils.setViewBlurMode + addBackgroundBlenderColor，
 * 它依赖 MIUI 私有接口，且需要读取「背后像素」（归 SurfaceFlinger 所有，第三方 App 拿不到），无法照搬。
 *
 * 本实现的等价做法（全部为标准 API）：
 *   ① 底板＝圆角方形（squircle），颜色不是写死的，而是把与页面同源的极光 AGSL 铺上去
 *      —— 由 AboutBgView 经 setGlowShader 注入，并用 uTranslate 把图标局部坐标平移回
 *      背景坐标，于是底板每个像素取到它正下方背景极光的色相；同时把饱和度/明度拉向
 *      uLogoSat / uLogoLum，保证底板是「高饱和紫」而不是淡淡的背景色。
 *   ② 图形＝纯白，来自 ic_logo_glyph.png（透明 PNG，几何重建，边缘无噪点）。
 * 于是「底板随流光呼吸变色 + 图形恒定纯白」，与目标结构完全一致。
 *
 * API < 33 无 RuntimeShader 时：底板退化为固定紫色实心，图形仍为纯白，形态不变。
 */
class AboutLogoView @JvmOverloads constructor(
    context: Context,
    @Nullable attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    /** 铺底板用（着色器或固定色）。 */
    private val mPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 画白色图形用。 */
    private val mGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mPlate = RectF()
    private val mDst = Rect()

    @Nullable
    private var mGlyph: Bitmap? = null

    /** 底板圆角占短边的比例（设计稿实测约 0.27）。 */
    private var mRadiusRatio = 0.27f

    /** 图形相对底板的占比（设计稿实测约 0.5~0.55，我们是宽卡片所以略大）。 */
    private var mGlyphScale = 0.62f

    /** 无极光着色器时的兜底紫色（对齐设计稿底板实测 RGB(95,32,124)）。 */
    private val FALLBACK_PLATE = Color.rgb(95, 32, 124)

    init {
        mPlatePaint.color = FALLBACK_PLATE
        mPlatePaint.style = Paint.Style.FILL
    }

    override fun setImageDrawable(@Nullable drawable: Drawable?) {
        super.setImageDrawable(drawable)
        mGlyph = if (drawable is BitmapDrawable) drawable.bitmap else null
        invalidate()
    }

    /**
     * 注入极光着色器（由 AboutBgView 调用）填充底板；传 null 则退化为固定紫色。
     * 底板颜色 = 该像素正下方背景极光的色相 + 拉向 uLogoSat/uLogoLum 的饱和度/明度。
     */
    fun setGlowShader(@Nullable shader: Shader?) {
        mPlatePaint.shader = shader
        invalidate()
    }

    /**
     * 直接用纯色填充底板（Compose 版极光用 CPU 单点采背后极光色后经此接口设置，
     * 与 setGlowShader 的 GPU 采色等价；调用时会清掉已注入的着色器）。
     */
    fun setPlateColor(color: Int) {
        mPlatePaint.shader = null
        mPlatePaint.color = color
        invalidate()
    }

    /**
     * 图形（卡片）着色。浅色模式传纯白（= 原图色，不改变），深色模式传深紫
     * （与浅紫底板形成强反差，对齐设计稿深色模式「浅底 + 深图形」观感）。
     * 传 0 表示清除着色（保持原图）。
     */
    fun setGlyphTint(color: Int) {
        if (color == 0) {
            mGlyphPaint.colorFilter = null
        } else {
            mGlyphPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val r = Math.min(w, h) * mRadiusRatio

        // ① 底板：圆角方形（不用 clipToOutline，避免依赖背景 outline / 硬件裁切差异）
        mPlate.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(mPlate, r, r, mPlatePaint)

        // ② 图形：纯白，居中
        val g = mGlyph
        if (g != null) {
            val side = (Math.min(w, h) * mGlyphScale).toInt()
            val left = (w - side) / 2
            val top = (h - side) / 2
            mDst.set(left, top, left + side, top + side)
            canvas.drawBitmap(g, null, mDst, mGlyphPaint)
        }
    }
}
