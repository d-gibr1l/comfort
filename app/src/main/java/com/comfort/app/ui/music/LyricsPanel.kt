// The full lyrics, as Mixify draws them (https://github.com/MixifyTeam/Mixify, GPL-3.0, as Comfort
// is): its LyricsPanel, PanelVoice and SweptLyricLine (sharedUi/.../ui/player/PlayerLyrics.kt),
// with the sweep, the word lift, the held words' swell and bloom, the breaks' dots, the answering
// vocal, duet lanes, the staggered handover and the falloff. Adapted to Comfort's player: its ink
// (light or dark player), its type, pinch to zoom; without Mixify's translation, line picking and
// controls-hiding, which Comfort doesn't have.
package com.comfort.app.ui.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfort.app.music.Lyrics
import com.comfort.app.music.lyrics.CharGrowth
import com.comfort.app.music.lyrics.GrowingWord
import com.comfort.app.music.lyrics.LyricAlignment
import com.comfort.app.music.lyrics.LyricLine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Returns true if any character in [text] belongs to a BMP RTL script. */
private fun isRtl(text: String): Boolean {
    var rtlCount = 0
    var ltrCount = 0
    for (ch in text) {
        val code = ch.code
        if (code in 0xFB1D..0xFB4F) return true
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtlCount++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltrCount++
        }
    }
    return rtlCount > ltrCount
}

/** Whether this text should align to the right end of its box. */
private fun alignsRight(text: String, alignEnd: Boolean, laneLocked: Boolean): Boolean =
    if (laneLocked) alignEnd else isRtl(text)

/** Per-voice text style with absolute physical alignment based on the voice's own text direction. */
private fun voiceStyle(base: TextStyle, text: String, alignEnd: Boolean, laneLocked: Boolean): TextStyle = base.copy(
    textAlign = if (alignsRight(text, alignEnd, laneLocked)) TextAlign.Right else TextAlign.Left,
    textDirection = if (isRtl(text)) androidx.compose.ui.text.style.TextDirection.Rtl else androidx.compose.ui.text.style.TextDirection.Ltr,
)

/** How far back the part of the playing line that hasn't been sung yet is held. */
private const val UNSUNG_ALPHA = 0.45f

/** The bloom behind the line being sung, at its very strongest (scaled by each letter's own). */
private const val GLOW_ALPHA = 0.62f

/** How far the bloom spreads off a letter. */
private val GLOW_RADIUS = 6.dp

/** Room inside each copy of a line for the halo to spread into (the list gives it back). */
private val GLOW_ROOM = 10.dp

/** The answering vocal: smaller than the lead and a shade behind it. */
private val BACKING_FONT_SIZE = 23.sp
private val BACKING_LINE_HEIGHT = 29.sp
private const val BACKING_ALPHA = 0.72f
private const val BACKING_OPEN_MS = 400

/** Where a lyric line is in its own animation; see [PanelSweptLine]. */
private const val PHASE_BEFORE = 0
private const val PHASE_MOVING = 1
private const val PHASE_AFTER = 2

/** An instrumental break that is playing, so its fill is read off the clock. */
private const val GAP_RUNNING = -1f

/** How far ahead of its first word the answering voice starts to open. */
private const val BACKING_LEAD_MS = 250L
private const val BACKING_CLOSE_MS = 450
private val BACKING_RISE = 6.dp
private const val BACKING_REST_SCALE = 0.94f
private const val BACKING_FADE_LEAD = 1.6f

/** How far the sweep's leading edge fades out instead of ending on a cut. */
private val WIPE_FEATHER = 30.dp

/** How far the word being sung lifts off the line. */
private val WORD_RISE = 2.dp

/** How much further up a row is opened for a word animated letter by letter, in [WORD_RISE]s. */
private const val GROW_HEADROOM = 3f

/** The lane kept clear on the far side of a duet line. */
private val DUET_LANE = 44.dp

/** How tall a break stands while it is playing (nothing when it is not). */
private val GAP_ROW_HEIGHT = 40.dp
private val GAP_ROW_SPACING = 16.dp

/** How the stack falls away either side of the line being sung, by distance from it. */
private val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)
private val LINE_FALLOFF_ALPHA_PHONE = floatArrayOf(1f, 0.74f, 0.6f, 0.48f, 0.4f, 0.34f)

/** What a line reads at while the list is being scrolled by hand. */
private const val BROWSING_ALPHA = 0.8f

/** The playing line sits at 1; the rest sit fractionally back from it. */
private const val INACTIVE_SCALE = 0.98f

/** A line under a finger dips, the way a button does. */
private const val PRESSED_SCALE = 0.96f

/** The break between verses, counted out by three dots lighting in turn. */
private const val GAP_DOTS = 3
private val GAP_DOT_SIZE = 13.dp
private val GAP_DOT_GAP = 5.dp
private const val GAP_DOT_REST = 0.25f
private const val GAP_REST_SCALE = 0.76f

/** How long the panel takes to settle on a new line, and how far ahead it starts. */
private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L

/** The curve every handover runs on: away quickly, in slowly and softly. */
private val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
private const val LYRIC_SETTLE_MS = 400

/** How the rows fan out as the panel moves between lines. */
private const val STAGGER_STEPS = 3
private const val STAGGER_FRACTION = 0.06f

/** One handover: how far the panel is going, and how long it is taking. */
private class ScrollRun(val id: Int, val delta: Float, val durationMs: Int) {
    /** The last row to arrive does so this long after the panel sets off. */
    val spanMs: Float get() = durationMs * (1f + STAGGER_FRACTION * STAGGER_STEPS)
}

/** How long before a line lands the panel starts moving to it (and how long the move takes). */
private fun scrollLead(lines: List<LyricLine>, positionMs: Long): Long {
    val current = lines.indexOfLast { it.timeMs <= positionMs }
    if (current < 0) return SCROLL_LEAD_MIN_MS
    val next = lines.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
    val gap = next.timeMs - lines[current].endMs
    return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
}

/** Keep every unfinished vocal visible, including overlaps spanning more than two rows (Mixify's LyricFocus). */
private fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
    val latest = lines.indexOfLast { it.timeMs <= positionMs }
    if (latest < 0) return emptyList()
    return (0..latest).filter { index ->
        val line = lines[index]
        index == latest || (!line.isGap &&
            (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
            line.timeMs <= positionMs && positionMs < line.endMs)
    }
}

/** [Lyrics] as Mixify's lines: the source's own where it has them (word timings, background
 * vocals, duet sides, breaks), else the LRC lines, a blank one or the intro being a break. */
internal fun Lyrics.panelLines(): List<LyricLine> {
    rich?.takeIf { r -> r.any { it.timeMs > 0 } }?.let { return it.sortedBy { line -> line.timeMs } }
    val synced = lines ?: return emptyList()
    val out = ArrayList<LyricLine>(synced.size + 1)
    if ((synced.firstOrNull()?.timeMs ?: 0L) >= com.comfort.app.music.lyrics.MIN_GAP_MS) out += LyricLine(0L, "")
    synced.forEach { line ->
        val text = line.text.trim().takeUnless { it == "♪" || it == "♫" }.orEmpty()
        out += LyricLine(line.timeMs, text)
    }
    return out
}

/**
 * The song position, ticking every frame (Mixify's rememberLyricClock): the player is asked
 * where it is a few times a second, and [LyricClock] runs on the frames in between, steered by
 * those readings rather than set by them. [seeks] changes on a jump the lyrics made themselves.
 */
@Composable
private fun rememberLyricClock(trackKey: Any, positionMs: Long, isPlaying: Boolean, seeks: Int): MutableLongState {
    // When this reading was taken: it reaches here with the recomposition it caused.
    val sampledAt = remember(positionMs) { System.nanoTime() }
    val reading = rememberUpdatedState(positionMs)
    val stamp = rememberUpdatedState(sampledAt)
    val jumps = rememberUpdatedState(seeks)
    val clock = remember(trackKey) { mutableLongStateOf(Snapshot.withoutReadObservation { positionMs }) }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }
    if (!isPlaying) {
        LaunchedEffect(engine, positionMs) {
            engine.hold(positionMs, System.nanoTime() / 1_000_000.0)
            clock.longValue = positionMs
        }
    }
    // Only while the app is on screen: the loop asks for a frame on every frame.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    LaunchedEffect(engine, isPlaying, foreground) {
        if (!isPlaying || !foreground) return@LaunchedEffect
        engine.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                val system = System.nanoTime()
                val now = if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system
                clock.longValue = engine.frame(
                    nowMs = now / 1_000_000.0,
                    reportedMs = reading.value,
                    sampledAtMs = stamp.value / 1_000_000.0,
                    discontinuity = jumps.value.toLong(),
                )
            }
        }
    }
    return clock
}

/** A frame time this close to [System.nanoTime] is on the same clock. */
private const val SAME_CLOCK_NANOS = 250_000_000L

/** Lets a hand scroll stay in the list rather than pass on to what holds it (Mixify's keepScrollInList). */
private fun keepScrollInList(listState: LazyListState) = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPreFling(available: Velocity): Velocity =
        if (available.y > 0f && !listState.canScrollBackward) available else Velocity.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Softens the list where it meets what's above and below it. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 28.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade), blendMode = BlendMode.DstIn)
        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height), blendMode = BlendMode.DstIn)
    }

/** Measured wider than its slot by [gutter] each side and placed back over it (the glow room). */
private fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-(placeable.width - width) / 2, 0) }
}

private fun DrawScope.clipShiftedDown(left: Float, top: Float, right: Float, bottom: Float, dy: Float, block: DrawScope.() -> Unit) {
    clipRect(left = left, top = top, right = right, bottom = bottom) {
        translate(top = dy) { block() }
    }
}

/**
 * Apple Music's lyrics view: big tight type, the playing line crisp and everything else falling
 * out of focus the further it is from it (blur on Android 12+; alpha carries it before).
 * Scrolling by hand clears the blur and suspends the following; it comes back once the sung
 * line is on screen again, or a few seconds after you stop.
 */
@Composable
internal fun LyricsPanel(
    lines: List<LyricLine>,
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
    zoom: Float,
    onSeekToLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var seeks by remember(trackKey) { mutableIntStateOf(0) }
    val clock = rememberLyricClock(trackKey, positionMs, isPlaying, seeks)
    val seekTo: (Long) -> Unit = { target -> seeks++; onSeekToLine(target) }
    val ink = Ink
    // A dark glow on a light player reads as a smudge, so the bloom is the dark player's.
    val glowing = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !LocalPlayerLight.current

    // Only a song that actually names a second voice is laid out as one.
    val duet = remember(lines) { lines.any { it.alignment == LyricAlignment.End } }
    val activeRows by remember(lines) { derivedStateOf { activeLyricRows(lines, clock.longValue) } }
    // The uppermost unfinished vocal owns the scroll anchor until its end.
    val scrollLine = activeRows.firstOrNull() ?: -1
    // Where the panel is heading, a beat ahead of the singing, so the words land under the eye.
    val leadLine by remember(lines) {
        derivedStateOf {
            val now = clock.longValue
            activeLyricRows(lines, now + scrollLead(lines, now)).firstOrNull() ?: -1
        }
    }
    val focusLine = if (leadLine >= 0) leadLine else scrollLine
    val listState = rememberLazyListState()
    val viewportHeight by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    var browsing by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) browsing = true }
    }
    val currentLine by rememberUpdatedState(focusLine)
    val activeOnScreen by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == currentLine } }
    }
    // Paused, a hand scroll sits wherever it was left.
    LaunchedEffect(browsing, activeOnScreen, listState.isScrollInProgress, isPlaying) {
        if (isPlaying && browsing && activeOnScreen && !listState.isScrollInProgress) {
            delay(600)
            browsing = false
        }
    }
    LaunchedEffect(browsing, listState.isScrollInProgress, isPlaying) {
        if (isPlaying && browsing && !listState.isScrollInProgress) {
            delay(5_000)
            browsing = false
        }
    }

    // The panel's own journey, so each row can work out how far behind it should be running.
    var run by remember(lines) { mutableStateOf(ScrollRun(0, 0f, LYRIC_SETTLE_MS)) }
    val since = remember(lines) { mutableFloatStateOf(0f) }
    LaunchedEffect(run.id) {
        if (run.id == 0) return@LaunchedEffect
        animate(0f, run.spanMs, animationSpec = tween(run.spanMs.toInt(), easing = LinearEasing)) { value, _ -> since.floatValue = value }
    }
    var placed by remember(trackKey) { mutableStateOf(false) }
    LaunchedEffect(focusLine, browsing) {
        if (!browsing && focusLine >= 0 && focusLine in lines.indices) {
            snapshotFlow { listState.layoutInfo }.first { it.viewportSize.height > 0 }
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusLine }
            when {
                !placed -> {
                    listState.scrollToItem(focusLine)
                    placed = true
                }
                // On screen, the ordinary handover: the run-up's own duration and curve.
                visible != null -> {
                    val span = scrollLead(lines, clock.longValue).toInt()
                    val distance = visible.offset.toFloat()
                    run = ScrollRun(run.id + 1, distance, span)
                    listState.animateScrollBy(distance, tween(durationMillis = span, easing = LYRIC_EASING))
                }
                // Off screen (a seek, a long break scrolled past): the list's own staged scroll.
                else -> listState.animateScrollToItem(focusLine)
            }
        }
    }

    val density = LocalDensity.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .bleedHorizontally(GLOW_ROOM)
            .nestedScroll(keepScroll)
            .fadingEdges(),
        // Each row carries GLOW_ROOM of its own inset for the halo; the list hands it back.
        contentPadding = PaddingValues(
            top = 40.dp - GLOW_ROOM,
            bottom = with(density) { viewportHeight.toDp() } * 0.8f,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val offset = if (scrollLine < 0) 0 else index - scrollLine
            val distance = abs(offset)
            val isActive = index in activeRows
            val falloff = LINE_FALLOFF_ALPHA_PHONE
            val step = distance.coerceAtMost(falloff.lastIndex)
            val blur by animateDpAsState(
                targetValue = if (browsing || isActive) 0.dp else LINE_FALLOFF_BLUR[step.coerceAtMost(LINE_FALLOFF_BLUR.lastIndex)],
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricBlur",
            )
            val lineAlpha by animateFloatAsState(
                targetValue = when {
                    isActive -> 1f
                    browsing -> BROWSING_ALPHA
                    else -> falloff[step]
                },
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricAlpha",
            )
            if (line.isGap) {
                // A break counts itself out: three dots lighting in turn across the interlude.
                val until = lines.getOrNull(index + 1)?.timeMs ?: line.endMs
                val gapFill = remember(line, until, clock) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        val now = clock.longValue
                        when {
                            now <= line.timeMs -> 0f
                            now >= until -> 1f
                            else -> GAP_RUNNING
                        }
                    }
                }
                // The row opens and closes with the break, so the list carries no dead space.
                val swell by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = tween(durationMillis = if (isActive) 400 else 350, easing = LYRIC_EASING),
                    label = "gapSwell",
                )
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.height((GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell).clipToBounds(),
                ) {
                    Box(
                        modifier = Modifier
                            .blur(blur, BlurredEdgeTreatment.Unbounded)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { seekTo(line.timeMs) }
                            .padding(GLOW_ROOM)
                            .size(width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2, height = GAP_DOT_SIZE)
                            .graphicsLayer {
                                val grow = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
                                scaleX = grow
                                scaleY = grow
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                alpha = lineAlpha * swell
                            }
                            .drawBehind {
                                val span = (until - line.timeMs).coerceAtLeast(1L)
                                val through = gapFill.value.takeUnless { it == GAP_RUNNING }
                                    ?: ((clock.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                                val radius = GAP_DOT_SIZE.toPx() / 2f
                                val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                                repeat(GAP_DOTS) { dot ->
                                    val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                                    drawCircle(
                                        color = ink.copy(alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit),
                                        radius = radius,
                                        center = Offset(radius + dot * stride, size.height / 2f),
                                    )
                                }
                            }
                            .semantics { contentDescription = "Instrumental" },
                    )
                }
            } else {
                val alignEnd = duet && line.alignment == LyricAlignment.End
                val style = TextStyle(
                    fontFamily = com.comfort.app.theme.HeaderFontFamily,
                    fontSize = 34.sp * zoom,
                    lineHeight = 41.sp * zoom,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                )
                val sung = offset < 0
                // Rows behind the one being scrolled to fan out; the ones it leaves arrive together.
                val behind = if (run.delta >= 0f) index - focusLine else focusLine - index
                val staggerDelay = behind.coerceIn(0, STAGGER_STEPS) * STAGGER_FRACTION * run.durationMs
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = when {
                        pressed -> PRESSED_SCALE
                        isActive -> 1f
                        else -> INACTIVE_SCALE
                    },
                    animationSpec = tween(durationMillis = if (pressed) 120 else LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                    label = "lyricScale",
                )
                // Apple's bloom on the line being sung, fading in and out with it.
                val glow by animateFloatAsState(
                    targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                    animationSpec = tween(durationMillis = 420),
                    label = "lyricGlow",
                )
                val shape = Modifier
                    .fillMaxWidth()
                    .padding(start = if (duet && alignEnd) DUET_LANE else 0.dp, end = if (duet && !alignEnd) DUET_LANE else 0.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                        alpha = lineAlpha
                        // Held back against the list's own movement, so the rows fan out.
                        translationY = if (staggerDelay <= 0f) {
                            0f
                        } else {
                            val elapsed = since.floatValue
                            run.delta * (
                                LYRIC_EASING.transform((elapsed / run.durationMs).coerceIn(0f, 1f)) -
                                    LYRIC_EASING.transform(((elapsed - staggerDelay) / run.durationMs).coerceIn(0f, 1f))
                                )
                        }
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = interaction, indication = LocalIndication.current) { seekTo(line.timeMs) }
                // The answering voice is on screen only while it is being sung.
                val backingOpen = line.background?.let { backing ->
                    val due by remember(backing, clock) {
                        derivedStateOf {
                            val now = clock.longValue
                            now >= backing.timeMs - BACKING_LEAD_MS && now < backing.endMs
                        }
                    }
                    val wanted = if (backing.isWordSynced) due else isActive || index == focusLine
                    animateFloatAsState(
                        targetValue = if (wanted) 1f else 0f,
                        animationSpec = if (wanted) {
                            tween(BACKING_OPEN_MS, easing = LYRIC_EASING)
                        } else {
                            tween(BACKING_CLOSE_MS, delayMillis = if (index < focusLine) run.durationMs else 0, easing = FastOutSlowInEasing)
                        },
                        label = "backingOpen",
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(modifier = shape) {
                        PanelVoice(
                            line = line,
                            clock = clock,
                            style = voiceStyle(style, line.text, alignEnd, duet),
                            isActive = isActive,
                            sung = sung,
                            browsing = browsing,
                            glowAlpha = glow,
                            room = GLOW_ROOM,
                            alignEnd = alignEnd,
                            laneLocked = duet,
                            ink = ink,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        line.background?.let { backing ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .then(backingOpen?.let { Modifier.revealBacking(it, alignEnd) } ?: Modifier),
                            ) {
                                PanelVoice(
                                    line = backing.withoutBracketPunctuation(),
                                    clock = clock,
                                    style = voiceStyle(style.copy(fontSize = BACKING_FONT_SIZE * zoom, lineHeight = BACKING_LINE_HEIGHT * zoom), backing.text, alignEnd, duet),
                                    isActive = isActive,
                                    sung = sung,
                                    browsing = browsing,
                                    glowAlpha = 0f,
                                    room = 0.dp,
                                    alignEnd = alignEnd,
                                    laneLocked = duet,
                                    rise = false,
                                    ink = ink,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                        .graphicsLayer { alpha = BACKING_ALPHA },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One voice of a row in [LyricsPanel]: the lead, or the answering line drawn under it. */
@Composable
private fun PanelVoice(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    sung: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    room: Dp,
    alignEnd: Boolean,
    laneLocked: Boolean = false,
    rise: Boolean = true,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    // The line being sung is in full ink; the others a shade behind it (Mixify tints them with the
    // sleeve's colour), faded across the handover.
    val color by animateColorAsState(
        targetValue = if (isActive) ink else ink.copy(alpha = 0.9f),
        label = "lyricLineColor",
    )
    if (line.isWordSynced) {
        // Every word-synced line goes through the sweep: sung ones are fully lit, coming ones not.
        val tail by animateFloatAsState(targetValue = if (sung) 1f else UNSUNG_ALPHA, label = "lyricTail")
        PanelSweptLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = if (browsing) 0f else glowAlpha,
            glowRoom = room,
            feather = isActive && !browsing,
            rise = rise,
            alignEnd = alignEnd,
            laneLocked = laneLocked,
            color = color,
        )
    } else {
        // No word timings: the line lights whole, the moment it starts.
        val lit by animateFloatAsState(targetValue = if (sung || isActive) 1f else UNSUNG_ALPHA, label = "lyricLit")
        Text(
            text = line.text,
            style = style,
            color = color.copy(alpha = color.alpha * lit),
            modifier = modifier.padding(room),
        )
    }
}

/** Opens the answering voice under its lead as [progress] runs from 0 to 1. */
private fun Modifier.revealBacking(progress: State<Float>, alignEnd: Boolean): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val open = progress.value
        val rise = BACKING_RISE.toPx()
        layout(placeable.width, (placeable.height * open).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                alpha = (open * BACKING_FADE_LEAD).coerceAtMost(1f)
                translationY = rise * (1f - open)
                val grow = BACKING_REST_SCALE + (1f - BACKING_REST_SCALE) * open
                scaleX = grow
                scaleY = grow
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
            }
        }
    }

/** The answering vocal without the parentheses text-only sources wrap it in (display only). */
private fun LyricLine.withoutBracketPunctuation(): LyricLine = copy(
    text = text.replace("(", "").replace(")", "").trim(),
    words = words.mapNotNull { word -> word.withoutChars { it == '(' || it == ')' } },
)

/**
 * A lyric line with the sung part of it lit, the rest dimmed, and the boundary
 * travelling across the words in time with the vocal.
 *
 * Two copies of the same text stacked: a dim one and a bright one clipped to
 * whatever has been sung. Same string, same style, same constraints, so the
 * two lay out identically and the bright copy lands exactly on top of the dim
 * one. The alternative — colouring an AnnotatedString word by word — can only
 * change a whole word at a time, which turns the sweep into a flicker.
 *
 * The clip is recomputed in the draw phase, so a frame costs one clip and one
 * redraw of already-measured text.
 *
 * [glowAlpha] adds Apple's bloom: a third copy, blurred, behind the other two
 * and clipped to the letters of whatever word is being held. Blurring *after*
 * the clip rather than before is what makes the halo bleed out past the letter
 * it belongs to, which is the part that reads as light coming off a carried
 * note rather than a drop shadow sitting under the line.
 */
@Composable
private fun PanelSweptLine(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
    feather: Boolean = false,
    rise: Boolean = true,
    alignEnd: Boolean = false,
    laneLocked: Boolean = false,
    /** What the words are set in — see [PanelVoice] for which lines differ. */
    color: Color = Color.White,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    // Filled in and read back a letter at a time inside the draw lambdas, and
    // shared by all three copies of the line — they draw one after another on
    // the same thread, so there is only ever one letter in hand. Held here
    // rather than allocated per frame: a held word is seven letters at the
    // outside, but this runs on every frame of every line that has one.
    val growth = remember { CharGrowth() }

    // Where this line is in its own animation — not started, moving, or done —
    // which changes three times a line rather than sixty times a second. The
    // draw passes below read the clock only while it says moving; on either
    // side they draw a position that looks identical to any other there (see
    // [LyricLine.animatesFromMs]), so a line nobody is singing never redraws.
    //
    // Read in draw, every line on screen used to redraw on every frame of
    // playback, and each line away from the playing one sits under a blur
    // that then had to be worked out again — most of the panel's frame, for a
    // picture that had not changed. Paused, nothing moved, which is why the
    // panel was smooth paused and slow playing.
    val phase = remember(line, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> PHASE_BEFORE
                now > line.animatesUntilMs -> PHASE_AFTER
                else -> PHASE_MOVING
            }
        }
    }
    val drawnAt: () -> Long = {
        when (phase.value) {
            PHASE_MOVING -> clock.longValue
            PHASE_BEFORE -> line.animatesFromMs - 1
            else -> line.animatesUntilMs + 1
        }
    }

    // Carried by every copy: identical insets keep them laying out identically,
    // and the inset is what gives the blurred copy's layer somewhere to put the
    // halo. Sits inside the blur and outside the draw lambdas, so text-layout
    // coordinates and draw coordinates still agree.
    //
    // Off unless asked for. Only the full panel can afford it — it takes the
    // space back off its own row spacing and content padding. Handed to the
    // one-line strip above the scrubber, where there is no glow to make room
    // for and nothing paying the space back, it just left the line sitting in
    // a pocket of air with the chevron pushed off it.
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    // Sits outside [room] and outside the sweep, so what it moves is the
    // finished picture of the word — dim tail, lit head and all — rather than
    // one copy sliding out from under another. Carried by both copies from the
    // same arithmetic, which is what keeps them on top of each other.
    //
    // Off for the one-line strip above the scrubber ([rise] = false). The lift
    // belongs to a page of lyrics, where a word rising out of the line it sits
    // in is the thing being read; on a single line pinned between the credits
    // and the slider it has nothing to rise away from and reads as the strip
    // itself twitching.
    val riseAgainst: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        riseWith(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    }
                }
                .then(inner)
        }
    }

    val sweep = Modifier.drawWithContent {
        val position = drawnAt()
        when {
            // Sung and done with: all of it is lit. Checked first so the lines
            // above and below the playing one — which are in this same state
            // for minutes at a time — cost a comparison per frame rather than
            // a walk of their words.
            position >= line.endMs -> drawContent()
            // Not started: nothing lit, the dim copy is the whole of it.
            position <= line.timeMs -> Unit
            else -> layout?.let {
                sweepTo(it, line.revealedChars(position), line.sweepSpans, feather)
            }
        }
    }

    val right = alignsRight(line.text, alignEnd, laneLocked)
    Box(
        modifier,
        contentAlignment = if (right) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft,
    ) {
        Text(
            text = line.text,
            style = style,
            color = color.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = riseAgainst(room),
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    // Each letter is masked to its own brightness with DstIn,
                    // which needs a layer of its own to erase into — against the
                    // backdrop it would take the artwork with it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // Deliberately not the shared sweep: that lights
                        // everything sung so far, and this lights only the words
                        // being held. Most lines draw nothing here at all, which
                        // is the whole difference between this and a halo
                        // travelling under the highlight.
                        val measured = layout ?: return@drawWithContent
                        glowGrown(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            // The feather erases into this layer, so the layer has to exist —
            // and only while it is being drawn. Every line carrying one would
            // put the whole panel through an offscreen buffer to soften an edge
            // that at most two of them have.
            modifier = riseAgainst(
                Modifier
                    .graphicsLayer {
                        compositingStrategy = if (feather) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .then(room)
                    .then(sweep),
            ),
        )
    }
}

/**
 * Draws this text clipped to the letters of the words being held, each at its
 * own brightness — the light the singing is actually giving off, rather than a
 * band of it dragged along behind the highlight.
 *
 * Nothing at all on a line of ordinary syllables: the words that light up are
 * the ones held long enough to have earned it, so a verse of patter is simply
 * dark and costs one comparison to establish. That selectiveness is the point.
 * A glow present on every word is a property of the highlight; a glow that
 * arrives only when a note is carried is a property of the voice.
 *
 * Each letter is masked to its own bloom rather than drawn at it, because the
 * caller's layer is what this erases into — see [PanelSweptLine]. The mask
 * lands before the blur, so what spreads is already the right brightness.
 */
private fun ContentDrawScope.glowGrown(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isGrowing(positionMs)) return
    val em = layout.layoutInput.style.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val visualLine = layout.getLineForOffset(char)
            // Row-aware, for the same reason the sweep is; see [xOn].
            val from = layout.xOn(char, visualLine, inset)
            val to = layout.xOn(char + 1, visualLine, inset)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val rowTop = layout.getLineTop(visualLine) + inset
            val bottom = layout.getLineBottom(visualLine) + inset
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(
                left = from - overhang + dx,
                top = rowTop - peak * GROW_HEADROOM,
                right = to + overhang + dx,
                bottom = bottom,
            ) {
                translate(left = dx, top = dy) {
                    scale(
                        growth.scale,
                        growth.scale,
                        Offset((from + to) / 2f, (rowTop + bottom) / 2f),
                    ) {
                        this@glowGrown.drawContent()
                    }
                }
                // Scoped to this letter's own clip, so it takes this letter's
                // brightness down and leaves its neighbours — which have their
                // own, a beat behind — where they are.
                drawRect(
                    color = Color.White.copy(alpha = growth.bloom),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

/**
 * Redraws this row with the word being sung lifted off the line, and the ones
 * behind it settling back down.
 *
 * The line is cut at word boundaries and each piece replayed at its own
 * height, which is what CSS gets for free by making every syllable its own
 * box. Cutting between words rather than inside one means no glyph is ever
 * sliced, and the pieces that are on the floor — which is most of them, most
 * of the time — are one replay between them rather than one each.
 *
 * Costs nothing at all until something is off the floor: a line with no lift
 * on it draws exactly once, the same as it did before any of this.
 */
private fun ContentDrawScope.riseWith(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isLifted(positionMs)) {
        drawContent()
        return
    }
    val em = layout.layoutInput.style.fontSize.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        // The row's own box. Anything standing still is clipped to exactly
        // this: a band opened upwards would take in the bottom of the row
        // above and draw it a second time, and two passes of a half-transparent
        // line do not add up to the same line. That doubled sliver along every
        // row is what read as the lines overlapping.
        val top = layout.getLineTop(visualLine) + inset
        val bottom = layout.getLineBottom(visualLine) + inset
        var at = lineStart
        var edge = layout.getLineLeft(visualLine) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, lineStart)
            val end = minOf(span.last + 1, lineEnd)
            if (start >= end) continue
            // Only while it is actually moving. Once the last letter has come to
            // rest the word is back to being an ordinary sung word settling
            // down, and the two agree exactly at the handover — a letter rests
            // at precisely the lift [LyricLine.wordLift] would give it — so the
            // cheaper single slice takes over without a step.
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            // A word with nothing happening to it is left to the flat run,
            // which is the whole of the line for all but a syllable of it.
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, visualLine, inset)
            val to = layout.xOn(end, visualLine, inset)
            // Nothing to cut. Left where it is rather than stepped over, so the
            // flat run still has it and the row keeps its words.
            if (to <= from) continue
            // Everything between the last risen word and this one is flat, and
            // goes down in a single piece however many words that spans.
            if (start > at) sliceRisen(edge, top, from, bottom, 0f)
            if (held != null) {
                growEach(
                    layout, held, line, positionMs, visualLine,
                    start, end, top, bottom, inset, peak, em, growth,
                )
            } else {
                // Only what is off the floor gets room above the row to be off
                // it in; see [top].
                sliceRisen(from, top - peak, to, bottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < lineEnd) {
            sliceRisen(edge, top, layout.getLineRight(visualLine) + inset, bottom, 0f)
        }
    }
}

/**
 * Redraws one held word a letter at a time, each at its own swell and height.
 *
 * The word is cut between characters rather than between words, so a letter can
 * be scaled about its own centre without the ones either side of it coming
 * along. Each piece is clipped to where its letter is *going* rather than where
 * it sits: a glyph grown about its middle reaches past the box it was laid out
 * in, and clipping to that box would shave both sides off it as it swells.
 *
 * The overlap that buys — a letter's clip reaching a pixel or so into its
 * neighbour's — is why this is only ever run on a word that has earned it. Two
 * copies of a glyph edge a pixel apart is nothing on a letter mid-swell and
 * would be an obvious double image across a whole line.
 */
@Suppress("LongParameterList")
private fun ContentDrawScope.growEach(
    layout: TextLayoutResult,
    word: GrowingWord,
    line: LyricLine,
    positionMs: Long,
    visualLine: Int,
    start: Int,
    end: Int,
    top: Float,
    bottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    // The settle is shared with every other word: a letter comes to rest at the
    // same small lift, and then goes down with the rest of the line.
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    // Room to swell into, above the row rather than inside it. The pivot stays
    // on the row's own middle: scaling about the middle of the *band* would
    // walk every letter downwards as it grew.
    val ceiling = top - peak * GROW_HEADROOM
    val middle = (top + bottom) / 2f
    for (char in start until end) {
        word.sampleInto(char - first, positionMs, growth)
        val from = layout.xOn(char, visualLine, inset)
        val to = layout.xOn(char + 1, visualLine, inset)
        if (to <= from) continue
        val dx = growth.shift * em
        val dy = -growth.rise * peak * fall
        val overhang = (to - from) * (growth.scale - 1f) / 2f
        clipShiftedDown(
            left = from - overhang + dx,
            top = ceiling,
            right = to + overhang + dx,
            bottom = bottom,
            dy = dy,
        ) {
            translate(left = dx) {
                scale(growth.scale, growth.scale, Offset((from + to) / 2f, middle)) {
                    this@growEach.drawContent()
                }
            }
        }
    }
}

/**
 * Where an offset sits horizontally *on the row it was cut out of*.
 *
 * [TextLayoutResult.getHorizontalPosition] answers for the row the offset
 * itself belongs to — and the offset one past the last character of a wrapped
 * row belongs to the next row, so asking where a word that runs up to a wrap
 * *ends* gives a position at the far left, one row down. A slice cut between
 * there and the word's start is empty, and the walk then treats the row as
 * finished: everything from that word to the end of the row is never drawn.
 *
 * Whole rows disappeared that way, and Japanese lines disappeared most, because
 * Apple's word spans there are whole phrases and reach a wrap on their own where
 * an English word rarely does.
 */
/**
 * Where a character offset sits across a visual line, in pixels.
 *
 * For RTL paragraphs the first character of a visual line is at the right
 * edge and the last is at the left — this function respects that so callers
 * (horizontalAt, sweepTo) get correct clip boundaries regardless of text
 * direction.  Interior offsets delegate to Compose's own cursor-position
 * calculation which already handles direction; only the two boundary cases
 * need special handling because getLineLeft / getLineRight return physical
 * bounding-box edges (always left ≤ right), not paragraph-direction edges.
 */
private fun TextLayoutResult.xOn(offset: Int, visualLine: Int, inset: Float): Float {
    val lineStart = getLineStart(visualLine)
    val lineEndVisible = getLineEnd(visualLine, visibleEnd = true)
    val lineLeft = getLineLeft(visualLine) + inset
    val lineRight = getLineRight(visualLine) + inset

    val rtl = getParagraphDirection(lineStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) ==
              androidx.compose.ui.text.style.ResolvedTextDirection.Rtl

    return when {
        offset <= lineStart -> if (rtl) lineRight else lineLeft
        offset >= lineEndVisible -> if (rtl) lineLeft else lineRight
        // The inset belongs on every answer, not just the two edges: the rows
        // are drawn [inset] in from the layout's origin, and an interior cut
        // without it lands that far left of the glyph boundary — the lifted
        // and swelling slices then sliced letters in half.
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
            .coerceIn(lineLeft, lineRight)
    }
}

/** One piece of a line, clipped to its own width and drawn at its own height. */
private fun ContentDrawScope.sliceRisen(
    from: Float,
    top: Float,
    to: Float,
    bottom: Float,
    dy: Float,
) {
    if (to <= from) return
    clipShiftedDown(left = from, top = top, right = to, bottom = bottom, dy = dy) {
        this@sliceRisen.drawContent()
    }
}

/**
 * Where a fractional character index sits across a visual line, in pixels.
 *
 * Inside a timed piece — a syllable, or a word nobody split — that sits whole
 * on this row, the index is read as a share of the piece and laid across its
 * width. Read letter by letter instead, the edge crossed a "w" in the same time
 * as an "i": it sped up and slowed down inside every word, a wobble that a held
 * note made plain. am-lyrics wipes each syllable across its box at a constant
 * rate, and this is the same thing.
 */
private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
    spans: List<IntRange>,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
    // Use the *all* end (including trailing whitespace/punctuation) for the
    // boundary check — visibleEnd can exclude chars that sit at the physical
    // edge of an RTL line and would cause xOn to resolve to the next visual
    // line instead of the current one's far edge.
    val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
    val word = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (word != null && word.first >= lineStart && word.last + 1 <= lineEndVisible) {
        val from = layout.xOn(word.first, visualLine, 0f)
        val to = layout.xOn(word.last + 1, visualLine, 0f)
        return from + (to - from) * (chars - word.first) / (word.last + 1 - word.first)
    }
    // A word broken over a wrap, or the space between two words: letter by
    // letter, which is all a single space or a half-word needs.
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    // Row-aware at both ends: on the last character of a wrapped row the next
    // position belongs to the row below, and read straight it puts the edge
    // back at the left margin — the highlight jumped backwards a letter before
    // every wrap.  Guard against that by checking whether index+1 would land
    // on the next visual line; if so, clamp to this line's far edge instead.
    val here = layout.xOn(index, visualLine, 0f)
    val rawNext = index + 1
    val textLen = layout.layoutInput.text.length
    val spills: Boolean = layout.getLineForOffset(rawNext.coerceIn(0, textLen)) != visualLine
    val next: Float = if (spills) {
        // Spilled to the next visual line: use this line's far edge as the
        // interpolation target.  For RTL that is getLineLeft; for LTR it is
        // getLineRight.  The fallback below (xOn with coerceAtMost(lineEnd))
        // would return the wrong side for RTL.
        if (rawNext > lineEndVisible) {
            // At or past the visible end: use the all-end edge.
            val rtl = layout.getParagraphDirection(
                visualLine.coerceAtMost(lineEndAll).coerceIn(0, textLen - 1)
            ) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
            val farLeft = layout.getLineLeft(visualLine)
            val farRight = layout.getLineRight(visualLine)
            // For RTL, the "far" edge in character order is getLineLeft;
            // for LTR it is getLineRight.  We detect by comparing the two:
            if (rtl) farLeft else farRight
        } else {
            layout.xOn(rawNext.coerceAtMost(lineEndVisible), visualLine, 0f)
        }
    } else {
        layout.xOn(rawNext, visualLine, 0f)
    }
    return here + (next - here) * (chars - index)
}

/**
 * Draws this text clipped to its first [revealedChars] characters.
 *
 * Wrapped lines are handled a visual line at a time: the ones already passed
 * are drawn whole, the one holding the boundary is cut at it, and the rest are
 * left to the dim copy. Within a word the cut moves across the word's width
 * at a steady rate — see [horizontalAt] — rather than jumping a letter at a
 * time. [spans] are the line's timed pieces, [LyricLine.sweepSpans]; empty, the
 * cut goes letter by letter.
 *
 * The boundary itself is then feathered over the [WIPE_FEATHER] ahead of it
 * rather than left as the cut, which needs the caller to give this an offscreen layer to erase
 * into — see [PanelSweptLine]. Only the line actually being sung carries one;
 * everywhere else the boundary is at one end of the text or the other and
 * there is nothing to soften.
 */
private fun ContentDrawScope.sweepTo(
    layout: TextLayoutResult,
    revealedChars: Float,
    spans: List<IntRange>,
    feather: Boolean,
) {
    if (revealedChars <= 0f) return
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) {
        drawContent()
        return
    }
    // Derive RTL from the layout's own resolved paragraph direction, not from
    // text heuristics.  This keeps geometry in lock-step with how Compose
    // actually laid out the glyphs — critical when a translation line (Hebrew)
    // sits under an English lead and both get different text-analysis results.
    val offset = layout.getLineStart(0).coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(offset) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl

    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)

        // Lines whose character-range start is past the sweep boundary have
        // nothing lit; lines after that too.  Use `>` (not `>=`) so a line
        // whose *first* char is exactly at the boundary still gets drawn —
        // it is the boundary line and must show the feather edge.
        if (revealedChars <= lineStart) return

        val cut = revealedChars < lineEndAll
        val charPos = if (cut) {
            horizontalAt(layout, revealedChars, visualLine, spans)
        } else {
            // The sweep has passed this entire visual line.  Its boundary is
            // the *far* edge: right edge for RTL (lineLeft), left edge for LTR.
            if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        }

        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)

        // Clamp the sweep boundary so the clip rect is always valid and never
        // inverted near the line ends.  For RTL the revealed prefix [0,k) must
        // occupy [x(k), lineRight], with x(k) clamped inside [lineLeft, lineRight].
        val safeCharPos = charPos.coerceIn(lineLeft, lineRight)

        // The feather runs *ahead* of the boundary, into what is still to be
        // sung: lit solid up to now, fading out over the next [WIPE_FEATHER].
        // Trailing behind it instead, the letter being sung right now was the
        // dimmest part of the lit copy, and the line ran out of time with a
        // faded tail still on it and then snapped to full — which read as the
        // highlight being late. Ahead of it, the fade runs off the end of the
        // text as the last letter is reached, and the line finishes already
        // whole.
        //
        // Grown in over the first [WIPE_FEATHER] of each row rather than
        // opened at full width, or the first letter of every row would pop in
        // half-lit the frame the sweep reached it.
        val featherPx = if (feather && cut) {
            minOf(
                WIPE_FEATHER.toPx(),
                if (rtl) lineRight - safeCharPos else safeCharPos - lineLeft,
            )
        } else {
            0f
        }
        val lead = if (rtl) {
            (safeCharPos - featherPx).coerceAtLeast(lineLeft)
        } else {
            (safeCharPos + featherPx).coerceAtMost(lineRight)
        }

        // For RTL lines the sweep fills right-to-left: clip from the line's
        // right edge down to the feather's leading edge.  For LTR it fills
        // left-to-right as before.
        val (clipLeft, clipRight) = if (rtl) {
            lead to lineRight
        } else {
            lineLeft to lead
        }

        // Guard: an empty or inverted clip is a no-op; skip it cleanly.
        if (clipLeft >= clipRight) continue

        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        clipRect(
            left = clipLeft,
            top = top,
            right = clipRight,
            bottom = bottom,
        ) {
            this@sweepTo.drawContent()
        }

        // Only the visual line holding the boundary has an edge to soften; a
        // line revealed to its end runs into the wrap, which is not an edge.
        if (featherPx < 0.5f) continue

        // Scoped to this line's band so the mask cannot reach the lines above
        // and below it: DstIn erases whatever the source does not cover, and
        // outside the clip there is no source at all, so they are left alone.
        // Within it the brush clamps — opaque up to the boundary, gone past
        // the feather. The gradient keeps its full width even where the clip
        // is cut short by the end of the row, so the last letters keep fading
        // up instead of being cut off.
        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    1f to Color.Transparent,
                    startX = safeCharPos,
                    endX = if (rtl) safeCharPos - featherPx else safeCharPos + featherPx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}
