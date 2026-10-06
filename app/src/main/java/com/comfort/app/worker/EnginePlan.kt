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
}

/** Which engines a download runs, and in what order — each plan is one fallback strategy. */
internal sealed interface EnginePlan {
    suspend fun execute(executor: EngineExecutor)

    /** Just this engine (yt-dlp, Spotify, or Instaloader with nothing to fall back to). */
    data class Single(val engine: DownloadEngine) : EnginePlan {
        override suspend fun execute(executor: EngineExecutor) = executor.run(engine)
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
    data class GalleryDlFirst(val supplementVideo: Boolean, val onlyVideos: Boolean = false) : EnginePlan {
        override suspend fun execute(executor: EngineExecutor) {
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
         * found nothing but videos. */
        fun planFor(engine: DownloadEngine, classicEngine: DownloadEngine, supplementVideo: Boolean, onlyVideos: Boolean = false): EnginePlan =
            if (engine == DownloadEngine.INSTALOADER) {
                FallbackIfNothingSaved(Single(DownloadEngine.INSTALOADER), classic(classicEngine, supplementVideo, onlyVideos))
            } else {
                classic(engine, supplementVideo, onlyVideos)
            }

        private fun classic(engine: DownloadEngine, supplementVideo: Boolean, onlyVideos: Boolean): EnginePlan = when (engine) {
            DownloadEngine.GALLERY_DL -> GalleryDlFirst(supplementVideo, onlyVideos)
            DownloadEngine.YT_DLP, DownloadEngine.SPOTIFY, DownloadEngine.INSTALOADER -> Single(engine)
        }
    }
}
