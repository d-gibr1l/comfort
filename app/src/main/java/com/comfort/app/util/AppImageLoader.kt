package com.comfort.app.util

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
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
                    // Registers frame extraction for video thumbnails (gallery-dl downloads are
                    // all images, but yt-dlp ones are video — without this decoder, a video's
                    // MediaStore thumbnail Uri just renders blank instead of a preview frame).
                    add(VideoFrameDecoder.Factory())
                }
                .build()
        )
    }
}
