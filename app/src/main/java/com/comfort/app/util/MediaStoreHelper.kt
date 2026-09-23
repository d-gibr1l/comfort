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

object MediaStoreHelper {

    private const val IMAGE_RELATIVE_DIR = "Pictures/Comfort"
    private const val VIDEO_RELATIVE_DIR = "Movies/Comfort"
    private const val AUDIO_RELATIVE_DIR = "Music/Comfort"
    private const val OTHER_RELATIVE_DIR = "Download/Comfort"

    /** Whether the Uri a download saved still resolves to a real file — false once the user has
     * deleted it from their gallery (or the SAF folder) outside the app. Errors fail open (return
     * true) since wrongly flagging a still-live file as deleted is worse than occasionally missing
     * a real deletion. */
    fun exists(context: Context, uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return true
        return runCatching {
            if (uri.authority == MediaStore.AUTHORITY) {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } else {
                DocumentFile.fromSingleUri(context, uri)?.exists() ?: false
            }
        }.getOrDefault(true)
    }

    /** Copies [sourceFile] into the user's configured download location — a custom SAF folder if
     * one is set, otherwise the public Pictures/gallery-dl gallery folder — and returns its
     * content Uri. */
    fun saveMediaToGallery(context: Context, sourceFile: File, forceAudioMime: Boolean = false): Uri? {
        val ext = sourceFile.name.substringAfterLast('.', "").lowercase()
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
        // extension) would file it under Movies/Comfort as a "video" instead of
        // Music/Comfort — extension-only detection can't tell an audio-only webm apart from
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
                    // away the Music-folder placement this override exists for, but a download
                    // that succeeds into the wrong folder beats one that fails outright.
                    "audio/webm" -> insertIntoMediaStore(context, sourceFile, "video/webm")
                    else -> null
                }
        } else {
            // Pre-scoped-storage devices (API 24-28): write straight into the public dir, then index it.
            val (collection, relativeDir) = collectionFor(mimeType)
            @Suppress("DEPRECATION")
            val publicRoot = Environment.getExternalStoragePublicDirectory(
                when {
                    mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                    mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_PICTURES
                }
            )
            val publicDir = File(publicRoot, "Comfort")
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

    // gallery-dl posts (Instagram reels/carousels especially) can include video or audio-only
    // files alongside images — MediaStore rejects any of these MIME types inserted into a
    // mismatched collection, so route each into its matching one. The `else` branch used to fall
    // through straight to Images regardless of what the file actually was — fine while every
    // download really was a picture, video, or audio file, but this app also saves yt-dlp
    // subtitles (.vtt/.srt, "Download Subtitles" in Settings) and those aren't remotely an image.
    // On API 29+, MediaStore.Images.Media flatly rejects a "text/vtt" (or any non-image/*) insert
    // with an IllegalArgumentException — insertIntoMediaStore() below already treats a thrown
    // insert() the same as a null return, so this failed *silently*: the file was left orphaned in
    // cacheDir/gallery-dl-staging/ forever (never cleaned up, never counted as saved) with no
    // visible error anywhere. Anything that isn't image/video/audio now goes to the generic Files
    // collection instead, which accepts any MIME type.
    private fun collectionFor(mimeType: String): Pair<Uri, String> = when {
        mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to IMAGE_RELATIVE_DIR
        mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to VIDEO_RELATIVE_DIR
        mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to AUDIO_RELATIVE_DIR
        else -> MediaStore.Files.getContentUri("external") to OTHER_RELATIVE_DIR
    }

    private fun insertIntoMediaStore(context: Context, sourceFile: File, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val (collection, relativeDir) = collectionFor(mimeType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            }, null, null)
        }
        return uri
    }
}
