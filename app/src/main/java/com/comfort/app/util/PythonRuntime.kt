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
    private const val PROVISION_VERSION = "100"

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

        val provisionStarted = System.currentTimeMillis()
        root.deleteRecursively()
        root.mkdirs()
        zipSo.inputStream().use { unzipStreamTo(it, root) }
        fixBrokenSymlinks(File(root, "usr/lib"))

        val sitePackages = sitePackagesDir(context)
        context.assets.open("python_packages/gallery_dl.whl").use { unzipStreamTo(it, sitePackages) }
        context.assets.open("python_packages/yt_dlp.whl").use { unzipStreamTo(it, sitePackages) }
        // Instagram posts/reels by default (see VideoSiteRouter.resolveEngine). Pure Python; its
        // only dependency, requests (plus the stdlib lzma it imports unconditionally), already
        // ships in the runtime zip above.
        context.assets.open("python_packages/instaloader.whl").use { unzipStreamTo(it, sitePackages) }

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
        for (name in listOf("gallery_dl_wrapper.py", "yt_dlp_wrapper.py", "spotify_wrapper.py", "instaloader_wrapper.py", SERVER_SCRIPT, "net_resilience.py", "cacert.pem")) {
            context.assets.open("python_packages/$name").use { input ->
                File(root, name).outputStream().use { input.copyTo(it) }
            }
        }

        marker.writeText(PROVISION_VERSION)
        android.util.Log.i("PythonRuntime", "provisioned $PROVISION_VERSION in ${System.currentTimeMillis() - provisionStarted}ms")
        true
    }

    private const val PRECOMPILE_MARKER = "precompiled.txt"
    private val warmUpStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    // Outlives any one Activity (a rotation shouldn't cancel a half-done compile).
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Gets the runtime ready before the user needs it: unpacks it if an app update invalidated it,
     * then compiles every engine module to .pyc. Without this, the first preview after an update
     * paid for the whole unpack *and* Python compiling each module it imported — measured on-device
     * (Samsung, Python 3.14): importing yt-dlp took 2.4–3.1s with no bytecode cache vs ~0.5s with
     * one, Instaloader 1.0s vs 0.18s, gallery-dl 0.7s vs 0.21s; and Python only caches what's
     * actually imported, so each yt-dlp site module compiled lazily on first use too.
     * Delayed and run at low CPU priority (one process) so it doesn't compete with the first
     * screens. Idempotent per [PROVISION_VERSION] (marker file), safe to call on every launch. */
    fun warmUpInBackground(context: Context) {
        if (!warmUpStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        backgroundScope.launch {
            kotlinx.coroutines.delay(4_000)
            if (!ensureProvisioned(appContext)) return@launch
            val marker = File(runtimeRoot(appContext), PRECOMPILE_MARKER)
            if (runCatching { marker.readText() }.getOrNull() != PROVISION_VERSION &&
                precompile(appContext, sitePackagesDir(appContext))) {
                marker.writeText(PROVISION_VERSION)
            }
            // After compiling, so the server imports from .pyc instead of compiling in memory.
            startServer(appContext)
        }
    }

    // ---- Fork server (assets/python_packages/py_server.py) -------------------------------------
    // One long-lived interpreter with yt-dlp/gallery-dl/Instaloader already imported; each job is a
    // fork() of it. Measured on-device: a yt-dlp probe job went from ~550ms (2s+ cold) as its own
    // interpreter to ~100ms. Jobs are still separate processes, exactly as isolated as before; the
    // server is only an optimisation: whenever it isn't up (still starting, idled out after 10 min,
    // killed, or just restarted by an engine update), run() spawns the interpreter directly.

    // Off for now. It was switched off over stalled downloads, but an in-app A/B test (same requests
    // run directly, forked, directly again) showed the direct runs stalling just the same: the
    // cause was the network dropping connections (see net_resilience.py), not the fork. Cancel
    // was also confirmed to kill a forked job together with its child process.
    private const val USE_FORK_SERVER = false
    private const val SERVER_SCRIPT = "py_server.py"
    private const val SERVER_SOCKET = "pyserver.sock"
    private const val CONTROL = '\u0001'

    @Volatile private var server: Process? = null
    private val serverStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    // So a server that can't start (a broken engine update, say) isn't retried alongside every job.
    @Volatile private var serverFailedAt = 0L

    /** Starts the fork server unless it's already running or starting. Returns once it's ready (or
     * has failed to start). */
    private suspend fun startServer(context: Context) {
        if (!USE_FORK_SERVER) return
        if (server?.isAlive == true || System.currentTimeMillis() - serverFailedAt < 5 * 60_000) return
        if (!serverStarting.compareAndSet(false, true)) return
        serverFailedAt = System.currentTimeMillis() // cleared below once it's ready
        try {
            if (!ensureProvisioned(context)) return
            val root = runtimeRoot(context)
            val process = pythonProcess(
                context,
                listOf(File(root, SERVER_SCRIPT).absolutePath, File(root, SERVER_SOCKET).absolutePath),
            ).start()
            // stdin is deliberately left open: the server exits when it closes, i.e. when this app
            // process dies, so it can never outlive the app (or serve a newer version of it).
            val started = System.currentTimeMillis()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val ready = withContext(Dispatchers.IO) {
                var line = reader.readLine()
                while (line != null && line != "${CONTROL}ready") line = reader.readLine()
                line != null
            }
            if (!ready) {
                android.util.Log.w("PythonRuntime", "fork server exited before becoming ready")
                return
            }
            server = process
            serverFailedAt = 0L
            android.util.Log.i("PythonRuntime", "fork server ready in ${System.currentTimeMillis() - started}ms")
            // Drain anything else it prints (warnings) so its pipe never fills, and forget it once
            // it exits (idle timeout, killed).
            backgroundScope.launch {
                runCatching { reader.use { while (it.readLine() != null) Unit } }
                if (server === process) server = null
            }
        } catch (e: Exception) {
            android.util.Log.w("PythonRuntime", "fork server failed to start", e)
        } finally {
            serverStarting.set(false)
        }
    }

    /** Stops the fork server so the next job starts a fresh one. EngineUpdater calls this after
     * replacing an engine, whose old version the server still has in memory. Running jobs are
     * separate processes and are unaffected. */
    fun restartServer() {
        server?.destroy()
        server = null
        serverFailedAt = 0L
    }

    private fun pythonProcess(context: Context, args: List<String>): ProcessBuilder {
        val nativeLibDir = helperNativeLibDir(context)
            ?: error("App nativeLibraryDir not found (this should never happen)")
        val root = runtimeRoot(context)
        return ProcessBuilder(listOf(File(nativeLibDir, "libpython.so").absolutePath) + args)
            .redirectErrorStream(true)
            .apply {
                environment()["PYTHONHOME"] = File(root, "usr").absolutePath
                environment()["LD_LIBRARY_PATH"] = File(root, "usr/lib").absolutePath
                environment()["PYTHONPATH"] = sitePackagesDir(context).absolutePath
            }
    }

    /** Runs a job through the fork server. Null if the server couldn't take it (nothing ran), in
     * which case the caller runs it directly instead. */
    private suspend fun runViaServer(
        context: Context,
        script: String,
        args: List<String>,
        onLine: suspend (String) -> Unit,
    ): Int? = withContext(Dispatchers.IO) {
        val socket = android.net.LocalSocket()
        try {
            try {
                socket.connect(
                    android.net.LocalSocketAddress(
                        File(runtimeRoot(context), SERVER_SOCKET).absolutePath,
                        android.net.LocalSocketAddress.Namespace.FILESYSTEM,
                    )
                )
                val request = org.json.JSONObject()
                    .put("script", script)
                    .put("args", org.json.JSONArray(args))
                socket.outputStream.write("$request\n".toByteArray())
                socket.outputStream.flush()
            } catch (e: java.io.IOException) {
                return@withContext null
            }
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val pid = runCatching { reader.readLine() }.getOrNull()
                ?.takeIf { it.startsWith("${CONTROL}pid ") }
                ?.substringAfter(' ')?.toIntOrNull()
                ?: return@withContext null

            // From here on the job is running: its result is final, never retried directly.
            var exitCode: Int? = null
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            coroutineScope {
                // Same reason as run()'s killer below: the read loop blocks without suspending. The
                // job runs in its own process group (py_server.py), so this also takes down any
                // ffmpeg/aria2c it started.
                val killer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) {
                            runCatching { Os.kill(-pid, android.system.OsConstants.SIGKILL) }
                            runCatching { Os.kill(pid, android.system.OsConstants.SIGKILL) }
                        }
                        runCatching { socket.close() }
                    }
                }
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("${CONTROL}exit ")) {
                            exitCode = line.substringAfter(' ').toIntOrNull()
                        } else {
                            onLine(line)
                        }
                        line = reader.readLine()
                    }
                } catch (e: java.io.IOException) {
                    // The socket was closed under us by the killer (cancellation, rethrown below).
                } finally {
                    finished.set(true)
                }
                killer.cancel()
            }
            android.util.Log.d("PythonRuntime", "$script ${args.firstOrNull()} via fork server: exit=$exitCode")
            // No exit line: the job was killed or crashed natively. Same as a direct run killed
            // by a signal — a nonzero code, which every caller already treats as a failure.
            exitCode ?: 137
        } finally {
            runCatching { socket.close() }
        }
    }

    /** [precompile] without waiting on it — EngineUpdater uses this so an engine's Update button
     * isn't held up by compiling the new version. */
    fun precompileInBackground(context: Context, dir: File) {
        val appContext = context.applicationContext
        backgroundScope.launch { precompile(appContext, dir) }
    }

    /** Compiles [dir] to .pyc with the bundled interpreter (compileall, quiet, single process,
     * `nice`d). Also used by EngineUpdater after it replaces one engine's package. Concurrent
     * Python runs are fine meanwhile: .pyc files are written atomically, and a module that isn't
     * compiled yet is simply compiled by whoever imports it first, as before. */
    suspend fun precompile(context: Context, dir: File): Boolean = withContext(Dispatchers.IO) {
        val nativeLibDir = helperNativeLibDir(context) ?: return@withContext false
        val root = runtimeRoot(context)
        val interpreter = File(nativeLibDir, "libpython.so").absolutePath
        val started = System.currentTimeMillis()
        val result = runCatching {
            val process = ProcessBuilder(
                "/system/bin/nice", "-n", "10",
                // Single process: compileall's -j uses multiprocessing, whose SemLock needs
                // /dev/shm, which Android doesn't have (FileNotFoundError, found live). ~8s for the
                // whole tree on-device at nice 10, in the background.
                interpreter, "-m", "compileall", "-q", dir.absolutePath,
            )
                .redirectErrorStream(true)
                .apply {
                    environment()["PYTHONHOME"] = File(root, "usr").absolutePath
                    environment()["LD_LIBRARY_PATH"] = File(root, "usr/lib").absolutePath
                    environment()["PYTHONPATH"] = sitePackagesDir(context).absolutePath
                }
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        }
        val ok = result.getOrNull() == 0
        android.util.Log.i("PythonRuntime", "precompile ${dir.name}: ok=$ok in ${System.currentTimeMillis() - started}ms")
        ok
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
        if (!ensureProvisioned(context)) {
            error("Failed to provision the Python runtime")
        }
        if (USE_FORK_SERVER && server?.isAlive == true) {
            runViaServer(context, script, args, onLine)?.let { return@withContext it }
            restartServer() // it didn't take the job; start over below
        }
        // Not up: bring it up for the next job, but don't make this one wait for it.
        backgroundScope.launch { startServer(context.applicationContext) }
        runDirect(context, script, args, onLine)
    }

    /** One job as its own fresh interpreter process. */
    private suspend fun runDirect(
        context: Context,
        script: String,
        args: List<String>,
        onLine: suspend (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val scriptFile = File(runtimeRoot(context), script)
        val process = pythonProcess(context, listOf(scriptFile.absolutePath) + args).start()

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
