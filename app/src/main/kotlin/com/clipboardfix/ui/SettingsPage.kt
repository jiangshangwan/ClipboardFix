package com.clipboardfix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：
 * - 「软件设置」卡：立即重启手机、隐藏桌面图标（开关）、停用更新检测（开关）、语言；
 * - 「外观」卡：主题模式。
 *
 * 语言 / 主题改用自绘的「液体玻璃」下拉弹卡（见 [GlassDropdownRow]）：弹卡画在页面
 * 根 Box 的覆盖层里，用 backdrop 对登记过的页面内容做实时折射 + 模糊 + 色散（真玻璃），
 * 并带弹出 / 关闭动画。整页内容即折射源，故 layerBackdrop 挂在滚动列上。
 */
@Composable
fun SettingsPage(
    themeMode: Int,
    langMode: Int,
    updateDisabled: Boolean,
    iconHidden: Boolean,
    onThemeSelect: (Int) -> Unit,
    onLanguageSelect: (Int) -> Unit,
    onToggleUpdateDisabled: (Boolean) -> Unit,
    onHideIcon: (Boolean) -> Unit,
    onReboot: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val langOptions = listOf(
        stringResource(R.string.opt_default),
        stringResource(R.string.opt_zh),
        stringResource(R.string.opt_en),
    )
    val themeOptions = listOf(
        stringResource(R.string.opt_default),
        stringResource(R.string.opt_light),
        stringResource(R.string.opt_dark),
    )

    // 下拉弹卡：模糊源（页面内容）+ 当前请求 / 展开状态
    val dropdownBackdrop = rememberLayerBackdrop()
    var dropdownReq by remember { mutableStateOf<DropdownRequest?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }
    var rootHeightPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackgroundColor())
            .onGloballyPositioned { rootHeightPx = it.size.height.toFloat() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 登记为模糊源：弹卡 drawBackdrop 实时模糊这整页内容 → 柔光玻璃
                .layerBackdrop(dropdownBackdrop)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 8.dp, bottom = 120.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // 软件设置：重启 / 隐藏桌面图标 / 停用更新检测 / 语言
            SectionLabel(stringResource(R.string.sec_software))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ActionRow(
                        iconRes = R.drawable.ic_restart,
                        title = stringResource(R.string.reboot_title),
                        subtitle = stringResource(R.string.reboot_sub),
                        onClick = onReboot,
                    )
                    SwitchPreference(
                        checked = iconHidden,
                        onCheckedChange = onHideIcon,
                        title = stringResource(R.string.hide_icon),
                        summary = stringResource(R.string.hide_icon_sub_default),
                    )
                    SwitchPreference(
                        checked = updateDisabled,
                        onCheckedChange = onToggleUpdateDisabled,
                        title = stringResource(R.string.disable_update),
                        summary = stringResource(R.string.disable_update_sub),
                    )
                    GlassDropdownRow(
                        title = stringResource(R.string.language),
                        items = langOptions,
                        selectedIndex = langMode.coerceIn(0, langOptions.size - 1),
                        onSelected = { onLanguageSelect(it) },
                        onOpen = {
                            dropdownReq = it
                            dropdownOpen = true
                        },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // 外观：主题模式
            SectionLabel(stringResource(R.string.sec_appearance))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    GlassDropdownRow(
                        title = stringResource(R.string.theme),
                        items = themeOptions,
                        selectedIndex = themeMode.coerceIn(0, themeOptions.size - 1),
                        onSelected = { onThemeSelect(it) },
                        onOpen = {
                            dropdownReq = it
                            dropdownOpen = true
                        },
                    )
                }
            }
        }

        // 覆盖层：遮罩 + 柔光玻璃弹卡（画在页面之上，不被 Card 裁剪）
        GlassDropdownOverlay(
            request = dropdownReq,
            expanded = dropdownOpen,
            backdrop = dropdownBackdrop,
            rootHeightPx = rootHeightPx,
            onDismiss = { dropdownOpen = false },
        )
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
