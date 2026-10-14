package com.clipboardfix.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    swClipboardPin: Boolean,
    onToggleImeUnlock: (Boolean) -> Unit,
    onToggleUnlimitCount: (Boolean) -> Unit,
    onTogglePanelColor: (Boolean) -> Unit,
    onToggleClipboardPin: (Boolean) -> Unit,
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
