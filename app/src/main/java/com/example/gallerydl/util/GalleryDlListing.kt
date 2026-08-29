package com.example.gallerydl.util

import android.content.Context
import com.example.gallerydl.data.DownloadEngine
import com.example.gallerydl.data.GalleryDlPreferences
import com.example.gallerydl.data.VideoSiteRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One file discovered while enumerating a URL, without downloading it. */
data class GalleryItem(val num: Int, val url: String, val filename: String?, val title: String?)

object GalleryDlListing {
    // gallery-dl's own Message.Url constant — stable across extractors, see gallery_dl/job.py.
    private const val MESSAGE_URL = 3
    // Exposed so callers can tell a genuinely small gallery apart from one that got truncated —
    // a result exactly at this size might just be the cap kicking in, not the real total.
    const val MAX_ITEMS = 200
    // Matches gallery_dl_wrapper.py's list_items() — everything after this marker is a warning
    // gallery-dl logged while still successfully listing everything it *could* find (e.g. it
    // silently drops an individual carousel item it couldn't fetch media info for — a real file
    // going missing from the result with no exception raised to catch). Not shown to the user,
    // just logged, so a dropped item at least leaves a trace instead of vanishing without one.
    private const val WARNINGS_MARKER = "\n---GALLERY_DL_WARNINGS---\n"

    /** Enumerates the items behind [url] for the share-sheet picker. Returns an empty list if the
     * source can't be listed at all — callers should fall back to a normal whole-gallery download
     * in that case. */
    suspend fun listItems(context: Context, url: String): List<GalleryItem> = withContext(Dispatchers.IO) {
        when (VideoSiteRouter.classify(url)) {
            // Video-only sources (Reels, TikTok, YouTube, ...) skip gallery-dl's listing entirely,
            // same as the real download does — gallery-dl either can't parse them at all, or
            // (Instagram Reels specifically) lists them fine but only with an internal
            // "ytdl:"-prefixed pseudo-URL as the item's own "url", which isn't a real fetchable
            // preview image (see yt_dlp_wrapper.py's list_info() doc comment). yt-dlp's own
            // extractor already resolves a real thumbnail as part of normal metadata extraction.
            DownloadEngine.YT_DLP -> listViaYtDlp(context, url)
            DownloadEngine.GALLERY_DL -> {
                val items = listViaGalleryDl(context, url)
                // Only worth the extra process + network round trip when there's actually a video
                // item to fix a thumbnail for — most gallery-dl sources are image-only galleries
                // and never hit this at all.
                if (items.any { it.filename?.let(VideoSiteRouter::isVideoFilename) == true }) {
                    enrichVideoThumbnails(context, url, items)
                } else {
                    items
                }
            }
        }
    }

    private suspend fun listViaGalleryDl(context: Context, url: String): List<GalleryItem> {
        val cookiesPath = context.filesDir.resolve("cookies.txt")
        val cookiesArg = if (cookiesPath.exists()) cookiesPath.absolutePath else ""
        val extraArgs = GalleryDlPreferences.getExtraArgs(context)

        // list_items() only ever prints once (see gallery_dl_wrapper.py's __main__), but that one
        // print can itself contain embedded newlines (the JSON text, plus the warnings marker) —
        // PythonRuntime.run() delivers it back one line at a time, so it has to be rejoined into
        // the single block of text list_items() originally returned before parsing it as JSON.
        val lines = mutableListOf<String>()
        val rawText = runCatching {
            PythonRuntime.run(context, "gallery_dl_wrapper.py", listOf("list_items", url, cookiesArg, extraArgs)) { line ->
                lines.add(line)
            }
            lines.joinToString("\n")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()

        val markerIndex = rawText.indexOf(WARNINGS_MARKER)
        val jsonText: String
        if (markerIndex >= 0) {
            jsonText = rawText.substring(0, markerIndex)
            val warnings = rawText.substring(markerIndex + WARNINGS_MARKER.length).trim()
            if (warnings.isNotBlank()) {
                android.util.Log.w("GalleryDlListing", "gallery-dl reported warnings while listing $url:\n$warnings")
            }
        } else {
            jsonText = rawText
        }

        return parseGalleryDlItems(jsonText)
    }

    private fun parseGalleryDlItems(jsonText: String): List<GalleryItem> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList()

        return runCatching {
            val root = JSONArray(trimmed)
            val items = mutableListOf<GalleryItem>()
            for (i in 0 until root.length()) {
                if (items.size >= MAX_ITEMS) break
                val entry = root.optJSONArray(i) ?: continue
                if (entry.length() < 2 || entry.optInt(0, -1) != MESSAGE_URL) continue
                val fileUrl = entry.optString(1, "").ifBlank { null } ?: continue
                val keywords = entry.optJSONObject(2)
                val num = keywords?.optInt("num", items.size + 1) ?: (items.size + 1)
                // gallery-dl reports these as two separate keywords — "filename" never has a
                // dot-extension suffix on its own — so a bare filename check (isVideoFilename)
                // always misses every item unless the extension is folded back in here.
                val rawFilename = keywords?.optString("filename")?.takeIf { it.isNotBlank() }
                val extension = keywords?.optString("extension")?.takeIf { it.isNotBlank() }
                val filename = when {
                    rawFilename == null -> null
                    extension == null || rawFilename.endsWith(".$extension", ignoreCase = true) -> rawFilename
                    else -> "$rawFilename.$extension"
                }
                // Which keyword actually holds a human-readable title varies a lot by extractor
                // (Reddit posts use "title", Instagram/Twitter only have a caption-style
                // "description"/"content") — tried in priority order, first non-blank wins. Kept
                // short: this renders as a caption under a picker thumbnail, not a headline.
                val title = listOf("title", "content", "description")
                    .firstNotNullOfOrNull { key -> keywords?.optString(key)?.trim()?.takeIf { it.isNotBlank() } }
                    ?.let { if (it.length > 120) it.take(120).trimEnd() + "…" else it }
                items.add(GalleryItem(num, fileUrl, filename, title))
            }
            items
        }.getOrElse { emptyList() }
    }

    /** Runs yt-dlp's own metadata-only extraction on [url] directly — used for sources
     * VideoSiteRouter already routes entirely to yt-dlp for the real download too, so this listing
     * matches what actually gets fetched. Almost always a single item; some of these hosts
     * (YouTube playlists, Twitter threads) can still return several. */
    private suspend fun listViaYtDlp(context: Context, url: String): List<GalleryItem> {
        val info = runYtDlpListInfo(context, url) ?: return emptyList()
        val entries = info.optJSONArray("entries")
        if (entries != null) {
            val items = mutableListOf<GalleryItem>()
            for (i in 0 until entries.length()) {
                if (items.size >= MAX_ITEMS) break
                val entry = entries.optJSONObject(i) ?: continue
                items.add(entryToGalleryItem(entry, items.size + 1))
            }
            return items
        }
        if (info.has("error")) return emptyList()
        return listOf(entryToGalleryItem(info, 1))
    }

    private fun entryToGalleryItem(entry: JSONObject, num: Int): GalleryItem {
        val thumbnail = entry.optString("thumbnail", "").ifBlank { null } ?: ""
        val title = entry.optString("title", "").trim().ifBlank { null }
            ?.let { if (it.length > 120) it.take(120).trimEnd() + "…" else it }
        // Synthetic filename purely so the existing isVideoFilename() extension check (shared with
        // the gallery-dl path below, and with SharePickerScreen's own video/quality-picker gating)
        // recognizes this as a video — the real download never uses this filename, only the
        // picker's video detection does.
        return GalleryItem(num, thumbnail, "$num.mp4", title)
    }

    /** For a gallery-dl-sourced carousel that contains a video item: fetches yt-dlp's own listing
     * of the *same* post URL and swaps in its real per-item thumbnail for gallery-dl's video items,
     * which otherwise carry gallery-dl's own internal "ytdl:" pseudo-URL as their "url" (renders
     * blank when Coil tries to load it — see yt_dlp_wrapper.py's list_info() doc comment for the
     * full story). The two engines don't share an item-numbering scheme, so items are correlated
     * purely by *order among the video items themselves* — both engines enumerate the same post
     * top-to-bottom, so the Nth video gallery-dl found should be the Nth video yt-dlp found too. A
     * mismatch (yt-dlp failing entirely, or the counts not lining up) just leaves the original
     * items untouched rather than guessing wrong. */
    private suspend fun enrichVideoThumbnails(context: Context, url: String, items: List<GalleryItem>): List<GalleryItem> {
        val info = runYtDlpListInfo(context, url) ?: return items
        val ytEntries = info.optJSONArray("entries")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } ?: listOfNotNull(info.takeIf { !it.has("error") })
        val ytThumbnails = ytEntries.mapNotNull { entry -> entry.optString("thumbnail", "").ifBlank { null } }
        if (ytThumbnails.isEmpty()) return items

        var videoIndex = 0
        return items.map { item ->
            val isVideo = item.filename?.let(VideoSiteRouter::isVideoFilename) == true
            if (!isVideo) return@map item
            val thumbnail = ytThumbnails.getOrNull(videoIndex)
            videoIndex++
            if (thumbnail != null) item.copy(url = thumbnail) else item
        }
    }

    private suspend fun runYtDlpListInfo(context: Context, url: String): JSONObject? {
        val cookiesPath = context.filesDir.resolve("cookies.txt")
        val cookiesArg = if (cookiesPath.exists()) cookiesPath.absolutePath else ""
        val extraArgs = GalleryDlPreferences.getExtraArgs(context)
        // Same JS-challenge runtime the real download() call gets — without it, extraction on
        // sites that require solving one (Instagram, YouTube, ...) fails outright rather than
        // just returning fewer fields, which was silently sending every one of these listings
        // straight to the UNAVAILABLE/instant-whole-download fallback (reproduced live: the
        // picker sheet flashed and closed in under a second instead of showing anything).
        val jsRuntimeArg = QuickJsRuntime.getExecutablePath(context).orEmpty()

        // list_info() only ever prints once (a single line — json.dumps() escapes any embedded
        // newlines within the JSON strings themselves), but joined the same defensive way as
        // listViaGalleryDl() above in case that ever isn't true for some entry's data.
        val lines = mutableListOf<String>()
        val rawText = runCatching {
            PythonRuntime.run(context, "yt_dlp_wrapper.py", listOf("list", url, cookiesArg, extraArgs, jsRuntimeArg)) { line ->
                lines.add(line)
            }
            lines.joinToString("\n")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return runCatching { JSONObject(rawText.trim()) }.getOrNull()
    }
}
