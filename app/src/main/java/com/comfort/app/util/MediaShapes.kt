package com.comfort.app.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** A saved file's shape and length, for the Library: [aspect] is width/height as displayed
 * (rotation applied), [shortSide] the smaller dimension in pixels (a video's "720p"/"1080p",
 * whatever its orientation), [durationMs] only for video/audio. */
data class MediaShape(val aspect: Float, val durationMs: Long?, val shortSide: Int? = null)

/** Reads [MediaShape]s from MediaStore — every finished download's thumbnailPath is its saved
 * file's MediaStore URI, and MediaStore already knows each file's size and duration, so nothing
 * has to be decoded or stored in the app's own database. */
object MediaShapes {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, MediaShape>()
    private val NOT_FOUND = MediaShape(-1f, null, null)

    /** Null when [uri] isn't a readable MediaStore row (a remote thumbnail, a deleted file). Cached
     * per URI: a saved file never changes shape. Blocking — call off the main thread. */
    fun of(context: Context, uri: String): MediaShape? {
        val cached = cache[uri]
        if (cached != null) return if (cached === NOT_FOUND) null else cached
        if (!uri.startsWith("content://")) return null
        val shape = query(context, Uri.parse(uri))
        cache[uri] = shape ?: NOT_FOUND
        return shape
    }

    /** Pre-fetches and caches [MediaShape]s for multiple URIs in a single batch MediaStore query,
     * avoiding hundreds of individual ContentResolver queries. */
    fun warm(context: Context, uris: Collection<String>) {
        val uncached = uris.filter { it.startsWith("content://") && !cache.containsKey(it) }
        if (uncached.isEmpty()) return

        val byId = mutableMapOf<Long, MutableList<String>>()
        for (uriStr in uncached) {
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
            if (uri?.authority == MediaStore.AUTHORITY) {
                uri.lastPathSegment?.toLongOrNull()?.let { id ->
                    byId.getOrPut(id) { mutableListOf() }.add(uriStr)
                }
            }
        }
        if (byId.isEmpty()) return

        val projections = listOf(
            arrayOf(MediaStore.MediaColumns._ID, "width", "height", "orientation", "duration"),
            arrayOf(MediaStore.MediaColumns._ID, "width", "height", "duration"),
            arrayOf(MediaStore.MediaColumns._ID, "width", "height"),
        )

        byId.keys.chunked(500).forEach { ids ->
            for (projection in projections) {
                val success = runCatching {
                    context.contentResolver.query(
                        MediaStore.Files.getContentUri("external"),
                        projection,
                        "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")})",
                        null,
                        null,
                    )?.use { c ->
                        val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                        val wIdx = c.getColumnIndex("width")
                        val hIdx = c.getColumnIndex("height")
                        val oIdx = c.getColumnIndex("orientation")
                        val dIdx = c.getColumnIndex("duration")

                        while (c.moveToNext()) {
                            val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                            var w = if (wIdx >= 0 && !c.isNull(wIdx)) c.getLong(wIdx) else 0L
                            var h = if (hIdx >= 0 && !c.isNull(hIdx)) c.getLong(hIdx) else 0L
                            val orient = if (oIdx >= 0 && !c.isNull(oIdx)) c.getLong(oIdx) else 0L
                            if (orient % 180L == 90L) w = h.also { h = w }
                            val dur = if (dIdx >= 0 && !c.isNull(dIdx)) c.getLong(dIdx).takeIf { it > 0 } else null

                            val shape = MediaShape(
                                aspect = if (w > 0 && h > 0) w.toFloat() / h else 1f,
                                durationMs = dur,
                                shortSide = minOf(w, h).takeIf { it > 0 }?.toInt(),
                            )
                            byId[id]?.forEach { uriStr ->
                                cache[uriStr] = shape
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
                if (success) break
            }
        }
    }

    private fun query(context: Context, uri: Uri): MediaShape? {
        // "orientation" is only a column on some tables/versions; a projection naming a missing
        // column throws, so fall back to the plainer projection.
        for (projection in listOf(
            arrayOf("width", "height", "orientation", "duration"),
            arrayOf("width", "height", "duration"),
            arrayOf("width", "height"),
        )) {
            val shape = runCatching {
                context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    fun long(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                    var w = long("width") ?: 0L
                    var h = long("height") ?: 0L
                    if ((long("orientation") ?: 0L) % 180L == 90L) w = h.also { h = w }
                    MediaShape(
                        aspect = if (w > 0 && h > 0) w.toFloat() / h else 1f,
                        durationMs = long("duration")?.takeIf { it > 0 },
                        shortSide = minOf(w, h).takeIf { it > 0 }?.toInt(),
                    )
                }
            }.getOrNull()
            if (shape != null) return shape
        }
        return null
    }
}
