package com.comfort.app.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** The Kotlin argument lists against the wrapper scripts they're read by: each wrapper's
 * `__main__` block is parsed for the `name=...a[N]...` kwargs of its download() call, and every
 * Kotlin field must land at the index whose kwarg it maps to — so an argument added, removed or
 * moved on one side only fails here instead of shifting every later argument by one. */
class EngineArgsTest {

    // ── Reading the wrappers ────────────────────────────────────────────────────────────────────

    private fun script(name: String): String {
        // Gradle runs unit tests in the module directory (app/); an IDE may use the repo root.
        val file = listOf("src/main/assets/python_packages", "app/src/main/assets/python_packages")
            .map { File(it, name) }.firstOrNull { it.isFile }
            ?: error("$name not found from ${File("").absolutePath}")
        return file.readText().replace("\r\n", "\n")
    }

    /** The text of the call starting at [start] (`download(` / `yt_dlp_wrapper.download(`) up to
     * its closing parenthesis, comment lines dropped. */
    private fun callBody(text: String, start: Regex): String {
        val source = text.lines().filterNot { it.trim().startsWith("#") }.joinToString("\n")
        val begin = start.find(source)?.range?.last ?: error("no ${start.pattern}")
        var depth = 1
        var i = begin + 1
        while (depth > 0) {
            when (source[i]) { '(' -> depth++; ')' -> depth-- }
            i++
        }
        return source.substring(begin + 1, i - 1)
    }

    private fun mainDownloadCall(script: String): String {
        val source = script(script)
        val main = source.substring(source.indexOf("if __name__ == \"__main__\":").also { assertTrue(it >= 0) })
        return callBody(main, Regex("""\b_?status = download\("""))
    }

    private val KWARG_INDEX_RE = Regex("""(\w+)=[^,=]*?\b(?:a|_rest)\[(\d+)]""")
    private val INDEX_RE = Regex("""\b(?:a|_rest)\[(\d+)]""")

    /** kwarg name → the argv index (after "download") the wrapper reads it from. */
    private fun wrapperPositions(script: String): Map<String, Int> {
        val call = mainDownloadCall(script)
        val positions = KWARG_INDEX_RE.findAll(call).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        // Every index read belongs to exactly one kwarg (nothing read that the parse missed).
        assertEquals("$script: indexes read", INDEX_RE.findAll(call).map { it.groupValues[1].toInt() }.toSortedSet(), positions.values.toSortedSet())
        assertEquals("$script: one kwarg per index", positions.size, positions.values.toSet().size)
        return positions
    }

    // ── Reading the Kotlin side ─────────────────────────────────────────────────────────────────

    private fun fieldsOf(cls: Class<*>): List<Field> =
        cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }

    /** An instance of data class [cls] with each field from [value]. */
    private fun <T> build(cls: Class<T>, value: (Field) -> Any): T {
        val fields = fieldsOf(cls)
        val ctor = cls.declaredConstructors.single { c -> c.parameterTypes.toList() == fields.map { it.type } }
        return cls.cast(ctor.newInstance(*fields.map(value).toTypedArray()))
    }

    private fun off(f: Field): Any = if (f.type == java.lang.Boolean.TYPE) false else ""
    private fun sentinel(f: Field): Any = if (f.type == java.lang.Boolean.TYPE) true else "<${f.name}>"

    @Suppress("UNCHECKED_CAST")
    private fun toArgs(obj: Any) = obj.javaClass.getMethod("toArgs").invoke(obj) as List<String>

    /** Kotlin field → its index in toArgs(): each field switched on alone, the one slot that changes. */
    private fun kotlinPositions(cls: Class<*>): Map<String, Int> {
        val base = toArgs(build(cls, ::off))
        assertEquals("download", base[0])
        val positions = fieldsOf(cls).associate { field ->
            val args = toArgs(build(cls) { if (it == field) sentinel(it) else off(it) })
            val changed = args.indices.filter { args[it] != base[it] }
            assertEquals("${cls.simpleName}.${field.name} should fill exactly one argument", 1, changed.size)
            field.name to changed.single()
        }
        // Every argument after "download" is some field (no constant or doubled slot).
        assertEquals("${cls.simpleName}: arguments without a field", (1 until base.size).toSet(), positions.values.toSet())
        assertEquals("${cls.simpleName}: one field per argument", positions.size, positions.values.toSet().size)
        return positions
    }

    // ── Kotlin name → wrapper kwarg ─────────────────────────────────────────────────────────────

    private fun snake(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    private val COMMON = mapOf(
        "cookies" to "cookies_path", "archive" to "archive_path", "jsRuntime" to "js_runtime_path",
        "ffmpeg" to "ffmpeg_path", "aria2" to "aria2_path", "proxy" to "proxy_url",
        "sleepInterval" to "sleep_interval_seconds", "socketTimeout" to "socket_timeout_seconds",
    )
    private val YT_DLP_NAMES = COMMON + mapOf(
        "formatOverride" to "format_selector", "formatSort" to "format_sort_extra", "infoCache" to "info_json_path",
        "sponsorBlockRemove" to "sponsorblock_remove", "sponsorBlockMark" to "sponsorblock_mark",
    )
    private val INSTALOADER_NAMES = COMMON + mapOf("infoCache" to "info_cache_path")

    private fun pyName(kotlin: String, table: Map<String, String>) = table[kotlin] ?: snake(kotlin)

    private fun assertMatches(cls: Class<*>, script: String, names: Map<String, String>) {
        val python = wrapperPositions(script)
        // toArgs()[0] is "download", the wrapper's a[0] is toArgs()[1].
        val kotlin = kotlinPositions(cls).entries.associate { (name, index) -> pyName(name, names) to index - 1 }
        assertEquals("$script's download kwargs by argv index vs ${cls.simpleName}.toArgs()", python.toSortedMap(), kotlin.toSortedMap())
        assertEquals("${cls.simpleName}.toArgs() size", python.values.max() + 2, toArgs(build(cls, ::off)).size)
    }

    @Test fun ytDlpArgsMatchTheWrapper() = assertMatches(YtDlpArgs::class.java, "yt_dlp_wrapper.py", YT_DLP_NAMES)
    @Test fun galleryDlArgsMatchTheWrapper() = assertMatches(GalleryDlArgs::class.java, "gallery_dl_wrapper.py", COMMON)
    @Test fun spotifyArgsMatchTheWrapper() = assertMatches(SpotifyArgs::class.java, "spotify_wrapper.py", COMMON)
    @Test fun instaloaderArgsMatchTheWrapper() = assertMatches(InstaloaderArgs::class.java, "instaloader_wrapper.py", INSTALOADER_NAMES)

    @Test
    fun instaloaderPadsToItsArgumentCount() {
        // The wrapper pads _rest to a fixed length; it must be the number of arguments sent.
        val pad = Regex("""_rest \+= \[""] \* \((\d+) - len\(_rest\)\)""").find(script("instaloader_wrapper.py"))!!.groupValues[1].toInt()
        assertEquals(pad, toArgs(InstaloaderArgs(url = "u")).size - 1)
    }

    // ── Spotify's per-track yt-dlp call ─────────────────────────────────────────────────────────

    /** download()'s literal True/False/None defaults by kwarg. */
    private fun signatureDefaults(source: String): Map<String, String> =
        Regex("""(\w+)=(True|False|None)\b""").findAll(callBody(source, Regex("""(?m)^def download\(""")))
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun perTrackIsWhatSpotifyHandsYtDlp() {
        val call = callBody(script("spotify_wrapper.py"), Regex("""status = yt_dlp_wrapper\.download\("""))
        val passed = Regex("""(\w+)=([^,\s)]+)""").findAll(call).associate { it.groupValues[1] to it.groupValues[2] }
        val ytDefaults = signatureDefaults(script("yt_dlp_wrapper.py"))

        val spotify = build(SpotifyArgs::class.java, ::sentinel)
        val track = spotify.perTrack("<matched>")
        val spotifyFields = fieldsOf(SpotifyArgs::class.java).associateBy { pyName(it.name, COMMON) }
        val ytFields = fieldsOf(YtDlpArgs::class.java).associateBy { pyName(it.name, YT_DLP_NAMES) }

        // Not arguments: the progress callback, and "{category}" in a custom filename format.
        val notArgs = setOf("callback", "site")
        passed.forEach { (kwarg, value) ->
            if (kwarg in notArgs) return@forEach
            val field = ytFields[kwarg] ?: error("spotify passes $kwarg, which YtDlpArgs has no field for")
            val actual = field.get(track)
            when {
                kwarg == "url" -> assertEquals("<matched>", actual)
                value == "True" -> assertEquals("perTrack.${field.name}", true, actual)
                value == "False" -> assertEquals("perTrack.${field.name}", false, actual)
                else -> {
                    val from = spotifyFields[value] ?: error("spotify passes $kwarg=$value, not one of its own arguments")
                    assertEquals("perTrack.${field.name} should be SpotifyArgs.${from.name}", from.get(spotify), actual)
                }
            }
        }
        // Everything spotify doesn't pass is download()'s default.
        ytFields.filterKeys { it !in passed }.forEach { (kwarg, field) ->
            val expected: Any = when (ytDefaults[kwarg]) {
                "True" -> true
                "False" -> false
                "None" -> ""
                else -> error("yt_dlp_wrapper.download has no literal default for $kwarg")
            }
            assertEquals("perTrack.${field.name} (not passed by spotify: download()'s default)", expected, field.get(track))
        }
    }
}
