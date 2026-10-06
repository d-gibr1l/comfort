package com.comfort.app.ui.main

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.CheckCircle as FilledCheckCircle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Building blocks shared by the Settings pages: headers, scaffold, sections, rows, sliders, sheets.

// Carries a sub-screen's "scroll to and flash this row" request down to whichever row actually
// matches it, without every row composable needing an explicit parameter threaded all the way
// down from its own screen's top — SettingsSection/IconToggleRow/ThemeToggleRow (AppearanceScreen)
// all just read this directly via highlightRowModifier() below. Not `private`: AppearanceScreen.kt
// (a separate file, same package) needs it too, and Kotlin's own same-package visibility means no
// import is needed either way.
class HighlightController(val targetKey: String?, val scrollState: ScrollState) {
    // Set once by the sub-screen's own root Column right after it's laid out — every row's own
    // target-scroll math below is relative to *this*, not the row's raw on-screen position, so it
    // stays correct regardless of how deep the row is nested (inside a SettingsSection's own Card,
    // itself inside the scrolling Column).
    var containerWindowY = 0f
}

val LocalHighlightState = compositionLocalOf<HighlightController?> { null }

/** Applied to a settings row's own outer Modifier — a no-op Modifier unless this row is the
 * ambient [LocalHighlightState]'s current target, in which case it scrolls the sub-screen's own
 * ScrollState to bring this row on-screen and flashes a brief background tint behind it. [title]
 * is matched with startsWith (not equals) since a couple of real row titles carry a dynamic suffix
 * the search index's own copy of that title can't predict (e.g. "Saved cookies (3)" for a target
 * key of "Saved cookies") — every actual title in SUBPAGE_SEARCH_INDEX is still specific enough
 * that this doesn't risk matching the wrong row. */
@Composable
fun highlightRowModifier(title: String): Modifier {
    val highlight = LocalHighlightState.current
    val targetKey = highlight?.targetKey
    if (highlight == null || targetKey == null || !title.startsWith(targetKey, ignoreCase = true)) return Modifier

    var rowWindowY by remember(highlight) { mutableStateOf<Float?>(null) }
    val flash = remember(highlight) { Animatable(0f) }

    LaunchedEffect(rowWindowY) {
        val y = rowWindowY ?: return@LaunchedEffect
        // A beat for the sub-screen's own enter transition (slide-in from Settings root) to finish
        // before scrolling — animating scroll position mid-transition read as a jarring double
        // motion when tested without this.
        delay(300)
        val target = (highlight.scrollState.value + (y - highlight.containerWindowY)).roundToInt().coerceAtLeast(0)
        highlight.scrollState.animateScrollTo(target)
        flash.animateTo(1f, tween(150))
        delay(450)
        flash.animateTo(0f, tween(600))
    }

    return Modifier
        .onGloballyPositioned { if (rowWindowY == null) rowWindowY = it.positionInWindow().y }
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = flash.value * 0.6f), RoundedCornerShape(14.dp))
}

// Shared by SettingsSubScaffold's pinned topBar, its own inline/collapsing variant, and the pinned
// "reappears once you scroll back up" overlay of that variant — all three want the exact same
// icon+title look, just with different top padding (a tall 40dp at rest, a tight 8dp once it's
// re-pinned after having scrolled away).
// Callers that AREN'T already inside a horizontally-padded container (the pinned topBar, and the
// floating re-pinned overlay) pass includeHorizontalPadding = true for their own 20dp side margin;
// the inline variant, living inside SettingsSubScaffold's own 20dp-padded Column, passes false so
// it doesn't end up with 40dp on each side.
// State for the "header scrolls away like ordinary content, then reappears compact and morphs back
// into its full size as you scroll the last collapseRangePx back to the top" behavior — shared by
// every Settings page's header (root included) so they all collapse/expand identically. See
// SettingsSubScaffold's own doc comment for why this is one continuously-interpolated instance
// rather than two separate composables crossfading against each other.
internal data class CollapsingHeaderState(
    val reveal: CompactHeaderReveal,
    val topPadding: androidx.compose.ui.unit.Dp,
    val bottomPadding: androidx.compose.ui.unit.Dp,
    val collapseFraction: Float,
    // Lazy lists only (see rememberLazyCollapsingHeaderState) — attach to an ancestor of the list.
    val nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection? = null,
)

/** How far a pinned compact header has slid up out of view, driven 1:1 by scroll distance instead
 * of a timed show/hide: scrolling toward the end pushes it up by exactly as many pixels as the
 * content moved, scrolling back pulls it down by the same amount, and it can rest part-way if the
 * finger stops there — so it "shows itself" at the pace of the scroll rather than snapping in.
 * Hidden amounts are clamped to the header's own measured height ([heightPx], fed by
 * [compactHeaderReveal]); [hide] parks it fully hidden even before that height is known. */
@androidx.compose.runtime.Stable
internal class CompactHeaderReveal {
    var heightPx by androidx.compose.runtime.mutableFloatStateOf(0f)
    private var rawHiddenPx by androidx.compose.runtime.mutableFloatStateOf(0f)
    val hiddenPx: Float get() = rawHiddenPx.coerceIn(0f, heightPx)
    /** 1 = fully shown, 0 = fully hidden. */
    val fraction: Float get() = when {
        heightPx > 0f -> 1f - hiddenPx / heightPx
        rawHiddenPx > 0f -> 0f
        else -> 1f
    }
    /** [deltaPx] > 0 = content scrolled toward its end (hides), < 0 = back toward the start (reveals). */
    fun scrollBy(deltaPx: Float) { rawHiddenPx = (hiddenPx + deltaPx).coerceIn(0f, heightPx) }
    fun show() { rawHiddenPx = 0f }
    fun hide() { rawHiddenPx = Float.POSITIVE_INFINITY }
}

/** Slides the header up by [reveal]'s hidden amount and reports its full size back to it. Place it
 * before any status-bar inset padding so the measured height includes the inset. Deliberately not
 * clipped at the status bar: while part-way, the header slides straight in over it (chosen live
 * over an emerge-from-under-the-status-bar variant). */
internal fun Modifier.compactHeaderReveal(reveal: CompactHeaderReveal): Modifier = this
    .graphicsLayer { translationY = -reveal.hiddenPx }
    .onSizeChanged { reveal.heightPx = it.height.toFloat() }

@Composable
internal fun rememberCollapsingHeaderState(
    scrollState: ScrollState,
    expandedTopPadding: androidx.compose.ui.unit.Dp,
    collapsedTopPadding: androidx.compose.ui.unit.Dp = 8.dp,
    expandedBottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    collapsedBottomPadding: androidx.compose.ui.unit.Dp = 8.dp,
): CollapsingHeaderState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 120dp, not the original 32dp — that was fine back when collapsing only meant a padding
    // change, but now that it also merges the back-button row into the title row and crossfades
    // the icon, 32dp of scroll (much less than a single normal scroll gesture) made that whole
    // transformation happen almost the instant a finger touched the list, reproduced live as an
    // abrupt collapse after barely any scroll at all. 120dp asks for a more deliberate scroll.
    val collapseRangePx = remember(density) { with(density) { 120.dp.toPx() } }
    val reveal = remember { CompactHeaderReveal() }
    LaunchedEffect(scrollState) {
        var previous = scrollState.value
        snapshotFlow { scrollState.value }.collect { current ->
            if (current <= collapseRangePx) reveal.show() else reveal.scrollBy((current - previous).toFloat())
            previous = current
        }
    }
    val collapseFraction = (scrollState.value / collapseRangePx).coerceIn(0f, 1f)
    return CollapsingHeaderState(
        reveal = reveal,
        topPadding = androidx.compose.ui.unit.lerp(expandedTopPadding, collapsedTopPadding, collapseFraction),
        bottomPadding = androidx.compose.ui.unit.lerp(expandedBottomPadding, collapsedBottomPadding, collapseFraction),
        collapseFraction = collapseFraction,
    )
}

/** [rememberCollapsingHeaderState] for a LazyColumn (the Download Queue) instead of a
 * verticalScroll Column. A LazyListState has no single running scroll total the way
 * ScrollState.value does, so this combines index and offset into one monotonic value (the index
 * weighted far above any single item's height) for the direction comparison, and reads the offset
 * alone while still on item 0 — exact there, which is the only range the collapse itself spans,
 * *provided item 0 is taller than the 120dp collapse range* (the Queue makes its header's reserved
 * space item 0 for exactly this; a shorter item 0 makes the collapse snap shut once it scrolls off).
 * That combined value jumps by ~1,000,000 whenever the index changes, though, so it can't supply
 * the pixel deltas the scroll-linked reveal needs; those come from [CollapsingHeaderState.nestedScrollConnection]
 * instead, which the caller attaches to an ancestor of the list. */
@Composable
internal fun rememberLazyCollapsingHeaderState(
    listState: androidx.compose.foundation.lazy.LazyListState,
    expandedTopPadding: androidx.compose.ui.unit.Dp,
    collapsedTopPadding: androidx.compose.ui.unit.Dp = 8.dp,
    expandedBottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    collapsedBottomPadding: androidx.compose.ui.unit.Dp = 8.dp,
): CollapsingHeaderState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapseRangePx = remember(density) { with(density) { 120.dp.toPx() } }
    val reveal = remember { CompactHeaderReveal() }
    fun nearTop() = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= collapseRangePx
    val connection = remember(listState, collapseRangePx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                // consumed.y < 0 means the content moved up, i.e. scrolled toward its end.
                if (nearTop()) reveal.show() else reveal.scrollBy(-consumed.y)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    // Programmatic jumps (e.g. scrollToItem(0) on a tab switch) never pass through nested scroll.
    LaunchedEffect(listState) {
        snapshotFlow { nearTop() }.collect { if (it) reveal.show() }
    }
    val collapseFraction = if (listState.firstVisibleItemIndex > 0) 1f
        else (listState.firstVisibleItemScrollOffset / collapseRangePx).coerceIn(0f, 1f)
    return CollapsingHeaderState(
        reveal = reveal,
        topPadding = androidx.compose.ui.unit.lerp(expandedTopPadding, collapsedTopPadding, collapseFraction),
        bottomPadding = androidx.compose.ui.unit.lerp(expandedBottomPadding, collapsedBottomPadding, collapseFraction),
        collapseFraction = collapseFraction,
        nestedScrollConnection = connection,
    )
}

/** The extra controls a Settings toggle reveals under itself (a slider, a size field, ...), expanding
 * open / collapsing shut instead of popping in and out. Collapses toward the toggle above it so the
 * content visibly folds back into it. With the OS reduce-motion setting on, it only fades, since the
 * height change is exactly the kind of movement that setting asks to avoid. */
@Composable
internal fun ColumnScope.ToggleReveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) fadeIn(tween(200)) else
            androidx.compose.animation.expandVertically(tween(250), expandFrom = Alignment.Top) + fadeIn(tween(250)),
        exit = if (reducedMotion) fadeOut(tween(150)) else
            androidx.compose.animation.shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(150)),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsSubPageHeader(
    title: String,
    topicIcon: ImageVector,
    onBack: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    includeHorizontalPadding: Boolean,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 8.dp,
    // 0 at rest (fully expanded), 1 once fully scrolled/collapsed — see SettingsSubScaffold's own
    // doc comment on CollapsingHeaderState. Continuous, not a discrete swap at some threshold: the
    // standalone back-button row above the title shrinks away exactly in step with this, and the
    // icon beside the title crossfades from the topic icon to the same back icon, so by the time
    // it's fully collapsed the compact bar is a single row — back icon, title, no space above it,
    // like every other Android app's compact app bar — with no separate "collapsed layout" to
    // jump-cut into (that's what caused the double-header ghosting fixed earlier).
    collapseFraction: Float = 0f,
) {
    val undoIcon = ImageVector.vectorResource(id = com.comfort.app.R.drawable.ic_undo)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (includeHorizontalPadding) Modifier.padding(horizontal = 20.dp) else Modifier)
            // Shrinks toward 0 as it collapses too, same reasoning as the back-button row below —
            // 8dp is what puts it right at the top like other apps at rest; a collapsed compact bar
            // shouldn't keep even that much air above it.
            .padding(top = androidx.compose.ui.unit.lerp(8.dp, 0.dp, collapseFraction), bottom = bottomPadding),
    ) {
        // Its own height (not just alpha) shrinks to 0 as collapseFraction approaches 1, so the
        // compact bar reclaims the space entirely instead of leaving it empty-but-reserved.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.ui.unit.lerp(40.dp, 0.dp, collapseFraction))
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { alpha = 1f - collapseFraction }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    undoIcon,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(topPadding))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    // Only clickable once it's visually closer to the back icon than the topic
                    // icon — the topic icon itself stays purely decorative at rest, same as before;
                    // this is a plain on/off flip on an already-invisible property (hit-testing),
                    // not a visual change, so there's no jump to smooth out here.
                    .then(
                        if (collapseFraction > 0.5f) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.foundation.LocalIndication.current,
                                onClick = onBack,
                            )
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Both icons occupy the exact same box, just crossfaded by the same collapseFraction
                // driving everything else here — unlike the double-header bug, there's no risk of
                // the two disagreeing on position, since they're literally stacked in one Box.
                Icon(
                    topicIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp).graphicsLayer { alpha = 1f - collapseFraction },
                )
                Icon(
                    undoIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp).graphicsLayer { alpha = collapseFraction },
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = com.comfort.app.theme.HeaderFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun SettingsSubScaffold(
    title: String,
    topicIcon: ImageVector,
    onBack: () -> Unit,
    // Non-null only when this screen was opened from a Settings-search result (see
    // SettingsRootScreen's subpage results list) — see HighlightController's own doc comment for
    // how a row actually consumes this.
    highlightKey: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val highlight = remember(highlightKey) { HighlightController(highlightKey, scrollState) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var maxHeaderHeightPx by remember { mutableStateOf(0) }
    // There's only ever one header composable here, not an inline copy plus a separate pinned one
    // that crossfades against it — that two-composable version was tried first and reliably showed
    // both at once for a moment (reproduced live as a double title ghost) because one was fading
    // out on its own timer while the other was simultaneously scrolling into view underneath it.
    // Instead this single instance is always pinned, and its own top/bottom padding is continuously
    // interpolated from scroll position while within the last collapseRangePx of the top — the
    // compact bar doesn't get replaced by the real header, it *grows into* it, exactly in step with
    // the finger, which is what "becomes the header" means here (same behavior on every sub-page).
    val headerState = rememberCollapsingHeaderState(scrollState, expandedTopPadding = 76.dp)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalHighlightState provides highlight) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // Reserves exactly the header's own resting (fully expanded) height so the
                    // first section starts right where it visually ends at scroll = 0 — the header
                    // itself is a pinned overlay below, entirely outside this Column, not one of
                    // its children.
                    .padding(top = with(density) { maxHeaderHeightPx.toDp() })
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarClearance())
                    .onGloballyPositioned { highlight.containerWindowY = it.positionInWindow().y },
                verticalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
        }
        StatusBarScrim(alpha = { 1f - headerState.reveal.fraction }, modifier = Modifier.align(Alignment.TopStart))
        SettingsSubPageHeader(
            title = title,
            topicIcon = topicIcon,
            onBack = onBack,
            topPadding = headerState.topPadding,
            bottomPadding = headerState.bottomPadding,
            collapseFraction = headerState.collapseFraction,
            includeHorizontalPadding = true,
            modifier = Modifier
                .align(Alignment.TopStart)
                .compactHeaderReveal(headerState.reveal)
                .background(MaterialTheme.colorScheme.background)
                // Measuring outside windowInsetsPadding, not inside it — inside, onSizeChanged only
                // sees the header's own topPadding+row+bottomPadding and never learns about the
                // status bar inset windowInsetsPadding adds beyond that, so the reserved space below
                // undercounted by exactly the status bar's height. Reproduced live: the first
                // section's own label (e.g. "SHARING") rendered a status-bar's-worth of pixels too
                // high, right underneath the opaque header.
                .onSizeChanged { maxHeaderHeightPx = maxOf(maxHeaderHeightPx, it.height) }
                .windowInsetsPadding(WindowInsets.statusBars),
        )
    }
}

/** Real ModalBottomSheet (not an AlertDialog) so this matches the rest of the app's own sheet-first
 * interaction language (DownloadPreviewSheet, the size-limit picker above, ...) instead of
 * introducing the one dialog-shaped confirmation in an app that otherwise never uses one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmDeleteSheet(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(confirmLabel) }
            }
        }
    }
}

@Composable
internal fun SettingsSection(title: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        // Google Sans Bold (the same face as each page's big title) at 14sp with a little tracking,
        // rather than the default 12sp semibold label — the section names read as too faint to
        // anchor each group (reported live). The icon scales with it.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = com.comfort.app.theme.HeaderFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 0.6.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().then(highlightRowModifier(title)),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            onClick = onClick ?: {},
            enabled = onClick != null,
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

// A toggle row with a leading icon-in-a-circle, matching SettingsListRow's top-level style —
// used for every individual switch setting within a section so the per-row icon convention holds
// at both levels, not just the top-level Appearance/Downloads/Advanced/... list.
@Composable
internal fun IconToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Compose's own Switch (unlike the platform's View-based SwitchCompat) doesn't call
    // performHapticFeedback internally at all — checked directly against this app's bundled
    // material3 1.5.0-alpha18 SwitchKt.class, no HapticFeedback reference anywhere in it. Every
    // toggle in Settings felt inert on tap without this.
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().then(highlightRowModifier(title)).padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No tinted circle behind this any more. onSurfaceVariant, not the bare onSurface the
        // circle version used — full onSurface is meant for primary content (titles/body text),
        // not a supporting row icon with nothing behind it to soften the contrast.
        // Several call sites pass a different icon depending on `checked` (Wifi/WifiOff, Speed/
        // Speed2, ...) — crossfade + scale pop between the two instead of a hard cut, so flipping
        // the switch reads as the icon itself changing state, not a jarring swap.
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(initialScale = 0.6f, animationSpec = tween(200)))
                    .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 0.6f, animationSpec = tween(150)))
            },
            label = "toggle-row-icon",
        ) { animatedIcon ->
            Icon(animatedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onCheckedChange(it)
            },
            // Default unchecked thumb color reads as near-invisible against the unchecked track
            // in this theme — an off toggle looked like a flat, dead pill rather than a working
            // control resting in its off position. onSurfaceVariant/surfaceVariant is M3's own
            // "always contrasts against its matching surface" pairing, so the thumb stays clearly
            // visible against the track regardless of light/dark theme — a plain alpha-dimmed
            // color wasn't enough contrast in this app's dark theme specifically.
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** An integer-valued Material 3 slider for a Settings row — [label] renders the current value
 * above the track (e.g. "4 at once"), snapping to whole numbers only (one step per integer in
 * [valueRange]). Used for Concurrent downloads/fragments and Retries, replacing what used to be a
 * fixed row of preset chips — a slider covers the whole range continuously instead of only the
 * handful of values a chip row could fit. */
@Composable
internal fun SettingsSlider(
    value: Int,
    valueRange: IntRange,
    label: (Int) -> String,
    onValueChange: (Int) -> Unit,
) {
    // A stored value can sit below valueRange.first — e.g. a fresh install's concurrentFragments
    // defaults to 1, but this slider (shown only while its own toggle is on) starts at 2, see the
    // Concurrent downloads/fragments call sites' own comments. Corrected immediately (not just
    // displayed clamped) so the actually-applied setting always matches what the slider shows —
    // without this, the label could read "2 at once" while the real stored value stayed 1, same
    // as toggle-off, silently doing nothing.
    LaunchedEffect(value, valueRange) {
        if (value !in valueRange) onValueChange(valueRange.first)
    }
    val displayValue = value.coerceIn(valueRange.first, valueRange.last)
    Text(
        label(displayValue),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    // Same gap as the Switch/toggle fixes above — Slider doesn't call performHapticFeedback
    // internally either. SegmentTick (not ToggleOn/Off) is the fitting one here: this is a
    // stepped, snap-to-integer slider, not a two-state control. Fired only on an actual step
    // change (tracked via lastTick), not on every pixel of drag the way a naive onValueChange
    // hook would — a continuous drag across a wide range would otherwise buzz constantly instead
    // of ticking once per whole number.
    val haptics = LocalHapticFeedback.current
    var lastTick by remember { mutableStateOf(displayValue) }
    Slider(
        value = displayValue.toFloat(),
        onValueChange = {
            val rounded = it.roundToInt().coerceIn(valueRange.first, valueRange.last)
            if (rounded != lastTick) {
                lastTick = rounded
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            onValueChange(rounded)
        },
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun StatusRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodySmall)
    }
}


// (label, suffix) pairs, e.g. "KB/s" -> "k". Order determines the trailing toggle's cycle order.
internal val SPEED_UNITS = listOf("KB/s" to "k", "MB/s" to "m")
internal val FILESIZE_UNITS = listOf("KB" to "k", "MB" to "m", "GB" to "g")

/** A field that opens a bottom sheet to edit a "number + suffix letter" value (e.g. "500k",
 * "2M") — the string format both Speed limit and Max file size share, and that both engines'
 * own config parsers accept directly. Blank/zero numeric input means unlimited, matching both
 * preferences' own convention. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SizeSheetField(
    modifier: Modifier = Modifier,
    label: String,
    currentValue: String,
    units: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    fun format(value: String): String = if (value.isBlank()) "Unlimited" else {
        val num = value.filter { it.isDigit() || it == '.' }
        val suffix = value.filter { it.isLetter() }.lowercase()
        val unitLabel = units.firstOrNull { it.second == suffix }?.first ?: units[0].first
        "$num $unitLabel"
    }
    val displayValue = format(currentValue)

    OutlinedButton(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        onClick = { showSheet = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayValue, style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showSheet) {
        // Seeded once from the committed value when the sheet opens, then only ever written by
        // the fields below — re-deriving from currentValue on every recomposition would fight
        // whatever the user is mid-typing.
        var numberText by remember { mutableStateOf(currentValue.filter { it.isDigit() || it == '.' }) }
        var unitIndex by remember {
            mutableStateOf(
                units.indexOfFirst { (_, suffix) -> currentValue.trim().endsWith(suffix, ignoreCase = true) }
                    .takeIf { it >= 0 } ?: 0
            )
        }
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = numberText,
                    onValueChange = { numberText = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Unlimited") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(12.dp))
                // A plain chip row instead of a DropdownMenu — a popup nested inside a
                // ModalBottomSheet's own popup dismisses BOTH on tap (reproduced live: tapping
                // the unit dropdown closed the whole sheet instead of opening the menu), so this
                // sidesteps that Compose nested-popup bug entirely rather than working around it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    units.forEachIndexed { index, (unitLabel, _) ->
                        val selected = unitIndex == index
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            onClick = { unitIndex = index },
                        ) {
                            Box(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(unitLabel)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                val commitTyped = {
                    onValueChange(if (numberText.isBlank()) "" else "$numberText${units[unitIndex].second}")
                    showSheet = false
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ConfirmCancelSplitButton(
                        label = "Done",
                        icon = Icons.Filled.FilledCheckCircle,
                        onConfirm = commitTyped,
                        onCancel = { showSheet = false },
                    )
                }
            }
        }
    }
}

/** M3 Expressive split button: the leading half confirms ([label] — Done, Save, Start now, ...),
 * the trailing half is a separate secondary action, a cancel ✕ by default. Default Small (40dp)
 * size — Medium read as oversized (reported live). Shared by Settings' edit sheets and the
 * Queue's waiting-download cards. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConfirmCancelSplitButton(
    label: String,
    icon: ImageVector,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    cancelDescription: String = "Cancel",
    // Every split button's look (settled live on the Queue cards, then applied everywhere): the
    // confirm half outline-only, the ✕ half in the light green of a selected filter chip.
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ),
    outlinedConfirm: Boolean = true,
) {
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            val content: @Composable RowScope.() -> Unit = {
                Icon(icon, contentDescription = null, modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
            if (outlinedConfirm) {
                SplitButtonDefaults.OutlinedLeadingButton(onClick = onConfirm, enabled = confirmEnabled, content = content)
            } else {
                SplitButtonDefaults.LeadingButton(onClick = onConfirm, enabled = confirmEnabled, colors = colors, content = content)
            }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(onClick = onCancel, colors = colors) {
                Icon(Icons.Outlined.Close, contentDescription = cancelDescription, modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize))
            }
        },
    )
}
