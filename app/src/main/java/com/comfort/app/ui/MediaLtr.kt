package com.comfort.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** [content] laid out left to right whatever the language: media playback controls and timelines
 * (previous/play/next, seek bars, the trim slider) aren't mirrored in a right-to-left language, as
 * Material's bidirectionality guidance has it — they follow time, not the line of text, and ⏮/⏭
 * point the same way everywhere. Mirrored, Compose put ⏭ on the left still pointing right. Only
 * around the controls themselves: a title next to them keeps the language's own direction. */
@Composable
fun MediaLtr(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
