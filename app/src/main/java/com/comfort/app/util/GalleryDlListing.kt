package com.comfort.app.util

import android.content.Context
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoSiteRouter
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
)

/** [items] is only ever non-empty when [errorMessage] is null and vice versa — a genuinely empty
 * gallery (no error, nothing found) and a real failure (login required, network error, ...) are
 * different situations for the picker: the former falls back to a normal whole-gallery download
 * silently, the latter should tell the user why before doing anything. */
data class ListingResult(val items: List<GalleryItem>, val errorMessage: String? = null)

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

    fun sanitizeErrorMessage(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val looksLikeMarkup = trimmed.length > 400 || MARKUP_LIKE_REGEX.containsMatchIn(trimmed.take(200))
        if (!looksLikeMarkup) return trimmed
        return "This site didn't return a usable response for this link — it may require login, or this URL might not be supported."
    }

    /** Enumerates the items behind [url] for the share-sheet picker. An empty result with no
     * [ListingResult.errorMessage] means the source genuinely can't be listed this way (single-file
     * links, unsupported extractors) — callers should fall back to a normal whole-gallery download
     * silently in that case. A non-null errorMessage means listing actually failed (needs login,
     * network error, ...) and should be shown, not silently swallowed into the same fallback. */
    suspend fun listItems(context: Context, url: String): ListingResult = withContext(Dispatchers.IO) {
        when (VideoSiteRouter.classify(url)) {
            // Video-only sources (Reels, TikTok, YouTube, ...) skip gallery-dl's listing entirely,
            // same as the real download does — gallery-dl either can't parse them at all, or
            // (Instagram Reels specifically) lists them fine but only with an internal
            // "ytdl:"-prefixed pseudo-URL as the item's own "url", which isn't a real fetchable
            // preview image (see yt_dlp_wrapper.py's list_info() doc comment). yt-dlp's own
            // extractor already resolves a real thumbnail as part of normal metadata extraction.
            DownloadEngine.YT_DLP -> listViaYtDlp(context, url)
            DownloadEngine.GALLERY_DL -> {
                val result = listViaGalleryDl(context, url)
                when {
                    result.items.isNotEmpty() -> {
                        // Only worth the extra process + network round trip when there's actually a
                        // video item to fix a thumbnail for — most gallery-dl sources are
                        // image-only galleries and never hit this at all. Enrichment failing (auth,
                        // network, ...) is treated as a soft miss, not surfaced as an error — the
                        // primary listing already succeeded, so there's a real gallery to show; the
                        // affected item(s) just keep gallery-dl's own unfetchable placeholder URL
                        // instead of a real thumbnail.
                        if (result.items.any { it.filename?.let(VideoSiteRouter::isVideoFilename) == true }) {
                            result.copy(items = enrichVideoThumbnails(context, url, result.items))
                        } else {
                            result
                        }
                    }
                    result.errorMessage != null -> result
                    // gallery-dl found nothing to list and reported no error — the real download
                    // (DownloadWorker) tries yt-dlp as a fallback whenever gallery-dl saves 0
                    // items, regardless of source, so the picker's own preview needs to match that
                    // instead of silently declaring this UNAVAILABLE and firing an instant
                    // whole-gallery download that's just headed for the exact same fallback a
                    // moment later with zero visible feedback first (reproduced live: a Reddit
                    // video post's gallery-dl listing came back empty with no error, the sheet
                    // flashed and vanished with no thumbnail and no user action, and the yt-dlp
                    // fallback that runs at download time failed with a real, explainable error —
                    // "Your IP address is unable to access the Reddit API" — the user never saw).
                    else -> listViaYtDlp(context, url)
                }
            }
        }
    }

    private suspend fun listViaGalleryDl(context: Context, url: String): ListingResult {
        val cookiesPath = context.filesDir.resolve("cookies.txt")
        // length() > 0, not just exists() — an empty cookies.txt (reproduced live: a corrupted
        // 0-byte file) still "exists" but gallery-dl/yt-dlp both hard-reject it as not looking like
        // a real Netscape cookies file, which used to fail every download outright even though a
        // *missing* cookies file downloads just fine anonymously. Treating "empty" the same as
        // "absent" here means a bad cookies file degrades to normal anonymous behavior instead of
        // breaking every download regardless of whether that particular site even needs cookies.
        val cookiesArg = if (cookiesPath.exists() && cookiesPath.length() > 0) cookiesPath.absolutePath else ""
        val extraArgs = GalleryDlPreferences.getExtraArgs(context)

        // list_items() only ever prints once (see gallery_dl_wrapper.py's __main__), but that one
        // print can itself contain embedded newlines (the JSON text, plus the warnings marker) —
        // PythonRuntime.run() delivers it back one line at a time, so it has to be rejoined into
        // the single block of text list_items() originally returned before parsing it as JSON.
        val lines = mutableListOf<String>()
        val rawText = runCatching {
            PythonRuntime.run(context, "gallery_dl_wrapper.py", listOf("list_items", url, cookiesArg, extraArgs)) { line ->
                lines.add(line)
            }
            lines.joinToString("\n")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return ListingResult(emptyList())

        // gallery_dl_wrapper.py's own "nothing on stdout" fallback — whatever gallery-dl said on
        // stderr (auth required, unsupported URL, network error, ...), prefixed so this side can
        // tell "genuinely nothing to list" (plain empty string, falls through to the JSON parse
        // below and comes back as no items/no error) apart from "listing actually failed and here's
        // why". Checked before the JSON parse, not as a parse-failure fallback — an unrelated JSON
        // bug should never get silently reinterpreted as this specific error path.
        if (rawText.startsWith("ERR:")) {
            val message = rawText.removePrefix("ERR:").trim()
            // Diagnostic only — this whole branch is gallery-dl's own stderr verbatim (see
            // gallery_dl_wrapper.py's list_items()), which occasionally turns out to be raw
            // HTML/CSS from a blocked/redirected response rather than a real error string (seen
            // live against a Reddit listing) — logged in full so that's visible in logcat instead
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
    suspend fun fetchPreviewInfo(context: Context, url: String): PreviewInfo? = withContext(Dispatchers.IO) {
        val json = runYtDlpListInfo(context, url) ?: return@withContext null
        // A multi-item source comes back as {"entries": [...]} — the sheet previews one download,
        // so the first entry that actually resolved stands in for it.
        val entry = json.optJSONArray("entries")?.let { entries ->
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

        PreviewInfo(
            title = entry.optString("title").takeIf { it.isNotBlank() && it != "null" },
            uploader = entry.optString("uploader").takeIf { it.isNotBlank() && it != "null" },
            thumbnail = entry.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" },
            filesizeBytes = entry.optLong("filesize", 0L).takeIf { it > 0L } ?: entry.optLong("filesize_approx", 0L).takeIf { it > 0L },
            durationMs = entry.optDouble("duration", -1.0).takeIf { it > 0.0 }?.let { (it * 1000).toLong() },
            streamUrls = streamUrls,
        )
    }

    private suspend fun runYtDlpListInfo(context: Context, url: String): JSONObject? {
        val cookiesPath = context.filesDir.resolve("cookies.txt")
        // length() > 0, not just exists() — an empty cookies.txt (reproduced live: a corrupted
        // 0-byte file) still "exists" but gallery-dl/yt-dlp both hard-reject it as not looking like
        // a real Netscape cookies file, which used to fail every download outright even though a
        // *missing* cookies file downloads just fine anonymously. Treating "empty" the same as
        // "absent" here means a bad cookies file degrades to normal anonymous behavior instead of
        // breaking every download regardless of whether that particular site even needs cookies.
        val cookiesArg = if (cookiesPath.exists() && cookiesPath.length() > 0) cookiesPath.absolutePath else ""
        val extraArgs = GalleryDlPreferences.getExtraArgs(context)
        // Same JS-challenge runtime the real download() call gets — without it, extraction on
        // sites that require solving one (Instagram, YouTube, ...) fails outright rather than
        // just returning fewer fields, which was silently sending every one of these listings
        // straight to the UNAVAILABLE/instant-whole-download fallback (reproduced live: the
        // picker sheet flashed and closed in under a second instead of showing anything).
        val jsRuntimeArg = QuickJsRuntime.getExecutablePath(context).orEmpty()

        // list_info()'s own json.dumps() call is the *last* thing list()'s __main__ branch ever
        // prints (see yt_dlp_wrapper.py) — but PythonRuntime.run() merges the subprocess's stderr
        // into this same stream (redirectErrorStream(true)), and "no_warnings"/the try/except
        // inside list_info() only cover yt-dlp's own warnings/exceptions, not everything else that
        // can land on stderr first (a Python DeprecationWarning, curl_cffi/cffi's own startup
        // chatter, ...). Reproduced live with expired cookies: extra lines ahead of the real JSON
        // made the whole-string JSONObject() parse below throw, come back null with no error
        // message, and get silently treated as "nothing to list" — the picker sheet flashed and
        // disappeared into an instant whole-gallery download that just failed the same way a
        // moment later with no explanation. Taking only the *last* non-blank line sidesteps that:
        // whatever came before it on stderr doesn't matter, only the one guaranteed-last print
        // does.
        val lines = mutableListOf<String>()
        val lastLine = runCatching {
            PythonRuntime.run(context, "yt_dlp_wrapper.py", listOf("list", url, cookiesArg, extraArgs, jsRuntimeArg)) { line ->
                lines.add(line)
            }
            lines.lastOrNull { it.isNotBlank() }
        }.getOrNull() ?: return null

        return runCatching { JSONObject(lastLine.trim()) }.getOrElse {
            android.util.Log.w("GalleryDlListing", "Failed to parse yt-dlp JSON while listing $url (all ${lines.size} lines):\n${lines.joinToString("\n")}", it)
            null
        }
    }
}


