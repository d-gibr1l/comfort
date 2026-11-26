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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.music.Lyrics
import com.comfort.app.ui.main.pinchToZoomText
import com.comfort.app.ui.main.rememberTextZoom

/**
 * The song's lyrics, in place of the cover. Synced ones follow the song: the line being sung is
 * bright and kept a third of the way down, the rest dimmed; a tap on a line plays from there.
 * Scrolling by hand leaves them where they are for a few seconds before following again.
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

    val current = lyrics.lineAt(positionMs)
    // The lines' words with their own times (word-timed lyrics), in [lines]' order; the line being
    // sung fills word by word on a clock that moves every frame.
    val rich = remember(lyrics) { lyrics.rich?.filterNot { it.isGap }?.sortedBy { it.timeMs }?.takeIf { it.size == lines.size } }
    val playing by com.comfort.app.music.MusicPlayer.state.let { flow ->
        androidx.compose.runtime.produceState(flow.value.isPlaying, flow) { flow.collect { value = it.isPlaying } }
    }
    val smoothPosition = rememberSmoothPosition(positionMs, playing)
    val listState = rememberLazyListState()
    // Settling on a line far from the last one followed (the player opening, the app back, a seek):
    // the lines fade, are put there, and rise back in, rather than scrolling through all of them.
    val settle = remember { androidx.compose.animation.core.Animatable(0f) }
    var followed by remember { androidx.compose.runtime.mutableIntStateOf(Int.MIN_VALUE) }
    // When the user last scrolled by hand; following waits a few seconds after that.
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolledAt = System.currentTimeMillis()
        }
    }

    // Google Sans, as the app's titles (its bundled bold cut; other scripts fall back to the system font).
    // Big, as Apple Music's lyrics.
    val lineStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily).let { base ->
        base.copy(fontSize = base.fontSize * zoom.factor, lineHeight = base.lineHeight * zoom.factor)
    }
    BoxWithConstraints(modifier.pinchToZoomText(zoom)) {
        val height = constraints.maxHeight
        // The line being sung sits a third of the way down.
        val anchor = height / 3
        val density = LocalDensity.current
        LaunchedEffect(current, userScrolledAt) {
            if (current < 0) {
                settle.animateTo(1f, tween(320))
                return@LaunchedEffect
            }
            val sinceUser = System.currentTimeMillis() - userScrolledAt
            if (sinceUser < FOLLOW_AGAIN_AFTER_MS) {
                kotlinx.coroutines.delay(FOLLOW_AGAIN_AFTER_MS - sinceUser)
            }
            autoScrolling = true
            try {
                // The top content padding is the anchor: an item scrolled to the start sits there.
                // Far from the line the list shows (away from the app it doesn't move while the
                // song goes on, and came back it ran through them all).
                val first = followed == Int.MIN_VALUE
                if (first || kotlin.math.abs(current - listState.firstVisibleItemIndex) > 2) {
                    followed = current
                    if (!first) settle.animateTo(0f, tween(160))
                    else if (cameWithApp()) kotlinx.coroutines.delay(GLIDE_DELAY_MS)
                    listState.scrollToItem(current)
                    settle.animateTo(1f, tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                } else {
                    followed = current
                    if (settle.value < 1f) launch { settle.animateTo(1f, tween(320)) }
                    listState.animateScrollToItem(current)
                }
            } finally {
                autoScrolling = false
            }
        }
        LazyColumn(
            state = listState,
            // Lines fade out toward the top and bottom edges rather than being cut off there.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    alpha = settle.value
                    translationY = (1f - settle.value) * 28.dp.toPx()
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Transparent, 0.12f to Color.Black, 0.85f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
            // Room above the first line and below the last, so either can sit at the anchor.
            contentPadding = PaddingValues(top = with(density) { anchor.toDp() }, bottom = with(density) { (height - anchor).toDp() }),
        ) {
            itemsIndexed(lines) { index, line ->
                val active = index == current
                val alpha by animateFloatAsState(
                    when {
                        active -> 1f
                        index < current -> 0.45f
                        else -> 0.55f
                    },
                    animationSpec = tween(300),
                    label = "lyricAlpha",
                )
                val scale by animateFloatAsState(if (active) 1f else 0.96f, animationSpec = tween(300), label = "lyricScale")
                val color by animateColorAsState(if (active) Ink else Ink.copy(alpha = 0.9f), label = "lyricColor")
                // Apple Music's depth: lines blur more the further they are from the one being sung
                // (Android 12+), sharp again while the lyrics are scrolled by hand.
                val handScrolled = listState.isScrollInProgress && !autoScrolling
                val blur by androidx.compose.animation.core.animateDpAsState(
                    if (handScrolled || current < 0) 0.dp else LINE_BLUR[kotlin.math.abs(index - current).coerceAtMost(LINE_BLUR.lastIndex)],
                    tween(400), label = "lyricBlur",
                )
                val richLine = rich?.getOrNull(index)
                // Shrinks towards the side the line is aligned to: its own script decides that (an
                // Arabic or Hebrew line sits on the right, whatever the app's language).
                val originX = remember(line.text) { if (java.text.Bidi(line.text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()) 0f else 1f }
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSeek(line.timeMs) }
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(originX, 0.5f)
                        }
                        .then(if (blur > 0.dp && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) Modifier.blur(blur, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else Modifier)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    if (active && richLine != null && richLine.isWordSynced) {
                        // Being sung, word by word: lit up to the word being sung, the rest dimmed.
                        SweptLyricLine(richLine, smoothPosition, lineStyle, dimAlpha = 0.4f)
                    } else {
                        Text(line.text.ifBlank { "♪" }, style = lineStyle, fontWeight = FontWeight.Bold, color = color)
                    }
                    // Background vocals, smaller and dimmer under their line.
                    richLine?.background?.text?.takeIf { it.isNotBlank() }?.let { backing ->
                        Text(
                            backing,
                            style = lineStyle.copy(fontSize = lineStyle.fontSize * 0.7f, lineHeight = lineStyle.lineHeight * 0.7f),
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.copy(alpha = if (active) 0.7f else 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val FOLLOW_AGAIN_AFTER_MS = 3_000L

/** The blur of a line 0, 1, 2, 3… lines from the one being sung (more beyond). */
private val LINE_BLUR = arrayOf(0.dp, 1.5.dp, 2.5.dp, 3.5.dp, 4.dp)

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
                LyricPreviewLine(text(first + 1), currentStyle, scale = nextScale + (1f - nextScale) * p, alpha = 0.55f + 0.45f * p)
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
) {
    val scaled = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
    if (swept != null) {
        val a = alpha.coerceIn(0f, 1f)
        SweptLyricLine(swept, position, style, dimAlpha = 0.4f * a, litAlpha = a, maxLines = 2, modifier = scaled)
        return
    }
    Text(
        text,
        style = style,
        fontWeight = FontWeight.Bold,
        color = Ink.copy(alpha = alpha.coerceIn(0f, 1f)),
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout((placeable.width * scale).toInt(), (placeable.height * scale).toInt()) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        },
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
