package com.comfort.app.data

/** One of a video's subtitle tracks (yt_dlp_wrapper.py's list_info "subtitles"): uploaded by its
 * maker, or the site's own auto-generated captions in the video's language ([auto]). */
data class SubtitleTrack(val code: String, val auto: Boolean)

/** Which subtitle languages a download asks for: Settings > Processing > Subtitle languages, or
 * the preview sheet's pick for one download. A comma list of language codes and two tokens the
 * wrapper understands: [APP] (turned into the app's language here) and [ORIGINAL] (the video's
 * own language, which only the wrapper knows once it has the video). yt-dlp gets one track per
 * language: the uploaded one, else (with auto-generated captions on) the site's own. */
object SubtitleLanguage {
    const val APP = "app"
    const val ORIGINAL = "orig"

    /** Settings' default: subtitles in the app's language, and the video's original ones. */
    const val DEFAULT = "$APP,$ORIGINAL"

    fun parse(setting: String): List<String> = setting.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** [setting] with [APP] turned into a code, as the wrapper takes it ("de,orig"). */
    fun requested(setting: String): String =
        parse(setting).map { if (it == APP) AudioLanguage.requested(AudioLanguage.APP) else it }.distinct().joinToString(",")

    /** The tracks of [tracks] a download with [setting] gets, by the wrapper's rules: per language
     * the uploaded track, else the auto-generated one when [auto] is on; [ORIGINAL] is the video's
     * [language]. A language with no track is left out — or, with [translations], shown as the
     * site's translation (code only, [SubtitleTrack.auto] true). */
    fun pick(tracks: List<SubtitleTrack>, setting: String, language: String?, auto: Boolean, translations: Boolean): List<SubtitleTrack> {
        val picked = mutableListOf<SubtitleTrack>()
        for (token in parse(requested(setting))) {
            val code = if (token == ORIGINAL) language ?: tracks.firstOrNull { it.auto }?.code ?: continue else token
            fun matches(track: SubtitleTrack) = track.code == code || track.code.startsWith("$code-")
            val track = tracks.firstOrNull { !it.auto && matches(it) }
                ?: tracks.firstOrNull { it.auto && matches(it) }?.takeIf { auto }
                ?: SubtitleTrack(code, auto = true).takeIf { auto && translations }
                ?: continue
            if (picked.none { it.code == track.code }) picked += track
        }
        return picked
    }

    fun displayName(code: String) = AudioLanguage.displayName(code)
}
