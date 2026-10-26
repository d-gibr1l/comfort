package com.comfort.app.worker

import com.comfort.app.R
import java.io.File

/** The preview sheet's and Settings' "Current command": each engine's real arguments ([YtDlpArgs]
 * and the others, the same objects a download hands the wrapper) written as the command line that
 * does the same — one line per option, so a setting switched off is a line gone. What the wrappers
 * do that no flag covers (normalizing, the MP4 re-encode, ...) is a note above the command instead
 * of a made-up flag. Secrets (a proxy's password, auth headers, passwords in extra arguments) are
 * masked, as the command can be copied. Pure Kotlin, so it's unit-tested without a device. */
internal object CommandPreview {

    /** A note line above a command: a string resource and its arguments, put in the app's
     * language by whoever shows it. */
    data class Note(val res: Int, val args: List<String> = emptyList())

    data class Block(val program: String, val options: List<String>, val url: String, val notes: List<Note> = emptyList()) {
        /** The notes as "# ..." lines, then the command, one option per continued line. */
        fun render(text: (Note) -> String): String = buildString {
            notes.forEach { append("# ").append(text(it)).append('\n') }
            append((listOf(program) + options + listOfNotNull(url.takeIf { it.isNotEmpty() }?.let(::quote))).joinToString(" \\\n  "))
        }
    }

    /** Several engines' blocks, in the order they'd run; the later ones under a "then, if needed"
     * note (a fallback, or gallery-dl's video supplement). */
    fun render(blocks: List<Block>, text: (Note) -> String): String =
        blocks.mapIndexed { i, block ->
            val withHeader = if (i == 0) block else block.copy(notes = listOf(Note(R.string.command_note_then)) + block.notes)
            withHeader.render(text)
        }.joinToString("\n\n")

    // ── yt-dlp ──────────────────────────────────────────────────────────────────────────────────
    // Mirrors yt_dlp_wrapper.download's ydl_opts, in the order it builds them.

    fun ytDlp(a: YtDlpArgs, extraNotes: List<Note> = emptyList(), impersonateAvailable: Boolean = true): Block {
        val o = mutableListOf<String>()
        val notes = extraNotes.toMutableList()
        val ffmpeg = a.ffmpeg.isNotEmpty()
        val clipped = a.clipRange.isNotBlank()

        // Format: the audio stream for the audio format, or the override, or the default.
        val audioSelector = when (a.audioFormat) {
            "mp3", "flac" -> "bestaudio/best"
            "opus" -> "bestaudio[acodec=opus]/bestaudio/best"
            else -> "bestaudio[ext=m4a]/bestaudio/best"
        }
        val baseFormat = if (a.audioOnly) audioSelector else a.formatOverride.ifEmpty { if (ffmpeg) "bestvideo+bestaudio/best" else "best/18" }
        o += "-f ${quote(withAudioLanguage(baseFormat, a.audioLanguage))}"

        // Format sort: the quality cap, MP4-friendly codecs, single files over pieces, then Settings'.
        val sort = buildList {
            if (positive(a.resolutionCap) != null && !a.audioOnly) add("res:${a.resolutionCap}")
            if (a.outputFormat == "mp4" && !a.audioOnly) { add("vcodec:h264"); add("acodec:aac") }
            add("proto")
            addAll(a.formatSort.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        o += "-S ${quote(sort.joinToString(","))}"

        if (a.restrictFilenames) o += "--restrict-filenames"
        o += "--no-mtime"
        // The API's default is to stop at the first error; the wrapper keeps the CLI's skipping
        // of failed items unless it's a single video.
        if (a.noPlaylist) o += "--abort-on-error"
        if (a.verbose) o += "-v"
        if (a.forceIpv4) o += "-4"
        // The wrapper reads these three as ints and skips a 0 ("if concurrent_fragments:" ...).
        positive(a.concurrentFragments)?.let { o += "-N $it" }
        if (a.noCheckCertificates) o += "--no-check-certificates"
        positive(a.sleepInterval)?.let { o += "--sleep-interval 2 --max-sleep-interval ${maxOf(2, it)}" }
        if (a.socketTimeout.isNotEmpty()) o += "--socket-timeout ${a.socketTimeout}"
        positive(a.bufferSizeKb)?.let { o += "--buffer-size ${it}K" }
        if (a.aria2.isNotEmpty()) {
            if (a.impersonate) notes += Note(R.string.command_note_aria2_off)
            else o += "--downloader aria2c --downloader-args ${quote("aria2c:--ca-certificate=cacert.pem")}"
        }
        headerLines(a.customHeaders).forEach { o += "--add-header ${quote(it)}" }
        // Only with curl_cffi, which only arm64's Python has (_IMPERSONATE_AVAILABLE). aria2c stays
        // off either way: the wrapper checks the setting, not the backend.
        if (a.impersonate) {
            if (impersonateAvailable) o += "--impersonate \"\""
            else notes += Note(R.string.command_note_impersonate_unavailable)
        }
        if (a.retries.isNotEmpty()) o += "--retries ${a.retries}"
        (a.fragmentRetries.ifEmpty { a.retries }).takeIf { it.isNotEmpty() }?.let { o += "--fragment-retries $it" }
        if (a.playlistItems.isNotEmpty()) o += "-I ${a.playlistItems}"
        else if (a.noPlaylist) o += "--no-playlist"

        if (ffmpeg) {
            o += "--ffmpeg-location ${File(a.ffmpeg).name}"
            o += "--merge-output-format ${if (a.outputFormat == "mp4") "mp4/mkv" else a.outputFormat.ifEmpty { "mkv" }}"
            // YouTube's "<Artist> - Topic" channels: the uploader without the suffix.
            o += "--parse-metadata ${quote("%(uploader,channel,creator|Unknown)s:(?P<uploader>.*?)(?: - Topic)?$")}"
            if (a.audioOnly) {
                val codec = a.audioFormat.takeIf { it in setOf("mp3", "m4a", "opus", "flac") } ?: "best"
                o += "-x --audio-format $codec" + (mapOf("mp3" to "0", "m4a" to "256K", "opus" to "160K")[codec]?.let { " --audio-quality $it" } ?: "")
            }
            // SponsorBlock and split chapters are left out of a trimmed download (times in the whole video).
            val remove = if (clipped) emptySet() else categories(a.sponsorBlockRemove)
            val mark = if (clipped) emptySet() else categories(a.sponsorBlockMark) - remove
            if (remove.isNotEmpty()) o += "--sponsorblock-remove ${remove.joinToString(",")}"
            if (mark.isNotEmpty()) o += "--sponsorblock-mark ${mark.joinToString(",")}"
            // Marked segments are chapters, so they bring chapters with them.
            val chapters = a.embedChapters || mark.isNotEmpty()
            // One metadata step does both, and an audio download always gets its tags.
            // The wrapper's FFmpegMetadata gets add_chapters=embed_chapters; the CLI's
            // --embed-metadata alone also embeds chapters, so it's said when they're not.
            if (a.embedMetadata || chapters || a.audioOnly) o += "--embed-metadata"
            if (chapters) o += "--embed-chapters"
            else if (a.embedMetadata || a.audioOnly) o += "--no-embed-chapters"
            if (a.embedThumbnail || a.audioOnly) o += "--embed-thumbnail"
            if ((a.downloadSubtitles || a.saveSubtitleFiles) && !a.audioOnly) {
                o += "--write-subs --sub-langs ${quote(a.subtitleLangs.ifBlank { "en" })}"
                if (a.downloadSubtitles) o += "--embed-subs"
            }
            if (a.audioLanguage.isNotEmpty()) notes += Note(R.string.command_note_audio_tag, listOf(a.audioLanguage))
            if (a.audioOnly && a.normalizeAudio) notes += Note(R.string.command_note_app, listOf("loudnorm"))
            if (a.outputFormat == "mp4" && !a.audioOnly) notes += Note(R.string.command_note_mp4)
            if (a.splitChapters && !clipped) o += "--split-chapters"
        }
        // js_runtimes is replaced, not added to; the CLI's --js-runtimes adds to its default (deno).
        if (a.jsRuntime.isNotEmpty()) o += "--no-js-runtimes --js-runtimes ${quote("quickjs:${File(a.jsRuntime).name}")}"
        if (a.cookies.isNotEmpty()) {
            o += "--cookies ${File(a.cookies).name}"
            if (isYoutube(a.url)) notes += Note(R.string.command_note_youtube_403)
        }
        if (a.archive.isNotEmpty()) o += "--download-archive ${File(a.archive).name}"
        if (a.limitRate.isNotEmpty()) o += "-r ${a.limitRate}"
        if (a.proxy.isNotEmpty()) o += "--proxy ${quote(maskProxy(a.proxy))}"
        if (a.liveFromStart) o += "--live-from-start"
        extractorArgBlocks(a.extractorArgs, a.youtubeClientRotation).forEach { o += "--extractor-args ${quote(it)}" }
        if (a.saveThumbnail) o += "--write-thumbnail"
        if (a.maxFilesize.isNotEmpty()) o += "--max-filesize ${a.maxFilesize}"
        if (a.writeInfoFiles) o += "--write-info-json --write-description"
        // The app cuts the finished file (_LocalTrimPP); --download-sections gives the same result.
        // _LocalTrimPP is only added with ffmpeg, so without it nothing is cut.
        if (clipped && ffmpeg) o += "--download-sections ${quote("*${a.clipRange}")}"

        // Filename: a yt-dlp template as given; the app's own (gallery-dl syntax) it fills in itself.
        when {
            a.filenameFormat.contains("%(") -> o += "-o ${quote(a.filenameFormat)}"
            a.filenameFormat.isNotEmpty() -> notes += Note(R.string.command_note_filename, listOf(a.filenameFormat))
        }
        // _CaptionPP caps the caption at 150 bytes; the default format shows it as "[:150]".
        if (a.trimFilenames && !a.filenameFormat.contains("%(") && !a.filenameFormat.contains("[:150]")) {
            notes += Note(R.string.command_note_trim)
        }
        if (a.overrideTitle.isNotEmpty()) notes += Note(R.string.command_note_title, listOf(a.overrideTitle))
        if (a.overrideArtist.isNotEmpty()) notes += Note(R.string.command_note_artist, listOf(a.overrideArtist))

        // Settings' yt-dlp set, then the shared part ("both" + this download's): last, so they
        // win, as in the wrapper — which leaves out what isn't for yt-dlp and says so.
        if (a.extraArgs.isNotBlank()) o += maskArgs(a.extraArgs.trim())
        if (a.sharedExtraArgs.isNotBlank()) o += maskArgs(a.sharedExtraArgs.trim())
        return Block("yt-dlp", o, a.url, notes)
    }

    // ── gallery-dl ──────────────────────────────────────────────────────────────────────────────
    // gallery_dl_wrapper.download builds a real gallery-dl argv; this is that argv.

    fun galleryDl(a: GalleryDlArgs, redditLogin: Boolean = false): Block {
        val o = mutableListOf<String>()
        if (isReddit(a.url) && !redditLogin) o += "-o extractor.reddit.client-id=6N9uN0krSDE-ig"
        if (a.cookies.isNotEmpty()) o += "--cookies ${File(a.cookies).name}"
        if (a.filenameFormat.startsWith("{poster_name|category}")) {
            o += "-o ${quote("extractor.filename=${a.filenameFormat}")}"
            o += "-o ${quote("extractor.directlink.filename={filename} [{subcategory}].{extension}")}"
        } else if (a.filenameFormat.isNotEmpty()) {
            o += "--filename ${quote(a.filenameFormat)}"
        }
        if (a.limitRate.isNotEmpty()) o += "--limit-rate ${a.limitRate}"
        if (a.proxy.isNotEmpty()) o += "--proxy ${quote(maskProxy(a.proxy))}"
        val videoFilter = if (a.excludeVideo) "extension not in ('mp4','webm','mov','mkv','m4v','avi','flv','wmv')" else ""
        val filter = when {
            a.itemFilter.isNotEmpty() && videoFilter.isNotEmpty() -> "(${a.itemFilter}) and $videoFilter"
            else -> a.itemFilter.ifEmpty { videoFilter }
        }
        if (filter.isNotEmpty()) o += "--filter ${quote(filter)}"
        if (a.archive.isNotEmpty()) o += "--download-archive ${File(a.archive).name}"
        if (a.retries.isNotEmpty()) o += "-o extractor.retries=${a.retries} -o downloader.retries=${a.retries}"
        o += "-o downloader.mtime=false"
        if (a.maxFilesize.isNotEmpty()) o += "-o downloader.filesize-max=${a.maxFilesize}"
        if (a.socketTimeout.isNotEmpty()) o += "-o extractor.timeout=${a.socketTimeout} -o downloader.timeout=${a.socketTimeout}"
        a.sleepInterval.toIntOrNull()?.let { o += "--sleep 2-${maxOf(2, it)}" }
        if (a.restrictFilenames) o += "-o ${quote("path-restrict=^0-9A-Za-z_.\\[\\]-")}"
        if (a.forceIpv4) o += "--force-ipv4"
        if (a.noCheckCertificates) o += "--no-check-certificate"
        if (a.writeInfoFiles) o += "--write-metadata"
        if (a.extraArgs.isNotBlank()) o += maskArgs(a.extraArgs.trim())
        if (a.sharedExtraArgs.isNotBlank()) o += maskArgs(a.sharedExtraArgs.trim())
        return Block("gallery-dl", o, a.url)
    }

    // ── Spotify ─────────────────────────────────────────────────────────────────────────────────
    // Each track is matched on YouTube and downloaded through yt_dlp_wrapper.download.

    fun spotify(a: SpotifyArgs, impersonateAvailable: Boolean = true): Block {
        val notes = buildList {
            add(Note(R.string.command_note_spotify))
            if (a.playlistItems.isNotEmpty()) add(Note(R.string.command_note_tracks, listOf(a.playlistItems)))
        }
        val perTrack = ytDlp(a.perTrack(""), extraNotes = notes, impersonateAvailable = impersonateAvailable)
        val titleNotes = buildList {
            if (a.overrideTitle.isNotEmpty()) add(Note(R.string.command_note_title, listOf(a.overrideTitle)))
            if (a.overrideArtist.isNotEmpty()) add(Note(R.string.command_note_artist, listOf(a.overrideArtist)))
        }
        return perTrack.copy(url = TRACK_URL, notes = perTrack.notes + titleNotes)
    }

    // ── Instaloader ─────────────────────────────────────────────────────────────────────────────
    // instaloader_wrapper drives Instaloader's Python API; where its CLI has the same option it's
    // written as that, the rest are the app's own steps.

    fun instaloader(a: InstaloaderArgs): Block {
        val o = mutableListOf<String>()
        val notes = mutableListOf<Note>()
        if (a.cookies.isNotEmpty()) notes += Note(R.string.command_note_app, listOf("cookies ${File(a.cookies).name}"))
        if (a.itemFilter.isNotEmpty()) notes += Note(R.string.command_note_app, listOf("--filter ${a.itemFilter}"))
        // Items already in it are skipped (_download_post's _read_archive), as with the other engines.
        if (a.archive.isNotEmpty()) notes += Note(R.string.command_note_app, listOf("--download-archive ${File(a.archive).name}"))
        if (!a.writeInfoFiles) o += "--no-metadata-json"
        if (a.socketTimeout.isNotEmpty()) o += "--request-timeout ${a.socketTimeout}"
        o += "--max-connection-attempts ${(a.retries.toIntOrNull() ?: 2) + 1}"
        if (a.proxy.isNotEmpty()) notes += Note(R.string.command_note_app, listOf("--proxy ${maskProxy(a.proxy)}"))
        a.sleepInterval.toIntOrNull()?.let { notes += Note(R.string.command_note_app, listOf("--sleep 2-${maxOf(2, it)}")) }
        if (a.limitRate.isNotEmpty()) notes += Note(R.string.command_note_app, listOf("--limit-rate ${a.limitRate}"))
        if (a.maxFilesize.isNotEmpty()) notes += Note(R.string.command_note_app, listOf("--max-filesize ${a.maxFilesize}"))
        if (a.restrictFilenames) notes += Note(R.string.command_note_app, listOf("--restrict-filenames"))
        if (a.forceIpv4) notes += Note(R.string.command_note_app, listOf("--force-ipv4"))
        if (a.noCheckCertificates) notes += Note(R.string.command_note_app, listOf("--no-check-certificate"))
        if (a.filenameFormat.isNotEmpty()) notes += Note(R.string.command_note_filename, listOf(a.filenameFormat))
        return Block("instaloader", o, a.url, notes)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Shown in place of the link where there's no one link (Settings, a Spotify track's match). */
    const val URL_PLACEHOLDER = "URL"
    private const val TRACK_URL = "https://www.youtube.com/watch?v=…"

    private val AUDIO_SELECTOR_RE = Regex("""\b(bestaudio\*?|ba\*?)(?=[\[+/]|$)""")

    /** yt_dlp_wrapper._with_audio_language: each alternative with a bestaudio asks for the dub,
     * then the whole selector again as the fallback. */
    fun withAudioLanguage(format: String, language: String): String {
        if (language.isEmpty() || format.isEmpty()) return format
        val tagged = format.split("/").filter { AUDIO_SELECTOR_RE.containsMatchIn(it) }
            .map { alt -> AUDIO_SELECTOR_RE.replace(alt) { "${it.groupValues[1]}[language^=$language]" } }
        return if (tagged.isEmpty()) format else (tagged + format).joinToString("/")
    }

    private const val CLIENT_ROTATION = "player_client=default,android,web,ios"

    /** yt_dlp_wrapper._parse_extractor_args' whitespace-separated "IE_KEY:ARGS" blocks, one
     * --extractor-args each (the CLI's one option per extractor). Client rotation is a default
     * merged into the youtube block, the user's own player_client winning — not a line of its own,
     * as the CLI takes a second "youtube:..." as a replacement of the first, not an addition. */
    private fun extractorArgBlocks(raw: String, clientRotation: Boolean): List<String> {
        val blocks = raw.split(Regex("\\s+")).filter { it.substringBefore(':', "").isNotBlank() }.toMutableList()
        if (!clientRotation) return blocks
        fun ie(block: String) = block.substringBefore(':').trim().lowercase()
        fun keys(block: String) = block.substringAfter(':').split(';')
            .filter { '=' in it }.map { it.substringBefore('=').trim().lowercase().replace('-', '_') }
        // The last youtube block is the one the wrapper keeps.
        val youtube = blocks.indexOfLast { ie(it) == "youtube" && keys(it).isNotEmpty() }
        when {
            youtube < 0 -> blocks.add(0, "youtube:$CLIENT_ROTATION")
            "player_client" !in keys(blocks[youtube]) ->
                blocks[youtube] = blocks[youtube].substringBefore(':') + ":$CLIENT_ROTATION;" + blocks[youtube].substringAfter(':')
        }
        return blocks
    }

    /** A number the wrapper reads with int() and skips when 0. */
    private fun positive(value: String) = value.trim().toIntOrNull()?.takeIf { it > 0 }

    private fun categories(csv: String) = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

    private val SECRET_HEADER_RE = Regex("(?i)auth|cookie|token|key|secret|session|pass")

    /** "Name: value" lines as the wrapper reads them, values of secret-looking headers masked.
     * Written "Name:value": the CLI keeps a space after the colon as part of the value, the
     * wrapper strips it. */
    private fun headerLines(raw: String) = raw.lines().mapNotNull { line ->
        val name = line.substringBefore(":", "").trim()
        val value = line.substringAfter(":", "").trim()
        if (name.isEmpty() || value.isEmpty()) null
        else "$name:${if (SECRET_HEADER_RE.containsMatchIn(name)) MASK else value}"
    }

    /** A proxy URL's password ("http://user:pass@host") masked. */
    fun maskProxy(proxy: String) = Regex("""^([a-zA-Z][\w+.-]*://)?([^/@:]+):[^/@]*@""").replace(proxy) { m ->
        "${m.groupValues[1]}${m.groupValues[2]}:$MASK@"
    }

    // "-p" is only a password in lower case: yt-dlp's -P is --paths.
    private val SECRET_FLAG_RE = Regex("""(?i)(--(?:password|ap-password|video-password|twofactor|client-secret)|(?<!\S)(?-i:-p))(\s+|=)("[^"]*"|'[^']*'|\S+)""")
    private const val SECRET_WORDS = "auth|cookie|token|key|secret|session|pass"
    // A quoted header's whole value ("Authorization: Bearer x" — not just "Bearer"), else one word.
    private val SECRET_HEADER_ARG_RE = Regex("""(?i)(--add-header\s+)(?:(["'])([^:"']*(?:$SECRET_WORDS)[^:"']*:\s*)[^"']*\2|([^:"'\s]*(?:$SECRET_WORDS)[^:"'\s]*:)\S+)""")
    // A gallery-dl "-o extractor.x.password=..." / "...refresh-token=..." option.
    private val SECRET_OPTION_RE = Regex("""(?i)((?<!\S)(?:-o|--option)\s*=?\s*["']?[\w.-]*(?:password|token|secret|api-?key|cookie|session)[\w.-]*=)([^"'\s]+)""")

    /** Extra arguments with passwords and secret header/option values masked, word by word as
     * the engines split them (so a "-p" inside a quoted value is left alone), then quoted back.
     * Text the engines can't split (an unclosed quote) is masked by pattern instead. */
    fun maskArgs(args: String): String {
        val words = ShellWords.split(args) ?: return maskArgsByPattern(args)
        val out = mutableListOf<String>()
        var maskNext: ((String) -> String)? = null
        for (word in words) {
            maskNext?.let { mask -> out += mask(word); maskNext = null; continue }
            val flag = word.substringBefore('=')
            val attached = if ('=' in word && word.startsWith("--")) word.substringAfter('=') else null
            out += when {
                flag in SECRET_FLAGS -> attached?.let { "$flag=$MASK" } ?: word.also { maskNext = { MASK } }
                // "-pSECRET": yt-dlp and gallery-dl both take a short option's value attached.
                word.startsWith("-p") && word.length > 2 && !word.startsWith("--") -> "-p$MASK"
                flag == "--add-header" -> attached?.let { "$flag=${maskHeader(it)}" } ?: word.also { maskNext = ::maskHeader }
                flag == "-o" || flag == "--option" -> attached?.let { "$flag=${maskOption(it)}" } ?: word.also { maskNext = ::maskOption }
                word.startsWith("-o") && !word.startsWith("--") && '=' in word -> "-o" + maskOption(word.drop(2))
                flag == "--extractor-args" -> attached?.let { "$flag=${maskExtractorArgs(it)}" } ?: word.also { maskNext = ::maskExtractorArgs }
                else -> maskProxy(word)
            }
        }
        // "***" itself needs no quoting (a shell would glob it, but it's only shown).
        return out.joinToString(" ") { quote(it.replace(MASK, "\u0000")).replace("\u0000", MASK) }
    }

    private val SECRET_FLAGS = setOf("--password", "-p", "--ap-password", "--video-password", "--twofactor", "--client-secret")
    private val SECRET_KEY_RE = Regex("(?i)$SECRET_WORDS|api-?key|po_token")

    /** "Name: value" with a secret-looking name: the whole value masked. */
    private fun maskHeader(header: String): String {
        val name = header.substringBefore(':', "")
        val separator = ":" + header.substringAfter(':').takeWhile { it == ' ' }
        return if (name.isNotEmpty() && SECRET_KEY_RE.containsMatchIn(name)) "$name$separator$MASK" else header
    }

    /** A gallery-dl "key=value" option whose key names a secret ("extractor.x.password"). */
    private fun maskOption(option: String): String {
        val key = option.substringBefore('=', "")
        return if (key.isNotEmpty() && SECRET_KEY_RE.containsMatchIn(key)) "$key=$MASK" else option
    }

    /** "youtube:po_token=...;player_client=web": each secret-looking key's value masked. */
    private fun maskExtractorArgs(value: String): String {
        val ie = value.substringBefore(':', "")
        if (ie.isEmpty()) return value
        return ie + ":" + value.substringAfter(':').split(';').joinToString(";") { maskOption(it) }
    }

    private fun maskArgsByPattern(args: String): String {
        val flags = SECRET_FLAG_RE.replace(args) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        val headers = SECRET_HEADER_ARG_RE.replace(flags) { m ->
            val (_, flag, q, quotedName, bareName) = m.groupValues
            if (q.isNotEmpty()) "$flag$q$quotedName$MASK$q" else "$flag$bareName$MASK"
        }
        return SECRET_OPTION_RE.replace(headers) { "${it.groupValues[1]}$MASK" }
    }

    private const val MASK = "***"

    /** Double-quoted when it has anything a shell would split or expand. */
    fun quote(value: String) =
        if (value.isNotEmpty() && value.none { it.isWhitespace() || it in "\"'\\\$`*?[]{}()<>|&;!#~" }) value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`") + "\""

    private fun host(url: String) = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringAfterLast('@').lowercase()
    private fun isYoutube(url: String) = host(url).let { it == "youtu.be" || it.endsWith("youtube.com") || it.endsWith("youtube-nocookie.com") }
    private fun isReddit(url: String) = host(url).let { it == "redd.it" || it.endsWith("reddit.com") }
}
