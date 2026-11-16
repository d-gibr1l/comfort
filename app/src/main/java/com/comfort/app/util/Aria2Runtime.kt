package com.comfort.app.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** aria2c, so yt-dlp can shell out to it as an external downloader — real multi-connection
 * segmented downloading of a single file, unlike yt-dlp's own built-in downloader which fetches one
 * file with one connection. Real value on a slow or high-latency connection; negligible on a fast
 * one. Off by default (Settings › Downloads).
 *
 * Sourced from `io.github.junkfood02.youtubedl-android:aria2c:0.18.1` (GPL-3.0, github.com/
 * yausername/youtubedl-android) — the same published prebuilt YTDLnis depends on for this feature.
 * GnuTLS instead of OpenSSL+libxml2+ICU, no ICU at all.
 *
 * Two parts:
 * - jniLibs/<abi>/libaria2c.so, the executable itself (~1 MB), stays in the APK: Android 10+ only
 *   lets an app run programs from its nativeLibraryDir, never one it wrote itself.
 * - Its shared libraries (libgnutls/libnettle/libgmp/libxml2/libcares/libc++_shared, ~4 MB) are
 *   downloaded when aria2 is turned on ([startInstall]) instead of shipping to everyone with it
 *   off: a GitHub release asset of the app's own public repo (release-assets/aria2-libs-<abi>.zip
 *   in the source tree), checked against the SHA-256 built in here, and unzipped into
 *   noBackupFilesDir like PythonRuntime's libpython.zip.so. Loading (not executing) a library from
 *   app storage is allowed under Android 10+'s W^X rules. aria2c also takes libz and friends from
 *   the Python runtime's usr/lib, which yt_dlp_wrapper leaves on LD_LIBRARY_PATH after this one. */
object Aria2Runtime {
    private const val BINARY_NAME = "libaria2c.so"
    private const val RUNTIME_DIR_NAME = "aria2_runtime"
    private const val PROVISION_MARKER = "provisioned.txt"

    // The release the libraries are published under. A new build of them is a new tag (and new
    // checksums below), so an installed copy is replaced.
    private const val LIBS_TAG = "aria2-libs-1"
    private const val RELEASES = "https://github.com/d-gibr1l/comfort/releases/download"
    private val SHA256 = mapOf(
        "arm64-v8a" to "10f7849893002d57a0068bf22687ec711dbe805dbcd9859507188d9d08efcbfb",
        "armeabi-v7a" to "d64275db45e2a13d2f99e78558c7716d4d3db0d1fd1d32f245ec07c164e8ea36",
    )
    // Unpacked from the APK's own copy before the libraries became a download: the same files,
    // still there and working, so nothing is downloaded again for them.
    private val READY_MARKERS = setOf(LIBS_TAG, "1")

    private fun runtimeRoot(context: Context) = File(context.noBackupFilesDir, RUNTIME_DIR_NAME)

    private fun libDir(context: Context) = File(runtimeRoot(context), "usr/lib")

    /** Path to the aria2c executable, or null if it wasn't bundled for this device's ABI. */
    fun getExecutablePath(context: Context): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        return binary.takeIf { it.isFile && it.exists() }?.absolutePath
    }

    /** aria2 can run on this device at all (its executable is in this APK: arm64 and 32-bit ARM). */
    fun isSupported(context: Context): Boolean = getExecutablePath(context) != null && abi() != null

    /** The libraries are downloaded and unpacked: aria2c can run. */
    fun isReady(context: Context): Boolean =
        getExecutablePath(context) != null &&
            runCatching { File(runtimeRoot(context), PROVISION_MARKER).readText() }.getOrNull() in READY_MARKERS

    /** Directory holding aria2c's shared libraries, for its LD_LIBRARY_PATH (yt_dlp_wrapper's
     * aria2_lib_dir); null until [isReady]. */
    fun ensureProvisioned(context: Context): File? = if (isReady(context)) libDir(context) else null

    private fun abi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in SHA256 }

    /** A running or failed download of the libraries: progress 0..1 (null while its size isn't
     * known yet), or the error that ended it. Null when nothing is happening. */
    data class InstallState(val progress: Float?, val error: String? = null)

    private val _install = MutableStateFlow<InstallState?>(null)
    val install: StateFlow<InstallState?> = _install.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Downloads and unpacks the libraries, in the background (leaving the page doesn't stop it);
     * [onDone] runs with true once aria2 can be used, false if it failed ([install] says why). */
    fun startInstall(context: Context, onDone: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        if (isReady(appContext)) { onDone(true); return }
        if (_install.value?.let { it.error == null } == true) return // already downloading
        _install.value = InstallState(progress = null)
        scope.launch {
            val result = runCatching { download(appContext) }
            result.exceptionOrNull()?.let { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _install.value = InstallState(progress = null, error = e.message ?: e.javaClass.simpleName)
            } ?: run { _install.value = null }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(result.isSuccess) }
        }
    }

    /** Clears a shown error. */
    fun dismissError() {
        if (_install.value?.error != null) _install.value = null
    }

    private suspend fun download(context: Context) {
        val abi = abi() ?: error("not available for this phone")
        val expected = SHA256.getValue(abi)
        val temp = File(context.cacheDir, "aria2-libs-$abi.zip.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            (URL("$RELEASES/$LIBS_TAG/aria2-libs-$abi.zip").openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Comfort")
                try {
                    if (responseCode !in 200..299) error("HTTP $responseCode")
                    val total = contentLengthLong
                    var done = 0L
                    inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                kotlin.coroutines.coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                if (total > 0) _install.value = InstallState((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    disconnect()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            // Not what was published with this version of the app: never loaded.
            if (actual != expected) error("the download didn't match (checksum)")

            // Unpacked beside the old copy, then swapped in, so a failure leaves nothing half-done.
            val root = runtimeRoot(context)
            val staging = File(context.noBackupFilesDir, "$RUNTIME_DIR_NAME.new")
            staging.deleteRecursively()
            staging.mkdirs()
            temp.inputStream().use { PythonRuntime.unzipStreamTo(it, staging) }
            PythonRuntime.fixBrokenSymlinks(File(staging, "usr/lib"))
            File(staging, PROVISION_MARKER).writeText(LIBS_TAG)
            root.deleteRecursively()
            if (!staging.renameTo(root)) {
                staging.deleteRecursively()
                error("couldn't save it")
            }
        } finally {
            temp.delete()
        }
    }
}
