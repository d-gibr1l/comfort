package com.comfort.app.worker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One thing a Python engine run reported, as DownloadWorker acts on it. Produced by
 * [EngineEventParser] from each line the wrapper process prints. */
sealed interface EngineEvent {
    /** Expected size of the download (bytes) — yt-dlp's estimate can be fractional, hence Double upstream. */
    data class Size(val bytes: Long) : EngineEvent
    /** How many items this download will save (Instaloader, before its first file). */
    data class Total(val count: Int) : EngineEvent
    /** Which sub-file of a merge is downloading now. */
    data class Phase(val isAudio: Boolean) : EngineEvent
    /** Format tags of the sub-file now downloading ("720p|MP4"). */
    data class Format(val tags: String) : EngineEvent
    data class Progress(val downloadedBytes: Long, val speedBytesPerSecond: Float) : EngineEvent
    data class Thumbnail(val url: String) : EngineEvent
    data class Title(val title: String) : EngineEvent
    data class Artist(val artist: String) : EngineEvent
    data class Album(val album: String) : EngineEvent
    data class Track(val track: String) : EngineEvent
    /** A real error announcement worth showing the user (never a traceback continuation line). */
    data class Error(val message: String) : EngineEvent
    /** gallery-dl's "No results for ..." — an empty-handed outcome it logs at info level, not as an error. */
    data object NoResults : EngineEvent
    /** A progress note while listing ("Fetching info…") — the preview's loading text. */
    data class Status(val message: String) : EngineEvent
    /** A finished file the engine saved (an absolute path; DownloadWorker checks it's in staging). */
    data class File(val path: String) : EngineEvent
    /** Warnings, debug output, status lines, traceback noise: nothing to act on. */
    data object Ignored : EngineEvent
}

/** Turns a wrapper's output line into an [EngineEvent].
 *
 * The wrappers send their own reports as structured JSON lines: [MARK] (ASCII record separator)
 * followed by an object with a "type" field (see comfort_events.py). Everything else is plain text
 * the engines' own loggers print, which this still classifies the way DownloadWorker always did —
 * gallery-dl's "[extractor][error] ..." / "[extractor][info] No results for ..." lines, yt-dlp's
 * "[error] ERROR: ..." announcement (its traceback continuation lines are noise), and a bare file
 * path. The older bracketed text forms of the wrappers' own reports ("[size] 123", ...) are still
 * understood, so a run of not-yet-updated scripts behaves exactly as before. */
object EngineEventParser {
    const val MARK = '\u001e'

    private val json = Json { ignoreUnknownKeys = true }

    // gallery-dl/yt-dlp extractor-level log lines: "[instagram][error] HTTP redirect to login page".
    private val EXTRACTOR_ERROR_LINE = Regex("^\\[[\\w.]+\\]\\[error\\] ")
    // Same shape at gallery-dl's [info] level — it doesn't treat "found nothing" as an error.
    private val NO_RESULTS_LINE = Regex("^\\[[\\w.]+\\]\\[info\\] No results for ")
    private val PROGRESS_DOWNLOADED = Regex("downloaded=(\\d+)")
    private val PROGRESS_SPEED = Regex("speed=([\\d.]+)")

    fun parse(line: String): EngineEvent =
        if (line.startsWith(MARK)) parseStructured(line.substring(1)) else parseText(line)

    private fun parseStructured(payload: String): EngineEvent {
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return EngineEvent.Ignored
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun num(key: String) = (obj[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        return when (str("type")) {
            "size" -> num("bytes")?.takeIf { it > 0 }?.let { EngineEvent.Size(it) }
            "total" -> num("count")?.toInt()?.takeIf { it > 0 }?.let { EngineEvent.Total(it) }
            "phase" -> str("phase")?.let { EngineEvent.Phase(it == "audio") }
            "format" -> str("tags")?.let { EngineEvent.Format(it) }
            "progress" -> num("downloaded")?.let { downloaded ->
                val speed = (obj["speed"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f
                EngineEvent.Progress(downloaded, speed)
            }
            "thumbnail" -> str("url")?.let { EngineEvent.Thumbnail(it) }
            "title" -> str("title")?.let { EngineEvent.Title(it) }
            "artist" -> str("artist")?.let { EngineEvent.Artist(it) }
            "album" -> str("album")?.let { EngineEvent.Album(it) }
            "track" -> str("track")?.let { EngineEvent.Track(it) }
            // A wrapper's own error is always a deliberate announcement. One relayed from
            // yt-dlp's logger can carry its whole traceback, so only its headline is kept.
            // yt-dlp's logger also relays the traceback that follows an error as an error of its
            // own: no announcement in it, so it's noise like the text form's continuation lines.
            "error" -> str("message")?.takeUnless { it.startsWith("Traceback (most recent call last)") }?.let { EngineEvent.Error(headline(it)) }
            "file" -> str("path")?.let { EngineEvent.File(it) }
            "status" -> str("message")?.let { EngineEvent.Status(it) }
            else -> null // "warning", "debug", "exit", or a type from a newer script
        } ?: EngineEvent.Ignored
    }

    /** The line that says what went wrong: yt-dlp's "ERROR: ..." line when the message is a
     * multi-line traceback, otherwise the first line. */
    internal fun headline(message: String): String {
        val lines = message.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.firstOrNull { it.startsWith("ERROR:") } ?: lines.firstOrNull() ?: message.trim()
    }

    private fun parseText(line: String): EngineEvent {
        val text = line.trimEnd()
        fun after(prefix: String) = text.removePrefix(prefix).trim()
        return when {
            text.startsWith("[size] ") -> after("[size] ").toDoubleOrNull()?.toLong()?.let { EngineEvent.Size(it) }
            text.startsWith("[total] ") -> after("[total] ").toIntOrNull()?.takeIf { it > 0 }?.let { EngineEvent.Total(it) }
            text.startsWith("[phase] ") -> EngineEvent.Phase(after("[phase] ") == "audio")
            text.startsWith("[format] ") -> EngineEvent.Format(after("[format] "))
            text.startsWith("[progress] ") -> {
                val rest = after("[progress] ")
                PROGRESS_DOWNLOADED.find(rest)?.groupValues?.get(1)?.toLongOrNull()?.let { downloaded ->
                    val speed = PROGRESS_SPEED.find(rest)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    EngineEvent.Progress(downloaded, speed)
                }
            }
            text.startsWith("[thumbnail] ") -> after("[thumbnail] ").takeIf { it.isNotEmpty() }?.let { EngineEvent.Thumbnail(it) }
            text.startsWith("[title] ") -> after("[title] ").takeIf { it.isNotEmpty() }?.let { EngineEvent.Title(it) }
            text.startsWith("[artist] ") -> after("[artist] ").takeIf { it.isNotEmpty() }?.let { EngineEvent.Artist(it) }
            text.startsWith("[album] ") -> after("[album] ").takeIf { it.isNotEmpty() }?.let { EngineEvent.Album(it) }
            text.startsWith("[track] ") -> after("[track] ").takeIf { it.isNotEmpty() }?.let { EngineEvent.Track(it) }
            // yt-dlp's logger reprints every line of a traceback with this prefix; only the real
            // "ERROR: ..." announcement is an error, the "File ..." lines after it are noise.
            text.startsWith("[error] ") -> after("[error] ").takeIf { it.startsWith("ERROR:") }?.let { EngineEvent.Error(it) }
            EXTRACTOR_ERROR_LINE.containsMatchIn(text) -> EngineEvent.Error(text.substringAfter("[error] ").trim())
            // gallery_dl_wrapper.py's own fallback report of an exit it couldn't otherwise describe.
            text.startsWith("Error,") || text.startsWith("Exception:") -> EngineEvent.Error(text.trim())
            NO_RESULTS_LINE.containsMatchIn(text) -> EngineEvent.NoResults
            text.startsWith("[status] ") -> after("[status] ").takeIf { it.isNotEmpty() }?.let { EngineEvent.Status(it) }
            text.startsWith("[") -> null // [warning], [debug], [__status__], other logger lines
            text.trim().startsWith("/") -> EngineEvent.File(text.trim())
            else -> null
        } ?: EngineEvent.Ignored
    }
}
