package com.comfort.app.ui.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Whether the player is on a light background: Music settings › Cover background off with the
 * app in light mode (white then; black in dark mode, and under the cover backdrop always). */
internal val LocalPlayerLight = staticCompositionLocalOf { false }

/** The player's text and icons: white on its dark background, black on the light one. */
internal val Ink: Color
    @Composable @ReadOnlyComposable get() = if (LocalPlayerLight.current) Color.Black else Color.White

/** The background's own colour, for what sits on an [Ink]-coloured control (the play button's
 * icon) and for the backdrop itself. */
internal val Paper: Color
    @Composable @ReadOnlyComposable get() = if (LocalPlayerLight.current) Color.White else Color.Black
