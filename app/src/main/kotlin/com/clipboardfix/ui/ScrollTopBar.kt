/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 顶栏模糊半径（与关于页头部条完全同款）。 */
private val BAR_BLUR_DP = 16f
/** 页底色叠加在模糊之上的透明度。 */
private const val BAR_TINT_ALPHA = 0.30f
/** 顶栏标题上边距（状态栏下方，dp）：与关于页一致。 */
private const val BAR_TOP_PAD_DP = 22f
/** 顶栏标题横坐标（dp）：原页面标题左对齐位置。 */
private const val TITLE_START_X_DP = 22f
/** 标题字号：全程保持不变，与功能页原大标题一致。 */
private const val TITLE_START_SP = 30f
/** 顶栏标题下方留白：与关于页 BAR_BOTTOM_PAD_DP 一致，决定渐隐带厚度。 */
private const val BAR_BOTTOM_PAD_DP = 52f
/** 标题淡入的滚动区间（dp）：与关于页 60→140 一致，避免两者观感割裂。 */
private const val TITLE_FADE_START_DP = 60f
private const val TITLE_FADE_END_DP = 140f

/** 顶栏总高（含状态栏）：状态栏 + 标题上边距 + 标题字号 + 下留白。 */
@Composable
internal fun topBlurBarHeight(): Dp {
    val density = LocalDensity.current
    val statusBarDp = WindowInsets.statusBars.getTop(density) / density.density
    return (statusBarDp + BAR_TOP_PAD_DP + TITLE_START_SP + BAR_BOTTOM_PAD_DP).dp
}

/**
 * 由 [ScrollState] 派生顶部标题条的淡入进度：未滚动时完全透明，向上滑动越过
 * 标题所在区间后渐显，使内容滚到顶栏下方时被磨砂层覆盖、标题仍清晰可读。
 *
 * 用 derivedStateOf 包住滚动偏移的读取：滚动只推进快照值、不满足阈值时不触发重组。
 * 关于页自带大头部占位，沿用 60→140dp；功能页标题紧贴顶部，需由调用方收窄区间。
 */
@Composable
internal fun rememberScrollTopAlpha(
    scrollState: ScrollState,
    startDp: Float = TITLE_FADE_START_DP,
    endDp: Float = TITLE_FADE_END_DP,
): Float {
    val density = LocalDensity.current
    val alpha by remember(scrollState, startDp, endDp) {
        derivedStateOf {
            val start = startDp * density.density
            val end = endDp * density.density
            ((scrollState.value - start) / (end - start)).coerceIn(0f, 1f)
        }
    }
    return alpha
}

/**
 * 顶栏模糊层：只负责把滚动内容磨砂，不再自带标题——标题由 [MorphTopTitle] 单独绘制，
 * 避免与页面原标题形成两个副本。高度需覆盖状态栏，模糊因此可直接延伸到状态栏区域。
 *
 * 放在模糊来源容器（已 [dev.chrisbanes.haze.hazeSource] 的滚动内容）之后、同级最上层；
 * 自身不挂任何 pointerInput，不抢列表触摸。
 *
 * @param alpha 淡入进度，来自 [rememberScrollTopAlpha]
 * @param barHeight 顶栏总高（含状态栏），由调用方按当前 insets 换算
 */
@Composable
internal fun BoxScope.ScrollTopBlurBar(
    hazeState: HazeState,
    pageBg: Color,
    alpha: Float,
    barHeight: Dp,
    tintAlpha: Float = BAR_TINT_ALPHA,
    blurDp: Float = BAR_BLUR_DP,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(barHeight)
            .graphicsLayer { this.alpha = alpha }
            .hazeEffect(hazeState) {
                blurRadius = blurDp.dp
                // 透明底：让 progressive 的 alpha 掩码单独控制模糊强度自上而下归零，
                // 底边才能柔和化入内容（设不透明页底色会变硬边）。
                backgroundColor = pageBg.copy(alpha = 0f)
                noiseFactor = 0f
                tints = listOf(HazeTint(pageBg.copy(alpha = tintAlpha)))
                progressive = HazeProgressive.Brush(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Black.copy(alpha = 0f),
                    ),
                )
            },
    )
}

/**
 * 唯一标题：固定在首屏原位，字号 / 字重 / 位置全程不变。滚动内容中不画第二个标题，
 * 只留同等高度的占位；上滑时是背后的磨砂层淡入，标题本身不移动、不缩放。
 *
 * @param titleTopDp 标题距状态栏下方的距离（dp）
 */
@Composable
internal fun BoxScope.MorphTopTitle(
    title: String,
    titleTopDp: Float,
) {
    Text(
        text = title,
        fontSize = TITLE_START_SP.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .offset(x = TITLE_START_X_DP.dp, y = titleTopDp.dp),
    )
}
