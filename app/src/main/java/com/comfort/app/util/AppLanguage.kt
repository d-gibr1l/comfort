package com.comfort.app.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The app's own language (Settings › Appearance › Language), apart from the phone's.
 *
 * Android 13+ keeps a per-app language itself (LocaleManager): it applies it everywhere, lists it
 * in the system's App languages settings (res/xml/locales_config.xml), and keeps it across updates.
 * Before 13 there's no such thing without AppCompat (which this app doesn't use), so the choice is
 * saved here and applied by [wrap]: to each activity and to the Application, so notifications and
 * other background text follow it too. */
object AppLanguage {
    /** The languages the app has text for, as BCP 47 tags; "" follows the phone. Each is shown in
     * its own language in the picker, so it can be found whatever the app is set to. */
    val SUPPORTED = listOf("", "en", "es", "pt-BR", "de", "fr", "ru", "id", "ar", "iw", "fa", "tr", "it")

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** The chosen language's tag, or "" for the phone's. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }

    /** Switches the app to [tag] ("" for the phone's). On 13+ Android recreates the screens
     * itself; before it, [activity] is recreated so it picks the language up. */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
            activity.recreate()
        }
    }

    /** [base] in the chosen language, for attachBaseContext — before Android 13 only (13+ does it
     * itself). Unchanged when following the phone. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** [tag]'s name in its own language ("Deutsch", "Português (Brasil)"); capitalised, as some
     * languages write their own names in lower case. */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
