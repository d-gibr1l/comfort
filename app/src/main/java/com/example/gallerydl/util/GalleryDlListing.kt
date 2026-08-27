package com.example.gallerydl.util

import android.content.Context
import com.chaquo.python.Python
import com.example.gallerydl.data.GalleryDlPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One file gallery-dl discovered while enumerating a URL, without downloading it. */
data class GalleryItem(val num: Int, val url: String, val filename: String?)

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
        val python = Python.getInstance()
        val wrapper = python.getModule("gallery_dl_wrapper")
        val cookiesPath = context.filesDir.resolve("cookies.txt")
        val cookiesArg = if (cookiesPath.exists()) cookiesPath.absolutePath else null
        val extraArgs = GalleryDlPreferences.getExtraArgs(context).ifBlank { null }

        val rawText = runCatching {
            PythonEngineLock.withLock {
                wrapper.callAttr("list_items", url, cookiesArg, extraArgs).toString()
            }
        }.onFailure {
            android.util.Log.w("GalleryDlListing", "list_items threw for $url", it)
        }.getOrNull() ?: return@withContext emptyList()

        if (rawText.startsWith("ERR:")) {
            android.util.Log.w("GalleryDlListing", "list_items returned no stdout for $url: $rawText")
        }

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

        parseItems(jsonText).also {
            if (it.isEmpty() && jsonText.isNotBlank()) {
                android.util.Log.w("GalleryDlListing", "parseItems() found nothing in a non-blank response for $url (len=${jsonText.length}): ${jsonText.take(300)}")
            }
        }
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
                items.add(GalleryItem(num, fileUrl, filename))
            }
            items
        }.getOrElse { emptyList() }
    }
}
