/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix.ui

import android.os.Build
import androidx.activity.compose.BackHandler // cf/welcome-native-backgesture-1124y
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect // cf/welcome-native-backgesture-1124z
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate as modifierRotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString // cf/legal-bold-1125l
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 欢迎覆盖层返回消费桥：协议阅读器与验证码弹卡优先于分页回退。cf/welcome-native-backbridge-1125a */
object WelcomeNavigationBridge {
    /** 由 Compose 侧注册；返回 true 表示本次系统返回已被覆盖层消费。cf/welcome-native-backbridge-1125b */
    @JvmStatic
    var consumeBack: (() -> Boolean)? = null
}

/**
 * 首次进入的欢迎引导（四页）：
 *
 * 1. 欢迎页 —— 模块标识 + 功能概览；
 * 2. 模块准备工作 —— 环境自检清单 + 语言选择；
 * 3. 协议与声明 —— 权限说明 / 动态验证码 / 文档阅读 / 同意勾选；
 * 4. 设置完成 —— 完成标识 + 当前状态确认。
 *
 * 页面状态由外部（Activity）持有并传入，使系统返回键与「语言切换触发 recreate」都能正确落页；
 * 语言行复用设置页的液体玻璃下拉弹卡（同一 Compose 树内做实时折射）。cf/welcome-native-doc-1124b
 */
@Composable
fun WelcomeFlow(
    page: MutableState<Int>,
    hyperOsVersion: String,
    androidVersion: String,
    moduleActive: Boolean,
    hasRoot: Boolean,
    gestureLineHidden: Boolean,
    multiKeyboard: Boolean,
    langMode: Int,
    onLanguageSelect: (Int) -> Unit,
    onFinish: () -> Unit,
) {
    // 下拉弹卡：模糊源（整页）+ 当前请求 / 展开状态
    val backdrop = rememberLayerBackdrop()
    var dropdownReq by remember { mutableStateOf<DropdownRequest?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }
    var rootHeightPx by remember { mutableFloatStateOf(0f) }

    // 协议阅读器（-1 = 未打开）
    var readerDoc by remember { mutableIntStateOf(-1) }

    // 动态验证码：本地随机 4 位数字，验证通过后才能勾选协议
    var codeValue by remember { mutableStateOf("") }
    var codeDialog by remember { mutableStateOf(false) }
    var codeVerified by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }

    // 覆盖层优先消费系统返回：协议阅读器 / 验证码弹卡先关，不触发分页回退。cf/welcome-native-backgesture-1125c
    val consumeOverlayBack = remember(readerDoc, codeDialog) {
        {
            when {
                readerDoc >= 0 -> {
                    readerDoc = -1
                    true
                }
                codeDialog -> {
                    codeDialog = false
                    true
                }
                else -> false
            }
        }
    }
    DisposableEffect(consumeOverlayBack) {
        WelcomeNavigationBridge.consumeBack = consumeOverlayBack
        onDispose {
            if (WelcomeNavigationBridge.consumeBack === consumeOverlayBack) {
                WelcomeNavigationBridge.consumeBack = null
            }
        }
    }
    BackHandler(enabled = readerDoc >= 0 || codeDialog, onBack = { consumeOverlayBack() })

    // 翻页：淡入 + 轻微横移（方向随前进 / 后退变化）
    val enter = remember { Animatable(1f) }
    var lastPage by remember { mutableIntStateOf(page.value) }
    var dir by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(page.value) {
        dir = if (page.value >= lastPage) 1f else -1f
        lastPage = page.value
        enter.snapTo(0f)
        enter.animateTo(1f, tween(260))
    }
    val shiftPx = with(LocalDensity.current) { 18.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackgroundColor())
            // 铺满整屏并吃掉落在空白处的触摸，避免穿透到底层主界面
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .onGloballyPositioned { rootHeightPx = it.size.height.toFloat() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 登记为模糊源：语言下拉弹卡实时折射这整页内容
                .layerBackdrop(backdrop),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.25f + 0.75f * enter.value
                        translationX = dir * shiftPx * (1f - enter.value)
                    },
            ) {
                when (page.value) {
                    0 -> IntroPage(onNext = { page.value = 1 })

                    1 -> PrepPage(
                        hyperOsVersion = hyperOsVersion,
                        androidVersion = androidVersion,
                        moduleActive = moduleActive,
                        hasRoot = hasRoot,
                        gestureLineHidden = gestureLineHidden,
                        multiKeyboard = multiKeyboard,
                        langMode = langMode,
                        onLanguageSelect = onLanguageSelect,
                        onBack = { page.value = 0 },
                        onNext = { page.value = 2 },
                        onOpenDropdown = {
                            dropdownReq = it
                            dropdownOpen = true
                        },
                    )

                    2 -> AgreementPage(
                        codeVerified = codeVerified,
                        agreed = agreed,
                        onAgreedChange = { agreed = it },
                        onOpenCode = {
                            codeValue = Random.nextInt(1000, 10000).toString()
                            codeDialog = true
                        },
                        onOpenDoc = { readerDoc = it },
                        onBack = { page.value = 1 },
                        onNext = { page.value = 3 },
                    )

                    else -> DonePage(playKey = page.value, onFinish = onFinish)
                }
            }
        }

        // 液体玻璃下拉弹卡（画在页面之上，不被 Card 裁剪）
        GlassDropdownOverlay(
            request = dropdownReq,
            expanded = dropdownOpen,
            backdrop = backdrop,
            rootHeightPx = rootHeightPx,
            onDismiss = { dropdownOpen = false },
        )

        if (readerDoc >= 0) {
            DocReader(docIndex = readerDoc, onBack = { readerDoc = -1 })
        }

        if (codeDialog) {
            CodeDialog(
                code = codeValue,
                onDismiss = { codeDialog = false },
                onSuccess = {
                    codeVerified = true
                    codeDialog = false
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 欢迎页原生卡片视觉原件（四屏共用）
//
// 只沿用模块的组件语言：Miuix 卡片、主题灰底/白卡、强调蓝、系统无衬线、
// squircle 圆角与状态胶囊；不保留视觉稿的编辑排版骨架与装饰动效。
// cf/welcome-native-1124c
// ---------------------------------------------------------------------------

/** 欢迎页配色：模块主线同源，附加状态色与标识渐变端点。cf/welcome-native-1124d / cf/logo-tone-1125k */
private data class EdColors(
    val bg: Color,
    val panel: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val warn: Color,
    val logoStart: Color,
    val logoEnd: Color,
)

/** 欢迎页配色实例：强调蓝、主题卡片与文字色全部取自全局主题。cf/welcome-native-1124e */
@Composable
private fun edColors(): EdColors {
    val cs = MiuixTheme.colorScheme
    return EdColors(
        bg = pageBackgroundColor(),
        panel = cardContainerColor(),
        fg = cs.onSurface,
        fg2 = cs.onSurfaceVariantSummary,
        fg3 = cs.onSurfaceVariantSummary.copy(alpha = 0.72f),
        line = cs.outline.copy(alpha = 0.5f),
        accent = colorResource(R.color.accent),
        onAccent = Color.White,
        good = Color(0xFF00B050),
        warn = Color(0xFFE39B18),
        // 标识底板使用真实资源的淡蓝斜向渐变。cf/logo-tone-1125k2
        logoStart = Color(0xFFC5DDF7),
        logoEnd = Color(0xFF90B4EC),
    )
}

/** 卡片内图标种类。cf/welcome-native-icon-1124f */
private object WGlyphKind {
    const val CLIPBOARD = 0
    const val SYNC = 1
    const val KEYBOARD = 2
    const val SHIELD = 3
    const val GESTURE = 4
    const val DOC = 5
    const val CHECK = 6
    const val LANG = 7
}

/** 状态胶囊色调。cf/welcome-native-tone-1124g */
private object WTone {
    const val BLUE = 0
    const val GREEN = 1
    const val AMBER = 2
}

/** 模块标识：蓝色圆角底板 + 仓库内真实白色图形，图形占比 74%。cf/welcome-native-logo-1124h */
@Composable
private fun WelcomeLogo(c: EdColors) {
    Box(
        modifier = Modifier
            .size(86.dp)
            .clip(RoundedCornerShape(26.dp))
            // 淡蓝斜向渐变取自原版标识资源。cf/logo-tone-1125k3
            .background(Brush.linearGradient(listOf(c.logoStart, c.logoEnd), start = Offset.Zero, end = Offset(1000f, 1000f))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_glyph),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(0.74f),
        )
    }
}

/** 卡片内线性图标，全部自绘，避免引入额外资源。cf/welcome-native-icon-1124i */
@Composable
private fun WGlyph(kind: Int, color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = h * 0.085f
        when (kind) {
            WGlyphKind.CLIPBOARD -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * .18f, h * .12f),
                    size = Size(w * .64f, h * .76f),
                    cornerRadius = CornerRadius(h * .14f),
                    style = Stroke(sw),
                )
                drawLine(color, Offset(w * .32f, h * .42f), Offset(w * .68f, h * .42f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .32f, h * .60f), Offset(w * .58f, h * .60f), sw, StrokeCap.Round)
            }
            WGlyphKind.SYNC -> {
                drawArc(color, 35f, 250f, false, Offset(w * .08f, h * .12f), Size(w * .84f, h * .76f), style = Stroke(sw))
                drawCircle(color, h * .07f, Offset(w * .82f, h * .20f))
                drawCircle(color, h * .07f, Offset(w * .18f, h * .80f))
            }
            WGlyphKind.KEYBOARD -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * .08f, h * .20f),
                    size = Size(w * .84f, h * .60f),
                    cornerRadius = CornerRadius(h * .13f),
                    style = Stroke(sw),
                )
                drawLine(color, Offset(w * .26f, h * .42f), Offset(w * .28f, h * .42f), sw * 1.6f, StrokeCap.Round)
                drawLine(color, Offset(w * .49f, h * .42f), Offset(w * .51f, h * .42f), sw * 1.6f, StrokeCap.Round)
                drawLine(color, Offset(w * .72f, h * .42f), Offset(w * .74f, h * .42f), sw * 1.6f, StrokeCap.Round)
                drawLine(color, Offset(w * .28f, h * .63f), Offset(w * .72f, h * .63f), sw, StrokeCap.Round)
            }
            WGlyphKind.SHIELD -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * .22f, h * .42f),
                    size = Size(w * .56f, h * .42f),
                    cornerRadius = CornerRadius(h * .12f),
                    style = Stroke(sw),
                )
                drawArc(color, 180f, 180f, false, Offset(w * .30f, h * .12f), Size(w * .40f, h * .48f), style = Stroke(sw))
            }
            WGlyphKind.GESTURE -> {
                drawLine(color, Offset(w * .12f, h * .76f), Offset(w * .88f, h * .76f), sw * 1.4f, StrokeCap.Round)
                drawLine(color, Offset(w * .24f, h * .20f), Offset(w * .76f, h * .66f), sw, StrokeCap.Round)
            }
            WGlyphKind.DOC -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * .18f, h * .10f),
                    size = Size(w * .64f, h * .80f),
                    cornerRadius = CornerRadius(h * .10f),
                    style = Stroke(sw),
                )
                drawLine(color, Offset(w * .32f, h * .40f), Offset(w * .68f, h * .40f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .32f, h * .60f), Offset(w * .60f, h * .60f), sw, StrokeCap.Round)
            }
            WGlyphKind.CHECK -> {
                drawLine(color, Offset(w * .20f, h * .52f), Offset(w * .42f, h * .72f), sw * 1.4f, StrokeCap.Round)
                drawLine(color, Offset(w * .42f, h * .72f), Offset(w * .80f, h * .28f), sw * 1.4f, StrokeCap.Round)
            }
            WGlyphKind.LANG -> {
                drawCircle(color, h * .38f, Offset(w * .50f, h * .50f), style = Stroke(sw))
                drawLine(color, Offset(w * .12f, h * .50f), Offset(w * .88f, h * .50f), sw, StrokeCap.Round)
            }
        }
    }
}

/** 模块风卡片容器。cf/welcome-native-card-1124j */
@Composable
private fun WCard(content: @Composable ColumnScope.() -> Unit) {
    val c = edColors()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = c.panel),
        content = content,
    )
}

/** 返回控件：主题卡片圆角底 + 细箭头，靠左。cf/welcome-native-back-1124j2 */
@Composable
private fun EdBack(onBack: () -> Unit, c: EdColors) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(c.panel)
                .border(width = 1.dp, color = c.line, shape = shape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = c.fg,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 180f },
            )
        }
    }
}

/** 标题与副标题。cf/welcome-native-heading-1124k */
@Composable
private fun WHeading(title: String, subtitle: String, c: EdColors, top: Dp = 16.dp) {
    Spacer(Modifier.height(top))
    Text(text = title, fontFamily = FontFamily.SansSerif, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.fg)
    Spacer(Modifier.height(7.dp))
    Text(text = subtitle, fontFamily = FontFamily.SansSerif, fontSize = 14.sp, color = c.fg2, lineHeight = 21.sp)
}

/** 章节小标题。cf/welcome-native-section-1124l */
@Composable
private fun WSectionLabel(text: String, c: EdColors) {
    Text(text = text, fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.fg3)
}

/** 卡片内分隔线。cf/welcome-native-divider-1124m */
@Composable
private fun WDivider(c: EdColors) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** 状态胶囊颜色。cf/welcome-native-status-1124n */
private fun wToneColor(tone: Int, c: EdColors): Color = when (tone) {
    WTone.GREEN -> c.good
    WTone.AMBER -> c.warn
    else -> c.accent
}

/** 通用状态行。cf/welcome-native-row-1124o */
@Composable
private fun WRow(
    glyph: Int,
    title: String,
    subtitle: String? = null,
    status: String? = null,
    tone: Int = WTone.BLUE,
    c: EdColors,
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 62.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 13.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(c.accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { WGlyph(kind = glyph, color = c.accent) }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.fg)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(text = subtitle, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = c.fg3, lineHeight = 15.sp)
            }
        }
        if (!status.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            val sc = wToneColor(tone, c)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(sc.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(text = status, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = sc)
            }
        }
    }
}

/** 说明文字行。cf/welcome-native-notice-1124p */
@Composable
private fun WNoticeLine(text: String, c: EdColors) {
    Text(
        text = text,
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        color = c.fg2,
        lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

/** 勾选行。cf/welcome-native-check-1124q */
@Composable
private fun WCheckRow(checked: Boolean, title: String, subtitle: String?, c: EdColors, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (checked) c.accent else Color.Transparent)
                .border(width = 1.6.dp, color = if (checked) c.accent else c.fg3, shape = RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = c.onAccent, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.fg)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(text = subtitle, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = c.fg3)
            }
        }
    }
}

/** 主行动按钮。cf/welcome-native-button-1124r */
@Composable
private fun WPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, c: EdColors) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) c.accent else c.line)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) c.onAccent else c.fg3)
    }
}

/** 次行动按钮。cf/welcome-native-button-1124s */
@Composable
private fun WSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, c: EdColors) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.panel)
            .border(width = 1.dp, color = c.line, shape = RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.fg2)
    }
}

/** 页脚按钮区。cf/welcome-native-footer-1124t */
@Composable
private fun WFooter(primary: String, onClick: () -> Unit, c: EdColors, enabled: Boolean = true, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            WSecondaryButton(text = stringResource(R.string.wn_back), onClick = onBack, modifier = Modifier.width(92.dp), c = c)
        }
        WPrimaryButton(text = primary, enabled = enabled, onClick = onClick, modifier = Modifier.weight(1f), c = c)
    }
}

// ---------------------------------------------------------------------------
// 第一页：欢迎与功能概览。cf/welcome-native-page1-1124u
// ---------------------------------------------------------------------------

@Composable
private fun IntroPage(onNext: () -> Unit) {
    val c = edColors()
    Column(modifier = Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            // 内容继续下移，靠近底部按钮但保留呼吸空间。cf/welcome-native-offset-1125g2
            Spacer(Modifier.height(128.dp))
            WelcomeLogo(c)
            Spacer(Modifier.height(24.dp))
            Text(text = stringResource(R.string.welcome_title), fontFamily = FontFamily.SansSerif, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.fg)
            Text(text = stringResource(R.string.welcome_brand), fontFamily = FontFamily.SansSerif, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.fg)
            Spacer(Modifier.height(7.dp))
            Text(text = stringResource(R.string.wn_subtitle), fontFamily = FontFamily.SansSerif, fontSize = 14.sp, color = c.fg2, lineHeight = 22.sp)
            Spacer(Modifier.height(28.dp))
            WSectionLabel(text = stringResource(R.string.wn_section_features), c = c)
            Spacer(Modifier.height(10.dp))
            WCard {
                WRow(glyph = WGlyphKind.CLIPBOARD, title = stringResource(R.string.wn_feature_clipboard), subtitle = stringResource(R.string.wn_feature_clipboard_sub), c = c)
                WDivider(c)
                WRow(glyph = WGlyphKind.SYNC, title = stringResource(R.string.wn_feature_sync), subtitle = stringResource(R.string.wn_feature_sync_sub), c = c)
                WDivider(c)
                WRow(glyph = WGlyphKind.KEYBOARD, title = stringResource(R.string.wn_feature_fullscreen), subtitle = stringResource(R.string.wn_feature_fullscreen_sub), c = c)
            }
            Spacer(Modifier.height(12.dp))
            Text(text = stringResource(R.string.wn_privacy_note), fontFamily = FontFamily.SansSerif, fontSize = 12.sp, color = c.fg3, lineHeight = 18.sp)
            Spacer(Modifier.weight(1f))
        }
        WFooter(primary = stringResource(R.string.wn_begin), onClick = onNext, c = c)
    }
}

// ---------------------------------------------------------------------------
// 第二页：环境检查。cf/welcome-native-page2-1124v
// ---------------------------------------------------------------------------

@Composable
private fun PrepPage(
    hyperOsVersion: String,
    androidVersion: String,
    moduleActive: Boolean,
    hasRoot: Boolean,
    gestureLineHidden: Boolean,
    multiKeyboard: Boolean,
    langMode: Int,
    onLanguageSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onOpenDropdown: (DropdownRequest) -> Unit,
) {
    val c = edColors()
    val unknown = stringResource(R.string.prep_unknown)
    val done = stringResource(R.string.wn_check_done)
    val pending = stringResource(R.string.wn_check_pending)
    val langOptions = listOf(
        stringResource(R.string.opt_default),
        stringResource(R.string.opt_zh),
        stringResource(R.string.opt_en),
    )
    Column(modifier = Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        // 顶部返回与底部「上一步」重复，已移除；内容整体下移并避开曲面边缘。cf/welcome-native-offset-1125h
        Spacer(Modifier.height(96.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            WHeading(title = stringResource(R.string.prep_title), subtitle = stringResource(R.string.wn_prep_subtitle), c = c)
            Spacer(Modifier.height(22.dp))
            WSectionLabel(text = stringResource(R.string.wn_section_checks), c = c)
            Spacer(Modifier.height(10.dp))
            WCard {
                WRow(
                    glyph = WGlyphKind.CHECK,
                    title = stringResource(R.string.wn_activate_title),
                    subtitle = stringResource(R.string.wn_activate_sub),
                    status = if (moduleActive) done else pending,
                    tone = if (moduleActive) WTone.GREEN else WTone.AMBER,
                    c = c,
                )
                WDivider(c)
                WRow(
                    glyph = WGlyphKind.SHIELD,
                    title = stringResource(R.string.wn_root_title),
                    subtitle = stringResource(R.string.wn_root_sub),
                    status = if (hasRoot) done else pending,
                    tone = if (hasRoot) WTone.GREEN else WTone.AMBER,
                    c = c,
                )
                WDivider(c)
                WRow(
                    glyph = WGlyphKind.GESTURE,
                    title = stringResource(R.string.prep_gesture_line),
                    subtitle = stringResource(R.string.prep_gesture_line_sub),
                    status = if (gestureLineHidden) done else pending,
                    tone = if (gestureLineHidden) WTone.GREEN else WTone.AMBER,
                    c = c,
                )
                WDivider(c)
                WRow(
                    glyph = WGlyphKind.KEYBOARD,
                    title = stringResource(R.string.prep_multi_keyboard),
                    subtitle = stringResource(R.string.prep_multi_keyboard_sub),
                    status = if (multiKeyboard) done else pending,
                    tone = if (multiKeyboard) WTone.GREEN else WTone.AMBER,
                    c = c,
                )
                WDivider(c)
                GlassDropdownRow(
                    title = stringResource(R.string.prep_language),
                    items = langOptions,
                    selectedIndex = langMode.coerceIn(0, langOptions.size - 1),
                    onSelected = onLanguageSelect,
                    onOpen = onOpenDropdown,
                )
            }
            Spacer(Modifier.height(14.dp))
            WCard { WNoticeLine(text = stringResource(R.string.wn_prep_note), c = c) }
            Spacer(Modifier.weight(1f))
        }
        WFooter(primary = stringResource(R.string.welcome_next), onClick = onNext, c = c, onBack = onBack)
    }
}

// ---------------------------------------------------------------------------
// 第三页：使用须知。cf/welcome-native-page3-1124w
// ---------------------------------------------------------------------------

@Composable
private fun AgreementPage(
    codeVerified: Boolean,
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onOpenCode: () -> Unit,
    onOpenDoc: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val c = edColors()
    Column(modifier = Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        // 顶部返回已移除；版面位置微调上移，保持底部按钮上方留白。cf/welcome-native-offset-1125i3
        Spacer(Modifier.height(52.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            WHeading(title = stringResource(R.string.agreement_title), subtitle = stringResource(R.string.wn_agreement_subtitle), c = c)
            Spacer(Modifier.height(22.dp))
            WSectionLabel(text = stringResource(R.string.wn_section_notice), c = c)
            Spacer(Modifier.height(10.dp))
            WCard {
                WNoticeLine(text = stringResource(R.string.wn_notice_1), c = c)
                WDivider(c)
                WNoticeLine(text = stringResource(R.string.wn_notice_2), c = c)
                WDivider(c)
                WNoticeLine(text = stringResource(R.string.wn_notice_3), c = c)
                WDivider(c)
                WRow(
                    glyph = WGlyphKind.CHECK,
                    title = stringResource(R.string.agreement_code),
                    subtitle = stringResource(if (codeVerified) R.string.agreement_code_sub_done else R.string.agreement_code_sub),
                    status = stringResource(if (codeVerified) R.string.wn_check_verified else R.string.wn_check_verify),
                    tone = if (codeVerified) WTone.GREEN else WTone.AMBER,
                    c = c,
                    onClick = if (codeVerified) null else onOpenCode,
                )
                WDivider(c)
                WRow(glyph = WGlyphKind.DOC, title = stringResource(R.string.agreement_user), subtitle = null, c = c, onClick = { onOpenDoc(0) })
                WDivider(c)
                WRow(glyph = WGlyphKind.DOC, title = stringResource(R.string.agreement_disclaimer), subtitle = null, c = c, onClick = { onOpenDoc(1) })
                WDivider(c)
                WRow(glyph = WGlyphKind.DOC, title = stringResource(R.string.agreement_privacy), subtitle = null, c = c, onClick = { onOpenDoc(2) })
                WDivider(c)
                WCheckRow(
                    checked = agreed,
                    title = stringResource(R.string.wn_agree_simple),
                    subtitle = stringResource(R.string.wn_agree_sub),
                    c = c,
                    onClick = { if (codeVerified) onAgreedChange(!agreed) else onOpenCode() },
                )
            }
            Spacer(Modifier.weight(1f))
        }
        WFooter(
            primary = stringResource(R.string.wn_agree_continue),
            onClick = onNext,
            c = c,
            enabled = agreed && codeVerified,
            onBack = onBack,
        )
    }
}

// ---------------------------------------------------------------------------
// 第四页：设置完成。cf/welcome-native-page4-1124x
// ---------------------------------------------------------------------------

@Composable
private fun DonePage(playKey: Int, onFinish: () -> Unit) {
    val c = edColors()
    Column(modifier = Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            // 内容继续下移，靠近底部按钮但保留呼吸空间。cf/welcome-native-offset-1125j2
            Spacer(Modifier.height(136.dp))
            Box(
                modifier = Modifier.size(88.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = c.accent, modifier = Modifier.size(46.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(text = stringResource(R.string.welcome_done_title), fontFamily = FontFamily.SansSerif, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.fg)
            Spacer(Modifier.height(7.dp))
            Text(text = stringResource(R.string.wn_done_subtitle), fontFamily = FontFamily.SansSerif, fontSize = 14.sp, color = c.fg2, lineHeight = 21.sp)
            Spacer(Modifier.height(28.dp))
            WSectionLabel(text = stringResource(R.string.wn_section_status), c = c)
            Spacer(Modifier.height(10.dp))
            WCard {
                WRow(glyph = WGlyphKind.CLIPBOARD, title = stringResource(R.string.wn_feature_clipboard), subtitle = stringResource(R.string.wn_status_clipboard_sub), status = stringResource(R.string.wn_status_clipboard), tone = WTone.GREEN, c = c)
                WDivider(c)
                WRow(glyph = WGlyphKind.KEYBOARD, title = stringResource(R.string.wn_feature_fullscreen), subtitle = stringResource(R.string.wn_status_fullscreen_sub), status = stringResource(R.string.wn_status_fullscreen), tone = WTone.GREEN, c = c)
                WDivider(c)
                WRow(glyph = WGlyphKind.SYNC, title = stringResource(R.string.wn_feature_sync), subtitle = stringResource(R.string.wn_status_sync_sub), status = stringResource(R.string.wn_status_sync), tone = WTone.BLUE, c = c)
                WDivider(c)
                WRow(glyph = WGlyphKind.GESTURE, title = stringResource(R.string.prep_gesture_line), subtitle = stringResource(R.string.wn_status_gesture_sub), status = stringResource(R.string.wn_status_gesture), tone = WTone.GREEN, c = c)
            }
            Spacer(Modifier.weight(1f))
        }
        WFooter(primary = stringResource(R.string.wn_enter), onClick = onFinish, c = c)
    }
}

private val DOC_TITLES = intArrayOf(
    R.string.agreement_user,
    R.string.agreement_disclaimer,
    R.string.agreement_privacy,
)

private val DOC_ASSETS = arrayOf(
    "legal/user_agreement.txt",
    "legal/disclaimer.txt",
    "legal/privacy_policy.txt",
)

/** 协议正文加粗标记渲染：**文本** 转为粗体，星号不再显示。cf/legal-bold-1125m */
private fun legalText(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (true) {
        val start = text.indexOf("**", cursor)
        if (start < 0) break
        val end = text.indexOf("**", start + 2)
        if (end < 0) break
        withStyle(SpanStyle(color = color)) { append(text.substring(cursor, start)) }
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
            append(text.substring(start + 2, end))
        }
        cursor = end + 2
    }
    if (cursor < text.length) {
        withStyle(SpanStyle(color = color)) { append(text.substring(cursor)) }
    }
}

@Composable
private fun DocReader(docIndex: Int, onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val title = stringResource(DOC_TITLES[docIndex])
    val body = remember(docIndex) {
        runCatching {
            context.assets.open(DOC_ASSETS[docIndex]).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    Box(modifier = Modifier.fillMaxSize().background(pageBackgroundColor())) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            ReaderTopBar(title = title, onBack = onBack)
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
                        // 正文按轻量加粗标记渲染。cf/legal-bold-1125m2
                        else -> Text(
                            text = remember(line) { legalText(line, colors.onSurface) },
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

// ---------------------------------------------------------------------------
// 动态验证码弹卡（本地随机 4 位数字，输入正确才放行勾选协议）
// ---------------------------------------------------------------------------

@Composable
private fun CodeDialog(code: String, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val accent = colorResource(R.color.accent)
    val colors = MiuixTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val surface = if (dark) Color(0xFF242428) else Color.White
    val fieldBg = if (dark) Color(0xFF1B1B1F) else Color(0xFFF2F2F4)
    val errColor = Color(0xFFFF5A52)

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(surface)
                // 吃掉卡片内的点击，避免穿透到遮罩把自己关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.agreement_code),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.agreement_code_prompt),
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                code.forEach { ch ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ch.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fieldBg)
                    .border(
                        width = 1.dp,
                        color = if (error) errColor else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { v ->
                        input = v.filter { it.isDigit() }.take(4)
                        error = false
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 20.sp,
                        color = colors.onSurface,
                        textAlign = TextAlign.Center,
                        letterSpacing = 6.sp,
                    ),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (input.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.agreement_code_input),
                                    fontSize = 15.sp,
                                    color = colors.onSurfaceVariantSummary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.agreement_code_wrong),
                    fontSize = 13.sp,
                    color = errColor,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogButton(
                    text = stringResource(R.string.agreement_code_cancel),
                    filled = false,
                    enabled = true,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    text = stringResource(R.string.agreement_code_confirm),
                    filled = true,
                    enabled = input.isNotEmpty(),
                    onClick = { if (input == code) onSuccess() else error = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 通用小组件
// ---------------------------------------------------------------------------

@Composable
private fun ReaderTopBar(title: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 64.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = 180f },
            )
        }
    }
}

/** 模块风自检行：无衬线标题 + 弱化小注 + 右侧强调勾，行间留白。cf/edstyle-1124n / cf/modstyle-1125l */
@Composable
private fun CheckRow(title: String, summary: String?, ok: Boolean?, c: EdColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = FontFamily.SansSerif,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = c.fg,
                style = TextStyle(letterSpacing = 0.5.sp),
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 10.sp,
                    color = c.fg3,
                    style = TextStyle(letterSpacing = 0.3.sp),
                )
            }
        }
        if (ok == true) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 模块风文档行：无衬线标题 + 右侧细箭头，整行可点。cf/edstyle-1124o / cf/modstyle-1125m */
@Composable
private fun DocRow(title: String, onClick: () -> Unit, c: EdColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = c.fg,
            style = TextStyle(letterSpacing = 0.5.sp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = c.accent,
            modifier = Modifier
                .size(width = 18.dp, height = 12.dp)
                .modifierRotate(-90f),
        )
    }
}

@Composable
private fun DialogButton(
    text: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = colorResource(R.color.accent)
    val dark = isSystemInDarkTheme()
    val bg = when {
        filled && enabled -> accent
        filled -> if (dark) Color(0xFF2E2E33) else Color(0xFFE3E3E8)
        dark -> Color(0xFF2E2E33)
        else -> Color(0xFFEDEDF0)
    }
    val fg = when {
        filled && enabled -> Color.White
        filled -> if (dark) Color(0xFF6A6A70) else Color(0xFF9A9AA0)
        else -> if (dark) Color(0xFFC8C8D0) else Color(0xFF4A4A50)
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}
