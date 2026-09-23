package com.comfort.app.util

import android.content.Context
import com.comfort.app.data.AppDatabase
import com.comfort.app.data.EngineUpdateChannel
import com.comfort.app.data.GalleryDlPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Checks for newer yt-dlp/gallery-dl/Instaloader releases than whatever's currently unpacked in the bundled
 * Python runtime's site-packages, and can install one in place — the in-app equivalent of
 * `pip install --upgrade yt-dlp`. Needed because both engines are bundled as static wheels in
 * assets/python_packages/ (see PythonRuntime's own doc comment) and only get refreshed when this
 * app itself ships a new APK version — but site extractors (Instagram, TikTok, ...) break against
 * the live site far more often than that, so this lets the engines be updated independently,
 * without waiting on an app release.
 *
 * Each engine can be checked/updated from one of two channels (per-engine preference, see
 * [GalleryDlPreferences.getYtDlpUpdateChannel]/[GalleryDlPreferences.getGalleryDlUpdateChannel]):
 * STABLE (the official tagged release, from PyPI — same as before this channel concept existed)
 * or BLEEDING_EDGE, whose actual source differs per engine since neither project publishes the
 * same *kind* of pre-release artifact:
 *  - yt-dlp: yt-dlp/yt-dlp-nightly-builds' own dated GitHub Releases (rebuilt from master roughly
 *    daily) — see [fetchLatestYtDlpNightly].
 *  - gallery-dl: there's no nightly build at all (confirmed live: mikf/gallery-dl's GitHub
 *    Releases ship zero assets now — development moved to Codeberg, see that release's own body
 *    text) — the closest equivalent is its live, unreleased Master branch source, fetched
 *    straight from Codeberg. See [fetchLatestGalleryDlMaster].
 *
 * Neither of those is a wheel, so [update] installs them differently — unpacking just the
 * relevant package directory out of a source tarball/zip and synthesizing a minimal dist-info
 * directory for it (a real `pip install` would build the wheel itself; there's no build backend
 * available on-device to do that, so this is a source-copy install instead).
 *
 * Caveat worth knowing: PythonRuntime.ensureProvisioned() re-unpacks everything from the bundled
 * assets from scratch whenever PROVISION_VERSION changes (i.e. the *next real app update* that
 * touches assets/python_packages/) — an engine updated in-app via this object reverts back to
 * whatever's bundled in that new APK at that point (STABLE, since that's what's bundled). It also
 * reverts back to the STABLE channel's wheel shape — a BLEEDING_EDGE install re-checks/re-installs
 * the same one tap away, same as any other update. */
object EngineUpdater {
    data class EngineInfo(
        val displayName: String,
        val pypiName: String,
        // The on-disk package/dist-info name — PyPI's own "name" field is hyphenated ("yt-dlp"),
        // but the installed dist-info directory and the importable package itself use
        // underscores ("yt_dlp"), the same PEP 503 normalization a real `pip install` produces.
        val packageDirName: String,
    )

    val YT_DLP = EngineInfo("yt-dlp", "yt-dlp", "yt_dlp")
    val GALLERY_DL = EngineInfo("gallery-dl", "gallery-dl", "gallery_dl")
    // Instagram posts/reels (see VideoSiteRouter.resolveEngine). STABLE is its PyPI wheel, same as
    // the other two; BLEEDING_EDGE is its GitHub master branch source — see fetchLatestInstaloaderMaster.
    val INSTALOADER = EngineInfo("Instaloader", "instaloader", "instaloader")
    val ALL = listOf(YT_DLP, GALLERY_DL, INSTALOADER)

    enum class ArtifactKind { WHEEL, TAR_GZ_SOURCE, GIT_ZIP_SOURCE }

    data class VersionStatus(
        val engine: EngineInfo,
        val channel: EngineUpdateChannel,
        val installedVersion: String?,
        val latestVersion: String?,
        val artifactUrl: String?,
        val artifactKind: ArtifactKind?,
        val sha256: String?,
    ) {
        // null-vs-null (both unknown) is deliberately NOT "update available" — there's no target
        // version to offer installing. But installedVersion == null with a real latestVersion is
        // NOT the same case and must still count as available: it means installedVersion() found
        // no ".dist-info" directory at all for this engine (reproduced live — gallery-dl's own
        // originally-bundled install only ever produced a ".data" directory, never ".dist-info",
        // so this read null from day one), not that the two versions genuinely match. The old
        // `installedVersion != null && ...` form silently rendered that as "Up to date" — a false
        // claim with nothing behind it — and since nothing ever treated it as an available update,
        // gallery-dl could never self-heal via auto-update either, unlike yt-dlp (whose bundled
        // install happened to already carry a real dist-info to begin with).
        val updateAvailable: Boolean
            get() = latestVersion != null && (installedVersion == null || installedVersion != latestVersion)
    }

    private data class FetchResult(
        val version: String,
        val artifactUrl: String,
        val artifactKind: ArtifactKind,
        val sha256: String?,
    )

    /** Reads the version straight off the installed dist-info directory's own name (e.g.
     * "yt_dlp-2026.8.19.dist-info" -> "2026.8.19") — the actual live version, which can be newer
     * than whatever's named in assets/python_packages/ once [update] has run at least once. */
    fun installedVersion(context: Context, engine: EngineInfo): String? {
        val dir = PythonRuntime.sitePackagesDir(context)
        val prefix = "${engine.packageDirName}-"
        return dir.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) && f.name.endsWith(".dist-info") }
            ?.firstOrNull()
            ?.name
            ?.removePrefix(prefix)
            ?.removeSuffix(".dist-info")
    }

    private fun httpGetText(urlStr: String): String? = runCatching {
        (URL(urlStr).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Comfort-App-Updater")
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }.getOrNull()

    /** Hits PyPI's own JSON API for the latest published release plus its wheel's direct download
     * URL and sha256 — the same source `pip install --upgrade` itself resolves against. Null on
     * any network/parse failure or if this release has no wheel (source-only) — callers treat
     * that the same as "nothing new to report right now", not a hard error. */
    private fun fetchLatestStable(engine: EngineInfo): FetchResult? = runCatching {
        val json = httpGetText("https://pypi.org/pypi/${engine.pypiName}/json") ?: return@runCatching null
        val root = JSONObject(json)
        val version = root.getJSONObject("info").getString("version")
        val urls = root.getJSONArray("urls")
        for (i in 0 until urls.length()) {
            val entry = urls.getJSONObject(i)
            if (entry.optString("packagetype") == "bdist_wheel") {
                val wheelUrl = entry.getString("url")
                val sha256 = entry.optJSONObject("digests")?.optString("sha256")?.takeIf { it.isNotBlank() }
                return@runCatching FetchResult(version, wheelUrl, ArtifactKind.WHEEL, sha256)
            }
        }
        null
    }.getOrNull()

    /** yt-dlp's own Nightly channel: a separate repo (yt-dlp/yt-dlp-nightly-builds) that rebuilds
     * from master and cuts a new dated GitHub Release roughly daily — confirmed live there's no
     * wheel asset published there (only standalone binaries + a source tarball), unlike PyPI's
     * stable releases, so this is installed from `yt-dlp.tar.gz` (a plain source tree, `yt-dlp/
     * yt_dlp/...`) rather than a wheel. `SHA2-256SUMS` (a published checksums file, same shape as
     * `sha256sum`'s own output) is fetched alongside it purely to verify that tarball the same way
     * PyPI's own published digest verifies a stable wheel. */
    private fun fetchLatestYtDlpNightly(): FetchResult? = runCatching {
        val json = httpGetText("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
            ?: return@runCatching null
        val root = JSONObject(json)
        val version = root.getString("tag_name")
        val assets = root.getJSONArray("assets")
        var tarUrl: String? = null
        var sumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            when (asset.optString("name")) {
                "yt-dlp.tar.gz" -> tarUrl = asset.getString("browser_download_url")
                "SHA2-256SUMS" -> sumsUrl = asset.getString("browser_download_url")
            }
        }
        val artifactUrl = tarUrl ?: return@runCatching null
        val sha256 = sumsUrl?.let { httpGetText(it) }
            ?.lineSequence()
            ?.firstOrNull { it.trim().endsWith("yt-dlp.tar.gz") }
            ?.trim()?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }
        FetchResult(version, artifactUrl, ArtifactKind.TAR_GZ_SOURCE, sha256)
    }.getOrNull()

    /** gallery-dl's closest equivalent to a "bleeding edge" channel — it has no nightly build at
     * all (see this object's own doc comment), so this reads the live HEAD commit of its Master
     * branch straight from Codeberg (where development actually happens now — GitHub's own
     * mirror's releases ship zero assets, confirmed live) and installs directly from that commit's
     * source zip. No published checksum exists for this endpoint the way PyPI/GitHub Releases
     * publish one for their own artifacts — sha256 is null here, same as PyPI already treats a
     * source-only release with no digest; verification falls back to plain HTTPS transport
     * integrity for this one channel. [version] is the commit's own short hash rather than a
     * package version string (gallery_dl/version.py's own `__version__`, e.g. "1.32.12-dev", would
     * need a second fetch after already knowing which commit to read it from) — good enough to
     * tell "still on the commit I last installed" from "there's a newer one," which is all a
     * Master channel promises anyway. */
    private fun fetchLatestGalleryDlMaster(): FetchResult? = runCatching {
        val json = httpGetText("https://codeberg.org/api/v1/repos/mikf/gallery-dl/branches/master")
            ?: return@runCatching null
        val fullSha = JSONObject(json).getJSONObject("commit").getString("id")
        val version = "master-${fullSha.take(10)}"
        FetchResult(version, "https://codeberg.org/mikf/gallery-dl/archive/master.zip", ArtifactKind.GIT_ZIP_SOURCE, null)
    }.getOrNull()

    /** Instaloader's own "bleeding edge": its GitHub master branch (development happens there; its
     * releases are cut from it onto PyPI). Same shape as [fetchLatestGalleryDlMaster] — a source
     * zip with no published checksum — but pinned to the exact commit just read rather than the
     * moving "master" ref, so what gets installed is guaranteed to be the version shown. GitHub
     * wraps it as `instaloader-<sha>/instaloader/...`, which [installFromGitZip] already handles. */
    private fun fetchLatestInstaloaderMaster(): FetchResult? = runCatching {
        val json = httpGetText("https://api.github.com/repos/instaloader/instaloader/branches/master")
            ?: return@runCatching null
        val fullSha = JSONObject(json).getJSONObject("commit").getString("sha")
        FetchResult(
            "master-${fullSha.take(10)}",
            "https://github.com/instaloader/instaloader/archive/$fullSha.zip",
            ArtifactKind.GIT_ZIP_SOURCE,
            null,
        )
    }.getOrNull()

    private fun channelFor(context: Context, engine: EngineInfo): EngineUpdateChannel = when (engine) {
        YT_DLP -> GalleryDlPreferences.getYtDlpUpdateChannel(context)
        INSTALOADER -> GalleryDlPreferences.getInstaloaderUpdateChannel(context)
        else -> GalleryDlPreferences.getGalleryDlUpdateChannel(context)
    }

    suspend fun checkAll(context: Context): List<VersionStatus> = withContext(Dispatchers.IO) {
        ALL.map { engine ->
            val channel = channelFor(context, engine)
            val installed = installedVersion(context, engine)
            val latest = when (channel) {
                EngineUpdateChannel.STABLE -> fetchLatestStable(engine)
                EngineUpdateChannel.BLEEDING_EDGE -> when (engine) {
                    YT_DLP -> fetchLatestYtDlpNightly()
                    INSTALOADER -> fetchLatestInstaloaderMaster()
                    else -> fetchLatestGalleryDlMaster()
                }
            }
            VersionStatus(engine, channel, installed, latest?.version, latest?.artifactUrl, latest?.artifactKind, latest?.sha256)
        }
    }

    private fun sha256Of(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads [status]'s artifact, verifies it against its published sha256 when there is one,
     * then replaces whatever's currently installed for [status]'s engine in site-packages with the
     * new artifact's contents. A wheel is just a zip with the package directory and its dist-info
     * at the top level — the same shape PythonRuntime.ensureProvisioned() already unpacks bundled
     * wheels from, just against a freshly downloaded one here instead of an asset. A source tarball
     * or repo zip (BLEEDING_EDGE channels) carries no dist-info of its own, so one's synthesized
     * here afterward purely so [installedVersion] keeps working the same way regardless of channel. */
    suspend fun update(context: Context, status: VersionStatus): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val artifactUrl = status.artifactUrl ?: error("Nothing to install")
                val artifactKind = status.artifactKind ?: error("Nothing to install")
                val version = status.latestVersion ?: error("Nothing to install")
                val engine = status.engine

                // Refuse outright rather than let it race an active download: Python lazy-loads
                // modules (yt-dlp's extractors especially), so deleting and replacing an engine's
                // package directory out from under a running download crashes it with a
                // ModuleNotFoundError the instant it next tries to import anything from here.
                // Checked fresh right before acting (not cached), since "no downloads running" can
                // stop being true between when the user opened this screen and when they tapped
                // Update.
                if (AppDatabase.getDatabase(context).downloadDao().getRunningOnce().isNotEmpty()) {
                    error("Can't update while a download is running — wait for it to finish first")
                }
                if (!PythonRuntime.ensureProvisioned(context)) error("Python runtime isn't provisioned yet")

                val tempFile = File(context.cacheDir, "${engine.packageDirName}_update.download")
                (URL(artifactUrl).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    try {
                        inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    } finally {
                        disconnect()
                    }
                }

                if (!status.sha256.isNullOrBlank()) {
                    val actual = sha256Of(tempFile)
                    if (!actual.equals(status.sha256, ignoreCase = true)) {
                        tempFile.delete()
                        error("Downloaded file didn't match the checksum published for it — aborted, nothing was installed")
                    }
                }

                // Shares PythonRuntime's own provisioning Mutex — without it, this delete+install
                // could race a concurrent first-ever ensureProvisioned() (e.g. a download that
                // started at the same moment on a fresh install) the same way two provisions could
                // race each other, clobbering whichever writer loses.
                PythonRuntime.withProvisionLock {
                    val sitePackages = PythonRuntime.sitePackagesDir(context)
                    // The old install first — the new artifact's own contents fully replace the
                    // package and dist-info directories, but a file the *previous* version shipped
                    // that the new one no longer does (a removed submodule, say) would otherwise
                    // linger forever.
                    sitePackages.listFiles { f ->
                        f.name == engine.packageDirName || (f.name.startsWith("${engine.packageDirName}-") && f.name.endsWith(".dist-info"))
                    }?.forEach { it.deleteRecursively() }

                    when (artifactKind) {
                        ArtifactKind.WHEEL -> tempFile.inputStream().use { PythonRuntime.unzipStreamTo(it, sitePackages) }
                        ArtifactKind.TAR_GZ_SOURCE -> installFromTarGz(tempFile, sitePackages, engine.packageDirName)
                        ArtifactKind.GIT_ZIP_SOURCE -> installFromGitZip(tempFile, sitePackages, engine.packageDirName)
                    }

                    if (artifactKind != ArtifactKind.WHEEL) {
                        File(sitePackages, "${engine.packageDirName}-$version.dist-info").mkdirs()
                    }
                }
                tempFile.delete()

                installedVersion(context, engine) ?: error("Update installed but its version couldn't be read back")
            }
        }

    /** A GitHub/Codeberg source zip wraps everything in one top-level directory named after the
     * repo (confirmed live for mikf/gallery-dl's Codeberg archive) — this pulls out just the
     * `<topDir>/<packageDirName>/` subtree (skipping docs/tests/etc. entirely) into
     * `sitePackages/<packageDirName>/`, detecting the actual top-level directory name from the
     * first entry rather than hardcoding it, in case that ever changes upstream. */
    private fun installFromGitZip(zipFile: File, sitePackages: File, packageDirName: String) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var topDir: String? = null
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (topDir == null && name.contains('/')) topDir = name.substringBefore('/')
                val packagePrefix = "${topDir.orEmpty()}/$packageDirName/"
                if (topDir != null && name.startsWith(packagePrefix)) {
                    val relative = name.removePrefix(packagePrefix)
                    if (relative.isNotEmpty()) {
                        val outFile = File(sitePackages, "$packageDirName/$relative")
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Minimal POSIX/GNU tar reader — just enough to pull `<topDir>/<packageDirName>/...` out of a
     * gzipped source tarball (yt-dlp's own nightly `yt-dlp.tar.gz`, confirmed live shaped as
     * `yt-dlp/yt_dlp/...` alongside its docs/tests/etc.) the same way [installFromGitZip] does for
     * a zip, without pulling in a whole archive library for one artifact shape. No `java.util.zip`
     * equivalent of ZipInputStream exists for tar, so this walks the format directly: a sequence of
     * 512-byte headers, each followed by that entry's data padded up to the next 512-byte boundary.
     * Handles the ustar `prefix` field (a long path split across `name`+`prefix`) since yt-dlp's own
     * longest path already comes close to plain `name`'s 100-char limit; doesn't handle GNU
     * long-name entries (typeflag 'L') — none exist in this tarball today (confirmed live, longest
     * entry is 84 chars), and one appearing would just silently skip that single file's data rather
     * than misparse the rest of the archive, since its own recorded size still advances the reader
     * correctly either way. */
    private fun installFromTarGz(tarGzFile: File, sitePackages: File, packageDirName: String) {
        BufferedInputStream(GZIPInputStream(tarGzFile.inputStream())).use { input ->
            var topDir: String? = null
            val header = ByteArray(512)
            while (true) {
                val read = readFully(input, header)
                if (read < 512 || header.all { it == ZERO_BYTE }) break

                val name = header.tarField(0, 100)
                if (name.isEmpty()) break
                val prefix = header.tarField(345, 155)
                val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

                val sizeField = header.tarField(124, 12).trim()
                val size = if (sizeField.isBlank()) 0L else sizeField.toLong(8)
                val typeflag = header[156].toInt().toChar()
                val paddedSize = ((size + 511) / 512) * 512

                if (topDir == null && fullName.contains('/')) topDir = fullName.substringBefore('/')
                val packagePrefix = "${topDir.orEmpty()}/$packageDirName/"

                if ((typeflag == '0' || typeflag == ' ') && topDir != null && fullName.startsWith(packagePrefix)) {
                    val relative = fullName.removePrefix(packagePrefix)
                    val outFile = File(sitePackages, "$packageDirName/$relative")
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> copyExactly(input, output, size) }
                    skipFully(input, paddedSize - size)
                } else {
                    skipFully(input, paddedSize)
                }
            }
        }
    }

    private const val ZERO_BYTE: Byte = 0

    // A tar header field is a fixed-width byte range, terminated by a zero byte (and often further
    // zero- or space-padded after that) rather than a plain fixed-length string — this stops at
    // the first zero byte within the field instead of including the padding as literal characters.
    private fun ByteArray.tarField(offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && this[end] != ZERO_BYTE) end++
        return String(this, offset, end - offset, Charsets.US_ASCII)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n == -1) break
            total += n
        }
        return total
    }

    // InputStream.skip() isn't guaranteed to skip everything it's asked to in one call (especially
    // through a GZIPInputStream) — this loops a real read until [count] bytes are actually gone.
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n == -1) break
            remaining -= n
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n == -1) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }
}
