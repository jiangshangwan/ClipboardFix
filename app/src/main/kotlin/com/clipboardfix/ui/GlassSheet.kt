package com.clipboardfix.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 居中「柔光玻璃」弹卡：材质与动画配方完全沿用功能页语言 / 主题下拉弹卡
 * （[GlassDropdownOverlay]）——`drawBackdrop` 效果链里依次做
 * **vibrancy → blur(4dp) → lens(10dp, 28dp, chromaticAberration)**，再叠 70% 高光描边与
 * 12dp 柔和投影，表面铺 80% 主题容器色；出场用同一条弹簧曲线。
 *
 * 与下拉弹卡的差异只有形态：下拉卡是锚定在触发行下方的小卡，这里换成**居中大卡**
 * （可滚动、可带底部操作按钮），用来承载更新日志、打赏二维码这类内容较长的弹卡。
 *
 * 为什么必须做成 Compose 覆盖层而不是 View 弹窗：只有与页面内容同处一棵 Compose 树，
 * `layerBackdrop` 才能把整页内容录进 GraphicsLayer 作为折射 / 模糊源；独立 Window
 * 拿不到本页内容，做不出真实的液体玻璃。
 *
 * 用法（三步，与 Snackbar 桥同风格）：
 * 1. 页面根 Box 内把「需要被折射的内容」挂 `Modifier.layerBackdrop(backdrop)`；
 * 2. 页面用 [GlassSheetBridge.bind] 注册一个请求槽位（[DisposableEffect] 解绑）；
 * 3. 页面根 Box 末尾调用 [PageGlassSheetOverlay] 渲染弹卡本体；
 *    外部（含 Java / Activity 侧）只需 [GlassSheetBridge.show] 投递请求。
 */

/** 弹卡内一个可点击动作行（图标 + 标题 + 可选摘要）。 */
internal class GlassSheetAction(
    val iconRes: Int,
    val title: String,
    val summary: String? = null,
    val onClick: () -> Unit,
)

/** 弹卡内容形态：覆盖现有四类弹卡（更新日志 / 捐赠菜单 / 二维码 / 名单）。 */
internal sealed interface GlassSheetContent {
    /** 分段文本（更新日志）：每段 = 小标题 + 正文。 */
    class Blocks(val blocks: List<Pair<String, String>>) : GlassSheetContent

    /** 动作列表（捐赠菜单）：点某项通常直接切换成另一张弹卡，故不做「先关后开」。 */
    class Actions(val actions: List<GlassSheetAction>) : GlassSheetContent

    /** 图片 + 备注（打赏二维码）。 */
    class Picture(val bitmap: Bitmap, val note: String) : GlassSheetContent

    /** 纯文本行（捐赠名单）。 */
    class Lines(val lines: List<String>) : GlassSheetContent

    /** 单段纯文本提示（如主开关关闭确认的正文）。 */
    class Message(val text: String) : GlassSheetContent
}

/** 一次「打开弹卡」的请求。[onAction] 执行后弹卡自动关闭。 */
internal class GlassSheetRequest(
    val title: String,
    val content: GlassSheetContent,
    val actionText: String? = null,
    val onAction: (() -> Unit)? = null,
    /** 取消按钮文案（双按钮弹卡时传入，使用 miuiX 文字按钮）。 */
    val cancelText: String? = null,
    /** 取消按钮点击回调；执行后弹卡自动关闭。 */
    val onCancel: (() -> Unit)? = null,
)

/**
 * 弹卡桥：页面注册槽位，任意处投递请求。与 [SnackbarBridge] 同风格——
 * 各页面共享同一份「当前请求」状态（广播到所有已绑定页面），弹卡只由**当前可见页**渲染，
 * 因此从功能页投递的弹窗也能正确显示在功能页，不再受「单槽位最后绑定者覆盖」影响
 * （此前 AboutPage 在 FeaturePage 之后绑定，会抢走唯一槽位，导致功能页弹窗发到 AboutPage
 * 而不可见）。
 */
internal object GlassSheetBridge {
    private val hosts = mutableSetOf<MutableState<GlassSheetRequest?>>()
    private val lock = Any()

    fun bind(state: MutableState<GlassSheetRequest?>) {
        synchronized(lock) { hosts.add(state) }
    }

    fun unbind(state: MutableState<GlassSheetRequest?>) {
        synchronized(lock) { hosts.remove(state) }
    }

    fun show(request: GlassSheetRequest) {
        synchronized(lock) { hosts.forEach { it.value = request } }
    }

    fun close() {
        synchronized(lock) { hosts.forEach { it.value = null } }
    }

    fun isOpen(): Boolean = synchronized(lock) { hosts.any { it.value != null } }
}

/** 大卡起止缩放：比小下拉卡（0.24）收敛得多——大卡从中心「炸开」会显得夸张。 */
private const val SHEET_SCALE_MIN = 0.90f
/** 卡片最大高度占屏幕高度的比例（超出则内容区滚动）。 */
private const val SHEET_MAX_H_RATIO = 0.72f
/** 弹卡最大宽度（dp）：与系统 Dialog / 页面卡片观感一致，宽屏时不至于拉成一条。 */
private const val SHEET_MAX_W_DP = 420
/** 卡片内边距。 */
private const val SHEET_PAD_H_DP = 20
private const val SHEET_PAD_V_DP = 18
/** 标题字号。 */
private const val SHEET_TITLE_SP = 20
/** 二维码边长。 */
private const val QR_SIZE_DP = 220
/** 行圆角（按压反馈的裁切形状）。 */
private const val SHEET_ROW_CORNER_DP = 12

/**
 * 覆盖层：全屏点击即关（不压暗背景，保持玻璃「轻盈」）+ 居中柔光玻璃弹卡。
 *
 * [request] 为 null 时播收起动画，动画结束后内容才真正移除；期间仍用最后一次请求
 * （[lastReq]）画出正在退场的弹卡，避免动画中途内容突然空掉。
 */
@Composable
internal fun PageGlassSheetOverlay(
    request: GlassSheetRequest?,
    backdrop: LayerBackdrop,
    onDismiss: () -> Unit,
) {
    var lastReq by remember { mutableStateOf<GlassSheetRequest?>(null) }
    if (request != null) lastReq = request
    val req = request ?: lastReq ?: return

    // 显式从 0 起步，保证首帧一定播入场动画（AnimatedVisibility 随请求一起进组合时
    // 会被判定「本来就可见」而跳过入场）。
    val fraction = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var hostVisible by remember { mutableStateOf(false) }

    LaunchedEffect(request != null) {
        if (request != null) {
            hostVisible = true
            launch {
                fraction.animateTo(
                    1f,
                    spring(
                        dampingRatio = GLASS_SPRING_DAMPING,
                        stiffness = ENTER_STIFFNESS,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            launch { alpha.animateTo(1f, tween(ENTER_ALPHA_MS)) }
        } else if (hostVisible) {
            launch {
                fraction.animateTo(
                    0f,
                    spring(
                        dampingRatio = GLASS_SPRING_DAMPING,
                        stiffness = EXIT_STIFFNESS,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
            alpha.animateTo(0f, tween(EXIT_ALPHA_MS))
            fraction.snapTo(0f)
            hostVisible = false
        }
    }

    if (!hostVisible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 透明层：点空白即关（不压暗，保持玻璃轻盈的观感）
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        GlassSheetCard(
            request = req,
            backdrop = backdrop,
            fraction = fraction.value,
            alpha = alpha.value,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 弹卡本体：`drawBackdrop`（背后页面实时折射 + 模糊）→ 表面铺色 → 标题 / 内容 / 操作按钮。
 * 整块随 [fraction] 从 [SHEET_SCALE_MIN] 缩放到 1（origin 中心）、随 [alpha] 淡入淡出。
 */
@Composable
private fun GlassSheetCard(
    request: GlassSheetRequest,
    backdrop: LayerBackdrop,
    fraction: Float,
    alpha: Float,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val surface = colors.surfaceContainer
    val isLightTheme = surface.luminance() > 0.5f
    val tint = (if (isLightTheme) GLASS_TINT_LIGHT else surface).copy(alpha = GLASS_TINT_ALPHA)
    val shadowColor = if (isLightTheme) Color.Gray else Color.Black
    val shape = remember { RoundedCornerShape(GLASS_CORNER_DP) }
    val maxH = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_H_RATIO).dp

    Box(modifier = Modifier.padding(GLASS_SHADOW_PAD_DP)) {
        Box(
            modifier = Modifier
                .widthIn(max = SHEET_MAX_W_DP.dp)
                .fillMaxWidth()
                .heightIn(max = maxH)
                // 吞掉卡片范围内的点击：否则点在分隔线 / 内边距等非交互区会穿透到
                // 覆盖层的「点空白关闭」，把弹卡误关掉（子节点的可点击项优先消费，不受影响）。
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .graphicsLayer {
                    val s = SHEET_SCALE_MIN + (1f - SHEET_SCALE_MIN) * fraction
                    scaleX = s
                    scaleY = s
                    this.alpha = alpha
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
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
                    .padding(horizontal = SHEET_PAD_H_DP.dp, vertical = SHEET_PAD_V_DP.dp),
            ) {
                Text(
                    text = request.title,
                    fontSize = SHEET_TITLE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                // 内容区：超过卡片最大高度时内部滚动
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    when (val c = request.content) {
                        is GlassSheetContent.Blocks -> BlocksBody(c)
                        is GlassSheetContent.Actions -> ActionsBody(c)
                        is GlassSheetContent.Picture -> PictureBody(c)
                        is GlassSheetContent.Lines -> LinesBody(c)
                        is GlassSheetContent.Message -> MessageBody(c)
                    }
                }
                if (request.cancelText != null) {
                    // 双按钮：取消（miuiX 文字按钮）+ 确认（miuiX 实心按钮）
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            text = request.cancelText,
                            onClick = {
                                request.onCancel?.invoke()
                                onDismiss()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                request.onAction?.invoke()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(request.actionText ?: "")
                        }
                    }
                } else if (request.actionText != null) {
                    Spacer(Modifier.height(14.dp))
                    ActionButton(text = request.actionText) {
                        request.onAction?.invoke()
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** 更新日志：版本号用强调色加粗，正文行距略放宽（对齐原 View 弹卡的排版）。 */
@Composable
private fun BlocksBody(content: GlassSheetContent.Blocks) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        for (block in content.blocks) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = block.first,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = block.second,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = colors.onSurface,
                )
            }
        }
    }
}

/**
 * 动作列表（捐赠菜单）。点击项通常直接投递另一张弹卡（如「打赏码」），这里**不**先关
 * 再开——否则后一步的 close 会把刚投递的请求一起清掉；改为直接替换内容，观感更连贯。
 */
@Composable
private fun ActionsBody(content: GlassSheetContent.Actions) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        content.actions.forEachIndexed { i, action ->
            if (i > 0) SheetDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SHEET_ROW_CORNER_DP.dp))
                    .rowPressClickable { action.onClick() }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = null,
                    tint = colors.onSurfaceVariantSummary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = action.title, fontSize = 16.sp, color = colors.onSurface)
                    if (action.summary != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = action.summary,
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = colors.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 图片 + 备注（打赏二维码）。 */
@Composable
private fun PictureBody(content: GlassSheetContent.Picture) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = content.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(QR_SIZE_DP.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = content.note,
            fontSize = 14.sp,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/** 纯文本行（捐赠名单），行间用细分隔线。 */
@Composable
private fun LinesBody(content: GlassSheetContent.Lines) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        content.lines.forEachIndexed { i, line ->
            if (i > 0) SheetDivider()
            Text(
                text = line,
                fontSize = 16.sp,
                color = colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** 弹卡底部主操作（如「保存到相册」）：胶囊按钮，靠右。 */
@Composable
private fun ColumnScope.ActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.End)
            .clip(RoundedCornerShape(percent = 50))
            .background(MiuixTheme.colorScheme.primary)
            .rowPressClickable(onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/** 弹卡内分隔线：用主色低透明度叠加，深浅主题都柔和。 */
@Composable
private fun SheetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

/** 单段纯文本提示（无小标题，直接展示正文）。 */
@Composable
private fun MessageBody(content: GlassSheetContent.Message) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = content.text,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = colors.onSurface,
    )
}
