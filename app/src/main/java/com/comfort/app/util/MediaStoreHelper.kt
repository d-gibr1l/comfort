package com.comfort.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.comfort.app.data.GalleryDlPreferences
import java.io.File
import java.net.URLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object MediaStoreHelper {

    /** Where every download goes by default, sorted into [subfolderFor]'s folders. The Download
     * folder takes any file type (Music/ refuses anything that isn't audio, so a song's .lrc
     * lyrics couldn't sit beside it there), and galleries and music players still index the media
     * in it. */
    const val RELATIVE_DIR = "Download/Comfort"

    /** Where downloads went before they moved to [RELATIVE_DIR] — [organizeSavedFiles] moves them. */
    private val OLD_RELATIVE_DIRS = listOf("Pictures/Comfort/", "Movies/Comfort/", "Music/Comfort/", "Download/Comfort/")

    /** Renames files saved under the old naming to today's (see [OldNames.tidy]) — the poster
     * no longer repeated, no links, X files named after their poster rather than "twitter".
     * [urlFor] gives the link a file was downloaded from, for the poster of an X file. A rename
     * keeps the file's MediaStore row, so the Library's links keep working; only files this app
     * owns can be renamed. Returns how many were. */
    suspend fun tidyOldNames(context: Context, urlFor: suspend (String) -> String?): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val rows = mutableListOf<Pair<Long, String>>()
        runCatching {
            resolver.query(
                files,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf("$RELATIVE_DIR/%", context.packageName),
                null,
            )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { rows += c.getLong(0) to it } }
        }
        // An X file's poster, by its "[id]" — sidecars (.json) share the id but have no record.
        val idOf = Regex(""" \[([^\[\]]+)]""")
        val posters = HashMap<String, String>()
        for ((_, name) in rows) {
            if (!name.startsWith("twitter - ", ignoreCase = true)) continue
            val id = idOf.find(name)?.groupValues?.get(1) ?: continue
            if (id in posters) continue
            OldNames.xPoster(urlFor(name))?.let { posters[id] = it }
        }
        var renamed = 0
        for ((id, name) in rows) {
            val poster = idOf.find(name)?.groupValues?.get(1)?.let { posters[it] }
            val tidied = OldNames.tidy(name, poster) ?: continue
            val uri = android.content.ContentUris.withAppendedId(files, id)
            // A same-named file already there makes the rename fail: try "name (1).ext" and so on.
            val dot = tidied.lastIndexOf('.')
            val base = if (dot > 0) tidied.substring(0, dot) else tidied
            val ext = if (dot > 0) tidied.substring(dot) else ""
            for (attempt in 0..20) {
                val candidate = if (attempt == 0) tidied else "$base ($attempt)$ext"
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, candidate) }
                if (runCatching { resolver.update(uri, values, null, null) }.getOrDefault(0) > 0) {
                    renamed++
                    break
                }
            }
        }
        return renamed
    }

    /** The [RELATIVE_DIR] subfolder a file goes in: by type, with sidecars beside what they
     * belong to — subtitles with the videos, .lrc lyrics with the songs, so players find them —
     * and info files (.json, .description) in Other. */
    internal fun subfolderFor(name: String, mimeType: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = mimeType.orEmpty()
        return when {
            ext == "lrc" -> "Music"
            ext == "srt" || ext == "vtt" -> "Videos"
            mime.startsWith("image/") -> "Pictures"
            mime.startsWith("video/") -> "Videos"
            mime.startsWith("audio/") -> "Music"
            else -> "Other"
        }
    }

    /** Moves downloads saved before the Download/Comfort layout (Pictures/, Movies/ and Music/
     * Comfort, or Download/Comfort's top level) into their [subfolderFor] folders. A MediaStore
     * move keeps each file's row, so the Library's links to them keep working. Only files this
     * app owns can be moved; others (saved by an earlier install) stay where they are. Returns
     * how many moved. */
    fun organizeSavedFiles(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val rows = mutableListOf<Triple<Long, String, String?>>()
        runCatching {
            resolver.query(
                files,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (${OLD_RELATIVE_DIRS.joinToString { "?" }}) AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                (OLD_RELATIVE_DIRS + context.packageName).toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { rows += Triple(c.getLong(0), it, c.getString(2)) }
            }
        }
        var moved = 0
        for ((id, name, mime) in rows) {
            val uri = android.content.ContentUris.withAppendedId(files, id)
            val target = "$RELATIVE_DIR/${subfolderFor(name, mime)}/"
            // A same-named file already there makes the move fail: try "name (1).ext" and so on.
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            for (attempt in 0..20) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, target)
                    if (attempt > 0) put(MediaStore.MediaColumns.DISPLAY_NAME, "$base ($attempt)$ext")
                }
                if (runCatching { resolver.update(uri, values, null, null) }.getOrDefault(0) > 0) {
                    moved++
                    break
                }
            }
        }
        return moved
    }

    /** Whether the Uri a download saved still resolves to a real file — false once the user has
     * deleted it from their gallery (or the SAF folder) outside the app. Errors fail open (return
     * true) since wrongly flagging a still-live file as deleted is worse than occasionally missing
     * a real deletion. [failOpen] false reverses that, for confirming a file is really back. */
    fun exists(context: Context, uriString: String, failOpen: Boolean = true): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return failOpen
        return runCatching {
            if (uri.authority == MediaStore.AUTHORITY) {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } else {
                DocumentFile.fromSingleUri(context, uri)?.exists() ?: false
            }
        }.getOrDefault(failOpen)
    }

    /** Copies [sourceFile] into the user's configured download location — a custom SAF folder if
     * one is set, otherwise Download/Comfort's subfolder for its type — and returns its
     * content Uri. */
    fun saveMediaToGallery(context: Context, sourceFile: File, forceAudioMime: Boolean = false): Uri? {
        // Whatever the engine left as this file's mtime (a server's Last-Modified = upload time,
        // for Redgifs) must not become the saved file's date: copies made by some gallery/profile
        // setups (Secure Folder) keep the source's timestamp rather than stamping a new one.
        sourceFile.setLastModified(System.currentTimeMillis())
        val ext = sourceFile.name.substringAfterLast('.', "").lowercase()
        if (ext == "mp4" || ext == "m4v" || ext == "mov") rewriteMp4CreationTime(sourceFile, System.currentTimeMillis())
        var mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: URLConnection.guessContentTypeFromName(sourceFile.name)
            ?: when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                // yt-dlp's own subtitle sidecar files ("Download Subtitles" in Settings) —
                // MimeTypeMap doesn't reliably recognize either on every Android version, and
                // falling all the way through this chain used to default to "image/jpeg" for
                // *anything* unmatched, which defeated collectionFor()'s own image/video/audio/
                // other split below: a subtitle "recognized" as an image still got routed into the
                // Images collection incorrectly, the exact failure this whole chain exists to fix.
                "vtt" -> "text/vtt"
                "srt" -> "application/x-subrip"
                // Honest "unknown binary" instead of the previous blanket "image/jpeg" guess — lets
                // collectionFor()'s own else branch (Files collection, accepts any type) correctly
                // catch whatever this still doesn't recognize, rather than silently mislabeling it.
                else -> "application/octet-stream"
            }
        // Overrides a video/* guess for a file the caller already knows is audio-only —
        // needed for Spotify's own opus/vorbis-sourced tracks, which this app's stripped
        // ffmpeg build can only remux into a bare .webm container (see yt_dlp_wrapper.py's
        // ACODECS remap and DownloadWorker.kt's own isAudioFile comment). Without this, such
        // a track's real MIME guess ("video/webm", MimeTypeMap's own mapping for the
        // extension) would index it as a video instead of a song, and send it to the video
        // folder setting — extension-only detection can't tell an audio-only webm apart from
        // a real video one, so the caller's own already-established audio/video knowledge
        // is trusted here instead.
        if (forceAudioMime && mimeType.startsWith("video/")) {
            mimeType = "audio/webm"
        }
        // Kept honest here (a real .mkv file reported as "video/x-matroska", not lied about) —
        // saveToMediaStore() itself falls back to "video/mp4" only if MediaStore actually rejects
        // the real type, rather than always lying about it. The previous unconditional override
        // (see "Fix MKV Android MediaStore rejection") was a real, necessary fix at the time — an
        // honest x-matroska insert really was getting rejected outright — but it silently
        // corrupted the DISPLAY_NAME/MIME_TYPE pairing for *every* .mkv save from then on: a file
        // genuinely named "*.mkv" registered with MediaStore as "video/mp4" reads as a mismatch
        // that some launchers/file managers resolve by appending the MIME-implied extension,
        // producing the "*.mkv.mp4" double-extension name reported live once MKV became a real,
        // visible user choice instead of an always-forced internal default nobody was looking at
        // closely. The file itself was never actually broken — it played fine — this was a
        // display-only symptom of the MIME lie.

        // Imported from YTDLnis's own separate music/video folder settings — checked first, each
        // falling back to the shared "download location" (then the built-in default) when unset,
        // so someone who only ever sets the one shared folder sees no change in behavior.
        val customTreeUri = when {
            mimeType.startsWith("audio/") -> GalleryDlPreferences.getAudioLocationUri(context)
                ?: GalleryDlPreferences.getDownloadLocationUri(context)
            mimeType.startsWith("video/") -> GalleryDlPreferences.getVideoLocationUri(context)
                ?: GalleryDlPreferences.getDownloadLocationUri(context)
            else -> GalleryDlPreferences.getDownloadLocationUri(context)
        }
        if (customTreeUri != null) {
            saveToCustomTree(context, customTreeUri, sourceFile, mimeType)?.let { return it }
            // Permission revoked or the folder was deleted outside the app — fall back to the
            // default location rather than silently losing the download.
        }

        return saveToMediaStore(context, sourceFile, mimeType)
    }

    /** Pulls the cover art embedded in an audio file's own metadata (ID3/MP4 "covr" atom/etc,
     * whatever mutagen wrote via yt_dlp_wrapper.py's EmbedThumbnail postprocessor) out to a
     * standalone cached image file, and returns a URI for it — or null if the file has no
     * embedded art, or isn't readable as media at all.
     *
     * Needed because DownloadWorker.kt's own thumbnail display just hands a download's saved
     * MediaStore Uri straight to Coil's AsyncImage: that works for a video Uri (Coil/Android can
     * pull a frame straight from it) but an audio Uri has no frame to pull — Coil has
     * nothing built in for "decode this MP4/M4A container's embedded album art as the image",
     * so it silently renders nothing. MediaMetadataRetriever.getEmbeddedPicture() is the standard
     * Android API for exactly this extraction; FileProvider (already declared in the manifest,
     * exposing all of cacheDir — see file_paths.xml) turns the resulting cache file back into a
     * content:// Uri so both Coil (in-process) and the Library screen's own share/open intents
     * (out-of-process) can read it the same way any other thumbnailPath already works. */
    fun extractAudioArtworkUri(context: Context, savedUri: Uri, downloadId: String): Uri? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, savedUri)
            val art = retriever.embeddedPicture ?: return null
            val dir = File(context.cacheDir, "audio_art").apply { mkdirs() }
            val file = File(dir, "$downloadId.jpg")
            file.writeBytes(art)
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun saveToCustomTree(context: Context, treeUri: Uri, sourceFile: File, mimeType: String): Uri? {
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!dir.canWrite()) return null

        val name = uniqueName(dir, sourceFile.name)
        val doc = dir.createFile(mimeType, name) ?: return null
        val copied = context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out) }
            true
        } ?: false
        if (!copied) {
            doc.delete()
            return null
        }
        return doc.uri
    }

    /** SAF doesn't dedupe display names the way MediaStore does, so avoid silently overwriting
     * an existing file when two items would otherwise land on the same name. */
    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        var candidate: String
        do {
            candidate = "$base ($index)$ext"
            index++
        } while (dir.findFile(candidate) != null)
        return candidate
    }

    /** Same dedup as [uniqueName] above, against a plain public directory instead of a
     * DocumentFile — for the legacy API 24-28 path in [saveToMediaStore], which (unlike API 29+'s
     * MediaStore insert() and this same uniqueName() on the SAF custom-folder path) used to write
     * straight to `File(publicDir, sourceFile.name)` with no existence check at all: a filename
     * collision silently overwrote the older file's bytes in place, then inserted a *second*
     * MediaStore row pointing at that same now-shared path — a duplicate gallery entry sitting on
     * top of clobbered content instead of its own. */
    private fun uniqueFile(dir: File, name: String): File {
        val candidate0 = File(dir, name)
        if (!candidate0.exists()) return candidate0
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        var candidate: File
        do {
            candidate = File(dir, "$base ($index)$ext")
            index++
        } while (candidate.exists())
        return candidate
    }

    private fun saveToMediaStore(context: Context, sourceFile: File, mimeType: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Real .mkv used to get flat-out rejected by MediaStore's own insert() with its honest
            // "video/x-matroska" type — try it as given first (correct on modern Android, avoiding
            // the DISPLAY_NAME/MIME_TYPE mismatch described in saveMediaToGallery's own comment),
            // and only fall back to lying about the type if that actually fails, keeping the
            // original fix's safety net for whatever device/OS combination still needs it.
            insertIntoMediaStore(context, sourceFile, mimeType)
                ?: when (mimeType) {
                    "video/x-matroska" -> insertIntoMediaStore(context, sourceFile, "video/mp4")
                    // "audio/webm" isn't a MIME MediaStore's Audio collection reliably accepts on
                    // every device (reproduced live: a Spotify opus/webm track's insert came back
                    // null, silently — see insertIntoMediaStore's own runCatching — turning a
                    // previously-working download into "No downloadable content found at this
                    // link"). Falling back to the plain, always-accepted "video/webm" guess trades
                    // away its indexing as a song, but a download that succeeds as a "video"
                    // beats one that fails outright.
                    "audio/webm" -> insertIntoMediaStore(context, sourceFile, "video/webm")
                    else -> null
                }
        } else {
            // Pre-scoped-storage devices (API 24-28): write straight into the public dir, then index it.
            val collection = legacyCollectionFor(mimeType)
            @Suppress("DEPRECATION")
            val publicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val publicDir = File(publicRoot, "Comfort/${subfolderFor(sourceFile.name, mimeType)}")
            if (!publicDir.exists()) publicDir.mkdirs()
            val destFile = uniqueFile(publicDir, sourceFile.name)
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, destFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
            }
            context.contentResolver.insert(collection, values)
        }
    }

    // Pre-Q only: the collection a file's row goes in, by type. MediaStore rejects a MIME type
    // inserted into a mismatched collection, and anything that isn't image/video/audio (yt-dlp's
    // .vtt/.srt subtitles) goes to the generic Files collection, which accepts any type.
    private fun legacyCollectionFor(mimeType: String): Uri = when {
        mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoMediaStore(context: Context, sourceFile: File, mimeType: String): Uri? {
        val resolver = context.contentResolver
        // The Downloads collection is the one that accepts Download/ paths, for any file type; the
        // media scanner still files each row as an image, video or song by its MIME type.
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$RELATIVE_DIR/${subfolderFor(sourceFile.name, mimeType)}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val copied = resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out) }
            true
        } ?: false
        if (!copied) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        // Clearing IS_PENDING has MediaStore index the file, which copies "date taken" out of its
        // own metadata when it has one (an MP4's creation_time, a photo's EXIF date): when the
        // uploader recorded/encoded it, not when it was downloaded. Galleries sort by that, so
        // such a download (seen with YouTube and Redgifs videos; most files carry no date) landed
        // months back among older items instead of with today's. Stamped after that update, since
        // indexing would overwrite it otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val takenAt = System.currentTimeMillis()
            stampDateTaken(resolver, uri, takenAt)
            // Indexing after IS_PENDING=0 isn't always finished by the time that returns (seen on
            // device: every download since the first fix had datetaken NULL, i.e. the stamp above
            // was overwritten by a later scan, and the gallery then fell back to the file's own
            // embedded creation date again). Re-check a few times and re-apply if it got reset.
            val appContext = context.applicationContext
            restampScope.launch {
                for (waitMs in RESTAMP_DELAYS_MS) {
                    delay(waitMs)
                    val current = appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)
                        ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
                    if (current != takenAt) stampDateTaken(appContext.contentResolver, uri, takenAt)
                }
            }
        }
        return uri
    }

    private val restampScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val RESTAMP_DELAYS_MS = longArrayOf(1_500, 4_000, 10_000)

    /** Rewrites an MP4/MOV's own creation/modification times (mvhd, tkhd, mdhd) to now, in place.
     * Redgifs and similar videos carry the upload date there, and MediaStore copies it into DATE_TAKEN
     * when indexing — which Secure Folder's MediaProvider then refuses to let us overwrite afterwards
     * (update() returns 0 rows), so the gallery showed the upload date. Only touches non-zero fields;
     * does nothing for anything that isn't a plain MP4 box structure. Staging file only (never the
     * user's copy), so editing in place is safe. */
    private fun rewriteMp4CreationTime(file: File, nowMs: Long) {
        runCatching {
            val secs = nowMs / 1000 + 2082844800L // Unix epoch -> MP4's 1904 epoch
            java.io.RandomAccessFile(file, "rw").use { f ->
                fun patch(start: Long, end: Long) {
                    var pos = start
                    while (pos + 8 <= end) {
                        f.seek(pos)
                        var size = f.readInt().toLong() and 0xFFFFFFFFL
                        val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
                        var header = 8L
                        if (size == 1L) { size = f.readLong(); header = 16L }
                        if (size == 0L) size = end - pos
                        if (size < header || pos + size > end) return
                        when (type) {
                            "moov", "trak", "mdia" -> patch(pos + header, pos + size)
                            "mvhd", "tkhd", "mdhd" -> {
                                val body = pos + header
                                f.seek(body)
                                val version = f.readUnsignedByte()
                                val fieldStart = body + 4
                                if (version == 1) {
                                    f.seek(fieldStart); f.writeLong(secs); f.writeLong(secs)
                                } else {
                                    f.seek(fieldStart); f.writeInt(secs.toInt()); f.writeInt(secs.toInt())
                                }
                            }
                        }
                        pos += size
                    }
                }
                patch(0, f.length())
            }
        }
    }

    private fun stampDateTaken(resolver: android.content.ContentResolver, uri: Uri, takenAt: Long) {
        runCatching {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            }, null, null)
        }
    }
}
