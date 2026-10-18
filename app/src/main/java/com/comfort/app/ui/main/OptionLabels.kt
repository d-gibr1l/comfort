package com.comfort.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import com.comfort.app.data.AudioFormat
import com.comfort.app.data.ShareMode
import com.comfort.app.data.VideoQuality

// The settings enums' names as shown, in the user's language. Their own `label` is the English
// name, kept for what isn't a word to translate ("1080p", "MP3"): only the ones that are words go
// through string resources here.

val VideoQuality.displayLabel: String
    @Composable get() = when (this) {
        VideoQuality.BEST -> stringResource(R.string.quality_best_available)
        VideoQuality.AUDIO_ONLY -> stringResource(R.string.quality_audio_only)
        else -> label
    }

val AudioFormat.displayLabel: String
    @Composable get() = when (this) {
        AudioFormat.ORIGINAL -> stringResource(R.string.audio_format_original)
        else -> label
    }

val ShareMode.displayLabel: String
    @Composable get() = stringResource(
        when (this) {
            ShareMode.CONFIGURE -> R.string.share_mode_configure
            ShareMode.INSTANT -> R.string.share_mode_instant
            ShareMode.ALWAYS_ASK -> R.string.share_mode_always_ask
        }
    )
