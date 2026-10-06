package com.comfort.app.worker

import android.content.Context
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.OutputFormat
import com.comfort.app.data.VideoQuality
import com.comfort.app.util.Aria2Runtime
import com.comfort.app.util.FfmpegRuntime
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.QuickJsRuntime
import java.io.File

/** One engine run: which wrapper script, its CLI arguments, and the name its errors are filed
 * under (the errored card's per-engine details). */
internal data class EngineCommand(val label: String, val script: String, val args: List<String>)

/** Builds each engine's [EngineCommand] for one download — the per-download choices the preview
 * sheet recorded on [entity] (falling back to the Settings defaults when it didn't) plus the
 * global engine settings, read once when this is created. Every wrapper takes positional string
 * arguments, with "" as its "not set". */
internal class EngineCommands(
    private val context: Context,
    private val entity: DownloadEntity?,
    private val url: String,
    private val downloadId: String,
    engine: DownloadEngine,
    private val stagingDir: File,
    private val cookiesArg: String,
    private val overrideTitle: String,
    private val overrideArtist: String,
) {
    // Each of these prefers what the download preview sheet recorded for this one download over
    // the global Settings default — see DownloadEntity's own comment on why they're nullable. A
    // retry/resume therefore re-applies exactly what the user picked in the sheet.
    private val filenameFormat = entity?.filenameTemplate?.takeIf { it.isNotBlank() }
        ?: GalleryDlPreferences.getFilenameFormat(context)

    // Advanced > Extra arguments: each engine gets the "both" set plus its own (see
    // GalleryDlPreferences.getExtraArgsFor) — the engines take different flags. The sheet's extra
    // commands are appended, not a replacement, so a per-download tweak keeps the global ones.
    private fun extraArgsFor(engine: DownloadEngine) = listOfNotNull(
        GalleryDlPreferences.getExtraArgsFor(context, engine).takeIf { it.isNotBlank() },
        entity?.extraCommands?.takeIf { it.isNotBlank() },
    ).joinToString(" ")
    private val galleryDlExtraArgs = extraArgsFor(DownloadEngine.GALLERY_DL)
    private val ytDlpExtraArgs = extraArgsFor(DownloadEngine.YT_DLP)

    // Already-fetched item IDs across retries, so a paused/retried download resumes where it left
    // off. One file per engine: gallery-dl's archive is sqlite, yt-dlp's and Instaloader's are text.
    private fun archive(name: String) = File(context.filesDir, "archives/$downloadId.$name")
        .apply { parentFile?.mkdirs() }.absolutePath
    private val galleryArchivePath = archive("sqlite3")
    private val ytDlpArchivePath = archive("ytdlp.txt")
    private val instaloaderArchivePath = archive("instaloader.txt")

    private val limitRate = GalleryDlPreferences.getEffectiveSpeedLimit(context)
    private val networkRetries = GalleryDlPreferences.getEffectiveNetworkRetries(context)
    private val maxFilesize = GalleryDlPreferences.getEffectiveMaxFilesize(context).orEmpty()
    val writeInfoFiles = GalleryDlPreferences.isWriteInfoFiles(context)
    private val proxyUrl = GalleryDlPreferences.getEffectiveProxyUrl(context)
    private val extractorArgs = GalleryDlPreferences.getExtractorArgs(context)
    private val socketTimeoutSeconds = GalleryDlPreferences.getEffectiveSocketTimeoutSeconds(context)

    // Bundled as jniLibs/<abi>/libqjs.so and libffmpeg.so — see QuickJsRuntime's and
    // FfmpegRuntime's doc comments: YouTube needs the former just to extract real download URLs,
    // the latter merges the separate video/audio streams into one playable file.
    private val jsRuntimePath = QuickJsRuntime.getExecutablePath(context).orEmpty()
    private val ffmpegPath = FfmpegRuntime.getExecutablePath(context).orEmpty()
    // Non-empty only on armeabi-v7a, whose ffmpeg needs its unpacked shared libraries
    // (LD_LIBRARY_PATH); the other ABIs' ffmpeg is fully static.
    private val ffmpegLibDir = FfmpegRuntime.ensureProvisioned(context)?.absolutePath.orEmpty()

    // A per-download quality the sheet set wins over the Settings default.
    private val videoQuality = entity?.videoQuality?.let { stored -> runCatching { VideoQuality.valueOf(stored) }.getOrNull() }
        ?: GalleryDlPreferences.getVideoQuality(context)
    private val audioOnly = videoQuality == VideoQuality.AUDIO_ONLY
    private val clipRange = entity?.clipRange.orEmpty()
    private val downloadSubtitles = GalleryDlPreferences.isDownloadSubtitles(context)
    private val subtitleLangs = GalleryDlPreferences.getSubtitleLanguages(context)
    private val embedThumbnail = GalleryDlPreferences.isEmbedThumbnail(context)
    // The sheet's "Save thumbnail" chip writes the thumbnail as its own file (yt-dlp's
    // writethumbnail), separate from the global "Embed thumbnail" setting above.
    val saveThumbnail = entity?.saveThumbnail == true
    private val embedMetadata = GalleryDlPreferences.isEmbedMetadata(context)
    private val noPlaylist = GalleryDlPreferences.isNoPlaylist(context)
    private val liveFromStart = GalleryDlPreferences.isLiveFromStart(context)
    private val outputFormat = entity?.outputFormat?.let { stored -> runCatching { OutputFormat.valueOf(stored) }.getOrNull() }
        ?: GalleryDlPreferences.getOutputFormat(context)

    // The picker's gallery-dl --filter ("num in {1,3,4}") as the bare "1,3,4" yt-dlp's
    // playlist_items and spotify_wrapper.py's own playlist_items take — the numbers are the same
    // 1-based positions. Blank for gallery-dl and Instaloader downloads: there yt-dlp only runs as
    // the video supplement, and it lists the same post independently (its own numbering, usually
    // only the videos), so those numbers would filter the wrong entries. Unfiltered, the
    // supplement fetches every real video it finds — an occasional extra file, never a wrong one.
    private val ytDlpPlaylistItems = if (engine == DownloadEngine.GALLERY_DL || engine == DownloadEngine.INSTALOADER) {
        ""
    } else {
        ITEM_FILTER_NUMS_RE.find(entity?.itemFilter.orEmpty())?.groupValues?.get(1).orEmpty()
    }

    // Imported from YTDLnis's settings screens — see GalleryDlPreferences for why they're yt-dlp-only.
    private val forceIpv4 = GalleryDlPreferences.isForceIpv4(context)
    private val concurrentFragments = GalleryDlPreferences.getEffectiveConcurrentFragments(context)
    private val noCheckCertificates = GalleryDlPreferences.isNoCheckCertificates(context)
    private val sleepIntervalSeconds = GalleryDlPreferences.getEffectiveSleepIntervalSeconds(context)
    private val customHeaders = GalleryDlPreferences.getCustomHeaders(context)
    private val formatSort = GalleryDlPreferences.getFormatSort(context)
    private val verboseLogging = GalleryDlPreferences.isVerboseLogging(context)
    private val embedChapters = GalleryDlPreferences.isEmbedChapters(context)
    val saveSubtitleFiles = GalleryDlPreferences.isSaveSubtitleFiles(context)
    private val restrictFilenames = GalleryDlPreferences.isRestrictFilenames(context)
    private val trimFilenames = GalleryDlPreferences.isTrimFilenames(context)
    private val fragmentRetries = GalleryDlPreferences.getEffectiveFragmentRetries(context)
    private val bufferSizeKb = GalleryDlPreferences.getEffectiveBufferSizeKb(context)
    private val formatIdOverride = GalleryDlPreferences.getFormatIdOverride(context)
    private val youtubeClientRotation = GalleryDlPreferences.isYoutubeClientRotationEnabled(context)
    private val impersonate = GalleryDlPreferences.isImpersonateEnabled(context)
    private val aria2Enabled = GalleryDlPreferences.isAria2Enabled(context)
    private val aria2Path = if (aria2Enabled) Aria2Runtime.getExecutablePath(context).orEmpty() else ""
    private val aria2LibDir = if (aria2Enabled) Aria2Runtime.ensureProvisioned(context)?.absolutePath.orEmpty() else ""

    private fun flag(on: Boolean) = if (on) "1" else "0"

    fun forEngine(engine: DownloadEngine, excludeVideo: Boolean = false): EngineCommand = when (engine) {
        DownloadEngine.GALLERY_DL -> galleryDl(excludeVideo)
        DownloadEngine.YT_DLP -> ytDlp()
        DownloadEngine.SPOTIFY -> spotify()
        DownloadEngine.INSTALOADER -> instaloader()
    }

    fun galleryDl(excludeVideo: Boolean) = EngineCommand(
        "gallery-dl", "gallery_dl_wrapper.py",
        listOf(
            "download", url, stagingDir.absolutePath, cookiesArg,
            filenameFormat, galleryDlExtraArgs, galleryArchivePath, limitRate,
            entity?.itemFilter.orEmpty(), flag(excludeVideo),
            networkRetries, maxFilesize, flag(writeInfoFiles), proxyUrl,
            socketTimeoutSeconds,
        ),
    )

    // gallery-dl's filename-format template syntax means nothing to yt-dlp, so it isn't passed.
    fun ytDlp() = EngineCommand(
        "yt-dlp", "yt_dlp_wrapper.py",
        listOf(
            "download", url, stagingDir.absolutePath, cookiesArg,
            "", ytDlpExtraArgs, ytDlpArchivePath, limitRate, formatIdOverride,
            jsRuntimePath, ffmpegPath,
            flag(audioOnly), flag(downloadSubtitles), subtitleLangs,
            flag(embedThumbnail), flag(embedMetadata), flag(noPlaylist),
            videoQuality.resolutionCap()?.toString().orEmpty(),
            outputFormat.extension, networkRetries, ytDlpPlaylistItems, maxFilesize,
            flag(writeInfoFiles), clipRange, proxyUrl,
            flag(liveFromStart), extractorArgs,
            flag(saveThumbnail),
            flag(forceIpv4),
            if (concurrentFragments > 1) concurrentFragments.toString() else "",
            flag(noCheckCertificates),
            if (sleepIntervalSeconds > 0) sleepIntervalSeconds.toString() else "",
            customHeaders,
            formatSort,
            flag(verboseLogging),
            flag(embedChapters),
            flag(saveSubtitleFiles),
            flag(restrictFilenames),
            flag(trimFilenames),
            fragmentRetries,
            socketTimeoutSeconds,
            bufferSizeKb,
            flag(youtubeClientRotation),
            flag(impersonate),
            aria2Path,
            aria2LibDir,
            ffmpegLibDir,
            overrideTitle,
            overrideArtist,
            // The preview sheet's saved extraction of this same URL, if any — reused instead of
            // extracting again (see yt_dlp_wrapper.download).
            GalleryDlListing.ytDlpInfoCacheFile(context, url).absolutePath,
        ),
    )

    // Always audio-only — spotify_wrapper.py hands each matched track to yt_dlp_wrapper's own
    // download(), which is where ffmpeg/aria2c/the JS runtime are used. gallery-dl's "{keyword}"
    // filename template isn't passed (reproduced live as a literal, unsubstituted filename that
    // ffmpeg rejected).
    fun spotify() = EngineCommand(
        "Spotify", "spotify_wrapper.py",
        listOf(
            "download", url, stagingDir.absolutePath, cookiesArg,
            "", ytDlpArchivePath, jsRuntimePath,
            ffmpegPath, ffmpegLibDir, aria2Path, aria2LibDir,
            flag(restrictFilenames),
            flag(trimFilenames),
            flag(verboseLogging),
            flag(saveThumbnail),
            ytDlpPlaylistItems,
            overrideTitle,
            overrideArtist,
        ),
    )

    // Instagram posts/reels (VideoSiteRouter.resolveEngine). The picker's itemFilter passes
    // straight through: its "num"s are the same 1-based carousel positions.
    fun instaloader() = EngineCommand(
        "Instaloader", "instaloader_wrapper.py",
        listOf(
            "download", url, stagingDir.absolutePath, cookiesArg,
            entity?.itemFilter.orEmpty(), instaloaderArchivePath,
            flag(writeInfoFiles), proxyUrl, socketTimeoutSeconds,
            // The preview's saved post, reused instead of fetching it again.
            GalleryDlListing.instaloaderInfoCacheFile(context, url).absolutePath,
        ),
    )

    private companion object {
        // The comma-separated item numbers in SharePickerScreen's gallery-dl --filter ("num in {1,3,4}").
        val ITEM_FILTER_NUMS_RE = Regex("""\{([\d,]+)\}""")
    }
}
