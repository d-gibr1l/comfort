package com.comfort.app.data

import java.net.URI

enum class DownloadEngine { GALLERY_DL, YT_DLP, SPOTIFY }

/** Decides which engine a URL's *primary* pass should go through. Sites that only ever post
 * video skip gallery-dl entirely (it can't extract them at all); everything else goes through
 * gallery-dl first, since that's the engine with real image/gallery support, and DownloadWorker
 * separately falls back to (or supplements with) yt-dlp per item as needed — including TikTok,
 * whose "photo mode" slideshow posts are real image galleries gallery-dl handles properly, not
 * video at all; only its actual video posts need yt-dlp's supplement pass (see
 * [alwaysSupplementsVideo] below, same reasoning as Instagram's mixed carousels). */
object VideoSiteRouter {
    // Deliberately a fixed list rather than "try gallery-dl, see if it fails" for these — gallery-dl
    // doesn't support them at all, so there's no value in paying for a doomed gallery-dl attempt
    // first the way the fallback does for genuinely unknown sites.
    private val videoOnlyHosts = setOf(
        "youtube.com", "youtu.be",
        "vimeo.com",
        "twitch.tv",
        "crunchyroll.com",
        "bbc.co.uk", "bbc.com",
        "bloomberg.com",
        "discoveryplus.com",
        "bilibili.com",
        "bongacams.com",
        "cam4.com",
        "bitchute.com",
        "dailymotion.com",
    )

    // Spotify itself never hosts downloadable audio (its streams are DRM'd) — this app's own
    // spotify_wrapper.py instead scrapes Spotify's public embed pages for real metadata (no API
    // credentials needed — see the plan this was built from) and searches/downloads the matching
    // track from YouTube via the already-bundled yt-dlp. Neither gallery-dl nor plain yt-dlp can
    // do anything with a Spotify URL at all, so this is checked unconditionally, same spirit as
    // videoOnlyHosts below but its own dedicated engine rather than reusing YT_DLP.
    private val spotifyHosts = setOf("open.spotify.com", "spotify.com", "spotify.link")

    fun classify(url: String): DownloadEngine {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?: return DownloadEngine.GALLERY_DL

        if (spotifyHosts.any { host == it || host.endsWith(".$it") }) {
            return DownloadEngine.SPOTIFY
        }

        // Instagram reels are always videos and handle much better in yt-dlp immediately
        if ((host == "instagram.com" || host.endsWith(".instagram.com")) && url.contains("/reel/")) {
            return DownloadEngine.YT_DLP
        }

        return if (videoOnlyHosts.any { host == it || host.endsWith(".$it") }) {
            DownloadEngine.YT_DLP
        } else {
            DownloadEngine.GALLERY_DL
        }
    }

    private val videoExtensions = setOf("mp4", "webm", "mov", "mkv", "m4v", "avi", "flv", "wmv")

    fun isVideoFilename(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

    // Hosts where gallery-dl's own listing can't be trusted to flag every video item — unlike most
    // gallery-dl sites (image boards, art platforms) that never carry embedded video at all,
    // these routinely mix video into otherwise-image posts (Instagram's carousels) or post real
    // video under the same extractor as its image "photo mode" posts (TikTok), and a listing miss
    // means DownloadWorker's normal hasVideoItem check would otherwise skip yt-dlp entirely with
    // no trace of a video ever having existed (see DownloadWorker's own comment on this).
    private val alwaysSupplementVideoHosts = setOf("instagram.com", "tiktok.com")

    /** Whether [url]'s host is one where a yt-dlp supplement pass is always worth attempting after
     * gallery-dl's own image-only pass, even without its listing having flagged a video item —
     * see [alwaysSupplementVideoHosts] above. yt-dlp fails fast and silently (no user-facing
     * error; see DownloadWorker's actualCallback default branch) on a genuinely video-less post,
     * so the cost of a wrong guess is a few extra seconds, not a broken download. */
    fun alwaysSupplementsVideo(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        return alwaysSupplementVideoHosts.any { host == it || host.endsWith(".$it") }
    }

    /** A short, human-readable site name for [url] — "Instagram" rather than "instagram.com" or
     * the URL itself. Used as the placeholder title for a download until the real poster/caption
     * is known (see DownloadWorker's derivePosterCaptionTitle), and as the fallback if it never
     * is (a custom filename format, or an engine that never reports one). */
    fun siteName(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?: return "this link"
        val label = host.substringBeforeLast('.').substringAfterLast('.')
        return label.replaceFirstChar { it.uppercase() }
    }
}
