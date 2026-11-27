package com.comfort.app.ui.music

import androidx.compose.animation.animateColorAsState
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.music.Lyrics
import com.comfort.app.ui.main.pinchToZoomText
import com.comfort.app.ui.main.rememberTextZoom

/**
 * The song's lyrics, in place of the cover. Synced ones are Mixify's panel (LyricsPanel): they
 * follow the song, the line being sung lit word by word; a tap on a line plays from there.
 * Plain lyrics are just text to scroll through.
 */
@Composable
internal fun LyricsView(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val lines = lyrics.lines
    val zoom = rememberTextZoom("music_player", "lyrics_zoom", min = 0.7f, max = 1.8f)
    if (lines.isNullOrEmpty()) {
        val base = MaterialTheme.typography.titleLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily)
        Box(modifier.pinchToZoomText(zoom).verticalScroll(rememberScrollState())) {
            Text(
                lyrics.plain.orEmpty(),
                style = base.copy(fontSize = base.fontSize * zoom.factor, lineHeight = base.lineHeight * zoom.factor),
                fontWeight = FontWeight.SemiBold,
                color = Ink.copy(alpha = 0.9f),
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        return
    }
    // Synced: Mixify's lyrics panel (LyricsPanel.kt).
    val panelLines = remember(lyrics) { lyrics.panelLines() }
    val playing by com.comfort.app.music.MusicPlayer.state.let { flow ->
        androidx.compose.runtime.produceState(flow.value.isPlaying, flow) { flow.collect { value = it.isPlaying } }
    }
    LyricsPanel(panelLines, lyrics, positionMs, playing, zoom.factor, onSeek, modifier.pinchToZoomText(zoom))
}


/**
 * Spotify's lyric line under the cover, as two: the line being sung, bright, and the next one,
 * dimmed under it. When the song moves on to the next line, it rolls up (Apple Music's): the next
 * line rises into the sung line's place, growing and brightening as it goes, while the old one
 * slides up and fades out, and the line after comes in dimmed below. Any other move (a seek, back
 * a line) just shows the new lines. Before the first line starts, the first two show dimmed. A tap
 * opens the full lyrics.
 */
@Composable
/** [compact]: smaller lines, for the room under the cover in landscape. */
internal fun LyricsPreview(lyrics: Lyrics, positionMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val lines = lyrics.lines ?: return
    val target = lyrics.lineAt(positionMs)
    // Word-timed lyrics: the line being sung fills word by word, as in the full lyrics.
    val rich = remember(lyrics) { lyrics.rich?.filterNot { it.isGap }?.sortedBy { it.timeMs }?.takeIf { it.size == lines.size } }
    val playing by com.comfort.app.music.MusicPlayer.state.let { flow ->
        androidx.compose.runtime.produceState(flow.value.isPlaying, flow) { flow.collect { value = it.isPlaying } }
    }
    val smoothPosition = rememberSmoothPosition(positionMs, playing)
    // The line shown as being sung; it follows [target], with the roll in between for a step on.
    var shown by remember(lyrics) { androidx.compose.runtime.mutableIntStateOf(target) }
    val roll = remember(lyrics) { androidx.compose.animation.core.Animatable(0f) }
    // A jump to a line further on or back (the app back, a seek): the lines fade, change, and
    // rise back in; coming up with the app, they rise in too.
    val settle = remember(lyrics) { androidx.compose.animation.core.Animatable(if (cameWithApp()) 0f else 1f) }
    SettleOnReturn(settle)
    LaunchedEffect(shown) {
        if (settle.value < 1f) {
            if (settle.value == 0f && cameWithApp()) kotlinx.coroutines.delay(GLIDE_DELAY_MS)
            settle.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    LaunchedEffect(lyrics, target) {
        if (target == shown) return@LaunchedEffect
        if (!(shown >= 0 && target == shown + 1) && kotlin.math.abs(target - shown) > 1) {
            try {
                settle.animateTo(0f, tween(160))
            } finally {
                shown = target
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { roll.snapTo(0f) }
            }
            return@LaunchedEffect
        }
        try {
            if (shown >= 0 && target == shown + 1) {
                roll.snapTo(0f)
                roll.animateTo(1f, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        } finally {
            // Rolled, cut short by the next line (the position gliding in as the player opens
            // runs through them fast: each rolls on from the last), or anything else (a seek,
            // back a line, several on): the new lines, at rest.
            shown = target
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { roll.snapTo(0f) }
        }
    }
    val p = roll.value

    val nextStyle = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge).copy(fontFamily = com.comfort.app.theme.HeaderFontFamily)
    val currentStyle = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).copy(fontFamily = com.comfort.app.theme.HeaderFontFamily)
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.toPx() }
    // Room for the line being sung on two lines and the next on one, whatever they hold: the
    // cover above takes what's left, so a line wrapping or not would make it jump.
    val height = with(density) { (currentStyle.lineHeight * 2).toDp() + nextStyle.lineHeight.toDp() } + 6.dp
    fun text(i: Int) = lines.getOrNull(i)?.text?.ifBlank { "♪" } ?: ""
    // Before the first line: the first two, the first dimmed until it starts.
    val first = shown.coerceAtLeast(0)
    val firstAlpha by animateFloatAsState(if (shown < 0) 0.55f else 1f, tween(320), label = "lyricFirstLine")
    var outgoingHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Lines are laid out once at the sung line's size and only drawn smaller, never re-wrapped:
    // growing the font itself re-broke a long line at every step, words hopping between its two
    // lines as it rose, and again as it settled.
    val nextScale = (nextStyle.fontSize.value / currentStyle.fontSize.value).coerceIn(0.5f, 1f)

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .graphicsLayer {
                alpha = settle.value
                translationY = (1f - settle.value) * 14.dp.toPx()
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                // A long next line runs past the bottom: it fades out there rather than being cut.
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 18.dp.toPx()
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Black, 1f to Color.Transparent,
                            startY = size.height - fade, endY = size.height,
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - fade),
                        size = androidx.compose.ui.geometry.Size(size.width, fade),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(androidx.compose.ui.Alignment.Top, unbounded = true)
                    // Up by the sung line and the gap under it: the next line ends where it was.
                    .graphicsLayer { translationY = -(outgoingHeight + gapPx) * p },
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                // The line being sung, whole: on two lines if need be.
                LyricPreviewLine(
                    text(first), currentStyle, scale = 1f, alpha = firstAlpha * (1f - p),
                    modifier = Modifier.onSizeChanged { outgoingHeight = it.height },
                    // Being sung (not before the first line starts): filled word by word.
                    swept = rich?.getOrNull(first)?.takeIf { shown >= 0 && it.isWordSynced },
                    position = smoothPosition,
                )
                // The next line, which grows into the sung one as it rolls up.
                // Rolling up into the sung line's place: already filled up to the word being sung
                // (it rose fully lit, then dropped to dim as the fill took over).
                LyricPreviewLine(
                    text(first + 1), currentStyle, scale = nextScale + (1f - nextScale) * p, alpha = 0.55f + 0.45f * p,
                    swept = rich?.getOrNull(first + 1)?.takeIf { p > 0f && it.isWordSynced },
                    position = smoothPosition,
                    // From the waiting line's dim to the sung line's.
                    sweptDim = 0.55f + (0.4f - 0.55f) * p,
                )
                // The one after, coming in dimmed below as the others rise.
                LyricPreviewLine(text(first + 2), currentStyle, scale = nextScale, alpha = 0.55f * p)
            }
        }
    }
}

/** One line of [LyricsPreview], drawn at [scale] of its laid-out size from its top-left corner, and
 * taking up only that scaled space, so the lines under it sit right below what shows. */
@Composable
private fun LyricPreviewLine(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    scale: Float,
    alpha: Float,
    modifier: Modifier = Modifier,
    // A word-timed line being sung: lit word by word (on [position]), the rest dimmed.
    swept: com.comfort.app.music.lyrics.LyricLine? = null,
    position: () -> Long = { 0L },
    // The unsung part's alpha when [swept] (else 40% of [alpha]).
    sweptDim: Float? = null,
) {
    // Each line sits by its own script (an English line on the left, an Arabic or Hebrew one on the
    // right), whatever the app's language, and shrinks towards that side. Laid out at the full
    // width (only its height scaled), so it lands at the same edge in either layout direction.
    val shown = swept?.text ?: text
    val rtl = remember(shown) { shown.isNotEmpty() && !java.text.Bidi(shown, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight() }
    val lineStyle = style.copy(
        textAlign = if (rtl) androidx.compose.ui.text.style.TextAlign.Right else androidx.compose.ui.text.style.TextAlign.Left,
        textDirection = if (rtl) androidx.compose.ui.text.style.TextDirection.Rtl else androidx.compose.ui.text.style.TextDirection.Ltr,
    )
    val scaled = modifier
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height * scale).toInt()) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (rtl) 1f else 0f, 0f)
                }
            }
        }
        .fillMaxWidth()
    if (swept != null) {
        val a = alpha.coerceIn(0f, 1f)
        SweptLyricLine(swept, position, lineStyle, dimAlpha = sweptDim ?: (0.4f * a), litAlpha = a, maxLines = 2, modifier = scaled)
        return
    }
    Text(
        text,
        style = lineStyle,
        fontWeight = FontWeight.Bold,
        color = Ink.copy(alpha = alpha.coerceIn(0f, 1f)),
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = scaled,
    )
}

/** [settle] from 0 back to 1 whenever the app comes back on screen (from outside it, or reopened):
 * the lyric lines rise in on the line being sung, while the progress bar glides to the song's
 * place (rememberGlidingPosition). */
@Composable
private fun SettleOnReturn(settle: androidx.compose.animation.core.Animatable<Float, *>) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START && cameWithApp()) {
                scope.launch {
                    settle.snapTo(0f)
                    kotlinx.coroutines.delay(GLIDE_DELAY_MS)
                    settle.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
