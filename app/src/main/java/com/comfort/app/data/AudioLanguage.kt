package com.comfort.app.data

import java.util.Locale

/** One of a video's audio tracks, by language: its original or a dub (yt_dlp_wrapper.py's
 * list_info "audio_languages"). [code] is the site's language tag, "en-US", "es". */
data class AudioTrack(val code: String, val original: Boolean)

/** Which audio track a download asks for: Settings > Processing > Audio language, or the preview
 * sheet's pick for one download. A value is [ORIGINAL], [APP] or a language code; yt-dlp is asked
 * for the code (yt_dlp_wrapper._with_audio_language) and falls back to the original when the video
 * has no such dub. Songs always keep their original. */
object AudioLanguage {
    /** The video's own audio, whatever language it's in. The default. */
    const val ORIGINAL = ""

    /** The app's language (Settings > Appearance > Language, else the phone's). */
    const val APP = "app"

    /** Offered in Settings besides the two above: the languages YouTube dubs into most. */
    val COMMON = listOf("en", "es", "pt", "de", "fr", "ru", "id", "it", "ja", "ko", "zh", "hi", "ar", "tr", "pl", "vi", "th")

    /** The language code [setting] asks yt-dlp for; "" for the original. toLanguageTag, not
     * language: Java still calls Indonesian "in" and Hebrew "iw", sites use "id" and "he". */
    fun requested(setting: String): String =
        if (setting == APP) Locale.getDefault().toLanguageTag().substringBefore('-') else setting

    /** The track of [tracks] a download with [setting] gets: the asked-for language if the video
     * has it, else its original. */
    fun pick(tracks: List<AudioTrack>, setting: String): AudioTrack? {
        val wanted = requested(setting)
        return tracks.firstOrNull { wanted.isNotEmpty() && (it.code == wanted || it.code.startsWith("$wanted-")) }
            ?: tracks.firstOrNull { it.original }
            ?: tracks.firstOrNull()
    }

    /** [code]'s name in the app's language: "Spanish", "Spanisch", "English (United States)". */
    fun displayName(code: String): String {
        val locale = Locale.getDefault()
        return Locale.forLanguageTag(code).getDisplayName(locale)
            .ifBlank { code }
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
