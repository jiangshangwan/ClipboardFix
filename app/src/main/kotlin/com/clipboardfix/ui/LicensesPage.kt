package com.clipboardfix.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

/**
 * 二级页面「开源许可证」：关于页「项目引用」卡片进入，替代旧版直接弹卡展示致谢名单的写法。
 *
 * 条目按依赖逐个往下排列（不分组）：先列本模块直接依赖的库，再列参考过的开源项目；
 * 点击整行跳转对应项目主页。本模块使用了下列第三方项目的代码 / API，许可证信息按其
 * 上游仓库 LICENSE 如实登记（DexKit 主体 Apache-2.0、其 Core/ 目录为 LGPL-3.0）。
 */
private const val URL_MIUIX = "https://github.com/compose-miuix-ui/miuix"
private const val URL_LIBXPOSED = "https://github.com/libxposed/api"
private const val URL_DEXKIT = "https://github.com/LuckyPray/DexKit"
private const val URL_COMPOSE = "https://developer.android.com/jetpack/compose"
private const val URL_HAZE = "https://github.com/chrisbanes/haze"
private const val URL_BACKDROP = "https://github.com/Kyant0/AndroidLiquidGlass"
private const val URL_KOTLIN = "https://www.kotlincn.net/"
private const val URL_MIUI_IME = "https://github.com/RC1844/MIUI_IME_Unlock"

/**
 * 头部条参数（与关于页顶栏同一套观感）：
 * · [BAR_BLUR_DP] —— 顶栏模糊半径（顶部满强度）；
 * · [BAR_TINT_ALPHA] —— 页底色叠加在模糊之上的透明度（越大越「实」）；
 * · [BAR_CONTENT_GAP_DP] —— 内容区在顶栏之下的额外留白（说明小字与卡片的上边距）。
 *
 * 模糊源是下方滚动列表，经 Haze `hazeSource` / `hazeEffect` 实时录制 + 模糊：其模糊副本
 * 与来源**同帧**生成，故快速滑动无拖影；`progressive` 让模糊强度自上而下线性归零。
 *
 * 边界处理：模糊层高度**只取顶栏自身**，不再向下额外加渐隐带。`progressive` 已让强度
 * 在这段高度内线性归零，底边本身就是软的；若再加缓冲带，下面那行说明小字会落进模糊区，
 * 被糊成一片（此前就是这样）。内容占位按「顶栏实测高 57dp + 间距」给足，确保小字整行
 * 位于模糊层之外。
 */
private val BAR_BLUR_DP = 16f
private const val BAR_TINT_ALPHA = 0.30f
/** 顶栏真实高度比 TopAppBarDefaults.SmallTopAppBarCenterHeight（50dp）高约 7dp，多留 13dp 作间距。 */
private val BAR_CONTENT_GAP_DP = 20

/** 一条许可证条目：项目名 / 许可证 / 简介 / 项目主页。 */
private class LicenseItem(
    val nameRes: Int,
    val licenseRes: Int,
    val descRes: Int,
    val url: String,
)

@Composable
internal fun LicensesPage(
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 页底色（组合作用域预取，供头部条 hazeEffect 的非组合 lambda 引用）
    val pageBg = pageBackgroundColor()
    val hazeState = rememberHazeState()
    val items = listOf(
        LicenseItem(R.string.lic_miuix_name, R.string.license_apache_2, R.string.lic_miuix_desc, URL_MIUIX),
        LicenseItem(
            R.string.lic_libxposed_name,
            R.string.license_apache_2,
            R.string.lic_libxposed_desc,
            URL_LIBXPOSED,
        ),
        LicenseItem(R.string.lic_dexkit_name, R.string.license_dexkit, R.string.lic_dexkit_desc, URL_DEXKIT),
        LicenseItem(R.string.lic_compose_name, R.string.license_apache_2, R.string.lic_compose_desc, URL_COMPOSE),
        LicenseItem(R.string.lic_haze_name, R.string.license_apache_2, R.string.lic_haze_desc, URL_HAZE),
        LicenseItem(
            R.string.lic_backdrop_name,
            R.string.license_apache_2,
            R.string.lic_backdrop_desc,
            URL_BACKDROP,
        ),
        LicenseItem(R.string.lic_kotlin_name, R.string.license_apache_2, R.string.lic_kotlin_desc, URL_KOTLIN),
        LicenseItem(
            R.string.lic_miui_ime_name,
            R.string.license_opensource,
            R.string.lic_miui_ime_desc,
            URL_MIUI_IME,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg),
    ) {
        // ① 滚动内容，同时是头部条的模糊来源（hazeSource）。
        //    顶部先空出一整个顶栏的高度（状态栏 + SmallTopAppBar 居中高度），让首个条目
        //    初始不被顶栏压住；上滑时条目从下方穿过，被头部条实时模糊。
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
                    .height(TopAppBarDefaults.SmallTopAppBarCenterHeight + BAR_CONTENT_GAP_DP.dp),
            )

            // 顶栏大标题下的说明小字
            Text(
                text = stringResource(R.string.licenses_intro),
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp),
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = cardContainerColor()),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (item in items) {
                        LicenseRow(item = item, onOpenLink = onOpenLink)
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }

        // ② 固定顶栏：返回键与标题独立成一行（Miuix 标准控件 SmallTopAppBar + IconButton），
        //    背景 = Haze 渐进模糊（与关于页顶部条同款），强度在顶栏高度内线性归零、底边自带
        //    柔化，故**不再额外叠渐隐带**（叠了会把下面的说明小字糊掉）。
        //    放在来源之后（同级最上层），自身不含滚动容器、不抢列表触摸。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .hazeEffect(hazeState) {
                    blurRadius = BAR_BLUR_DP.dp
                    // 透明底：让 progressive 的 alpha 掩码单独控制「模糊强度自上而下归零」，
                    // 底边才能柔和化入内容（若设不透明页底色，底边会变硬边）。
                    backgroundColor = pageBg.copy(alpha = 0f)
                    noiseFactor = 0f
                    tints = listOf(HazeTint(pageBg.copy(alpha = BAR_TINT_ALPHA)))
                    progressive = HazeProgressive.Brush(
                        Brush.verticalGradient(
                            0f to Color.Black,
                            1f to Color.Black.copy(alpha = 0f),
                        )
                    )
                },
        ) {
            SmallTopAppBar(
                title = stringResource(R.string.licenses_title),
                // 顶栏底色透明，磨砂由下层 hazeEffect 提供；否则组件默认 surface 会盖住模糊
                color = pageBg.copy(alpha = 0f),
                titleColor = colors.onSurface,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // 复用 chevron 旋转 180° 作返回箭头，不新增图标资源
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron),
                            contentDescription = null,
                            tint = colors.onSurface,
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(180f),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun LicenseRow(
    item: LicenseItem,
    onOpenLink: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .rowPressClickable { onOpenLink(item.url) },
        title = stringResource(item.nameRes),
        summary = stringResource(item.licenseRes) + " · " + stringResource(item.descRes),
        insideMargin = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
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
