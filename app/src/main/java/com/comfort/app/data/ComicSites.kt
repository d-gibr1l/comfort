package com.comfort.app.data

import android.content.Context
import java.net.URI

/**
 * Manga and comic sites whose chapters are saved as one .cbz file (a zip of the pages, in order)
 * instead of loose pictures in the gallery (Settings › Processing › Comics). A built-in list of
 * known sites, with the user's additions and removals on top, as the Sites lists keep theirs.
 */
object ComicSites {
    const val MIME = "application/vnd.comicbook+zip"

    /** Known manga / comic / webtoon sites gallery-dl reads chapters from. */
    val DEFAULTS: Set<String> = setOf(
        "mangadex.org", "mangafire.to", "mangapark.net", "mangapark.io", "bato.to", "comick.io", "comick.cc",
        "mangakakalot.com", "manganato.com", "chapmanganato.to", "natomanga.com", "mangasee123.com", "manga4life.com",
        "mangareader.to", "mangahere.cc", "fanfox.net", "mangatown.com", "mangasee.com", "mangaread.org",
        "webtoons.com", "tapas.io", "comic.naver.com", "readcomiconline.li", "dynasty-scans.com", "tcbscans.com",
        "mangaplus.shueisha.co.jp", "weebcentral.com", "kagane.org", "hiperdex.com", "toonily.com",
    )

    private const val KEY_ENABLED = "cbz_enabled"
    private const val KEY_ADDED = "cbz_sites_added"
    private const val KEY_REMOVED = "cbz_sites_removed"

    private fun prefs(context: Context) = context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()

    /** The sites, sorted: the built-in ones not removed, and the ones added. */
    fun sites(context: Context): List<String> {
        val p = prefs(context)
        val added = p.getStringSet(KEY_ADDED, emptySet()).orEmpty()
        val removed = p.getStringSet(KEY_REMOVED, emptySet()).orEmpty()
        return ((DEFAULTS - removed) + added).sorted()
    }

    fun add(context: Context, host: String) {
        val p = prefs(context)
        val added = p.getStringSet(KEY_ADDED, emptySet()).orEmpty()
        val removed = p.getStringSet(KEY_REMOVED, emptySet()).orEmpty()
        p.edit().putStringSet(KEY_ADDED, added + host).putStringSet(KEY_REMOVED, removed - host).apply()
    }

    fun remove(context: Context, host: String) {
        val p = prefs(context)
        val added = p.getStringSet(KEY_ADDED, emptySet()).orEmpty()
        val removed = p.getStringSet(KEY_REMOVED, emptySet()).orEmpty()
        p.edit().putStringSet(KEY_ADDED, added - host)
            .putStringSet(KEY_REMOVED, if (host in DEFAULTS) removed + host else removed).apply()
    }

    fun resetToDefaults(context: Context) {
        prefs(context).edit().remove(KEY_ADDED).remove(KEY_REMOVED).apply()
    }

    /** Whether [url]'s chapters are packed as .cbz: the setting's on and its site (or a parent
     * domain of it) is on the list. */
    fun packs(context: Context, url: String): Boolean {
        if (!isEnabled(context)) return false
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        return sites(context).any { host == it || host.endsWith(".$it") }
    }

    /** Whether a saved file is a comic archive (by its name or type). */
    fun isComicFile(name: String?, mime: String?): Boolean =
        mime == MIME || mime == "application/x-cbz" || name?.lowercase()?.endsWith(".cbz") == true
}
