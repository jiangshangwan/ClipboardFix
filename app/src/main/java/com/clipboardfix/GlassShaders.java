package com.clipboardfix;

/**
 * 液态玻璃渲染管线用到的 AGSL 着色器源码（零依赖，纯 framework API）。
 *
 * <p>来源与授权（经参考项目转引）：
 * <ul>
 *   <li>圆角矩形 SDF + 折射透镜（含色散变体）：Kyant0 的透镜实现（Apache-2.0），
 *       liuran001/WeChat-LiquidGlass 与 1812z/HyperIsland 同款。</li>
 *   <li>内阴影：WeChat-LiquidGlass 的 {@code INNER_SHADOW_SHADER}（MIT）。</li>
 *   <li>边缘镜面高光：HyperIsland 的 {@code IndicatorSpecular}（BloomStroke 双光源，
 *       Apache-2.0）的 Canvas 近似——顶边主光 + 底边副光，主光方向跟随重力。</li>
 *   <li>交互径向光晕：KernelSU / WeChat-LiquidGlass 的 InteractiveHighlight。</li>
 * </ul>
 *
 * <p>注意：AGSL 的 {@code main()} 返回的是预乘（premultiplied）颜色，白色半透明
 * 要写成 {@code half4(a, a, a, a)} 而不是 {@code (1, 1, 1, a)}，否则会过曝成纯白。
 */
final class GlassShaders {

    private GlassShaders() {
    }

    /** 圆角矩形 SDF 及其梯度（Kyant0，Apache-2.0）。 */
    static final String SDF = ""
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

    private static final String CIRCLE_MAP = "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n";

    /**
     * 折射透镜（无色散）——玻璃条本体。
     *
     * <p>只折射边缘 refractionHeight 范围内的一圈，中间原样透出模糊后的背景，
     * 这正是「液态玻璃」边缘弯曲、中心清晰的观感来源。
     */
    static final String LENS = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + SDF
            + CIRCLE_MAP
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

    /**
     * 折射透镜 + 边缘色散（7 采样光谱展开）——液滴。
     *
     * <p>chromaticAberration &gt; 0 时按红→紫 7 档微偏移采样再加权合成，
     * 边缘出现彩虹色散；偏移量与到中心的对角距离成正比，中间不受影响。
     */
    static final String LENS_DISPERSION = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + SDF
            + CIRCLE_MAP
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
            + "    float dispersionIntensity = chromaticAberration"
            + "            * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5; color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5; color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0; color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    /**
     * 液滴内阴影：从边缘向内平滑衰减（WeChat-LiquidGlass，MIT）。
     *
     * <p>直接画一圈描边会在内侧留下硬边，恰好压在折射带边界上显得分层；
     * 用同一套 SDF 做渐变才是真内阴影。
     */
    static final String INNER_SHADOW = ""
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float blur;\n"
            + "uniform float alpha;\n"
            + SDF
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float sd = sdRoundedRect(coord - halfSize, halfSize, radius);\n"
            + "    float t = 1.0 - smoothstep(0.0, blur, -sd);\n"
            + "    half a = half(alpha * t * t);\n"
            + "    return half4(0.0, 0.0, 0.0, a);\n"
            + "}\n";

    /**
     * 边缘镜面高光（HyperIsland BloomStroke 的近似）。
     *
     * <p>贴着圆角轮廓内侧的一圈白色辉光：整体 12% 打底，朝向主光（默认上方，
     * 跟随重力滑动）和副光（下方）的轮廓处各起一道亮弧，形成「双高点」镜面感。
     * 与普通描边的区别在于亮度沿法线方向连续衰减，读起来是光而不是线。
     */
    static final String RIM_HIGHLIGHT = ""
            + "uniform float2 size;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float2 light1;\n"
            + "uniform float2 light2;\n"
            + "uniform float blur;\n"
            + "uniform float alpha;\n"
            + SDF
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (sd > 0.0) { return half4(0.0); }\n"
            + "    float rim = smoothstep(-blur, 0.0, sd);\n"
            + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    float peak1 = pow(max(dot(grad, light1), 0.0), 3.0);\n"
            + "    float peak2 = pow(max(dot(grad, light2), 0.0), 2.0);\n"
            + "    float a = (0.12 + 0.5 * peak1 + 0.2 * peak2) * rim * alpha;\n"
            + "    return half4(half(a), half(a), half(a), half(a));\n"
            + "}\n";

    /**
     * 交互高光的径向光晕（KernelSU InteractiveHighlight，MIT）。
     *
     * <p>按压/拖动时贴着液滴位置的一团白色 bloom，混合模式 PLUS，
     * 模拟手指压住玻璃时的聚光。
     */
    static final String BLOOM = ""
            + "uniform float2 size;\n"
            + "uniform float alpha;\n"
            + "uniform float radius;\n"
            + "uniform float2 position;\n"
            + "half4 main(float2 coord) {\n"
            + "    float dist = distance(coord, position);\n"
            + "    float intensity = smoothstep(radius, radius * 0.5, dist);\n"
            + "    half a = half(alpha * intensity);\n"
            + "    return half4(a, a, a, a);\n"
            + "}\n";
}
