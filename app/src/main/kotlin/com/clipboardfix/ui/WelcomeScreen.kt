package com.clipboardfix.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipboardfix.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次进入的欢迎引导（三页）：
 *
 * 1. 欢迎页 —— 品牌 + 一句话定位 + 圆形「下一步」按钮；
 * 2. 模块准备工作 —— 环境自检清单（HyperOS / Android / LSPosed 激活 / 超级用户 / 关闭手势提示线）+ 语言；
 * 3. 协议与声明 —— 用户协议 / 免责声明 / 隐私政策 + 动态验证码闸门 + 同意勾选。
 *
 * 页面状态由外部（Activity）持有并传入，使系统返回键与「语言切换触发 recreate」都能正确落页；
 * 语言行复用设置页的液体玻璃下拉弹卡（同一 Compose 树内做实时折射）。
 */
@Composable
fun WelcomeFlow(
    page: MutableState<Int>,
    hyperOsVersion: String,
    androidVersion: String,
    moduleActive: Boolean,
    hasRoot: Boolean,
    gestureLineHidden: Boolean,
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
                        langMode = langMode,
                        onLanguageSelect = onLanguageSelect,
                        onBack = { page.value = 0 },
                        onNext = { page.value = 2 },
                        onOpenDropdown = {
                            dropdownReq = it
                            dropdownOpen = true
                        },
                    )

                    else -> AgreementPage(
                        codeVerified = codeVerified,
                        agreed = agreed,
                        onAgreedChange = { agreed = it },
                        onOpenCode = {
                            codeValue = Random.nextInt(1000, 10000).toString()
                            codeDialog = true
                        },
                        onOpenDoc = { readerDoc = it },
                        onBack = { page.value = 1 },
                        onFinish = onFinish,
                    )
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
// 第一页：欢迎
// ---------------------------------------------------------------------------

@Composable
private fun IntroPage(onNext: () -> Unit) {
    val accent = colorResource(R.color.accent)
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.welcome_title),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(R.string.welcome_brand),
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.welcome_desc),
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            lineHeight = 35.sp,
        )
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(onClick = onNext),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.weight(1.45f))
    }
}

// ---------------------------------------------------------------------------
// 第二页：模块准备工作
// ---------------------------------------------------------------------------

@Composable
private fun PrepPage(
    hyperOsVersion: String,
    androidVersion: String,
    moduleActive: Boolean,
    hasRoot: Boolean,
    gestureLineHidden: Boolean,
    langMode: Int,
    onLanguageSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onOpenDropdown: (DropdownRequest) -> Unit,
) {
    val accent = colorResource(R.color.accent)
    val unknown = stringResource(R.string.prep_unknown)
    val langOptions = listOf(
        stringResource(R.string.opt_default),
        stringResource(R.string.opt_zh),
        stringResource(R.string.opt_en),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        WelcomeTopBar(onBack = onBack)
        Spacer(Modifier.height(30.dp))
        CenteredPageTitle(stringResource(R.string.prep_title))
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CheckRow(
                        title = stringResource(R.string.prep_hyperos),
                        summary = hyperOsVersion.ifBlank { unknown },
                        ok = hyperOsVersion.isNotBlank(),
                        accent = accent,
                    )
                    CheckRow(
                        title = stringResource(R.string.prep_android),
                        summary = androidVersion,
                        ok = Build.VERSION.SDK_INT >= 33,
                        accent = accent,
                    )
                    CheckRow(
                        title = stringResource(R.string.prep_activate),
                        summary = null,
                        ok = moduleActive,
                        accent = accent,
                    )
                    CheckRow(
                        title = stringResource(R.string.prep_root),
                        summary = null,
                        ok = hasRoot,
                        accent = accent,
                    )
                    // 读 global 设置 hide_gesture_line（1=已隐藏）判断小白条是否已关闭；读不到按未关闭处理
                    CheckRow(
                        title = stringResource(R.string.prep_gesture_line),
                        summary = stringResource(R.string.prep_gesture_line_sub),
                        ok = gestureLineHidden,
                        accent = accent,
                    )
                    GlassDropdownRow(
                        title = stringResource(R.string.prep_language),
                        items = langOptions,
                        selectedIndex = langMode.coerceIn(0, langOptions.size - 1),
                        onSelected = onLanguageSelect,
                        onOpen = onOpenDropdown,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        BottomButton(text = stringResource(R.string.welcome_next), enabled = true, onClick = onNext)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// 第三页：协议与声明
// ---------------------------------------------------------------------------

@Composable
private fun AgreementPage(
    codeVerified: Boolean,
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onOpenCode: () -> Unit,
    onOpenDoc: (Int) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val accent = colorResource(R.color.accent)
    val colors = MiuixTheme.colorScheme
    val dark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        WelcomeTopBar(onBack = onBack)
        Spacer(Modifier.height(30.dp))
        CenteredPageTitle(stringResource(R.string.agreement_title))
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DocRow(stringResource(R.string.agreement_user)) { onOpenDoc(0) }
                    DocRow(stringResource(R.string.agreement_disclaimer)) { onOpenDoc(1) }
                    DocRow(stringResource(R.string.agreement_privacy)) { onOpenDoc(2) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        modifier = Modifier
                            .fillMaxWidth()
                            .rowPressClickable { if (!codeVerified) onOpenCode() },
                        title = stringResource(R.string.agreement_code),
                        summary = stringResource(
                            if (codeVerified) R.string.agreement_code_sub_done
                            else R.string.agreement_code_sub,
                        ),
                        insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                        endActions = {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = colors.onSurfaceVariantSummary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = null,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        // 未验证时点勾选框 → 直接引导去验证，避免操作走空
                        if (codeVerified) onAgreedChange(!agreed) else onOpenCode()
                    }
                    .padding(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(21.dp)
                        .clip(CircleShape)
                        .background(if (agreed) accent else Color.Transparent)
                        .border(
                            width = 1.5.dp,
                            color = if (agreed) accent else if (dark) Color(0xFF565660) else Color(0xFFC4C4CC),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (agreed) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.agreement_read),
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariantSummary,
                    lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        BottomButton(
            text = stringResource(R.string.welcome_next),
            enabled = agreed && codeVerified,
            onClick = onFinish,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// 协议阅读器
// ---------------------------------------------------------------------------

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
                        else -> Text(
                            text = line,
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
private fun WelcomeTopBar(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
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

@Composable
private fun CenteredPageTitle(text: String) {
    Text(
        text = text,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckRow(title: String, summary: String?, ok: Boolean?, accent: Color) {
    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = title,
        summary = summary,
        insideMargin = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        endActions = {
            if (ok == true) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        onClick = null,
    )
}

@Composable
private fun DocRow(title: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .rowPressClickable(onClick),
        title = title,
        insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        endActions = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = null,
    )
}

@Composable
private fun BottomButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = colorResource(R.color.accent)
    val dark = isSystemInDarkTheme()
    val bg = when {
        enabled -> accent
        dark -> Color(0xFF2E2E33)
        else -> Color(0xFFE3E3E8)
    }
    val fg = when {
        enabled -> Color.White
        dark -> Color(0xFF6A6A70)
        else -> Color(0xFF9A9AA0)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
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
