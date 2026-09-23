package com.comfort.app.util

import android.content.Context
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One file discovered while enumerating a URL, without downloading it. [num] is gallery-dl's own
 * "num" keyword (or, for the yt-dlp path, a locally synthesized 1-based sequence) — real download
 * selection depends on it matching gallery-dl's own numbering exactly (see SharePickerScreen's
 * "num in {...}" --filter string), so it's NOT safe to use as a Compose list key on its own: it's
 * only guaranteed unique *within one source's own file sequence*, not across a combined listing
 * that spans several separate posts (a subreddit-index URL like reddit.com/r/pics/top/, for
 * instance) — reproduced live as a hard crash ("Key "0" was already used") the moment two different
 * posts' items both happened to be numbered 0. [listIndex] is this item's actual position in the
 * combined list — always unique regardless of what gallery-dl's own numbering does — kept as a
 * separate field precisely so nothing downstream is tempted to reuse [num] for identity again. */
data class GalleryItem(val num: Int, val url: String, val filename: String?, val title: String?, val listIndex: Int)

/** What the download preview sheet shows about a link before committing to downloading it. Every
 * field is independently optional — extractors vary a lot in what they populate, and a missing
 * title or thumbnail is a cosmetic gap in the card, not a reason to refuse the download. */
data class PreviewInfo(
    val title: String?,
    val uploader: String?,
    val thumbnail: String?,
    /** Bytes, from yt-dlp's own filesize/filesize_approx. Null when the extractor doesn't report
     * either - common enough that the preview card just omits the size rather than guessing. */
    val filesizeBytes: Long?,
    val durationMs: Long?,
    val streamUrls: List<String>,
    /** Real track artist/album, for the download preview sheet's own song-styled card — populated
     * by both yt_dlp_wrapper.py's and spotify_wrapper.py's list_info() (see their own doc
     * comments). Null for anything that isn't a song, and often null for [album] even on a real
     * song (a plain YouTube video, or a bare Spotify track link — see spotify_wrapper.py's own
     * top comment on that specific gap). */
    val artist: String? = null,
    val album: String? = null,
    /** Every track of a Spotify album/playlist link, in resolution order — empty for a single-item
     * link. [TrackPreview.num] is the same 1-based position the rest of the app's
     * "num in {...}" item-filter format already means (see DownloadEntity.itemFilter's own doc
     * comment) and the same order spotify_wrapper.py's download() resolves its own track_ids in,
     * so a UI-built filter string from these nums lines up with the real download without any
     * translation. */
    val tracks: List<TrackPreview> = emptyList(),
    /** The album/playlist itself (not any one track) — from spotify_wrapper.py's own
     * "collection_*" fields, null for a single-item link. */
    val collectionTitle: String? = null,
    val collectionArtist: String? = null,
    val collectionThumbnail: String? = null,
)

/** One track of a multi-item song listing (Spotify album/playlist today — see [PreviewInfo.tracks]
 * doc comment for why [num] is load-bearing beyond just display). [thumbnail] is the track's own
 * cover art when the source engine actually provides one per-item (YouTube Music playlists do);
 * for Spotify, which has no per-track art at all (confirmed live against its own embed-page JSON
 * — every track entry carries only title/subtitle/duration, never an image), this is left null
 * here and the UI falls back to the whole collection's own cover art instead (see
 * [PreviewInfo.collectionThumbnail]) rather than fetching one image per track, which doesn't
 * scale to a long playlist. */
data class TrackPreview(val num: Int, val title: String?, val artist: String?, val durationMs: Long?, val thumbnail: String? = null)

/** [items] is only ever non-empty when [errorMessage] is null and vice versa — a genuinely empty
 * gallery (no error, nothing found) and a real failure (login required, network error, ...) are
 * different situations for the picker: the former falls back to a normal whole-gallery download
 * silently, the latter should tell the user why before doing anything. */
data class ListingResult(val items: List<GalleryItem>, val errorMessage: String? = null)

/** Whether a listing should land on DownloadPreviewSheet (quality/trim/format/commands controls)
 * rather than SharePickerScreen's picker grid — a single detected video, or two-or-more videos
 * anywhere in the listing regardless of how many non-video items sit alongside them. Shared by
 * every caller that has to make this same "which sheet does this link deserve" call before it even
 * knows what's in the link (ShareActivity's own share-sheet flow, MainScreen's paste-a-link flow)
 * so the two never quietly drift into deciding it differently. A single video mixed with photos
 * still goes to the picker: there's exactly one video either way, but excluding the photos needs
 * the picker's own per-item selection, which DownloadPreviewSheet's single-video card has no UI
 * for. */
fun ListingResult.shouldUsePreviewSheet(): Boolean {
    val videoItemCount = items.count { it.filename?.let(VideoSiteRouter::isVideoFilename) == true }
    return items.isNotEmpty() && ((items.size == 1 && videoItemCount == 1) || videoItemCount >= 2)
}

object GalleryDlListing {
    // gallery-dl's own Message.Url constant — stable across extractors, see gallery_dl/job.py.
    private const val MESSAGE_URL = 3
    // gallery-dl's own Message.Error-shaped entry: [-1, {"error": "...", "message": "..."}] — seen
    // live from an --dump-json run against a login-gated post ({"error": "AbortExtraction",
    // "message": "HTTP redirect to login page (...)"}), not otherwise documented as a stable
    // constant the way MESSAGE_URL is, but every real message type gallery-dl defines is positive
    // (Version/Directory/Url/...), so any negative type is reserved for exactly this.
    // Exposed so callers can tell a genuinely small gallery apart from one that got truncated —
    // a result exactly at this size might just be the cap kicking in, not the real total.
    const val MAX_ITEMS = 200
    // Matches gallery_dl_wrapper.py's list_items() — everything after this marker is a warning
    // gallery-dl logged while still successfully listing everything it *could* find (e.g. it
    // silently drops an individual carousel item it couldn't fetch media info for — a real file
    // going missing from the result with no exception raised to catch). Not shown to the user,
    // just logged, so a dropped item at least leaves a trace instead of vanishing without one.
    private const val WARNINGS_MARKER = "\n---GALLERY_DL_WARNINGS---\n"

    // A real error string from gallery-dl/yt-dlp is a short human sentence. Reproduced live
    // against a Reddit subreddit-index listing: gallery-dl's own exception message occasionally
    // *is* raw HTML/CSS from a blocked/redirected response instead (a whole Reddit stylesheet's
    // ":root{...}" custom-property block, hundreds of "--name:value;" declarations packed with no
    // spaces) — something upstream in gallery-dl's own error handling, not this app's JSON parsing
    // (see the Message.Error branch below, and the ERR: fallback, where this is applied — both are
    // gallery-dl's own stderr/exception text verbatim). Showing that verbatim in the picker's error
    // card is useless and looks broken, so anything implausibly long or shaped like markup instead
    // of a sentence gets swapped for a generic message. The full original text is still logged
    // (see the two call sites) for whenever this needs to be actually debugged.
    //
    // Not private: reproduced live a second time in DownloadWorker's own terminal-failure toast/DB
    // errorMessage — the *real download* attempt (not just this file's listing/preview step) can
    // hit the exact same gallery-dl behavior, on an entirely separate code path with its own error
    // capture, so this needs to be reusable there too rather than duplicated.
    private val MARKUP_LIKE_REGEX = Regex("""[{}<>]|--[a-zA-Z-]+:""")

    /** Same cookies.txt every real download reads (see DownloadWorker's own identical
     * exists()/length()>0 check for why "empty" counts as "absent") — but filtered through the
     * Cookies & Login screen's per-site toggle first, same as DownloadWorker's own per-download
     * normalized copy, so a preview never shows content a toggled-off site's cookies would have
     * unlocked when the real download wouldn't actually send them either. Writes a temp filtered
     * copy only when something's actually disabled; otherwise just hands back the real file's own
     * path, avoiding pointless I/O on every listing when nothing's toggled off. */
    private fun effectiveCookiesPath(context: Context): Pair<String, java.io.File?> {
        val cookiesPath = context.filesDir.resolve("cookies.txt")
        if (!cookiesPath.exists() || cookiesPath.length() <= 0) return "" to null
        val disabledDomains = GalleryDlPreferences.getDisabledCookieDomains(context)
        if (disabledDomains.isEmpty()) return cookiesPath.absolutePath to null
        val filtered = GalleryDlPreferences.filterCookiesByDisabledDomains(
            cookiesPath.readText().replace("\r\n", "\n"), disabledDomains,
        )
        // Unique per call, not a fixed name - two listing calls (e.g. a paste and a share-sheet
        // open) can genuinely run concurrently, and a shared filename would let one overwrite the
        // other mid-read. Returned back to the caller so it can be deleted after the Python run.
        val tempFile = java.io.File(context.cacheDir, "cookies-listing-filtered-${System.nanoTime()}.txt")
        tempFile.writeText(filtered)
        return tempFile.absolutePath to tempFile
    }

    /** Deletes per-run cookie copies left in cacheDir by runs that never reached their `finally`:
     * the listing copies above and DownloadWorker's `cookies-normalized-<id>.txt`. Both are
     * deleted in a `finally` normally, but not when the app process is killed mid-run (swiped
     * away, killed in the background, reinstalled) — dozens had built up. They hold live session
     * tokens, so they shouldn't outlive their run. Anything older than an hour goes, except a
     * download copy whose download is still RUNNING (a long download legitimately keeps its own).
     * Never reads the files. Called once per launch. */
    suspend fun sweepStaleCookieCopies(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        val stale = context.cacheDir.listFiles { f ->
            f.isFile && f.lastModified() < cutoff &&
                (f.name.startsWith("cookies-listing-filtered-") || f.name.startsWith("cookies-normalized-")) &&
                f.name.endsWith(".txt")
        } ?: return@withContext
        if (stale.isEmpty()) return@withContext
        val dao = com.comfort.app.data.AppDatabase.getDatabase(context).downloadDao()
        var deleted = 0
        for (file in stale) {
            if (file.name.startsWith("cookies-normalized-")) {
                val downloadId = file.name.removePrefix("cookies-normalized-").removeSuffix(".txt")
                if (dao.getById(downloadId)?.status == com.comfort.app.data.DownloadStatus.RUNNING) continue
            }
            if (file.delete()) deleted++
        }
        android.util.Log.i("GalleryDlListing", "deleted $deleted stale cookie copies")
    }

    /** Where a preview's full yt-dlp extraction for [url] is saved (yt_dlp_wrapper.py list_info's
     * info_cache_path) and where the real download looks for it (download()'s info_json_path) —
     * so tapping Download on a loaded preview doesn't extract the whole thing again. One file per
     * URL, replaced by each new preview; the wrapper ignores it once it's too old. */
    fun ytDlpInfoCacheFile(context: Context, url: String): java.io.File = previewCacheFile(context, "ytdlp-info", url)

    /** Same idea for Instaloader: the preview's fetched post, saved by instaloader_wrapper.py
     * list_items and loaded back by its download(), so the metadata request isn't made twice. */
    fun instaloaderInfoCacheFile(context: Context, url: String): java.io.File = previewCacheFile(context, "instaloader-info", url)

    private fun previewCacheFile(context: Context, dir: String, url: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return java.io.File(context.cacheDir, "$dir/$name.json")
    }

    // The last successful listing per URL, for a short while. A download started from a preview
    // re-lists the same link in DownloadWorker (item count + "is there a video in here?") — for a
    // gallery-dl link that meant fetching the whole gallery again right after the preview just did
    // (reported live as the download "starting all over"). Same 20-minute window the saved
    // yt-dlp/Instaloader extractions use. Only non-empty results are kept; failures always retry.
    private data class CachedListing(val result: ListingResult, val atMs: Long)
    private val listingCache = java.util.concurrent.ConcurrentHashMap<String, CachedListing>()
    private const val LISTING_CACHE_MS = 20 * 60 * 1000L

    fun sanitizeErrorMessage(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Structural markup (an actual HTML/CSS blob, real gallery-dl AbortExtraction garbage —
        // see this function's own callers) always shows it within the first 200 chars, so this
        // alone is a reliable signal on its own; length alone used to also count as "looks like
        // garbage" and get fully replaced by the generic line below — but a genuinely long, real,
        // plain-English error is common too (reproduced live: yt-dlp's own "Instagram sent an
        // empty media response... may need cookies..." message, 588 chars purely from its own
        // verbose "please file an issue"/"confirm you're on the latest version" boilerplate) —
        // full replacement threw away the one actually-informative sentence at the front of it.
        // Truncating instead keeps that sentence and only drops the boilerplate tail.
        if (MARKUP_LIKE_REGEX.containsMatchIn(trimmed.take(200))) {
            return "This site didn't return a usable response for this link — it may require login, or this URL might not be supported."
        }
        return if (trimmed.length > 400) trimmed.take(400).trimEnd() + "…" else trimmed
    }

    /** Enumerates the items behind [url] for the share-sheet picker. An empty result with no
     * [ListingResult.errorMessage] means the source genuinely can't be listed this way (single-file
     * links, unsupported extractors) — callers should fall back to a normal whole-gallery download
     * silently in that case. A non-null errorMessage means listing actually failed (needs login,
     * network error, ...) and should be shown, not silently swallowed into the same fallback. */
    suspend fun listItems(context: Context, url: String): ListingResult = withContext(Dispatchers.IO) {
        listingCache[url]?.let { cached ->
            if (System.currentTimeMillis() - cached.atMs < LISTING_CACHE_MS) return@withContext cached.result
        }
        listItemsUncached(context, url).also { result ->
            if (result.items.isNotEmpty()) listingCache[url] = CachedListing(result, System.currentTimeMillis())
        }
    }

    private suspend fun listItemsUncached(context: Context, url: String): ListingResult {
        if (VideoSiteRouter.resolveEngine(context, url) == DownloadEngine.INSTALOADER) {
            val result = listViaInstaloader(context, url)
            if (result.items.isNotEmpty()) return result
            // Same fallback the real download makes (DownloadWorker): Instaloader coming back
            // empty hands the link to the classic engines. Its own error is kept only if they
            // fail too — it's usually the clearer explanation for an Instagram post.
            val classic = listClassic(context, url)
            return if (classic.items.isEmpty() && result.errorMessage != null) result else classic
        }
        return listClassic(context, url)
    }

    private suspend fun listClassic(context: Context, url: String): ListingResult =
        when (VideoSiteRouter.classify(url)) {
            // Video-only sources (Reels, YouTube, ...) skip gallery-dl's listing entirely, same as
            // the real download does — gallery-dl either can't parse them at all, or (Instagram
            // Reels specifically) lists them fine but only with an internal "ytdl:"-prefixed
            // pseudo-URL as the item's own "url", which isn't a real fetchable preview image (see
            // yt_dlp_wrapper.py's list_info() doc comment). yt-dlp's own extractor already resolves
            // a real thumbnail as part of normal metadata extraction.
            DownloadEngine.YT_DLP -> listViaYtDlp(context, url)
            DownloadEngine.SPOTIFY -> listViaSpotify(context, url)
            DownloadEngine.GALLERY_DL -> {
                val result = listViaGalleryDl(context, url)
                if (result.items.isNotEmpty()) {
                    // Only worth the extra process + network round trip when there's actually a
                    // video item to fix a thumbnail for - most gallery-dl sources are
                    // image-only galleries and never hit this at all. Enrichment failing (auth,
                    // network, ...) is treated as a soft miss, not surfaced as an error - the
                    // primary listing already succeeded, so there's a real gallery to show; the
                    // affected item(s) just keep gallery-dl's own unfetchable placeholder URL
                    // instead of a real thumbnail.
                    if (result.items.any { it.filename?.let(VideoSiteRouter::isVideoFilename) == true }) {
                        result.copy(items = enrichVideoThumbnails(context, url, result.items))
                    } else {
                        result
                    }
                } else {
                    // gallery-dl found 0 items - the real download (DownloadWorker) tries yt-dlp
                    // as a fallback whenever gallery-dl saves 0 items, regardless of whether
                    // gallery-dl threw an error (like "Unsupported URL") or just came back empty.
                    // The preview needs to match that, otherwise a site unknown to gallery-dl but
                    // supported by yt-dlp (like xnxx) will fail in preview but work in download.
                    val fallback = listViaYtDlp(context, url)
                    if (fallback.items.isNotEmpty()) {
                        fallback
                    } else {
                        // Both failed. If gallery-dl had an explicit error (like "Login required"),
                        // keep it rather than yt-dlp's downstream error, as it's usually the root
                        // cause for domains primarily handled by gallery-dl.
                        if (result.errorMessage != null) result else fallback
                    }
                }
            }
            // Never produced by classify() — only by resolveEngine(), handled in listItems().
            DownloadEngine.INSTALOADER -> ListingResult(emptyList())
        }

    /** instaloader_wrapper.py's `list` prints one line of JSON in gallery-dl's own --dump-json
     * shape (see that file's doc comment), so the existing gallery-dl parser reads it unchanged —
     * same item numbering, same video detection by extension, same [-1, {...}] error entry. */
    private suspend fun listViaInstaloader(context: Context, url: String): ListingResult {
        val (cookiesArg, tempCookieFile) = effectiveCookiesPath(context)
        try {
            val lines = mutableListOf<String>()
            val lastLine = runCatching {
                PythonRuntime.run(
                    context, "instaloader_wrapper.py",
                    listOf(
                        "list", url, cookiesArg,
                        GalleryDlPreferences.getEffectiveProxyUrl(context),
                        GalleryDlPreferences.getEffectiveSocketTimeoutSeconds(context),
                        instaloaderInfoCacheFile(context, url).absolutePath,
                    ),
                ) { line -> lines.add(line) }
                // Last non-blank line only, same reasoning as runYtDlpListInfo: anything the
                // interpreter printed to stderr first (merged into this stream) isn't the JSON.
                lines.lastOrNull { it.isNotBlank() }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return ListingResult(emptyList())
            return parseGalleryDlItems(lastLine, url)
        } finally {
            tempCookieFile?.delete()
        }
    }

    private suspend fun listViaGalleryDl(context: Context, url: String): ListingResult {
        val extraArgs = GalleryDlPreferences.getExtraArgs(context)

        val (cookiesArg, tempCookieFile) = effectiveCookiesPath(context)
        try {
            // list_items() only ever prints once (see gallery_dl_wrapper.py's __main__), but that one
            // print can itself contain embedded newlines (the JSON text, plus the warnings marker) -
            // PythonRuntime.run() delivers it back one line at a time, so it has to be rejoined into
            // the single block of text list_items() originally returned before parsing it as JSON.
            val lines = mutableListOf<String>()
            val rawText = runCatching {
                PythonRuntime.run(context, "gallery_dl_wrapper.py", listOf("list_items", url, cookiesArg, extraArgs)) { line ->
                    lines.add(line)
                }
                lines.joinToString("\n")
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()?.takeIf { it.isNotBlank() } ?: return ListingResult(emptyList())
    
            // gallery_dl_wrapper.py's own "nothing on stdout" fallback - whatever gallery-dl said on
            // stderr (auth required, unsupported URL, network error, ...), prefixed so this side can
            // tell "genuinely nothing to list" (plain empty string, falls through to the JSON parse
            // below and comes back as no items/no error) apart from "listing actually failed and here's
            // why". Checked before the JSON parse, not as a parse-failure fallback - an unrelated JSON
            // bug should never get silently reinterpreted as this specific error path.
            if (rawText.startsWith("ERR:")) {
                val message = rawText.removePrefix("ERR:").trim()
                // Diagnostic only - this whole branch is gallery-dl's own stderr verbatim (see
                // gallery_dl_wrapper.py's list_items()), which occasionally turns out to be raw
                // HTML/CSS from a blocked/redirected response rather than a real error string (seen
                // live against a Reddit listing) - logged in full so that's visible in logcat instead
                // of only ever showing up truncated in the picker's own error card.
                android.util.Log.w("GalleryDlListing", "gallery-dl ERR: fallback while listing $url:\n$message")
                return ListingResult(emptyList(), errorMessage = sanitizeErrorMessage(message))
            }
    
            val markerIndex = rawText.indexOf(WARNINGS_MARKER)
            val jsonText: String
            if (markerIndex >= 0) {
                jsonText = rawText.substring(0, markerIndex)
                val warnings = rawText.substring(markerIndex + WARNINGS_MARKER.length).trim()
                if (warnings.isNotBlank()) {
                    android.util.Log.w("GalleryDlListing", "gallery-dl reported warnings while listing $url:\n$warnings")
                }
            } else {
                jsonText = rawText
            }
    
            return parseGalleryDlItems(jsonText, url)
        } finally {
            tempCookieFile?.delete()
        }
    }

    private fun parseGalleryDlItems(jsonText: String, url: String): ListingResult {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return ListingResult(emptyList())

        return runCatching {
            val root = JSONArray(trimmed)
            val items = mutableListOf<GalleryItem>()
            var errorMessage: String? = null
            for (i in 0 until root.length()) {
                if (items.size >= MAX_ITEMS) break
                val entry = root.optJSONArray(i) ?: continue
                val messageType = entry.optInt(0, MESSAGE_URL)
                if (messageType < 0 && errorMessage == null && entry.length() >= 2) {
                    val errorObj = entry.optJSONObject(1)
                    errorMessage = errorObj?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: errorObj?.optString("error")?.takeIf { it.isNotBlank() }
                    continue
                }
                if (entry.length() < 2 || messageType != MESSAGE_URL) continue
                val fileUrl = entry.optString(1, "").ifBlank { null } ?: continue
                val keywords = entry.optJSONObject(2)
                val num = keywords?.optInt("num", items.size + 1) ?: (items.size + 1)
                // gallery-dl reports these as two separate keywords — "filename" never has a
                // dot-extension suffix on its own — so a bare filename check (isVideoFilename)
                // always misses every item unless the extension is folded back in here.
                val rawFilename = keywords?.optString("filename")?.takeIf { it.isNotBlank() }
                val extension = keywords?.optString("extension")?.takeIf { it.isNotBlank() }
                val filename = when {
                    rawFilename == null -> null
                    extension == null || rawFilename.endsWith(".$extension", ignoreCase = true) -> rawFilename
                    else -> "$rawFilename.$extension"
                }
                // Which keyword actually holds a human-readable title varies a lot by extractor
                // (Reddit posts use "title", Instagram/Twitter only have a caption-style
                // "description"/"content") — tried in priority order, first non-blank wins. Kept
                // short: this renders as a caption under a picker thumbnail, not a headline.
                val title = listOf("title", "content", "description")
                    .firstNotNullOfOrNull { key -> keywords?.optString(key)?.trim()?.takeIf { it.isNotBlank() } }
                    ?.let { if (it.length > 120) it.take(120).trimEnd() + "…" else it }
                items.add(GalleryItem(num, fileUrl, filename, title, listIndex = items.size))
            }
            // Real items found despite an error entry also being present (a partial failure) still
            // count as a usable listing — only surface the error when there's nothing else to show.
            if (items.isEmpty() && errorMessage != null) {
                android.util.Log.w("GalleryDlListing", "gallery-dl Message.Error while listing $url:\n$errorMessage")
            }
            ListingResult(items, errorMessage = errorMessage?.takeIf { items.isEmpty() }?.let(::sanitizeErrorMessage))
        }.getOrElse {
            // A JSON parse failure here means gallery-dl's own --dump-json output wasn't valid
            // JSON at all — worth seeing what it actually was (the raw text this app's own error
            // card ends up not showing, since a parse failure just falls back to "nothing to
            // list" below) rather than only ever seeing this as a silent empty result.
            android.util.Log.w("GalleryDlListing", "Failed to parse gallery-dl JSON while listing $url:\n$trimmed", it)
            ListingResult(emptyList())
        }
    }

    /** Runs yt-dlp's own metadata-only extraction on [url] directly — used for sources
     * VideoSiteRouter already routes entirely to yt-dlp for the real download too, so this listing
     * matches what actually gets fetched. Almost always a single item; some of these hosts
     * (YouTube playlists, Twitter threads) can still return several. */
    private suspend fun listViaYtDlp(context: Context, url: String): ListingResult {
        // Unlike gallery-dl's routing (where "nothing usable came back" can legitimately mean "this
        // extractor just doesn't support listing"), every URL VideoSiteRouter sends here is always
        // meant to produce a real listing — runYtDlpListInfo() returning null only ever means the
        // subprocess itself failed or its output couldn't be parsed, a genuine failure that
        // deserves the same "tell the user why" treatment as an {"error": ...} JSON result, not the
        // silent whole-gallery-download fallback (reproduced live: an expired-cookie listing failed
        // this way and the picker sheet just flashed and vanished into a download doomed to fail
        // the same way a moment later).
        val info = runYtDlpListInfo(context, url)
            ?: return ListingResult(emptyList(), errorMessage = "Couldn't check this link — the download may still work, but its content couldn't be previewed.")
        val entries = info.optJSONArray("entries")
        if (entries != null) {
            val items = mutableListOf<GalleryItem>()
            for (i in 0 until entries.length()) {
                if (items.size >= MAX_ITEMS) break
                val entry = entries.optJSONObject(i) ?: continue
                items.add(entryToGalleryItem(entry, items.size + 1, listIndex = items.size))
            }
            return ListingResult(items)
        }
        val error = info.optString("error", "").takeIf { it.isNotBlank() }
        if (error != null) return ListingResult(emptyList(), errorMessage = sanitizeErrorMessage(error))
        return ListingResult(listOf(entryToGalleryItem(info, 1, listIndex = 0)))
    }

    /** Same shape as listViaYtDlp above — spotify_wrapper.py's list_info() returns the identical
     * JSON contract (see its own doc comment) specifically so this reuse needs no changes. The
     * only difference: items get a synthetic ".m4a" filename instead of ".mp4", so
     * VideoSiteRouter.isVideoFilename() correctly reports these as *not* video — Spotify links are
     * always audio, so the preview sheet's quality/resolution picker should never show for one. */
    private suspend fun listViaSpotify(context: Context, url: String): ListingResult {
        val info = runSpotifyListInfo(context, url)
            ?: return ListingResult(emptyList(), errorMessage = "Couldn't check this Spotify link — the download may still work, but its content couldn't be previewed.")
        val entries = info.optJSONArray("entries")
        if (entries != null) {
            val items = mutableListOf<GalleryItem>()
            for (i in 0 until entries.length()) {
                if (items.size >= MAX_ITEMS) break
                val entry = entries.optJSONObject(i) ?: continue
                items.add(spotifyEntryToGalleryItem(entry, items.size + 1, listIndex = items.size))
            }
            return ListingResult(items)
        }
        val error = info.optString("error", "").takeIf { it.isNotBlank() }
        if (error != null) return ListingResult(emptyList(), errorMessage = sanitizeErrorMessage(error))
        return ListingResult(listOf(spotifyEntryToGalleryItem(info, 1, listIndex = 0)))
    }

    private fun spotifyEntryToGalleryItem(entry: JSONObject, num: Int, listIndex: Int): GalleryItem {
        val thumbnail = entry.optString("thumbnail", "").ifBlank { null } ?: ""
        val title = entry.optString("title", "").trim().ifBlank { null }
            ?.let { if (it.length > 120) it.take(120).trimEnd() + "…" else it }
        return GalleryItem(num, thumbnail, "$num.m4a", title, listIndex = listIndex)
    }

    private suspend fun runSpotifyListInfo(context: Context, url: String, onStatus: ((String) -> Unit)? = null): JSONObject? {
        val extraArgs = GalleryDlPreferences.getExtraArgs(context)
        val jsRuntimeArg = QuickJsRuntime.getExecutablePath(context).orEmpty()

        val (cookiesArg, tempCookieFile) = effectiveCookiesPath(context)
        try {
            val lines = mutableListOf<String>()
            val lastLine = runCatching {
                PythonRuntime.run(context, "spotify_wrapper.py", listOf("list", url, cookiesArg, extraArgs, jsRuntimeArg)) { line ->
                    lines.add(line)
                    if (line.startsWith("[status] ")) onStatus?.invoke(line.removePrefix("[status] "))
                }
                lines.lastOrNull { it.isNotBlank() }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null
    
            return runCatching { JSONObject(lastLine.trim()) }.getOrElse {
                android.util.Log.w("GalleryDlListing", "Failed to parse Spotify JSON while listing $url (all ${lines.size} lines):\n${lines.joinToString("\n")}", it)
                null
            }
        } finally {
            tempCookieFile?.delete()
        }
    }

    private fun entryToGalleryItem(entry: JSONObject, num: Int, listIndex: Int): GalleryItem {
        val thumbnail = entry.optString("thumbnail", "").ifBlank { null } ?: ""
        val title = entry.optString("title", "").trim().ifBlank { null }
            ?.let { if (it.length > 120) it.take(120).trimEnd() + "…" else it }
        // Synthetic filename purely so the existing isVideoFilename() extension check (shared with
        // the gallery-dl path below, and with SharePickerScreen's own video/quality-picker gating)
        // recognizes this as a video — the real download never uses this filename, only the
        // picker's video detection does.
        return GalleryItem(num, thumbnail, "$num.mp4", title, listIndex = listIndex)
    }

    /** For a gallery-dl-sourced carousel that contains a video item: fetches yt-dlp's own listing
     * of the *same* post URL and swaps in its real per-item thumbnail for gallery-dl's video items,
     * which otherwise carry gallery-dl's own internal "ytdl:" pseudo-URL as their "url" (renders
     * blank when Coil tries to load it — see yt_dlp_wrapper.py's list_info() doc comment for the
     * full story). The two engines don't share an item-numbering scheme, so items are correlated
     * purely by *order among the video items themselves* — both engines enumerate the same post
     * top-to-bottom, so the Nth video gallery-dl found should be the Nth video yt-dlp found too. A
     * mismatch (yt-dlp failing entirely, or the counts not lining up) just leaves the original
     * items untouched rather than guessing wrong. */
    private suspend fun enrichVideoThumbnails(context: Context, url: String, items: List<GalleryItem>): List<GalleryItem> {
        val info = runYtDlpListInfo(context, url) ?: return items
        val ytEntries = info.optJSONArray("entries")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } ?: listOfNotNull(info.takeIf { !it.has("error") })
        val ytThumbnails = ytEntries.mapNotNull { entry -> entry.optString("thumbnail", "").ifBlank { null } }
        if (ytThumbnails.isEmpty()) return items

        var videoIndex = 0
        return items.map { item ->
            val isVideo = item.filename?.let(VideoSiteRouter::isVideoFilename) == true
            if (!isVideo) return@map item
            val thumbnail = ytThumbnails.getOrNull(videoIndex)
            videoIndex++
            if (thumbnail != null) item.copy(url = thumbnail) else item
        }
    }

    /** Title/uploader/thumbnail for the download preview sheet's card, from the same yt-dlp
     * listing pass the share picker already runs — so showing the sheet costs no extra extraction
     * beyond what a preview already did. Null for anything that fails to list (an unsupported
     * link, a login-gated post, no network): the sheet just shows its placeholder card and the
     * download itself still goes ahead, since a preview failing is not a reason to block it. */
    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

    suspend fun fetchPreviewInfo(context: Context, url: String, onStatus: ((String) -> Unit)? = null): PreviewInfo? = withContext(Dispatchers.IO) {
        // GALLERY_DL is the real download engine for a multi-item Instagram/TikTok *post* (not a
        // reel — see VideoSiteRouter.classify), so its checklist has to come from gallery-dl's own
        // listing/numbering too, not yt-dlp's. yt-dlp's own listing for these hosts only ever
        // enumerates the video items (never the photos) — using it here silently dropped every
        // photo from the checklist, and worse, its per-item "num" never corresponded to gallery-dl's
        // own numbering of the same post, so deselecting a video risked telling gallery-dl's real
        // --filter to keep/drop the wrong items entirely (reproduced live: missing photo thumbnails
        // is what surfaced this). See fetchGalleryDlPreviewInfo's own doc comment for the rest.
        // Instaloader-routed Instagram posts too: their checklist numbering is the same 1-based
        // carousel order gallery-dl uses, and listItems() already handles their fallback.
        if (VideoSiteRouter.classify(url) == DownloadEngine.GALLERY_DL ||
            VideoSiteRouter.resolveEngine(context, url) == DownloadEngine.INSTALOADER
        ) {
            return@withContext fetchGalleryDlPreviewInfo(context, url)
        }
        val json = when (VideoSiteRouter.classify(url)) {
            DownloadEngine.SPOTIFY -> runSpotifyListInfo(context, url, onStatus)
            else -> runYtDlpListInfo(context, url, onStatus)
        } ?: return@withContext null
        // A multi-item source comes back as {"entries": [...]} — the sheet previews one download,
        // so the first entry that actually resolved stands in for it.
        val entriesArray = json.optJSONArray("entries")
        val entry = entriesArray?.let { entries ->
            (0 until entries.length())
                .mapNotNull { entries.optJSONObject(it) }
                .firstOrNull { !it.optString("title").isNullOrBlank() || !it.optString("thumbnail").isNullOrBlank() }
        } ?: json
        val requestedFormats = entry.optJSONArray("requested_formats")
        val streamUrls = if (requestedFormats != null && requestedFormats.length() > 0) {
            (0 until requestedFormats.length()).mapNotNull {
                requestedFormats.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotBlank() }
            }
        } else {
            entry.optString("url").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        }

        // Every entry (not just the first "usable" one above) becomes a TrackPreview when this is
        // a multi-item listing — num is positional (see TrackPreview's own doc comment), so an
        // entry can never be skipped here without silently shifting every later track's number and
        // selecting the wrong song once a checkbox filter is built from it.
        val collectionThumbnail = json.optString("collection_thumbnail").blankToNull()
        val tracks = entriesArray?.let { entries ->
            (0 until entries.length()).mapNotNull { i -> entries.optJSONObject(i) }.mapIndexed { i, e ->
                TrackPreview(
                    num = i + 1,
                    title = e.optString("title").blankToNull(),
                    artist = (e.optString("artist").blankToNull() ?: e.optString("uploader").blankToNull()),
                    durationMs = e.optDouble("duration", -1.0).takeIf { it > 0.0 }?.let { (it * 1000).toLong() },
                    // Real per-track art when the engine actually provides one (YouTube Music
                    // playlists do); Spotify never does (see TrackPreview's own doc comment), so
                    // every row falls back to the same collection cover rather than showing a
                    // blank/placeholder box for something that does have a real image, just not
                    // a per-track one.
                    thumbnail = e.optString("thumbnail").blankToNull() ?: collectionThumbnail,
                )
            }
        } ?: emptyList()

        PreviewInfo(
            title = entry.optString("title").blankToNull(),
            uploader = entry.optString("uploader").blankToNull(),
            thumbnail = entry.optString("thumbnail").blankToNull(),
            filesizeBytes = entry.optLong("filesize", 0L).takeIf { it > 0L } ?: entry.optLong("filesize_approx", 0L).takeIf { it > 0L },
            durationMs = entry.optDouble("duration", -1.0).takeIf { it > 0.0 }?.let { (it * 1000).toLong() },
            streamUrls = streamUrls,
            artist = (entry.optString("artist").blankToNull() ?: entry.optString("uploader").blankToNull()),
            album = entry.optString("album").blankToNull(),
            tracks = tracks,
            collectionTitle = json.optString("collection_title").blankToNull(),
            collectionArtist = json.optString("collection_artist").blankToNull(),
            collectionThumbnail = collectionThumbnail,
        )
    }

    /** The GALLERY_DL-engine counterpart to fetchPreviewInfo's own general (Spotify/yt-dlp) path —
     * builds the checklist from [listItems] instead, the exact same listing+numbering
     * SharePickerScreen/ShareRouter already use, so a "num" the user unchecks here is the same
     * "num" gallery-dl's own real --filter at download time understands. [GalleryItem.url] doubles
     * directly as the row's own thumbnail: for a plain photo item it already *is* the image, and
     * for a video item it's whatever [enrichVideoThumbnails] resolved it to during [listViaGalleryDl]
     * (a real yt-dlp-sourced thumbnail by position) — no separate per-item fetch needed either way.
     * Single-item listings (nothing to check between) fall back to the plain single-item VIDEO/
     * SONG_SINGLE card as before — this only ever returns a non-empty [PreviewInfo.tracks] when
     * there's a real multi-item choice to make. */
    private suspend fun fetchGalleryDlPreviewInfo(context: Context, url: String): PreviewInfo? {
        val result = listItems(context, url)
        val items = result.items
        if (items.isEmpty()) return null
        if (items.size == 1) {
            val only = items[0]
            return PreviewInfo(title = only.title, uploader = null, thumbnail = only.url, filesizeBytes = null, durationMs = null, streamUrls = listOf(only.url))
        }
        val tracks = items.map { item -> TrackPreview(num = item.num, title = item.title, artist = null, durationMs = null, thumbnail = item.url) }
        val first = items.first()
        return PreviewInfo(
            title = first.title,
            uploader = null,
            thumbnail = first.url,
            filesizeBytes = null,
            durationMs = null,
            streamUrls = emptyList(),
            tracks = tracks,
        )
    }

    private suspend fun runYtDlpListInfo(context: Context, url: String, onStatus: ((String) -> Unit)? = null): JSONObject? {
        val extraArgs = GalleryDlPreferences.getExtraArgs(context)
        // Same JS-challenge runtime the real download() call gets — without it, extraction on
        // sites that require solving one (Instagram, YouTube, ...) fails outright rather than
        // just returning fewer fields, which was silently sending every one of these listings
        // straight to the UNAVAILABLE/instant-whole-download fallback (reproduced live: the
        // picker sheet flashed and closed in under a second instead of showing anything).
        val jsRuntimeArg = QuickJsRuntime.getExecutablePath(context).orEmpty()
        // list_info() never had any impersonation wiring of its own until this — the only thing
        // that ever impersonated a *listing* request was the removed tls-client integration's own
        // unconditional reddit.com special-case, scoped to that one host regardless of this
        // setting. Passing the same global toggle download() already respects here too means a
        // preview can now succeed under the same condition a real download already does, instead
        // of never getting impersonation at all (reproduced live: with tls-client gone, a Reddit
        // download succeeded with this setting on while its own preview kept failing).
        val impersonateArg = if (GalleryDlPreferences.isImpersonateEnabled(context)) "1" else "0"


        val (cookiesArg, tempCookieFile) = effectiveCookiesPath(context)
        try {
            // list_info()'s own json.dumps() call is the *last* thing list()'s __main__ branch ever
            // prints (see yt_dlp_wrapper.py) - but PythonRuntime.run() merges the subprocess's stderr
            // into this same stream (redirectErrorStream(true)), and "no_warnings"/the try/except
            // inside list_info() only cover yt-dlp's own warnings/exceptions, not everything else that
            // can land on stderr first (a Python DeprecationWarning, curl_cffi/cffi's own startup
            // chatter, ...). Reproduced live with expired cookies: extra lines ahead of the real JSON
            // made the whole-string JSONObject() parse below throw, come back null with no error
            // message, and get silently treated as "nothing to list" - the picker sheet flashed and
            // disappeared into an instant whole-gallery download that just failed the same way a
            // moment later with no explanation. Taking only the *last* non-blank line sidesteps that:
            // whatever came before it on stderr doesn't matter, only the one guaranteed-last print
            // does.
            val lines = mutableListOf<String>()
            val lastLine = runCatching {
                PythonRuntime.run(
                    context, "yt_dlp_wrapper.py",
                    listOf("list", url, cookiesArg, extraArgs, jsRuntimeArg, impersonateArg, ytDlpInfoCacheFile(context, url).absolutePath),
                ) { line ->
                    lines.add(line)
                    if (line.startsWith("[status] ")) onStatus?.invoke(line.removePrefix("[status] "))
                }
                lines.lastOrNull { it.isNotBlank() }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null
    
            return runCatching { JSONObject(lastLine.trim()) }.getOrElse {
                android.util.Log.w("GalleryDlListing", "Failed to parse yt-dlp JSON while listing $url (all ${lines.size} lines):\n${lines.joinToString("\n")}", it)
                null
            }
        } finally {
            tempCookieFile?.delete()
        }
    }
}
