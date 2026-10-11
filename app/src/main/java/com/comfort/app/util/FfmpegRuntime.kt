package com.comfort.app.util

import android.content.Context
import java.io.File

/** Locates the ffmpeg CLI binary bundled in the APK as jniLibs/<abi>/libffmpeg.so — a real ELF
 * executable given a library-shaped name so Android's installer extracts it to nativeLibraryDir
 * (the one place in an app's sandbox that's still exec-permitted since Android 10 made the rest of
 * private storage noexec).
 *
 * The same build for every ABI (ffmpeg 7.1, built from ~/ffmpeg-build/build2.sh in WSL): it links
 * only against system libraries (libc, libz, libmediandk), so there's nothing to unpack. Besides
 * muxing yt-dlp's separate video and audio streams, it decodes audio/video/images and encodes AAC,
 * MP3 (LAME), Opus, FLAC, JPEG, PNG and GIF, plus H.264/HEVC through the phone's own hardware
 * encoder (h264_mediacodec — needs the frame width to be a multiple of 16, see
 * yt_dlp_wrapper.py's _h264_args).
 *
 * armeabi-v7a used to ship a dynamically linked ytdlnis-packages build with a separate
 * libffmpeg.zip.so of dependencies, unzipped into noBackupFilesDir; [ensureProvisioned] now only
 * removes that leftover folder. */
object FfmpegRuntime {
    private const val BINARY_NAME = "libffmpeg.so"
    private const val RUNTIME_DIR_NAME = "ffmpeg_runtime"

    /** Path to the ffmpeg executable, or null if it wasn't bundled for this device's ABI. */
    fun getExecutablePath(context: Context): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        return binary.takeIf { it.isFile && it.exists() }?.absolutePath
    }

    /** Always null: no ABI's ffmpeg has its own libraries to point LD_LIBRARY_PATH at anymore.
     * Deletes the old armeabi-v7a bundle's unzipped folder if an update left it behind. */
    @Synchronized
    fun ensureProvisioned(context: Context): File? {
        val leftover = File(context.noBackupFilesDir, RUNTIME_DIR_NAME)
        if (leftover.exists()) leftover.deleteRecursively()
        return null
    }
}
