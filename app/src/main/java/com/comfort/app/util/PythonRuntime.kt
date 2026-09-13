package com.comfort.app.util

import android.content.Context
import android.system.Os
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs gallery_dl_wrapper.py / yt_dlp_wrapper.py as a real OS subprocess against a standalone
 * Python interpreter, replacing Chaquopy's JNI-embedded one. Chaquopy can't bundle curl_cffi —
 * TikTok's bot-detection bypass, the whole reason this exists — since every curl_cffi release
 * with an Android wheel needs cffi>=2.0.0, and no Android build of cffi exists on PyPI at any
 * version (confirmed by directly attempting the Chaquopy build, not just reading docs). See the
 * migration plan (C:\Users\domin\.claude\plans\rosy-soaring-sundae.md) for the full story.
 *
 * The interpreter itself, plus cffi/curl_cffi and their native dependency closure, come from
 * YTDLnis's own published "python" package (github.com/deniscerri/ytdlnis-packages), installed on
 * the device as a small standalone APK (HELPER_PACKAGE below — its only purpose is to carry
 * jniLibs; see that repo's PackageBase.kt/build_python.sh). Its jniLibs/<abi>/libpython.so (a
 * small NDK-built launcher, not a raw termux-built python binary — confirmed via `file`) runs
 * directly from *that app's own* nativeLibraryDir — real, on-disk, exec-permitted, the same trick
 * QuickJsRuntime/FfmpegRuntime already use for their own bundled binaries in this app — while
 * jniLibs/<abi>/libpython.zip.so (the stdlib + those native deps, zipped) gets unzipped once into
 * our own noBackupFilesDir. Loading C-extension .so's from *that* regular storage (not
 * nativeLibraryDir) was the one real unknown here — verified working on-device via a manual spike
 * (including a real curl_cffi-impersonated request against the exact TikTok URL that was 403ing)
 * before any of this was written.
 *
 * gallery-dl and yt-dlp themselves aren't part of that package (it only ships the interpreter and
 * a few native deps) — bundled here as plain pure-Python wheels in assets/python_packages/ and
 * unzipped alongside it instead.
 *
 * This is Phase 1 of the migration only: the interpreter/cffi/curl_cffi source is still YTDLnis's
 * published package, not a self-build (that's Phase 5, only if ever needed) — and DownloadWorker.kt
 * / GalleryDlListing.kt haven't been switched over to call this yet (Phase 2).
 */
object PythonRuntime {
    const val HELPER_PACKAGE = "com.deniscerri.ytdl.python"
    private const val RUNTIME_DIR_NAME = "python_runtime"
    private const val PROVISION_MARKER = "provisioned.txt"

    // Bump whenever assets/python_packages/ changes (a new gallery-dl/yt-dlp version, a wrapper
    // script edit) so a rebuild re-provisions instead of silently keeping a stale extracted tree.
    private const val PROVISION_VERSION = "57"

    private fun runtimeRoot(context: Context) = File(context.noBackupFilesDir, RUNTIME_DIR_NAME)

    // internal, not private — EngineUpdater.kt reuses this to find/replace an installed engine's
    // package directory in place when the user updates yt-dlp/gallery-dl from Settings.
    internal fun sitePackagesDir(context: Context) =
        File(runtimeRoot(context), "usr/lib/python3.14/site-packages")

    /** Null if the app's own nativeLibraryDir can't be resolved. */
    private fun helperNativeLibDir(context: Context): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        return File(dir).takeIf { it.exists() }
    }

    // Guards the unpack-everything critical section below. Without this, two downloads starting
    // at once right after an app update (PROVISION_VERSION bumped, marker stale) both see the
    // stale marker, both proceed into root.deleteRecursively() + unzip concurrently, and clobber
    // each other's writes — a real corruption risk, not just a wasted duplicate unzip, since one
    // coroutine's deleteRecursively() can run while the other is mid-write into the same tree.
    private val provisionMutex = Mutex()

    /** Lets EngineUpdater share this same critical section when it replaces an engine's
     * site-packages contents in place — same corruption risk as two ensureProvisioned() calls
     * racing each other, just between an update's delete+unzip and a concurrent first-ever
     * provision instead of two provisions. */
    suspend fun <T> withProvisionLock(block: suspend () -> T): T = provisionMutex.withLock { block() }

    /** True once the interpreter tree + our own packages are unpacked and ready to run. Cheap to
     * call repeatedly — only actually does work the first time (or after [PROVISION_VERSION]
     * changes). */
    suspend fun ensureProvisioned(context: Context): Boolean = provisionMutex.withLock {
        val nativeLibDir = helperNativeLibDir(context) ?: return@withLock false
        val root = runtimeRoot(context)
        val marker = File(root, PROVISION_MARKER)
        if (marker.exists() && runCatching { marker.readText() }.getOrNull() == PROVISION_VERSION) {
            return@withLock true
        }

        val zipSo = File(nativeLibDir, "libpython.zip.so")
        if (!zipSo.exists()) return@withLock false

        root.deleteRecursively()
        root.mkdirs()
        zipSo.inputStream().use { unzipStreamTo(it, root) }
        fixBrokenSymlinks(File(root, "usr/lib"))

        val sitePackages = sitePackagesDir(context)
        context.assets.open("python_packages/gallery_dl.whl").use { unzipStreamTo(it, sitePackages) }
        context.assets.open("python_packages/yt_dlp.whl").use { unzipStreamTo(it, sitePackages) }

        // curl_cffi (bundled as part of the native runtime zip above) was previously deleted here —
        // it was suspected of causing Reddit's "Your IP address is unable to access the Reddit API"
        // block, since having a working impersonation backend available makes yt-dlp's Reddit
        // extractor request impersonation for one of its calls. Re-tested live with curl_cffi
        // restored (2026-09-01): got the exact same Reddit failure either way, conclusively ruling
        // out curl_cffi/impersonation as the differentiator for that error. So it's kept (not
        // deleted) again — Reddit's block is a separate, still-unresolved issue unrelated to this,
        // and TikTok genuinely needs curl_cffi's impersonation to get past its bot detection.

        // cacert.pem (Mozilla's CA bundle via curl.se's own maintained mirror) rides along here
        // too — aria2c's GnuTLS-linked TLS stack has no CA trust store of its own in this bundled
        // environment (unlike a request made through Python's own ssl module or OkHttp, which both
        // go through Android's system trust store transparently), so every aria2c-downloaded
        // HTTPS URL failed with "SSL/TLS handshake failure: not signed by known authorities"
        // until yt_dlp_wrapper.py started passing --ca-certificate=<this file> explicitly.
        for (name in listOf("gallery_dl_wrapper.py", "yt_dlp_wrapper.py", "cacert.pem")) {
            context.assets.open("python_packages/$name").use { input ->
                File(root, name).outputStream().use { input.copyTo(it) }
            }
        }

        marker.writeText(PROVISION_VERSION)
        true
    }

    // internal, not private — EngineUpdater.kt reuses this to unpack a freshly downloaded engine
    // wheel the same way a bundled one gets unpacked here.
    internal fun unzipStreamTo(input: InputStream, destDir: File) {
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** ZipInputStream (and, confirmed via a manual on-device spike, Android's own `unzip` too) has
     * no symlink concept — a `zip --symlinks`-stored entry (e.g. `libz.so -> libz.so.1.3.2`) comes
     * out as a regular file whose tiny content is just the link target's name. A real .so is
     * always far bigger than that, so "suspiciously small file next to the target it names" is a
     * safe, narrow heuristic — confirmed against the actual broken entries during that spike. */
    // internal, not private — Aria2Runtime.kt reuses this for its own zip.so's broken-symlink
    // entries (identical artifact, same underlying zip/unzip limitation).
    internal fun fixBrokenSymlinks(libDir: File) {
        val files = libDir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile || file.length() > 200) continue
            val target = runCatching { file.readText().trim() }.getOrNull() ?: continue
            if (target.isEmpty() || target.contains('/')) continue
            val targetFile = File(libDir, target)
            if (targetFile.exists() && targetFile != file) {
                file.delete()
                runCatching { Os.symlink(target, file.absolutePath) }
            }
        }
    }

    /** Runs one wrapper script's CLI entry point (see gallery_dl_wrapper.py's / yt_dlp_wrapper.py's
     * own `if __name__ == "__main__":` block) as a real subprocess, streaming its stdout back one
     * line at a time — same line-prefix protocol Chaquopy's callback used ([title]/[thumbnail]/
     * [size]/[progress]/[error]/[warning]/bare file paths), just delivered over a pipe instead of a
     * JNI-reentrant call. [onLine] runs on this function's own dispatcher (a suspend fun on
     * Dispatchers.IO) — no JNI reentrancy constraint, so unlike DownloadWorker's old callbackScope/
     * pendingJobs/joinAll() dance, a suspend DB write inside [onLine] is safe to call directly.
     *
     * [script] is "gallery_dl_wrapper.py" or "yt_dlp_wrapper.py"; [args] is that script's own CLI
     * argv (see each file's `__main__` block for the exact positional shape/`""`-for-null
     * convention). [onLine] is itself `suspend` — no JNI reentrancy here, unlike Chaquopy's old
     * callback, so a plain suspend DAO write is safe to call directly from it, no
     * launch-and-collect dance needed.
     *
     * Cancellation: this is a real OS process now, not a reentrant call into a shared interpreter,
     * so there's no cooperative should_cancel polling on the Python side anymore — Kotlin just
     * kills it. But the blocking `readLine()` loop below has no coroutine suspension point of its
     * own, so a cancelled parent Job (WorkManager stopping this download) can't interrupt it by
     * being cancelled alone — it would just sit there until the process exits by itself. Killing
     * the process the moment cancellation is *requested* (registered right after start, not
     * waited-for at a suspension point) is what makes Pause/Cancel take effect immediately. */
    suspend fun run(
        context: Context,
        script: String,
        args: List<String>,
        onLine: suspend (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val nativeLibDir = helperNativeLibDir(context)
            ?: error("App nativeLibraryDir not found (this should never happen)")
        if (!ensureProvisioned(context)) {
            error("Failed to provision the Python runtime")
        }
        val root = runtimeRoot(context)
        val interpreter = File(nativeLibDir, "libpython.so")
        val scriptFile = File(root, script)

        val command = mutableListOf(interpreter.absolutePath, scriptFile.absolutePath).apply { addAll(args) }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["PYTHONHOME"] = File(root, "usr").absolutePath
                environment()["LD_LIBRARY_PATH"] = File(root, "usr/lib").absolutePath
                environment()["PYTHONPATH"] = sitePackagesDir(context).absolutePath
            }
            .start()

        try {
            var exitCode = 0
            coroutineScope {
                // A plain `invokeOnCompletion` on this coroutine's own Job does NOT work here — a
                // cancelled Job only reaches its terminal "Cancelled" state (what that callback
                // waits for) once its body actually finishes, and the body below is stuck in a
                // blocking readLine() with no suspension point of its own until the process dies.
                // Deadlock: waiting for the process to die to kill the process. A sibling coroutine
                // suspended in awaitCancellation(), by contrast, gets cancellation delivered to it
                // the moment this scope is cancelled — independent of whatever the *other* sibling
                // (the blocking read, right below) happens to be stuck doing — so its `finally`
                // kills the process immediately, which closes the pipe, which is what actually
                // unblocks the read loop. Standard idiom for cancelling a blocking call.
                val killer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
                process.outputStream.close()
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        onLine(line)
                        line = reader.readLine()
                    }
                }
                exitCode = process.waitFor()
                killer.cancel()
            }
            exitCode
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
