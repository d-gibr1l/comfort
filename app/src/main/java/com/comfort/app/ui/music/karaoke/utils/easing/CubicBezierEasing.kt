// Copied from accompanist-lyrics-ui 1.0.19 (https://github.com/6xingyv/accompanist-lyrics-ui),
// Apache License 2.0, to fix its scrolling inside Comfort (see KaraokeLyricsView's follower).
package com.comfort.app.ui.music.karaoke.utils.easing

import androidx.compose.animation.core.CubicBezierEasing

val EasingOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)