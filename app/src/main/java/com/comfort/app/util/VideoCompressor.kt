package com.comfort.app.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Makes a smaller copy of a saved video: re-encoded to H.264 at a lower resolution on the phone's
 * own hardware encoder (the bundled ffmpeg's h264_mediacodec — see FfmpegRuntime), AAC audio,
 * saved next to the original as "<name> (720p).mp4" and added to the Library as its own entry.
 * The original is never touched. */
object VideoCompressor {

    /** What the saved file is, read without decoding it. [shortSide] is the frame's smaller
     * dimension (the "720" in 720p) whichever way the video is turned. */
    data class Info(val width: Int, val height: Int, val shortSide: Int, val durationMs: Long, val bytes: Long)

    /** The sizes offered, as the short side of the frame. */
    val TARGETS = listOf(1080, 720, 480)

    // Video bitrate per target: what streaming sites use for H.264 at that size.
    private val KBPS = mapOf(1080 to 5000, 720 to 2500, 480 to 1200)

    fun info(context: Context, uri: Uri): Info? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val (dw, dh) = if (rotation % 180 != 0) h to w else w to h
            val bytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            Info(dw, dh, minOf(dw, dh), duration, bytes)
        } finally {
            r.release()
        }
    }.getOrNull()

    private const val AUDIO_KBPS = 96

    /** Video bitrate for [target]: the usual rate for that size, but never more than the source's
     * own scaled down by the drop in pixels — an already lean source (Instagram Reels run ~1 Mbps
     * at 720p) would otherwise come out *bigger* at 480p (seen: 2.8 MB in, ~4 MB estimated). */
    private fun videoKbps(info: Info, target: Int): Int {
        val table = KBPS.getValue(target)
        if (info.bytes <= 0 || info.durationMs <= 0) return table
        val sourceKbps = (info.bytes * 8 / info.durationMs).toInt() - AUDIO_KBPS
        val ratio = target.toFloat() / info.shortSide
        return minOf(table, (sourceKbps * ratio * ratio).toInt()).coerceAtLeast(300)
    }

    /** Rough size of the result, for the dialog — bitrate × length. */
    fun estimateBytes(info: Info, target: Int): Long =
        (videoKbps(info, target) + AUDIO_KBPS) * info.durationMs / 8

    /** Re-encodes [source] to [target] (short side, px) and adds it to the Library next to [item].
     * [onProgress] gets 0..1. Returns the new file's Uri, or null on failure; cancelling the
     * calling coroutine stops ffmpeg and leaves nothing behind. */
    suspend fun compress(
        context: Context,
        item: DownloadEntity,
        source: Uri,
        target: Int,
        onProgress: (Float) -> Unit,
    ): Uri? = withContext(Dispatchers.IO) {
        val ffmpeg = FfmpegRuntime.getExecutablePath(context) ?: return@withContext null
        val info = info(context, source) ?: return@withContext null
        val work = File(context.cacheDir, "compress").apply { deleteRecursively(); mkdirs() }
        try {
            val input = File(work, "in")
            context.contentResolver.openInputStream(source)?.use { inp -> input.outputStream().use { inp.copyTo(it) } }
                ?: return@withContext null
            coroutineContext.ensureActive()

            val name = (queryName(context, source) ?: item.title).substringBeforeLast('.')
                .replace(Regex("""\s*\(\d{3,4}p\)$"""), "")
            val output = File(work, "$name (${target}p).mp4")
            // Scale the short side to the target, then crop both sides to multiples of 16 — the
            // phone's H.264 encoder rejects other sizes ("Invalid argument": 1080-wide and 480x854
            // frames failed on a Galaxy S24). Frames are already upright here: ffmpeg applies the
            // rotation flag before filtering.
            val scale = if (info.width >= info.height) "scale=-2:$target" else "scale=$target:-2"
            val cmd = listOf(
                ffmpeg, "-hide_banner", "-nostats", "-y", "-i", input.absolutePath,
                "-map", "0:v:0", "-map", "0:a:0?",
                "-vf", "$scale,crop=trunc(iw/16)*16:trunc(ih/16)*16",
                "-c:v", "h264_mediacodec", "-b:v", "${videoKbps(info, target)}k", "-pix_fmt", "nv12",
                "-c:a", "aac", "-b:a", "${AUDIO_KBPS}k",
                "-movflags", "+faststart",
                "-progress", "pipe:1",
                output.absolutePath,
            )
            val process = ProcessBuilder(cmd).redirectErrorStream(false).apply {
                // Not the app's LD_LIBRARY_PATH: see yt_dlp_wrapper.py's _ffmpeg_env.
                environment().remove("LD_LIBRARY_PATH")
            }.start()
            val errors = StringBuilder()
            val errReader = Thread {
                runCatching { process.errorStream.bufferedReader().forEachLine { if (errors.length < 4000) errors.appendLine(it) } }
            }.apply { start() }
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        coroutineContext.ensureActive()
                        if (line.startsWith("out_time_us=") && info.durationMs > 0) {
                            val us = line.substringAfter('=').toLongOrNull() ?: continue
                            onProgress((us / 1000f / info.durationMs).coerceIn(0f, 1f))
                        }
                    }
                }
                val code = process.waitFor()
                errReader.join(2000)
                if (code != 0 || !output.isFile || output.length() == 0L) {
                    android.util.Log.w("VideoCompressor", "ffmpeg exited $code: ${errors.takeLast(1500)}")
                    return@withContext null
                }
            } finally {
                process.destroy()
            }
            coroutineContext.ensureActive()
            onProgress(1f)

            val saved = MediaStoreHelper.saveMediaToGallery(context, output) ?: return@withContext null
            val now = System.currentTimeMillis()
            AppDatabase.getDatabase(context).downloadDao().insert(
                DownloadEntity(
                    id = UUID.randomUUID().toString(),
                    url = item.url,
                    title = "${item.title.replace(Regex("""\s*\(\d{3,4}p\)$"""), "")} (${target}p)",
                    thumbnailPath = saved.toString(),
                    status = DownloadStatus.FINISHED,
                    progress = 1f,
                    downloadedItems = 1,
                    totalItems = 1,
                    speedMbs = 0f,
                    etaSeconds = 0,
                    errorMessage = null,
                    dateAdded = now,
                    downloadStartTime = now,
                    totalBytes = output.length(),
                    artist = item.artist,
                ),
            )
            saved
        } finally {
            work.deleteRecursively()
        }
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}
