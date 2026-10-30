package com.comfort.app.data

/** What makes two links the same download, for "Prevent duplicate downloads": the link without
 * its scheme, "www."/"m."/"mobile.", fragment, trailing slash and share-tracking parameters.
 * Links were compared as typed, and a share token differs on every copy — X's copied links got
 * "?a=<token>&s=09" (seen in October 2026), so the same post was never a duplicate. */
object UrlKey {

    /** Parameters that only say who shared the link and how, never what it points to. */
    val TRACKING_PARAMS = setOf(
        "si", "igsh", "igshid", "fbclid", "gclid", "feature", "pp", "share_id", "share_app_id",
        "is_from_webapp", "sender_device", "web_id", "ref", "ref_src", "s", "t", "xmt", "mibextid", "rdt",
        // Instagram's per-share tokens (seen in the Library's own links, 2026), and its carousel
        // position: the post is the same whichever picture the link opened on.
        "stkn", "vrfl", "rpxt", "img_index",
    )

    private val X_HOSTS = setOf("x.com", "twitter.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com")
    private val X_STATUS = Regex("""^/(?:[^/]+/)?(?:i/web/)?status(?:es)?/(\d+)""")
    private val YT_HOSTS = setOf("youtube.com", "youtu.be")

    /** Sites whose path names the post on its own, the whole query being share data (TikTok's
     * copied links carry twenty-odd parameters): (host, path pattern, key prefix). */
    private val PATH_IDS = listOf(
        Triple("instagram.com", Regex("""^/(?:[^/]+/)?(?:p|reel|reels|tv)/([\w-]+)"""), "instagram.com/p/"),
        Triple("tiktok.com", Regex("""^/(?:@[^/]+/)?(?:video|photo)/(\d+)"""), "tiktok.com/video/"),
        Triple("reddit.com", Regex("""^/(?:r/[^/]+/)?comments/(\w+)"""), "reddit.com/comments/"),
        Triple("open.spotify.com", Regex("""^/(?:intl-[\w-]+/)?(track|album|playlist|episode|show|artist)/(\w+)"""), "open.spotify.com/"),
    )
    private val YT_PATH_ID = Regex("""^/(?:shorts|live|embed)/([\w-]{11})""")

    fun isTracking(param: String): Boolean = param in TRACKING_PARAMS || param.startsWith("utm_")

    /** [url]'s key: equal keys are the same thing to download. */
    fun of(url: String): String {
        val noFragment = url.trim().substringBefore('#')
        val afterScheme = noFragment.substringAfter("://", noFragment)
        val hostPart = afterScheme.substringBefore('/').substringBefore('?').lowercase()
        var host = hostPart.substringAfter('@').substringBefore(':')
        for (prefix in listOf("www.", "mobile.", "m.")) host = host.removePrefix(prefix)
        val rest = afterScheme.substring(hostPart.length)
        val path = rest.substringBefore('?').trimEnd('/')
        if (host in X_HOSTS) {
            // A post is its number: /user/status/N, /i/status/N and /i/web/status/N are all it,
            // and nothing in the query changes which post it is.
            X_STATUS.find(path)?.let { return "x.com/status/${it.groupValues[1]}" }
            host = "x.com"
        }
        for ((site, pattern, prefix) in PATH_IDS) {
            if (host != site && !host.endsWith(".$site")) continue
            val m = pattern.find(path) ?: break
            return prefix + m.groupValues.drop(1).joinToString("/")
        }
        if (host in YT_HOSTS) {
            // One video, whichever way it was shared: youtu.be/ID, /shorts/ID and /watch?v=ID.
            // (YouTube Music stays its own: there it's downloaded as a song.)
            val id = if (host == "youtu.be") path.removePrefix("/").substringBefore('/')
            else YT_PATH_ID.find(path)?.groupValues?.get(1)
            if (!id.isNullOrEmpty() && path != "/watch") {
                val others = rest.substringAfter('?', "").split('&')
                    .filter { it.isNotBlank() && !isTracking(it.substringBefore('=').lowercase()) && it.substringBefore('=') != "v" }
                return "youtube.com/watch?" + (others + "v=$id").sorted().joinToString("&")
            }
        }
        val kept = rest.substringAfter('?', "").split('&').filter { param ->
            param.isNotBlank() && !isTracking(param.substringBefore('=').lowercase())
        }.sorted()
        return host + path + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    /** A LIKE pattern every link with [url]'s key matches (and others too, checked with [of]):
     * the ID every way of writing the link contains (its key's last path part). */
    fun likePattern(url: String): String {
        val key = of(url)
        // A YouTube video by its ID, which every way of sharing it contains.
        val path = Regex("""(?:^|&|\?)v=([\w-]{11})""").find(key)?.groupValues?.get(1)
            ?.takeIf { key.startsWith("youtube.com/watch") }
            ?: key.substringBefore('?').substringAfter('/', "").substringAfterLast('/')
        val escaped = path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return if (escaped.isEmpty()) "%" else "%$escaped%"
    }
}
