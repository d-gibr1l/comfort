package com.comfort.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Checks PyPI for newer yt-dlp/gallery-dl releases than whatever's currently unpacked in the
 * bundled Python runtime's site-packages, and can install one in place — the in-app equivalent of
 * `pip install --upgrade yt-dlp`. Needed because both engines are bundled as static wheels in
 * assets/python_packages/ (see PythonRuntime's own doc comment) and only get refreshed when this
 * app itself ships a new APK version — but site extractors (Instagram, TikTok, ...) break against
 * the live site far more often than that, so this lets the engines be updated independently,
 * without waiting on an app release.
 *
 * Caveat worth knowing: PythonRuntime.ensureProvisioned() re-unpacks everything from the bundled
 * assets from scratch whenever PROVISION_VERSION changes (i.e. the *next real app update* that
 * touches assets/python_packages/) — an engine updated in-app via this object reverts back to
 * whatever's bundled in that new APK at that point. Acceptable for now: it only resets on an
 * actual app update, not on every launch, and re-checking/re-updating afterward is one tap. */
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
    val ALL = listOf(YT_DLP, GALLERY_DL)

    data class VersionStatus(
        val engine: EngineInfo,
        val installedVersion: String?,
        val latestVersion: String?,
        val wheelUrl: String?,
        val sha256: String?,
    ) {
        // null-vs-null (both unknown) is deliberately NOT "update available" — nothing to act on.
        val updateAvailable: Boolean
            get() = installedVersion != null && latestVersion != null && installedVersion != latestVersion
    }

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

    /** Hits PyPI's own JSON API for the latest published release plus its wheel's direct download
     * URL and sha256 — the same source `pip install --upgrade` itself resolves against. Null on
     * any network/parse failure or if this release has no wheel (source-only) — callers treat
     * that the same as "nothing new to report right now", not a hard error. */
    private fun fetchLatest(engine: EngineInfo): Triple<String, String, String?>? = runCatching {
        val json = (URL("https://pypi.org/pypi/${engine.pypiName}/json").openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
        val root = JSONObject(json)
        val version = root.getJSONObject("info").getString("version")
        val urls = root.getJSONArray("urls")
        for (i in 0 until urls.length()) {
            val entry = urls.getJSONObject(i)
            if (entry.optString("packagetype") == "bdist_wheel") {
                val wheelUrl = entry.getString("url")
                val sha256 = entry.optJSONObject("digests")?.optString("sha256")?.takeIf { it.isNotBlank() }
                return@runCatching Triple(version, wheelUrl, sha256)
            }
        }
        null
    }.getOrNull()

    suspend fun checkAll(context: Context): List<VersionStatus> = withContext(Dispatchers.IO) {
        ALL.map { engine ->
            val installed = installedVersion(context, engine)
            val latest = fetchLatest(engine)
            VersionStatus(engine, installed, latest?.first, latest?.second, latest?.third)
        }
    }

    /** Downloads [wheelUrl], verifies it against [sha256] when PyPI published one, then replaces
     * whatever's currently installed for [engine] in site-packages with the new wheel's contents.
     * A wheel is just a zip with the package directory and its dist-info at the top level — the
     * same shape PythonRuntime.ensureProvisioned() already unpacks bundled wheels from, just
     * against a freshly downloaded one here instead of an asset. */
    suspend fun update(context: Context, engine: EngineInfo, wheelUrl: String, sha256: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PythonRuntime.ensureProvisioned(context)) error("Python runtime isn't provisioned yet")

                val tempFile = File(context.cacheDir, "${engine.packageDirName}_update.whl")
                (URL(wheelUrl).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    try {
                        inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    } finally {
                        disconnect()
                    }
                }

                if (!sha256.isNullOrBlank()) {
                    val actual = tempFile.inputStream().use { stream ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (!actual.equals(sha256, ignoreCase = true)) {
                        tempFile.delete()
                        error("Downloaded file didn't match the checksum PyPI published for it — aborted, nothing was installed")
                    }
                }

                val sitePackages = PythonRuntime.sitePackagesDir(context)
                // The old install first — the new wheel's own contents fully replace the package
                // and dist-info directories, but a file the *previous* version shipped that the
                // new one no longer does (a removed submodule, say) would otherwise linger forever.
                sitePackages.listFiles { f ->
                    f.name == engine.packageDirName || (f.name.startsWith("${engine.packageDirName}-") && f.name.endsWith(".dist-info"))
                }?.forEach { it.deleteRecursively() }

                tempFile.inputStream().use { PythonRuntime.unzipStreamTo(it, sitePackages) }
                tempFile.delete()

                installedVersion(context, engine) ?: error("Update installed but its version couldn't be read back")
            }
        }
}
