package com.clipboardfix

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import com.clipboardfix.ui.AppConfig
import com.clipboardfix.ui.ClipboardFixTheme
import com.clipboardfix.ui.LicensesPage
import com.clipboardfix.ui.SnackbarBridge
import com.clipboardfix.ui.showSnack

/**
 * 二级页面：开源许可证。
 *
 * 由关于页「项目引用 → 开源许可证」进入（Activity 级二级页，替代旧版直接弹卡展示致谢名单）。
 * 与 AboutActivity 同款沉浸式窗口与语言覆盖，页面内容全部由 Compose 绘制（见 LicensesPage）。
 */
class LicensesActivity : ComponentActivity() {

    override fun attachBaseContext(base: Context?) {
        // 语言偏好：与 AboutActivity 一致，仅为上下文覆盖 Locale
        super.attachBaseContext(AppConfig.applyLocale(base!!))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式（对齐 AboutActivity）：系统栏全透明且禁用系统自动加的对比度蒙层
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false)
            window.setNavigationBarContrastEnforced(false)
        }
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            // 浅色模式下让状态栏/导航栏文字变深，保证可读
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        // Snackbar 队列泵（幂等）：直接冷启动本页时也能用上提示
        SnackbarBridge.start()

        val cv = ComposeView(this)
        cv.setContent {
            ClipboardFixTheme {
                LicensesPage(
                    onBack = { finish() },
                    onOpenLink = { openUrl(it) },
                )
            }
        }
        setContentView(cv)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            showSnack(getString(R.string.open_failed))
        }
    }
}
