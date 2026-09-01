package com.comfort.app.util

import android.content.Context
import java.io.File

/** Locates the ffmpeg CLI binary bundled in the APK so yt-dlp can shell out to it to merge
 * separately-downloaded best video and best audio streams into one file — without it, yt-dlp is
 * limited to whatever single pre-muxed ("progressive") format a site happens to still serve,
 * which for YouTube in particular is frequently nothing at all for newer uploads/Shorts.
 *
 * Ships as jniLibs/<abi>/libffmpeg.so for the same reason as libqjs.so (see QuickJsRuntime's doc
 * comment): a real, statically-linked ELF executable just given a library-shaped name so
 * Android's installer extracts it to nativeLibraryDir — the one place in an app's sandbox that's
 * still exec-permitted since Android 10 made the rest of private storage noexec. Executed
 * directly from there, never copied elsewhere first. */
object FfmpegRuntime {
    private const val BINARY_NAME = "libffmpeg.so"

    /** Path to the ffmpeg executable, or null if it wasn't bundled for this device's ABI. */
    fun getExecutablePath(context: Context): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        return binary.takeIf { it.exists() }?.absolutePath
    }
}
