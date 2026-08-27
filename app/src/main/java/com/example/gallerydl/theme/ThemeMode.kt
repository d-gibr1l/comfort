package com.example.gallerydl.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val setMode: (ThemeMode) -> Unit = {},
    val lightTheme: AppTheme = AppTheme.DEFAULT,
    val setLightTheme: (AppTheme) -> Unit = {},
    val darkTheme: AppTheme = AppTheme.DEFAULT,
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
    }

    fun getLightTheme(context: Context): AppTheme = getAppTheme(context, KEY_LIGHT_THEME)
    fun setLightTheme(context: Context, theme: AppTheme) = setAppTheme(context, KEY_LIGHT_THEME, theme)

    fun getDarkTheme(context: Context): AppTheme = getAppTheme(context, KEY_DARK_THEME)
    fun setDarkTheme(context: Context, theme: AppTheme) = setAppTheme(context, KEY_DARK_THEME, theme)

    private fun getAppTheme(context: Context, key: String): AppTheme {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, AppTheme.DEFAULT.name)
        return runCatching { AppTheme.valueOf(stored ?: AppTheme.DEFAULT.name) }
            .getOrDefault(AppTheme.DEFAULT)
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
