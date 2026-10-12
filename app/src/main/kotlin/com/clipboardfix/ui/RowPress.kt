package com.clipboardfix.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 行按压反馈：整行铺满的主题按压高亮，并把高亮裁成 14dp 圆角矩形。
 *
 * 背景：主题默认按压高亮是直角矩形（drawRect 铺满行宽），在圆角卡片内边缘显得生硬；
 * 旧版 XML 用 row_ripple 的 14dp 圆角 mask 裁切，本扩展等价复刻该观感。
 *
 * 用法：挂在行根 Modifier 最外层（fillMaxWidth 之后），配合不传 onClick 的行容器
 * （行容器自身的点击逻辑由此处的 clickable 提供，避免嵌套两个点击区）。
 *
 * 顺序要求：clip 必须在 clickable 【之前】（外层），否则圆角裁不掉按压高亮。
 */
@Composable
internal fun Modifier.rowPressClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    return this
        .clip(RoundedCornerShape(14.dp))
        .clickable(
            interactionSource = interactionSource,
            indication = indication,
            onClick = onClick,
        )
}
