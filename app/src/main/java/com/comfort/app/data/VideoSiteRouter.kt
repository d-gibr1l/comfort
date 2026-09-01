package com.comfort.app.data

import java.net.URI

enum class DownloadEngine { GALLERY_DL, YT_DLP }

/** Decides which engine a URL's *primary* pass should go through. Sites that only ever post
 * video skip gallery-dl entirely (it can't extract them, or extracts them poorly — see the
 * TikTok photo-vs-video investigation this was built from); everything else goes through
 * gallery-dl first, since that's the engine with real image/gallery support, and DownloadWorker
 * separately falls back to (or supplements with) yt-dlp per item as needed. */
object VideoSiteRouter {
    // Deliberately a fixed list rather than "try gallery-dl, see if it fails" for these — gallery-dl
    // either doesn't support them at all or (TikTok) has a real but weaker path than yt-dlp's, so
    // there's no value in paying for a doomed gallery-dl attempt first the way the fallback does
    // for genuinely unknown sites.
    private val videoOnlyHosts = setOf(
        "youtube.com", "youtu.be",
        "tiktok.com",
        "vimeo.com",
        "twitch.tv",
        "twitter.com", "x.com",
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

    fun classify(url: String): DownloadEngine {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?: return DownloadEngine.GALLERY_DL
            
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

    /** Whether [url] is an Instagram link — used to decide when a yt-dlp supplement pass is worth
     * attempting even without gallery-dl's own listing having flagged a video: unlike most
     * gallery-dl sites (image boards, art platforms) that never carry embedded video at all,
     * Instagram routinely does, and gallery-dl's listing can miss one it should have caught (see
     * DownloadWorker's own comment on this). */
    fun isInstagram(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        return host == "instagram.com" || host.endsWith(".instagram.com")
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
