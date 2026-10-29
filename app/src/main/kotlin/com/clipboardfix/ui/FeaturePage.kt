package com.clipboardfix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
    swUnlimitTime: Boolean,
    swPanelColor: Boolean,
    swClipboardPin: Boolean,
    onToggleImeUnlock: (Boolean) -> Unit,
    onToggleUnlimitCount: (Boolean) -> Unit,
    onToggleUnlimitTime: (Boolean) -> Unit,
    onTogglePanelColor: (Boolean) -> Unit,
    onToggleClipboardPin: (Boolean) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 玻璃折射源：整页内容登记进 layerBackdrop，供 Snackbar 的柔光玻璃面板实时折射
    val snackbarBackdrop = rememberLayerBackdrop()

    // 预取文案：避免在事件回调 lambda 内调用 stringResource（其需在组合作用域调用）
    val strConfirmTitle = stringResource(R.string.dialog_ime_unlock_title)
    val strConfirmBody = stringResource(R.string.dialog_ime_unlock_body)
    val strCancel = stringResource(R.string.dialog_cancel)
    val strConfirmClose = stringResource(R.string.dialog_confirm_close)
    val strRebootEffect = stringResource(R.string.toast_reboot_effect)
    val strEnableFirst = stringResource(R.string.toast_enable_first)
    val strEnabledReboot = stringResource(R.string.toast_enabled_reboot)

    // 柔光玻璃弹卡（关闭主开关二次确认）请求槽位：与 Snackbar 同风格，由本页渲染
    val sheetReq = remember { mutableStateOf<GlassSheetRequest?>(null) }
    DisposableEffect(Unit) {
        GlassSheetBridge.bind(sheetReq)
        onDispose { GlassSheetBridge.unbind(sheetReq) }
    }

    // 主开关：开启即提示重启生效；关闭需二次确认（不立即翻转，确认后才落库）
    val handleImeUnlock: (Boolean) -> Unit = { checked ->
        if (checked) {
            onToggleImeUnlock(true)
            showSnack(strEnabledReboot)
        } else {
            GlassSheetBridge.show(
                GlassSheetRequest(
                    title = strConfirmTitle,
                    content = GlassSheetContent.Message(strConfirmBody),
                    cancelText = strCancel,
                    onCancel = { GlassSheetBridge.close() },
                    actionText = strConfirmClose,
                    onAction = {
                        onToggleImeUnlock(false)
                        showSnack(strRebootEffect)
                    },
                ),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(snackbarBackdrop)
                .background(pageBackgroundColor())
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 8.dp, bottom = 120.dp),
        ) {
            Text(text = stringResource(R.string.feature_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.sec_feature))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = swImeUnlock,
                        onCheckedChange = handleImeUnlock,
                        title = stringResource(R.string.sw_ime_unlock),
                    )
                    FeatureSwitch(
                        checked = swImeUnlock && swUnlimitCount,
                        onCheckedChange = onToggleUnlimitCount,
                        title = stringResource(R.string.sw_unlimit),
                        enabled = swImeUnlock,
                        onClickWhenDisabled = { showSnack(strEnableFirst) },
                    )
                    FeatureSwitch(
                        checked = swImeUnlock && swUnlimitTime,
                        onCheckedChange = onToggleUnlimitTime,
                        title = stringResource(R.string.sw_unlimit_time),
                        enabled = swImeUnlock,
                        onClickWhenDisabled = { showSnack(strEnableFirst) },
                    )
                    FeatureSwitch(
                        checked = swImeUnlock && swPanelColor,
                        onCheckedChange = onTogglePanelColor,
                        title = stringResource(R.string.sw_panel),
                        enabled = swImeUnlock,
                        onClickWhenDisabled = { showSnack(strEnableFirst) },
                    )
                    FeatureSwitch(
                        checked = swImeUnlock && swClipboardPin,
                        onCheckedChange = onToggleClipboardPin,
                        title = stringResource(R.string.sw_clipboard_pin),
                        enabled = swImeUnlock,
                        onClickWhenDisabled = { showSnack(strEnableFirst) },
                    )
                }
            }
        }
        // Snackbar 宿主：玻璃面板折射本页内容，浮在底部导航条上方
        PageSnackbarHost(backdrop = snackbarBackdrop)
        // 柔光玻璃弹卡：与更新日志弹卡同材质（同一 drawBackdrop 效果链）
        PageGlassSheetOverlay(
            request = sheetReq.value,
            backdrop = snackbarBackdrop,
            onDismiss = { GlassSheetBridge.close() },
        )
    }
}

/**
 * 功能开关行：主开关（[enabled]=swImeUnlock）关闭时，子开关被强制显示为关闭（checked=swImeUnlock && 实际值）、
 * 置灰且开关不可拨动；此时点击整行触发 [onClickWhenDisabled] 提示「请先启用主开关」。
 * 主开关重新开启后，子开关恢复各自保存的真实开关状态（非破坏性，仅视觉跟随）。
 */
@Composable
private fun FeatureSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    enabled: Boolean,
    onClickWhenDisabled: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!enabled && onClickWhenDisabled != null) {
                    Modifier.clickable { onClickWhenDisabled() }
                } else {
                    Modifier
                },
            ),
    ) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            enabled = enabled,
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
