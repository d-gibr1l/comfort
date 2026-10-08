package com.comfort.app.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val setMode: (ThemeMode) -> Unit = {},
    val lightTheme: AppTheme = AppTheme.MONOCHROME,
    val setLightTheme: (AppTheme) -> Unit = {},
    val darkTheme: AppTheme = AppTheme.MONOCHROME,
    val setDarkTheme: (AppTheme) -> Unit = {},
    val pureBlack: Boolean = false,
    val setPureBlack: (Boolean) -> Unit = {},
)

val LocalThemeState = staticCompositionLocalOf { ThemeState() }

private const val PREFS_NAME = "gallerydl_theme_prefs"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_LIGHT_THEME = "light_theme"
private const val KEY_DARK_THEME = "dark_theme"
private const val KEY_PURE_BLACK = "pure_black_dark_mode"

object ThemePreferences {
    fun getThemeMode(context: Context): ThemeMode {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(stored ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
        applyNightMode(context, mode)
    }

    /** Tells Android (12+) which of light/dark the app is in, so the app's own resources follow
     * it rather than the phone's setting: the theme's status bar icon colour (values vs
     * values-night windowLightStatusBar), the splash screen, and the share sheet. Without it a
     * light app on a phone in dark mode got white status bar icons on a white page whenever
     * Android restored the theme's bar style — after the splash screen closed, on some phones a
     * beat after the app had set dark icons itself. Called at start-up and on every change. */
    fun applyNightMode(context: Context, mode: ThemeMode = getThemeMode(context)) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val manager = context.getSystemService(android.app.UiModeManager::class.java) ?: return
        val night = when (mode) {
            ThemeMode.SYSTEM -> android.app.UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> android.app.UiModeManager.MODE_NIGHT_YES
        }
        runCatching { manager.setApplicationNightMode(night) }
    }

    /** Whether the app is showing its dark theme, for screens outside MainActivity's theme state. */
    fun isDark(context: Context): Boolean = when (getThemeMode(context)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun getLightTheme(context: Context): AppTheme = getAppTheme(context, KEY_LIGHT_THEME)
    fun setLightTheme(context: Context, theme: AppTheme) = setAppTheme(context, KEY_LIGHT_THEME, theme)

    fun getDarkTheme(context: Context): AppTheme = getAppTheme(context, KEY_DARK_THEME)
    fun setDarkTheme(context: Context, theme: AppTheme) = setAppTheme(context, KEY_DARK_THEME, theme)

    private fun getAppTheme(context: Context, key: String): AppTheme {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, AppTheme.MONOCHROME.name)
        return runCatching { AppTheme.valueOf(stored ?: AppTheme.MONOCHROME.name) }
            .getOrDefault(AppTheme.MONOCHROME)
    }

    private fun setAppTheme(context: Context, key: String, theme: AppTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, theme.name)
            .apply()
    }

    fun isPureBlack(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PURE_BLACK, false)

    fun setPureBlack(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PURE_BLACK, enabled)
            .apply()
    }
}
