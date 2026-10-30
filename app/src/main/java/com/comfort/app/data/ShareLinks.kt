package com.comfort.app.data

import android.content.Context

/** Reddit's share links (reddit.com/r/<sub>/s/<code>) for "Prevent duplicate downloads": the code
 * is new on every share and says nothing about the post, which is only known by following the
 * link — so two shares of one post never matched by [UrlKey]. Each share link's post is looked up
 * once (EngineProbe.resolveRedirect, headers only) and kept, link → the post's UrlKey, so a share
 * matches another share of the same post and the post's own link. */
object ShareLinks {
    private val REDDIT_SHARE = Regex("""^https?://([\w-]+\.)?reddit\.com/(r|u|user)/[^/]+/s/\w+""", RegexOption.IGNORE_CASE)

    /** A LIKE pattern for every saved Reddit share link (DownloadDao.findActiveOrFinishedLike). */
    const val LIKE_PATTERN = "%reddit.com/%/s/%"

    /** The UrlKey every Reddit post's starts with. */
    const val REDDIT_POST = "reddit.com/comments/"

    private const val PREFS = "share_link_posts"

    /** Kept lookups at most; past that they're dropped and looked up again when needed. */
    private const val MAX_KEPT = 500

    fun isShareLink(url: String): Boolean = REDDIT_SHARE.containsMatchIn(url.trim())

    /** Share links whose lookup failed just now, by UrlKey → when. Not looked up again for
     * [RETRY_AFTER_MS]: every duplicate check goes through every saved share link, and offline each
     * lookup waited out its timeout (5 s) — the preview sheet's button waited for all of them. */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RETRY_AFTER_MS = 10 * 60_000L

    /** Whether [url]'s lookup failed within the last [RETRY_AFTER_MS]. */
    fun failedRecently(url: String): Boolean =
        failedAt[UrlKey.of(url)]?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true

    /** The UrlKey of the post share link [url] leads to. Null when it can't be followed right now
     * (offline, refused, the proxy can't be used), failed within [RETRY_AFTER_MS], or doesn't lead
     * to a post: then it's only the same as itself, as before. [lookUp] false answers only from
     * what's kept, without asking Reddit. */
    suspend fun postKey(context: Context, url: String, lookUp: Boolean = true): String? {
        val shareKey = UrlKey.of(url)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(shareKey, null)?.let { return it }
        if (!lookUp || failedRecently(url)) return null
        val key = com.comfort.app.util.EngineProbe.resolveRedirect(context, url.trim())
            ?.let { UrlKey.of(it) }?.takeIf { it.startsWith(REDDIT_POST) }
        if (key == null) {
            failedAt[shareKey] = System.currentTimeMillis()
            return null
        }
        failedAt.remove(shareKey)
        prefs.edit().apply {
            if (prefs.all.size >= MAX_KEPT) clear()
            putString(shareKey, key)
        }.apply()
        return key
    }
}
