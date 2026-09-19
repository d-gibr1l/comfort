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
    // Boolean? (not a plain Boolean), tri-state — null means "the probe itself failed to run, we
    // genuinely don't know" and is NOT the same thing as "confirmed no extractor." A caller that
    // conflates the two (checking `!hasExtractor` rather than `hasExtractor == false`) accepts a
    // probe-subprocess crash as proof of "no extractor" — the exact bug this used to have: a
    // transient spawn/provisioning failure on one engine's probe, racing against the other
    // engine's probe genuinely succeeding, made the "fail open, don't skip anything" comment
    // below false in practice, since returning a bare `false` on failure is indistinguishable
    // from a real negative result to a caller doing plain boolean logic.
    data class Result(val galleryDlHasExtractor: Boolean?, val ytDlpHasExtractor: Boolean?)

    /** Runs both engines' probes concurrently — neither depends on the other's result — so the
     * added latency is roughly the slower of the two, not their sum. */
    suspend fun probeBoth(context: Context, url: String): Result = coroutineScope {
        val galleryDl = async { runProbe(context, "gallery_dl_wrapper.py", url) }
        val ytDlp = async { runProbe(context, "yt_dlp_wrapper.py", url) }
        Result(galleryDl.await(), ytDlp.await())
    }

    private suspend fun runProbe(context: Context, script: String, url: String): Boolean? {
        var lastLine: String? = null
        return try {
            PythonRuntime.run(context, script, listOf("probe", url)) { line -> lastLine = line }
            lastLine?.trim() == "1"
        } catch (e: CancellationException) {
            throw e // a Pause/Cancel mid-probe must still cancel the download immediately
        } catch (e: Exception) {
            null // provisioning/spawn failure — genuinely unknown, not a confirmed negative;
            // callers must treat this as "don't skip anything" themselves, not coerce it to false
        }
    }
}
