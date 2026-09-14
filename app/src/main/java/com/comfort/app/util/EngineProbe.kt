package com.comfort.app.util

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Live, no-network probe against the actually-bundled gallery-dl/yt-dlp packages — "does this
 * engine have a real extractor for this URL at all?" — used by DownloadWorker to skip a call
 * that both packages agree is doomed. Deliberately NOT a hardcoded Kotlin extractor-name list:
 * such a list mixes in many non-hostname match patterns and goes stale the moment
 * EngineUpdater.kt bumps either bundled package's version. Each call spawns a real subprocess
 * (same cost model as any other PythonRuntime.run call, but cheap — pure regex, no network).
 *
 * Deliberately not cached across retries/resumes, and never persisted to DownloadEntity:
 * EngineUpdater.kt can update either bundled package's extractor coverage between attempts, so a
 * stale cached result could keep skipping an engine that just gained a matching extractor.
 */
object EngineProbe {
    data class Result(val galleryDlHasExtractor: Boolean, val ytDlpHasExtractor: Boolean)

    /** Runs both engines' probes concurrently — neither depends on the other's result — so the
     * added latency is roughly the slower of the two, not their sum. */
    suspend fun probeBoth(context: Context, url: String): Result = coroutineScope {
        val galleryDl = async { runProbe(context, "gallery_dl_wrapper.py", url) }
        val ytDlp = async { runProbe(context, "yt_dlp_wrapper.py", url) }
        Result(galleryDl.await(), ytDlp.await())
    }

    private suspend fun runProbe(context: Context, script: String, url: String): Boolean {
        var lastLine: String? = null
        return try {
            PythonRuntime.run(context, script, listOf("probe", url)) { line -> lastLine = line }
            lastLine?.trim() == "1"
        } catch (e: CancellationException) {
            throw e // a Pause/Cancel mid-probe must still cancel the download immediately
        } catch (e: Exception) {
            false // provisioning/spawn failure — fail open to "don't skip anything"
        }
    }
}
