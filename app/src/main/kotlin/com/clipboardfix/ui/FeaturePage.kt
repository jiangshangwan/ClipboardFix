package com.clipboardfix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FeaturePage(
    swImeUnlock: Boolean,
    swUnlimitCount: Boolean,
    swPanelColor: Boolean,
    iconHidden: Boolean,
    onToggleImeUnlock: (Boolean) -> Unit,
    onToggleUnlimitCount: (Boolean) -> Unit,
    onTogglePanelColor: (Boolean) -> Unit,
    onHideIcon: () -> Unit,
    onReboot: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackgroundColor())
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 8.dp, bottom = 120.dp),
    ) {
        Text(text = "功能", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Spacer(Modifier.height(16.dp))

        SectionLabel("软件设置")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cardContainerColor()),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ActionRow(
                    iconRes = R.drawable.ic_eye_off,
                    title = if (iconHidden) "显示桌面图标" else "隐藏桌面图标",
                    subtitle = if (iconHidden) "当前已隐藏，点击恢复" else "LSPosed 内仍可打开模块设置",
                    onClick = onHideIcon,
                )
                ActionRow(
                    iconRes = R.drawable.ic_restart,
                    title = "立即重启手机",
                    subtitle = "需要 ROOT 权限",
                    onClick = onReboot,
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        SectionLabel("功能设置")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cardContainerColor()),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = swImeUnlock,
                    onCheckedChange = onToggleImeUnlock,
                    title = "启用全面屏优化功能",
                )
                SwitchPreference(
                    checked = swUnlimitCount,
                    onCheckedChange = onToggleUnlimitCount,
                    title = "解锁剪贴板数量上限",
                )
                SwitchPreference(
                    checked = swPanelColor,
                    onCheckedChange = onTogglePanelColor,
                    title = "优化剪贴板面板背景",
                )
            }
        }
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
private fun ActionRow(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .rowPressClickable(onClick),
        title = title,
        summary = subtitle,
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
