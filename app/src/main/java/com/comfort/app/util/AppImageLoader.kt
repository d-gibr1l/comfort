package com.comfort.app.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.graphics.drawable.toDrawable
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.VideoFrameDecoder
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

object AppImageLoader {
    /** Coil's default OkHttp dispatcher caps at 5 concurrent requests per host, which throttles
     * the share picker badly — a dozen-plus preview thumbnails all come from the same CDN host,
     * so most of them just queue behind the first 5 instead of loading in parallel. */
    fun install(context: Context) {
        val client = OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 32
                    maxRequestsPerHost = 16
                }
            )
            .build()

        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .okHttpClient(client)
                .components {
                    // First in line for gallery (MediaStore) items — see MediaStoreThumbnailFetcher.
                    add(MediaStoreThumbnailFetcher.Factory())
                    // Registers frame extraction for video thumbnails (gallery-dl downloads are
                    // all images, but yt-dlp ones are video — without this decoder, a video's
                    // MediaStore thumbnail Uri just renders blank instead of a preview frame).
                    // Still the fallback below Android 10 and for anything the system thumbnail
                    // lookup can't serve.
                    add(VideoFrameDecoder.Factory())
                }
                .build()
        )
    }
}

/** Library thumbnails point at the real saved file's MediaStore Uri. Loading that through Coil's
 * normal path decodes the full-resolution photo — or, for a video, extracts a frame with
 * MediaMetadataRetriever — for every tile that scrolls into view, which is what made scrolling
 * the Library stutter while thumbnails were loading (reported live). ContentResolver.loadThumbnail
 * (Android 10+) returns the system's own cached, already-downscaled thumbnail at the size the tile
 * actually needs, for images and videos alike. Returning null on any failure hands the request
 * back to the normal fetch/decode path, so this can only make a thumbnail faster, never missing. */
private class MediaStoreThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // Thumbnail-sized requests only: an unknown or large target (a full-screen view) must get
        // the real full-quality image, so it goes back to the normal path.
        val width = options.size.width.pxOrElse { return null }
        val height = options.size.height.pxOrElse { return null }
        if (width <= 0 || height <= 0 || width > MAX_PX || height > MAX_PX) return null
        val bitmap = runCatching {
            options.context.contentResolver.loadThumbnail(uri, android.util.Size(width, height), null)
        }.getOrNull() ?: return null
        return DrawableResult(
            drawable = bitmap.toDrawable(options.context.resources),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                data.scheme == "content" && data.authority == MediaStore.AUTHORITY
            ) {
                MediaStoreThumbnailFetcher(data, options)
            } else {
                null
            }
    }

    private companion object {
        // A thumbnail, not a full-screen image — anything larger goes through the normal path.
        const val MAX_PX = 1024
    }
}
