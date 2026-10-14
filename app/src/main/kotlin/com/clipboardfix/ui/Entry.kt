@file:JvmName("ClipboardFixUi")

package com.clipboardfix.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.platform.ComposeView

/** 带布尔参数的回调（Java 侧以 lambda 传入，如 checked -> onToggleXxx(checked)）。 */
fun interface BoolAction {
    fun invoke(value: Boolean)
}

/** 带整型参数的回调（Java 侧以 lambda 传入，如 mode -> selectTheme(mode)）。 */
fun interface IntAction {
    fun invoke(value: Int)
}

/**
 * 供 Java 侧创建 Compose 可观察状态。Kotlin 的 mutableStateOf 用默认参数，Java 看不到
 * 单参重载；这里用 @JvmOverloads 暴露单参版本。
 */
@JvmOverloads
fun <T> stateOf(value: T, policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy()): MutableState<T> =
    mutableStateOf(value, policy)

/**
 * 把三页 Miuix 界面装配进各自的 ComposeView（由 View 外壳 activity_about.xml 承载，
 * 底部液态玻璃导航 LiquidGlassBarView 保持不变）。
 *
 * Java 侧只传「可观察状态」（androidx State）与「动作回调」（Runnable / BoolAction）；
 * 在每个 ComposeView 的 setContent 里读取 State.value，从而获得响应式刷新。
 */
fun installPages(
    home: ComposeView,
    feature: ComposeView,
    about: ComposeView,
    settings: ComposeView,
    statusText: State<String>,
    imeCountText: State<String>,
    statusEnabled: State<Boolean>,
    appVersion: String,
    buildDate: String,
    deviceModel: String,
    androidVersion: String,
    systemVersion: String,
    abiInfo: String,
    swImeUnlock: State<Boolean>,
    swUnlimitCount: State<Boolean>,
    swPanelColor: State<Boolean>,
    swClipboardPin: State<Boolean>,
    iconHidden: State<Boolean>,
    onCheckUpdate: Runnable,
    onOpenStatusSheet: Runnable,
    onToggleImeUnlock: BoolAction,
    onToggleUnlimitCount: BoolAction,
    onTogglePanelColor: BoolAction,
    onToggleClipboardPin: BoolAction,
    onHideIcon: BoolAction,
    onReboot: Runnable,
    onOpenCoolapk: Runnable,
    onOpenWeibo: Runnable,
    onOpenGithub: Runnable,
    onReleases: Runnable,
    onDonate: Runnable,
    onOpenTelegram: Runnable,
    onUpstream: Runnable,
    onOpenMiuix: Runnable,
    themeMode: Int,
    langMode: Int,
    updateDisabled: State<Boolean>,
    onThemeSelect: IntAction,
    onLanguageSelect: IntAction,
    onToggleUpdateDisabled: BoolAction,
) {
    home.setContent {
        ClipboardFixTheme {
            HomePage(
                statusText = statusText.value,
                imeCountText = imeCountText.value,
                statusEnabled = statusEnabled.value,
                appVersion = appVersion,
                buildDate = buildDate,
                deviceModel = deviceModel,
                androidVersion = androidVersion,
                systemVersion = systemVersion,
                abiInfo = abiInfo,
                onStatusCardClick = { onOpenStatusSheet.run() },
            )
        }
    }
    feature.setContent {
        ClipboardFixTheme {
            FeaturePage(
                swImeUnlock = swImeUnlock.value,
                swUnlimitCount = swUnlimitCount.value,
                swPanelColor = swPanelColor.value,
                swClipboardPin = swClipboardPin.value,
                onToggleImeUnlock = { onToggleImeUnlock.invoke(it) },
                onToggleUnlimitCount = { onToggleUnlimitCount.invoke(it) },
                onTogglePanelColor = { onTogglePanelColor.invoke(it) },
                onToggleClipboardPin = { onToggleClipboardPin.invoke(it) },
            )
        }
    }
    about.setContent {
        ClipboardFixTheme {
            AboutPage(
                versionName = appVersion,
                onOpenCoolapk = { onOpenCoolapk.run() },
                onOpenWeibo = { onOpenWeibo.run() },
                onOpenGithub = { onOpenGithub.run() },
                onReleases = { onReleases.run() },
                onDonate = { onDonate.run() },
                onOpenTelegram = { onOpenTelegram.run() },
                onUpstream = { onUpstream.run() },
                onOpenMiuix = { onOpenMiuix.run() },
                onCheckUpdate = { onCheckUpdate.run() },
            )
        }
    }
    settings.setContent {
        ClipboardFixTheme {
            SettingsPage(
                themeMode = themeMode,
                langMode = langMode,
                updateDisabled = updateDisabled.value,
                iconHidden = iconHidden.value,
                onThemeSelect = { onThemeSelect.invoke(it) },
                onLanguageSelect = { onLanguageSelect.invoke(it) },
                onToggleUpdateDisabled = { onToggleUpdateDisabled.invoke(it) },
                onHideIcon = { onHideIcon.invoke(it) },
                onReboot = { onReboot.run() },
            )
        }
    }
}

/**
 * 装配「首次进入欢迎引导」（三页）到独立 ComposeView —— 由 AboutActivity 挂到根布局顶层的
 * 覆盖层里，铺满整屏并盖住底部导航。
 *
 * 与 [installPages] 同风格：Java 侧只传「可观察状态」（androidx State / MutableState）与
 * 「动作回调」（Runnable / IntAction）。页面下标由 Java 侧的 MutableState 持有，使系统返回键
 * 与「语言切换触发 recreate」都能正确落页。
 */
fun installWelcome(
    host: ComposeView,
    page: MutableState<Int>,
    hyperOsVersion: String,
    androidVersion: String,
    moduleActive: State<Boolean>,
    hasRoot: State<Boolean>,
    gestureLineHidden: State<Boolean>,
    langMode: Int,
    onLanguageSelect: IntAction,
    onFinish: Runnable,
) {
    host.setContent {
        ClipboardFixTheme {
            WelcomeFlow(
                page = page,
                hyperOsVersion = hyperOsVersion,
                androidVersion = androidVersion,
                moduleActive = moduleActive.value,
                hasRoot = hasRoot.value,
                gestureLineHidden = gestureLineHidden.value,
                langMode = langMode,
                onLanguageSelect = { onLanguageSelect.invoke(it) },
                onFinish = { onFinish.run() },
            )
        }
    }
}
