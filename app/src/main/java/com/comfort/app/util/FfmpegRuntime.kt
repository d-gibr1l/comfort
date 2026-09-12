package com.comfort.app.util

import android.content.Context
import java.io.File

/** Locates (and, on some ABIs, provisions) the ffmpeg CLI binary bundled in the APK so yt-dlp can
 * shell out to it to merge separately-downloaded best video and best audio streams into one file
 * — without it, yt-dlp is limited to whatever single pre-muxed ("progressive") format a site
 * happens to still serve, which for YouTube in particular is frequently nothing at all for newer
 * uploads/Shorts.
 *
 * Two different shapes bundled depending on ABI, both under jniLibs/<abi>/:
 * - arm64-v8a/x86_64: `libffmpeg.so` alone — a real, fully statically-linked ELF executable, just
 *   given a library-shaped name so Android's installer extracts it to nativeLibraryDir (the one
 *   place in an app's sandbox that's still exec-permitted since Android 10 made the rest of
 *   private storage noexec). Executed directly from there, no dependencies to resolve.
 * - armeabi-v7a: sourced from the same ytdlnis-packages release as PythonRuntime's own interpreter
 *   (github.com/deniscerri/ytdlnis-packages, "ffmpeg" package) — this build is dynamically linked
 *   instead, so it also ships `libffmpeg.zip.so`, a zip of its actual shared-library dependencies
 *   (libavcodec/libavformat/libx264/...), provisioned exactly the way PythonRuntime/Aria2Runtime
 *   already provision their own zip.so bundles: unzipped once into noBackupFilesDir, broken
 *   zip-symlink entries repaired the same way. Its own dynamic linker needs LD_LIBRARY_PATH
 *   pointed at that unzipped directory to actually run — see yt_dlp_wrapper.py's own
 *   ffmpeg_lib_dir kwarg (identical mechanism to its pre-existing aria2_lib_dir one). */
object FfmpegRuntime {
    private const val BINARY_NAME = "libffmpeg.so"
    private const val ZIP_SO_NAME = "libffmpeg.zip.so"
    private const val RUNTIME_DIR_NAME = "ffmpeg_runtime"
    private const val PROVISION_MARKER = "provisioned.txt"

    // Bump if this project ever moves to a newer published ytdlnis-packages ffmpeg build.
    private const val PROVISION_VERSION = "1"

    private fun runtimeRoot(context: Context) = File(context.noBackupFilesDir, RUNTIME_DIR_NAME)

    /** Path to the ffmpeg executable, or null if it wasn't bundled for this device's ABI. */
    fun getExecutablePath(context: Context): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        return binary.takeIf { it.exists() }?.absolutePath
    }

    /** Directory holding this ABI's ffmpeg's own bundled shared-library dependencies — null on
     * arm64-v8a/x86_64, where libffmpeg.so is fully static and has none to resolve, or if
     * provisioning it failed. Callers must point the ffmpeg subprocess's own dynamic linker at
     * this directory (LD_LIBRARY_PATH) when it's non-null — see yt_dlp_wrapper.py's own use of
     * this via the ffmpeg_lib_dir kwarg, identical to Aria2Runtime.ensureProvisioned's own
     * equivalent for aria2c. Cheap to call repeatedly — only actually unzips the first time (or
     * after [PROVISION_VERSION] changes). */
    fun ensureProvisioned(context: Context): File? {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val zipSo = File(nativeLibDir, ZIP_SO_NAME)
        if (!zipSo.exists()) return null

        val root = runtimeRoot(context)
        val libDir = File(root, "usr/lib")
        val marker = File(root, PROVISION_MARKER)
        if (marker.exists() && runCatching { marker.readText() }.getOrNull() == PROVISION_VERSION) {
            return libDir
        }

        root.deleteRecursively()
        root.mkdirs()
        runCatching {
            zipSo.inputStream().use { PythonRuntime.unzipStreamTo(it, root) }
        }.onFailure { return null }
        PythonRuntime.fixBrokenSymlinks(libDir)

        marker.writeText(PROVISION_VERSION)
        return libDir
    }
}
