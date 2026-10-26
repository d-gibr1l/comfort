package com.comfort.app.worker

import com.comfort.app.R
import com.comfort.app.worker.CommandPreview.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every setting that changes a download is a line of the "Current command" (or a note), the line
 * is gone with the setting off, and each line is what the wrapper does for it — the wrapper's own
 * code is cited next to the expectations it proves. */
class CommandPreviewTest {

    private fun script(name: String) = listOf("src/main/assets/python_packages", "app/src/main/assets/python_packages")
        .map { File(it, name) }.first { it.isFile }.readText().replace("\r\n", "\n")

    /** [on]'s options contain every one of [lines] and [off]'s none of them. */
    private fun assertOnOff(on: CommandPreview.Block, off: CommandPreview.Block, vararg lines: String) {
        lines.forEach { line ->
            assertTrue("expected `$line` in ${on.options}", line in on.options)
            assertFalse("expected no `$line` in ${off.options}", line in off.options)
        }
    }

    private fun assertNone(block: CommandPreview.Block, prefix: String) =
        assertTrue("expected no `$prefix…` in ${block.options}", block.options.none { it.startsWith(prefix) })

    // ── yt-dlp ──────────────────────────────────────────────────────────────────────────────────

    private val base = YtDlpArgs(url = "https://example.com/v")
    private val ff = base.copy(ffmpeg = "/data/app/lib/libffmpeg.so")
    private fun yt(a: YtDlpArgs) = CommandPreview.ytDlp(a)
    private fun ytOnOff(on: YtDlpArgs, off: YtDlpArgs, vararg lines: String) = assertOnOff(yt(on), yt(off), *lines)

    @Test
    fun ytDlpAllOff() {
        val b = yt(base)
        assertEquals(listOf("-f best/18", "-S proto", "--no-mtime"), b.options)
        assertEquals(emptyList<Note>(), b.notes)
        assertEquals("https://example.com/v", b.url)
        // With ffmpeg: merging, the container and the "- Topic" uploader clean-up (always on there).
        assertEquals(
            listOf(
                "-f bestvideo+bestaudio/best", "-S proto", "--no-mtime",
                "--ffmpeg-location libffmpeg.so", "--merge-output-format mkv",
                "--parse-metadata \"%(uploader,channel,creator|Unknown)s:(?P<uploader>.*?)(?: - Topic)?\\\$\"",
            ),
            yt(ff).options,
        )
    }

    @Test
    fun ytDlpImpersonateNeedsBackend() {
        // `if impersonate and _IMPERSONATE_AVAILABLE:` — without curl_cffi it's skipped, said in a note.
        val on = base.copy(impersonate = true)
        assertTrue("--impersonate \"\"" in yt(on).options)
        val unavailable = CommandPreview.ytDlp(on, impersonateAvailable = false)
        assertNone(unavailable, "--impersonate")
        assertEquals(listOf(Note(R.string.command_note_impersonate_unavailable)), unavailable.notes)
    }

    @Test
    fun ytDlpTrimNote() {
        // _CaptionPP's 150-byte cap: a note unless the format itself shows it ("[:150]").
        val custom = base.copy(filenameFormat = "{title}.{extension}", trimFilenames = true)
        assertTrue(Note(R.string.command_note_trim) in yt(custom).notes)
        assertFalse(Note(R.string.command_note_trim) in yt(custom.copy(trimFilenames = false)).notes)
        assertFalse(Note(R.string.command_note_trim) in yt(custom.copy(filenameFormat = "{title|content[:150]}.{extension}")).notes)
    }

    @Test
    fun ytDlpSimpleSwitches() {
        ytOnOff(base.copy(restrictFilenames = true), base, "--restrict-filenames")
        ytOnOff(base.copy(verbose = true), base, "-v")
        ytOnOff(base.copy(forceIpv4 = true), base, "-4")
        ytOnOff(base.copy(noCheckCertificates = true), base, "--no-check-certificates")
        ytOnOff(base.copy(liveFromStart = true), base, "--live-from-start")
        ytOnOff(base.copy(saveThumbnail = true), base, "--write-thumbnail")
        ytOnOff(base.copy(writeInfoFiles = true), base, "--write-info-json --write-description")
        ytOnOff(base.copy(concurrentFragments = "4"), base, "-N 4")
        ytOnOff(base.copy(socketTimeout = "30"), base, "--socket-timeout 30")
        ytOnOff(base.copy(bufferSizeKb = "16"), base, "--buffer-size 16K")
        ytOnOff(base.copy(limitRate = "2M"), base, "-r 2M")
        ytOnOff(base.copy(maxFilesize = "50M"), base, "--max-filesize 50M")
        ytOnOff(base.copy(archive = "/data/files/archives/download.ytdlp.txt"), base, "--download-archive download.ytdlp.txt")
        ytOnOff(base.copy(jsRuntime = "/data/app/lib/libqjs.so"), base, "--no-js-runtimes --js-runtimes quickjs:libqjs.so")
        // The wrapper reads these with int() and skips a 0 (`if concurrent_fragments:` ...).
        assertNone(yt(base.copy(concurrentFragments = "0")), "-N")
        assertNone(yt(base.copy(bufferSizeKb = "0")), "--buffer-size")
        assertNone(yt(base.copy(sleepInterval = "0")), "--sleep-interval")
    }

    @Test
    fun ytDlpPlaylist() {
        // "ignoreerrors": False if no_playlist else "only_download" — the CLI's --abort-on-error.
        ytOnOff(base.copy(noPlaylist = true), base, "--abort-on-error", "--no-playlist")
        // playlist_items wins over noplaylist (elif).
        val items = yt(base.copy(noPlaylist = true, playlistItems = "1,3"))
        assertTrue("-I 1,3" in items.options)
        assertTrue("--abort-on-error" in items.options)
        assertFalse("--no-playlist" in items.options)
        ytOnOff(base.copy(playlistItems = "2"), base, "-I 2")
    }

    @Test
    fun ytDlpSleepInterval() {
        // sleep_interval 2, max_sleep_interval max(2, n).
        ytOnOff(base.copy(sleepInterval = "5"), base, "--sleep-interval 2 --max-sleep-interval 5")
        ytOnOff(base.copy(sleepInterval = "1"), base, "--sleep-interval 2 --max-sleep-interval 2")
    }

    @Test
    fun ytDlpAria2AndImpersonation() {
        val aria2 = base.copy(aria2 = "/data/app/lib/libaria2c.so")
        ytOnOff(aria2, base, "--downloader aria2c --downloader-args aria2c:--ca-certificate=cacert.pem")
        ytOnOff(base.copy(impersonate = true), base, "--impersonate \"\"")
        // `if aria2_path and not impersonate:` — no aria2c while impersonating, said in a note.
        val both = yt(aria2.copy(impersonate = true))
        assertNone(both, "--downloader")
        assertTrue("--impersonate \"\"" in both.options)
        assertEquals(listOf(Note(R.string.command_note_aria2_off)), both.notes)
        assertEquals(emptyList<Note>(), yt(aria2).notes)
    }

    @Test
    fun ytDlpRetries() {
        // retries sets both, fragment_retries (when given) overrides the second.
        ytOnOff(base.copy(retries = "5"), base, "--retries 5", "--fragment-retries 5")
        val both = yt(base.copy(retries = "5", fragmentRetries = "10"))
        assertTrue("--fragment-retries 10" in both.options)
        assertFalse("--fragment-retries 5" in both.options)
        val fragmentsOnly = yt(base.copy(fragmentRetries = "3"))
        assertTrue("--fragment-retries 3" in fragmentsOnly.options)
        assertNone(fragmentsOnly, "--retries")
    }

    @Test
    fun ytDlpHeaders() {
        val b = yt(base.copy(customHeaders = "Authorization: Bearer abc\nUser-Agent: Foo Bar\nCookie: a=b\nX-Api-Key: k\nnot a header\nEmpty:"))
        assertEquals(
            listOf("--add-header \"Authorization:***\"", "--add-header \"User-Agent:Foo Bar\"", "--add-header \"Cookie:***\"", "--add-header \"X-Api-Key:***\""),
            b.options.filter { it.startsWith("--add-header") },
        )
        assertNone(yt(base), "--add-header")
    }

    @Test
    fun ytDlpFormatSort() {
        ytOnOff(base.copy(resolutionCap = "720"), base, "-S res:720,proto")
        // `if resolution_cap and not audio_only`.
        assertTrue("-S proto" in yt(base.copy(resolutionCap = "720", audioOnly = true)).options)
        // `if output_format == "mp4" and not audio_only` — outside `if ffmpeg_path`, so with or without it.
        assertTrue("-S res:1080,vcodec:h264,acodec:aac,proto" in yt(base.copy(resolutionCap = "1080", outputFormat = "mp4")).options)
        assertTrue("-S proto" in yt(base.copy(outputFormat = "mp4", audioOnly = true)).options)
        // Settings' terms last, trimmed, empty ones dropped.
        assertTrue("-S proto,codec:vp9,fps" in yt(base.copy(formatSort = "codec:vp9, fps,")).options)
    }

    @Test
    fun ytDlpFormat() {
        assertTrue("-f 137+140" in yt(base.copy(formatOverride = "137+140")).options)
        assertTrue("-f 137+140" in yt(ff.copy(formatOverride = "137+140")).options)
        // audio_only overrides the override; the selector follows audio_format.
        assertTrue("-f \"bestaudio[ext=m4a]/bestaudio/best\"" in yt(base.copy(audioOnly = true, formatOverride = "137+140")).options)
        assertTrue("-f \"bestaudio[ext=m4a]/bestaudio/best\"" in yt(base.copy(audioOnly = true, audioFormat = "m4a")).options)
        assertTrue("-f bestaudio/best" in yt(base.copy(audioOnly = true, audioFormat = "mp3")).options)
        assertTrue("-f bestaudio/best" in yt(base.copy(audioOnly = true, audioFormat = "flac")).options)
        assertTrue("-f \"bestaudio[acodec=opus]/bestaudio/best\"" in yt(base.copy(audioOnly = true, audioFormat = "opus")).options)
    }

    @Test
    fun ytDlpAudioExtraction() {
        // codec = audio_format if in (mp3, m4a, opus, flac) else "best"; quality mp3 "0", m4a 256, opus 160.
        val audio = ff.copy(audioOnly = true)
        ytOnOff(audio, ff, "-x --audio-format best", "--embed-metadata", "--no-embed-chapters", "--embed-thumbnail")
        assertTrue("-x --audio-format mp3 --audio-quality 0" in yt(audio.copy(audioFormat = "mp3")).options)
        assertTrue("-x --audio-format m4a --audio-quality 256K" in yt(audio.copy(audioFormat = "m4a")).options)
        assertTrue("-x --audio-format opus --audio-quality 160K" in yt(audio.copy(audioFormat = "opus")).options)
        assertTrue("-x --audio-format flac" in yt(audio.copy(audioFormat = "flac")).options)
        // ExtractAudio only exists with ffmpeg.
        assertNone(yt(base.copy(audioOnly = true, audioFormat = "mp3")), "-x")
        // Normalizing: _LocalTrimPP(normalize=...) only for audio, only with ffmpeg.
        val loudnorm = Note(R.string.command_note_app, listOf("loudnorm"))
        assertTrue(loudnorm in yt(audio.copy(normalizeAudio = true)).notes)
        assertFalse(loudnorm in yt(audio).notes)
        assertFalse(loudnorm in yt(ff.copy(normalizeAudio = true)).notes)
        assertFalse(loudnorm in yt(base.copy(audioOnly = true, normalizeAudio = true)).notes)
    }

    @Test
    fun ytDlpEmbedding() {
        // FFmpegMetadata's add_chapters is embed_chapters; the CLI's --embed-metadata alone adds chapters
        // (yt_dlp/__init__.py: "if (opts.addmetadata or ...) and opts.addchapters is None").
        ytOnOff(ff.copy(embedMetadata = true), ff, "--embed-metadata", "--no-embed-chapters")
        assertNone(yt(ff.copy(embedMetadata = true)), "--embed-chapters")
        ytOnOff(ff.copy(embedChapters = true), ff, "--embed-metadata", "--embed-chapters")
        assertFalse("--no-embed-chapters" in yt(ff.copy(embedChapters = true, embedMetadata = true)).options)
        ytOnOff(ff.copy(embedThumbnail = true), ff, "--embed-thumbnail")
        // All postprocessors: nothing without ffmpeg.
        val noFfmpeg = yt(base.copy(embedMetadata = true, embedChapters = true, embedThumbnail = true))
        listOf("--embed-metadata", "--embed-chapters", "--no-embed-chapters", "--embed-thumbnail", "--ffmpeg-location", "--merge-output-format").forEach { assertNone(noFfmpeg, it) }
    }

    @Test
    fun ytDlpContainer() {
        // merge_output_format: "mp4/mkv" for mp4, else the format or mkv.
        assertTrue("--merge-output-format mp4/mkv" in yt(ff.copy(outputFormat = "mp4")).options)
        assertTrue("--merge-output-format webm" in yt(ff.copy(outputFormat = "webm")).options)
        // _Mp4ConvertPP: `output_format == "mp4" and not audio_only`, with ffmpeg.
        val mp4 = Note(R.string.command_note_mp4)
        assertTrue(mp4 in yt(ff.copy(outputFormat = "mp4")).notes)
        assertFalse(mp4 in yt(ff.copy(outputFormat = "mp4", audioOnly = true)).notes)
        assertFalse(mp4 in yt(base.copy(outputFormat = "mp4")).notes)
    }

    @Test
    fun ytDlpSubtitles() {
        // `if (download_subtitles or save_subtitle_files) and not audio_only`, inside `if ffmpeg_path`.
        val subs = ff.copy(downloadSubtitles = true, subtitleLangs = "en,de")
        ytOnOff(subs, ff, "--write-subs --sub-langs en,de", "--embed-subs")
        val files = yt(ff.copy(saveSubtitleFiles = true))
        assertTrue("--write-subs --sub-langs en" in files.options)
        assertNone(files, "--embed-subs")
        assertNone(yt(subs.copy(audioOnly = true)), "--write-subs")
        assertNone(yt(subs.copy(ffmpeg = "")), "--write-subs")
    }

    @Test
    fun ytDlpSponsorBlock() {
        ytOnOff(ff.copy(sponsorBlockRemove = "sponsor,intro"), ff, "--sponsorblock-remove sponsor,intro")
        assertNone(yt(ff.copy(sponsorBlockRemove = "sponsor,intro")), "--embed-chapters")
        // sb_mark makes embed_chapters True.
        ytOnOff(ff.copy(sponsorBlockMark = "sponsor,selfpromo"), ff, "--sponsorblock-mark sponsor,selfpromo", "--embed-metadata", "--embed-chapters")
        // sb_mark minus sb_remove.
        val both = yt(ff.copy(sponsorBlockRemove = "sponsor", sponsorBlockMark = "sponsor,outro"))
        assertTrue("--sponsorblock-remove sponsor" in both.options)
        assertTrue("--sponsorblock-mark outro" in both.options)
        // Dropped for a clipped download and without ffmpeg.
        val clipped = yt(ff.copy(sponsorBlockRemove = "sponsor", sponsorBlockMark = "outro", clipRange = "00:10-00:20"))
        assertNone(clipped, "--sponsorblock")
        assertNone(clipped, "--embed-chapters")
        assertNone(yt(base.copy(sponsorBlockRemove = "sponsor")), "--sponsorblock")
    }

    @Test
    fun ytDlpChaptersAndClip() {
        ytOnOff(ff.copy(splitChapters = true), ff, "--split-chapters")
        // split_chapters and ffmpeg_path and not clip_ranges.
        assertNone(yt(ff.copy(splitChapters = true, clipRange = "1:00-2:00")), "--split-chapters")
        assertNone(yt(base.copy(splitChapters = true)), "--split-chapters")
        ytOnOff(ff.copy(clipRange = "00:10-00:20,01:00-01:30"), ff, "--download-sections \"*00:10-00:20,01:00-01:30\"")
        // _LocalTrimPP is only added under `if ffmpeg_path:` — no ffmpeg, nothing is cut.
        assertNone(yt(base.copy(clipRange = "00:10-00:20")), "--download-sections")
    }

    @Test
    fun ytDlpCookies() {
        val cookies = "/data/files/cookies.txt"
        ytOnOff(base.copy(cookies = cookies), base, "--cookies cookies.txt")
        val youtube403 = Note(R.string.command_note_youtube_403)
        assertTrue(youtube403 in yt(base.copy(url = "https://youtu.be/abc", cookies = cookies)).notes)
        assertTrue(youtube403 in yt(base.copy(url = "https://www.youtube.com/watch?v=abc", cookies = cookies)).notes)
        assertFalse(youtube403 in yt(base.copy(cookies = cookies)).notes)
        assertFalse(youtube403 in yt(base.copy(url = "https://youtu.be/abc")).notes)
    }

    @Test
    fun ytDlpProxyMasked() {
        assertTrue("--proxy \"http://user:***@host:8080\"" in yt(base.copy(proxy = "http://user:secret@host:8080")).options)
        assertTrue("--proxy http://host:8080" in yt(base.copy(proxy = "http://host:8080")).options)
        assertNone(yt(base), "--proxy")
    }

    @Test
    fun ytDlpClientRotationAndExtractorArgs() {
        val rotation = "--extractor-args youtube:player_client=default,android,web,ios"
        ytOnOff(base.copy(youtubeClientRotation = true), base, rotation)
        // youtube_args.setdefault("player_client", ...): the user's own wins.
        val own = yt(base.copy(youtubeClientRotation = true, extractorArgs = "youtube:player_client=web"))
        assertEquals(listOf("--extractor-args youtube:player_client=web"), own.options.filter { it.startsWith("--extractor-args") })
        // The key is normalized (lower case, "-" to "_") by _parse_extractor_args.
        val dashed = yt(base.copy(youtubeClientRotation = true, extractorArgs = "YouTube:Player-Client=web"))
        assertEquals(listOf("--extractor-args YouTube:Player-Client=web"), dashed.options.filter { it.startsWith("--extractor-args") })
        // Other youtube args: rotation merged into that block (a second youtube:... would replace the first on the CLI).
        val merged = yt(base.copy(youtubeClientRotation = true, extractorArgs = "youtube:po_token=x"))
        assertEquals(listOf("--extractor-args \"youtube:player_client=default,android,web,ios;po_token=x\""), merged.options.filter { it.startsWith("--extractor-args") })
        // Blocks are whitespace-separated, one --extractor-args each.
        val blocks = yt(base.copy(extractorArgs = "youtube:lang=de  generic:impersonate=chrome"))
        assertEquals(listOf("--extractor-args youtube:lang=de", "--extractor-args generic:impersonate=chrome"), blocks.options.filter { it.startsWith("--extractor-args") })
        val otherIe = yt(base.copy(youtubeClientRotation = true, extractorArgs = "generic:impersonate=chrome"))
        assertEquals(listOf(rotation, "--extractor-args generic:impersonate=chrome"), otherIe.options.filter { it.startsWith("--extractor-args") })
    }

    @Test
    fun ytDlpFilenameAndTags() {
        // A yt-dlp template is the outtmpl; anything else is the app's own format (_CaptionPP).
        ytOnOff(base.copy(filenameFormat = "%(title)s [%(id)s].%(ext)s"), base, "-o \"%(title)s [%(id)s].%(ext)s\"")
        val custom = yt(base.copy(filenameFormat = "{poster_name} - {title}"))
        assertNone(custom, "-o")
        assertEquals(listOf(Note(R.string.command_note_filename, listOf("{poster_name} - {title}"))), custom.notes)
        val tags = yt(base.copy(overrideTitle = "Song", overrideArtist = "Band"))
        assertEquals(listOf(Note(R.string.command_note_title, listOf("Song")), Note(R.string.command_note_artist, listOf("Band"))), tags.notes)
    }

    @Test
    fun ytDlpExtraArgsLast() {
        val b = yt(base.copy(forceIpv4 = true, extraArgs = "  --no-part --password hunter2 "))
        assertEquals("--no-part --password ***", b.options.last())
        assertEquals(yt(base).options, yt(base.copy(extraArgs = "   ")).options)
    }

    @Test
    fun ytDlpAudioLanguage() {
        // Outputs of yt_dlp_wrapper._with_audio_language, run with Python 3.13 on the wrapper's own code.
        listOf(
            Triple("bestvideo+bestaudio/best", "es", "bestvideo+bestaudio[language^=es]/bestvideo+bestaudio/best"),
            Triple("best/18", "es", "best/18"),
            Triple("bestaudio[ext=m4a]/bestaudio/best", "de", "bestaudio[language^=de][ext=m4a]/bestaudio[language^=de]/bestaudio[ext=m4a]/bestaudio/best"),
            Triple("bestaudio[acodec=opus]/bestaudio/best", "pt-BR", "bestaudio[language^=pt-BR][acodec=opus]/bestaudio[language^=pt-BR]/bestaudio[acodec=opus]/bestaudio/best"),
            Triple("bv*+ba/b", "ja", "bv*+ba[language^=ja]/bv*+ba/b"),
            Triple("137+ba*", "fr", "137+ba*[language^=fr]/137+ba*"),
            Triple("bestaudio", "es", "bestaudio[language^=es]/bestaudio"),
            Triple("bestvideo+bestaudio/best", "", "bestvideo+bestaudio/best"),
            Triple("", "es", ""),
            Triple("bestaudiox/bestaudio", "es", "bestaudio[language^=es]/bestaudiox/bestaudio"),
            Triple("ba[abr>100]/worstaudio", "es", "ba[language^=es][abr>100]/ba[abr>100]/worstaudio"),
        ).forEach { (format, language, expected) ->
            assertEquals("_with_audio_language($format, $language)", expected, CommandPreview.withAudioLanguage(format, language))
        }
        // The same selector regex as the wrapper's.
        assertTrue(script("yt_dlp_wrapper.py").contains("_AUDIO_SELECTOR_RE = re.compile(r\"\\b(bestaudio\\*?|ba\\*?)(?=[\\[+/]|\$)\")"))

        val dub = yt(ff.copy(audioLanguage = "es"))
        assertTrue("-f \"bestvideo+bestaudio[language^=es]/bestvideo+bestaudio/best\"" in dub.options)
        // _AudioLanguageTagPP: with ffmpeg.
        assertEquals(listOf(Note(R.string.command_note_audio_tag, listOf("es"))), dub.notes)
        assertEquals(emptyList<Note>(), yt(base.copy(audioLanguage = "es")).notes)
        assertTrue("-f \"bestaudio[language^=es][ext=m4a]/bestaudio[language^=es]/bestaudio[ext=m4a]/bestaudio/best\"" in yt(ff.copy(audioOnly = true, audioLanguage = "es")).options)
    }

    @Test
    fun ytDlpFlagsInTheWrapperOrder() {
        // Everything on: one line each, in ydl_opts' order.
        val all = ff.copy(
            restrictFilenames = true, noPlaylist = true, verbose = true, forceIpv4 = true, concurrentFragments = "3",
            noCheckCertificates = true, sleepInterval = "4", socketTimeout = "15", bufferSizeKb = "8",
            aria2 = "/x/libaria2c.so", customHeaders = "Referer: https://a.b", retries = "2", fragmentRetries = "6",
            embedMetadata = true, jsRuntime = "/x/libqjs.so", cookies = "/x/cookies.txt", archive = "/x/a.txt",
            limitRate = "1M", proxy = "socks5://p:q@h:1", liveFromStart = true, youtubeClientRotation = true,
            saveThumbnail = true, maxFilesize = "9M", writeInfoFiles = true, extraArgs = "--no-part",
        )
        assertEquals(
            listOf(
                "-f bestvideo+bestaudio/best", "-S proto", "--restrict-filenames", "--no-mtime", "--abort-on-error", "-v", "-4", "-N 3",
                "--no-check-certificates", "--sleep-interval 2 --max-sleep-interval 4", "--socket-timeout 15", "--buffer-size 8K",
                "--downloader aria2c --downloader-args aria2c:--ca-certificate=cacert.pem", "--add-header Referer:https://a.b",
                "--retries 2", "--fragment-retries 6", "--no-playlist",
                "--ffmpeg-location libffmpeg.so", "--merge-output-format mkv",
                "--parse-metadata \"%(uploader,channel,creator|Unknown)s:(?P<uploader>.*?)(?: - Topic)?\\\$\"",
                "--embed-metadata", "--no-embed-chapters", "--no-js-runtimes --js-runtimes quickjs:libqjs.so", "--cookies cookies.txt", "--download-archive a.txt", "-r 1M",
                "--proxy \"socks5://p:***@h:1\"", "--live-from-start", "--extractor-args youtube:player_client=default,android,web,ios",
                "--write-thumbnail", "--max-filesize 9M", "--write-info-json --write-description", "--no-part",
            ),
            yt(all).options,
        )
    }

    @Test
    fun ytDlpConstantsAreTheWrapper() {
        val src = script("yt_dlp_wrapper.py")
        // The --parse-metadata pair is the MetadataParser action's.
        assertTrue(src.contains("\"%(uploader,channel,creator|Unknown)s\",\n                r\"(?P<uploader>.*?)(?: - Topic)?\$\","))
        assertTrue(src.contains("youtube_args.setdefault(\"player_client\", [\"default\", \"android\", \"web\", \"ios\"])"))
        assertTrue(src.contains("ydl_opts[\"sleep_interval\"] = 2"))
        // An extra --audio-quality wins over these defaults.
        assertTrue(src.contains("quality = extra_audio_quality or {\"mp3\": \"0\", \"m4a\": \"256\", \"opus\": \"160\"}.get(codec)"))
    }

    // ── gallery-dl ──────────────────────────────────────────────────────────────────────────────

    private val gBase = GalleryDlArgs(url = "https://example.com/g")
    private fun g(a: GalleryDlArgs, redditLogin: Boolean = false) = CommandPreview.galleryDl(a, redditLogin)
    private val videoFilter = "extension not in ('mp4','webm','mov','mkv','m4v','avi','flv','wmv')"

    @Test
    fun galleryDlAllOff() {
        assertEquals(listOf("-o downloader.mtime=false"), g(gBase).options)
        assertEquals("gallery-dl", g(gBase).program)
    }

    @Test
    fun galleryDlRedditClientId() {
        val clientId = "-o extractor.reddit.client-id=6N9uN0krSDE-ig"
        assertOnOff(g(gBase.copy(url = "https://www.reddit.com/r/x/comments/1")), g(gBase), clientId)
        assertTrue(clientId in g(gBase.copy(url = "https://redd.it/abc")).options)
        // _reddit_client_args: not with a Reddit login.
        assertFalse(clientId in g(gBase.copy(url = "https://old.reddit.com/r/x", cookies = "/x/cookies.txt"), redditLogin = true).options)
    }

    @Test
    fun galleryDlSwitches() {
        assertOnOff(g(gBase.copy(cookies = "/data/files/cookies.txt")), g(gBase), "--cookies cookies.txt")
        assertOnOff(g(gBase.copy(limitRate = "2M")), g(gBase), "--limit-rate 2M")
        assertOnOff(g(gBase.copy(archive = "/x/download.sqlite3")), g(gBase), "--download-archive download.sqlite3")
        assertOnOff(g(gBase.copy(retries = "3")), g(gBase), "-o extractor.retries=3 -o downloader.retries=3")
        assertOnOff(g(gBase.copy(maxFilesize = "50M")), g(gBase), "-o downloader.filesize-max=50M")
        assertOnOff(g(gBase.copy(socketTimeout = "30")), g(gBase), "-o extractor.timeout=30 -o downloader.timeout=30")
        assertOnOff(g(gBase.copy(sleepInterval = "5")), g(gBase), "--sleep 2-5")
        // `if sleep_interval_seconds:` on the string, then max(2, int(...)).
        assertTrue("--sleep 2-2" in g(gBase.copy(sleepInterval = "1")).options)
        assertOnOff(g(gBase.copy(restrictFilenames = true)), g(gBase), "-o \"path-restrict=^0-9A-Za-z_.\\\\[\\\\]-\"")
        assertOnOff(g(gBase.copy(forceIpv4 = true)), g(gBase), "--force-ipv4")
        assertOnOff(g(gBase.copy(noCheckCertificates = true)), g(gBase), "--no-check-certificate")
        assertOnOff(g(gBase.copy(writeInfoFiles = true)), g(gBase), "--write-metadata")
        assertOnOff(g(gBase.copy(proxy = "http://u:pw@h:3128")), g(gBase), "--proxy \"http://u:***@h:3128\"")
        assertEquals("--range 1-3", g(gBase.copy(extraArgs = " --range 1-3 ")).options.last())
    }

    @Test
    fun galleryDlFilenameFormat() {
        val default = "{poster_name|category} - {title} [{id}].{extension}"
        val d = g(gBase.copy(filenameFormat = default))
        assertTrue("-o \"extractor.filename=$default\"" in d.options)
        assertTrue("-o \"extractor.directlink.filename={filename} [{subcategory}].{extension}\"" in d.options)
        assertNone(d, "--filename")
        val custom = g(gBase.copy(filenameFormat = "{id}.{extension}"))
        assertTrue("--filename \"{id}.{extension}\"" in custom.options)
        assertNone(custom, "-o \"extractor.filename")
    }

    @Test
    fun galleryDlCombinedFilter() {
        assertTrue("--filter \"num in {1,3}\"" in g(gBase.copy(itemFilter = "num in {1,3}")).options)
        assertTrue("--filter \"$videoFilter\"" in g(gBase.copy(excludeVideo = true)).options)
        assertEquals(
            listOf("--filter \"(num in {1,3}) and $videoFilter\""),
            g(gBase.copy(itemFilter = "num in {1,3}", excludeVideo = true)).options.filter { it.startsWith("--filter") },
        )
        assertNone(g(gBase), "--filter")
    }

    @Test
    fun galleryDlArgvOrder() {
        val all = gBase.copy(
            url = "https://www.reddit.com/r/x", cookies = "/x/c.txt", filenameFormat = "{id}", limitRate = "1M", proxy = "http://h:1",
            itemFilter = "num == 1", archive = "/x/a.sqlite3", retries = "2", maxFilesize = "5M", socketTimeout = "9",
            sleepInterval = "3", restrictFilenames = true, forceIpv4 = true, noCheckCertificates = true, writeInfoFiles = true, extraArgs = "-v",
        )
        // gallery_dl_wrapper.download's args, in its order.
        assertEquals(
            listOf(
                "-o extractor.reddit.client-id=6N9uN0krSDE-ig", "--cookies c.txt", "--filename \"{id}\"", "--limit-rate 1M", "--proxy http://h:1",
                "--filter \"num == 1\"", "--download-archive a.sqlite3", "-o extractor.retries=2 -o downloader.retries=2", "-o downloader.mtime=false",
                "-o downloader.filesize-max=5M", "-o extractor.timeout=9 -o downloader.timeout=9", "--sleep 2-3",
                "-o \"path-restrict=^0-9A-Za-z_.\\\\[\\\\]-\"", "--force-ipv4", "--no-check-certificate", "--write-metadata", "-v",
            ),
            g(all).options,
        )
    }

    @Test
    fun galleryDlConstantsAreTheWrapper() {
        val src = script("gallery_dl_wrapper.py")
        assertTrue(src.contains("[\"-o\", \"extractor.reddit.client-id=6N9uN0krSDE-ig\"]"))
        assertTrue(src.contains("\"-o\", \"extractor.directlink.filename={filename} [{subcategory}].{extension}\""))
        assertTrue(src.contains("filename_format.startswith(\"{poster_name|category}\")"))
        assertTrue(src.contains("\"$videoFilter\" if exclude_video"))
        // The shell-unquoted preview value is the wrapper's raw string.
        assertTrue(src.contains("r\"path-restrict=^0-9A-Za-z_.\\[\\]-\""))
        assertEquals("path-restrict=^0-9A-Za-z_.\\[\\]-", shellUnquote("\"path-restrict=^0-9A-Za-z_.\\\\[\\\\]-\""))
        assertTrue(src.contains("args.extend([\"-o\", \"downloader.mtime=false\"])"))
    }

    // ── Spotify ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun spotifyIsTheYtDlpCommandPerTrack() {
        val s = SpotifyArgs(url = "https://open.spotify.com/track/x", ffmpeg = "/x/libffmpeg.so", audioFormat = "mp3", proxy = "http://h:1")
        val b = CommandPreview.spotify(s)
        assertEquals("yt-dlp", b.program)
        assertEquals("https://www.youtube.com/watch?v=…", b.url)
        assertEquals(CommandPreview.ytDlp(s.perTrack("")).options, b.options)
        assertTrue("-x --audio-format mp3 --audio-quality 0" in b.options)
        assertTrue("--no-playlist" in b.options)
        assertEquals(Note(R.string.command_note_spotify), b.notes.first())

        val picked = CommandPreview.spotify(s.copy(playlistItems = "1,4", overrideTitle = "T", overrideArtist = "A"))
        assertEquals(
            listOf(
                Note(R.string.command_note_spotify), Note(R.string.command_note_tracks, listOf("1,4")),
                Note(R.string.command_note_title, listOf("T")), Note(R.string.command_note_artist, listOf("A")),
            ),
            picked.notes,
        )
        // The tracks pick isn't yt-dlp's -I: spotify_wrapper picks the tracks itself.
        assertNone(picked, "-I")
        assertTrue(Note(R.string.command_note_app, listOf("loudnorm")) in CommandPreview.spotify(s.copy(normalizeAudio = true)).notes)
    }

    // ── Instaloader ─────────────────────────────────────────────────────────────────────────────

    private val iBase = InstaloaderArgs(url = "https://www.instagram.com/p/abc/")
    private fun il(a: InstaloaderArgs) = CommandPreview.instaloader(a)
    private fun app(text: String) = Note(R.string.command_note_app, listOf(text))

    @Test
    fun instaloader() {
        // save_metadata=False unless write_info_files; max_connection_attempts retries + 1, else 3.
        assertEquals(listOf("--no-metadata-json", "--max-connection-attempts 3"), il(iBase).options)
        assertEquals(emptyList<Note>(), il(iBase).notes)
        assertFalse("--no-metadata-json" in il(iBase.copy(writeInfoFiles = true)).options)
        assertTrue("--max-connection-attempts 6" in il(iBase.copy(retries = "5")).options)
        assertTrue("--max-connection-attempts 1" in il(iBase.copy(retries = "0")).options)
        assertOnOff(il(iBase.copy(socketTimeout = "30")), il(iBase), "--request-timeout 30")

        listOf(
            iBase.copy(cookies = "/x/cookies.txt") to app("cookies cookies.txt"),
            iBase.copy(itemFilter = "num in {2}") to app("--filter num in {2}"),
            iBase.copy(archive = "/x/download.instaloader.txt") to app("--download-archive download.instaloader.txt"),
            iBase.copy(proxy = "http://u:p@h:1") to app("--proxy http://u:***@h:1"),
            iBase.copy(sleepInterval = "6") to app("--sleep 2-6"),
            iBase.copy(limitRate = "1M") to app("--limit-rate 1M"),
            iBase.copy(maxFilesize = "20M") to app("--max-filesize 20M"),
            iBase.copy(restrictFilenames = true) to app("--restrict-filenames"),
            iBase.copy(forceIpv4 = true) to app("--force-ipv4"),
            iBase.copy(noCheckCertificates = true) to app("--no-check-certificate"),
            iBase.copy(filenameFormat = "{shortcode}") to Note(R.string.command_note_filename, listOf("{shortcode}")),
        ).forEach { (args, note) -> assertEquals(listOf(note), il(args).notes) }
    }

    // ── Masking ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun proxyPasswordMasked() {
        assertEquals("http://user:***@host:8080", CommandPreview.maskProxy("http://user:pass@host:8080"))
        assertEquals("socks5://u:***@h:1080", CommandPreview.maskProxy("socks5://u:pw@h:1080"))
        assertEquals("http://user:***@host:8080", CommandPreview.maskProxy("http://user:pa:ss@host:8080"))
        assertEquals("user:***@host:8080", CommandPreview.maskProxy("user:pass@host:8080"))
        assertEquals("http://host:8080", CommandPreview.maskProxy("http://host:8080"))
        assertEquals("http://user@host:8080", CommandPreview.maskProxy("http://user@host:8080"))
        assertEquals("http://host:8080/a@b", CommandPreview.maskProxy("http://host:8080/a@b"))
    }

    @Test
    fun extraArgsMasked() {
        val m = CommandPreview::maskArgs
        assertEquals("--password ***", m("--password x"))
        assertEquals("-p ***", m("-p x"))
        assertEquals("--password=***", m("--password=x"))
        assertEquals("--password ***", m("--password \"two words\""))
        assertEquals("--video-password *** --ap-password ***", m("--video-password a --ap-password b"))
        assertEquals("--add-header \"Authorization: ***\"", m("--add-header \"Authorization: Bearer x\""))
        // Shown re-quoted the way the engines split it.
        assertEquals("--add-header \"Cookie: ***\"", m("--add-header 'Cookie: a=b; c=d'"))
        assertEquals("--add-header X-Api-Key:***", m("--add-header X-Api-Key:abc"))
        assertEquals("-o extractor.pixiv.refresh-token=***", m("-o extractor.pixiv.refresh-token=abc"))
        assertEquals("-o extractor.twitter.password=***", m("-o \"extractor.twitter.password=abc\""))
        // Forms the first version missed (found testing real commands):
        assertEquals("-p***", m("-pSECRET"))
        assertEquals("--add-header=Authorization:***", m("--add-header=Authorization:SECRET"))
        assertEquals("-o extractor.x.password=***", m("-o \"extractor.x.password=my SECRET\""))
        assertEquals("--option extractor.x.password=***", m("--option extractor.x.password=SECRET"))
        assertEquals("-oextractor.x.password=***", m("-oextractor.x.password=SECRET"))
        assertEquals("-o extractor.fanbox.sessionid=***", m("-o extractor.fanbox.sessionid=abc"))
        assertEquals("--proxy http://user:***@host:8080", m("--proxy http://user:SECRET@host:8080"))
        assertEquals("--extractor-args \"youtube:po_token=***;player_client=web\"", m("--extractor-args youtube:po_token=abc;player_client=web"))
        // A " -p " inside a quoted value isn't a password flag, and the quote survives.
        assertEquals("--filter \"title contains ' -p '\" --range 1-2", m("--filter \"title contains ' -p '\" --range 1-2"))
        // Text the engines can't split is still masked, by pattern.
        assertEquals("--password *** --ua \"Mozilla", m("--password x --ua \"Mozilla"))
        // Ordinary flags untouched — "-P" (yt-dlp's --paths) included.
        listOf(
            "--no-part -N 4 --user-agent \"Foo Bar\"",
            "--add-header \"Referer: https://example.com\"",
            "-P /sdcard/Download --playlist-items 1-3",
            "-o extractor.timeout=30 --range 1-5",
            "--username me",
        ).forEach { assertEquals(it, m(it)) }
    }

    // ── quote() and rendering ───────────────────────────────────────────────────────────────────

    @Test
    fun quote() {
        val q = CommandPreview::quote
        listOf("plain", "best/18", "res:720,proto", "aria2c:--ca-certificate=cacert.pem", "1,3", "-4", "a=b", "http://h:1/x").forEach { assertEquals(it, q(it)) }
        assertEquals("\"\"", q(""))
        assertEquals("\"a b\"", q("a b"))
        assertEquals("\"a\\\"b\"", q("a\"b"))
        assertEquals("\"\\\$HOME\"", q("\$HOME"))
        assertEquals("\"\\`x\\`\"", q("`x`"))
        assertEquals("\"a\\\\b\"", q("a\\b"))
        assertEquals("\"it's\"", q("it's"))
        listOf("*", "a?b", "[x]", "{x}", "(x)", "a|b", "a&b", "a;b", "<x>", "!x", "#x", "~/x", "a\tb", "a\nb").forEach {
            assertEquals("\"$it\"", q(it))
        }
        // Whatever it quotes, a shell reads back as the value.
        listOf("a \"b\" \$c `d` \\e", "%(title)s [%(id)s].%(ext)s", "path-restrict=^0-9A-Za-z_.\\[\\]-").forEach {
            assertEquals(it, shellUnquote(q(it)))
        }
    }

    @Test
    fun renderNotesThenOneOptionPerLine() {
        val block = CommandPreview.Block("yt-dlp", listOf("-f best", "-4"), "https://a.b/c d", listOf(Note(1, listOf("x")), Note(2)))
        assertEquals("# 1:x\n# 2:\nyt-dlp \\\n  -f best \\\n  -4 \\\n  \"https://a.b/c d\"", block.render { "${it.res}:${it.args.joinToString()}" })
        // No link (Settings' template has the placeholder; an empty url is left out).
        assertEquals("gallery-dl \\\n  -v", CommandPreview.Block("gallery-dl", listOf("-v"), "").render { "" })
        // Later blocks under "then, if needed".
        val two = CommandPreview.render(listOf(CommandPreview.Block("a", emptyList(), "u"), CommandPreview.Block("b", emptyList(), "u"))) {
            if (it.res == R.string.command_note_then) "THEN" else "?"
        }
        assertEquals("a \\\n  u\n\n# THEN\nb \\\n  u", two)
    }

    /** What a POSIX shell makes of one double-quoted word (the escapes quote() writes). */
    private fun shellUnquote(word: String): String {
        if (!word.startsWith("\"")) return word
        val inner = word.substring(1, word.length - 1)
        return buildString {
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length && inner[i + 1] in "\\\"\$`") { append(inner[i + 1]); i += 2 } else { append(c); i++ }
            }
        }
    }
}
