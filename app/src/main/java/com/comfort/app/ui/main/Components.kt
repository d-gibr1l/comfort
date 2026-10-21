package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Icon
import androidx.compose.material3.toShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [StatusBarScrim]'s opacity at its very top edge. Deliberately well below 1: content scrolling
 * under the status bar should still show through, just dimmed enough to keep the icons legible. */
private const val STATUS_BAR_SCRIM_PEAK_ALPHA = 0.6f

/** Status bar scrim for screens whose header scrolls away: a translucent tint behind the status bar,
 * fading into the content below. [alpha] is read in the draw phase (0 = hidden, 1 = fully shown) so callers can
 * tie it to their own header state without recomposing every frame.
 *
 * The fade is a cosine ease, not a linear ramp or a handful of hand-picked stops: its slope is zero
 * where it reaches full transparency, so there's no visible line where the scrim ends. It is also
 * drawn through a native Paint with dithering on, since a fade over a dark background is exactly
 * where 8-bit color steps show up as bands. */
@Composable
fun StatusBarScrim(alpha: () -> Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = 24.dp)))
            .graphicsLayer { this.alpha = alpha() }
            .drawWithCache {
                val steps = 32
                val rgb = color.toArgb() and 0x00FFFFFF
                val colors = IntArray(steps + 1)
                val positions = FloatArray(steps + 1)
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val a = STATUS_BAR_SCRIM_PEAK_ALPHA * 0.5f * (1f + cos(PI.toFloat() * t))
                    colors[i] = rgb or ((a * 255f).roundToInt() shl 24)
                    positions[i] = t
                }
                val paint = android.graphics.Paint().apply {
                    isDither = true
                    shader = android.graphics.LinearGradient(
                        0f, 0f, 0f, size.height, colors, positions, android.graphics.Shader.TileMode.CLAMP,
                    )
                }
                onDrawBehind {
                    drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                }
            },
    )
}

/** A finished/failed-download toast's own [SnackbarVisuals] — [isSuccess] is read back out in
 * [DownloadEventSnackbarHost] to pick the icon and color, since plain SnackbarHostState.showSnackbar
 * only carries a bare message string otherwise. */
data class DownloadEventVisuals(
    val isSuccess: Boolean,
    override val message: String,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = if (isSuccess) SnackbarDuration.Short else SnackbarDuration.Long
}

/** Every snackbar in the app (Library, the Download Queue, the media viewer): Material 3's
 * standard one — a dark inverse-surface bar (light in dark mode) with small corners, the message,
 * the action in inversePrimary and a ✕ — drawn here rather than with Snackbar() so it can also be
 * swiped away. */
@Composable
fun DownloadEventSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val visuals = data.visuals
        // A sideways swipe either way slides it off and dismisses it, the same as its ✕: past a
        // third of its width, or a quick flick; a short drag springs back.
        // Plain state set straight from each drag delta. (An Animatable snapped from a coroutine per
        // delta let a late snap cancel the slide-off animation after release — and the dismiss
        // that came after it — leaving the snackbar stuck part-way.)
        var swipeOffset by remember(data) { androidx.compose.runtime.mutableFloatStateOf(0f) }
        var swipeWidth by remember(data) { mutableStateOf(1f) }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // 12dp under it so it floats clear of whatever is below — on a Queue tab without
                // Retry All it otherwise sat flush on the navigation bar, reading as a white bar.
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                .onSizeChanged { swipeWidth = it.width.toFloat().coerceAtLeast(1f) }
                // Slides only, no fade: a half-faded snackbar let what's under it show through.
                .graphicsLayer { translationX = swipeOffset }
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta -> swipeOffset += delta },
                    onDragStopped = { velocity ->
                        val offset = swipeOffset
                        val flung = kotlin.math.abs(velocity) > 1500f && kotlin.math.sign(velocity) == kotlin.math.sign(offset)
                        if (kotlin.math.abs(offset) > swipeWidth / 3f || flung) {
                            val target = if (offset < 0f) -swipeWidth * 1.2f else swipeWidth * 1.2f
                            try {
                                androidx.compose.animation.core.animate(offset, target, animationSpec = androidx.compose.animation.core.tween(160)) { value, _ -> swipeOffset = value }
                            } finally {
                                data.dismiss()
                            }
                        } else {
                            androidx.compose.animation.core.animate(offset, 0f, animationSpec = androidx.compose.animation.core.spring()) { value, _ -> swipeOffset = value }
                        }
                    },
                ),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Capped however long the message is: error text is sanitized at the source
                // (GalleryDlListing.sanitizeErrorMessage), and this keeps it from ever filling the
                // screen anyway.
                Text(
                    visuals.message,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // Drawn here, not by Snackbar(): an actionLabel would otherwise have no button
                // (the Queue's delete-undo "Undo" once wasn't clickable because of exactly that).
                visuals.actionLabel?.let { actionLabel ->
                    TextButton(
                        onClick = { data.performAction() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    ) {
                        Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
                IconButton(onClick = { data.dismiss() }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The snackbar job each host is showing or waiting to show: [showReplacing] cancels it, so a new
 * snackbar replaces the current one instead of queueing behind it. Main thread only. */
private val currentSnackbarJobs = java.util.WeakHashMap<SnackbarHostState, kotlinx.coroutines.Job>()

/** Launches [show] (a showSnackbar call on [host]) in place of whatever [host] is showing or still
 * has waiting. SnackbarHostState shows one at a time and queues the rest, so after a burst (Retry
 * All, several deletes) each dismissed snackbar made an older one appear — reported as "old
 * snackbars keep appearing". A replaced delete-undo snackbar ends its Undo window (its finally
 * runs), the same as letting it time out. */
fun kotlinx.coroutines.CoroutineScope.showReplacing(host: SnackbarHostState, show: suspend () -> Unit): kotlinx.coroutines.Job {
    currentSnackbarJobs[host]?.cancel()
    val job = launch { show() }
    currentSnackbarJobs[host] = job
    job.invokeOnCompletion { if (currentSnackbarJobs[host] === job) currentSnackbarJobs.remove(host) }
    return job
}

/** Fires a [DownloadEventVisuals] snackbar for any download that finishes or fails while the
 * caller is actively composed — never for ones that were already finished/errored *before* this
 * call's first composition (each backing "seen" set starts at null specifically so the very first
 * emission from each flow only seeds a baseline to diff against, it never itself fires a toast).
 * Call once per screen that wants this (Library, the Download Queue), each with its own
 * [snackbarHostState] — the two calls don't share any state, so a download finishing while the
 * user is on Library doesn't also toast if they later open the Queue, or vice versa: whichever
 * screen is actually on screen at the moment a transition happens is the one that shows it, and
 * never again after that, on either screen. */
@Composable
fun DownloadEventSnackbars(
    historyItems: List<DownloadEntity>,
    queueItems: List<DownloadEntity>,
    snackbarHostState: SnackbarHostState,
) {
    val eventsContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var seenFinishedIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(historyItems) {
        val currentIds = historyItems.map { it.id }.toSet()
        val previous = seenFinishedIds
        if (previous != null) {
            val finished = (currentIds - previous).mapNotNull { id -> historyItems.firstOrNull { it.id == id } }
            // Several at once read as one snackbar ("3 downloads finished"), not a row of them.
            val message = when (finished.size) {
                0 -> null
                1 -> eventsContext.getString(R.string.download_finished_named, finished[0].title.ifBlank { eventsContext.getString(R.string.download_untitled) })
                else -> eventsContext.resources.getQuantityString(R.plurals.downloads_finished, finished.size, finished.size)
            }
            if (message != null) scope.showReplacing(snackbarHostState) {
                snackbarHostState.showSnackbar(DownloadEventVisuals(isSuccess = true, message = message))
            }
        }
        seenFinishedIds = currentIds
    }

    var seenErroredIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(queueItems) {
        val erroredNow = queueItems.filter { it.status == DownloadStatus.ERRORED }.map { it.id }.toSet()
        val previous = seenErroredIds
        if (previous != null) {
            val failed = (erroredNow - previous).mapNotNull { id -> queueItems.firstOrNull { it.id == id } }
            val message = when (failed.size) {
                0 -> null
                1 -> failed[0].title.ifBlank { eventsContext.getString(R.string.download_untitled) }.let { title ->
                    failed[0].errorMessage?.takeIf { it.isNotBlank() }
                        ?.let { eventsContext.getString(R.string.download_failed_named_reason, title, it) }
                        ?: eventsContext.getString(R.string.download_failed_named, title)
                }
                else -> eventsContext.resources.getQuantityString(R.plurals.downloads_failed, failed.size, failed.size)
            }
            if (message != null) scope.showReplacing(snackbarHostState) {
                snackbarHostState.showSnackbar(DownloadEventVisuals(isSuccess = false, message = message))
            }
        }
        seenErroredIds = erroredNow
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}

/** A rough ETA from remaining bytes ÷ current speed — same "estimate, not a promise" spirit as
 * yt-dlp/gallery-dl's own terminal ETA, which also jumps around as speed fluctuates rather than
 * settling into a smooth countdown. Caller is expected to only call this with a positive value. */
fun formatEta(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m left"
        m > 0 -> "${m}m ${s}s left"
        else -> "${s}s left"
    }
}

/** A small tinted pill for a queue card's site badge/format tags — a plain icon+text label read
 * as an afterthought floating in mostly-empty card space, not a deliberate piece of the layout. */
@Composable
fun InfoPill(shape: Shape = MaterialTheme.shapes.small, content: @Composable () -> Unit) {
    Surface(
        shape = shape,
        // A wash of onSurface over whatever card this sits on, not a fixed surfaceVariant: the
        // wallpaper (dynamic) schemes can make surfaceVariant nearly identical to the cards'
        // surfaceContainer, and the pills vanished (reported live on the Queue card). A tint of
        // the text color always shows against its own background, light or dark.
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            content = { content() },
        )
    }
}

/** The M3 Expressive "connected group" shape treatment for a row of adjacent chips/pills sitting
 * with a small gap between them: fully rounded on the group's own outer ends, a tighter corner on
 * the sides facing a neighbor — so the row reads as one grouped control rather than N separate
 * pills. `height` should match the real rendered height of the item (its outer radius is half of
 * it, i.e. a true pill cap); a lone item (count == 1) is just fully rounded on every corner. */
fun groupedChipShape(index: Int, count: Int, height: Dp = 32.dp): RoundedCornerShape {
    val outer = height / 2
    val inner = 8.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

/** [groupedChipShape], but with M3 Expressive's own signature move on a real ButtonGroup —
 * corners spring-animate on press/selection — built by hand with plain animateDpAsState rather
 * than the real ButtonGroup composable: that alpha API's actual Kotlin parameter names/order
 * aren't recoverable from this app's bundled material3 1.5.0-alpha18 without a cached sources jar
 * (see QueueScreen.kt's own comment on the same finding), so this reproduces just the shape-morph
 * *effect* with stable, ordinary Compose animation APIs instead of guessing blind at an unstable
 * one. Selected corners round out further (a "puffed" resting shape); pressed corners pull in
 * tighter (a "squish" the same bouncy spring un-squishes on release). */
@Composable
fun rememberMorphingChipShape(
    index: Int,
    count: Int,
    selected: Boolean,
    interactionSource: androidx.compose.foundation.interaction.InteractionSource,
    height: Dp = 32.dp,
): RoundedCornerShape {
    val pressed by interactionSource.collectIsPressedAsState()
    val restOuter = height / 2
    val targetOuter = if (pressed) restOuter * 0.55f else restOuter
    val targetInner = when {
        pressed -> 4.dp
        selected -> 14.dp
        else -> 8.dp
    }
    val animSpec = androidx.compose.animation.core.spring<Dp>(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
    )
    val outer by androidx.compose.animation.core.animateDpAsState(targetOuter, animSpec, label = "chipOuterCorner")
    val inner by androidx.compose.animation.core.animateDpAsState(targetInner, animSpec, label = "chipInnerCorner")
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A count badge's text: "99+" past two digits (Material's badge guidance), so the pill stays
 * compact next to the icon it's on — "123" was wide enough to be cut off at the Library header's
 * edge and to run over the nav bar's "Library" label. */
fun badgeCountText(count: Int): String = if (count > 99) "99+" else count.toString()

/** The download thumbnail shape — Material 3 Expressive's 4-sided cookie, for the Library list
 * and Queue thumbnails. One place, so every thumbnail stays the same shape. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun thumbnailShape(): Shape = androidx.compose.material3.MaterialShapes.Cookie4Sided.toShape()

/** Delete with an Undo window, shared by the Queue and the Library. hideForDeletion() only hides
 * the ids (reversible); the real, irreversible confirmDelete() runs once the Snackbar's Undo window
 * passes untouched — a delete straight from a tap, with no confirmation or undo, was flagged HIGH
 * in review. */
@Composable
fun rememberUndoableDelete(
    viewModel: com.comfort.app.viewmodel.DownloadsViewModel,
    snackbarHostState: SnackbarHostState,
): (Set<String>) -> Unit {
    val scope = rememberCoroutineScope()
    val removeContext = androidx.compose.ui.platform.LocalContext.current
    return { ids ->
        if (ids.isNotEmpty()) {
            viewModel.hideForDeletion(ids)
            scope.showReplacing(snackbarHostState) {
                var undone = false
                try {
                    val result = snackbarHostState.showSnackbar(
                        message = removeContext.resources.getQuantityString(R.plurals.downloads_removed, ids.size, ids.size),
                        actionLabel = removeContext.getString(R.string.undo),
                        duration = SnackbarDuration.Short,
                    )
                    undone = (result == androidx.compose.material3.SnackbarResult.ActionPerformed)
                } finally {
                    withContext(NonCancellable) {
                        if (undone) viewModel.cancelDeletion(ids) else viewModel.confirmDelete(ids)
                    }
                }
            }
        }
    }
}

/** Ends editing when the keyboard is dismissed (back gesture, the keyboard's own hide key) while
 * this field has focus: focus is cleared, so the cursor and the focused outline go away instead of
 * the field staying "active" with no keyboard. Per field rather than once per screen because a
 * sheet or dialog is its own window, with its own insets and focus. Only acts after the keyboard
 * was actually seen for this focus, so focusing a field doesn't immediately unfocus it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun Modifier.clearFocusOnKeyboardDismiss(): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    var keyboardShownSinceFocus by remember { mutableStateOf(false) }
    if (isFocused) {
        val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        LaunchedEffect(imeVisible) {
            if (imeVisible) {
                keyboardShownSinceFocus = true
            } else if (keyboardShownSinceFocus) {
                focusManager.clearFocus()
            }
        }
    }
    onFocusEvent { state ->
        if (isFocused != state.isFocused) {
            isFocused = state.isFocused
            if (isFocused) keyboardShownSinceFocus = false
        }
    }
}
