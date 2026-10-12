package com.clipboardfix.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主题：包一层 MiuixTheme，统一走 Miuix 的配色 / 字体 / squircle 圆角体系。
 * 跟随系统深浅色（ColorSchemeMode.System），不启用 Monet 动态取色——保证确定性、
 * 全设备一致（模块需在任意小米机型上稳定呈现）。
 */
@Composable
fun ClipboardFixTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val controller = ThemeController(
        colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
    )
    MiuixTheme(controller = controller, content = content)
}

/**
 * 页面底色：浅色=浅灰、深色=近黑。
 * Miuix 默认主题里 surfaceContainer 与 background 过于接近，卡片会糊进背景（用户反馈
 * 「卡片 UI 没有了」）；这里显式拉开「灰底 / 白卡」的确定性对比（对齐原版 card_bg/bg_surface 观感）。
 */
@Composable
fun pageBackgroundColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF16161A) else Color(0xFFF2F2F4)

/** 卡片底色：浅色=纯白、深色=抬升深灰，与页面底色形成明确对比。 */
@Composable
fun cardContainerColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF242428) else Color(0xFFFFFFFF)
