package com.comfort.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Checks GitHub Releases on the app's own open-source repo for a newer build than what's
 * installed, and can download + launch the system installer for one — this app isn't on the Play
 * Store, so unlike a Play-distributed app it has no built-in update channel of its own at all
 * without this. Mirrors EngineUpdater's own check/download shape (see its doc comment) but the
 * artifact here is a whole new APK, not a wheel dropped into site-packages, so "installing" one
 * means handing it to Android's own package installer rather than unzipping it in place.
 *
 * Releases are expected to be tagged like "v1.2.0" (a leading "v" is stripped) with the release
 * APKs attached, named like the build's own splits: "Comfort-1.2.0-arm64-v8a.apk", ...,
 * "Comfort-1.2.0-universal.apk" — see fetchLatest for how one is picked. */
object AppUpdater {
    private const val REPO = "d-gibr1l/comfort"

    data class UpdateStatus(
        val installedVersion: String,
        val latestVersion: String?,
        val downloadUrl: String?,
        val releaseNotes: String?,
    ) {
        // null latestVersion (no releases yet, or the check failed) is deliberately NOT "update
        // available" — same reasoning as EngineUpdater.VersionStatus.
        val updateAvailable: Boolean
            get() = latestVersion != null && isNewer(latestVersion, installedVersion)
    }

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"

    /** Dotted-numeric version compare ("1.10.0" > "1.9.0"), not a lexicographic string compare
     * (which would get that backwards). Falls back to treating a missing/non-numeric component as
     * 0, so "1.2" vs "1.2.1" and other differing-length version strings still compare sanely. */
    internal fun isNewer(a: String, b: String): Boolean {
        val partsA = a.removePrefix("v").split(".")
        val partsB = b.removePrefix("v").split(".")
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val n1 = partsA.getOrNull(i)?.toIntOrNull() ?: 0
            val n2 = partsB.getOrNull(i)?.toIntOrNull() ?: 0
            if (n1 != n2) return n1 > n2
        }
        return false
    }

    /** Hits the repo's own "latest release" endpoint — null on any network/parse failure, no
     * releases published yet, or a release with no .apk asset attached, all treated the same as
     * "nothing new to report right now" rather than a hard error (identical idiom to
     * EngineUpdater.fetchLatest). */
    private fun fetchLatest(): Triple<String, String, String?>? = runCatching {
        val json = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            // GitHub's API 403s generic-looking User-Agent-less requests.
            setRequestProperty("User-Agent", "Comfort-App-Updater")
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
        val root = JSONObject(json)
        val version = root.getString("tag_name").removePrefix("v")
        val notes = root.optString("body").takeIf { it.isNotBlank() }
        val assets: JSONArray = root.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk") }
            .associate { it.getString("name") to it.getString("browser_download_url") }
        // A release carries the split APKs (Comfort-<version>-arm64-v8a.apk, ...-universal.apk, ...),
        // so take the one built for this phone's CPU, then the universal one, then any APK — the
        // first .apk alone could be another CPU's build, which the installer rejects.
        val preferred = android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> apks.entries.firstOrNull { it.key.endsWith("-$abi.apk") } }
            ?: apks.entries.firstOrNull { it.key.endsWith("-universal.apk") }
            ?: apks.entries.firstOrNull()
        preferred?.let { Triple(version, it.value, notes) }
    }.getOrNull()

    suspend fun check(context: Context): UpdateStatus = withContext(Dispatchers.IO) {
        val installed = installedVersion(context)
        val latest = fetchLatest()
        UpdateStatus(installed, latest?.first, latest?.second, latest?.third)
    }

    /** Downloads [downloadUrl] into the app's own cache dir — content-provider'd back out to the
     * system installer via [installApk], never written anywhere world-readable. No checksum step
     * here unlike EngineUpdater.update: GitHub only publishes one over plain HTTPS from this
     * project's own repo, without the separate published-digest EngineUpdater cross-checks PyPI's
     * wheels against. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val downloadProgress = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
    val downloadError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun startDownload(context: Context, status: UpdateStatus) {
        if (downloadProgress.value != null) return // Already downloading
        val downloadUrl = status.downloadUrl ?: return
        
        // Clean up any old updates (both incomplete .tmp and old .apk files) so they don't waste space
        context.cacheDir.listFiles()?.filter { it.name.startsWith("Comfort-") && it.name != "Comfort-${status.latestVersion}.apk" }?.forEach { it.delete() }
        
        val apkFile = File(context.cacheDir, "Comfort-${status.latestVersion}.apk")
        
        if (apkFile.exists() && apkFile.length() > 0L) {
            promptInstall(context, apkFile)
            return
        }

        downloadProgress.value = 0f
        downloadError.value = null
        
        val appContext = context.applicationContext
        
        scope.launch {
            runCatching {
                val tempFile = File(appContext.cacheDir, "Comfort-${status.latestVersion}.apk.tmp")
                (URL(downloadUrl).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    try {
                        val totalBytes = contentLength
                        var downloadedBytes = 0L
                        inputStream.use { input ->
                            tempFile.outputStream().use { output -> 
                                val buffer = ByteArray(8192)
                                var bytes = input.read(buffer)
                                while (bytes >= 0) {
                                    output.write(buffer, 0, bytes)
                                    downloadedBytes += bytes
                                    if (totalBytes > 0) {
                                        downloadProgress.value = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                    }
                                    bytes = input.read(buffer)
                                }
                            }
                        }
                    } finally {
                        disconnect()
                    }
                }
                tempFile.renameTo(apkFile)
                downloadProgress.value = null
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    promptInstall(appContext, apkFile)
                }
            }.onFailure { e ->
                downloadError.value = "Couldn't download update: ${e.message}"
                downloadProgress.value = null
            }
        }
    }
    
    private fun promptInstall(context: Context, apkFile: File) {
        if (canInstall(context)) {
            installApk(context, apkFile)
        } else {
            downloadError.value = "Allow installing from this app in the settings screen that just opened, then tap Update again."
            requestInstallPermission(context)
        }
    }

    /** True once Android will actually let [installApk] proceed without itself being silently
     * dropped — API 26+ gates "install from this app" per-app rather than with one device-wide
     * Settings toggle, off by default for every app including this one. [installApk] launches the
     * one settings screen that grants it if this is false, the same "walk the user to the one
     * Settings screen that unlocks this" shape Settings > Downloads > Reliability's own battery-
     * optimization exemption already uses for the same reason (a system permission with no runtime
     * request dialog of its own). Always true below API 26 — the toggle didn't exist yet, sideload
     * installs were always allowed. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system settings screen where the user grants "install unknown apps" for this app
     * specifically — call when [canInstall] is false, then have the user retry [installApk] once
     * they're back. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands [apkFile] to Android's own package installer UI — same flow as manually opening a
     * downloaded APK from Downloads, just launched directly instead of making the user go find it.
     * A content:// URI via FileProvider (declared in the manifest against @xml/file_paths), not a
     * plain file:// one — Android 7+ blocks file:// Uris crossing the app-to-installer boundary
     * (FileUriExposedException) the same reasoning as every other cross-app file hand-off in this
     * app already follows. */
    fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
