/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix.ui

import android.content.Context
import android.provider.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 读取系统「全面屏键盘优化」当前是否选中【多功能键盘】。
 * 系统键 enable_miui_ime_bottom_view=1 表示启用输入法底部功能区（即多功能键盘），
 * 0 或缺省表示默认键盘；读取异常一律按未选中处理，避免误放行。cf/mkbgate-1123a
 */
private fun isMultiFunctionKeyboardSelected(context: Context): Boolean = runCatching {
    Settings.Secure.getInt(context.contentResolver, "enable_miui_ime_bottom_view", 0) == 1
}.getOrDefault(false)

/**
 * 读取系统手势提示线是否已关闭。系统键 hide_gesture_line=1 表示已隐藏（底层可直接绘制柔光，
 * 提示线区域不再抢占底部空间），0 或缺省表示仍显示；读取异常一律按未关闭处理。cf/matglass-1126c
 */
private fun isGestureLineHidden(context: Context): Boolean = runCatching {
    Settings.Global.getInt(context.contentResolver, "hide_gesture_line", 0) == 1
}.getOrDefault(false)

@Composable
fun FeaturePage(
    moduleEnabled: Boolean,
    swImeUnlock: Boolean,
    swUnlimitCount: Boolean,
    swUnlimitTime: Boolean,
    swPanelColor: Boolean,
    swPanelMaterial: Boolean,
    swClipboardPin: Boolean,
    swSinkLayout: Boolean,
    swClipboardGroup: Boolean,
    swClipboardGroupRemember: Boolean,
    onToggleImeUnlock: (Boolean) -> Unit,
    onToggleUnlimitCount: (Boolean) -> Unit,
    onToggleUnlimitTime: (Boolean) -> Unit,
    onTogglePanelColor: (Boolean) -> Unit,
    onTogglePanelMaterial: (Boolean) -> Unit,
    onToggleClipboardPin: (Boolean) -> Unit,
    onToggleSinkLayout: (Boolean) -> Unit,
    onToggleClipboardGroup: (Boolean) -> Unit,
    onToggleClipboardGroupRemember: (Boolean) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 系统设置读取入口：主开关开启前核验键盘模式。cf/mkbgate-1123e
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 系统键盘模式记忆：默认键盘下主开关强制显示关闭且不可开启；切回多功能键盘后自动恢复。
    // 每次页面回到前台重新读取——用户去系统设置切换后返回即可即时反映。cf/mkbgate-1123f
    var multiKeyboardOk by remember { mutableStateOf(isMultiFunctionKeyboardSelected(context)) }
    // 手势提示线状态：提示线仍显示时柔光玻璃无法开启，返回前台即时刷新。cf/matglass-1126d
    var gestureLineHidden by remember { mutableStateOf(isGestureLineHidden(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                multiKeyboardOk = isMultiFunctionKeyboardSelected(context)
                gestureLineHidden = isGestureLineHidden(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 生效开关值：持久开关与系统键盘模式取交集，仅影响本页显示，不改写持久化配置。cf/mkbgate-1123g
    val imeUnlockVisible = swImeUnlock && multiKeyboardOk
    // 柔光玻璃生效值：持久开关与手势提示线状态取交集，持久值不动，关闭提示线后自动恢复。cf/matglass-1126e
    val panelMaterialVisible = swPanelMaterial && gestureLineHidden
    // 玻璃折射源：整页内容登记进 layerBackdrop，供 Snackbar 的柔光玻璃面板实时折射
    val snackbarBackdrop = rememberLayerBackdrop()
    // 页面底色预取（顶栏磨砂叠加用）+ Haze 状态：滚动内容作模糊源，顶栏条消费
    val pageBg = pageBackgroundColor()
    val hazeState = rememberHazeState()
    val scrollState = rememberScrollState()
    // 功能页没有关于页那种大头部占位，标题一滚就出视野；顶栏须从首帧滑动即开始淡入。
    val topAlpha = rememberScrollTopAlpha(scrollState, startDp = 0f, endDp = 24f)

    // 预取文案：避免在事件回调 lambda 内调用 stringResource（其需在组合作用域调用）
    val strConfirmTitle = stringResource(R.string.dialog_ime_unlock_title)
    val strConfirmBody = stringResource(R.string.dialog_ime_unlock_body)
    val strCancel = stringResource(R.string.dialog_cancel)
    val strConfirmClose = stringResource(R.string.dialog_confirm_close)
    val strRebootEffect = stringResource(R.string.toast_reboot_effect)
    val strEnableFirst = stringResource(R.string.toast_enable_first)
    val strModuleInactive = stringResource(R.string.toast_module_inactive)
    val strEnabledReboot = stringResource(R.string.toast_enabled_reboot)
    val strNeedMultiKeyboard = stringResource(R.string.toast_need_multi_keyboard)
    val strNeedGestureLineOff = stringResource(R.string.toast_need_gesture_line_off)

    // 两级只读：模块未激活时整页置灰；主开关未开启（含系统非多功能键盘）时子项置灰。
    // 提示按实际阻塞原因分流：系统键盘模式不符时给出专门引导，否则沿用通用文案。cf/mkbgate-1123h
    val switchesEnabled = moduleEnabled
    val subSwitchesEnabled = moduleEnabled && imeUnlockVisible
    val disabledHint: () -> Unit = {
        if (!multiKeyboardOk) {
            showSnack(strNeedMultiKeyboard)
        } else {
            showSnack(if (moduleEnabled) strEnableFirst else strModuleInactive)
        }
    }

    // 柔光玻璃弹卡（关闭主开关二次确认）请求槽位：与 Snackbar 同风格，由本页渲染
    val sheetReq = remember { mutableStateOf<GlassSheetRequest?>(null) }
    DisposableEffect(Unit) {
        GlassSheetBridge.bind(sheetReq)
        onDispose { GlassSheetBridge.unbind(sheetReq) }
    }

    // 主开关：开启前须系统已选多功能键盘（否则功能区不存在，开了也无意义），通过即提示重启生效；
    // 关闭需二次确认（不立即翻转，确认后才落库）。cf/mkbgate-1123b
    val handleImeUnlock: (Boolean) -> Unit = { checked ->
        if (checked) {
            // 系统停在默认键盘模式下无底部功能区可接入，拦截开启并提示。cf/mkbgate-1123c
            if (!multiKeyboardOk) {
                showSnack(strNeedMultiKeyboard)
            } else {
                onToggleImeUnlock(true)
                showSnack(strEnabledReboot)
            }
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
                .hazeSource(hazeState)
                .background(pageBg)
                // 视口铺满全屏（含状态栏），让卡片滚到状态栏下方成为模糊源；
                // 状态栏高度由内容首部的 Spacer 顶开，标题位置交给 MorphTopTitle 单独绘制。
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp)
                .padding(top = 8.dp, bottom = 120.dp),
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(40.dp))
            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.sec_feature))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = imeUnlockVisible,
                        onCheckedChange = handleImeUnlock,
                        title = stringResource(R.string.sw_ime_unlock),
                        summary = stringResource(R.string.sw_ime_unlock_sub),
                        enabled = switchesEnabled,
                    )
                    FeatureSwitch(
                        checked = imeUnlockVisible &&swUnlimitCount,
                        onCheckedChange = onToggleUnlimitCount,
                        title = stringResource(R.string.sw_unlimit),
                        summary = stringResource(R.string.sw_unlimit_sub),
                        enabled = subSwitchesEnabled,
                        onClickWhenDisabled = disabledHint,
                    )
                    FeatureSwitch(
                        checked = imeUnlockVisible &&swUnlimitTime,
                        onCheckedChange = onToggleUnlimitTime,
                        title = stringResource(R.string.sw_unlimit_time),
                        summary = stringResource(R.string.sw_unlimit_time_sub),
                        enabled = subSwitchesEnabled,
                        onClickWhenDisabled = disabledHint,
                    )
                    FeatureSwitch(
                        checked = imeUnlockVisible &&swPanelColor,
                        onCheckedChange = onTogglePanelColor,
                        title = stringResource(R.string.sw_panel),
                        summary = stringResource(R.string.sw_panel_sub),
                        enabled = subSwitchesEnabled,
                        onClickWhenDisabled = disabledHint,
                    )
                    // 连续磨砂背景：仅当「优化剪贴板面板背景」打开时才显示（子选项），默认关闭
                    if (imeUnlockVisible && swPanelColor) {
                        FeatureSwitch(
                            checked = panelMaterialVisible,
                            // 手势提示线仍显示时拦截开启并提示，持久值不动；关闭提示线后自动恢复。cf/matglass-1126f
                            onCheckedChange = { checked ->
                                if (checked && !gestureLineHidden) {
                                    showSnack(strNeedGestureLineOff)
                                } else {
                                    onTogglePanelMaterial(checked)
                                }
                            },
                            title = stringResource(R.string.sw_panel_material),
                            summary = stringResource(R.string.sw_panel_material_sub),
                            enabled = switchesEnabled,
                            onClickWhenDisabled = disabledHint,
                        )
                    }
                    FeatureSwitch(
                        checked = imeUnlockVisible &&swClipboardPin,
                        onCheckedChange = onToggleClipboardPin,
                        title = stringResource(R.string.sw_clipboard_pin),
                        summary = stringResource(R.string.sw_clipboard_pin_sub),
                        enabled = subSwitchesEnabled,
                        onClickWhenDisabled = disabledHint,
                    )
                    FeatureSwitch(
                        checked = imeUnlockVisible &&swSinkLayout,
                        onCheckedChange = onToggleSinkLayout,
                        title = stringResource(R.string.sw_sink_layout),
                        summary = stringResource(R.string.sw_sink_layout_sub),
                        enabled = subSwitchesEnabled,
                        onClickWhenDisabled = disabledHint,
                    )
                    FeatureSwitch(
                        checked = imeUnlockVisible &&swClipboardGroup,
                        onCheckedChange = onToggleClipboardGroup,
                        title = stringResource(R.string.sw_clipboard_group),
                        summary = stringResource(R.string.sw_clipboard_group_sub),
                        enabled = subSwitchesEnabled,
                        onClickWhenDisabled = disabledHint,
                    )
                    // 【记忆剪贴板分组位置】：仅当「开启剪贴板分组功能」打开时才显示（子选项），默认关闭
                    if (imeUnlockVisible && swClipboardGroup) {
                        FeatureSwitch(
                            checked = swClipboardGroupRemember,
                            onCheckedChange = onToggleClipboardGroupRemember,
                            title = stringResource(R.string.sw_clipboard_group_remember),
                            summary = stringResource(R.string.sw_clipboard_group_remember_sub),
                            enabled = switchesEnabled,
                            onClickWhenDisabled = disabledHint,
                        )
                    }
                }
            }
        }
        // 上滑淡入的顶栏：只画模糊，状态栏一并覆盖。标题不在这里画第二份。
        ScrollTopBlurBar(
            hazeState = hazeState,
            pageBg = pageBg,
            alpha = topAlpha,
            barHeight = topBlurBarHeight(),
            // 功能页底材是纯色卡片，关于页那套 0.30 叠色在此几乎看不出磨砂，适度加强。
            tintAlpha = 0.46f,
            blurDp = 20f,
        )
        // 唯一的「功能」标题：固定在首屏原位不动；上滑仅由背后磨砂层淡入承载。
        MorphTopTitle(
            title = stringResource(R.string.feature_title),
            titleTopDp = 8f,
        )
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
 * 功能开关行：模块未激活或主开关关闭时，子开关被强制显示为关闭
 * （checked=imeUnlockVisible && 实际值）、置灰且不可拨动；此时点击整行触发
 * [onClickWhenDisabled]。主开关重新开启后，子开关恢复各自保存的真实开关状态
 * （非破坏性，仅视觉跟随）。(fs-207u / cf/mkbgate-1123i)
 */
@Composable
private fun FeatureSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    enabled: Boolean,
    summary: String? = null,
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
            summary = summary,
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
