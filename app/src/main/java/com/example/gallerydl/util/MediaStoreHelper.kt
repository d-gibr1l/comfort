package com.example.gallerydl.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.gallerydl.data.GalleryDlPreferences
import java.io.File
import java.net.URLConnection

object MediaStoreHelper {

    private const val IMAGE_RELATIVE_DIR = "Pictures/Comfort"
    private const val VIDEO_RELATIVE_DIR = "Movies/Comfort"
    private const val AUDIO_RELATIVE_DIR = "Music/Comfort"

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
    fun saveMediaToGallery(context: Context, sourceFile: File): Uri? {
        val ext = sourceFile.name.substringAfterLast('.', "").lowercase()
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: URLConnection.guessContentTypeFromName(sourceFile.name)
            ?: when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                else -> "image/jpeg"
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

        val customTreeUri = GalleryDlPreferences.getDownloadLocationUri(context)
        if (customTreeUri != null) {
            saveToCustomTree(context, customTreeUri, sourceFile, mimeType)?.let { return it }
            // Permission revoked or the folder was deleted outside the app — fall back to the
            // default location rather than silently losing the download.
        }

        return saveToMediaStore(context, sourceFile, mimeType)
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

    private fun saveToMediaStore(context: Context, sourceFile: File, mimeType: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Real .mkv used to get flat-out rejected by MediaStore's own insert() with its honest
            // "video/x-matroska" type — try it as given first (correct on modern Android, avoiding
            // the DISPLAY_NAME/MIME_TYPE mismatch described in saveMediaToGallery's own comment),
            // and only fall back to lying about the type if that actually fails, keeping the
            // original fix's safety net for whatever device/OS combination still needs it.
            insertIntoMediaStore(context, sourceFile, mimeType)
                ?: if (mimeType == "video/x-matroska") insertIntoMediaStore(context, sourceFile, "video/mp4") else null
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
            val destFile = File(publicDir, sourceFile.name)
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
    // mismatched collection, so route each into its matching one.
    private fun collectionFor(mimeType: String): Pair<Uri, String> = when {
        mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to VIDEO_RELATIVE_DIR
        mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to AUDIO_RELATIVE_DIR
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to IMAGE_RELATIVE_DIR
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
        return uri
    }
}
