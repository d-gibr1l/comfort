package com.comfort.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches the top 10 finished downloads with thumbnails so HomeScreen can pre-populate
 * "Recently downloaded" on Frame 1 of cold start without waiting for Room SQLite queries.
 * This completely eliminates content layout shift (CLS) and placeholder pop-in.
 */
object RecentDownloadsCache {
    private const val PREFS_NAME = "recent_downloads_cache"
    private const val KEY_ITEMS = "cached_recent_items"

    @Volatile
    private var memoryCache: List<DownloadEntity>? = null

    fun get(context: Context): List<DownloadEntity> {
        memoryCache?.let { return it }
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val list = ArrayList<DownloadEntity>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val statusStr = obj.optString("status", DownloadStatus.FINISHED.name)
                val status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrDefault(DownloadStatus.FINISHED)
                list += DownloadEntity(
                    id = obj.getString("id"),
                    url = obj.optString("url", ""),
                    title = obj.optString("title", ""),
                    thumbnailPath = obj.optString("thumbnailPath").takeIf { it.isNotEmpty() },
                    status = status,
                    progress = 1f,
                    downloadedItems = 1,
                    totalItems = 1,
                    speedMbs = 0f,
                    etaSeconds = 0,
                    errorMessage = null,
                    dateAdded = obj.optLong("dateAdded", 0L),
                    mediaUri = obj.optString("mediaUri").takeIf { it.isNotEmpty() },
                    isAudio = obj.optBoolean("isAudio", false),
                )
            }
            list
        }.getOrDefault(emptyList()).also { memoryCache = it }
    }

    fun save(context: Context, items: List<DownloadEntity>) {
        val top = items.take(10)
        if (top == memoryCache) return
        memoryCache = top
        val array = JSONArray()
        for (item in top) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("url", item.url)
            obj.put("title", item.title)
            item.thumbnailPath?.let { obj.put("thumbnailPath", it) }
            item.mediaUri?.let { obj.put("mediaUri", it) }
            obj.put("status", item.status.name)
            obj.put("isAudio", item.isAudio)
            obj.put("dateAdded", item.dateAdded)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
