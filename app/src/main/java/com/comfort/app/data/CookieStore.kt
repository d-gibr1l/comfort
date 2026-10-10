package com.comfort.app.data

import android.content.Context
import java.io.File

// The saved cookies.txt: its Netscape format (parsing, grouping by site, merging a browser login
// in) and the one place it's read and written. Moved out of the Cookies settings page so the format
// rules are testable and every writer (the page, the login browser) goes through the same code.

/** Reads and writes the cookies file gallery-dl and yt-dlp are handed. The file is the source of
 * truth; KEY_COOKIES in SharedPreferences is a same-content mirror written alongside it. */
object CookieStore {
    /** gallery-dl/yt-dlp's cookie-jar parser (Python's http.cookiejar) requires this as the file's
     * literal first line, or rejects it as "does not look like a Netscape format cookies file" and
     * every download fails — parseCookiesFile() drops it, so every rewrite adds it back. */
    const val HEADER = "# Netscape HTTP Cookie File"

    fun file(context: Context) = File(context.filesDir, "cookies.txt")

    fun read(context: Context): String = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun write(context: Context, content: String) {
        context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(GalleryDlPreferences.KEY_COOKIES, content).apply()
        file(context).writeText(content)
    }

    /** [cookies] as a whole file, header first. */
    fun render(cookies: List<ParsedCookie>): String = (listOf(HEADER) + cookies.map { it.rawLine }).joinToString("\n")

    /** A pasted cookies.txt merged into [existing]: only the registrable domains the paste touches
     * are replaced, every other saved site is kept as it was. Works on the parsed rawLine form, not
     * mergeNetscapeCookies (which rebuilds lines with a fixed expiry and TRUE/TRUE flags), so a
     * pasted file's own expiry/secure/subdomain flags survive. Null when nothing in [pasted]
     * parsed — a paste in the wrong format (a "name=value; ..." header string, a space-aligned
     * export that lost its tabs past repair) is refused rather than silently saved as a no-op. */
    fun mergePasted(existing: String, pasted: String): String? {
        val pastedParsed = parseCookiesFile(pasted)
        if (pastedParsed.isEmpty()) return null
        val touchedDomains = pastedParsed.map { it.domain.removePrefix(".") }.toSet()
        val keptExisting = parseCookiesFile(existing).filterNot { it.domain.removePrefix(".") in touchedDomains }
        return render(keptExisting + pastedParsed)
    }

    /** [existing] with only the edited cookies' value fields changed — other sites, flags and
     * expiry are written back exactly as they were. */
    fun withValues(existing: String, edits: Map<ParsedCookie, String>): String =
        render(parseCookiesFile(existing).map { cookie -> edits[cookie]?.let { cookie.withValue(it) } ?: cookie })

    fun without(existing: String, remove: Set<ParsedCookie>): String =
        render(parseCookiesFile(existing).filter { it !in remove })

    /** What the login browser's "Extract cookies" saves: the cookies CookieManager holds for [url],
     * merged in over that host's previous lines. Returns the new file, or null when there was
     * nothing to save (no host, no cookies). */
    fun saveFromBrowser(context: Context, url: String, cookieHeader: String?): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        if (host == null || cookieHeader.isNullOrBlank()) return null
        val existing = GalleryDlPreferences.getCookies(context)
        return mergeNetscapeCookies(existing, host, cookieHeader).also { write(context, it) }
    }
}

/** One row of a Netscape-format cookies.txt (the format gallery-dl/yt-dlp's --cookies flag reads —
 * see mergeNetscapeCookies' own doc comment for the field layout). [rawLine] is kept verbatim
 * (rather than reconstructed from the parsed fields) so deleting a cookie can just drop its exact
 * original line instead of risking a lossy round-trip through re-serialization. */
data class ParsedCookie(
    val domain: String,
    val name: String,
    val expiryEpochSeconds: Long,
    val rawLine: String,
) {
    /** The value field — the 7th tab-separated field. Splitting the whole raw line (a leading
     * "#HttpOnly_" stays attached to the first field) with limit = 7 keeps a value that itself
     * contains tabs intact. */
    val value: String get() = rawLine.split("\t", limit = 7).getOrElse(6) { "" }

    /** Same line with only the value replaced. Tabs and line breaks are stripped from the new value
     * since either would corrupt the one-cookie-per-line, tab-separated format. */
    fun withValue(newValue: String): ParsedCookie {
        val fields = rawLine.split("\t", limit = 7).toMutableList()
        if (fields.size < 7) return this
        fields[6] = newValue.replace(Regex("[\\t\\r\\n]"), "")
        return copy(rawLine = fields.joinToString("\t"))
    }
}

/** Real comment lines start with "#" and nothing else meaningful follows on that line; a
 * "#HttpOnly_"-prefixed line (a real convention plenty of cookies.txt exports — Chrome's own
 * cookie-export extensions among them — actually use) is NOT a comment despite the leading "#": the
 * rest of the line past that exact prefix is a genuine tab-separated cookie row, just one flagged
 * httpOnly. Reproduced live in this app's own "paste raw cookies" flow: without stripping that
 * prefix first, every httpOnly cookie in a real exported file silently vanished from the parsed
 * list, showing as if the file were mostly empty.
 *
 * Falls back to splitting on any run of whitespace (not just a literal tab) when a line doesn't
 * split into 7 real tab-separated fields — reproduced live with a genuine Cookie-Editor export
 * whose tabs had been silently collapsed to plain spaces somewhere in the copy/paste chain (a
 * viewer or app re-rendering the text, not something gallery-dl's own export ever did). Visually
 * indistinguishable from a real tab-separated file, and previously failed this parser entirely —
 * `limit = 7` keeps the 7th field (the cookie's value) intact as everything remaining, in case it
 * legitimately contains its own internal whitespace, rather than truncating it. The corresponding
 * ParsedCookie.rawLine is rebuilt with real tabs in this fallback branch — a big difference from
 * gallery-dl/yt-dlp's own `--cookies` parser (Python's http.cookiejar), which is strict and only
 * ever splits on literal tabs: persisting the original, space-only line as-is would have let this
 * *display* correctly in the app's own cookie list while still silently failing to actually
 * authenticate a single real download — worse than never fixing it, since it would look fixed. */
fun parseCookiesFile(content: String): List<ParsedCookie> {
    return content.lineSequence().mapNotNull { rawLine ->
        val trimmed = rawLine.trimEnd('\r')
        if (trimmed.isBlank()) return@mapNotNull null
        val hadHttpOnlyPrefix = trimmed.startsWith("#HttpOnly_")
        val dataLine = trimmed.removePrefix("#HttpOnly_")
        if (dataLine.startsWith("#")) return@mapNotNull null
        var fields = dataLine.split("\t")
        var normalizedLine = rawLine
        if (fields.size < 7) {
            val whitespaceFields = dataLine.trim().split(Regex("\\s+"), limit = 7)
            if (whitespaceFields.size == 7) {
                fields = whitespaceFields
                normalizedLine = (if (hadHttpOnlyPrefix) "#HttpOnly_" else "") + whitespaceFields.joinToString("\t")
            }
        }
        if (fields.size < 7) return@mapNotNull null
        ParsedCookie(
            domain = fields[0],
            expiryEpochSeconds = fields[4].toLongOrNull() ?: 0L,
            name = fields[5],
            rawLine = normalizedLine,
        )
    }.toList()
}

/** One "site" worth of cookies for the grouped summary row — [cookies] keeps every individual
 * [ParsedCookie] that belongs to it (needed to actually delete them, and to compute
 * [soonestExpiryEpochSeconds]), while the row itself only ever shows [label] and a count. */
data class SiteCookies(
    val label: String,
    // The raw registrable domain (e.g. "reddit.com"), distinct from [label] ("Reddit") — used as
    // the stable key for the per-site "use these cookies" toggle (GalleryDlPreferences' own
    // disabled-domains set), since a human-readable label is derived/cosmetic and shouldn't be
    // relied on as a persisted identity.
    val rootDomain: String,
    val cookies: List<ParsedCookie>,
) {
    // The soonest of the group's own expiries is what actually determines when this login first
    // needs refreshing — showing the *latest* one instead would understate how soon a session
    // might already be partly stale (a real login session's individual cookies don't all share one
    // expiry; some non-essential ones (display prefs, A/B-test bucketing) are often set to expire
    // far sooner than the actual session token itself, without meaning the session as a whole
    // isn't still good).
    val soonestExpiryEpochSeconds: Long = cookies
        .map { it.expiryEpochSeconds }
        .filter { it > 0L }
        .minOrNull() ?: 0L
}

/** Groups by *registrable* domain (the last two dot-separated labels — "www.reddit.com" and
 * ".reddit.com" both collapse to the same "reddit.com" group, same simplification VideoSiteRouter
 * itself already makes) rather than by the exact domain string each cookie's own line happens to
 * carry, since a single real login often spans a mix of exact-domain and subdomain-inclusive
 * ("TRUE" in the Netscape format's own includeSubdomains column) cookies for what's really one
 * site as far as a user setting up a login is concerned. Sorted by site label for a stable,
 * predictable display order rather than whatever order cookies.txt's own lines happen to be in. */
fun groupCookiesBySite(cookies: List<ParsedCookie>): List<SiteCookies> {
    return cookies
        .groupBy { cookie ->
            val bare = cookie.domain.removePrefix(".")
            val labelParts = bare.split(".")
            if (labelParts.size <= 2) bare else labelParts.takeLast(2).joinToString(".")
        }
        .map { (rootDomain, group) -> SiteCookies(label = VideoSiteRouter.siteName("https://$rootDomain"), rootDomain = rootDomain, cookies = group) }
        .sortedBy { it.label.lowercase() }
}

/** CookieManager.getCookie() returns an HTTP-header-style string ("name1=value1; name2=value2"),
 * not the Netscape cookies.txt format gallery-dl/yt-dlp's --cookies flag actually parses (tab-
 * separated: domain, includeSubdomains, path, secure, expiry, name, value). Converts and merges
 * into [existing], dropping any prior lines for [host] first so re-extracting replaces rather than
 * duplicates/conflicts with them. */
fun mergeNetscapeCookies(existing: String, host: String, cookieHeader: String): String {
    val domain = if (host.startsWith(".")) host else ".$host"
    val bareDomain = domain.removePrefix(".")
    // Five years out — CookieManager doesn't expose each cookie's real expiry, and a long-lived
    // session cookie being treated as farther in the future than it really is just means it stops
    // working when the site itself expires it, same as any other stale-cookie failure.
    val expiry = (System.currentTimeMillis() / 1000L) + 60L * 60 * 24 * 365 * 5
    val newLines = cookieHeader.split(";").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val name = pair.substring(0, idx).trim()
        val value = pair.substring(idx + 1).trim()
        if (name.isEmpty()) return@mapNotNull null
        "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
    }
    val keptExisting = existing.lineSequence()
        .filter { line ->
            val trimmed = line.trimEnd('\r')
            if (trimmed.isBlank()) return@filter true
            val isComment = trimmed.startsWith("#") && !trimmed.startsWith("#HttpOnly_")
            if (isComment) return@filter true
            val dataLine = trimmed.removePrefix("#HttpOnly_")
            val lineDomain = dataLine.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
            !(lineDomain == domain || lineDomain == bareDomain)
        }
        .toList()
    val header = if (keptExisting.any { it.startsWith("# Netscape") }) emptyList() else listOf("# Netscape HTTP Cookie File")
    return (header + keptExisting + newLines).joinToString("\n").trim()
}
