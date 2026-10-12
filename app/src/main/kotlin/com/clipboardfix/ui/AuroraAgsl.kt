package com.clipboardfix.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「关于」页极光 AGSL 与调色板逻辑 —— 自 AboutBgView.java 移植到 Compose 绘制层。
 *
 * 移植原因：Miuix blur 的 `layerBackdrop` 只能录制 **Compose 绘制节点**，原
 * `AndroidView(AboutBgView)` 是 View 宿主录不进去；把极光搬到 Compose 后，
 * 卡片即可用 `drawBackdrop` 实时模糊背后流光（毛玻璃流光卡）。
 *
 * GLSL 与 AboutBgView.java 逐字一致（仅排版为多行），保证观感不漂移。
 * AboutBgView.java 保留但不再实例化（极光改由 AuroraBackground.kt 绘制）。
 */

// ---- uniform 头（与 AboutBgView.java AGSL_HEADER 一致）----
internal const val AGSL_UNIFORMS = """
uniform vec2 uResolution;
uniform float uAnimTime;
uniform vec4 uBound;
uniform vec2 uTranslate;
uniform vec2 uLogoCenter;
uniform float uTranslateY;
uniform vec3 uPoints[4];
uniform vec4 uColors[4];
uniform float uAlphaMulti;
uniform float uNoiseScale;
uniform float uPointOffset;
uniform float uPointRadiusMulti;
uniform float uSaturateOffset;
uniform float uLightOffset;
uniform float uAlphaOffset;
uniform float uTitleLum;
uniform float uLogoSat;
uniform float uLogoLum;
"""

// ---- 公共函数（与 AboutBgView.java AGSL_FUNCS 一致）----
internal const val AGSL_FUNCS = """
vec3 rgb2hsv(vec3 c){
  vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y)/(6.0*d + e)), d/(q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c){
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float hash(vec2 p){
  vec3 p3 = fract(vec3(p.xyx)*0.13);
  p3 += dot(p3, p3.yzx + 3.333);
  return fract((p3.x + p3.y)*p3.z);
}
float perlin(vec2 x){
  vec2 i = floor(x); vec2 f = fract(x);
  float a = hash(i); float b = hash(i + vec2(1.0,0.0));
  float c = hash(i + vec2(0.0,1.0)); float d = hash(i + vec2(1.0,1.0));
  vec2 u = f*f*(3.0-2.0*f);
  return mix(a,b,u.x) + (c-a)*u.y*(1.0-u.x) + (d-b)*u.x*u.y;
}
float gradientNoise(vec2 uv){
  return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
}
vec4 aurora(vec2 fc){
  vec2 vUv = fc/uResolution;
  vUv.y = 1.0 - vUv.y;
  vec2 uv = vUv;
  uv -= vec2(0.0, uTranslateY);
  uv.xy -= uBound.xy; uv.xy /= uBound.zw;
  vec4 color = vec4(0.0);
  float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));
  for (int i = 0; i < 4; i++){
    vec4 pointColor = uColors[i];
    pointColor.rgb *= pointColor.a;
    vec2 point = uPoints[i].xy;
    float rad = uPoints[i].z * uPointRadiusMulti;
    point.x += sin(uAnimTime + point.y) * uPointOffset;
    point.y += cos(uAnimTime + point.x) * uPointOffset;
    float dd = distance(uv, point);
    float pct = smoothstep(rad, 0.0, dd);
    color.rgb = mix(color.rgb, pointColor.rgb, pct);
    color.a = mix(color.a, pointColor.a, pct);
  }
  float oppositeNoise = smoothstep(0.0, 1.0, noiseValue);
  color.rgb /= max(color.a, 1.0e-4);
  vec3 hsvC = rgb2hsv(color.rgb);
  hsvC.y = mix(hsvC.y, 0.0, oppositeNoise * uSaturateOffset);
  color.rgb = hsv2rgb(hsvC);
  color.rgb += oppositeNoise * uLightOffset;
  color.a = clamp(color.a, 0.0, 1.0);
  color.a *= uAlphaMulti;
  color += (10.0/255.0) * gradientNoise(fc.xy) - (5.0/255.0);
  return color;
}
"""

/** 背景极光：直接输出预乘色（与 AGSL_BG_MAIN 一致）。 */
internal const val AGSL_BG_MAIN = """
vec4 main(vec2 fragCoord){
  vec4 color = aurora(fragCoord);
  return vec4(color.rgb * color.a, color.a);
}
"""

/** 模块名专用：逐像素取背后极光色（与 AGSL_TITLE_MAIN 一致）。 */
internal const val AGSL_TITLE_MAIN = """
vec4 main(vec2 fragCoord){
  vec4 color = aurora(fragCoord + uTranslate);
  vec3 thsv = rgb2hsv(color.rgb / max(color.a, 1.0e-4));
  thsv.y = max(thsv.y, 0.55);
  thsv.z = uTitleLum;
  return vec4(hsv2rgb(thsv), 1.0);
}
"""

internal const val AGSL_BG = AGSL_UNIFORMS + AGSL_FUNCS + AGSL_BG_MAIN
internal const val AGSL_TITLE = AGSL_UNIFORMS + AGSL_FUNCS + AGSL_TITLE_MAIN

/** 4 枚 metaball 的静态参数（与 AboutBgView.initShaderUniforms 一致）。 */
internal val AURORA_POINTS = floatArrayOf(
    0.8f, 0.2f, 1.0f,
    0.8f, 0.9f, 1.0f,
    0.2f, 0.9f, 1.0f,
    0.2f, 0.2f, 1.0f,
)

/**
 * 调色板循环：3 套配色在 4 段往返顺序间 smoothstep 插值（与 AboutBgView.java 一致）。
 * 浅色周期 5s / 深色 8s。
 */
internal object AuroraPalette {

    val LIGHT = arrayOf(
        floatArrayOf(1.0f, 0.90f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f),
        floatArrayOf(0.58f, 0.74f, 1.00f, 1.0f, 1.0f, 0.90f, 0.93f, 1.0f, 0.74f, 0.76f, 1.00f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
        floatArrayOf(0.98f, 0.86f, 0.90f, 1.0f, 0.60f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.00f, 1.0f, 0.56f, 0.69f, 1.00f, 1.0f),
    )

    val DARK = arrayOf(
        floatArrayOf(0.150f, 0.045f, 0.660f, 0.4f, 0.225f, 0.105f, 0.413f, 0.5f, 0.000f, 0.480f, 0.720f, 0.5f, 0.083f, 0.120f, 0.623f, 0.4f),
        floatArrayOf(0.053f, 0.113f, 0.593f, 0.5f, 0.465f, 0.158f, 0.503f, 0.5f, 0.045f, 0.188f, 0.630f, 0.5f, 0.000f, 0.150f, 0.585f, 0.5f),
        floatArrayOf(0.435f, 0.225f, 0.555f, 0.4f, 0.203f, 0.135f, 0.450f, 0.5f, 0.495f, 0.195f, 0.465f, 0.5f, 0.090f, 0.120f, 0.525f, 0.6f),
    )

    /** 4 段往返闭环：0→1→2→1→0，首尾相接无跳变。 */
    val SEG = arrayOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(1, 0))

    fun period(night: Boolean): Float = if (night) 8f else 5f

    /** 初始配色须与 colorT=0/seg=0 的插值结果一致（= pal[SEG[0][0]]），否则首帧跳色。 */
    fun initialColors(night: Boolean): FloatArray =
        (if (night) DARK else LIGHT)[SEG[0][0]].copyOf()
}

/**
 * CPU 侧单点复刻 GLSL `aurora(fc)`，用于 LOGO 底板取色。
 * LOGO 的 AGSL_LOGO_MAIN 本来就是「取 uLogoCenter 单点色再收敛 S/L」，
 * 因此 CPU 单点采样与 GPU 采色等价（单点、无幕布），且省去跨 View 着色器同步。
 * 静态参数与 AboutBgView.initShaderUniforms 一致。
 */
internal object AuroraSampler {

    fun noiseScale(): Float = 1.5f
    fun pointOffset(night: Boolean): Float = if (night) 0.4f else 0.2f
    fun saturateOffset(night: Boolean): Float = if (night) 0.10f else 0.2f
    fun lightOffset(night: Boolean): Float = if (night) 0.05f else 0.1f

    /** 返回未收敛的极光色（RGB，0..1 浮动，可能超出后由调用方处理）。 */
    fun sampleRgb(
        fx: Float, fy: Float,
        resW: Float, resH: Float,
        colorsF: FloatArray,
        animTime: Float,
        night: Boolean,
    ): FloatArray {
        val vUvX = fx / resW
        val vUvY = 1f - fy / resH
        val uvX = vUvX
        val uvY = vUvY
        val noiseValue = perlin(
            vUvX * noiseScale() - animTime,
            vUvY * noiseScale() - animTime,
        )
        var cr = 0f; var cg = 0f; var cb = 0f; var ca = 0f
        for (i in 0 until 4) {
            val pr = colorsF[i * 4]; val pg = colorsF[i * 4 + 1]
            val pb = colorsF[i * 4 + 2]; val pa = colorsF[i * 4 + 3]
            var px = AURORA_POINTS[i * 3]; val py = AURORA_POINTS[i * 3 + 1]
            val rad = AURORA_POINTS[i * 3 + 2]
            val off = pointOffset(night)
            px += sin(animTime + py) * off
            val py2 = py + cos(animTime + px) * off
            val dx = uvX - px
            val dy = uvY - py2
            val dd = sqrt(dx * dx + dy * dy)
            val pct = smoothstep(rad, 0f, dd)
            val er = pr * pa; val eg = pg * pa; val eb = pb * pa
            cr += (er - cr) * pct; cg += (eg - cg) * pct; cb += (eb - cb) * pct
            ca += (pa - ca) * pct
        }
        val oppositeNoise = smoothstep(0f, 1f, noiseValue)
        val inv = 1f / max(ca, 1e-4f)
        var r = cr * inv; var g = cg * inv; var b = cb * inv
        val hsv = rgb2hsv(r, g, b)
        val sat = hsv[1] * (1f - oppositeNoise * saturateOffset(night))
        val add = oppositeNoise * lightOffset(night)
        val rgb = hsv2rgb(hsv[0], sat, hsv[2])
        r = rgb[0] + add; g = rgb[1] + add; b = rgb[2] + add
        return floatArrayOf(r, g, b)
    }

    /** LOGO 底板色：取单点极光色相，饱和度/明度收敛到目标值（与 AGSL_LOGO_MAIN 等价）。 */
    fun logoColor(
        fx: Float, fy: Float,
        resW: Float, resH: Float,
        colorsF: FloatArray,
        animTime: Float,
        night: Boolean,
    ): FloatArray {
        val rgb = sampleRgb(fx, fy, resW, resH, colorsF, animTime, night)
        val hsv = rgb2hsv(rgb[0], rgb[1], rgb[2])
        val logoSat = if (night) 0.47f else 0.82f
        val logoLum = if (night) 0.94f else 0.46f
        val sat = max(hsv[1], logoSat)
        return hsv2rgb(hsv[0], sat, logoLum)
    }

    // ---- 以下为 GLSL 函数的 CPU 对应实现（逐字照搬语义）----

    private fun fract(x: Float): Float = x - floor(x)

    private fun hash2(x: Float, y: Float): Float {
        var p3x = fract(x * 0.13f); var p3y = fract(y * 0.13f); var p3z = fract(x * 0.13f)
        // GLSL: vec3(p.xyx) → (x, y, x)
        val dotv = p3x * (p3y + 3.333f) + p3y * (p3z + 3.333f) + p3z * (p3x + 3.333f)
        p3x += dotv; p3y += dotv; p3z += dotv
        return fract((p3x + p3y) * p3z)
    }

    private fun perlin(x: Float, y: Float): Float {
        val ix = floor(x); val iy = floor(y)
        val fx = x - ix; val fy = y - iy
        val a = hash2(ix, iy)
        val b = hash2(ix + 1f, iy)
        val c = hash2(ix, iy + 1f)
        val d = hash2(ix + 1f, iy + 1f)
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        // mix(a,b,u.x) + (c-a)*u.y*(1-u.x) + (d-b)*u.x*u.y
        return a + (b - a) * ux + (c - a) * uy * (1f - ux) + (d - b) * ux * uy
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 与 GLSL rgb2hsv 一致。 */
    fun rgb2hsv(r: Float, g: Float, b: Float): FloatArray {
        val cMax = max(r, max(g, b))
        val cMin = min(r, min(g, b))
        val d = cMax - cMin
        var h = 0f
        val s = if (cMax > 1e-10f) d / cMax else 0f
        if (d > 1e-10f) {
            h = when (cMax) {
                r -> (g - b) / d
                g -> 2f + (b - r) / d
                else -> 4f + (r - g) / d
            }
            if (h < 0f) h += 6f
            h /= 6f
        }
        return floatArrayOf(h, s, cMax)
    }

    /** 与 GLSL hsv2rgb 一致（Sam Hocevar 算法，等价标准 HSV→RGB）。 */
    fun hsv2rgb(hIn: Float, s: Float, v: Float): FloatArray {
        if (s <= 0f) return floatArrayOf(v, v, v)
        val h = hIn - floor(hIn)
        val i = floor(h * 6f)
        val f = h * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        return when (i.toInt() % 6) {
            0 -> floatArrayOf(v, t, p)
            1 -> floatArrayOf(q, v, p)
            2 -> floatArrayOf(p, v, t)
            3 -> floatArrayOf(p, q, v)
            4 -> floatArrayOf(t, p, v)
            else -> floatArrayOf(v, p, q)
        }
    }

    /** 标题兜底单色：调色板首色 → 可读强调色（与 AboutBgView.deriveTitleColor 一致）。 */
    fun deriveTitleColor(r: Float, g: Float, b: Float, night: Boolean): Int {
        val hsl = rgb2hsl(r, g, b)
        val s = max(hsl[1], 0.55f)
        val l = if (hsl[2] > 0.5f) 0.93f else 0.45f
        val rgb = hsl2rgb(hsl[0], s, l)
        fun c255(x: Float): Int = (x * 255f).toInt().coerceIn(0, 255)
        return (c255(rgb[0]) shl 16) or (c255(rgb[1]) shl 8) or c255(rgb[2]) or (0xFF shl 24)
    }

    private fun rgb2hsl(r: Float, g: Float, b: Float): FloatArray {
        val cMin = min(r, min(g, b))
        val cMax = max(r, max(g, b))
        val l = (cMax + cMin) / 2f
        var h = 0f; var s = 0f
        if (cMax > cMin) {
            val d = cMax - cMin
            s = if (l < 0.5f) d / (cMax + cMin) else d / (2f - (cMax + cMin))
            h = when (cMax) {
                r -> (g - b) / d
                g -> 2f + (b - r) / d
                else -> 4f + (r - g) / d
            }
            if (h < 0f) h += 6f
            h /= 6f
        }
        return floatArrayOf(h, s, l)
    }

    private fun hsl2rgb(hIn: Float, s: Float, l: Float): FloatArray {
        if (s <= 0f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val rt = hue2rgb(p, q, hIn + 1f / 3f)
        val gt = hue2rgb(p, q, hIn)
        val bt = hue2rgb(p, q, hIn - 1f / 3f)
        return floatArrayOf(rt, gt, bt)
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** 供 AboutBgView 之外的兜底判断：色差是否够大（> 3/255 每通道）。 */
    fun colorDelta(a: Int, b: Int): Boolean {
        return abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > 3 ||
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) > 3 ||
            abs((a and 0xFF) - (b and 0xFF)) > 3
    }
}
