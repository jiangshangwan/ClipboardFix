package com.clipboardfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

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
public class AboutBgView extends View {

    /** 顶部渐变带（顶栏）像素高度 = 状态栏 + 头部高（389dp）；之下即页面底色。 */
    private int mBandBottom = 0;
    /** 滚动时渐变整体淡出（对齐设计稿：背景层随滚动淡出）。 */
    private float mGradientAlpha = 1f;

    private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 头部区底部「没入页面底色」的收尾渐变（避免极光在带底出现硬边）。 */
    private final Paint mFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient mFadeShader;
    private int mFadeShaderBand = -1;
    private int mFadeShaderColor = 0;

    @Nullable
    private final RuntimeShader mShader;

    /** 模块名「跟随流光」着色器：复用同一段极光 AGSL，坐标经 uTranslate 对齐到背景。 */
    @Nullable
    private RuntimeShader mTitleShader;
    /** 模块名 View（TextureView 不可用时为 null，退回单色循环）。 */
    @Nullable
    private TextView mTitleView;
    /** 标题亮度的统一下压/上提值（浅色模式压暗成深字、深色模式提亮成浅字）。 */
    private float mTitleLum = 0.45f;

    /** LOGO 图标「跟随流光」着色器：与标题同源，色相取背景、饱和度/亮度拉到可读值。 */
    @Nullable
    private RuntimeShader mLogoShader;
    /** LOGO 图标 View（着色器不可用时为 null，保持原图）。 */
    @Nullable
    private AboutLogoView mLogoView;
    /** LOGO 目标饱和度 / 亮度（深色模式提亮，浅色模式压暗，保证与背景有反差）。 */
    private float mLogoSat = 0.62f;
    private float mLogoLum = 0.38f;

    private boolean mNight;

    // ---- 动画时钟（按真实 dt 推进，metaball 缓慢游动）----
    private float mAnimTime = 0f;
    private long mLastTime = 0;
    private boolean mRunning = false;

    // ---- 调色板循环（对齐 BgEffectDataManager 的 3 套配色 + 4 段往返插值）----
    private static final float[][] LIGHT = {
            {1.0f, 0.90f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f},
            {0.58f, 0.74f, 1.00f, 1.0f, 1.0f, 0.90f, 0.93f, 1.0f, 0.74f, 0.76f, 1.00f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f},
            {0.98f, 0.86f, 0.90f, 1.0f, 0.60f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.00f, 1.0f, 0.56f, 0.69f, 1.00f, 1.0f}
    };
    // 深色调色板：RGB 整体压暗（×0.75）→ 底色更「深」；色相 / 饱和度保留，深色下仍浓郁。
    private static final float[][] DARK = {
            {0.150f, 0.045f, 0.660f, 0.4f, 0.225f, 0.105f, 0.413f, 0.5f, 0.000f, 0.480f, 0.720f, 0.5f, 0.083f, 0.120f, 0.623f, 0.4f},
            {0.053f, 0.113f, 0.593f, 0.5f, 0.465f, 0.158f, 0.503f, 0.5f, 0.045f, 0.188f, 0.630f, 0.5f, 0.000f, 0.150f, 0.585f, 0.5f},
            {0.435f, 0.225f, 0.555f, 0.4f, 0.203f, 0.135f, 0.450f, 0.5f, 0.495f, 0.195f, 0.465f, 0.5f, 0.090f, 0.120f, 0.525f, 0.6f}
    };
    private static final int[][] TITLE_LIGHT = {
            {0x3B, 0x6F, 0xD4}, {0x8A, 0x5B, 0xD0}, {0xD4, 0x58, 0x8A}
    };
    private static final int[][] TITLE_DARK = {
            {0x6F, 0xA0, 0xFF}, {0xC7, 0x7F, 0xE0}, {0xFF, 0x8F, 0xB0}
    };
    // 4 段往返顺序：0→1→2→1→0→…
    // ⚠️ 必须是真正的「闭环」：每段的终点 = 下一段的起点，一圈走完首尾相接，肉眼才看不到循环接缝。
    //    旧值 {{1,0},{0,2},{1,2},{2,0}} 看着像 4 段，实则 seg1→seg2（终点 pal2 vs 起点 pal1）
    //    与 seg3→seg0（终点 pal0 vs 起点 pal1）各有一次硬跳变 —— 配色走到尾会「跳回重来」，
    //    正是用户反馈的「像一段动画循环播放，播到尾就从头开始」。已修正为往返闭环。
    private static final int[][] SEG = {{0, 1}, {1, 2}, {2, 1}, {1, 0}};

    private final float[] mColorsF = new float[16];
    private float mColorT = 0f;
    private int mColorSeg = 0;
    private float mColorPeriod;

    private OnTitleColorListener mTitleListener;
    private int mLastTitleColor = 0;

    // 上游 AGSL 实现（已剔除 UI 合成相关的 uTex/uTexBitmap 与未使用的 uShadow*，
    // 仅保留流体极光主体；作为 Paint 着色器直接绘制）。
    // 拆成「uniform 头 + 公共函数 + 两个 main」四段：背景用 BG_MAIN，模块名用 TITLE_MAIN。
    private static final String AGSL_HEADER =
            "uniform vec2 uResolution;" +
            "uniform float uAnimTime;" +
            "uniform vec4 uBound;" +
            "uniform vec2 uTranslate;" +      // 标题/LOGO 专用：View 局部坐标 → 背景坐标的平移（标题用）
            "uniform vec2 uLogoCenter;" +     // LOGO 专用：LOGO 中心点（窗口坐标），直接取背后极光色
            "uniform float uTranslateY;" +
            "uniform vec3 uPoints[4];" +
            "uniform vec4 uColors[4];" +
            "uniform float uAlphaMulti;" +
            "uniform float uNoiseScale;" +
            "uniform float uPointOffset;" +
            "uniform float uPointRadiusMulti;" +
            "uniform float uSaturateOffset;" +
            "uniform float uLightOffset;" +
            "uniform float uAlphaOffset;" +
            "uniform float uTitleLum;" +      // 标题专用：统一下压/上提亮度，保证可读
            "uniform float uLogoSat;" +       // LOGO 专用：目标饱和度
            "uniform float uLogoLum;" +       // LOGO 专用：目标亮度（与背景拉开反差）
            "";

    private static final String AGSL_FUNCS =
            "vec3 rgb2hsv(vec3 c){" +
            "  vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);" +
            "  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));" +
            "  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));" +
            "  float d = q.x - min(q.w, q.y);" +
            "  float e = 1.0e-10;" +
            "  return vec3(abs(q.z + (q.w - q.y)/(6.0*d + e)), d/(q.x + e), q.x);" +
            "}" +
            "vec3 hsv2rgb(vec3 c){" +
            "  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);" +
            "  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);" +
            "  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);" +
            "}" +
            "float hash(vec2 p){" +
            "  vec3 p3 = fract(vec3(p.xyx)*0.13);" +
            "  p3 += dot(p3, p3.yzx + 3.333);" +
            "  return fract((p3.x + p3.y)*p3.z);" +
            "}" +
            "float perlin(vec2 x){" +
            "  vec2 i = floor(x); vec2 f = fract(x);" +
            "  float a = hash(i); float b = hash(i + vec2(1.0,0.0));" +
            "  float c = hash(i + vec2(0.0,1.0)); float d = hash(i + vec2(1.0,1.0));" +
            "  vec2 u = f*f*(3.0-2.0*f);" +
            "  return mix(a,b,u.x) + (c-a)*u.y*(1.0-u.x) + (d-b)*u.x*u.y;" +
            "}" +
            "float gradientNoise(vec2 uv){" +
            "  return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));" +
            "}" +
            // 极光主体（背景与标题共用）：把「局部坐标」映射到带内 uv，叠加 4 枚 metaball + perlin 去饱和/提亮
            "vec4 aurora(vec2 fc){" +
            "  vec2 vUv = fc/uResolution;" +
            "  vUv.y = 1.0 - vUv.y;" +
            "  vec2 uv = vUv;" +
            "  uv -= vec2(0.0, uTranslateY);" +
            "  uv.xy -= uBound.xy; uv.xy /= uBound.zw;" +
            "  vec4 color = vec4(0.0);" +
            "  float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));" +
            "  for (int i = 0; i < 4; i++){" +
            "    vec4 pointColor = uColors[i];" +
            "    pointColor.rgb *= pointColor.a;" +
            "    vec2 point = uPoints[i].xy;" +
            "    float rad = uPoints[i].z * uPointRadiusMulti;" +
            "    point.x += sin(uAnimTime + point.y) * uPointOffset;" +
            "    point.y += cos(uAnimTime + point.x) * uPointOffset;" +
            "    float dd = distance(uv, point);" +
            "    float pct = smoothstep(rad, 0.0, dd);" +
            "    color.rgb = mix(color.rgb, pointColor.rgb, pct);" +
            "    color.a = mix(color.a, pointColor.a, pct);" +
            "  }" +
            "  float oppositeNoise = smoothstep(0.0, 1.0, noiseValue);" +
            "  color.rgb /= max(color.a, 1.0e-4);" +
            "  vec3 hsvC = rgb2hsv(color.rgb);" +
            "  hsvC.y = mix(hsvC.y, 0.0, oppositeNoise * uSaturateOffset);" +
            "  color.rgb = hsv2rgb(hsvC);" +
            "  color.rgb += oppositeNoise * uLightOffset;" +
            "  color.a = clamp(color.a, 0.0, 1.0);" +
            "  color.a *= uAlphaMulti;" +
            "  color += (10.0/255.0) * gradientNoise(fc.xy) - (5.0/255.0);" +
            "  return color;" +
            "}";

    /** 背景极光：直接输出预乘色（原逻辑不变）。 */
    private static final String AGSL_BG_MAIN =
            "vec4 main(vec2 fragCoord){" +
            "  vec4 color = aurora(fragCoord);" +
            "  return vec4(color.rgb * color.a, color.a);" +
            "}";

    /**
     * 模块名专用（实现「文字跟随流光照射变色」）：
     * 把「文字局部坐标」经 uTranslate 平移回「背景坐标」再取色 —— 于是文字每个像素都取到
     * 它正下方背景极光的颜色，文字像一块透出流光的玻璃（原实现依赖 MIUI 私有
     * MiuiBlurUtils 背景混色，不可移植；此处用标准 AGSL 等价实现）。
     * 最后把颜色归一为「色相跟随背景、饱和度与亮度统一」的强调色，保证任何背景下都清晰可读。
     */
    private static final String AGSL_TITLE_MAIN =
            "vec4 main(vec2 fragCoord){" +
            "  vec4 color = aurora(fragCoord + uTranslate);" +
            "  vec3 thsv = rgb2hsv(color.rgb / max(color.a, 1.0e-4));" +
            "  thsv.y = max(thsv.y, 0.55);" +
            "  thsv.z = uTitleLum;" +
            "  return vec4(hsv2rgb(thsv), 1.0);" +
            "}";

    /**
     * LOGO 图标专用（温和跟随流光、无幕布）：
     * code 50 曾改为完全静态紫底板；code 51 改为取「4 枚调色板颜色 RGB 平均」作为主导色相，
     * 但 4 色同相位轮转后均值几乎不动、且都是浅粉彩，强加固定饱和度/亮度后色相只在紫↔蓝间
     * 微弱摆动、整页周期又长达 20s，肉眼难辨（用户反馈「看不出在变」）。
     * 本版改为：直接取 LOGO 正后方那块极光的颜色（aurora(uLogoCenter)，与标题同源、同窗口坐标），
     * 得到的是「背后流光」的真实色 —— metaball 漂移 + 调色板轮转会让中心色持续流动，
     * LOGO 因此明显随流光变色；同时只取**中心点**（整块板是单一均匀色，而非把背景局部放大铺满），
     * 所以从根上杜绝了 code 50 之前「小实心板把背景色界放大成幕布」的问题。
     * 最后把饱和度/明度收敛到 night-aware 的 uLogoSat/uLogoLum，与背景拉开反差、保持品牌色。
     * uAnimTime / uColors 每帧由 syncGlowShaders 写入，LOGO 随极光持续呼吸。
     */
    private static final String AGSL_LOGO_MAIN =
            "vec4 main(vec2 fragCoord){" +
            "  vec4 color = aurora(uLogoCenter);" +
            "  vec3 hsv = rgb2hsv(color.rgb / max(color.a, 1.0e-4));" +
            "  hsv.y = max(hsv.y, uLogoSat);" +
            "  hsv.z = uLogoLum;" +
            "  return vec4(hsv2rgb(hsv), 1.0);" +
            "}";

    private static final String AGSL = AGSL_HEADER + AGSL_FUNCS + AGSL_BG_MAIN;
    private static final String AGSL_TITLE = AGSL_HEADER + AGSL_FUNCS + AGSL_TITLE_MAIN;
    private static final String AGSL_LOGO = AGSL_HEADER + AGSL_FUNCS + AGSL_LOGO_MAIN;

    public AboutBgView(Context context) {
        this(context, null);
    }

    public AboutBgView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AboutBgView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        mNight = isNight(context);
        mColorPeriod = mNight ? 8f : 5f;
        // 初始配色须与「mColorT=0 / mColorSeg=0」的插值结果一致（= pal[0]），
        // 否则首帧会从初始值跳到 pal[0]，开页瞬间闪一下。
        System.arraycopy((mNight ? DARK : LIGHT)[SEG[0][0]], 0, mColorsF, 0, 16);

        RuntimeShader shader = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = new RuntimeShader(AGSL);
                initShaderUniforms(shader);
            } catch (Throwable t) {
                shader = null;   // 着色器不可用时走静态兜底
            }
        }
        mShader = shader;
    }

    /** 设置一遍与主题相关的静态 uniform（uColors 随时间更新，uBound/uResolution 每帧更新）。 */
    private void initShaderUniforms(RuntimeShader s) {
        float[] pts = {0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f};
        s.setFloatUniform("uPoints", pts);
        s.setFloatUniform("uTranslateY", 0f);
        s.setFloatUniform("uAlphaMulti", 1f);
        s.setFloatUniform("uNoiseScale", 1.5f);
        s.setFloatUniform("uPointOffset", mNight ? 0.4f : 0.2f);
        s.setFloatUniform("uPointRadiusMulti", 1f);
        // 深色：更「浓郁」（少去饱和）+ 更深（少提亮），对齐「深色下色调更深也更亮眼」的观感
        s.setFloatUniform("uSaturateOffset", mNight ? 0.10f : 0.2f);
        s.setFloatUniform("uLightOffset", mNight ? 0.05f : 0.1f);
        s.setFloatUniform("uAlphaOffset", 0.5f);
        s.setFloatUniform("uColors", mColorsF);
    }

    public void setBandBottom(int px) {
        if (mBandBottom != px) {
            mBandBottom = px;
            invalidate();
        }
    }

    public void setGradientAlpha(float a) {
        a = Math.max(0f, Math.min(1f, a));
        if (mGradientAlpha != a) {
            mGradientAlpha = a;
            invalidate();
        }
    }

    /**
     * 让模块名「跟随流光照射变色」：
     * 把同一段极光 AGSL 挂到标题文字的 Paint 上，并用 uTranslate 把「文字局部坐标」平移回
     * 「背景坐标」，于是文字每个像素都取到它正下方背景极光的颜色——文字像一块透出流光的玻璃。
     * API 33 以下无 RuntimeShader 时自动退回单色循环（setTextColor）。
     */
    public void setTitleView(@Nullable TextView tv) {
        mTitleView = tv;
        // 深色模式标题提到高亮（对齐设计稿深色模式的亮紫标题，实测 V≈0.95）；
        // 浅色模式仍压暗成深字。原深色值 0.72 在暗极光上偏灰、和背景糊在一起。
        mTitleLum = mNight ? 0.93f : 0.45f;
        if (tv == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader ts = new RuntimeShader(AGSL_TITLE);
                initShaderUniforms(ts);
                ts.setFloatUniform("uTitleLum", mTitleLum);
                ts.setFloatUniform("uTranslate", 0f, 0f);
                mTitleShader = ts;
                tv.getPaint().setShader(ts);
                mTitleListener = null;      // 走着色器，不再需要单色回调
                return;
            } catch (Throwable t) {
                mTitleShader = null;        // 着色器不可用 → 退回单色
            }
        }
        mTitleListener = tv::setTextColor;  // 退回：单色循环
    }

    /**
     * 让 LOGO 图标「跟随后面的流光变色」——对图标与标题施加同一套背景混色。
     *
     * 目标结构是「圆角方形底板 + 高饱和紫渐变 + 纯白图形」；我们原先是白圆底盘 +
     * 紫色剪影，方向正好相反，而且剪影是从柔和 3D 渲染图里抠出来的，边缘带毛刺。
     * 本版改为同一结构：底板由极光着色器填满（色相随流光，饱和度/明度拉向实测目标值
     * 的深紫），图形用 ic_logo_glyph.png（纯白、几何重建、边缘干净）。
     * API 33 以下无 RuntimeShader 时底板退化为固定紫色，图形不变。
     */
    public void setLogoView(@Nullable AboutLogoView v) {
        mLogoView = v;
        // 目标值按「极光自身 S≈0.17 / V≈0.99」反解（见 AGSL_LOGO_MAIN 注释）。
        //  浅色模式：命中设计稿浅色实测的深紫底板（S≈0.74 / V≈0.49）。
        //  深色模式：对齐设计稿深色观感 —— 浅紫薰衣草底板（实测 S≈0.43 / V≈0.94），
        //            故反解 mLogoSat≈0.47 / mLogoLum≈0.94；图形另用深紫以形成反差。
        //            原深色值 0.78/0.52 底板偏暗，用户反馈「和背景快融为一体」。
        mLogoSat = mNight ? 0.47f : 0.82f;
        mLogoLum = mNight ? 0.94f : 0.46f;
        if (v == null) {
            return;
        }
        // 图形色随深浅色反色：浅色=纯白（深底白图形）；深色=深紫（浅底深图形）。
        v.setGlyphTint(mNight ? GLYPH_DARK : GLYPH_LIGHT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader ls = new RuntimeShader(AGSL_LOGO);
                initShaderUniforms(ls);
                ls.setFloatUniform("uLogoSat", mLogoSat);
                ls.setFloatUniform("uLogoLum", mLogoLum);
                ls.setFloatUniform("uTranslate", 0f, 0f);
                mLogoShader = ls;
                v.setGlowShader(ls);
            } catch (Throwable t) {
                mLogoShader = null;   // 着色器不可用 → 保持原图
            }
        }
    }

    /** 深色模式图形色：深紫（对齐设计稿深色图标实测 rgb(38,29,86)）。 */
    private static final int GLYPH_DARK = Color.rgb(46, 37, 96);
    /** 浅色模式图形色：纯白（原图色）。 */
    private static final int GLYPH_LIGHT = Color.WHITE;

    public void setOnTitleColorListener(@Nullable OnTitleColorListener l) {
        mTitleListener = l;
    }

    public interface OnTitleColorListener {
        void onTitleColor(int color);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || isInEditMode()) {
            return;
        }
        // ⭐ 极光铺满整屏（不再约束在头部带内）：uBound 覆盖全高，流光得以一路向下延伸、
        //    穿过卡片区；配合 drawBottomFade 的「上亮下暗」形成整屏连续渐变，
        //    卡片（半透明）便能透出背后的流光，而非只在顶部露一段。
        int band = h;

        // 底：整屏铺页面底色
        int bg = ContextCompat.getColor(getContext(), R.color.bg_surface) | 0xFF000000;
        mBasePaint.setColor(bg);
        mBasePaint.setAlpha((int) (255 * mGradientAlpha));
        canvas.drawColor(bg);

        if (mShader != null) {
            mShader.setFloatUniform("uAnimTime", mAnimTime);
            mShader.setFloatUniform("uResolution", (float) w, (float) h);
            // 全屏 uv（uBound 覆盖整个 View），metaball 铺满整屏
            mShader.setFloatUniform("uBound", 0f, 0f, 1f, 1f);
            mShaderPaint.setShader(mShader);
            mShaderPaint.setAlpha((int) (255 * mGradientAlpha));
            canvas.drawRect(0f, 0f, (float) w, (float) h, mShaderPaint);
        } else {
            // 静态兜底：竖向极光 + 缓慢游动的半透明色斑（保证不空白、不崩）
            drawFallback(canvas, w, h);
        }

        // 下部渐暗：极光自约 1/3 高度起渐次没入页面底色（上亮下暗的整屏渐变）。
        drawBottomFade(canvas, w, h, bg);

        // 跟随流光：每帧把背景的实时状态（时间/配色/尺寸）同步给标题/LOGO 着色器，
        // 并把「目标 View 相对本 View 的偏移」写入 uTranslate，使其取到它背后位置的极光色。
        syncGlowShaders(w, h, h);
    }

    /** 把背景每帧状态同步给「跟随流光」的着色器（见 setTitleView / setLogoView 说明）。 */
    private void syncGlowShaders(int w, int h, int band) {
        float height2 = (band > 0 ? (float) band : (float) h) / (float) h;
        int[] bgPos = new int[2];
        getLocationInWindow(bgPos);

        RuntimeShader ts = mTitleShader;
        TextView tv = mTitleView;
        if (ts != null && tv != null) {
            ts.setFloatUniform("uAnimTime", mAnimTime);
            ts.setFloatUniform("uResolution", (float) w, (float) h);
            ts.setFloatUniform("uBound", 0f, 1f - height2, 1f, height2);
            ts.setFloatUniform("uColors", mColorsF);
            int[] vPos = new int[2];
            tv.getLocationInWindow(vPos);
            ts.setFloatUniform("uTranslate",
                    (float) (vPos[0] - bgPos[0]), (float) (vPos[1] - bgPos[1]));
            tv.invalidate();
        }

        RuntimeShader ls = mLogoShader;
        AboutLogoView lv = mLogoView;
        if (ls != null && lv != null) {
            ls.setFloatUniform("uAnimTime", mAnimTime);
            ls.setFloatUniform("uResolution", (float) w, (float) h);
            ls.setFloatUniform("uBound", 0f, 1f - height2, 1f, height2);
            ls.setFloatUniform("uColors", mColorsF);
            int[] vPos = new int[2];
            lv.getLocationInWindow(vPos);
            float cx = (float) (vPos[0] - bgPos[0]) + lv.getWidth() * 0.5f;
            float cy = (float) (vPos[1] - bgPos[1]) + lv.getHeight() * 0.5f;
            ls.setFloatUniform("uTranslate",
                    (float) (vPos[0] - bgPos[0]), (float) (vPos[1] - bgPos[1]));
            // LOGO 中心点（窗口坐标）：直接采背后极光色，单一均匀色、无幕布
            ls.setFloatUniform("uLogoCenter", cx, cy);
            lv.invalidate();
        }
    }

    private void drawBottomFade(Canvas canvas, int w, int band, int bg) {
        if (band <= 0) {
            return;
        }
        if (mFadeShader == null || mFadeShaderBand != band || mFadeShaderColor != bg) {
            // ⭐ 渐暗起点约 50% 高度：极光在头部 + 首屏卡片区（≈ 0~480dp）保持明亮，
            //    卡片半透明时背后的流光才透得出来；之下平缓没入页面底色，整屏「上亮下暗」。
            // 渐暗起点：深色模式延后到 64% 高度 → 浓郁色调一路延伸到卡片区（整屏都被极光照射）；
            // 浅色维持 50%（浅色下过亮的极光压不住，反而发灰）。
            float fadeStart = band * (mNight ? 0.64f : 0.5f);
            // 用 5 个色标拟合成 smoothstep（t²(3-2t)）而非线性：
            // 线性渐变在两端与「纯极光」「纯底色」相接处会留下折角，长时间注视仍能看出一条带；
            // 缓入缓出后两端斜率为 0，与上下两段无缝焊接。
            int c0 = bg & 0x00FFFFFF;
            mFadeShader = new LinearGradient(0f, fadeStart, 0f, (float) band,
                    new int[]{c0, 0x28000000 | c0, 0x80000000 | c0, 0xD7000000 | c0, bg},
                    new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP);
            mFadeShaderBand = band;
            mFadeShaderColor = bg;
        }
        mFadePaint.setShader(mFadeShader);
        // 收尾遮罩随背景整体淡出一起收起：band=整屏（无「带底」概念，不会露硬边），
        // 因此可安全地乘 mGradientAlpha。完全上滑后 mGradientAlpha=0，遮罩随之消失，
        // 背景成为纯表面色（对齐 HyperCeiler：划上去无流光、背景变纯色）。
        mFadePaint.setAlpha((int) (255 * mGradientAlpha));
        canvas.drawRect(0f, 0f, (float) w, (float) band, mFadePaint);
    }

    private void drawFallback(Canvas canvas, int w, int band) {
        int c1 = ContextCompat.getColor(getContext(), R.color.about_aurora_1);
        int c2 = ContextCompat.getColor(getContext(), R.color.about_aurora_2);
        int c3 = ContextCompat.getColor(getContext(), R.color.about_aurora_3);
        mFallbackPaint.setShader(new LinearGradient(
                0f, 0f, 0f, band, new int[]{c1, c2, c3}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        mFallbackPaint.setAlpha((int) (255 * mGradientAlpha));
        canvas.drawRect(0f, 0f, (float) w, (float) band, mFallbackPaint);
    }

    // ---------------- 动画：仅在可见时流动 ----------------
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning || !isShown()) {
                mRunning = false;
                return;
            }
            long now = System.nanoTime();
            float dt = (now - mLastTime) * 1e-9f;
            mLastTime = now;
            if (dt > 0.1f) dt = 0.1f;
            mAnimTime += dt;
            updateColors(dt);
            invalidate();
            postOnAnimation(this);
        }
    };

    private void startAnim() {
        if (mRunning) return;
        mRunning = true;
        mLastTime = System.nanoTime();
        postOnAnimation(mTick);
    }

    private void stopAnim() {
        mRunning = false;
    }

    /** 调色板在 3 套配色间往返插值（4 段往返顺序）。 */
    private void updateColors(float dt) {
        mColorT += dt / mColorPeriod;
        while (mColorT >= 1f) {
            mColorT -= 1f;
            mColorSeg = (mColorSeg + 1) % 4;
        }
        float[][] pal = mNight ? DARK : LIGHT;
        int[] seg = SEG[mColorSeg];
        float[] a = pal[seg[0]];
        float[] b = pal[seg[1]];
        // smoothstep 缓动：让每段「起止的变化速度」都为 0。
        // 线性插值虽首尾相接，但在切换点存在导数突变（折角），长时间看仍有「一顿」的感觉；
        // 缓动后端与端之间过渡速度连续，整条循环像呼吸一样丝滑（对齐设计稿观感）。
        float t = mColorT * mColorT * (3f - 2f * mColorT);
        for (int i = 0; i < 16; i++) {
            mColorsF[i] = a[i] + (b[i] - a[i]) * t;
        }
        if (mShader != null) {
            mShader.setFloatUniform("uColors", mColorsF);
        }
        // 模块名跟随变色：取当前调色板首色（RGB），转为饱和、适合可读的强调色
        int title = deriveTitleColor(mColorsF[0], mColorsF[1], mColorsF[2]);
        if (Math.abs(title - mLastTitleColor) > 0x030303 && mTitleListener != null) {
            mLastTitleColor = title;
            mTitleListener.onTitleColor(title);
        }
    }

    private static int deriveTitleColor(float r, float g, float b) {
        float[] hsl = rgb2hsl(r, g, b);
        hsl[1] = Math.max(hsl[1], 0.55f);
        hsl[2] = isNightTint(hsl[2]) ? 0.93f : 0.45f;
        float[] rgb = hsl2rgb(hsl);
        return Color.rgb(clamp255(rgb[0]), clamp255(rgb[1]), clamp255(rgb[2]));
    }

    private static boolean isNightTint(float l) {
        return l > 0.5f; // 当前色偏亮 → 深色模式用浅色标题，否则用深色标题
    }

    private static float clamp255(float v) {
        int i = Math.round(v * 255f);
        if (i < 0) i = 0;
        if (i > 255) i = 255;
        return i;
    }

    // ---- RGB <-> HSL（用于把渐变首色转成可读强调色）----
    private static float[] rgb2hsl(float r, float g, float b) {
        float cMin = Math.min(r, Math.min(g, b));
        float cMax = Math.max(r, Math.max(g, b));
        float l = (cMax + cMin) / 2f;
        float h = 0f, s = 0f;
        if (cMax > cMin) {
            float d = cMax - cMin;
            s = l < 0f ? d / (cMax + cMin) : d / (2f - (cMax + cMin));
            if (r == cMax) h = (g - b) / d;
            else if (g == cMax) h = 2f + (b - r) / d;
            else h = 4f + (r - g) / d;
            if (h < 0f) h += 6f;
            h /= 6f;
        }
        return new float[]{h, s, l};
    }

    private static float[] hsl2rgb(float[] c) {
        float h = c[0], s = c[1], l = c[2];
        if (s == 0f) return new float[]{l, l, l};
        float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
        float p = 2f * l - q;
        float[] t = {h + 1f / 3f, h, h - 1f / 3f};
        float[] rgb = new float[3];
        for (int i = 0; i < 3; i++) {
            float tt = t[i];
            if (tt < 0f) tt += 1f;
            if (tt > 1f) tt -= 1f;
            float v;
            if (tt < 1f / 6f) v = p + (q - p) * 6f * tt;
            else if (tt < 1f / 2f) v = q;
            else if (tt < 2f / 3f) v = p + (q - p) * (2f / 3f - tt) * 6f;
            else v = p;
            rgb[i] = v;
        }
        return rgb;
    }

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnim();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) startAnim();
        else stopAnim();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) startAnim();
        else stopAnim();
    }
}
