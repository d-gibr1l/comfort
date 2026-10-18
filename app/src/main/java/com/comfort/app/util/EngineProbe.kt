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
        // A share link that redirects (Reddit's /s/<code>, redd.it, pin.it, Instagram's /share/)
        // never matches yt-dlp's own patterns, which want the post's real address, though its
        // generic extractor follows the redirect fine. So a "no" from yt-dlp there isn't a no:
        // it's unknown, which no plan skips yt-dlp for.
        val yt = ytDlp.await()
        val gd = galleryDl.await()
        val ytVerdict = when {
            yt != false -> yt
            ytDlpNoIsUnreliable(url) -> null
            // Any other link yt-dlp says "no" to while gallery-dl claims it: a share format not
            // listed above would leave yt-dlp out entirely. Follow its redirects and ask yt-dlp
            // about where it lands — if it knows that address, its "no" was about the short link
            // only. (A site that really is gallery-dl's alone, Pixiv say, doesn't redirect.)
            gd == true -> resolveRedirect(url)?.let { target ->
                if (runProbe(context, "yt_dlp_wrapper.py", target) == true) null else false
            } ?: false
            else -> false
        }
        Result(gd, ytVerdict)
    }

    /** Where [url] redirects to (following up to [MAX_REDIRECTS] hops), or null when it doesn't
     * redirect or the request fails. Only the headers are read: no page is downloaded. */
    private suspend fun resolveRedirect(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var current = url
        var redirected = false
        repeat(MAX_REDIRECTS) {
            val next = runCatching {
                val connection = java.net.URL(current).openConnection() as java.net.HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = REDIRECT_TIMEOUT_MS
                    connection.readTimeout = REDIRECT_TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                    val code = connection.responseCode
                    if (code in 300..399) connection.getHeaderField("Location")?.let { java.net.URL(java.net.URL(current), it).toString() } else null
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: return@withContext current.takeIf { redirected }
            if (next == current) return@withContext current.takeIf { redirected }
            current = next
            redirected = true
        }
        current.takeIf { redirected }
    }

    private const val MAX_REDIRECTS = 5
    private const val REDIRECT_TIMEOUT_MS = 5_000
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Whether yt-dlp's probe can't be trusted to say "no" for [url]: a share link that only
     * resolves to the real post by redirecting. Checked against the bundled engines (probe on
     * each format): Reddit /r|u/<name>/s/<code> and redd.it/<id>, Pinterest's pin.it, and
     * Instagram's /share/ links read "no" from yt-dlp while gallery-dl claims them. */
    internal fun ytDlpNoIsUnreliable(url: String): Boolean = SHARE_LINKS.any { it.containsMatchIn(url) }

    private val SHARE_LINKS = listOf(
        Regex("""^https?://([\w-]+\.)?reddit\.com/(r|u|user)/[^/]+/s/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(www\.)?redd\.it/\w+""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(www\.)?pin\.it/\w+""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(www\.)?instagram\.com/share/""", RegexOption.IGNORE_CASE),
    )

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
