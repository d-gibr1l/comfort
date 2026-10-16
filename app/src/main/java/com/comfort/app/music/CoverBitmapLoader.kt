package com.comfort.app.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * The covers for the music notification, lock screen and widget: a song's artwork Uri is either
 * its pulled-out cover file or, when it has none, the song itself (Song.from) — for that one the
 * cover embedded in the file is read here. Media3's own loader only decodes image files.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CoverBitmapLoader(context: Context) : BitmapLoader {
    private val context = context.applicationContext
    private val executor: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> { decode(data) ?: throw IllegalArgumentException("Not an image") }

    override fun loadBitmap(uri: Uri, options: BitmapFactory.Options?): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> { load(uri) ?: throw IllegalArgumentException("No cover in $uri") }

    private fun load(uri: Uri): Bitmap? {
        val isSong = uri.authority == MediaStore.AUTHORITY ||
            runCatching { context.contentResolver.getType(uri) }.getOrNull()?.startsWith("audio/") == true
        val bytes = if (isSong) {
            android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    embeddedPicture
                } catch (e: Exception) {
                    null
                } finally {
                    release()
                }
            }
        } else {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        return bytes?.let(::decode)
    }

    /** At most ~512 px a side: the notification and lock screen don't need more. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
