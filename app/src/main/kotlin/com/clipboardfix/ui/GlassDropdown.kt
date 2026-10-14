package com.clipboardfix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 语言 / 主题 / 重启或重载的「液体玻璃」下拉弹卡。
 *
 * 玻璃材质与动画节奏完全对齐 HyperOS 系统级柔光玻璃观感：
 * 在 `drawBackdrop` 的效果链里依次做
 * **vibrancy（提饱和/提亮）→ blur(4dp) 基础模糊 → lens(10dp, 28dp, chromaticAberration)
 * 边缘透镜折射（带色散）**，再叠一层 70% 白色高光描边（Highlight）与 12dp 柔和投影（Shadow），
 * 表面铺 80% 不透明度的主题容器色；弹出 / 收起用同一条
 * `scale = 0.24 + 0.76 * fraction`（origin 顶部中心）的弹簧曲线。
 *
 * 为什么放在页面根 Box 的覆盖层、而不是独立弹窗：
 * 只有和页面内容处于同一 Compose 树，`layerBackdrop` 才能把**整页内容**录进
 * GraphicsLayer 作为折射/模糊的源，做出「真实液体玻璃」；独立 Window 拿不到本页内容，
 * 动画也不可控。
 *
 * 用法：
 * 1. 页面根 Box 内，把「需要被折射的内容」挂 `Modifier.layerBackdrop(backdrop)`；
 * 2. 行用 [GlassDropdownRow]（点击时把锚点坐标 + 选项打包成 [DropdownRequest] 交给页面）；
 * 3. 页面根 Box 末尾调用 [GlassDropdownOverlay] 渲染弹卡本体。
 */

/** 弹卡圆角。 */
internal val GLASS_CORNER_DP = 25.dp
/**
 * 弹卡四周留白：投影与高光都会画到卡片外，必须给它留出绘制空间，
 * 否则会被父布局裁掉（这也是弹卡定位时要整体外扩的量）。
 */
internal val GLASS_SHADOW_PAD_DP = 28.dp
/** 表面铺色透明度：越实越不透明，模糊透出越少。 */
internal const val GLASS_TINT_ALPHA = 0.80f
/** 表面铺色在浅色主题下固定用近白，深色主题直接取主题容器色。 */
internal val GLASS_TINT_LIGHT = Color(0xFFF8F8F8)

/** 基础模糊半径（px 由 dp 换算）。 */
internal val GLASS_BLUR_DP = 4.dp
/** 边缘透镜：折射高度 / 折射强度。 */
internal val GLASS_LENS_HEIGHT_DP = 10.dp
internal val GLASS_LENS_AMOUNT_DP = 28.dp
/** 高光描边不透明度。 */
internal const val GLASS_HIGHLIGHT_ALPHA = 0.70f
/** 投影半径 / 不透明度。 */
internal val GLASS_SHADOW_RADIUS_DP = 12.dp
internal const val GLASS_SHADOW_ALPHA = 0.35f

/** 弹卡固定宽度（= Miuix DropdownDefaults.MinWidth，与系统下拉菜单一致的小卡）。 */
internal val GLASS_MIN_WIDTH_DP = 200.dp
/** 弹卡与锚点行的相对位置：从锚点行顶部再往下这个比例处开始（菜单「盖住」触发行的观感）。 */
internal const val GLASS_ANCHOR_TOP_RATIO = 0.10f
/** 弹卡距屏幕上 / 下边缘的最小留白。 */
private const val DROPDOWN_EDGE_DP = 20f
/** 单项最小高度（与行高一致，点起来不局促）。 */
private const val DROPDOWN_ITEM_MIN_DP = 48
/** 单项左右内边距。 */
private const val ITEM_H_PAD_DP = 20f
/** 勾选框槽位：始终占位，保证各选项宽度一致（未选中时不画勾）。 */
private const val ITEM_CHECK_SLOT_DP = 20f
private const val ITEM_CHECK_GAP_DP = 12f

/** 弹出：弹簧稍软、回弹明显（stiffness 232）。 */
private const val ENTER_STIFFNESS = 232f
/** 收起：更硬更快（stiffness 400）。 */
private const val EXIT_STIFFNESS = 400f
internal const val GLASS_SPRING_DAMPING = 0.78f
/** 起止缩放：0.24 → 1，从锚点顶部「展开」。 */
private const val SCALE_MIN = 0.24f
/** 透明度时长：进场更快、退场更慢。 */
private const val ENTER_ALPHA_MS = 120
private const val EXIT_ALPHA_MS = 320

/** 一次「打开下拉」的请求：行被点击时按当前锚点位置构造。 */
internal class DropdownRequest(
    val items: List<String>,
    val selectedIndex: Int,
    val anchorLeft: Float,
    val anchorTop: Float,
    val anchorWidth: Int,
    val anchorHeight: Int,
    val onSelected: (Int) -> Unit,
)

/**
 * 下拉行：外观与 Miuix 的 DropdownPreference 一致（标题 + 当前值 + 下箭头），
 * 点击时把锚点位置（根坐标 px）与选项打包成 [DropdownRequest] 交给页面覆盖层渲染。
 *
 * 尾栏默认显示当前选中项；传入 [summary] 时改为显示该摘要文本——用于「重启或者重载」
 * 这类没有持久当前值的动作行（例如显示「需要 ROOT 权限」提示）。
 *
 * 锚点坐标存在**非状态**容器里：onGloballyPositioned 在滚动时每帧回调，
 * 若存进 state 会导致滚动时整页重组；这里只在点击那一刻读取。
 */
@Composable
internal fun GlassDropdownRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onOpen: (DropdownRequest) -> Unit,
    summary: String? = null,
) {
    val colors = MiuixTheme.colorScheme
    val anchor = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val idx = selectedIndex.coerceIn(0, items.size - 1)
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchor[0] = it }
            .rowPressClickable {
                val c = anchor[0] ?: return@rowPressClickable
                val p = c.positionInRoot()
                onOpen(
                    DropdownRequest(
                        items = items,
                        // 透传原始选中下标：-1 表示「无默认选中」（如重启或者重载），
                        // 弹卡不画勾；语言/主题行传合法下标，行为不变
                        selectedIndex = selectedIndex,
                        anchorLeft = p.x,
                        anchorTop = p.y,
                        anchorWidth = c.size.width,
                        anchorHeight = c.size.height,
                        onSelected = onSelected,
                    )
                )
            },
        title = title,
        insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        endActions = {
            Text(
                text = summary ?: items[idx],
                fontSize = 15.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                // 右箭头旋转 90° 即下拉指示箭头（不新增图标资源）
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 90f },
            )
        },
        onClick = null,
    )
}

/**
 * 覆盖层：全屏点击即关（不压暗背景，保持「轻」）+ 液体玻璃弹卡。
 *
 * [expanded] 为 false 时播收起动画，动画结束后内容才真正移除；期间仍用最后一次
 * 请求（[lastReq]）画出正在退场的弹卡，避免动画中途内容突然空掉。
 */
@Composable
internal fun GlassDropdownOverlay(
    request: DropdownRequest?,
    expanded: Boolean,
    backdrop: LayerBackdrop,
    rootHeightPx: Float,
    onDismiss: () -> Unit,
) {
    // 记住最后一次请求：关闭动画期间 request 已被页面清空，但仍要画得出来
    var lastReq by remember { mutableStateOf<DropdownRequest?>(null) }
    if (request != null) lastReq = request
    val req = request ?: lastReq ?: return

    // 用 Animatable 而非 AnimatedVisibility：显式从 0 起步，首帧一定会播入场动画
    // （AnimatedVisibility 若随请求一起进组合且 visible=true，会被判定「本来就可见」而跳过）。
    val fraction = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var hostVisible by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) {
        if (expanded) {
            hostVisible = true
            launch {
                fraction.animateTo(
                    1f,
                    spring(dampingRatio = GLASS_SPRING_DAMPING, stiffness = ENTER_STIFFNESS, visibilityThreshold = 0.0001f),
                )
            }
            launch { alpha.animateTo(1f, tween(ENTER_ALPHA_MS)) }
        } else if (hostVisible) {
            launch {
                fraction.animateTo(
                    0f,
                    spring(dampingRatio = GLASS_SPRING_DAMPING, stiffness = EXIT_STIFFNESS, visibilityThreshold = 0.0001f),
                )
            }
            alpha.animateTo(0f, tween(EXIT_ALPHA_MS))
            fraction.snapTo(0f)
            hostVisible = false
        }
    }

    if (!hostVisible) return

    val density = LocalDensity.current
    val padPx = with(density) { GLASS_SHADOW_PAD_DP.toPx() }
    val edgePx = with(density) { DROPDOWN_EDGE_DP.dp.toPx() }
    val cardWPx = with(density) { GLASS_MIN_WIDTH_DP.toPx() }

    // 位置（与系统下拉菜单一致）：
    //   横向 = 右对齐到锚点行的右边缘；
    //   纵向 = 从锚点行顶部再往下 10% 行高处开始 → 菜单会「盖住」触发行的后半段。
    // 整体再外扩 padPx（投影 / 高光留白），故左上角要减掉 padPx。
    var cardH by remember(req) { mutableIntStateOf(0) }
    val desiredTop = req.anchorTop + req.anchorHeight * GLASS_ANCHOR_TOP_RATIO
    val maxTop = (rootHeightPx - cardH - edgePx).coerceAtLeast(edgePx)
    val cardTop = if (cardH == 0) desiredTop else desiredTop.coerceIn(edgePx, maxTop)
    val cardLeft = req.anchorLeft + req.anchorWidth - cardWPx

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 透明层：点空白即关（不压暗，保持玻璃「轻盈」的观感）
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
    ) {
        GlassCard(
            request = req,
            backdrop = backdrop,
            fraction = fraction.value,
            alpha = alpha.value,
            onMeasured = { cardH = it },
            onDismiss = onDismiss,
            modifier = Modifier.offset {
                IntOffset(
                    (cardLeft - padPx).roundToInt(),
                    (cardTop - padPx).roundToInt(),
                )
            },
        )
    }
}

/**
 * 弹卡本体：`drawBackdrop`（背后页面实时折射 + 模糊）→ 表面铺色 → 内容。
 * 整块随 [fraction] 从 0.24 缩放到 1（origin 顶部中心）、随 [alpha] 淡入淡出。
 */
@Composable
private fun GlassCard(
    request: DropdownRequest,
    backdrop: LayerBackdrop,
    fraction: Float,
    alpha: Float,
    onMeasured: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val surface = colors.surfaceContainer
    val isLightTheme = surface.luminance() > 0.5f
    val tint = (if (isLightTheme) GLASS_TINT_LIGHT else surface).copy(alpha = GLASS_TINT_ALPHA)
    val shadowColor = if (isLightTheme) Color.Gray else Color.Black
    val shape = remember { RoundedCornerShape(GLASS_CORNER_DP) }

    Box(modifier.padding(GLASS_SHADOW_PAD_DP)) {
        Box(
            modifier = Modifier
                .width(GLASS_MIN_WIDTH_DP)
                .onGloballyPositioned { onMeasured(it.size.height) }
                .graphicsLayer {
                    val s = SCALE_MIN + (1f - SCALE_MIN) * fraction
                    scaleX = s
                    scaleY = s
                    this.alpha = alpha
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(GLASS_BLUR_DP.toPx())
                        lens(
                            GLASS_LENS_HEIGHT_DP.toPx(),
                            GLASS_LENS_AMOUNT_DP.toPx(),
                            chromaticAberration = true,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = GLASS_HIGHLIGHT_ALPHA) },
                    shadow = {
                        Shadow.Default.copy(
                            radius = GLASS_SHADOW_RADIUS_DP,
                            color = shadowColor,
                            alpha = GLASS_SHADOW_ALPHA,
                        )
                    },
                    innerShadow = null,
                    onDrawSurface = { drawRect(tint) },
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                request.items.forEachIndexed { i, item ->
                    val selected = i == request.selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = DROPDOWN_ITEM_MIN_DP.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                request.onSelected(i)
                                onDismiss()
                            }
                            .padding(horizontal = ITEM_H_PAD_DP.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item,
                            fontSize = 16.sp,
                            color = if (selected) colors.primary else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(ITEM_CHECK_GAP_DP.dp))
                        // 勾选槽位始终占位：各选项宽度一致，未选中时槽位留白
                        Box(
                            modifier = Modifier.size(ITEM_CHECK_SLOT_DP.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(ITEM_CHECK_SLOT_DP.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
