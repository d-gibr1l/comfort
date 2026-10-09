package com.comfort.app.util

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Collects the media a page in the in-app browser loads, for downloading what the engines can't
 * reach. Two sources:
 * - every request the page makes (WebBrowser's shouldInterceptRequest, which only reports here and
 *   never changes a request) — files, stream playlists, the pieces of a stream;
 * - [PAGE_SCRIPT], run in the page: its <video>/<audio>/<img> elements with their real sizes and
 *   whether they're playing, which the requests alone don't say.
 * Each video, audio file and playlist is then checked once in the background ([check]): a file's
 * size, and whether a playlist is a stream's master or one of its quality variants.
 * [ranked] turns all of it into the list worth showing. Thread-safe: requests arrive on WebView's
 * IO thread, page reports on its JavaBridge thread.
 */
class MediaSniffer {
    enum class Kind { VIDEO, STREAM, AUDIO, IMAGE, SEGMENT }

    data class Caught(
        val url: String,
        val kind: Kind,
        val width: Int = 0,
        val height: Int = 0,
        val playing: Boolean = false,
        // The headers the page sent with it (Referer, Origin, ...): some hosts refuse the file
        // without them. Cookies aren't in these; they come from CookieManager when downloading.
        val headers: Map<String, String> = emptyMap(),
        /** From the check: the file's size, -1 until known (or if the server doesn't say). */
        val bytes: Long = -1,
        /** From the check, for a playlist: a master (lists qualities) or not; null until known. */
        val isMaster: Boolean? = null,
    )

    private val _items = MutableStateFlow<List<Caught>>(emptyList())
    val items: StateFlow<List<Caught>> = _items

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = Semaphore(3)
    private val checked = java.util.Collections.synchronizedSet(HashSet<String>())

    /** A new page: what the last one loaded no longer applies. */
    fun reset() {
        _items.update { emptyList() }
        checked.clear()
    }

    /** The browser closed: stops the checks still running. */
    fun close() = scope.cancel()

    fun onRequest(url: String, headers: Map<String, String>) {
        val kind = kindOf(url) ?: return
        merge(Caught(url, kind, headers = headers.filterKeys { it.lowercase() != "cookie" }))
    }

    /** The page script's reports, through addJavascriptInterface(this, JS_NAME). */
    @JavascriptInterface
    fun report(json: String) {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (i in 0 until minOf(array.length(), 200)) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("u").takeIf { it.startsWith("http") } ?: continue
            val kind = when (o.optString("t")) {
                "img" -> Kind.IMAGE
                "audio" -> Kind.AUDIO
                else -> kindOf(url)?.takeIf { it != Kind.IMAGE } ?: Kind.VIDEO
            }
            merge(Caught(url, kind, o.optInt("w"), o.optInt("h"), o.optBoolean("p")))
        }
    }

    private fun merge(new: Caught) {
        var added = false
        _items.update { list ->
            val i = list.indexOfFirst { it.url == new.url }
            when {
                i >= 0 -> list.toMutableList().apply {
                    val old = this[i]
                    this[i] = old.copy(
                        width = if (new.width > 0) new.width else old.width,
                        height = if (new.height > 0) new.height else old.height,
                        playing = new.playing || old.playing,
                        headers = old.headers.ifEmpty { new.headers },
                    )
                }
                list.size >= MAX_ITEMS -> list
                else -> list + new.also { added = true }
            }
        }
        if (added && new.kind in setOf(Kind.VIDEO, Kind.AUDIO, Kind.STREAM) && checked.add(new.url)) {
            scope.launch { checks.withPermit { check(new) } }
        }
    }

    /** One small request per file: its size (HEAD), or for a playlist, its first lines (master
     * playlists list their qualities with #EXT-X-STREAM-INF; a DASH .mpd is always the whole). */
    private fun check(item: Caught) {
        val result = runCatching {
            val headers = item.headers.filterKeys { it.lowercase() in FORWARDED_HEADERS }
            if (item.kind == Kind.STREAM) {
                if (item.url.substringBefore('?').lowercase().endsWith(".mpd")) return@runCatching -1L to true
                val text = open(item.url, "GET", headers).let { c ->
                    try {
                        c.inputStream.use { input ->
                            // Only the start: #EXT-X-STREAM-INF lines come first in a master.
                            val buf = ByteArray(64 * 1024)
                            var n = 0
                            while (n < buf.size) {
                                val r = input.read(buf, n, buf.size - n)
                                if (r < 0) break
                                n += r
                            }
                            String(buf, 0, n)
                        }
                    } finally {
                        c.disconnect()
                    }
                }
                -1L to text.contains("#EXT-X-STREAM-INF")
            } else {
                val c = open(item.url, "HEAD", headers)
                val length = try { c.contentLengthLong } finally { c.disconnect() }
                length to null
            }
        }.getOrNull() ?: return
        _items.update { list ->
            list.map { if (it.url == item.url) it.copy(bytes = result.first, isMaster = result.second) else it }
        }
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    companion object {
        const val JS_NAME = "ComfortSniffer"
        private const val MAX_ITEMS = 400
        // Smaller than this is a site's own sound or a placeholder clip, not what you came for.
        private const val MIN_MEDIA_BYTES = 150_000L
        private const val MIN_IMAGE_SIDE = 300
        private val FORWARDED_HEADERS = setOf("referer", "origin", "user-agent")

        private val STREAM = Regex("""\.(m3u8|mpd)$""")
        private val SEGMENT = Regex("""\.(ts|m4s|cmfv|cmfa|aac)$""")
        private val VIDEO = Regex("""\.(mp4|webm|mov|mkv|m4v|3gp|flv)$""")
        private val AUDIO = Regex("""\.(mp3|m4a|ogg|opus|oga|wav|flac)$""")
        private val IMAGE = Regex("""\.(jpe?g|png|gif|webp|avif)$""")
        // "1440_2560", "1920x1080" in a file name; digits on either side mean it's part of an id.
        private val SIZE_IN_NAME = Regex("""(?<!\d)(\d{3,4})[x_×](\d{3,4})(?!\d)""")
        private val HEIGHT_IN_NAME = Regex("""(?<!\d)(\d{3,4})p(?![a-z])""")

        /** Ad and tracking hosts whose media is never what a page is about. */
        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google",
            "imasdk.googleapis.com", "amazon-adsystem.com", "adnxs.com", "criteo.", "taboola.com",
            "outbrain.com", "pubmatic.com", "rubiconproject.com", "moatads.com", "scorecardresearch.com",
            "adsrvr.org", "teads.tv", "spotxchange.com", "springserve.com", "innovid.com",
        )

        /** What a URL is, from its path's extension; null for anything that isn't media. */
        fun kindOf(url: String): Kind? {
            if (!url.startsWith("http")) return null
            val path = url.substringBefore('#').substringBefore('?').lowercase()
            return when {
                STREAM.containsMatchIn(path) -> Kind.STREAM
                SEGMENT.containsMatchIn(path) -> Kind.SEGMENT
                VIDEO.containsMatchIn(path) -> Kind.VIDEO
                AUDIO.containsMatchIn(path) -> Kind.AUDIO
                IMAGE.containsMatchIn(path) -> Kind.IMAGE
                else -> null
            }
        }

        /** Pixels: the element's real size when the page script saw it, else a size in the name. */
        fun pixelsOf(item: Caught): Long {
            if (item.width > 0 && item.height > 0) return item.width.toLong() * item.height
            val name = item.url.substringBefore('?').substringAfterLast('/')
            SIZE_IN_NAME.find(name)?.let { m -> return m.groupValues[1].toLong() * m.groupValues[2].toLong() }
            HEIGHT_IN_NAME.find(name)?.let { m -> val h = m.groupValues[1].toLong(); return h * h * 16 / 9 }
            return 0
        }

        private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().lowercase()

        /** "videos.pexels.com" → "pexels.com" (good enough for "same site"; no public-suffix list). */
        private fun siteOf(host: String) = host.split('.').takeLast(2).joinToString(".")

        /**
         * What's worth listing for the page at [pageUrl], best first. Left out: a stream's pieces,
         * a stream's quality variants once its master is caught, images under 300 px (or never
         * seen on the page), files the check found under 150 KB, anything from an ad host.
         * Order: videos and streams, then audio, then images; within that the largest (pixels,
         * then bytes), then the page's own site, then what's playing.
         */
        fun ranked(items: List<Caught>, pageUrl: String): List<Caught> {
            val pageSite = siteOf(hostOf(pageUrl))
            val hasMaster = items.any { it.kind == Kind.STREAM && it.isMaster == true }
            return items.filter { c ->
                val host = hostOf(c.url)
                AD_HOSTS.none { host.contains(it) } && when (c.kind) {
                    Kind.SEGMENT -> false
                    Kind.IMAGE -> c.width >= MIN_IMAGE_SIDE || c.height >= MIN_IMAGE_SIDE
                    Kind.STREAM -> !(hasMaster && c.isMaster == false)
                    Kind.VIDEO, Kind.AUDIO -> c.bytes < 0 || c.bytes >= MIN_MEDIA_BYTES
                }
            }.sortedWith(
                compareBy<Caught> { if (it.kind == Kind.VIDEO || it.kind == Kind.STREAM) 0 else if (it.kind == Kind.AUDIO) 1 else 2 }
                    .thenByDescending { pixelsOf(it) }
                    .thenByDescending { if (it.kind == Kind.STREAM) (if (it.isMaster == true) 1L else 0L) else it.bytes }
                    .thenByDescending { siteOf(hostOf(it.url)) == pageSite }
                    .thenByDescending { it.playing },
            )
        }

        /** Runs in the page (again after each load; it guards itself): reports its media elements
         * every 2 s and whenever something starts playing. Images under 200 px are left out. */
        val PAGE_SCRIPT = """
            (function () {
              if (window.__comfortSniff) return;
              window.__comfortSniff = true;
              function scan() {
                var out = [];
                document.querySelectorAll('video, audio').forEach(function (v) {
                  var urls = [v.currentSrc || v.src];
                  v.querySelectorAll('source').forEach(function (s) { urls.push(s.src); });
                  urls.forEach(function (u) {
                    if (u) out.push({ u: u, t: v.tagName.toLowerCase(), w: v.videoWidth || 0, h: v.videoHeight || 0, p: !v.paused });
                  });
                });
                document.querySelectorAll('img').forEach(function (i) {
                  if (i.currentSrc && i.naturalWidth >= 200) out.push({ u: i.currentSrc, t: 'img', w: i.naturalWidth, h: i.naturalHeight, p: false });
                });
                try { $JS_NAME.report(JSON.stringify(out)); } catch (e) {}
              }
              scan();
              setInterval(scan, 2000);
              document.addEventListener('play', function () { setTimeout(scan, 300); }, true);
            })();
        """.trimIndent()
    }
}
