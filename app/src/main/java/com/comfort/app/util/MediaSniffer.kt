package com.comfort.app.util

import android.webkit.CookieManager
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
 * reach. Three sources, the way the cat-catch browser extension (GPLv3) finds media:
 * - every request the page and its service workers make (WebBrowser's shouldInterceptRequest,
 *   which only reports here and never changes a request): by the URL's extension, and for a
 *   request that looks like media but has none (a media element's Range request, a video/audio
 *   Accept) by its real Content-Type, read with one tiny request ([probe]) — WebView shows
 *   requests, never their responses;
 * - [PAGE_SCRIPT], run in every frame from the start of each page: the page's own fetch/XHR
 *   responses, caught by Content-Type or by what's in them (an HLS playlist, a DASH manifest, JSON
 *   carrying media links), and its <video>/<audio>/<img> elements, inside web components too, with
 *   their real sizes and whether they're playing;
 * - the same script's MediaSource hook, for players that build the video from pieces (a blob:
 *   source) with no link to catch: [recorder] records those pieces while the video plays.
 * Each video, audio file and playlist is then checked once in the background ([check]): a file's
 * size, and whether a playlist is a stream's master or one of its quality variants.
 * [ranked] turns all of it into the list worth showing. Thread-safe: requests arrive on WebView's
 * IO threads, page reports on its JavaBridge thread.
 */
class MediaSniffer(val recorder: MseRecorder) {
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
        /** The response's Content-Type when it's known (page script, probe). */
        val contentType: String? = null,
        /** The poster image of the <video> element playing it, when it has one. */
        val poster: String? = null,
        /** When an element last reported it playing (ms), 0 if never: the one playing now wins
         * the ranking, then the one played most recently. */
        val playedAt: Long = 0,
        /** The file the same player takes this video's sound from, when that's a file of its own:
         * downloading the video merges it in (yt_dlp_wrapper's --comfort-merge-audio). */
        val pairedAudio: String? = null,
    )

    private val _items = MutableStateFlow<List<Caught>>(emptyList())
    val items: StateFlow<List<Caught>> = _items

    /** The page plays something built from pieces (MediaSource): only recording can save it. */
    private val _pieceBuilt = MutableStateFlow(false)
    val pieceBuilt: StateFlow<Boolean> = _pieceBuilt

    /** The page asked for DRM (Encrypted Media Extensions): a recording would be unplayable. */
    private val _protected = MutableStateFlow(false)
    val protected: StateFlow<Boolean> = _protected

    /** The page's own preview picture (og:image / twitter:image), the stand-in for a stream. */
    private val _pagePicture = MutableStateFlow<String?>(null)
    val pagePicture: StateFlow<String?> = _pagePicture

    /** The poster of the page's piece-built (blob:) player: that's the stream's own picture. */
    private val _playerPoster = MutableStateFlow<String?>(null)
    val playerPoster: StateFlow<String?> = _playerPoster

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = Semaphore(3)
    private val checked = java.util.Collections.synchronizedSet(HashSet<String>())
    private val probed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val frameSlots = Semaphore(2)
    private val frames = android.util.LruCache<String, android.graphics.Bitmap>(48)
    private val noFrame = java.util.Collections.synchronizedSet(HashSet<String>())

    /** A new page: what the last one loaded no longer applies. */
    fun reset() {
        _items.update { emptyList() }
        _pieceBuilt.value = false
        _protected.value = false
        _pagePicture.value = null
        _playerPoster.value = null
        checked.clear()
        probed.clear()
    }

    /**
     * A small frame from a caught video file, for its row in the media sheet: MediaMetadataRetriever
     * reads just what it needs (the index and one keyframe, by Range requests) with the page's
     * headers and cookies. Kept per URL; null for a file it can't read (then not tried again).
     */
    suspend fun frameOf(item: Caught): android.graphics.Bitmap? {
        frames.get(item.url)?.let { return it }
        if (item.url in noFrame) return null
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            frameSlots.withPermit {
                frames.get(item.url)?.let { return@withPermit it }
                val retriever = android.media.MediaMetadataRetriever()
                val frame = runCatching {
                    retriever.setDataSource(
                        item.url,
                        item.headers.filterKeys { it.lowercase() in FORWARDED_HEADERS } + cookiesFor(item.url),
                    )
                    val at = 1_000_000L
                    val option = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(at, option, FRAME_SIZE, FRAME_SIZE)
                            ?: retriever.getScaledFrameAtTime(0, option, FRAME_SIZE, FRAME_SIZE)
                    } else {
                        retriever.getFrameAtTime(at, option) ?: retriever.getFrameAtTime(0, option)
                    }
                }.getOrNull()
                runCatching { retriever.release() }
                if (frame != null) frames.put(item.url, frame) else noFrame.add(item.url)
                frame
            }
        }
    }

    /** The browser closed: stops the checks still running. */
    fun close() = scope.cancel()

    fun onRequest(rawUrl: String, headers: Map<String, String>) {
        val url = wholeFile(rawUrl)
        val kept = headers.filterKeys { it.lowercase() != "cookie" }
        val kind = kindOf(url)
        if (kind != null) {
            merge(Caught(url, kind, headers = kept))
        } else if (looksLikeMedia(url, headers) && probed.size < MAX_PROBES && probed.add(url.substringBefore('#'))) {
            scope.launch { checks.withPermit { probe(url, kept) } }
        }
    }

    /** The page script's reports, through addJavascriptInterface(this, JS_NAME). */
    @JavascriptInterface
    fun report(json: String) {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (i in 0 until minOf(array.length(), 200)) {
            val o = array.optJSONObject(i) ?: continue
            val poster = o.optString("po").takeIf { it.startsWith("http") }
            when (o.optString("t")) {
                "mse" -> {
                    _pieceBuilt.value = true
                    if (poster != null) _playerPoster.compareAndSet(null, poster)
                    continue
                }
                "drm" -> { _protected.value = true; continue }
                "og" -> { o.optString("u").takeIf { it.startsWith("http") }?.let { _pagePicture.compareAndSet(null, it) }; continue }
            }
            val url = o.optString("u").takeIf { it.startsWith("http") }?.let(::wholeFile) ?: continue
            val contentType = o.optString("ct").ifBlank { null }
            val kind = when (o.optString("t")) {
                "img" -> Kind.IMAGE
                "audio" -> Kind.AUDIO
                "stream" -> Kind.STREAM
                // A link found in a response: what its extension or Content-Type says, if media.
                "net" -> kindOf(url)?.takeIf { it != Kind.SEGMENT && it != Kind.IMAGE } ?: continue
                else -> kindOf(url)?.takeIf { it != Kind.IMAGE } ?: Kind.VIDEO
            }
            val playing = o.optBoolean("p")
            merge(
                Caught(
                    url, kind, o.optInt("w"), o.optInt("h"), playing,
                    contentType = contentType, poster = poster,
                    playedAt = if (playing) System.currentTimeMillis() else 0,
                    pairedAudio = o.optString("pa").takeIf { it.startsWith("http") }?.let(::wholeFile),
                ),
                // Element reports (every 2 s) say whether it's playing right now; others don't know.
                fromElement = o.has("p"),
            )
        }
    }

    /** Whether the MediaSource hook should pass on what's appended (a recording is running). */
    @JavascriptInterface
    fun recording(): Boolean = recorder.isActive

    /** One appended piece of a recording: [id] the page's SourceBuffer, [mime] its type. */
    @JavascriptInterface
    fun chunk(id: String, mime: String, base64: String) {
        val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull() ?: return
        recorder.append(id, mime, bytes)
    }

    /** The page's MediaSource said its stream is complete (endOfStream). */
    @JavascriptInterface
    fun ended() = recorder.markEnded()

    private fun merge(new: Caught, fromElement: Boolean = false) {
        var added = false
        _items.update { list ->
            val i = list.indexOfFirst { it.url == new.url }
            when {
                i >= 0 -> list.toMutableList().apply {
                    val old = this[i]
                    this[i] = old.copy(
                        // A Content-Type learned later can turn a guess into the real kind.
                        // So can the player itself: a file it plays as sound is audio, whatever
                        // type the server gave it (Instagram serves its audio as video/mp4).
                        kind = if (
                            old.kind == Kind.SEGMENT || (old.kind == Kind.VIDEO && new.kind == Kind.STREAM) ||
                            (fromElement && old.kind == Kind.VIDEO && new.kind == Kind.AUDIO)
                        ) new.kind else old.kind,
                        pairedAudio = old.pairedAudio ?: new.pairedAudio,
                        width = if (new.width > 0) new.width else old.width,
                        height = if (new.height > 0) new.height else old.height,
                        playing = if (fromElement) new.playing else old.playing,
                        playedAt = maxOf(old.playedAt, new.playedAt),
                        headers = old.headers.ifEmpty { new.headers },
                        contentType = old.contentType ?: new.contentType,
                        poster = old.poster ?: new.poster,
                    )
                }
                // Pictures have their own, smaller allowance, so a gallery page full of them can't
                // fill the list before the video it also plays shows up.
                new.kind == Kind.IMAGE && list.count { it.kind == Kind.IMAGE } >= MAX_IMAGES -> list
                list.count { it.kind != Kind.IMAGE } >= MAX_MEDIA -> list
                else -> list + new.also { added = true }
            }
        }
        if (added && new.kind in setOf(Kind.VIDEO, Kind.AUDIO, Kind.STREAM) && checked.add(new.url)) {
            scope.launch { checks.withPermit { check(new) } }
        }
    }

    /** For a request with no media extension that looks like media: its first byte, with the
     * page's headers and cookies, to read the Content-Type (and the whole size from Content-Range).
     * Media is listed; anything else (an image, a script) is forgotten. */
    private fun probe(url: String, headers: Map<String, String>) {
        val c = runCatching {
            open(url, "GET", headers.filterKeys { it.lowercase() in FORWARDED_HEADERS } + cookiesFor(url) + ("Range" to "bytes=0-0"))
        }.getOrNull() ?: return
        try {
            if (c.responseCode !in 200..299) return
            val type = c.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val disposition = c.getHeaderField("Content-Disposition").orEmpty()
            val kind = kindOfType(type)
                ?: Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""").find(disposition)?.groupValues?.get(1)
                    ?.let { kindOf("http://x/$it") }?.takeIf { it != Kind.IMAGE && it != Kind.SEGMENT }
                ?: return
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: c.contentLengthLong.takeIf { c.responseCode == 200 } ?: -1L
            merge(Caught(url, kind, headers = headers, bytes = total, contentType = type))
        } catch (_: Exception) {
        } finally {
            c.disconnect()
        }
    }

    /** One small request per file: its size (HEAD), or for a playlist, its first lines (master
     * playlists list their qualities with #EXT-X-STREAM-INF; a DASH .mpd is always the whole). */
    private fun check(item: Caught) {
        val result = runCatching {
            val headers = item.headers.filterKeys { it.lowercase() in FORWARDED_HEADERS }
            if (item.kind == Kind.STREAM) {
                if (item.url.substringBefore('?').lowercase().endsWith(".mpd") || item.contentType?.contains("dash") == true) {
                    return@runCatching -1L to true
                }
                val text = open(item.url, "GET", headers + cookiesFor(item.url)).let { c ->
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
                if (item.bytes >= 0) return@runCatching item.bytes to null
                val c = open(item.url, "HEAD", headers + cookiesFor(item.url))
                val length = try { c.contentLengthLong } finally { c.disconnect() }
                length to null
            }
        }.getOrNull() ?: return
        _items.update { list ->
            list.map { if (it.url == item.url) it.copy(bytes = result.first, isMaster = result.second) else it }
        }
    }

    private fun cookiesFor(url: String): Map<String, String> =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { mapOf("Cookie" to it) }.orEmpty()

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
        private const val MAX_MEDIA = 400
        private const val MAX_IMAGES = 200
        // Extensionless requests checked per page, at most: a page can make thousands.
        private const val MAX_PROBES = 80
        // A row's thumbnail frame, at most this many pixels on its longer side.
        private const val FRAME_SIZE = 192
        // Smaller than this is a site's own sound or a placeholder clip, not what you came for.
        private const val MIN_MEDIA_BYTES = 150_000L
        // A picture needs one side this long and neither side shorter than MIN_IMAGE_SHORT_SIDE:
        // a 568×53 logo strip is long but no picture.
        private const val MIN_IMAGE_SIDE = 300
        private const val MIN_IMAGE_SHORT_SIDE = 150
        private val FORWARDED_HEADERS = setOf("referer", "origin", "user-agent", "authorization")

        private val STREAM = Regex("""\.(m3u8|m3u|mpd)$""")
        private val SEGMENT = Regex("""\.(ts|m4s|cmfv|cmfa|aac)$""")
        private val VIDEO = Regex("""\.(mp4|webm|mov|mkv|m4v|3gp|flv|f4v|ogv|avi|wmv|mpeg)$""")
        private val AUDIO = Regex("""\.(mp3|m4a|ogg|opus|oga|wav|flac|weba|wma)$""")
        private val IMAGE = Regex("""\.(jpe?g|png|gif|webp|avif)$""")
        // Clearly not media, whatever its headers say: never worth a probe.
        private val NOT_MEDIA = Regex("""\.(js|mjs|css|json|html?|svg|ico|woff2?|ttf|otf|wasm|map|txt|xml|vtt|srt)$""")
        // "1440_2560", "1920x1080" in a file name; digits on either side mean it's part of an id.
        private val SIZE_IN_NAME = Regex("""(?<!\d)(\d{3,4})[x_×](\d{3,4})(?!\d)""")
        private val HEIGHT_IN_NAME = Regex("""(?<!\d)(\d{3,4})p(?![a-z])""")

        /** Ad and tracking hosts whose media is never what a page is about. */
        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google",
            "imasdk.googleapis.com", "amazon-adsystem.com", "adnxs.com", "criteo.", "taboola.com",
            "outbrain.com", "pubmatic.com", "rubiconproject.com", "moatads.com", "scorecardresearch.com",
            "adsrvr.org", "teads.tv", "spotxchange.com", "springserve.com", "innovid.com",
            // Cookie-consent banners' own logos and pictures.
            "onetrust.com", "cookielaw.org", "cookiebot.com", "trustarc.com", "quantcast.com", "usercentrics.eu",
        )

        private val PIECE_PARAMS = Regex("""(?<=[?&])(?:bytestart|byteend)=\d*&?""")

        /** The whole file for a URL that asks for one piece of it: Instagram's and Facebook's
         * players fetch a video as …mp4?…&bytestart=0&byteend=812345, a new URL per piece — each
         * would be listed (and downloaded) as a file of its own, and only that piece. */
        fun wholeFile(url: String): String {
            if (!url.contains("bytestart=") && !url.contains("byteend=")) return url
            return PIECE_PARAMS.replace(url, "").removeSuffix("&").removeSuffix("?")
        }

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

        /** What a response's Content-Type says it is; null for anything that isn't media. */
        fun kindOfType(type: String): Kind? = when {
            "mpegurl" in type || "dash+xml" in type -> Kind.STREAM
            type.startsWith("video/") || type == "application/ogg" -> Kind.VIDEO
            type.startsWith("audio/") -> Kind.AUDIO
            else -> null
        }

        /** A request without a media extension that's still worth a probe: what a media element
         * sends (a Range request, a video/audio Accept, a mime= query), never a known non-media
         * file. Every other request stays unchecked — a page makes too many to check them all. */
        fun looksLikeMedia(url: String, headers: Map<String, String>): Boolean {
            if (!url.startsWith("http")) return false
            val path = url.substringBefore('#').substringBefore('?').lowercase()
            if (NOT_MEDIA.containsMatchIn(path) || IMAGE.containsMatchIn(path)) return false
            val h = headers.mapKeys { it.key.lowercase() }
            val accept = h["accept"].orEmpty().lowercase()
            return h["range"]?.startsWith("bytes=") == true ||
                "video/" in accept || "audio/" in accept ||
                Regex("""[?&]mime=(video|audio)""").containsMatchIn(url.lowercase())
        }

        /** Width × height: the element's real size when the page script saw it, else a size in
         * the file name ("1440_2560", "1080p" taken as 16:9); null when neither says. */
        fun sizeOf(item: Caught): Pair<Int, Int>? {
            if (item.width > 0 && item.height > 0) return item.width to item.height
            val name = item.url.substringBefore('?').substringAfterLast('/')
            SIZE_IN_NAME.find(name)?.let { m -> return m.groupValues[1].toInt() to m.groupValues[2].toInt() }
            HEIGHT_IN_NAME.find(name)?.let { m -> val h = m.groupValues[1].toInt(); return h * 16 / 9 to h }
            return null
        }

        fun pixelsOf(item: Caught): Long = sizeOf(item)?.let { (w, h) -> w.toLong() * h } ?: 0

        private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().trim().lowercase()

        /** "videos.pexels.com" → "pexels.com" (good enough for "same site"; no public-suffix list). */
        private fun siteOf(host: String): String {
            val parts = host.split('.').filter { it.isNotBlank() }
            return if (parts.size < 2) host else parts.takeLast(2).joinToString(".")
        }

        /**
         * What's worth listing for the page at [pageUrl], best first. Left out: a stream's pieces,
         * a stream's quality variants once its master is caught, images under 300 px, or under
         * 150 px on their short side (or never seen on the page), files the check found under 150 KB, anything from an ad host.
         * Order: videos and streams, then audio, then images; within that what's playing now, then
         * what played last, then the largest (pixels, then bytes), then the page's own site.
         */
        fun ranked(items: List<Caught>, pageUrl: String): List<Caught> {
            val pageSite = siteOf(hostOf(pageUrl))
            val hasMaster = items.any { it.kind == Kind.STREAM && it.isMaster == true }
            return items.filter { c ->
                val host = hostOf(c.url)
                AD_HOSTS.none { host.contains(it) } && when (c.kind) {
                    Kind.SEGMENT -> false
                    Kind.IMAGE -> maxOf(c.width, c.height) >= MIN_IMAGE_SIDE && minOf(c.width, c.height) >= MIN_IMAGE_SHORT_SIDE
                    Kind.STREAM -> !(hasMaster && c.isMaster == false)
                    Kind.VIDEO, Kind.AUDIO -> c.bytes < 0 || c.bytes >= MIN_MEDIA_BYTES
                }
            }.sortedWith(
                compareBy<Caught> { if (it.kind == Kind.VIDEO || it.kind == Kind.STREAM) 0 else if (it.kind == Kind.AUDIO) 1 else 2 }
                    // What's on screen beats what the page only preloaded, however big that is.
                    .thenByDescending { it.playing }
                    .thenByDescending { it.playedAt }
                    .thenByDescending { pixelsOf(it) }
                    .thenByDescending { if (it.kind == Kind.STREAM) (if (it.isMaster == true) 1L else 0L) else it.bytes }
                    .thenByDescending { siteOf(hostOf(it.url)) == pageSite },
            )
        }

        /**
         * Runs in every frame of the page from its very start (WebViewCompat's document-start
         * script; after each load as well where that isn't supported — it guards itself):
         * - wraps fetch and XMLHttpRequest to look at each response: media by its Content-Type,
         *   an HLS playlist or DASH manifest by its first line, and media links inside JSON or text
         *   (a player's config). Small text responses only; a video's own bytes are never read.
         * - reports the frame's <video>/<audio>/<img> elements every 2 s and when one starts
         *   playing, searching web components' shadow roots too. Images under 200 px are left out.
         * - wraps MediaSource.addSourceBuffer, so a player that builds its video from pieces (a
         *   blob: source) is reported, and, while a recording runs, every piece it appends is
         *   copied (in order, per buffer) to the app; endOfStream says the stream is complete.
         * - notes a request for DRM (requestMediaKeySystemAccess): such a recording can't play.
         * Reports are batched to one bridge call every 400 ms.
         */
        val PAGE_SCRIPT = """
            (function () {
              if (window.__comfortSniff) return;
              window.__comfortSniff = true;
              var B = window.$JS_NAME;
              if (!B) return;
              var queue = [], timer = null;
              function send(item) {
                queue.push(item);
                if (!timer) timer = setTimeout(function () {
                  timer = null;
                  var q = queue; queue = [];
                  try { B.report(JSON.stringify(q)); } catch (e) {}
                }, 400);
              }
              function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return null; } }
              function kindOfType(ct) {
                ct = (ct || '').toLowerCase();
                if (/mpegurl|dash\+xml/.test(ct)) return 'stream';
                if (/^audio\//.test(ct)) return 'audio';
                if (/^video\/|^application\/ogg/.test(ct)) return 'video';
                return null;
              }
              function manifest(text) {
                if (!text) return false;
                var t = text.slice(0, 4096).replace(/^\s+/, '');
                return t.indexOf('#EXTM3U') === 0 || /<MPD[\s>]/.test(t);
              }
              var LINK = /https?:\/\/[^\s"'<>\\]+?\.(?:m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3)(?:\?[^\s"'<>\\]*)?(?=["'\s<>\\,\]\}]|$)/gi;
              function links(text) {
                if (!text || text.length > 4000000) return;
                text = text.replace(/\\\//g, '/').replace(/\\u0026/gi, '&');
                var m, n = 0;
                LINK.lastIndex = 0;
                while ((m = LINK.exec(text)) && n++ < 50) send({ u: m[0], t: 'net' });
              }
              // Which file each player's pieces come from, so the playing video's own file can be
              // told apart from others the page preloads (Instagram fetches the next reels too).
              // Matched by content: the first 64 bytes of every response, and of every chunk a
              // page streams one in by (Instagram reads response.body and copies the chunks into
              // new buffers, so the buffer objects themselves can't be followed), are noted with
              // the response's URL; appendBuffer looks its data's first 64 bytes up. No match, no
              // claim. Pieces of a segmented stream (.ts, .m4s) aren't files worth naming.
              var seen = new Map();
              var PIECE = /\.(?:ts|m4s|cmf[va]|aac)(?:[?#]|$)/i;
              function print(bytes) {
                if (!bytes || bytes.byteLength < 16) return null;
                return String.fromCharCode.apply(null, bytes.subarray(0, 64));
              }
              function note(bytes, url) {
                if (!url || PIECE.test(url)) return;
                var f = print(bytes);
                if (!f) return;
                if (seen.size > 3000) seen.clear();
                seen.set(f, url);
              }
              function inspect(url, ct, readText) {
                if (!url) return;
                var k = kindOfType(ct);
                if (k) { send({ u: url, t: k, ct: (ct || '').split(';')[0] }); return; }
                if (!/json|text|mpegurl|xml|javascript/i.test(ct || 'text/plain')) return;
                readText(function (text) {
                  if (manifest(text)) send({ u: url, t: 'stream' });
                  else links(text);
                });
              }

              var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (m, u) { this.__cu = u; return XO.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function () {
                var x = this;
                x.addEventListener('load', function () {
                  try {
                    if (x.responseType === 'arraybuffer' && x.response) note(new Uint8Array(x.response), x.responseURL || abs(x.__cu));
                    inspect(x.responseURL || abs(x.__cu), x.getResponseHeader('content-type'), function (cb) {
                      if (x.responseType === '' || x.responseType === 'text') cb(x.responseText);
                      else if (x.responseType === 'json' && x.response) cb(JSON.stringify(x.response));
                    });
                  } catch (e) {}
                });
                return XS.apply(this, arguments);
              };

              var F = window.fetch;
              if (F) window.fetch = function () {
                var p = F.apply(this, arguments);
                p.then(function (r) {
                  try {
                    if (r.type === 'opaque') return;
                    var len = +(r.headers.get('content-length') || 0);
                    inspect(r.url, r.headers.get('content-type'), function (cb) {
                      if (len > 4000000) return;
                      r.clone().text().then(cb, function () {});
                    });
                  } catch (e) {}
                }, function () {});
                return p;
              };

              try {
                var RP = Response.prototype, RAB = RP.arrayBuffer;
                RP.arrayBuffer = function () {
                  var u = this.url;
                  return RAB.apply(this, arguments).then(function (b) { note(new Uint8Array(b), u); return b; });
                };
                // A streamed media body: each chunk it gives, however it's consumed — through a
                // reader, or piped on (Instagram pipes it through its own transform and on into a
                // sink, so no reader ever sees it; a pass-through tap goes in front there, at the
                // point the page locks the stream anyway). Only bodies served as media.
                var streamUrl = new WeakMap(), readerUrl = new WeakMap();
                var BODY = Object.getOwnPropertyDescriptor(RP, 'body');
                if (BODY && BODY.get) Object.defineProperty(RP, 'body', {
                  configurable: true, enumerable: BODY.enumerable,
                  get: function () {
                    var b = BODY.get.call(this);
                    try {
                      if (b && this.url && /^(?:video|audio)\/|octet-stream/i.test(this.headers.get('content-type') || '')) streamUrl.set(b, this.url);
                    } catch (e) {}
                    return b;
                  },
                });
                function tap(u) {
                  return new TransformStream({ transform: function (c, ctl) { if (c instanceof Uint8Array) note(c, u); ctl.enqueue(c); } });
                }
                var RS = ReadableStream.prototype, PT = RS.pipeThrough, PTO = RS.pipeTo, GR = RS.getReader;
                RS.pipeThrough = function (t, o) {
                  var u = streamUrl.get(this);
                  return u ? PT.call(PT.call(this, tap(u)), t, o) : PT.apply(this, arguments);
                };
                RS.pipeTo = function (d, o) {
                  var u = streamUrl.get(this);
                  return u ? PTO.call(PT.call(this, tap(u)), d, o) : PTO.apply(this, arguments);
                };
                RS.getReader = function () {
                  var r = GR.apply(this, arguments), u = streamUrl.get(this);
                  if (u) readerUrl.set(r, u);
                  return r;
                };
                var RD = ReadableStreamDefaultReader.prototype.read;
                ReadableStreamDefaultReader.prototype.read = function () {
                  var p = RD.apply(this, arguments), u = readerUrl.get(this);
                  if (u) p.then(function (c) { if (c && !c.done && c.value instanceof Uint8Array) note(c.value, u); }, function () {});
                  return p;
                };
              } catch (e) {}

              try {
                var RK = navigator.requestMediaKeySystemAccess;
                if (RK) navigator.requestMediaKeySystemAccess = function () { send({ t: 'drm' }); return RK.apply(this, arguments); };
              } catch (e) {}

              var MS = window.MediaSource || window.ManagedMediaSource;
              if (MS && MS.prototype.addSourceBuffer) {
                var ASB = MS.prototype.addSourceBuffer, EOS = MS.prototype.endOfStream;
                var frameId = Math.random().toString(36).slice(2, 8), next = 0;
                // blob: URL -> its MediaSource, to find the one behind a <video>.
                var sources = {};
                try {
                  var COU = URL.createObjectURL;
                  URL.createObjectURL = function (o) {
                    var u = COU.apply(this, arguments);
                    try { if (o instanceof MS) sources[u] = o; } catch (e) {}
                    return u;
                  };
                } catch (e) {}
                MS.prototype.addSourceBuffer = function (mime) {
                  var sb = ASB.apply(this, arguments), ms = this;
                  var audio = /^audio\//i.test(String(mime));
                  var id = frameId + '-' + (next++), chain = Promise.resolve();
                  send({ t: 'mse' });
                  var AB = sb.appendBuffer;
                  sb.appendBuffer = function (data) {
                    try {
                      var src = seen.get(print(data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength)));
                      if (src) {
                        var fed = ms.__comfortFed || (ms.__comfortFed = {});
                        if (!(src in fed) && Object.keys(fed).length < 8) fed[src] = audio ? 'audio' : 'video';
                      }
                    } catch (e) {}
                    try {
                      if (B.recording()) {
                        var view = data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                        var copy = new Blob([view.slice(0)]);
                        chain = chain.then(function () {
                          return new Promise(function (done) {
                            var fr = new FileReader();
                            fr.onload = function () {
                              try { B.chunk(id, String(mime), String(fr.result).split(',')[1] || ''); } catch (e) {}
                              done();
                            };
                            fr.onerror = function () { done(); };
                            fr.readAsDataURL(copy);
                          });
                        });
                      }
                    } catch (e) {}
                    return AB.apply(this, arguments);
                  };
                  return sb;
                };
                if (EOS) MS.prototype.endOfStream = function () {
                  try { if (B.recording()) B.ended(); } catch (e) {}
                  return EOS.apply(this, arguments);
                };
              }

              function each(root, selector, fn, depth) {
                root.querySelectorAll(selector).forEach(fn);
                if (depth > 4) return;
                root.querySelectorAll('*').forEach(function (el) { if (el.shadowRoot) each(el.shadowRoot, selector, fn, depth + 1); });
              }
              function scan() {
                if (!document.documentElement) return;
                var out = [];
                each(document, 'video, audio', function (v) {
                  var urls = [v.currentSrc || v.src], po = v.poster ? abs(v.poster) : '';
                  v.querySelectorAll('source').forEach(function (s) { urls.push(s.src); });
                  urls.forEach(function (u) {
                    if (!u) return;
                    if (u.indexOf('blob:') === 0) {
                      out.push({ t: 'mse', po: po });
                      // The files this player is built from, as this element: playing or not.
                      var fed = (sources[u] || {}).__comfortFed || {};
                      // A video file with its sound in a file of its own (Instagram's): paired.
                      var pa = Object.keys(fed).filter(function (f) { return fed[f] === 'audio'; })[0] || '';
                      Object.keys(fed).forEach(function (f) {
                        var a = fed[f] === 'audio';
                        out.push({ u: f, t: a ? 'audio' : 'video', w: a ? 0 : v.videoWidth || 0, h: a ? 0 : v.videoHeight || 0, p: !v.paused, po: a ? '' : po, pa: a ? '' : pa });
                      });
                      return;
                    }
                    out.push({ u: u, t: v.tagName.toLowerCase(), w: v.videoWidth || 0, h: v.videoHeight || 0, p: !v.paused, po: po });
                  });
                }, 0);
                each(document, 'img', function (i) {
                  if (i.currentSrc && i.naturalWidth >= 200) out.push({ u: i.currentSrc, t: 'img', w: i.naturalWidth, h: i.naturalHeight, p: false });
                }, 0);
                if (window === window.top) {
                  var og = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                  if (og && og.content) out.push({ u: abs(og.content), t: 'og' });
                }
                if (out.length) try { B.report(JSON.stringify(out)); } catch (e) {}
              }
              setInterval(scan, 2000);
              document.addEventListener('DOMContentLoaded', scan);
              document.addEventListener('play', function () { setTimeout(scan, 300); }, true);
            })();
        """.trimIndent()
    }
}
