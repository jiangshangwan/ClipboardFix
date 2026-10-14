package com.clipboardfix.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.ContextCompat

/**
 * 应用级偏好与「语言 / 主题」应用逻辑。
 *
 * - 偏好存于本地 SharedPreferences（与 hook 无关，故不走 ModulePrefs 远程）。
 * - 主题：用系统 [UiModeManager.setApplicationNightMode]（API 31+，minSdk 33 满足），
 *   稳定驱动 Compose 的 isSystemInDarkTheme() 与 Miuix 主题，无需自定义 Activity 继承。
 * - 语言：在 [applyLocale] 中经 createConfigurationContext 覆盖 Locale，
 *   由 AboutActivity.attachBaseContext 挂载，切换即时随 recreate 生效。
 */
object AppConfig {

    const val PREFS_NAME = "clipboardfix_prefs"

    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_LANG_MODE = "lang_mode"
    const val KEY_DISABLE_UPDATE = "disable_update"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    const val LANG_SYSTEM = 0
    const val LANG_ZH = 1
    const val LANG_EN = 2

    @JvmStatic
    fun getThemeMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME_MODE, MODE_SYSTEM)

    @JvmStatic
    fun getLangMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LANG_MODE, LANG_SYSTEM)

    @JvmStatic
    fun isUpdateDisabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_UPDATE, false)

    @JvmStatic
    fun setThemeMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME_MODE, mode).apply()
        applyApplicationNightMode(ctx, mode)
    }

    @JvmStatic
    fun setLangMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LANG_MODE, mode).apply()
    }

    @JvmStatic
    fun setUpdateDisabled(ctx: Context, disabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISABLE_UPDATE, disabled).apply()
    }

    /** 把主题偏好映射到系统 Application Night Mode 并应用（仅用户主动切换时调用）。 */
    @JvmStatic
    fun applyApplicationNightMode(ctx: Context, mode: Int) {
        val umm = ContextCompat.getSystemService(ctx, UiModeManager::class.java) ?: return
        val sysMode = when (mode) {
            MODE_LIGHT -> UiModeManager.MODE_NIGHT_NO
            MODE_DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        umm.setApplicationNightMode(sysMode)
    }

    /** 在 attachBaseContext 中调用：仅为上下文覆盖语言（主题走系统 Application Night Mode）。 */
    @JvmStatic
    fun applyLocale(base: Context): Context {
        val lang = getLangMode(base)
        val loc = when (lang) {
            LANG_ZH -> java.util.Locale.SIMPLIFIED_CHINESE
            LANG_EN -> java.util.Locale.ENGLISH
            else -> null
        }
        if (loc == null) return base
        val cfg = Configuration(base.resources.configuration)
        val list = LocaleList(loc)
        cfg.setLocales(list)
        return base.createConfigurationContext(cfg)
    }
}
