package com.comfort.app.worker

import com.comfort.app.data.DownloadEngine
import com.comfort.app.util.EngineProbe

/** Runs one engine for the download and answers what a plan needs to decide its next step.
 * DownloadWorker's implementation runs the real Python wrappers; tests use a fake. */
internal interface EngineExecutor {
    /** Runs [engine]'s wrapper to completion. [excludeVideo] only applies to gallery-dl. */
    suspend fun run(engine: DownloadEngine, excludeVideo: Boolean = false)
    /** Whether gallery-dl and yt-dlp have a real extractor for the link (no network). */
    suspend fun probe(): EngineProbe.Result
    /** Files this download has saved so far, across every engine that ran. */
    val savedCount: Int
    /** Paused or cancelled: no further engine should start. */
    val isStopped: Boolean
    /** The download can't run at all — reported as its error (an engine it needs is turned off). */
    fun fail(message: String)
}

/** Which engines the user has switched on, and the order they try a link that more than one of
 * them can handle (Settings › Updates › Engines). Spotify isn't an engine of its own here: it
 * downloads through yt-dlp, so it's available exactly when yt-dlp is. */
data class EngineChoice(val enabled: Set<DownloadEngine>, val order: List<DownloadEngine>) {
    fun isOn(engine: DownloadEngine) = engine in enabled

    /** Whether [a] tries before [b]. */
    fun before(a: DownloadEngine, b: DownloadEngine): Boolean {
        val ia = order.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it }
        val ib = order.indexOf(b).let { if (it < 0) Int.MAX_VALUE else it }
        return ia <= ib
    }

    companion object {
        /** The user's current choice, from Settings. */
        fun load(context: android.content.Context): EngineChoice {
            val saved = com.comfort.app.data.GalleryDlPreferences.getEngineOrder(context)
                ?.mapNotNull { name -> ORDERABLE.firstOrNull { it.name == name } }
                .orEmpty()
            return EngineChoice(
                enabled = ORDERABLE.filter { com.comfort.app.data.GalleryDlPreferences.isEngineEnabled(context, it) }.toSet(),
                // Anything missing from a saved order (a newer engine) goes at the end.
                order = saved + ORDERABLE.filter { it !in saved },
            )
        }

        /** The engines the order covers: the ones a link can have more than one of. */
        val ORDERABLE = listOf(DownloadEngine.INSTALOADER, DownloadEngine.GALLERY_DL, DownloadEngine.YT_DLP)
        val DEFAULT = EngineChoice(ORDERABLE.toSet(), ORDERABLE)
    }
}

/** Which engines a download runs, and in what order — each plan is one fallback strategy. */
internal sealed interface EnginePlan {
    suspend fun execute(executor: EngineExecutor)

    /** Just this engine (yt-dlp, Spotify, or Instaloader with nothing to fall back to). gallery-dl
     * alone (yt-dlp turned off) keeps its videos: there's nothing to hand them to. */
    data class Single(val engine: DownloadEngine) : EnginePlan {
        override suspend fun execute(executor: EngineExecutor) = executor.run(engine)
    }

    /** No engine that can handle the link is on: the download fails straight away with [message]
     * instead of an engine error that doesn't say why. */
    data class Unavailable(val message: String) : EnginePlan {
        override suspend fun execute(executor: EngineExecutor) = executor.fail(message)
    }

    /** yt-dlp first (the user put it above gallery-dl, or gallery-dl's listing timed out): then
     * gallery-dl with video excluded — for an image post yt-dlp saved nothing from, or for the
     * pictures of a post whose listing showed some ([alsoImages]). */
    data class YtDlpFirst(val alsoImages: Boolean) : EnginePlan {
        override suspend fun execute(executor: EngineExecutor) {
            executor.run(DownloadEngine.YT_DLP)
            if (!executor.isStopped && (executor.savedCount == 0 || alsoImages)) {
                executor.run(DownloadEngine.GALLERY_DL, excludeVideo = true)
            }
        }
    }

    /** [first], then [fallback] only if [first] saved nothing — Instaloader falls back to the
     * routing every Instagram link had before it (private post, rate limit, a site change, ...).
     * Its own error still shows if the fallback fails too, since errors are first-wins. */
    data class FallbackIfNothingSaved(val first: EnginePlan, val fallback: EnginePlan) : EnginePlan {
        override suspend fun execute(executor: EngineExecutor) {
            first.execute(executor)
            if (!executor.isStopped && executor.savedCount == 0) fallback.execute(executor)
        }
    }

    /** gallery-dl for everything it can parse, with yt-dlp after it:
     * - The listing timed out ([listingTimedOut]) — on Reddit, gallery-dl's shared client ID
     *   waiting out a rate limit, for minutes: yt-dlp first, and gallery-dl only if yt-dlp saved
     *   nothing (an image post), so a video doesn't sit behind that same wait a second time. A
     *   mixed post's images are lost in that case; the alternative was every video waiting.
     * - The listing found only videos ([onlyVideos]): yt-dlp alone. gallery-dl runs with video
     *   excluded, so its pass could only save nothing before yt-dlp ran anyway — on a Reddit video
     *   post that was a probe plus a Reddit API round trip spent on nothing, every time.
     * - The probe says only yt-dlp knows this link: yt-dlp alone, no doomed gallery-dl attempt.
     *   Only a confirmed "no" from gallery-dl plus a confirmed "yes" from yt-dlp counts; a probe
     *   that failed to run (null) is no evidence, so gallery-dl still gets its turn.
     * - Otherwise gallery-dl runs with video excluded — its own internal yt-dlp hand-off has no
     *   ffmpeg/JS runtime and produces split, broken video — then yt-dlp runs if gallery-dl saved
     *   nothing (gallery-dl having run at all means the link resolves to something), or as the
     *   video supplement when the post has or may have a video ([supplementVideo]).
     * The probe isn't consulted for that second yt-dlp run: its regex check against a raw share
     * link (Reddit's /s/<code>) said "no extractor" for links yt-dlp's generic extractor handles. */
    data class GalleryDlFirst(val supplementVideo: Boolean, val onlyVideos: Boolean = false, val listingTimedOut: Boolean = false) : EnginePlan {
        override suspend fun execute(executor: EngineExecutor) {
            if (listingTimedOut) {
                YtDlpFirst(alsoImages = false).execute(executor)
                return
            }
            if (onlyVideos) {
                executor.run(DownloadEngine.YT_DLP)
                return
            }
            val probe = executor.probe()
            if (probe.galleryDlHasExtractor == false && probe.ytDlpHasExtractor == true) {
                executor.run(DownloadEngine.YT_DLP)
                return
            }
            executor.run(DownloadEngine.GALLERY_DL, excludeVideo = true)
            if (executor.isStopped) return
            if (executor.savedCount == 0 || supplementVideo) executor.run(DownloadEngine.YT_DLP)
        }
    }

    companion object {
        /** The plan for a download routed to [engine]. [classicEngine] is where the link would go
         * without Instaloader (VideoSiteRouter.classify); [supplementVideo] whether a gallery-dl
         * download should also give its video to yt-dlp (a listed video, or a host whose listings
         * miss them — VideoSiteRouter.alwaysSupplementsVideo); [onlyVideos] whether its listing
         * found nothing but videos; [listingTimedOut] whether it gave up waiting for one. */
        fun planFor(
            engine: DownloadEngine,
            classicEngine: DownloadEngine,
            supplementVideo: Boolean,
            onlyVideos: Boolean = false,
            listingTimedOut: Boolean = false,
            choice: EngineChoice = EngineChoice.DEFAULT,
            hasImageItem: Boolean = false,
        ): EnginePlan {
            fun classic(e: DownloadEngine) = classicPlan(e, supplementVideo, onlyVideos, listingTimedOut, choice, hasImageItem)
            if (engine != DownloadEngine.INSTALOADER) return classic(engine)
            // Instaloader is only routed to while it's on (VideoSiteRouter.resolveEngine); where it
            // sits in the order decides whether it goes before or after the classic engines.
            val firstClassic = listOf(DownloadEngine.GALLERY_DL, DownloadEngine.YT_DLP)
                .filter { choice.isOn(it) }
                .minByOrNull { choice.order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            return when {
                firstClassic == null -> Single(DownloadEngine.INSTALOADER)
                choice.before(DownloadEngine.INSTALOADER, firstClassic) -> FallbackIfNothingSaved(Single(DownloadEngine.INSTALOADER), classic(classicEngine))
                else -> FallbackIfNothingSaved(classic(classicEngine), Single(DownloadEngine.INSTALOADER))
            }
        }

        private fun classicPlan(
            engine: DownloadEngine,
            supplementVideo: Boolean,
            onlyVideos: Boolean,
            listingTimedOut: Boolean,
            choice: EngineChoice,
            hasImageItem: Boolean,
        ): EnginePlan {
            val galleryDl = choice.isOn(DownloadEngine.GALLERY_DL)
            val ytDlp = choice.isOn(DownloadEngine.YT_DLP)
            return when (engine) {
                DownloadEngine.GALLERY_DL -> when {
                    !galleryDl && !ytDlp -> Unavailable(OFF_BOTH)
                    !galleryDl -> Single(DownloadEngine.YT_DLP)
                    !ytDlp -> Single(DownloadEngine.GALLERY_DL)
                    choice.before(DownloadEngine.GALLERY_DL, DownloadEngine.YT_DLP) -> GalleryDlFirst(supplementVideo, onlyVideos, listingTimedOut)
                    else -> YtDlpFirst(alsoImages = hasImageItem)
                }
                DownloadEngine.YT_DLP -> if (ytDlp) Single(engine) else Unavailable(OFF_YT_DLP)
                DownloadEngine.SPOTIFY -> if (ytDlp) Single(engine) else Unavailable(OFF_SPOTIFY)
                DownloadEngine.INSTALOADER -> Single(engine)
            }
        }

        const val OFF_YT_DLP = "yt-dlp is turned off (Settings › Updates › Engines) — it's the only engine that can download this link."
        const val OFF_SPOTIFY = "Spotify downloads need yt-dlp, which is turned off (Settings › Updates › Engines)."
        const val OFF_BOTH = "gallery-dl and yt-dlp are both turned off (Settings › Updates › Engines)."
    }
}
