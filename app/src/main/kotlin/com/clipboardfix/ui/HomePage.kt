package com.clipboardfix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val StatusGreen = androidx.compose.ui.graphics.Color(0xFF36D167)
private val StatusRed = androidx.compose.ui.graphics.Color(0xFFFF5A52)
private val StatusBgEnabledLight = androidx.compose.ui.graphics.Color(0xFFDFFAE4)
private val StatusBgDisabledLight = androidx.compose.ui.graphics.Color(0xFFFFE5E3)
private val StatusBgEnabledDark = androidx.compose.ui.graphics.Color(0xFF16321F)
private val StatusBgDisabledDark = androidx.compose.ui.graphics.Color(0xFF35191A)

@Composable
fun HomePage(
    statusText: String,
    imeCountText: String,
    statusEnabled: Boolean,
    statusRootGranted: Boolean,
    enabledFeatures: Int,
    badgesVisible: Boolean,
    appVersion: String,
    buildDate: String,
    deviceModel: String,
    androidVersion: String,
    systemVersion: String,
    clipboardVersion: String,
    abiInfo: String,
    onStatusCardClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 玻璃折射源：整页内容登记进 layerBackdrop，供 Snackbar 的柔光玻璃面板实时折射
    val snackbarBackdrop = rememberLayerBackdrop()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(snackbarBackdrop)
                .background(pageBackgroundColor())
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 120.dp),
            ) {
            Text(
                text = stringResource(R.string.home_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.home_subtitle),
                fontSize = 14.sp,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )

            // 模块状态卡：整卡可按压（Miuix Tilt 倾斜反馈），连续点击 10 次切换标识显隐
            StatusCard(
                statusText = statusText,
                imeCountText = imeCountText,
                statusEnabled = statusEnabled,
                statusRootGranted = statusRootGranted,
                enabledFeatures = enabledFeatures,
                badgesVisible = badgesVisible,
                onClick = onStatusCardClick,
            )

            Spacer(Modifier.height(18.dp))

            // 信息卡（卡片本身不加内边距：行的按压高亮需与卡片上下边缘齐平，
            // 与旧版 XML 一致；行间距由各行自身内边距负责——首行顶部 22dp、
            // 末行底部 22dp，均对齐旧版 fragment_home.xml）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InfoRow(stringResource(R.string.info_app_version), appVersion, topPad = 22.dp)
                    InfoRow(stringResource(R.string.info_device), deviceModel)
                    InfoRow(stringResource(R.string.info_android), androidVersion)
                    InfoRow(stringResource(R.string.info_system), systemVersion)
                    InfoRow(stringResource(R.string.info_clipboard_version), clipboardVersion)
                    InfoRow(stringResource(R.string.info_arch), abiInfo)
                    InfoRow(stringResource(R.string.info_lsposed), "API 102")
                    InfoRow(stringResource(R.string.info_build), buildDate, bottomPad = 22.dp)
                }
            }
        }
        // Snackbar 宿主：玻璃面板折射本页内容，浮在底部导航条上方
        PageSnackbarHost(backdrop = snackbarBackdrop)
    }
}

@Composable
private fun StatusCard(
    statusText: String,
    imeCountText: String,
    statusEnabled: Boolean,
    statusRootGranted: Boolean,
    enabledFeatures: Int,
    badgesVisible: Boolean,
    onClick: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val bg = when {
        statusEnabled && dark -> StatusBgEnabledDark
        statusEnabled -> StatusBgEnabledLight
        dark -> StatusBgDisabledDark
        else -> StatusBgDisabledLight
    }
    val accent = if (statusEnabled) StatusGreen else StatusRed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = bg),
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.module_status), fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        text = statusText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    // 已启用且标识可见：在「已启用」后加小字标识（已Root / 未Root）
                    if (statusEnabled && badgesVisible) {
                        Spacer(Modifier.width(8.dp))
                        RootBadge(
                            text = stringResource(
                                if (statusRootGranted) R.string.status_root else R.string.status_no_root,
                            ),
                            accent = accent,
                        )
                    }
                }
                Text(
                    text = imeCountText,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 已启用且标识可见：在「已启用输入法」下展示已启用的功能数量
                if (statusEnabled && badgesVisible) {
                    Text(
                        text = stringResource(R.string.enabled_features, enabledFeatures),
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Icon(
                painter = painterResource(if (statusEnabled) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled),
                contentDescription = null,
                tint = accent.copy(alpha = 0.82f),
                modifier = Modifier.size(96.dp),
            )
        }
    }
}

/**
 * 状态卡「已启用」后的小字标识：模块已被授予 su 时显示「已Root」，否则「未Root」。
 * 用强调色的低透明底 + 小号字，紧贴大号状态文字，不喧宾夺主。
 */
@Composable
private fun RootBadge(text: String, accent: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String, topPad: Dp = 12.dp, bottomPad: Dp = 12.dp) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = topPad, bottom = bottomPad)) {
        Text(text = title, fontSize = 17.sp, color = colors.onSurface)
        Text(text = value, fontSize = 15.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp))
    }
}
