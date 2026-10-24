package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfort.app.R
import com.comfort.app.util.DownloadLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One line of the downloads' log, as the viewer shows it. */
private data class LogLine(val time: String, val id: String, val text: String, val kind: Kind) {
    enum class Kind { START, OUTCOME, ERROR, WARNING, DEBUG, PLAIN }
}

// "2026-10-24 15:30:37.070 4038863b <text>" — see DownloadLog.line.
private val LINE = Regex("""^\d{4}-\d\d-\d\d (\d\d:\d\d:\d\d)\.\d{3} (\S+) (.*)$""")

/** An engine's report line is JSON ({"type": "error", "message": ...}); the viewer shows its
 * message, coloured by its type. The worker's own lines ("start", "finished", "failed") are plain.
 * Lines logged before the 0x1E marker was dropped from the log still start with it. */
private fun parse(raw: String): LogLine {
    val m = LINE.matchEntire(raw) ?: return LogLine("", "", raw, LogLine.Kind.PLAIN)
    val time = m.groupValues[1]
    val id = m.groupValues[2]
    val rest = m.groupValues[3].trimStart('\u001E')
    val json = if (rest.startsWith("{")) runCatching { JSONObject(rest) }.getOrNull() else null
    if (json != null) {
        val type = json.optString("type")
        val text = json.optString("message").ifEmpty { json.optString("status").ifEmpty { rest } }.trimEnd()
        val kind = when {
            type == "error" -> LogLine.Kind.ERROR
            type == "warning" || text.contains("[warning]") || text.startsWith("WARNING") -> LogLine.Kind.WARNING
            type == "debug" -> LogLine.Kind.DEBUG
            else -> LogLine.Kind.PLAIN
        }
        return LogLine(time, id, if (type in setOf("error", "warning", "debug", "")) text else "$type: $text", kind)
    }
    val kind = when {
        rest.startsWith("start ") -> LogLine.Kind.START
        rest.startsWith("finished") || rest.startsWith("stopped") -> LogLine.Kind.OUTCOME
        // gallery-dl's own lines are plain text: "[download][error] ...".
        rest.startsWith("failed") || rest.startsWith("ERROR") || rest.contains("][error]") -> LogLine.Kind.ERROR
        rest.contains("[warning]") || rest.startsWith("WARNING") -> LogLine.Kind.WARNING
        else -> LogLine.Kind.PLAIN
    }
    return LogLine(time, id, rest, kind)
}

/** [lines] a download at a time, the latest download first, each one's lines in the order they
 * came: the sheet opens on the start of the latest. Two running at once wrote their lines into the
 * file mixed together; a line with no download (a stack trace's wrapped tail) stays with the one
 * before it. */
private fun latestDownloadFirst(lines: List<LogLine>): List<LogLine> {
    val groups = LinkedHashMap<String, MutableList<LogLine>>()
    var last = ""
    for (line in lines) {
        val key = line.id.ifEmpty { last }
        groups.getOrPut(key) { mutableListOf() } += line
        last = key
    }
    return groups.values.reversed().flatten()
}

/** Settings > Advanced > View logs: the downloads' log in a sheet, the latest download first
 * (where it opens), each from its bold "start <link>" line; errors are red, warnings tinted. A long
 * entry (a stack trace) shows its first lines until tapped. Pinch to zoom the text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogViewer(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<LogLine>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val zoom = rememberLogZoom()
    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.IO) { latestDownloadFirst(DownloadLog.read(context).map(::parse)) }
    }
    val listState = rememberLazyListState()

    // A flick down the list closed the whole sheet (seen live), two ways. The list ran up to its
    // top and the rest of the fling went to the sheet, which Material closes at ~125dp/s however
    // short the pull. Or the sheet was still moving (opening, or springing back) when the finger
    // came down, and Material then gives the gesture straight to the sheet, list or not. So a
    // gesture that started on the log lines may close the sheet only if listPullCloses says so:
    // how far it was pulled when let go, never the speed. The handle and title, Back and a tap
    // above the sheet close it as usual.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gestureOnList = remember { booleanArrayOf(false) }
    val listPullCloses = remember { booleanArrayOf(false) }
    // Where the sheet was as this gesture's finger came down: each pull is measured from there, so
    // flicks in quick succession (each starting before the last has sprung back) don't add up.
    val pullStartPx = remember { floatArrayOf(Float.NaN) }
    val sheetState = remember {
        SheetState(
            skipPartiallyExpanded = true,
            // A slow drag closes the sheet only past this far (Material's default is much shorter).
            positionalThreshold = { with(density) { 200.dp.toPx() } },
            velocityThreshold = { with(density) { 800.dp.toPx() } },
            initialValue = SheetValue.Hidden,
            confirmValueChange = { target -> target != SheetValue.Hidden || !gestureOnList[0] || listPullCloses[0] },
            skipHiddenState = false,
        )
    }
    // Where the sheet sits open, read whenever it comes to rest there.
    var openOffsetPx by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue to sheetState.isAnimationRunning }.collect { (value, animating) ->
            if (value == SheetValue.Expanded && !animating) {
                runCatching { sheetState.requireOffset() }.getOrNull()?.let { openOffsetPx = it }
                // Settled open: the gesture that moved it is over (Back or a tap above may follow).
                gestureOnList[0] = false
            }
        }
    }
    val closePullPx = with(density) { 200.dp.toPx() }
    // A drag past the list's top still pulls the sheet with the finger; on letting go, how far it was
    // pulled decides — past closePullPx it closes, short of it it springs back.
    val pullNotFling = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ) = if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                androidx.compose.ui.geometry.Offset.Zero // the drag: the sheet follows it
            } else available // the fling: kept from the sheet

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val offset = runCatching { sheetState.requireOffset() }.getOrNull()
                if (offset != null && !openOffsetPx.isNaN() && offset > openOffsetPx + 1f) {
                    val start = pullStartPx[0].takeUnless { it.isNaN() } ?: openOffsetPx
                    if (offset - start >= closePullPx) {
                        listPullCloses[0] = true
                        sheetState.hide()
                        onDismiss()
                    } else {
                        sheetState.expand()
                    }
                }
                return available
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // Tall, but a sheet: the page it opened from still shows above it.
        val height = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        // Each touch on the sheet first counts as not on the list; the list's own check below (which
        // sees it after this one) says otherwise.
        Column(Modifier.fillMaxWidth().height(height).onTouchDown { gestureOnList[0] = false }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmClear = true }, enabled = !lines.isNullOrEmpty()) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.clear_logs))
                }
            }
            val shown = lines
            when {
                shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize().onTouchDown {
                        gestureOnList[0] = true
                        pullStartPx[0] = runCatching { sheetState.requireOffset() }.getOrDefault(Float.NaN)
                    }.nestedScroll(pullNotFling).pinchToZoomText(zoom),
                ) {
                    itemsIndexed(shown) { index, line ->
                        LogRow(
                            line,
                            newDownload = line.kind == LogLine.Kind.START && index > 0,
                            zoom = zoom.floatValue,
                            expanded = expanded[index] == true,
                            onToggle = { expanded[index] = expanded[index] != true },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_logs_question)) },
            text = { Text(stringResource(R.string.clear_logs_description)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { DownloadLog.clear(context) }
                        lines = emptyList()
                    }
                }) { Text(stringResource(R.string.clear_logs)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** How many lines a long entry shows until it's tapped. */
private const val COLLAPSED_LINES = 3

@Composable
private fun LogRow(line: LogLine, newDownload: Boolean, zoom: Float, expanded: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (color, weight) = when (line.kind) {
        LogLine.Kind.START -> colors.primary to FontWeight.Bold
        LogLine.Kind.OUTCOME -> colors.primary to FontWeight.SemiBold
        LogLine.Kind.ERROR -> colors.error to FontWeight.Normal
        LogLine.Kind.WARNING -> colors.tertiary to FontWeight.Normal
        LogLine.Kind.DEBUG -> colors.onSurfaceVariant to FontWeight.Normal
        LogLine.Kind.PLAIN -> colors.onSurface to FontWeight.Normal
    }
    // A stack trace or a long debug dump: its first lines, the rest on a tap.
    val long = line.text.count { it == '\n' } >= COLLAPSED_LINES || line.text.length > 280
    if (newDownload) HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (line.kind == LogLine.Kind.ERROR) Modifier.background(colors.errorContainer.copy(alpha = 0.35f), RoundedCornerShape(6.dp)) else Modifier)
            .then(if (long) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = 2.dp, horizontal = 2.dp),
    ) {
        Text(
            line.time,
            fontFamily = FontFamily.Monospace,
            fontSize = (11 * zoom).sp,
            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            line.text,
            fontFamily = FontFamily.Monospace,
            fontSize = (12 * zoom).sp,
            lineHeight = (16 * zoom).sp,
            color = color,
            fontWeight = weight,
            maxLines = if (long && !expanded) COLLAPSED_LINES else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Calls [onDown] as each touch starts here, before anything inside handles it, without taking it. */
private fun Modifier.onTouchDown(onDown: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onDown()
    }
}

/** The log's text size, as a factor of the normal one: pinched bigger or smaller, and kept for
 * next time. */
@Composable
private fun rememberLogZoom(): MutableFloatState {
    val context = LocalContext.current
    return remember {
        mutableFloatStateOf(
            context.getSharedPreferences(ZOOM_PREFS, android.content.Context.MODE_PRIVATE).getFloat(ZOOM_KEY, 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        )
    }
}

/** Two fingers pinch the log bigger or smaller; one finger still scrolls it (only a two-finger
 * move is taken, before the list sees it). The same as the lyrics' (LyricsView). */
@Composable
private fun Modifier.pinchToZoomText(zoom: MutableFloatState): Modifier {
    val context = LocalContext.current
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinched = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val factor = event.calculateZoom()
                    if (factor != 1f) {
                        zoom.floatValue = (zoom.floatValue * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        pinched = true
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (pinched) {
                context.getSharedPreferences(ZOOM_PREFS, android.content.Context.MODE_PRIVATE).edit().putFloat(ZOOM_KEY, zoom.floatValue).apply()
            }
        }
    }
}

private const val ZOOM_PREFS = "log_viewer"
private const val ZOOM_KEY = "zoom"
private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 2f
