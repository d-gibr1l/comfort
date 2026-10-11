package com.comfort.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File

/** "Save WebP pictures as JPG" (Settings > Processing): many sites serve pictures as WebP, which
 * some apps and older devices can't open or share. Decoded with Android's own decoder and saved as
 * a JPEG at high quality. */
object ImageConverter {

    /** Converts a still .webp to a .jpg beside it and deletes the original; returns the .jpg, or
     * [file] unchanged when it isn't a still WebP or anything goes wrong. An animated WebP is kept:
     * a JPEG would hold only its first frame. */
    fun webpToJpg(file: File): File {
        if (!file.extension.equals("webp", ignoreCase = true) || isAnimatedWebp(file)) return file
        val out = File(file.parentFile, file.nameWithoutExtension + ".jpg")
        return runCatching {
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return file
            // JPEG has no transparency: transparent pixels would turn black, so they go on white.
            val bitmap = if (decoded.hasAlpha()) {
                Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                    decoded.recycle()
                }
            } else decoded
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            if (out.length() > 0) {
                file.delete()
                out
            } else {
                out.delete()
                file
            }
        }.getOrElse {
            out.delete()
            file
        }
    }

    /** A WebP's extended header (VP8X chunk) carries an "animation" flag, bit 1 of its first byte. */
    private fun isAnimatedWebp(file: File): Boolean = runCatching {
        val header = ByteArray(21)
        file.inputStream().use { if (it.read(header) < header.size) return false }
        String(header, 12, 4, Charsets.US_ASCII) == "VP8X" && (header[20].toInt() and 0x02) != 0
    }.getOrDefault(false)
}
