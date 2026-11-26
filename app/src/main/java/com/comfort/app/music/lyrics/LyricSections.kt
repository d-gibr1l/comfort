// Adapted from Mixify (https://github.com/MixifyTeam/Mixify, GPL-3.0), as Comfort is.
package com.comfort.app.music.lyrics

/**
 * Whether a plain lyric line is one of Genius's section markers — `[Chorus]`,
 * `[Verse 2: Artist]` — rather than words to sing.
 */
fun isGeniusSectionHeader(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length in 3..60
}
