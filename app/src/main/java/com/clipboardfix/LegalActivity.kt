/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import com.clipboardfix.ui.AppConfig
import com.clipboardfix.ui.ClipboardFixTheme
import com.clipboardfix.ui.LegalPage
import com.clipboardfix.ui.SnackbarBridge

/**
 * 二级页面：用户协议与声明。
 *
 * 由关于页「协议与声明 → 用户协议」进入，集中展示用户协议、免责声明与隐私政策三份文本。
 * 与 LicensesActivity 同款沉浸式窗口与语言覆盖，页面内容全部由 Compose 绘制（见 LegalPage）。
 * 引导页协议勾选后也可从关于页回到此处重新查阅。cf/legalhub-act-1124c
 */
class LegalActivity : ComponentActivity() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(AppConfig.applyLocale(base!!))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false)
            window.setNavigationBarContrastEnforced(false)
        }
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        SnackbarBridge.start()

        val cv = ComposeView(this)
        cv.setContent {
            ClipboardFixTheme {
                LegalPage(onBack = { finish() })
            }
        }
        setContentView(cv)
    }
}
