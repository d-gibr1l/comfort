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

    // Presentation-only signal for the download preview sheet ("does this URL deserve the
    // song-styled card/track-list instead of the video-styled one?") — deliberately NOT consulted
    // by classify() above. music.youtube.com still routes to YT_DLP (via the plain "youtube.com"
    // entry in videoOnlyHosts already matching its subdomain) and soundcloud.com still routes to
    // GALLERY_DL (it has its own gallery-dl extractor) exactly as before this existed; this only
    // changes how the preview sheet renders, never which engine downloads the link.
    private val songHosts = setOf("music.youtube.com", "soundcloud.com")

    fun isKnownSongHost(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?: return false
        return songHosts.any { host == it || host.endsWith(".$it") }
    }

    /** Whether [url] is a known "song" source for the preview sheet's own styling — a Spotify
     * link (always audio, see spotifyHosts above) or a known other song host (see [songHosts]).
     * Does not include "the user manually picked Audio quality on some other link" — that's
     * per-sheet UI state, not a property of the URL, so it's checked separately at the call site. */
    fun isSongSource(url: String): Boolean = classify(url) == DownloadEngine.SPOTIFY || isKnownSongHost(url)

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

    // Two-label public suffixes common enough among sites this app actually sees that the plain
    // "second-to-last label" heuristic below needs to skip both, not just the TLD — without this,
    // "mangaplus.shueisha.co.jp" read as "Co" (host.substringBeforeLast('.').substringAfterLast('.')
    // landed on "co", not the real "shueisha" brand label before it) — reproduced live as
    // "Downloading from Co" in the Errored queue. Not an exhaustive public-suffix list (that's a
    // much larger, constantly-updated dataset — overkill for a cosmetic placeholder title), just
    // the handful likely to actually show up.
    private val twoLabelSuffixes = setOf(
        "co.jp", "co.uk", "co.kr", "co.in", "co.nz", "co.za", "co.il", "co.id",
        "com.br", "com.au", "com.cn", "com.tw", "com.mx", "com.sg", "com.hk",
        "ne.jp", "or.jp", "ac.jp", "org.uk", "net.au",
    )

    /** A short, human-readable site name for [url] — "Instagram" rather than "instagram.com" or
     * the URL itself. Used as the placeholder title for a download until the real poster/caption
     * is known (see DownloadWorker's derivePosterCaptionTitle), and as the fallback if it never
     * is (a custom filename format, or an engine that never reports one). */
    fun siteName(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?: return "this link"
        val parts = host.split('.')
        val suffixLabels = if (parts.size >= 3 && "${parts[parts.size - 2]}.${parts.last()}" in twoLabelSuffixes) 2 else 1
        val label = parts.getOrNull(parts.size - 1 - suffixLabels) ?: parts.first()
        return label.replaceFirstChar { it.uppercase() }
    }
}
