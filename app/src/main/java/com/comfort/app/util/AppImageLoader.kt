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
import okio.buffer
import okio.source
import kotlinx.coroutines.launch
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
                    // A song's cover (AudioCover) — see AudioCoverFetcher.
                    add(AudioCoverFetcher.Factory())
                    add(AudioCoverFetcher.Keyer())
                    // Registers frame extraction for video thumbnails (gallery-dl downloads are
                    // all images, but yt-dlp ones are video — without this decoder, a video's
                    // MediaStore thumbnail Uri just renders blank instead of a preview frame).
                    // Still the fallback below Android 10 and for anything the system thumbnail
                    // lookup can't serve.
                    add(VideoFrameDecoder.Factory())
                }
                // Coil's default lets downloaded images (previews' and the queue's remote
                // thumbnails) take 2% of the phone's storage, up to 250 MB — it had grown to 150 MB.
                // Library thumbnails don't use it (MediaStoreThumbnailFetcher), so a smaller cache
                // only means an old preview's picture is fetched again.
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache"))
                        .maxSizeBytes(50L * 1024 * 1024)
                        .build()
                }
                .build()
        )
        // A cache already over that size (from before the limit) only shrinks once Coil next writes
        // to it; clear it instead, once, rather than leaving it at its old size until then.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val cache = Coil.imageLoader(context).diskCache ?: return@runCatching
                if (cache.size > cache.maxSize) cache.clear()
            }
        }
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

/**
 * A song's cover, for a Library or Home thumbnail. [storedArt] is the cover the download pulled out
 * of the file (MediaStoreHelper.extractAudioArtworkUri) — kept in the app's cache, so Android can
 * clear it (Settings > Storage > Clear cache, or when space runs low) — and [audioUri] the song.
 */
data class AudioCover(val audioUri: String?, val storedArt: String?)

/** What a download's thumbnail loads: its cover for a song (an AudioCover), else thumbnailPath. */
fun com.comfort.app.data.DownloadEntity.thumbnailModel(): Any? =
    if (isAudio) {
        // mediaUri is set only when a cover was pulled out; without one, thumbnailPath is the song.
        AudioCover(audioUri = mediaUri ?: thumbnailPath, storedArt = thumbnailPath.takeIf { mediaUri != null })
    } else {
        thumbnailPath
    }

/** The cover pulled out when the song was saved; failing that (cleared, or never there), the
 * system's thumbnail for the song (its embedded cover), then the embedded cover read directly.
 * No cover at all is an error, so the tile shows its music-note placeholder. */
private class AudioCoverFetcher(
    private val data: AudioCover,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val context = options.context
        val resolver = context.contentResolver
        data.storedArt?.let { art ->
            val stream = runCatching { resolver.openInputStream(Uri.parse(art)) }.getOrNull()
            if (stream != null) {
                return coil.fetch.SourceResult(
                    source = coil.decode.ImageSource(stream.source().buffer(), context),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }
        val audio = data.audioUri?.let(Uri::parse) ?: throw java.io.IOException("no song")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audio.authority == MediaStore.AUTHORITY) {
            val width = options.size.width.pxOrElse { 512 }.coerceIn(1, 1024)
            val height = options.size.height.pxOrElse { 512 }.coerceIn(1, 1024)
            val bitmap = runCatching { resolver.loadThumbnail(audio, android.util.Size(width, height), null) }.getOrNull()
            if (bitmap != null) {
                return DrawableResult(bitmap.toDrawable(context.resources), isSampled = true, dataSource = DataSource.DISK)
            }
        }
        val embedded = android.media.MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(context, audio)
                retriever.embeddedPicture
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        } ?: throw java.io.IOException("no cover")
        return coil.fetch.SourceResult(
            source = coil.decode.ImageSource(okio.Buffer().write(embedded), context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<AudioCover> {
        override fun create(data: AudioCover, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioCoverFetcher(data, options)
    }

    /** Lets the memory cache keep covers (a custom type has no cache key otherwise). */
    class Keyer : coil.key.Keyer<AudioCover> {
        override fun key(data: AudioCover, options: Options): String = "audio-cover:${data.audioUri}|${data.storedArt}"
    }
}
