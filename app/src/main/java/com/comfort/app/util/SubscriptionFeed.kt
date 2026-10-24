package com.comfort.app.util

import android.content.Context
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One upload of a channel or playlist, as yt_dlp_wrapper's `latest` lists it. */
data class FeedEntry(
    val id: String,
    val url: String,
    val title: String?,
    /** "YYYYMMDD". */
    val uploadDate: String?,
    val durationSeconds: Long?,
    val thumbnail: String?,
)

/** A channel's or playlist's newest uploads, newest first. */
data class Feed(
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val webpageUrl: String,
    val entries: List<FeedEntry>,
)

/** A subscription's check: the newest uploads of a channel or playlist through the app's yt-dlp
 * (yt_dlp_wrapper.py's `latest` command), called the way [YouTubeSearch] calls `search`. */
object SubscriptionFeed {
    // kotlinx.serialization rather than org.json, so [parse] runs in plain JVM unit tests (the
    // Android org.json there is a stub).
    private val json = Json { ignoreUnknownKeys = true }

    /** The newest [limit] uploads of [url]; fails with the wrapper's message. */
    suspend fun fetch(context: Context, url: String, limit: Int): Result<Feed> {
        val lines = mutableListOf<String>()
        // The same cookies a download would send: a members-only or age-gated upload is listed
        // only when signed in.
        val (cookiesArg, tempCookieFile) = GalleryDlListing.effectiveCookiesPath(context)
        val run = try {
            runCatching {
                PythonRuntime.run(
                    context, "yt_dlp_wrapper.py",
                    listOf("latest", url, limit.toString(), GalleryDlPreferences.getEffectiveProxyUrl(context), cookiesArg),
                ) { line -> lines.add(line) }
            }
        } finally {
            tempCookieFile?.delete()
        }
        run.exceptionOrNull()?.let { if (it is CancellationException) throw it; return Result.failure(it) }
        return parse(lines)
    }

    /** The wrapper's output: its last non-blank line is the JSON (anything on stderr comes first).
     * Entries without an id fall back to their url, and ones with neither are dropped — the id is
     * what a subscription remembers an upload by. */
    fun parse(lines: List<String>): Result<Feed> {
        val last = lines.lastOrNull { it.isNotBlank() }?.trim()
            ?: return Result.failure(IllegalStateException("No output from yt-dlp"))
        val obj = runCatching { json.parseToJsonElement(last) as? JsonObject }.getOrNull()
            ?: return Result.failure(IllegalStateException(last))
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        obj.str("error")?.let { return Result.failure(IllegalStateException(it)) }
        val entries = (obj["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
            val e = element as? JsonObject ?: return@mapNotNull null
            val entryUrl = e.str("url")
            val id = e.str("id") ?: entryUrl ?: return@mapNotNull null
            FeedEntry(
                id = id,
                // Subscriptions are YouTube-only, so a bare id is a video id.
                url = entryUrl ?: "https://www.youtube.com/watch?v=$id",
                title = e.str("title"),
                uploadDate = e.str("upload_date"),
                durationSeconds = (e["duration"] as? JsonPrimitive)?.doubleOrNull?.toLong(),
                thumbnail = e.str("thumbnail"),
            )
        }
        val webpageUrl = obj.str("webpage_url").orEmpty()
        return Result.success(
            Feed(
                title = obj.str("title") ?: obj.str("uploader") ?: webpageUrl,
                uploader = obj.str("uploader"),
                thumbnail = obj.str("thumbnail"),
                webpageUrl = webpageUrl,
                entries = entries,
            )
        )
    }
}
