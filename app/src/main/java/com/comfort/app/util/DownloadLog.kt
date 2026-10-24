package com.comfort.app.util

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The downloads' own log, kept in the app's storage: every line an engine reports, the start and
 * the outcome of each download, by time and download id. Exported from Settings > Advanced >
 * Export logs, with the system log's lines from the app.
 *
 * Only the system log had these before, and it keeps a few minutes of the whole phone at most and
 * nothing past a reboot; a failure in Secure Folder (which adb can't read into) had to be caught
 * there in time. Two files of [MAX_FILE_BYTES]: the current one, and the one before it. */
object DownloadLog {
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val DIR = "logs"
    private const val CURRENT = "downloads.log"
    private const val PREVIOUS = "downloads.1.log"

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var size = 0L
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Adds [text] for download [downloadId]. Never throws: a log that can't be written mustn't
     * fail the download. */
    fun line(context: Context, downloadId: String, text: String) {
        // Without the 0x1E an engine's report line starts with (comfort_events.py): it's only there
        // to tell those apart on the pipe.
        val entry = "${time.format(Date())} ${downloadId.take(8)} ${text.trimStart('\u001E').replace('\n', ' ')}\n"
        synchronized(lock) {
            runCatching {
                val out = writer ?: open(context)
                out.write(entry)
                out.flush()
                size += entry.length
                if (size > MAX_FILE_BYTES) rotate(context)
            }.onFailure { close() }
        }
    }

    private fun open(context: Context): BufferedWriter {
        val f = File(File(context.filesDir, DIR).apply { mkdirs() }, CURRENT)
        size = f.length()
        return BufferedWriter(FileWriter(f, true)).also { writer = it }
    }

    private fun rotate(context: Context) {
        close()
        val dir = File(context.filesDir, DIR)
        File(dir, CURRENT).renameTo(File(dir, PREVIOUS).apply { delete() })
    }

    private fun close() {
        runCatching { writer?.close() }
        writer = null
        size = 0L
    }

    /** This log's lines, oldest first, with logins blanked ([redact]) — for Settings' log viewer. */
    fun read(context: Context): List<String> {
        val dir = File(context.filesDir, DIR)
        val text = synchronized(lock) {
            writer?.flush()
            listOf(PREVIOUS, CURRENT).map { File(dir, it) }.filter { it.isFile }.joinToString("") { it.readText() }
        }
        return redact(text).lines().filter { it.isNotBlank() }
    }

    /** Empties this log. */
    fun clear(context: Context) {
        synchronized(lock) {
            close()
            val dir = File(context.filesDir, DIR)
            listOf(PREVIOUS, CURRENT).forEach { File(dir, it).delete() }
        }
    }

    /** Writes the export to [uri]: the app and phone, then this log (oldest first), then the system
     * log's lines from the app (Android only gives an app its own). Logins are blanked first — see
     * [redact]. Runs on the caller's thread; call it off the main one. */
    fun export(context: Context, uri: Uri) {
        val dir = File(context.filesDir, DIR)
        val ownLog = synchronized(lock) {
            writer?.flush()
            listOf(PREVIOUS, CURRENT).map { File(dir, it) }.filter { it.isFile }.joinToString("") { it.readText() }
        }
        val systemLog = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        }.getOrElse { "(couldn't read the system log: ${it.message})" }
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val header = buildString {
            appendLine("Comfort ${info?.versionName} (${info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) }})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
            // Not 0 in a work profile or Secure Folder.
            appendLine("User ${android.os.Process.myUserHandle().hashCode()}")
            appendLine("Exported ${time.format(Date())}")
        }
        val text = buildString {
            append(header)
            appendLine()
            appendLine("===== Downloads =====")
            append(ownLog.ifEmpty { "(nothing yet)\n" })
            appendLine()
            appendLine("===== System log =====")
            append(systemLog)
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(redact(text)) }
            ?: error("Couldn't open the file")
    }

    // A login in a link ("socks5://user:password@host"), and cookie or authorisation values in a
    // header or a field.
    // A cookie or authorisation header: its whole value ("Bearer <token>", "a=1; b=2").
    private val SECRET_HEADER = Regex(
        """^(.*?\b(?:set-cookie|cookie|proxy-authorization|authorization)\s*:\s*).+$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val CREDENTIALS_IN_URL = Regex("""(\b[a-z][a-z0-9+.-]*://)[^\s/@:]+:[^\s/@]+@""", RegexOption.IGNORE_CASE)
    private val SECRET_FIELD = Regex(
        """((?:set-)?cookie|authorization|proxy-authorization|x-csrf-token|x-ig-www-claim|sessionid|csrftoken|password|token)(["']?\s*[:=]\s*["']?)[^\s"',;]+""",
        RegexOption.IGNORE_CASE,
    )

    /** [text] with logins blanked: a proxy's user and password, and cookie, session, token and
     * authorisation values. The links downloaded stay, as that's what a failure is traced from. */
    internal fun redact(text: String): String =
        text.replace(SECRET_HEADER, "$1***").replace(CREDENTIALS_IN_URL, "$1***:***@").replace(SECRET_FIELD, "$1$2***")
}
