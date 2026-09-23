package com.comfort.app.data

import android.content.Context
import java.net.URI

enum class DownloadEngine { GALLERY_DL, YT_DLP, SPOTIFY, INSTALOADER }

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
        "xnxx.com", "xnxx.tv",
    )

    // Spotify itself never hosts downloadable audio (its streams are DRM'd) — this app's own
    // spotify_wrapper.py instead scrapes Spotify's public embed pages for real metadata (no API
    // credentials needed — see the plan this was built from) and searches/downloads the matching
    // track from YouTube via the already-bundled yt-dlp. Neither gallery-dl nor plain yt-dlp can
    // do anything with a Spotify URL at all, so this is checked unconditionally, same spirit as
    // videoOnlyHosts below but its own dedicated engine rather than reusing YT_DLP.
    private val spotifyHosts = setOf("open.spotify.com", "spotify.com", "spotify.link")

    // Extracted once and reused everywhere below (classify/isKnownSongHost/alwaysSupplementsVideo/
    // siteName each used to independently re-run this exact same runCatching{...} expression) —
    // a future fix to host normalization (stripping a trailing dot, handling IDN/punycode, ...)
    // now only needs to happen in one place instead of four, with no risk of one call site
    // silently being missed.
    private fun normalizedHost(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")

    // Same reasoning as normalizedHost above — this exact `any { host == it || host.endsWith(
    // ".$it") }` predicate was copy-pasted at every host-set check site.
    private fun Set<String>.matchesHost(host: String): Boolean = any { host == it || host.endsWith(".$it") }

    fun classify(url: String): DownloadEngine {
        val host = normalizedHost(url) ?: return DownloadEngine.GALLERY_DL

        if (spotifyHosts.matchesHost(host)) {
            return DownloadEngine.SPOTIFY
        }

        // Instagram reels are always videos and handle much better in yt-dlp immediately.
        // Checked against the URI's own path (lowercased), not a raw substring search over the
        // whole url — the raw-substring version missed differently-cased links (url itself was
        // never lowercased, unlike host above) and Instagram's plural "/reels/" path form (which
        // doesn't contain the literal substring "/reel/"), silently falling through to
        // gallery-dl's own unconfigured internal yt-dlp delegation for those reels instead.
        val path = runCatching { URI(url).path }.getOrNull()?.lowercase() ?: ""
        if ((host == "instagram.com" || host.endsWith(".instagram.com")) &&
            (path.contains("/reel/") || path.contains("/reels/"))
        ) {
            return DownloadEngine.YT_DLP
        }

        return if (videoOnlyHosts.matchesHost(host)) {
            DownloadEngine.YT_DLP
        } else {
            DownloadEngine.GALLERY_DL
        }
    }

    // A single post/reel/IGTV link, optionally under a username ("instagram.com/<user>/p/<code>").
    // Profiles, stories and highlights aren't matched — those stay on the classic path.
    private val instagramPostPath = Regex("^/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE)

    fun isInstagramPost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        if (host != "instagram.com" && !host.endsWith(".instagram.com")) return false
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        return instagramPostPath.containsMatchIn(path)
    }

    /** The engine a download (and its preview) actually starts with — [classify] plus the one
     * user setting that changes routing: "Use Instaloader for Instagram" (on by default) sends
     * single Instagram posts/reels to Instaloader. Tested side by side without cookies, gallery-dl
     * hit Instagram's login wall on every post while Instaloader fetched public ones anonymously
     * (all carousel images, reels, captions). DownloadWorker still falls back to [classify]'s
     * engine whenever Instaloader saves nothing, so turning it on never loses a download the
     * classic path could have made. */
    fun resolveEngine(context: Context, url: String): DownloadEngine =
        if (isInstagramPost(url) && GalleryDlPreferences.isInstaloaderForInstagram(context)) {
            DownloadEngine.INSTALOADER
        } else {
            classify(url)
        }

    // Presentation-only signal for the download preview sheet ("does this URL deserve the
    // song-styled card/track-list instead of the video-styled one?") — deliberately NOT consulted
    // by classify() above. music.youtube.com still routes to YT_DLP (via the plain "youtube.com"
    // entry in videoOnlyHosts already matching its subdomain) and soundcloud.com still routes to
    // GALLERY_DL (it has its own gallery-dl extractor) exactly as before this existed; this only
    // changes how the preview sheet renders, never which engine downloads the link.
    private val songHosts = setOf("music.youtube.com", "soundcloud.com")

    fun isKnownSongHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return songHosts.matchesHost(host)
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
        val host = normalizedHost(url) ?: return false
        return alwaysSupplementVideoHosts.matchesHost(host)
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
        val host = normalizedHost(url) ?: return "this link"
        val parts = host.split('.')
        val suffixLabels = if (parts.size >= 3 && "${parts[parts.size - 2]}.${parts.last()}" in twoLabelSuffixes) 2 else 1
        val label = parts.getOrNull(parts.size - 1 - suffixLabels) ?: parts.first()
        return label.replaceFirstChar { it.uppercase() }
    }
}
