package com.clipboardfix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarData
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一条待展示的 Snackbar 消息；[long] 为 true 用长时长（约 10s），否则短时长（约 4s）。 */
internal class SnackMessage(val text: String, val long: Boolean)

/**
 * 全局 Snackbar 桥：Java 侧（AboutActivity 的各类提示）经 [show] 投递消息，由各页面
 * composition 内的 [PageSnackbarHost] 用 Miuix Snackbar 组件渲染——替代此前的系统
 * Toast 与自绘 TextView 提示条。
 *
 * 队列 / 时长 / 滑动关闭沿用 Miuix SnackbarHostState；玻璃面板（[GlassSnackbar]）的
 * 材质配方与下拉弹卡（GlassDropdown）完全一致，折射源是当前页面的内容。
 *
 * 通道用 CONFLATED：提示都是瞬时确认类消息，只保留最新一条，避免连续操作开关时
 * 排一长队过期提示。投递线程无关（Channel.trySend 无锁安全），后台线程可直接调用。
 */
object SnackbarBridge {

    private val channel = Channel<SnackMessage>(Channel.CONFLATED)
    private val flow = channel.receiveAsFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 四个页面共享同一个宿主状态——各页面 composition 只负责渲染，队列集中在这里驱动，
     * 避免每页各排一条队。跨 Activity 重建（切主题/语言）不重置：对象是进程级的。
     */
    val hostState = SnackbarHostState()

    private var started = false

    /** 启动队列泵（幂等）：把桥收到的消息逐条交给宿主状态。 */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            flow.collect { msg ->
                hostState.showSnackbar(
                    message = msg.text,
                    duration = if (msg.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            }
        }
    }

    fun show(text: String, long: Boolean = false) {
        channel.trySend(SnackMessage(text, long))
    }
}

/**
 * 页面级 Snackbar 宿主：放在页面根 Box 的末尾（不随内容滚动、位于下拉弹卡之上），
 * 提示条浮在底部液态玻璃导航条上方；留白区不消费触摸（仅提示条自身响应滑动关闭）。
 *
 * @param backdrop 页面内容登记的 layerBackdrop（玻璃折射源，与下拉弹卡同源）
 */
@Composable
fun PageSnackbarHost(backdrop: LayerBackdrop) {
    // Miuix 的 SnackbarHost 容器是 wrap-content（不填满），直接放进页面根 Box 会落到
    // 顶部；这里套一层fillMaxSize + BottomCenter 的 Box 把它按到页面底部。
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SnackbarHost(state = SnackbarBridge.hostState) { data ->
            GlassSnackbar(data = data, backdrop = backdrop)
        }
    }
}

/** Snackbar 圆角（对齐 Miuix SnackbarDefaults.CornerRadius）。 */
private val SNACK_CORNER_DP = 16.dp
/** 提示条距屏幕左右边缘的外边距。 */
private val SNACK_OUTER_H_DP = 12.dp
/**
 * 提示条底边抬升。Miuix SnackbarHost 自身还会加 12dp 底padding、玻璃面板另有 28dp
 * 投影留白，三者相加 = 110dp，与旧版自绘提示条的落点一致（导航胶囊顶边约 76dp）。
 */
private val SNACK_BOTTOM_DP = 70.dp
/** 面板文字内边距。 */
private val SNACK_TEXT_PAD_H_DP = 20.dp
private val SNACK_TEXT_PAD_V_DP = 14.dp

/**
 * 柔光玻璃 Snackbar：`drawBackdrop`（背后页面实时折射 + 模糊 + 色散）→ 表面铺色 → 文字，
 * 材质与动画节奏和 [GlassDropdownOverlay] 的弹卡同一套配方，视觉上与下拉小卡一致。
 */
@Composable
private fun GlassSnackbar(data: SnackbarData, backdrop: LayerBackdrop) {
    val colors = MiuixTheme.colorScheme
    val surface = colors.surfaceContainer
    val isLightTheme = surface.luminance() > 0.5f
    val tint = (if (isLightTheme) GLASS_TINT_LIGHT else surface).copy(alpha = GLASS_TINT_ALPHA)
    val shadowColor = if (isLightTheme) Color.Gray else Color.Black
    val shape = remember { RoundedCornerShape(SNACK_CORNER_DP) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 外边距 + 投影/高光留白（投影画到卡片外，必须给绘制空间）
            .padding(horizontal = SNACK_OUTER_H_DP + GLASS_SHADOW_PAD_DP)
            .padding(bottom = SNACK_BOTTOM_DP + GLASS_SHADOW_PAD_DP),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
            Text(
                text = data.visuals.message,
                fontSize = 15.sp,
                color = colors.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = SNACK_TEXT_PAD_H_DP,
                    vertical = SNACK_TEXT_PAD_V_DP,
                ),
            )
        }
    }
}
