package com.comfort.app.worker

import android.content.Context
import com.comfort.app.R
import com.comfort.app.data.CookieStore
import com.comfort.app.data.DownloadEngine
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import java.io.File

/** The "Current command" text: what a download would run, built by [EngineCommands] exactly as
 * DownloadWorker builds it (in preview mode, so nothing is created), shown by [CommandPreview]. */
internal object CurrentCommand {

    /** The preview sheet's: [choices] is the sheet's state as the download it would add (see
     * [sheetEntity]). Every engine that could run for [url], in the order they'd run. */
    fun forDownload(context: Context, url: String, choices: DownloadEntity): List<Shown> {
        val commands = commands(context, choices, url, routedEngine(context, url, choices))
        val redditLogin = cookiesArg(context).isNotEmpty() && hasRedditLogin(CookieStore.file(context))
        val engines = engines(context, url, choices)
        return engines.mapIndexed { i, engine ->
            val block = when {
                engine == DownloadEngine.GALLERY_DL -> CommandPreview.galleryDl(commands.galleryDlArgs(galleryDlExcludesVideo(engines)), redditLogin)
                // Instaloader runs as a library, without the download's extra commands: said where
                // they'd seem to be missing.
                engine == DownloadEngine.INSTALOADER && !choices.extraCommands.isNullOrBlank() ->
                    commands.preview(engine).let { it.copy(notes = it.notes + CommandPreview.Note(R.string.command_note_instaloader_extras)) }
                else -> commands.preview(engine)
            }
            // A later engine is a fallback or the video supplement: it runs only if needed.
            val shown = if (i == 0) block else block.copy(notes = listOf(CommandPreview.Note(R.string.command_note_then)) + block.notes)
            Shown(engine, shown.render { text(context, it) })
        }
    }

    /** One engine's command, as text. */
    data class Shown(val engine: DownloadEngine, val text: String)

    // EnginePlan: gallery-dl leaves the videos to yt-dlp whenever yt-dlp runs too (GalleryDlFirst,
    // YtDlpFirst); only alone (Single) does it keep them.
    private fun galleryDlExcludesVideo(engines: List<DownloadEngine>) = DownloadEngine.YT_DLP in engines

    /** Settings > Advanced > Command templates': gallery-dl's and yt-dlp's commands from the
     * Settings alone — what any download starts from before its own choices. */
    fun forSettings(context: Context): List<Shown> {
        val commands = commands(context, null, CommandPreview.URL_PLACEHOLDER, DownloadEngine.YT_DLP)
        val choice = EngineChoice.load(context)
        val engines = listOf(DownloadEngine.GALLERY_DL, DownloadEngine.YT_DLP).filter { choice.isOn(it) }
        // Side by side here, not as a fallback, so no "then, if needed" note.
        return engines.map {
            val block = commands.preview(it, excludeVideo = it == DownloadEngine.GALLERY_DL && galleryDlExcludesVideo(engines))
            Shown(it, block.render { note -> text(context, note) })
        }
    }

    /** The download the preview sheet would add, as [EngineCommands] reads it. */
    fun sheetEntity(
        url: String,
        videoQuality: VideoQuality?,
        outputFormat: String?,
        saveThumbnail: Boolean,
        clipRange: String?,
        extraCommands: String?,
        filenameTemplate: String?,
        itemFilter: String?,
        engineOverride: DownloadEngine?,
        audioFormat: String?,
        audioLanguage: String?,
        subtitleLanguages: String?,
        overrideTitle: String?,
        overrideArtist: String?,
    ) = DownloadEntity(
        id = PREVIEW_ID, url = url, title = "", thumbnailPath = null, status = DownloadStatus.QUEUED,
        progress = 0f, downloadedItems = 0, totalItems = 0, speedMbs = 0f, etaSeconds = 0L,
        errorMessage = null, dateAdded = 0L,
        itemFilter = itemFilter,
        videoQuality = videoQuality?.name ?: if (VideoSiteRouter.isSongSource(url)) VideoQuality.AUDIO_ONLY.name else null,
        clipRange = clipRange, extraCommands = extraCommands, outputFormat = outputFormat,
        filenameTemplate = filenameTemplate, saveThumbnail = saveThumbnail,
        overrideTitle = overrideTitle?.trim()?.ifBlank { null }, overrideArtist = overrideArtist?.trim()?.ifBlank { null },
        engineOverride = engineOverride?.name, audioFormat = audioFormat, audioLanguage = audioLanguage,
        subtitleLanguages = subtitleLanguages,
    )

    /** The engines DownloadWorker would run for [url], in order — the ones that might run, as a
     * fallback or a video supplement depends on what the first one finds. */
    internal fun engines(context: Context, url: String, entity: DownloadEntity?): List<DownloadEngine> {
        entity?.engineOverride?.let { name -> DownloadEngine.entries.firstOrNull { it.name == name } }?.let { return listOf(it) }
        val engine = routedEngine(context, url, entity)
        val choice = EngineChoice.load(context)
        fun ordered(engines: List<DownloadEngine>) = engines.filter { choice.isOn(it) }
            .sortedBy { choice.order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        fun classic(e: DownloadEngine) = when (e) {
            DownloadEngine.GALLERY_DL ->
                if (VideoSiteRouter.isGalleryDlOnly(url)) ordered(listOf(DownloadEngine.GALLERY_DL))
                else ordered(listOf(DownloadEngine.GALLERY_DL, DownloadEngine.YT_DLP))
            // Spotify runs on yt-dlp, so it's there while yt-dlp is on.
            DownloadEngine.SPOTIFY -> if (choice.isOn(DownloadEngine.YT_DLP)) listOf(e) else emptyList()
            else -> ordered(listOf(e))
        }
        return if (engine == DownloadEngine.INSTALOADER) {
            ordered(listOf(DownloadEngine.INSTALOADER) + classic(VideoSiteRouter.classify(url))).ifEmpty { listOf(DownloadEngine.INSTALOADER) }
        } else {
            classic(engine)
        }
    }

    /** The engine DownloadWorker routes [url] to (it passes this to EngineCommands, which leaves
     * yt-dlp's picked items out for a gallery-dl or Instaloader download): Instagram goes to
     * Instaloader unless the download needs yt-dlp's processing. */
    private fun routedEngine(context: Context, url: String, entity: DownloadEntity?): DownloadEngine {
        val wantsYtDlpOnly = !entity?.clipRange.isNullOrBlank() ||
            (entity?.videoQuality ?: GalleryDlPreferences.getVideoQuality(context).name) == VideoQuality.AUDIO_ONLY.name
        return VideoSiteRouter.resolveEngine(context, url).let {
            if (it == DownloadEngine.INSTALOADER && wantsYtDlpOnly) VideoSiteRouter.classify(url) else it
        }
    }

    private fun commands(context: Context, entity: DownloadEntity?, url: String, engine: DownloadEngine) = EngineCommands(
        context, entity, url, PREVIEW_ID, engine, File(context.cacheDir, "staging"),
        cookiesArg(context), entity?.overrideTitle.orEmpty(), entity?.overrideArtist.orEmpty(), previewOnly = true,
    )

    // As DownloadWorker passes it: the cookie file when there is one.
    private fun cookiesArg(context: Context) = CookieStore.file(context).let { if (it.exists() && it.length() > 0) it.absolutePath else "" }

    /** gallery_dl_wrapper._has_reddit_login: a reddit_session cookie in cookies.txt. */
    private fun hasRedditLogin(file: File) = runCatching {
        file.useLines { lines ->
            lines.any { raw ->
                val parts = raw.trimEnd('\r', '\n').split('\t')
                parts.size > 6 && "reddit.com" in parts[0] && parts[5] == "reddit_session" && parts[6].isNotEmpty()
            }
        }
    }.getOrDefault(false)

    private fun text(context: Context, note: CommandPreview.Note) = context.getString(note.res, *note.args.toTypedArray())

    // The archive files' name in the preview ("download.ytdlp.txt"); never created.
    private const val PREVIEW_ID = "download"
}
