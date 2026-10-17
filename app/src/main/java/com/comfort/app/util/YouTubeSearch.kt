package com.comfort.app.util

import android.content.Context
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** One video from a YouTube search. */
data class SearchResult(
    val id: String,
    val url: String,
    val title: String,
    val channel: String,
    val durationSeconds: Long?,
    val views: Long?,
    val thumbnail: String,
    val live: Boolean,
)

/** YouTube search through the app's yt-dlp (yt_dlp_wrapper.search): a flat listing of the search
 * page, a page of [PAGE_SIZE] results at a time. */
object YouTubeSearch {
    const val PAGE_SIZE = 20

    /** Page [page] (1-based) of the results for [query]; fails with the wrapper's message. */
    suspend fun search(context: Context, query: String, page: Int = 1): Result<List<SearchResult>> {
        val lines = mutableListOf<String>()
        // Signed in, YouTube shows results it hides from a signed-out search (some queries get
        // only an age check). The same cookies a download would send.
        val (cookiesArg, tempCookieFile) = GalleryDlListing.effectiveCookiesPath(context)
        val run = try {
            runCatching {
                PythonRuntime.run(
                    context, "yt_dlp_wrapper.py",
                    listOf("search", query, PAGE_SIZE.toString(), GalleryDlPreferences.getEffectiveProxyUrl(context), page.toString(), cookiesArg),
                ) { line -> lines.add(line) }
            }
        } finally {
            tempCookieFile?.delete()
        }
        run.exceptionOrNull()?.let { if (it is CancellationException) throw it; return Result.failure(it) }
        // The JSON is the last line printed; anything on stderr comes before it.
        val json = lines.lastOrNull { it.isNotBlank() }?.let { runCatching { JSONObject(it.trim()) }.getOrNull() }
            ?: return Result.failure(IllegalStateException(lines.lastOrNull { it.isNotBlank() } ?: "No results"))
        json.optString("error").takeIf { it.isNotBlank() }?.let { return Result.failure(IllegalStateException(it)) }
        val array = json.optJSONArray("results") ?: return Result.success(emptyList())
        return Result.success((0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            SearchResult(
                id = o.optString("id"),
                url = o.optString("url"),
                title = o.optString("title"),
                channel = o.optString("channel"),
                durationSeconds = if (o.isNull("duration")) null else o.optDouble("duration").toLong(),
                views = if (o.isNull("views")) null else o.optLong("views"),
                thumbnail = o.optString("thumbnail"),
                live = o.optBoolean("live"),
            )
        })
    }
}
