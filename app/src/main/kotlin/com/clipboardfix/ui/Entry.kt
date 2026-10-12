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
    iconHidden: State<Boolean>,
    onCheckUpdate: Runnable,
    onMustKnow: Runnable,
    onOpenStatusSheet: Runnable,
    onToggleImeUnlock: BoolAction,
    onToggleUnlimitCount: BoolAction,
    onTogglePanelColor: BoolAction,
    onHideIcon: Runnable,
    onReboot: Runnable,
    onOpenCoolapk: Runnable,
    onOpenWeibo: Runnable,
    onOpenGithub: Runnable,
    onReleases: Runnable,
    onDonate: Runnable,
    onOpenTelegram: Runnable,
    onUpstream: Runnable,
    onOpenMiuix: Runnable,
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
                onMustKnow = { onMustKnow.run() },
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
                iconHidden = iconHidden.value,
                onToggleImeUnlock = { onToggleImeUnlock.invoke(it) },
                onToggleUnlimitCount = { onToggleUnlimitCount.invoke(it) },
                onTogglePanelColor = { onTogglePanelColor.invoke(it) },
                onHideIcon = { onHideIcon.run() },
                onReboot = { onReboot.run() },
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
}
