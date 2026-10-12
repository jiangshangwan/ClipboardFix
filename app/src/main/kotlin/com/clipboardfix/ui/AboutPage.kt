package com.clipboardfix.ui

import android.graphics.RuntimeShader
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.clipboardfix.AboutLogoView
import com.clipboardfix.R
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 首项占位高度：决定卡片整体「沉」多低。
 * 取值标准（以 411×914dp 屏幕为准）：让「为爱发电」卡片底边留在导航栏 view
 * （96dp、贴底，顶边 818dp）之上约 30dp——占位 450dp 时该卡底边约 786dp。
 */
private val HEADER_SPACER_DP = 450
private val AURORA_FADE_DP = 200f
private val LOGO_FADE_START_DP = 120f
private val LOGO_FADE_END_DP = 185f
private val CARD_REVEAL_END_DP = 480f

/** 上滑时头部「关于」小标题的淡入区间（构图期换算成 px）。 */
private val TITLE_FADE_START_DP = 60f
private val TITLE_FADE_END_DP = 140f

/**
 * 头部标题条：
 * · [BAR_SCRIM_ALPHA] —— 页底遮罩不透明度（实心段要足够实，卡片滑上来才不会被看穿）；
 * · [BAR_FADE_DP] —— 条底部渐隐带高度：遮罩在这段内线性溶到全透明（柔和边缘）；
 * · [BAR_BOTTOM_PAD_DP] —— 标题下留白，把渐隐带整体推到标题墨迹之下。
 *
 * 说明：这里**不做高斯模糊**。极光在 scrollY 200dp 处已淡尽，而标题正是 60dp 之后才出现——
 * 「条可见时背后极光已消失」，模糊极光等于没有效果；而模糊滚动内容（真做出 HyperLight
 * 那种卡片被糊掉）会因列表图层每帧位移而滞后数帧，实机表现为拖影。两版均已留档，需要再回炉。
 */
private const val BAR_SCRIM_ALPHA = 0.88f
private val BAR_FADE_DP = 40f
private val BAR_BOTTOM_PAD_DP = 52

/** 毛玻璃卡片最大模糊半径（极光明亮区）。 */
private val GLASS_MAX_BLUR_DP = 18f
/** 毛玻璃卡片圆角（与卡片视觉圆角一致，模糊层同形状被裁）。 */
private val GLASS_CARD_SHAPE = RoundedCornerShape(20.dp)

/** LOGO 图形色：浅色=纯白（深底白图形）；深色=深紫（浅底深图形，对齐设计稿 rgb(46,37,96)）。 */
private val GLYPH_LIGHT = 0xFFFFFFFF.toInt()
private val GLYPH_DARK = 0xFF2E2560.toInt()

@Composable
fun AboutPage(
    versionName: String,
    onOpenCoolapk: () -> Unit,
    onOpenWeibo: () -> Unit,
    onOpenGithub: () -> Unit,
    onReleases: () -> Unit,
    onDonate: () -> Unit,
    onOpenTelegram: () -> Unit,
    onUpstream: () -> Unit,
    onOpenMiuix: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val night = isSystemInDarkTheme()

    // 极光驱动状态 + 毛玻璃背后内容源（layerBackdrop 只录 Compose 绘制节点，
    // 故极光必须是 Compose 节点而非 AndroidView）
    val aurora = remember(night) { AuroraState(night) }
    val backdrop = rememberLayerBackdrop()

    // 滚动联动的淡出参数（对齐原「关于」页观感：上滑流光迅速消失、LOGO 先淡出）
    val scrollY by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) HEADER_SPACER_DP * density.density
            else listState.firstVisibleItemScrollOffset.toFloat()
        }
    }
    val auroraAlpha = (1f - (scrollY / (AURORA_FADE_DP * density.density)).coerceIn(0f, 1f))
    val logoProgress = ((scrollY - LOGO_FADE_START_DP * density.density) /
        ((LOGO_FADE_END_DP - LOGO_FADE_START_DP) * density.density)).coerceIn(0f, 1f)
    val headerAlpha = 1f - logoProgress
    val headerScale = 1f - 0.15f * logoProgress
    // 上滑淡入的「关于」小标题：与 LOGO 头部淡出（120→185dp）形成交接——
    // 起得早、收得早，避免两者同时半透明时的「双标题叠影」。
    val titleAlpha = ((scrollY - TITLE_FADE_START_DP * density.density) /
        ((TITLE_FADE_END_DP - TITLE_FADE_START_DP) * density.density)).coerceIn(0f, 1f)
    // 头部标题条参数（构图期换算一次）
    val barFadePx = with(density) { BAR_FADE_DP.dp.toPx() }
    val barScrimColor = pageBackgroundColor().copy(alpha = BAR_SCRIM_ALPHA)

    // 卡片「被极光照亮」与毛玻璃模糊半径均由 GlassCard 在 draw 阶段按 scrollY 计算
    // （滚动只重绘不重组，见 GlassCard 注释）；此处不再派生 cardAlpha/blurPx。

    // 标题 GPU 着色器（逐像素取背后极光色；创建失败走兜底单色）
    val titleShader = remember(night) {
        try {
            RuntimeShader(AGSL_TITLE)
        } catch (t: Throwable) {
            null
        }
    }

    // 头部 View（LOGO + 标题）：factory 只产出，注册逻辑跟随 night 变化重做
    var headerView by remember { mutableStateOf<View?>(null) }
    LaunchedEffect(night, headerView, aurora, titleShader) {
        val v = headerView ?: return@LaunchedEffect
        val logo = v.findViewById<AboutLogoView>(R.id.ivLogo)
        val title = v.findViewById<TextView>(R.id.tvAppTitle)
        logo.setGlyphTint(if (night) GLYPH_DARK else GLYPH_LIGHT)
        aurora.logoView = logo
        aurora.onLogoColor = { color -> logo.setPlateColor(color) }
        if (titleShader != null) {
            aurora.initTitleShader(titleShader)
            title.paint.shader = titleShader
            aurora.titleShader = titleShader
        } else {
            aurora.onTitleFallbackColor = { c -> title.setTextColor(c) }
        }
        aurora.titleView = title
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ① 极光背景（Compose AGSL，铺满整屏；滚动整体淡出；挂 layerBackdrop 供卡片模糊）
        AuroraBackground(
            state = aurora,
            night = night,
            gradientAlpha = auroraAlpha,
            backdrop = backdrop,
            modifier = Modifier.fillMaxSize(),
        )

        // ② 固定 LOGO + 标题头部（LOGO 底板由 CPU 单点采极光色，标题走 GPU 逐像素采色）。
        //    放在滚动列表之后（被列表覆盖），触摸由顶层 LazyColumn 统一处理。
        AndroidView(
            factory = { ctx ->
                val v = LayoutInflater.from(ctx).inflate(R.layout.view_about_header, null, false)
                headerView = v
                v
            },
            update = { v ->
                v.findViewById<AboutLogoView>(R.id.ivLogo)
                    .setGlyphTint(if (night) GLYPH_DARK else GLYPH_LIGHT)
                v.findViewById<TextView>(R.id.tvVersion).text = "V$versionName"
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = headerAlpha
                    scaleX = headerScale
                    scaleY = headerScale
                },
        )

        // ③ 滚动内容（透明，首项大占位透出背后 LOGO/极光，其后毛玻璃卡片升起覆盖）
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(HEADER_SPACER_DP.dp)) }

            item {
                Column {
                    SectionLabel("开发者")
                    GlassCard(backdrop = backdrop, night = night, scrollYProvider = { scrollY }) {
                        PersonRow(R.drawable.author_avatar, "江上晚", "酷安 @江上晚", onOpenCoolapk)
                        PersonRow(R.drawable.author_avatar_weibo, "李十六的日记本", "微博 @李十六的日记本", onOpenWeibo)
                    }
                }
            }

            item {
                Column {
                    SectionLabel("为爱发电")
                    GlassCard(backdrop = backdrop, night = night, scrollYProvider = { scrollY }) {
                        LinkRow(R.drawable.ic_heart, "您的支持就是我的最大动力", onDonate)
                    }
                }
            }

            item {
                Column {
                    SectionLabel("项目")
                    GlassCard(backdrop = backdrop, night = night, scrollYProvider = { scrollY }) {
                        LinkRow(R.drawable.ic_github, "GitHub", onOpenGithub)
                        LinkRow(R.drawable.ic_books, "更新日志", onReleases)
                    }
                }
            }

            item {
                Column {
                    SectionLabel("社群讨论")
                    GlassCard(backdrop = backdrop, night = night, scrollYProvider = { scrollY }) {
                        LinkRow(R.drawable.ic_qq, "腾讯QQ", onOpenTelegram)
                    }
                }
            }

            item {
                Column {
                    SectionLabel("检查更新")
                    GlassCard(backdrop = backdrop, night = night, scrollYProvider = { scrollY }) {
                        LinkRow(R.drawable.ic_check, "检查更新", onCheckUpdate,
                                summary = "检查 GitHub 上的新版本")
                    }
                }
            }

            item {
                Column {
                    SectionLabel("开源致谢")
                    GlassCard(backdrop = backdrop, night = night, scrollYProvider = { scrollY }) {
                        LinkRow(R.drawable.ic_bookshelf, "方案参考", onUpstream)
                        LinkRow(R.drawable.ic_link, "Miuix", onOpenMiuix)
                    }
                }
            }
        }

        // ④ 头部标题条 + 上滑淡入的「关于」小标题。
        //    背景 = 页面底色的竖向渐变遮罩：上部实心（把滑上来的卡片整条盖住 → 不再与标题重叠），
        //    底部 [BAR_FADE_DP] 内线性溶到全透明（柔和边缘，复刻 HyperLight 那种看不到下边缘的过渡）。
        //    放在 LazyColumn **之后**（同级最上层），自身不挂任何 pointerInput，不抢列表触摸。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer { alpha = titleAlpha }
                .drawWithContent {
                    val h = size.height.coerceAtLeast(1f)
                    val solidStop = ((h - barFadePx) / h).coerceIn(0f, 1f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to barScrimColor,
                                solidStop to barScrimColor,
                                1f to Color.Transparent,
                            ),
                            startY = 0f,
                            endY = h,
                        ),
                    )
                    drawContent()
                },
        ) {
            Text(
                text = "关于",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 22.dp, bottom = BAR_BOTTOM_PAD_DP.dp),
            )
        }
    }
}

/**
 * 毛玻璃流光卡：把背后极光实时模糊后透出，上覆半透明卡片色（随滚动变实心）。
 * 层级：clip(圆角) → drawBackdrop（背后极光模糊层）→ 卡片色罩 → 内容。
 *
 * 滚动性能：模糊半径与卡片色 alpha 都在 **draw 阶段**读 scrollYProvider（Compose draw
 * 依赖，滚动时只重绘、不触发重组）；另对模糊层做 2x 降采样（模糊本身低频，视觉几乎无损，
 * GPU 成本约降 4 倍）——缓解关于页滑动掉帧。
 */
@Composable
private fun GlassCard(
    backdrop: LayerBackdrop,
    night: Boolean,
    scrollYProvider: () -> Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val maxBlurPx = with(density) { GLASS_MAX_BLUR_DP.dp.toPx() }
    val auroraFadeEndPx = with(density) { AURORA_FADE_DP.dp.toPx() }
    val cardRevealEndPx = with(density) { CARD_REVEAL_END_DP.dp.toPx() }
    val minCardAlpha = if (night) 0.40f else 0.50f
    val cardColor = cardContainerColor()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GLASS_CARD_SHAPE)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { GLASS_CARD_SHAPE },
                effects = {
                    // RenderEffect 要求 radius > 0；极光淡出后收敛到 1px（近乎无感）
                    val auroraAlpha = 1f - (scrollYProvider() / auroraFadeEndPx).coerceIn(0f, 1f)
                    val r = (maxBlurPx * auroraAlpha).coerceAtLeast(1f)
                    blur(r, r)
                },
            )
            .drawWithContent {
                val sy = scrollYProvider()
                val cardAlpha = minCardAlpha + (1f - minCardAlpha) *
                    (sy / cardRevealEndPx).coerceIn(0f, 1f)
                drawRect(cardColor.copy(alpha = cardAlpha))
                drawContent()
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
    )
}

@Composable
private fun PersonRow(avatarRes: Int, name: String, handle: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .rowPressClickable(onClick),
        title = name,
        summary = handle,
        insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        startAction = {
            Image(
                painter = painterResource(avatarRes),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShapeLocal)
                    .border(2.dp, Color.White.copy(alpha = 0.68f), CircleShapeLocal),
            )
        },
        endActions = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(24.dp),
            )
        },
        onClick = null,
    )
}

@Composable
private fun LinkRow(iconRes: Int, title: String, onClick: () -> Unit, summary: String? = null) {
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .rowPressClickable(onClick),
        title = title,
        summary = summary,
        insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        startAction = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(24.dp),
            )
        },
        endActions = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(24.dp),
            )
        },
        onClick = null,
    )
}

private val CircleShapeLocal = RoundedCornerShape(percent = 50)
