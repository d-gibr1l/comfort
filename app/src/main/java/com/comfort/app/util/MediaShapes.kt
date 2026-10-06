package com.comfort.app.util

import android.content.Context
import android.net.Uri

/** A saved file's shape and length, for the Library: [aspect] is width/height as displayed
 * (rotation applied), [shortSide] the smaller dimension in pixels (a video's "720p"/"1080p",
 * whatever its orientation), [durationMs] only for video/audio. */
data class MediaShape(val aspect: Float, val durationMs: Long?, val shortSide: Int? = null)

/** Reads [MediaShape]s from MediaStore — every finished download's thumbnailPath is its saved
 * file's MediaStore URI, and MediaStore already knows each file's size and duration, so nothing
 * has to be decoded or stored in the app's own database. */
object MediaShapes {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, MediaShape>()

    /** Null when [uri] isn't a readable MediaStore row (a remote thumbnail, a deleted file). Cached
     * per URI: a saved file never changes shape. Blocking — call off the main thread. */
    fun of(context: Context, uri: String): MediaShape? {
        cache[uri]?.let { return it }
        if (!uri.startsWith("content://")) return null
        return query(context, Uri.parse(uri))?.also { cache[uri] = it }
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
