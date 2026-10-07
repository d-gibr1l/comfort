package com.comfort.app.util

/** Tidies the names downloads were saved under before the poster/caption naming: the same name
 * the default template would give today, worked out from the old name alone: a tidier
 * "<poster> - <caption>", and a long "[id]" shortened to the code new downloads get. */
object OldNames {
    // A link turned into a filename: "https_-_t.co_cFUYmh8Vc6", "https_t.co_x", "https://t.co/x".
    private val LINK = Regex("""https?(?:_-_|_|://)\S*?t\.co[_/][A-Za-z0-9]+|https?://\S+""")
    // "<stem> [<id>]<rest>" — rest is the extension, plus " (1)" or ".info.json"-style suffixes.
    private val SHAPE = Regex("""^(.*?) \[([^\[\]]+)](.*)$""")
    private const val SEPARATORS = " _-–—|:·"

    /** The tidied name, or null when [name] needs no change. [poster] is the poster to use when
     * the old name only has the site's name ("twitter - twitter"), from the download's link. */
    fun tidy(name: String, poster: String? = null): String? {
        val match = SHAPE.find(name) ?: return null
        val (stem, id, rest) = match.destructured
        val tidied = tidyStem(stem, poster) + " [${shortId(id)}]$rest"
        return tidied.takeIf { it != name }
    }

    /** The same tidying for a Library title ("Shitpost 2048 - https://t.co/…", "twitter - twitter"),
     * or null when it needs no change. */
    fun tidyTitle(title: String, poster: String? = null): String? =
        tidyStem(title, poster).takeIf { it.isNotBlank() && it != title }

    private fun tidyStem(stem: String, poster: String?): String {
        val parts = stem.split(" - ", limit = 2)
        var who = parts[0].trim()
        var what = parts.getOrNull(1).orEmpty()
        // yt-dlp's X titles start with the poster again: "Poster - Poster_-_caption".
        for (prefix in listOf("${who}_-_", "$who - ", who)) {
            if (what.startsWith(prefix, ignoreCase = true)) { what = what.substring(prefix.length); break }
        }
        what = LINK.replace(what, "").trim(*SEPARATORS.toCharArray())
        if (what.equals(who, ignoreCase = true) || what.none { it.isLetterOrDigit() }) what = ""
        // gallery-dl named X files after the site: "twitter - twitter".
        if (who.lowercase() in SITE_ONLY && !poster.isNullOrBlank()) {
            if (what.lowercase() in SITE_ONLY) what = ""
            who = poster
        }
        return if (what.isEmpty()) who else "$who - $what"
    }

    private val SITE_ONLY = setOf("twitter", "x")

    /** The same code the wrappers' short_id() gives: [id] as is up to 11 characters, otherwise
     * 6 base-36 characters from its SHA-1. */
    fun shortId(id: String): String {
        if (id.length <= 11) return id
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(id.toByteArray(Charsets.UTF_8))
        val hex = sha.joinToString("") { "%02x".format(it) }.take(12)
        return java.lang.Long.parseLong(hex, 16).toString(36).take(6)
    }

    /** The poster's handle in an X post link, or null. */
    fun xPoster(url: String?): String? =
        url?.let { Regex("""(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})/status/""").find(it)?.groupValues?.get(1) }
            ?.takeIf { it.lowercase() != "i" }
}
