package com.comfort.app.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** A song's lyrics: [lines] when they're synced (each with the time it's sung), else [plain]. */
data class Lyrics(val lines: List<Line>?, val plain: String?) {
    data class Line(val timeMs: Long, val text: String)

    val synced: Boolean get() = !lines.isNullOrEmpty()

    /** The line being sung at [positionMs]: the last one that has started, or -1 before the first. */
    fun lineAt(positionMs: Long): Int {
        val l = lines ?: return -1
        var lo = 0
        var hi = l.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (l[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    companion object {
        // [mm:ss], [mm:ss.x], [mm:ss.xx] or [mm:ss.xxx]; a line can carry several.
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        /** [text] as synced lyrics if its lines carry LRC time stamps, else as plain text. Null
         * when there's nothing to show. */
        fun parse(text: String): Lyrics? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val lines = mutableListOf<Line>()
            for (raw in trimmed.lines()) {
                val stamps = STAMP.findAll(raw).toList()
                if (stamps.isEmpty() || stamps.first().range.first != 0) continue
                val words = raw.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val (min, sec, frac) = m.destructured
                    val fracMs = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                    lines += Line(min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs, words)
                }
            }
            if (lines.size >= 2) return Lyrics(lines.sortedBy { it.timeMs }, null)
            // Not synced: the text as it is, without any stray [ar:..]-style header tags.
            val plain = trimmed.lines().filterNot { it.trim().matches(Regex("""\[[a-z]+:.*]""")) }.joinToString("\n").trim()
            return if (plain.isEmpty()) null else Lyrics(null, plain)
        }

        private val cache = object : LinkedHashMap<Uri, Lyrics?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Lyrics?>?) = size > 20
        }

        /** The lyrics saved in the song's own tags (Settings > Processing > Lyrics puts them there):
         * MP4 "©lyr", ID3 "USLT", or Vorbis "LYRICS". Null for a song without any. */
        suspend fun load(context: Context, uri: Uri): Lyrics? {
            synchronized(cache) { if (cache.containsKey(uri)) return cache[uri] }
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val groups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(uri)).get(10, TimeUnit.SECONDS)
                    (0 until groups.length).asSequence()
                        .flatMap { g -> (0 until groups[g].length).asSequence().map { groups[g].getFormat(it).metadata } }
                        .filterNotNull()
                        .firstNotNullOfOrNull { lyricsIn(it) }
                }.getOrNull()?.let { parse(it) }
            }
            synchronized(cache) { cache[uri] = found }
            return found
        }

        private fun lyricsIn(metadata: Metadata): String? {
            for (i in 0 until metadata.length()) {
                when (val entry = metadata[i]) {
                    // MP4's ©lyr comes as a "USLT" text frame.
                    is TextInformationFrame -> if (entry.id == "USLT") entry.values.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
                    // ID3's USLT isn't decoded by Media3: the raw frame.
                    is BinaryFrame -> if (entry.id == "USLT") decodeUslt(entry.data)?.let { return it }
                    is VorbisComment -> if (entry.key.equals("LYRICS", true) || entry.key.equals("UNSYNCEDLYRICS", true)) {
                        entry.value.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
            return null
        }

        /** An ID3 USLT frame: encoding (1 byte), language (3), a terminated description, the text. */
        private fun decodeUslt(data: ByteArray): String? {
            if (data.size < 5) return null
            val charset = when (data[0].toInt()) {
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
            val wide = data[0].toInt() == 1 || data[0].toInt() == 2
            var i = 4
            // Skip the description up to its terminator (two zero bytes in UTF-16, aligned).
            if (wide) {
                while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
                i += 2
            } else {
                while (i < data.size && data[i].toInt() != 0) i++
                i += 1
            }
            if (i >= data.size) return null
            return String(data, i, data.size - i, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
        }
    }
}
