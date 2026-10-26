package com.comfort.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** SubtitleLanguage.pick shows in the preview sheet what yt_dlp_wrapper will fetch: per language
 * the uploaded track, else the auto-generated one (when on), "orig" being the video's language. */
class SubtitleLanguageTest {

    private val uploadedEnDe = listOf(SubtitleTrack("en", false), SubtitleTrack("de-DE", false), SubtitleTrack("ja", true))

    private fun pick(setting: String, tracks: List<SubtitleTrack> = uploadedEnDe, language: String? = "ja", auto: Boolean = true, translations: Boolean = false) =
        SubtitleLanguage.pick(tracks, setting, language, auto, translations).map { it.code to it.auto }

    @Test
    fun uploadedFirstThenAuto() {
        assertEquals(listOf("en" to false), pick("en"))
        // A regional track counts for its language.
        assertEquals(listOf("de-DE" to false), pick("de"))
        // The original (Japanese here) has only auto-generated captions.
        assertEquals(listOf("ja" to true), pick(SubtitleLanguage.ORIGINAL))
        assertEquals(emptyList<Pair<String, Boolean>>(), pick(SubtitleLanguage.ORIGINAL, auto = false))
    }

    @Test
    fun oneTrackPerLanguage() {
        // Asking for English twice, by code and as the original, gives it once.
        assertEquals(listOf("en" to false), pick("en,orig", language = "en"))
        assertEquals(listOf("de-DE" to false, "ja" to true), pick("de,orig"))
    }

    @Test
    fun missingLanguageSkippedOrTranslated() {
        assertEquals(emptyList<Pair<String, Boolean>>(), pick("fr"))
        // Where the site translates captions, the language comes as an auto track.
        assertEquals(listOf("fr" to true), pick("fr", translations = true))
        assertEquals(emptyList<Pair<String, Boolean>>(), pick("fr", translations = true, auto = false))
    }

    @Test
    fun originalWithoutKnownLanguageUsesTheAutoTrack() {
        assertEquals(listOf("ja" to true), pick(SubtitleLanguage.ORIGINAL, language = null))
        assertEquals(emptyList<Pair<String, Boolean>>(), pick(SubtitleLanguage.ORIGINAL, tracks = listOf(SubtitleTrack("en", false)), language = null))
    }
}
