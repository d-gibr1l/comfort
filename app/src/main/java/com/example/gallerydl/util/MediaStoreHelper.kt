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

    private const val RELATIVE_DIR = "Pictures/gallery-dl"

    /** Copies [sourceFile] into the user's configured download location — a custom SAF folder if
     * one is set, otherwise the public Pictures/gallery-dl gallery folder — and returns its
     * content Uri. */
    fun saveImageToGallery(context: Context, sourceFile: File): Uri? {
        val mimeType = URLConnection.guessContentTypeFromName(sourceFile.name) ?: "image/jpeg"

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
        val resolver = context.contentResolver

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val copied = resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (!copied) {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            // Pre-scoped-storage devices (API 24-28): write straight into the public dir, then index it.
            @Suppress("DEPRECATION")
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "gallery-dl")
            if (!publicDir.exists()) publicDir.mkdirs()
            val destFile = File(publicDir, sourceFile.name)
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, destFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, destFile.absolutePath)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }
    }
}
