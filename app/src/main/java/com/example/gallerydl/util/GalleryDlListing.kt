package com.example.gallerydl.util

import android.content.Context
import com.example.gallerydl.data.GalleryDlPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One file gallery-dl discovered while enumerating a URL, without downloading it. */
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

    /** Enumerates the items behind [url] via gallery-dl's simulate+dump-json mode. Returns an
     * empty list if the source can't be listed this way (single-file links, parsing failures,
     * extractors that don't emit the expected shape) — callers should fall back to a normal
     * whole-gallery download in that case. */
    suspend fun listItems(context: Context, url: String): List<GalleryItem> = withContext(Dispatchers.IO) {
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
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()

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

        parseItems(jsonText)
    }

    private fun parseItems(jsonText: String): List<GalleryItem> {
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
}
