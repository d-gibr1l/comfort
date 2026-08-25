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
    private const val MAX_ITEMS = 200

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

        val jsonText = runCatching {
            wrapper.callAttr("list_items", url, cookiesArg, extraArgs).toString()
        }.getOrNull() ?: return@withContext emptyList()

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
                val filename = keywords?.optString("filename")?.takeIf { it.isNotBlank() }
                items.add(GalleryItem(num, fileUrl, filename))
            }
            items
        }.getOrElse { emptyList() }
    }
}
