package com.comfort.app.worker

import android.content.Context
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.AudioFormat
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
    // Only shown (the preview sheet, Settings' current command): nothing is created or unzipped.
    private val previewOnly: Boolean = false,
) {
    // Each of these prefers what the download preview sheet recorded for this one download over
    // the global Settings default — see DownloadEntity's own comment on why they're nullable. A
    // retry/resume therefore re-applies exactly what the user picked in the sheet.
    private val filenameFormat = entity?.filenameTemplate?.takeIf { it.isNotBlank() }
        ?: GalleryDlPreferences.getFilenameFormat(context)
    // yt-dlp, Spotify and Instaloader fill the same format in through gallery-dl's formatter
    // (custom_names.py), so every download is named alike, the site included. "Trim filenames" off
    // lifts the default's 150-character caption cap, as it did for their own naming.
    private val customFilenameFormat by lazy {
        if (!trimFilenames && filenameFormat == GalleryDlPreferences.DEFAULT_FILENAME_FORMAT) filenameFormat.replace("[:150]", "")
        else filenameFormat
    }

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
        .apply { if (!previewOnly) parentFile?.mkdirs() }.absolutePath
    private val galleryArchivePath by lazy { archive("sqlite3") }
    private val ytDlpArchivePath by lazy { archive("ytdlp.txt") }
    private val instaloaderArchivePath by lazy { archive("instaloader.txt") }

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
    // Always empty now (no ABI's ffmpeg has libraries of its own); the call also clears the old
    // armeabi-v7a bundle's leftover folder. Still passed: the wrappers' argument positions.
    private val ffmpegLibDir by lazy { if (previewOnly) "" else FfmpegRuntime.ensureProvisioned(context)?.absolutePath.orEmpty() }
    // A per-download pick (the Configure sheet) wins over the Settings default.
    private val audioFormat = entity?.audioFormat?.let { stored -> runCatching { AudioFormat.valueOf(stored) }.getOrNull() }
        ?: GalleryDlPreferences.getAudioFormat(context)
    private val normalizeAudio = GalleryDlPreferences.isNormalizeAudio(context)
    // A song keeps its original audio; a video gets the dub asked for, when it has one.
    private val audioLanguage = if (com.comfort.app.data.VideoSiteRouter.isSongSource(url)) "" else
        com.comfort.app.data.AudioLanguage.requested(entity?.audioLanguage ?: GalleryDlPreferences.getAudioLanguage(context))

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

    // Imported from YTDLnis's settings screens. Mostly yt-dlp only; force IPv4, skipped certificate
    // checks, restricted filenames and the sleep interval go to every engine.
    private val forceIpv4 = GalleryDlPreferences.isForceIpv4(context)
    private val concurrentFragments = GalleryDlPreferences.getEffectiveConcurrentFragments(context)
    private val noCheckCertificates = GalleryDlPreferences.isNoCheckCertificates(context)
    private val sleepIntervalSeconds = GalleryDlPreferences.getEffectiveSleepIntervalSeconds(context)
    private val customHeaders = GalleryDlPreferences.getCustomHeaders(context)
    private val formatSort = GalleryDlPreferences.getFormatSort(context)
    private val verboseLogging = GalleryDlPreferences.isVerboseLogging(context)
    private val embedChapters = GalleryDlPreferences.isEmbedChapters(context)
    private val splitChapters = GalleryDlPreferences.isSplitChapters(context)
    // SponsorBlock: the categories to cut out or to mark as chapters (the other one stays blank).
    private val sponsorBlockMode = GalleryDlPreferences.getSponsorBlockMode(context)
    private val sponsorBlockCategories = GalleryDlPreferences.getSponsorBlockCategories(context).joinToString(",")
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
    private val aria2LibDir by lazy { if (aria2Enabled && !previewOnly) Aria2Runtime.ensureProvisioned(context)?.absolutePath.orEmpty() else "" }

    fun forEngine(engine: DownloadEngine, excludeVideo: Boolean = false): EngineCommand = when (engine) {
        DownloadEngine.GALLERY_DL -> galleryDl(excludeVideo)
        DownloadEngine.YT_DLP -> ytDlp()
        DownloadEngine.SPOTIFY -> spotify()
        DownloadEngine.INSTALOADER -> instaloader()
    }

    fun galleryDl(excludeVideo: Boolean) = EngineCommand("gallery-dl", "gallery_dl_wrapper.py", galleryDlArgs(excludeVideo).toArgs())

    fun galleryDlArgs(excludeVideo: Boolean) = GalleryDlArgs(
        url = url, downloadDir = stagingDir.absolutePath, cookies = cookiesArg,
        filenameFormat = filenameFormat, extraArgs = galleryDlExtraArgs, archive = galleryArchivePath, limitRate = limitRate,
        itemFilter = entity?.itemFilter.orEmpty(), excludeVideo = excludeVideo,
        retries = networkRetries, maxFilesize = maxFilesize, writeInfoFiles = writeInfoFiles, proxy = proxyUrl,
        socketTimeout = socketTimeoutSeconds,
        sleepInterval = if (sleepIntervalSeconds > 0) sleepIntervalSeconds.toString() else "",
        restrictFilenames = restrictFilenames, forceIpv4 = forceIpv4, noCheckCertificates = noCheckCertificates,
    )

    // A custom filename format (gallery-dl's syntax) is filled in by the wrapper itself.
    fun ytDlp() = EngineCommand("yt-dlp", "yt_dlp_wrapper.py", ytDlpArgs().toArgs())

    fun ytDlpArgs() = YtDlpArgs(
        url = url, downloadDir = stagingDir.absolutePath, cookies = cookiesArg,
        filenameFormat = customFilenameFormat, extraArgs = ytDlpExtraArgs, archive = ytDlpArchivePath,
        limitRate = limitRate, formatOverride = formatIdOverride,
        jsRuntime = jsRuntimePath, ffmpeg = ffmpegPath,
        audioOnly = audioOnly, downloadSubtitles = downloadSubtitles, subtitleLangs = subtitleLangs,
        embedThumbnail = embedThumbnail, embedMetadata = embedMetadata, noPlaylist = noPlaylist,
        resolutionCap = videoQuality.resolutionCap()?.toString().orEmpty(),
        outputFormat = outputFormat.extension, retries = networkRetries, playlistItems = ytDlpPlaylistItems,
        maxFilesize = maxFilesize, writeInfoFiles = writeInfoFiles, clipRange = clipRange, proxy = proxyUrl,
        liveFromStart = liveFromStart, extractorArgs = extractorArgs, saveThumbnail = saveThumbnail,
        forceIpv4 = forceIpv4,
        concurrentFragments = if (concurrentFragments > 1) concurrentFragments.toString() else "",
        noCheckCertificates = noCheckCertificates,
        sleepInterval = if (sleepIntervalSeconds > 0) sleepIntervalSeconds.toString() else "",
        customHeaders = customHeaders, formatSort = formatSort, verbose = verboseLogging,
        embedChapters = embedChapters, saveSubtitleFiles = saveSubtitleFiles,
        restrictFilenames = restrictFilenames, trimFilenames = trimFilenames,
        fragmentRetries = fragmentRetries, socketTimeout = socketTimeoutSeconds, bufferSizeKb = bufferSizeKb,
        youtubeClientRotation = youtubeClientRotation, impersonate = impersonate,
        aria2 = aria2Path, aria2LibDir = aria2LibDir, ffmpegLibDir = ffmpegLibDir,
        overrideTitle = overrideTitle, overrideArtist = overrideArtist,
        // The preview sheet's saved extraction of this same URL, if any — reused instead of
        // extracting again (see yt_dlp_wrapper.download).
        infoCache = GalleryDlListing.ytDlpInfoCacheFile(context, url).absolutePath,
        audioFormat = audioFormat.codec, normalizeAudio = normalizeAudio,
        sponsorBlockRemove = if (sponsorBlockMode == GalleryDlPreferences.SPONSORBLOCK_REMOVE) sponsorBlockCategories else "",
        sponsorBlockMark = if (sponsorBlockMode == GalleryDlPreferences.SPONSORBLOCK_MARK) sponsorBlockCategories else "",
        splitChapters = splitChapters,
        audioLanguage = audioLanguage,
    )

    // Always audio-only — spotify_wrapper.py hands each matched track to yt_dlp_wrapper's own
    // download(), which is where ffmpeg/aria2c/the JS runtime are used. A custom filename format is
    // filled in there (it used to reach yt-dlp raw: a literal, unsubstituted name ffmpeg rejected).
    fun spotify() = EngineCommand("Spotify", "spotify_wrapper.py", spotifyArgs().toArgs())

    fun spotifyArgs() = SpotifyArgs(
        url = url, downloadDir = stagingDir.absolutePath, cookies = cookiesArg,
        filenameFormat = customFilenameFormat, archive = ytDlpArchivePath, jsRuntime = jsRuntimePath,
        ffmpeg = ffmpegPath, ffmpegLibDir = ffmpegLibDir, aria2 = aria2Path, aria2LibDir = aria2LibDir,
        restrictFilenames = restrictFilenames, trimFilenames = trimFilenames, verbose = verboseLogging,
        saveThumbnail = saveThumbnail, playlistItems = ytDlpPlaylistItems,
        overrideTitle = overrideTitle, overrideArtist = overrideArtist,
        // The matched YouTube video is downloaded like any YouTube link: same impersonation,
        // client rotation, extractor args, proxy, timeout and retries.
        impersonate = impersonate, youtubeClientRotation = youtubeClientRotation,
        extractorArgs = extractorArgs, proxy = proxyUrl, socketTimeout = socketTimeoutSeconds,
        retries = networkRetries, audioFormat = audioFormat.codec, normalizeAudio = normalizeAudio,
    )

    // Instagram posts/reels (VideoSiteRouter.resolveEngine). The picker's itemFilter passes
    // straight through: its "num"s are the same 1-based carousel positions.
    fun instaloader() = EngineCommand("Instaloader", "instaloader_wrapper.py", instaloaderArgs().toArgs())

    fun instaloaderArgs() = InstaloaderArgs(
        url = url, downloadDir = stagingDir.absolutePath, cookies = cookiesArg,
        itemFilter = entity?.itemFilter.orEmpty(), archive = instaloaderArchivePath,
        writeInfoFiles = writeInfoFiles, proxy = proxyUrl, socketTimeout = socketTimeoutSeconds,
        // The preview's saved post, reused instead of fetching it again.
        infoCache = GalleryDlListing.instaloaderInfoCacheFile(context, url).absolutePath,
        filenameFormat = customFilenameFormat,
        sleepInterval = if (sleepIntervalSeconds > 0) sleepIntervalSeconds.toString() else "",
        // The Settings > Downloads options every engine honours.
        limitRate = limitRate, retries = networkRetries, maxFilesize = maxFilesize,
        restrictFilenames = restrictFilenames, forceIpv4 = forceIpv4, noCheckCertificates = noCheckCertificates,
    )

    // curl_cffi, yt-dlp's only impersonation backend, is only in the arm64-v8a Python runtime
    // (yt_dlp_wrapper's _IMPERSONATE_AVAILABLE), and the runtime follows the primary ABI.
    private val impersonateAvailable get() = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    /** The command [engine] would run with, as the preview sheet and Settings show it. */
    fun preview(engine: DownloadEngine, excludeVideo: Boolean = false): CommandPreview.Block = when (engine) {
        DownloadEngine.GALLERY_DL -> CommandPreview.galleryDl(galleryDlArgs(excludeVideo))
        DownloadEngine.YT_DLP -> CommandPreview.ytDlp(ytDlpArgs(), impersonateAvailable = impersonateAvailable)
        DownloadEngine.SPOTIFY -> CommandPreview.spotify(spotifyArgs(), impersonateAvailable = impersonateAvailable)
        DownloadEngine.INSTALOADER -> CommandPreview.instaloader(instaloaderArgs())
    }

    private companion object {
        // The comma-separated item numbers in SharePickerScreen's gallery-dl --filter ("num in {1,3,4}").
        val ITEM_FILTER_NUMS_RE = Regex("""\{([\d,]+)\}""")
    }
}
