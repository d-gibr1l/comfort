package com.comfort.app.util

import android.content.Context
import java.io.File

/** Locates (and provisions) the aria2c binary bundled in the APK so yt-dlp can shell out to it as
 * an external downloader — real multi-connection segmented downloading of a single file, unlike
 * yt-dlp's own built-in downloader which fetches one file with one connection. Real value on a
 * slow or high-latency connection; negligible on a fast one.
 *
 * Sourced from `io.github.junkfood02.youtubedl-android:aria2c:0.18.1` (GPL-3.0, github.com/
 * yausername/youtubedl-android) — the same published, widely-used prebuilt YTDLnis itself
 * depends on for this exact feature — not a from-scratch NDK cross-compile. Investigated and
 * dropped once already this project (manually chaining Termux's own aria2 .deb package pulled in
 * a real ICU dependency transitively via libxml2, 40-80MB); this build turned out to be a
 * completely different, much leaner story: GnuTLS instead of OpenSSL+libxml2+ICU, and confirmed
 * via direct ELF inspection to need **no ICU at all** — total added weight for arm64-v8a is
 * ~6.8MB (libaria2c.so + libaria2c.zip.so combined), not 40-80MB.
 *
 * Ships as jniLibs/arm64-v8a/libaria2c.so (a real ELF executable, library-shaped name so
 * Android's installer extracts it to nativeLibraryDir — same trick FfmpegRuntime/QuickJsRuntime
 * already use) plus libaria2c.zip.so, a zip of aria2c's own *actual* shared-library dependencies
 * (libgnutls/libnettle/libgmp/libxml2/libcares/libc++_shared — aria2c isn't statically linked the
 * way libffmpeg.so/libqjs.so are). That second file is provisioned the same way PythonRuntime
 * provisions its own libpython.zip.so: unzipped once into noBackupFilesDir, broken zip-symlink
 * entries repaired the same way, with regular (non-nativeLibraryDir) app storage being fine for a
 * shared library the dynamic linker only ever *loads* (not executes directly) even under Android
 * 10+'s W^X restrictions on private storage — PythonRuntime's own doc comment covers why this is
 * safe in more detail; this reuses its already-verified assumption rather than re-proving it. */
object Aria2Runtime {
    private const val BINARY_NAME = "libaria2c.so"
    private const val ZIP_SO_NAME = "libaria2c.zip.so"
    private const val RUNTIME_DIR_NAME = "aria2_runtime"
    private const val PROVISION_MARKER = "provisioned.txt"

    // Bump if this project ever moves to a newer published aria2c AAR build.
    private const val PROVISION_VERSION = "1"

    private fun runtimeRoot(context: Context) = File(context.noBackupFilesDir, RUNTIME_DIR_NAME)

    /** Path to the aria2c executable, or null if it wasn't bundled for this device's ABI. */
    fun getExecutablePath(context: Context): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        return binary.takeIf { it.exists() }?.absolutePath
    }

    /** Directory holding aria2c's own bundled shared-library dependencies. Callers must point the
     * aria2c subprocess's own dynamic linker at this directory (LD_LIBRARY_PATH) — see
     * yt_dlp_wrapper.py's own use of this via the aria2_lib_dir kwarg. Null if aria2c wasn't
     * bundled for this device's ABI, or if provisioning it failed. Cheap to call repeatedly, like
     * PythonRuntime.ensureProvisioned — only actually unzips the first time (or after
     * [PROVISION_VERSION] changes). */
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
