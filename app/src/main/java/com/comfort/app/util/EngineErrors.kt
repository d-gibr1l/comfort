package com.comfort.app.util

import android.content.res.Resources
import com.comfort.app.R

/** The engines' errors (yt-dlp, gallery-dl, Instaloader and the wrappers' own) are English, and
 * come from code the app doesn't own, so they can't be translated as they are. The common ones
 * are recognised here and replaced by the app's own explanation, in the app's language; anything
 * else is shown as the engine wrote it. The original text stays in the download's error details
 * (DownloadEntity.errorDetails), which list each engine's own message. */
object EngineErrors {
    // First match wins, so the specific come before the general: "Unable to download webpage:
    // HTTP Error 404" is a missing page, not a network failure.
    private val RULES: List<Pair<Regex, Int>> = listOf(
        "No space left on device|ENOSPC" to R.string.engine_error_no_space,
        "can't use a SOCKS proxy" to R.string.engine_error_socks_proxy,
        "\\bDRM\\b" to R.string.engine_error_drm,
        // Names private and age-restricted as possibilities, so it goes before those.
        "Instagram didn.?t return this post" to R.string.engine_error_instagram_missing,
        "confirm you.?re not a bot" to R.string.engine_error_bot_check,
        "Sign in to confirm your age|age.?restricted|inappropriate for some users" to R.string.engine_error_age,
        "members.only|Join this channel" to R.string.engine_error_members_only,
        "Private video|This video is private|account is private|private (account|profile)" to R.string.engine_error_private,
        "available in your country|geo.?restrict" to R.string.engine_error_geo,
        "live event will begin|Premieres in|This live stream (recording )?is not available" to R.string.engine_error_not_started,
        "Requested format is not available" to R.string.engine_error_format,
        "HTTP Error 429|\\b429 Too Many|rate.?limit" to R.string.engine_error_rate_limited,
        "HTTP Error 401|login required|LoginRequired|AuthRequired|AuthorizationError|requires a logged.in session|needs? (to )?log ?in|use --cookies" to R.string.engine_error_login,
        "HTTP Error 403|\\b403 Forbidden" to R.string.engine_error_forbidden,
        "Video unavailable|This video is( no longer)? unavailable|has been removed|HTTP Error 404|\\b404 Not Found|NotFoundError|could not be found" to R.string.engine_error_unavailable,
        "Unsupported URL|No suitable extractor|NoExtractorError|Not a recognized Spotify link|Not an Instagram post" to R.string.engine_error_unsupported,
        "No video formats found|no info extracted|No video could be found|No tracks found|didn.?t provide a download link" to R.string.engine_error_no_media,
        "Unable to download webpage|Failed to resolve|name resolution|Name or service not known|timed? ?out|Connection (reset|refused|aborted)|Network is unreachable|No route to host|Couldn.?t reach" to R.string.engine_error_network,
    ).map { (pattern, res) -> Regex(pattern, RegexOption.IGNORE_CASE) to res }

    /** The app's own explanation of [raw] in [resources]' language, or null when it isn't one it knows. */
    fun explain(raw: String, resources: Resources): String? =
        RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(raw) }?.let { (_, res) -> resources.getString(res) }
}
