package com.comfort.app.util

import android.content.Context
import android.net.Uri
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * "Record while it plays" in the in-app browser, for players that build their video from pieces
 * through MediaSource (a blob: source) and leave no link to download — the approach of cat-catch's
 * catch.js. MediaSniffer's page script copies every piece the page appends to each of its
 * SourceBuffers ([append]); each buffer is one track (video, audio, or both together) written to
 * its own file in arrival order: its initialization segment and then its media segments, which
 * together are a valid fragmented MP4 or WebM. [finish] puts the tracks into one file with ffmpeg
 * (a stream copy, nothing re-encoded) and saves it like a download.
 *
 * A recording only gets what the player actually loads, so the video has to play through; the
 * page is reloaded when recording starts so the first pieces (which carry the initialization) are
 * caught too. A player switching quality mid-way appends a second initialization segment, which
 * ffmpeg may not follow — stay on one quality while recording. DRM-protected video records as
 * encrypted data that won't play (MediaSniffer.protected says when a page asked for DRM).
 */
class MseRecorder(private val workDir: File) {
    data class State(val recording: Boolean = false, val bytes: Long = 0, val ended: Boolean = false)

    private class Track(val mime: String, val file: File, val out: FileOutputStream, var bytes: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile var isActive = false
        private set

    private val tracks = LinkedHashMap<String, Track>()

    fun start() {
        cancel()
        workDir.deleteRecursively()
        workDir.mkdirs()
        isActive = true
        _state.value = State(recording = true)
    }

    /** One appended piece of the SourceBuffer [id] (on WebView's JavaBridge thread). */
    fun append(id: String, mime: String, data: ByteArray) {
        if (!isActive || data.isEmpty()) return
        synchronized(tracks) {
            val track = tracks.getOrPut(id) {
                val file = File(workDir, "track${tracks.size}")
                Track(mime, file, FileOutputStream(file, true))
            }
            runCatching { track.out.write(data) }.onSuccess { track.bytes += data.size }
            _state.value = _state.value.copy(bytes = tracks.values.sumOf { it.bytes })
        }
    }

    fun markEnded() {
        if (isActive) _state.value = _state.value.copy(ended = true)
    }

    fun cancel() {
        isActive = false
        synchronized(tracks) {
            tracks.values.forEach { runCatching { it.out.close() } }
            tracks.clear()
        }
        workDir.deleteRecursively()
        _state.value = State()
    }

    /** Merges what was recorded into one file, saves it to the gallery and adds it to the
     * Library as [title] from [pageUrl]. Null when nothing usable was recorded or ffmpeg failed. */
    suspend fun finish(context: Context, title: String, pageUrl: String): Uri? = withContext(Dispatchers.IO) {
        isActive = false
        val recorded = synchronized(tracks) {
            tracks.values.forEach { runCatching { it.out.close() } }
            tracks.values.filter { it.bytes > 0 }.toList().also { tracks.clear() }
        }
        try {
            val video = recorded.filter { it.mime.startsWith("video/") }.maxByOrNull { it.bytes }
            val audio = recorded.filter { it.mime.startsWith("audio/") }.maxByOrNull { it.bytes }
            if (video == null && audio == null) return@withContext null
            val ffmpeg = FfmpegRuntime.getExecutablePath(context) ?: return@withContext null
            val webm = listOfNotNull(video, audio).any { "webm" in it.mime }
            val ext = when {
                video == null -> if (webm) "opus" else "m4a"
                webm -> "mkv"
                else -> "mp4"
            }
            val site = runCatching { java.net.URI(pageUrl).host }.getOrNull()?.removePrefix("www.").orEmpty()
            val name = listOf(site, title.trim()).filter { it.isNotBlank() }.joinToString(" - ")
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(150).ifBlank { "Recording" }
            val output = File(workDir, "$name.$ext")
            val inputs = listOfNotNull(video, audio)
            val cmd = buildList {
                add(ffmpeg); add("-hide_banner"); add("-y")
                inputs.forEach { add("-i"); add(it.file.absolutePath) }
                if (video != null && audio != null) {
                    addAll(listOf("-map", "0:v:0", "-map", "1:a:0"))
                } else {
                    addAll(listOf("-map", "0"))
                }
                addAll(listOf("-c", "copy"))
                if (ext == "mp4" || ext == "m4a") addAll(listOf("-movflags", "+faststart"))
                add(output.absolutePath)
            }
            val process = ProcessBuilder(cmd).redirectErrorStream(true).apply {
                // Not the app's LD_LIBRARY_PATH: see yt_dlp_wrapper.py's _ffmpeg_env.
                environment().remove("LD_LIBRARY_PATH")
            }.start()
            val log = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0 || !output.isFile || output.length() == 0L) {
                android.util.Log.w("MseRecorder", "ffmpeg failed: ${log.takeLast(1500)}")
                return@withContext null
            }
            val saved = MediaStoreHelper.saveMediaToGallery(context, output, forceAudioMime = video == null)
                ?: return@withContext null
            val now = System.currentTimeMillis()
            AppDatabase.getDatabase(context).downloadDao().insert(
                DownloadEntity(
                    id = UUID.randomUUID().toString(),
                    url = pageUrl,
                    title = name,
                    // A song's thumbnailPath is its cover art (none here) and mediaUri the file;
                    // a video's thumbnailPath is the file itself.
                    thumbnailPath = if (video == null) null else saved.toString(),
                    mediaUri = if (video == null) saved.toString() else null,
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
                    isAudio = video == null,
                ),
            )
            saved
        } finally {
            workDir.deleteRecursively()
            _state.value = State()
        }
    }
}
