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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
                        onCheckedChange = onToggleImeUnlock,
                        title = stringResource(R.string.sw_ime_unlock),
                    )
                    SwitchPreference(
                        checked = swUnlimitCount,
                        onCheckedChange = onToggleUnlimitCount,
                        title = stringResource(R.string.sw_unlimit),
                    )
                    SwitchPreference(
                        checked = swUnlimitTime,
                        onCheckedChange = onToggleUnlimitTime,
                        title = stringResource(R.string.sw_unlimit_time),
                    )
                    SwitchPreference(
                        checked = swPanelColor,
                        onCheckedChange = onTogglePanelColor,
                        title = stringResource(R.string.sw_panel),
                    )
                    SwitchPreference(
                        checked = swClipboardPin,
                        onCheckedChange = onToggleClipboardPin,
                        title = stringResource(R.string.sw_clipboard_pin),
                    )
                }
            }
        }
        // Snackbar 宿主：玻璃面板折射本页内容，浮在底部导航条上方
        PageSnackbarHost(backdrop = snackbarBackdrop)
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
