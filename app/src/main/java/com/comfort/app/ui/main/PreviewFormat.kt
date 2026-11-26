@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

// Formatting for the preview sheet: sizes, durations, timestamps, song titles and artists.
package com.comfort.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

internal fun parseTimestampToMs(value: String): Long? {
    val v = value.trim()
    if (v.isEmpty()) return null
    return try {
        // toFloatOrNull() returning null (a genuinely non-numeric part, e.g. "hh:mm" typed
        // literally) must fail the whole parse — silently coercing it to 0f used to make any
        // garbage text parse "successfully" as 00:00.000 and get applied to the segment as if it
        // were a real, deliberate edit.
        val parts = v.split(":").map { it.toFloatOrNull() ?: return null }
        var seconds = 0f
        for (part in parts) {
            seconds = seconds * 60 + part
        }
        (seconds * 1000).toLong()
    } catch (e: Exception) {
        null
    }
}

internal fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}

/** Bytes as the compact size the preview card shows ("10mb"). */
internal fun formatFilesize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    return when {
        b >= 1024L * 1024L * 1024L -> "%.1fgb".format(b / (1024.0 * 1024.0 * 1024.0))
        b >= 1024L * 1024L -> "${b / (1024L * 1024L)}mb"
        b >= 1024L -> "${b / 1024L}kb"
        else -> "${b}b"
    }
}

/** "3:42" / "1:02:11" — no existing formatter has this shape: formatTimestamp (Trim screen) is
 * "mm:ss.mmm", and formatFilesize above is the naming/placement precedent for this one. */
internal fun formatDurationShort(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "Artist — Album" (or just "Artist" when the album isn't known) — shared between
 * DownloadsHistoryScreen's Library card subtitle and this sheet's own song preview card so the
 * two formats can't drift apart. Null (not a fallback string) when there's no artist at all;
 * callers own their own fallback (the Library card's domain, this sheet's own "Untitled"-style
 * treatment). */
internal fun audioSubtitle(artist: String?, album: String?): String? =
    artist?.let { a -> album?.let { "$a — $it" } ?: a }

/** Starting value for the preview sheet's own editable title field — strips common YouTube-upload
 * clutter tags so the field opens already tidy rather than a raw scrape; the user can still edit
 * (or restore) anything from there. Deliberately conservative: only removes whole bracketed/
 * parenthesized segments matching known clutter keywords, never touches free text outside them. */
internal fun cleanTrackTitle(raw: String): String =
    raw.replace(CLUTTER_TAG_RE, "").replace(EXTRA_SPACES_RE, " ").trim()

/** Starting value for the preview sheet's own editable artist field — strips the " - Topic" suffix
 * YouTube's auto-generated artist channels carry (the preview's own listing pass doesn't run
 * through MetadataParserPP the way a real download does — see yt_dlp_wrapper.py's own
 * progress_hook — so this same cleanup needs its own copy here for the editable field's starting
 * value). */
internal fun cleanArtistName(raw: String): String = raw.replace(TOPIC_SUFFIX_RE, "").trim()
