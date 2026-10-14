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
import androidx.compose.foundation.rememberScrollState
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
    appVersion: String,
    buildDate: String,
    deviceModel: String,
    androidVersion: String,
    systemVersion: String,
    abiInfo: String,
    onStatusCardClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
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

        // 模块状态卡：整卡可按压（Miuix Tilt 倾斜反馈），点击弹出更新说明弹卡
        StatusCard(
            statusText = statusText,
            imeCountText = imeCountText,
            statusEnabled = statusEnabled,
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
                InfoRow(stringResource(R.string.info_arch), abiInfo)
                InfoRow(stringResource(R.string.info_lsposed), "API 102")
                InfoRow(stringResource(R.string.info_build), buildDate, bottomPad = 22.dp)
            }
        }
    }
}

@Composable
private fun StatusCard(statusText: String, imeCountText: String, statusEnabled: Boolean, onClick: () -> Unit) {
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
                Text(
                    text = statusText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = imeCountText,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
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

@Composable
private fun InfoRow(title: String, value: String, topPad: Dp = 12.dp, bottomPad: Dp = 12.dp) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = topPad, bottom = bottomPad)) {
        Text(text = title, fontSize = 17.sp, color = colors.onSurface)
        Text(text = value, fontSize = 15.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp))
    }
}
