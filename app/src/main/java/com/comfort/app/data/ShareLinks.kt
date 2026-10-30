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

    /** The UrlKey of the post share link [url] leads to. Null when it can't be followed right now
     * (offline, refused, the proxy can't be used) or doesn't lead to a post: then it's only the
     * same as itself, as before. */
    suspend fun postKey(context: Context, url: String): String? {
        val shareKey = UrlKey.of(url)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(shareKey, null)?.let { return it }
        val target = com.comfort.app.util.EngineProbe.resolveRedirect(context, url.trim()) ?: return null
        val key = UrlKey.of(target).takeIf { it.startsWith(REDDIT_POST) } ?: return null
        prefs.edit().apply {
            if (prefs.all.size >= MAX_KEPT) clear()
            putString(shareKey, key)
        }.apply()
        return key
    }
}
