package com.clipboardfix.ui

import android.graphics.RuntimeShader
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.clipboardfix.AboutLogoView
import com.clipboardfix.R
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 极光每帧驱动状态：animTime + 调色板插值 + 标题（GPU 着色器逐像素采色）+
 * LOGO（CPU 单点采色）三者同步。由 AuroraBackground 持有，AboutPage 共享。
 */
internal class AuroraState(val night: Boolean) {

    var animTime by mutableFloatStateOf(0f)
        private set
    val colorsF = FloatArray(16)
    /** 每帧自增，Canvas draw 读取它即可注册「每帧重绘」依赖。 */
    var frameVersion by mutableIntStateOf(0)
        private set

    /** 极光节点尺寸（写标题 shader 的 uResolution 用）。 */
    var auroraSize = Size.Zero

    /** 极光节点窗口原点（uTranslate = 目标 View 窗口坐标 - 此值）。 */
    var auroraPosInWindow = Offset.Zero

    // ---- 标题：GPU 着色器逐像素取背后极光色（与 AboutBgView.setTitleView 同机制）----
    var titleShader: RuntimeShader? = null
    var titleView: TextView? = null
    /** 着色器不可用时的兜底单色回调。 */
    var onTitleFallbackColor: ((Int) -> Unit)? = null

    // ---- LOGO：CPU 单点采背后极光色（与 AGSL_LOGO_MAIN 等价）----
    var logoView: AboutLogoView? = null
    var onLogoColor: ((Int) -> Unit)? = null

    private var colorT = 0f
    private var colorSeg = 0
    private val period = AuroraPalette.period(night)
    private var lastTitleColor = 0
    private var lastLogoColor = 0
    private val loc = IntArray(2)

    init {
        System.arraycopy(AuroraPalette.initialColors(night), 0, colorsF, 0, 16)
    }

    /**
     * 标题着色器静态 uniform 初始化（对应旧版 AboutBgView.initShaderUniforms + setTitleView）。
     * ⚠️ RuntimeShader 的 uniform 默认值全为 0：uPoints=0 会使 smoothstep 除零、
     * uAlphaMulti=0 会把 alpha 乘成 0，颜色计算直接崩坏（标题变静态色）——必须显式初始化。
     */
    fun initTitleShader(ts: RuntimeShader) {
        ts.setFloatUniform("uPoints", AURORA_POINTS)
        ts.setFloatUniform("uTranslateY", 0f)
        ts.setFloatUniform("uAlphaMulti", 1f)
        ts.setFloatUniform("uNoiseScale", AuroraSampler.noiseScale())
        ts.setFloatUniform("uPointOffset", AuroraSampler.pointOffset(night))
        ts.setFloatUniform("uPointRadiusMulti", 1f)
        ts.setFloatUniform("uSaturateOffset", AuroraSampler.saturateOffset(night))
        ts.setFloatUniform("uLightOffset", AuroraSampler.lightOffset(night))
        ts.setFloatUniform("uAlphaOffset", 0.5f)
        ts.setFloatUniform("uTitleLum", if (night) 0.93f else 0.45f)
        ts.setFloatUniform("uLogoSat", if (night) 0.47f else 0.82f)
        ts.setFloatUniform("uLogoLum", if (night) 0.94f else 0.46f)
        ts.setFloatUniform("uTranslate", 0f, 0f)
        ts.setFloatUniform("uLogoCenter", 0f, 0f)
    }

    fun advance(dt: Float) {
        animTime += dt
        advanceColors(dt)
        frameVersion++
        syncTitle()
        syncLogo()
    }

    /** 调色板在 3 套配色间往返插值（4 段往返 + smoothstep，与 AboutBgView.updateColors 一致）。 */
    private fun advanceColors(dt: Float) {
        colorT += dt / period
        while (colorT >= 1f) {
            colorT -= 1f
            colorSeg = (colorSeg + 1) % 4
        }
        val pal = if (night) AuroraPalette.DARK else AuroraPalette.LIGHT
        val seg = AuroraPalette.SEG[colorSeg]
        val a = pal[seg[0]]
        val b = pal[seg[1]]
        val t = colorT * colorT * (3f - 2f * colorT)
        for (i in 0 until 16) {
            colorsF[i] = a[i] + (b[i] - a[i]) * t
        }
    }

    private fun syncTitle() {
        val ts = titleShader
        val tv = titleView
        if (ts == null || tv == null) {
            // 兜底：调色板首色 → 可读强调色（与 AboutBgView 兜底一致）
            val c = AuroraSampler.deriveTitleColor(colorsF[0], colorsF[1], colorsF[2], night)
            if (AuroraSampler.colorDelta(c, lastTitleColor)) {
                lastTitleColor = c
                onTitleFallbackColor?.invoke(c)
            }
            return
        }
        tv.getLocationInWindow(loc)
        ts.setFloatUniform("uAnimTime", animTime)
        ts.setFloatUniform("uResolution", auroraSize.width, auroraSize.height)
        ts.setFloatUniform("uBound", 0f, 0f, 1f, 1f)
        ts.setFloatUniform("uColors", colorsF)
        ts.setFloatUniform("uTitleLum", if (night) 0.93f else 0.45f)
        ts.setFloatUniform(
            "uTranslate",
            loc[0] - auroraPosInWindow.x,
            loc[1] - auroraPosInWindow.y,
        )
        tv.invalidate()
    }

    private fun syncLogo() {
        val lv = logoView ?: return
        lv.getLocationInWindow(loc)
        val cx = loc[0] + lv.width * 0.5f - auroraPosInWindow.x
        val cy = loc[1] + lv.height * 0.5f - auroraPosInWindow.y
        val rgb = AuroraSampler.logoColor(
            cx, cy,
            auroraSize.width, auroraSize.height,
            colorsF, animTime, night,
        )
        val r = (rgb[0].coerceIn(0f, 1f) * 255f).toInt()
        val g = (rgb[1].coerceIn(0f, 1f) * 255f).toInt()
        val b = (rgb[2].coerceIn(0f, 1f) * 255f).toInt()
        val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        if (AuroraSampler.colorDelta(color, lastLogoColor)) {
            lastLogoColor = color
            onLogoColor?.invoke(color)
        }
    }
}

private fun argbColor(argb: Int): Color = Color(
    red = ((argb shr 16) and 0xFF) / 255f,
    green = ((argb shr 8) and 0xFF) / 255f,
    blue = (argb and 0xFF) / 255f,
    alpha = ((argb ushr 24) and 0xFF) / 255f,
)

/**
 * 「关于」页极光背景（Compose 绘制版，替代原 AndroidView(AboutBgView)）。
 *
 * - 极光 AGSL 与 AboutBgView.java 逐字一致（见 AuroraAgsl.kt）；
 * - 挂 [layerBackdrop] 后，背后内容（极光）被录进 GraphicsLayer，
 *   供卡片 `drawBackdrop` 实时模糊 → 毛玻璃流光卡；
 * - [gradientAlpha] 随滚动淡出（与旧版 setGradientAlpha 一致）；
 * - 标题/LOGO 采色由 [state] 每帧驱动（GPU 逐像素 / CPU 单点）。
 */
@Composable
internal fun AuroraBackground(
    state: AuroraState,
    night: Boolean,
    gradientAlpha: Float,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bgColor = remember(night) {
        ContextCompat.getColor(context, R.color.bg_surface) or (0xFF shl 24)
    }
    val shader = remember(night) {
        try {
            RuntimeShader(AGSL_BG)
        } catch (t: Throwable) {
            null
        }
    }
    // RuntimeShader 包成 Compose Brush（shader 实例不变，uniform 每帧更新后即时生效）
    val shaderBrush = remember(shader) {
        shader?.let { s ->
            object : ShaderBrush() {
                override fun createShader(size: Size): android.graphics.Shader = s
            }
        }
    }

    // 静态 uniform 只初始化一次（night 切换时重建 shader 后重设）
    LaunchedEffect(shader, night) {
        shader?.let { s ->
            s.setFloatUniform("uPoints", AURORA_POINTS)
            s.setFloatUniform("uTranslateY", 0f)
            s.setFloatUniform("uAlphaMulti", 1f)
            s.setFloatUniform("uNoiseScale", AuroraSampler.noiseScale())
            s.setFloatUniform("uPointOffset", AuroraSampler.pointOffset(night))
            s.setFloatUniform("uPointRadiusMulti", 1f)
            s.setFloatUniform("uSaturateOffset", AuroraSampler.saturateOffset(night))
            s.setFloatUniform("uLightOffset", AuroraSampler.lightOffset(night))
        }
    }

    // 每帧驱动（与 AboutBgView.postOnAnimation tick 同机制：dt 钳 0.1s）
    LaunchedEffect(state) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNs != 0L) {
                    val dt = ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
                    state.advance(dt)
                }
                lastNs = now
            }
        }
    }

    // 下部渐变色标（上亮下暗，5 色标拟合 smoothstep；与 AboutBgView.drawBottomFade 一致）
    val fadeStops = remember(bgColor) {
        val c0 = bgColor and 0x00FFFFFF
        arrayOf(
            0f to argbColor(c0),
            0.25f to argbColor(0x28000000 or c0),
            0.5f to argbColor(0x80000000.toInt() or c0),
            0.75f to argbColor(0xD7000000.toInt() or c0),
            1f to argbColor(bgColor),
        )
    }
    val fadeStartRatio = if (night) 0.64f else 0.5f
    val auroraResIds = remember {
        intArrayOf(R.color.about_aurora_1, R.color.about_aurora_2, R.color.about_aurora_3)
    }

    Canvas(
        modifier = modifier
            .onGloballyPositioned { coords: LayoutCoordinates ->
                state.auroraPosInWindow = coords.positionInWindow()
            }
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
    ) {
        // 读 frameVersion 注册每帧重绘依赖（Compose draw 阶段依赖，不触发重组）
        val frame = state.frameVersion
        if (frame < 0) return@Canvas
        state.auroraSize = size

        // 底：整屏铺页面底色（不乘 gradientAlpha，与旧版一致）
        drawRect(argbColor(bgColor))

        if (shader != null && shaderBrush != null) {
            shader.setFloatUniform("uAnimTime", state.animTime)
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uBound", 0f, 0f, 1f, 1f)
            shader.setFloatUniform("uColors", state.colorsF)
            drawRect(brush = shaderBrush, alpha = gradientAlpha)
        } else {
            // 静态兜底：竖向极光渐变（与 AboutBgView.drawFallback 一致）
            val stops = arrayOf(
                0f to argbColor(ContextCompat.getColor(context, auroraResIds[0])),
                0.6f to argbColor(ContextCompat.getColor(context, auroraResIds[1])),
                1f to argbColor(ContextCompat.getColor(context, auroraResIds[2])),
            )
            drawRect(brush = Brush.verticalGradient(colorStops = stops), alpha = gradientAlpha)
        }

        // 下部渐暗：极光自约 1/3 高度起渐次没入页面底色（上亮下暗）
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = fadeStops,
                startY = size.height * fadeStartRatio,
                endY = size.height,
            ),
            alpha = gradientAlpha,
        )
    }
}

/** 兼容旧调用点：无 backdrop 的纯背景用法。 */
@Composable
internal fun AuroraBackground(
    night: Boolean,
    gradientAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val state = remember(night) { AuroraState(night) }
    AuroraBackground(
        state = state,
        night = night,
        gradientAlpha = gradientAlpha,
        backdrop = null,
        modifier = modifier,
    )
}
