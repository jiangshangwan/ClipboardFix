package com.clipboardfix

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.round

/**
 * 「关于」页顶部动态极光渐变背景（移植自 Apache-2.0 授权的 AGSL 极光实现）。
 *
 * 目标观感并非「全屏渐变 + 白色底板」，而是一块**仅限顶栏区域**的流体极光（4 枚
 * 带色 metaball 互相混合 + perlin 噪声去饱和/提亮），通过 uBound 把效果约束在「状态栏 + 头部高」
 * 这一段；其下方就是页面正常底色，卡片直接落在页面底色上（无统一白板）。LOGO 是固定层，
 * 滚动时按位移淡出 + 轻微缩放；模块名文字颜色随调色板在 3 套配色间循环切换（即「跟随变色」）。
 *
 * 本实现：minSdk 33，使用标准 AGSL（同方案思路，无小米私有依赖），直接作为 Paint 着色器绘制；
 * 顶栏之外透明，露出页面底色。动画仅在 isShown() 时推进。
 */
class AboutBgView @JvmOverloads constructor(
    context: Context,
    @Nullable attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 顶部渐变带（顶栏）像素高度 = 状态栏 + 头部高（389dp）；之下即页面底色。 */
    private var mBandBottom: Int = 0

    /** 滚动时渐变整体淡出（对齐设计稿：背景层随滚动淡出）。 */
    private var mGradientAlpha = 1f

    private val mShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mFadeShader: LinearGradient? = null
    private var mFadeShaderBand: Int = -1
    private var mFadeShaderColor: Int = 0

    @Nullable
    private var mShader: RuntimeShader? = null

    /** 模块名「跟随流光」着色器：复用同一段极光 AGSL，坐标经 uTranslate 对齐到背景。 */
    @Nullable
    private var mTitleShader: RuntimeShader? = null

    /** 模块名 View（TextureView 不可用时为 null，退回单色循环）。 */
    @Nullable
    private var mTitleView: TextView? = null

    /** 标题亮度的统一下压/上提值（浅色模式压暗成深字、深色模式提亮成浅字）。 */
    private var mTitleLum = 0.45f

    /** LOGO 图标「跟随流光」着色器：与标题同源，色相取背景、饱和度/亮度拉到可读值。 */
    @Nullable
    private var mLogoShader: RuntimeShader? = null

    /** LOGO 图标 View（着色器不可用时为 null，保持原图）。 */
    @Nullable
    private var mLogoView: AboutLogoView? = null

    /** LOGO 目标饱和度 / 亮度（深色模式提亮，浅色模式压暗，保证与背景有反差）。 */
    private var mLogoSat = 0.62f
    private var mLogoLum = 0.38f

    private var mNight: Boolean = false

    // ---- 动画时钟（按真实 dt 推进，metaball 缓慢游动）----
    private var mAnimTime = 0f
    private var mLastTime: Long = 0
    private var mRunning = false

    // ---- 调色板循环（对齐 BgEffectDataManager 的 3 套配色 + 4 段往返插值）----
    private val mColorsF = FloatArray(16)
    private var mColorT = 0f
    private var mColorSeg = 0
    private var mColorPeriod = 0f

    private var mTitleListener: OnTitleColorListener? = null
    private var mLastTitleColor = 0

    companion object {
        // 浅色调色板
        private val LIGHT = arrayOf(
            floatArrayOf(1.0f, 0.90f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f),
            floatArrayOf(0.58f, 0.74f, 1.00f, 1.0f, 1.0f, 0.90f, 0.93f, 1.0f, 0.74f, 0.76f, 1.00f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
            floatArrayOf(0.98f, 0.86f, 0.90f, 1.0f, 0.60f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.00f, 1.0f, 0.56f, 0.69f, 1.00f, 1.0f)
        )
        // 深色调色板：RGB 整体压暗（×0.75）→ 底色更「深」；色相 / 饱和度保留，深色下仍浓郁。
        private val DARK = arrayOf(
            floatArrayOf(0.150f, 0.045f, 0.660f, 0.4f, 0.225f, 0.105f, 0.413f, 0.5f, 0.000f, 0.480f, 0.720f, 0.5f, 0.083f, 0.120f, 0.623f, 0.4f),
            floatArrayOf(0.053f, 0.113f, 0.593f, 0.5f, 0.465f, 0.158f, 0.503f, 0.5f, 0.045f, 0.188f, 0.630f, 0.5f, 0.000f, 0.150f, 0.585f, 0.5f),
            floatArrayOf(0.435f, 0.225f, 0.555f, 0.4f, 0.203f, 0.135f, 0.450f, 0.5f, 0.495f, 0.195f, 0.465f, 0.5f, 0.090f, 0.120f, 0.525f, 0.6f)
        )
        private val TITLE_LIGHT = arrayOf(
            intArrayOf(0x3B, 0x6F, 0xD4), intArrayOf(0x8A, 0x5B, 0xD0), intArrayOf(0xD4, 0x58, 0x8A)
        )
        private val TITLE_DARK = arrayOf(
            intArrayOf(0x6F, 0xA0, 0xFF), intArrayOf(0xC7, 0x7F, 0xE0), intArrayOf(0xFF, 0x8F, 0xB0)
        )
        // 4 段往返顺序：0→1→2→1→0→…（真正的闭环，避免循环接缝）
        private val SEG = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(1, 0)
        )

        // 上游 AGSL 实现（仅保留流体极光主体；作为 Paint 着色器直接绘制）。
        private const val AGSL_HEADER = """uniform vec2 uResolution;uniform float uAnimTime;uniform vec4 uBound;uniform vec2 uTranslate;uniform vec2 uLogoCenter;uniform float uTranslateY;uniform vec3 uPoints[4];uniform vec4 uColors[4];uniform float uAlphaMulti;uniform float uNoiseScale;uniform float uPointOffset;uniform float uPointRadiusMulti;uniform float uSaturateOffset;uniform float uLightOffset;uniform float uAlphaOffset;uniform float uTitleLum;uniform float uLogoSat;uniform float uLogoLum;"""
        private const val AGSL_FUNCS = """vec3 rgb2hsv(vec3 c){  vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));  float d = q.x - min(q.w, q.y);  float e = 1.0e-10;  return vec3(abs(q.z + (q.w - q.y)/(6.0*d + e)), d/(q.x + e), q.x);}vec3 hsv2rgb(vec3 c){  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);}float hash(vec2 p){  vec3 p3 = fract(vec3(p.xyx)*0.13);  p3 += dot(p3, p3.yzx + 3.333);  return fract((p3.x + p3.y)*p3.z);}float perlin(vec2 x){  vec2 i = floor(x); vec2 f = fract(x);  float a = hash(i); float b = hash(i + vec2(1.0,0.0));  float c = hash(i + vec2(0.0,1.0)); float d = hash(i + vec2(1.0,1.0));  vec2 u = f*f*(3.0-2.0*f);  return mix(a,b,u.x) + (c-a)*u.y*(1.0-u.x) + (d-b)*u.x*u.y;}float gradientNoise(vec2 uv){  return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));}vec4 aurora(vec2 fc){  vec2 vUv = fc/uResolution;  vUv.y = 1.0 - vUv.y;  vec2 uv = vUv;  uv -= vec2(0.0, uTranslateY);  uv.xy -= uBound.xy; uv.xy /= uBound.zw;  vec4 color = vec4(0.0);  float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));  for (int i = 0; i < 4; i++){    vec4 pointColor = uColors[i];    pointColor.rgb *= pointColor.a;    vec2 point = uPoints[i].xy;    float rad = uPoints[i].z * uPointRadiusMulti;    point.x += sin(uAnimTime + point.y) * uPointOffset;    point.y += cos(uAnimTime + point.x) * uPointOffset;    float dd = distance(uv, point);    float pct = smoothstep(rad, 0.0, dd);    color.rgb = mix(color.rgb, pointColor.rgb, pct);    color.a = mix(color.a, pointColor.a, pct);  }  float oppositeNoise = smoothstep(0.0, 1.0, noiseValue);  color.rgb /= max(color.a, 1.0e-4);  vec3 hsvC = rgb2hsv(color.rgb);  hsvC.y = mix(hsvC.y, 0.0, oppositeNoise * uSaturateOffset);  color.rgb = hsv2rgb(hsvC);  color.rgb += oppositeNoise * uLightOffset;  color.a = clamp(color.a, 0.0, 1.0);  color.a *= uAlphaMulti;  color += (10.0/255.0) * gradientNoise(fc.xy) - (5.0/255.0);  return color;}"""
        private const val AGSL_BG_MAIN = """vec4 main(vec2 fragCoord){  vec4 color = aurora(fragCoord);  return vec4(color.rgb * color.a, color.a);}"""
        private const val AGSL_TITLE_MAIN = """vec4 main(vec2 fragCoord){  vec4 color = aurora(fragCoord + uTranslate);  vec3 thsv = rgb2hsv(color.rgb / max(color.a, 1.0e-4));  thsv.y = max(thsv.y, 0.55);  thsv.z = uTitleLum;  return vec4(hsv2rgb(thsv), 1.0);}"""
        private const val AGSL_LOGO_MAIN = """vec4 main(vec2 fragCoord){  vec4 color = aurora(uLogoCenter);  vec3 hsv = rgb2hsv(color.rgb / max(color.a, 1.0e-4));  hsv.y = max(hsv.y, uLogoSat);  hsv.z = uLogoLum;  return vec4(hsv2rgb(hsv), 1.0);}"""

        /** 背景极光：直接输出预乘色（原逻辑不变）。 */
        private const val AGSL = AGSL_HEADER + AGSL_FUNCS + AGSL_BG_MAIN

        /** 模块名专用（文字跟随流光照射变色）。 */
        private const val AGSL_TITLE = AGSL_HEADER + AGSL_FUNCS + AGSL_TITLE_MAIN

        /** LOGO 图标专用（温和跟随流光、无幕布）。 */
        private const val AGSL_LOGO = AGSL_HEADER + AGSL_FUNCS + AGSL_LOGO_MAIN

        /** 深色模式图形色：深紫（对齐设计稿深色图标实测 rgb(38,29,86) → 实际取 46,37,96）。 */
        private val GLYPH_DARK = Color.rgb(46, 37, 96)

        /** 浅色模式图形色：纯白（原图色）。 */
        private val GLYPH_LIGHT = Color.WHITE
    }

    /** 模块名跟随变色回调。 */
    interface OnTitleColorListener {
        fun onTitleColor(color: Int)
    }

    init {
        setWillNotDraw(false)
        mNight = isNight(context)
        mColorPeriod = if (mNight) 8f else 5f
        // 初始配色须与「mColorT=0 / mColorSeg=0」的插值结果一致（= pal[0]），否则首帧会闪一下。
        System.arraycopy((if (mNight) DARK else LIGHT)[SEG[0][0]], 0, mColorsF, 0, 16)
        var shader: RuntimeShader? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = RuntimeShader(AGSL)
                initShaderUniforms(shader)
            } catch (_: Throwable) {
                shader = null   // 着色器不可用时走静态兜底
            }
        }
        mShader = shader
    }

    /** 设置一遍与主题相关的静态 uniform（uColors 随时间更新，uBound/uResolution 每帧更新）。 */
    private fun initShaderUniforms(s: RuntimeShader) {
        val pts = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f)
        s.setFloatUniform("uPoints", pts)
        s.setFloatUniform("uTranslateY", 0f)
        s.setFloatUniform("uAlphaMulti", 1f)
        s.setFloatUniform("uNoiseScale", 1.5f)
        s.setFloatUniform("uPointOffset", if (mNight) 0.4f else 0.2f)
        s.setFloatUniform("uPointRadiusMulti", 1f)
        // 深色：更「浓郁」（少去饱和）+ 更深（少提亮），对齐「深色下色调更深也更亮眼」的观感
        s.setFloatUniform("uSaturateOffset", if (mNight) 0.10f else 0.2f)
        s.setFloatUniform("uLightOffset", if (mNight) 0.05f else 0.1f)
        s.setFloatUniform("uAlphaOffset", 0.5f)
        s.setFloatUniform("uColors", mColorsF)
    }

    fun setBandBottom(px: Int) {
        if (mBandBottom != px) {
            mBandBottom = px
            invalidate()
        }
    }

    fun setGradientAlpha(a: Float) {
        val na = Math.max(0f, Math.min(1f, a))
        if (mGradientAlpha != na) {
            mGradientAlpha = na
            invalidate()
        }
    }

    /**
     * 让模块名「跟随流光照射变色」：把同一段极光 AGSL 挂到标题文字的 Paint 上，
     * 并用 uTranslate 把「文字局部坐标」平移回「背景坐标」，于是文字每个像素都取到
     * 它正下方背景极光的颜色——文字像一块透出流光的玻璃。
     * API 33 以下无 RuntimeShader 时自动退回单色循环（setTextColor）。
     */
    fun setTitleView(@Nullable tv: TextView?) {
        mTitleView = tv
        // 深色模式标题提到高亮（对齐设计稿深色模式的亮紫标题，实测 V≈0.95）；浅色模式仍压暗成深字。
        mTitleLum = if (mNight) 0.93f else 0.45f
        if (tv == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val ts = RuntimeShader(AGSL_TITLE)
                initShaderUniforms(ts)
                ts.setFloatUniform("uTitleLum", mTitleLum)
                ts.setFloatUniform("uTranslate", 0f, 0f)
                mTitleShader = ts
                tv.paint.shader = ts
                mTitleListener = null      // 走着色器，不再需要单色回调
                return
            } catch (_: Throwable) {
                mTitleShader = null        // 着色器不可用 → 退回单色
            }
        }
        mTitleListener = object : OnTitleColorListener {
            override fun onTitleColor(color: Int) = tv.setTextColor(color)
        }  // 退回：单色循环
    }

    /**
     * 让 LOGO 图标「跟随后面的流光变色」——对图标与标题施加同一套背景混色。
     * API 33 以下无 RuntimeShader 时底板退化为固定紫色，图形不变。
     */
    fun setLogoView(@Nullable v: AboutLogoView?) {
        mLogoView = v
        // 目标值按「极光自身 S≈0.17 / V≈0.99」反解。
        //  浅色模式：命中设计稿浅色实测的深紫底板（S≈0.74 / V≈0.49）。
        //  深色模式：对齐设计稿深色观感 —— 浅紫薰衣草底板（实测 S≈0.43 / V≈0.94）。
        mLogoSat = if (mNight) 0.47f else 0.82f
        mLogoLum = if (mNight) 0.94f else 0.46f
        if (v == null) return
        // 图形色随深浅色反色：浅色=纯白（深底白图形）；深色=深紫（浅底深图形）。
        v.setGlyphTint(if (mNight) GLYPH_DARK else GLYPH_LIGHT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val ls = RuntimeShader(AGSL_LOGO)
                initShaderUniforms(ls)
                ls.setFloatUniform("uLogoSat", mLogoSat)
                ls.setFloatUniform("uLogoLum", mLogoLum)
                ls.setFloatUniform("uTranslate", 0f, 0f)
                mLogoShader = ls
                v.setGlowShader(ls)
            } catch (_: Throwable) {
                mLogoShader = null   // 着色器不可用 → 保持原图
            }
        }
    }

    fun setOnTitleColorListener(@Nullable l: OnTitleColorListener?) {
        mTitleListener = l
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || isInEditMode) return
        // 极光铺满整屏（不再约束在头部带内）：uBound 覆盖全高，流光得以一路向下延伸、穿过卡片区。
        val band = h
        // 底：整屏铺页面底色
        val bg = ContextCompat.getColor(context, R.color.bg_surface) or 0xFF000000.toInt()
        mBasePaint.color = bg
        mBasePaint.alpha = (255 * mGradientAlpha).toInt()
        canvas.drawColor(bg)
        val s = mShader
        if (s != null) {
            s.setFloatUniform("uAnimTime", mAnimTime)
            s.setFloatUniform("uResolution", w.toFloat(), h.toFloat())
            // 全屏 uv（uBound 覆盖整个 View），metaball 铺满整屏
            s.setFloatUniform("uBound", 0f, 0f, 1f, 1f)
            mShaderPaint.shader = s
            mShaderPaint.alpha = (255 * mGradientAlpha).toInt()
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mShaderPaint)
        } else {
            // 静态兜底：竖向极光 + 缓慢游动的半透明色斑（保证不空白、不崩）
            drawFallback(canvas, w, h)
        }
        // 下部渐暗：极光自约 1/3 高度起渐次没入页面底色（上亮下暗的整屏渐变）。
        drawBottomFade(canvas, w, h, bg)
        // 跟随流光：每帧把背景的实时状态同步给标题/LOGO 着色器。
        syncGlowShaders(w, h, h)
    }

    /** 把背景每帧状态同步给「跟随流光」的着色器（见 setTitleView / setLogoView 说明）。 */
    private fun syncGlowShaders(w: Int, h: Int, band: Int) {
        val height2 = (if (band > 0) band.toFloat() else h.toFloat()) / h.toFloat()
        val bgPos = IntArray(2)
        getLocationInWindow(bgPos)
        val ts = mTitleShader
        val tv = mTitleView
        if (ts != null && tv != null) {
            ts.setFloatUniform("uAnimTime", mAnimTime)
            ts.setFloatUniform("uResolution", w.toFloat(), h.toFloat())
            ts.setFloatUniform("uBound", 0f, 1f - height2, 1f, height2)
            ts.setFloatUniform("uColors", mColorsF)
            val vPos = IntArray(2)
            tv.getLocationInWindow(vPos)
            ts.setFloatUniform(
                "uTranslate",
                (vPos[0] - bgPos[0]).toFloat(),
                (vPos[1] - bgPos[1]).toFloat()
            )
            tv.invalidate()
        }
        val ls = mLogoShader
        val lv = mLogoView
        if (ls != null && lv != null) {
            ls.setFloatUniform("uAnimTime", mAnimTime)
            ls.setFloatUniform("uResolution", w.toFloat(), h.toFloat())
            ls.setFloatUniform("uBound", 0f, 1f - height2, 1f, height2)
            ls.setFloatUniform("uColors", mColorsF)
            val vPos = IntArray(2)
            lv.getLocationInWindow(vPos)
            val cx = (vPos[0] - bgPos[0]).toFloat() + lv.width * 0.5f
            val cy = (vPos[1] - bgPos[1]).toFloat() + lv.height * 0.5f
            ls.setFloatUniform(
                "uTranslate",
                (vPos[0] - bgPos[0]).toFloat(),
                (vPos[1] - bgPos[1]).toFloat()
            )
            // LOGO 中心点（窗口坐标）：直接采背后极光色，单一均匀色、无幕布
            ls.setFloatUniform("uLogoCenter", cx, cy)
            lv.invalidate()
        }
    }

    private fun drawBottomFade(canvas: Canvas, w: Int, band: Int, bg: Int) {
        if (band <= 0) return
        if (mFadeShader == null || mFadeShaderBand != band || mFadeShaderColor != bg) {
            // 渐暗起点约 50% 高度：极光在头部 + 首屏卡片区保持明亮；之下平缓没入页面底色。
            // 渐暗起点：深色模式延后到 64% 高度；浅色维持 50%。
            val fadeStart = band * (if (mNight) 0.64f else 0.5f)
            // 用 5 个色标拟合成 smoothstep（t²(3-2t)）而非线性：两端斜率为 0，与上下两段无缝焊接。
            val c0 = bg and 0x00FFFFFF
            mFadeShader = LinearGradient(
                0f, fadeStart, 0f, band.toFloat(),
                intArrayOf(c0, 0x28000000 or c0, 0x80000000.toInt() or c0, 0xD7000000.toInt() or c0, bg),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            mFadeShaderBand = band
            mFadeShaderColor = bg
        }
        mFadePaint.shader = mFadeShader
        // 收尾遮罩随背景整体淡出一起收起；完全上滑后 mGradientAlpha=0，遮罩随之消失。
        mFadePaint.alpha = (255 * mGradientAlpha).toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), band.toFloat(), mFadePaint)
    }

    private fun drawFallback(canvas: Canvas, w: Int, band: Int) {
        val c1 = ContextCompat.getColor(context, R.color.about_aurora_1)
        val c2 = ContextCompat.getColor(context, R.color.about_aurora_2)
        val c3 = ContextCompat.getColor(context, R.color.about_aurora_3)
        mFallbackPaint.shader = LinearGradient(
            0f, 0f, 0f, band.toFloat(),
            intArrayOf(c1, c2, c3),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        mFallbackPaint.alpha = (255 * mGradientAlpha).toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), band.toFloat(), mFallbackPaint)
    }

    // ---------------- 动画：仅在可见时流动 ----------------
    private val mTick = object : Runnable {
        override fun run() {
            if (!mRunning || !isShown) {
                mRunning = false
                return
            }
            val now = System.nanoTime()
            var dt = (now - mLastTime) * 1e-9f
            mLastTime = now
            if (dt > 0.1f) dt = 0.1f
            mAnimTime += dt
            updateColors(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun startAnim() {
        if (mRunning) return
        mRunning = true
        mLastTime = System.nanoTime()
        postOnAnimation(mTick)
    }

    private fun stopAnim() {
        mRunning = false
    }

    /** 调色板在 3 套配色间往返插值（4 段往返顺序）。 */
    private fun updateColors(dt: Float) {
        mColorT += dt / mColorPeriod
        while (mColorT >= 1f) {
            mColorT -= 1f
            mColorSeg = (mColorSeg + 1) % 4
        }
        val pal = if (mNight) DARK else LIGHT
        val seg = SEG[mColorSeg]
        val a = pal[seg[0]]
        val b = pal[seg[1]]
        // smoothstep 缓动：让每段「起止的变化速度」都为 0，整条循环像呼吸一样丝滑。
        val t = mColorT * mColorT * (3f - 2f * mColorT)
        for (i in 0..15) {
            mColorsF[i] = a[i] + (b[i] - a[i]) * t
        }
        mShader?.setFloatUniform("uColors", mColorsF)
        // 模块名跟随变色：取当前调色板首色（RGB），转为饱和、适合可读的强调色
        val title = deriveTitleColor(mColorsF[0], mColorsF[1], mColorsF[2])
        if (abs(title - mLastTitleColor) > 0x030303 && mTitleListener != null) {
            mLastTitleColor = title
            mTitleListener!!.onTitleColor(title)
        }
    }

    private fun deriveTitleColor(r: Float, g: Float, b: Float): Int {
        val hsl = rgb2hsl(r, g, b)
        hsl[1] = Math.max(hsl[1], 0.55f)
        hsl[2] = if (isNightTint(hsl[2])) 0.93f else 0.45f
        val rgb = hsl2rgb(hsl)
        return Color.rgb(clamp255(rgb[0]), clamp255(rgb[1]), clamp255(rgb[2]))
    }

    private fun isNightTint(l: Float): Boolean {
        return l > 0.5f // 当前色偏亮 → 深色模式用浅色标题，否则用深色标题
    }

    private fun clamp255(v: Float): Int {
        var i = round(v * 255f).toInt()
        if (i < 0) i = 0
        if (i > 255) i = 255
        return i
    }

    // ---- RGB <-> HSL（用于把渐变首色转成可读强调色）----
    private fun rgb2hsl(r: Float, g: Float, b: Float): FloatArray {
        val cMin = Math.min(r, Math.min(g, b))
        val cMax = Math.max(r, Math.max(g, b))
        val l = (cMax + cMin) / 2f
        var h = 0f
        var s = 0f
        if (cMax > cMin) {
            val d = cMax - cMin
            s = if (l < 0f) d / (cMax + cMin) else d / (2f - (cMax + cMin))
            h = if (r == cMax) (g - b) / d
            else if (g == cMax) 2f + (b - r) / d
            else 4f + (r - g) / d
            if (h < 0f) h += 6f
            h /= 6f
        }
        return floatArrayOf(h, s, l)
    }

    private fun hsl2rgb(c: FloatArray): FloatArray {
        val h = c[0]
        val s = c[1]
        val l = c[2]
        if (s == 0f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val t = floatArrayOf(h + 1f / 3f, h, h - 1f / 3f)
        val rgb = FloatArray(3)
        for (i in 0..2) {
            var tt = t[i]
            if (tt < 0f) tt += 1f
            if (tt > 1f) tt -= 1f
            val v = when {
                tt < 1f / 6f -> p + (q - p) * 6f * tt
                tt < 1f / 2f -> q
                tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
                else -> p
            }
            rgb[i] = v
        }
        return rgb
    }

    private fun isNight(c: Context): Boolean {
        val nightMode = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startAnim() else stopAnim()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnim() else stopAnim()
    }
}
