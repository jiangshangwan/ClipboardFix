/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 三份协议在 assets 内的相对路径，顺序与标题数组一一对应。cf/legalhub-src-1124d */
private val LEGAL_ASSETS = arrayOf(
    "legal/user_agreement.txt",
    "legal/disclaimer.txt",
    "legal/privacy_policy.txt",
)
private val LEGAL_TITLES = intArrayOf(
    R.string.agreement_user,
    R.string.agreement_disclaimer,
    R.string.agreement_privacy,
)

private val LEGAL_BAR_BLUR_DP = 16f
private const val LEGAL_BAR_TINT_ALPHA = 0.30f
private const val LEGAL_BAR_GAP_DP = 20

/**
 * 关于页「协议与声明」二级页：列表态展示三份协议入口，点入后切到全文阅读态。
 *
 * 系统返回键在阅读态先退回列表态，列表态再交回外层 Activity。cf/legalhub-root-1124e
 */
@Composable
internal fun LegalPage(onBack: () -> Unit) {
    var doc by remember { mutableIntStateOf(-1) }
    BackHandler(enabled = doc >= 0) { doc = -1 }
    if (doc >= 0) {
        LegalReader(docIndex = doc, onBack = { doc = -1 })
    } else {
        LegalList(onBack = onBack, onOpen = { doc = it })
    }
}

/** 协议目录：三行入口，风格与开源许可证页一致。cf/legalhub-list-1124f */
@Composable
private fun LegalList(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val pageBg = pageBackgroundColor()
    val hazeState = rememberHazeState()

    Box(modifier = Modifier.fillMaxSize().background(pageBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp),
        ) {
            Spacer(
                Modifier
                    .statusBarsPadding()
                    .height(TopAppBarDefaults.SmallTopAppBarCenterHeight + LEGAL_BAR_GAP_DP.dp),
            )
            Text(
                text = stringResource(R.string.legal_intro),
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (i in LEGAL_ASSETS.indices) {
                        BasicComponent(
                            modifier = Modifier
                                .fillMaxWidth()
                                .rowPressClickable { onOpen(i) },
                            title = stringResource(LEGAL_TITLES[i]),
                            insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                            startAction = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_books),
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariantSummary,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            // 进入子页的指示箭头保持默认朝右；只有顶栏返回键才旋转。cf/legalhub-chevron-1124t
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
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .hazeEffect(hazeState) {
                    blurRadius = LEGAL_BAR_BLUR_DP.dp
                    backgroundColor = pageBg.copy(alpha = 0f)
                    noiseFactor = 0f
                    tints = listOf(HazeTint(pageBg.copy(alpha = LEGAL_BAR_TINT_ALPHA)))
                    progressive = HazeProgressive.Brush(
                        Brush.verticalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = 0f)),
                    )
                },
        ) {
            SmallTopAppBar(
                title = stringResource(R.string.legal_page_title),
                color = pageBg.copy(alpha = 0f),
                titleColor = colors.onSurface,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron),
                            contentDescription = null,
                            tint = colors.onSurface,
                            modifier = Modifier.size(24.dp).rotate(180f),
                        )
                    }
                },
            )
        }
    }
}

/** 协议正文的轻量加粗标记：**文本** 转粗体并去星号。cf/legalhub-bold-1124g */
private fun legalRich(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (true) {
        val begin = text.indexOf("**", cursor)
        if (begin < 0) break
        val end = text.indexOf("**", begin + 2)
        if (end < 0) break
        withStyle(SpanStyle(color = color)) { append(text.substring(cursor, begin)) }
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
            append(text.substring(begin + 2, end))
        }
        cursor = end + 2
    }
    if (cursor < text.length) withStyle(SpanStyle(color = color)) { append(text.substring(cursor)) }
}

/** 协议全文阅读态：标题栏 + 可滚动正文。cf/legalhub-read-1124h */
@Composable
private fun LegalReader(docIndex: Int, onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val title = stringResource(LEGAL_TITLES[docIndex])
    val body = remember(docIndex) {
        runCatching {
            context.assets.open(LEGAL_ASSETS[docIndex]).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    Box(modifier = Modifier.fillMaxSize().background(pageBackgroundColor())) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 64.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                        .size(40.dp)
                        .rowPressClickable(onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron),
                        contentDescription = null,
                        tint = colors.onSurface,
                        modifier = Modifier.size(24.dp).rotate(180f),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
            ) {
                body.split("\n").forEach { raw ->
                    val line = raw.trimEnd()
                    when {
                        line.isBlank() -> Spacer(Modifier.height(6.dp))
                        line.startsWith("## ") -> Text(
                            text = line.removePrefix("## ").trim(),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                        )
                        line.startsWith("### ") -> Text(
                            text = line.removePrefix("### ").trim(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        else -> Text(
                            text = remember(line) { legalRich(line, colors.onSurface) },
                            fontSize = 14.5.sp,
                            color = colors.onSurface,
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
