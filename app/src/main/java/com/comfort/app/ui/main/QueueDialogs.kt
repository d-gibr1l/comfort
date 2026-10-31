package com.comfort.app.ui.main

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import com.comfort.app.viewmodel.DownloadsViewModel
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.rememberIsNetworkAvailable
import com.comfort.app.util.rememberIsReducedMotionEnabled
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** DownloadEntity.errorDetails as (engine, message) pairs; empty when absent or unreadable. */
private fun parseEngineErrors(json: String?): List<Pair<String, String>> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = org.json.JSONArray(json)
        (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            entry.optString("engine") to entry.optString("message")
        }
    }.getOrDefault(emptyList())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ErrorDetailsSheet(
    item: com.comfort.app.data.DownloadEntity,
    onDismiss: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            // The download's name under the title, so it's clear which one failed.
            SheetHeader(
                icon = Icons.Outlined.Warning,
                title = stringResource(R.string.error_details),
                subtitle = item.title,
                tileColor = MaterialTheme.colorScheme.errorContainer,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            // Each engine's own error, in the order they ran (DownloadEntity.errorDetails) — the
            // card only shows one summary line, which could be a fallback engine's less relevant
            // message. Older failures have none: the summary is shown instead.
            val engineErrors = remember(item.errorDetails) { parseEngineErrors(item.errorDetails) }
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (engineErrors.isEmpty()) {
                    Text(
                        item.errorMessage ?: stringResource(R.string.unknown_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    engineErrors.forEach { (engine, message) ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(engine, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(4.dp))
                                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = engineErrors.takeIf { it.isNotEmpty() }
                                ?.joinToString("\n\n") { (engine, message) -> "$engine: $message" }
                                ?: item.errorMessage.orEmpty()
                            val clipData = android.content.ClipData.newPlainText(context.getString(R.string.error_message), text)
                            clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(clipData))
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                android.widget.Toast.makeText(context, context.getString(R.string.error_copied), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.copy)) }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/** "Waiting for a network connection" copy, shared by Home's notice and the Queue's Running tab:
 * how many downloads are held, and that Wi-Fi only is why when it's on. */
internal fun networkWaitSubtitle(context: android.content.Context, count: Int): String {
    val res = context.resources
    return if (com.comfort.app.data.GalleryDlPreferences.isWifiOnly(context)) res.getQuantityString(R.plurals.downloads_start_on_wifi, count, count)
    else res.getQuantityString(R.plurals.downloads_start_when_online, count, count)
}

/** A time today ("10:42"), or a date ("Oct 9") for anything older — in the phone's 12/24-hour style. */
internal fun shortWhen(context: android.content.Context, millis: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val today = java.util.Calendar.getInstance()
    val sameDay = then.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
        then.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
    else shortDateFormat().format(Date(millis))
}

// One "Oct 9" formatter for every queue card rather than a new one per card, rebuilt only if the
// language changes. SimpleDateFormat isn't thread-safe; it's shared only because shortWhen is
// called while composing, on the main thread.
private var shortDateFormatCache: Pair<Locale, SimpleDateFormat>? = null

private fun shortDateFormat(): SimpleDateFormat {
    val locale = Locale.getDefault()
    shortDateFormatCache?.let { (cachedLocale, format) -> if (cachedLocale == locale) return format }
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).also { shortDateFormatCache = locale to it }
}

/** A scheduled download's two lines: "Starts at 1:00 AM · in 3 h 20 min" (or "Starting soon"
 * once the window is open), and "Downloads run 1:00 AM–6:00 AM". Uses the same delay the
 * dispatcher waited on (DownloadDispatcher.scheduleDelayMillis). */
internal fun scheduleTexts(context: android.content.Context): Pair<String, String> {
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    fun clock(minutesOfDay: Int): String = timeFormat.format(
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            set(java.util.Calendar.MINUTE, minutesOfDay % 60)
        }.time,
    )
    val start = com.comfort.app.data.GalleryDlPreferences.getScheduleStartMinutes(context)
    val end = com.comfort.app.data.GalleryDlPreferences.getScheduleEndMinutes(context)
    val window = context.getString(R.string.downloads_run_x_x, clock(start), clock(end))
    val delay = com.comfort.app.data.DownloadDispatcher.scheduleDelayMillis(context)
    if (delay <= 0) return context.getString(R.string.starting_soon_the_window_is_open) to window
    val startsAt = timeFormat.format(Date(System.currentTimeMillis() + delay))
    return context.getString(R.string.starts_at_x_in_x, startsAt, inText(context, delay)) to window
}

/** "3 h 20 min" until something [delayMillis] away, rounded up to the minute. */
private fun inText(context: android.content.Context, delayMillis: Long): String {
    val minutes = (delayMillis + 59_999) / 60_000
    return when {
        minutes < 60 -> context.getString(R.string.x_min, minutes)
        minutes % 60 == 0L -> context.getString(R.string.x_h, minutes / 60)
        else -> context.getString(R.string.x_h_x_min, minutes / 60, minutes % 60)
    }
}

/** [scheduleTexts]' first line for a download with its own start time [at] ("Start at…"):
 * "Starts at 7:00 AM · in 9 h 10 min", or "Starting soon" once it's come. */
internal fun ownStartText(context: android.content.Context, at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= now) return context.getString(R.string.starting_soon)
    val startsAt = android.text.format.DateFormat.getTimeFormat(context).format(Date(at))
    return context.getString(R.string.starts_at_x_in_x, startsAt, inText(context, at - now))
}

/** The next time the clock shows [minutesOfDay] after [now]: later today, or tomorrow once it's
 * passed. Seconds at 0, so it starts on the minute picked. */
internal fun nextOccurrence(minutesOfDay: Int, now: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = now
    set(java.util.Calendar.HOUR_OF_DAY, minutesOfDay / 60)
    set(java.util.Calendar.MINUTE, minutesOfDay % 60)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
    if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

/** The Queue's "Start at…" on a waiting or paused download: a time of day on the same wheel as the
 * schedule window's (Settings › Downloads), for the next time it comes round — later today, or
 * tomorrow once it's passed — said underneath as it turns ("Starts at 7:00 AM · in 9 h 10 min").
 * [initial]: the time already set, if any (else the next full hour). [onRemove], when a time is
 * set: back to waiting for the window like the rest. */
@Composable
internal fun StartAtDialog(
    initial: Long?,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
    onRemove: (() -> Unit)?,
    /** A line under the title: what picking a time does here (a running download pauses). */
    note: String? = null,
) {
    val context = LocalContext.current
    // Seeded once, then only written by the wheel (re-seeding it would fight its scrolling).
    val startMinutes = remember {
        val c = java.util.Calendar.getInstance()
        if (initial != null && initial > System.currentTimeMillis()) {
            c.timeInMillis = initial
        } else {
            c.add(java.util.Calendar.HOUR_OF_DAY, 1)
            c.set(java.util.Calendar.MINUTE, 0)
        }
        c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }
    var minutes by remember { mutableStateOf(startMinutes) }
    val startTime = remember { kotlinx.datetime.LocalTime(hour = startMinutes / 60, minute = startMinutes % 60) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val summary = remember(minutes, now) { ownStartText(context, nextOccurrence(minutes, now), now) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.start_at), style = MaterialTheme.typography.titleMedium)
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                dev.darkokoa.datetimewheelpicker.WheelTimePicker(
                    modifier = Modifier.size(280.dp, 160.dp),
                    startTime = startTime,
                    // The phone's own 12/24-hour setting.
                    timeFormatter = dev.darkokoa.datetimewheelpicker.core.format.timeFormatter(
                        timeFormat = if (android.text.format.DateFormat.is24HourFormat(context)) {
                            dev.darkokoa.datetimewheelpicker.core.format.TimeFormat.HOUR_24
                        } else {
                            dev.darkokoa.datetimewheelpicker.core.format.TimeFormat.AM_PM
                        },
                    ),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    selectedTextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    onSnappedTime = { snapped -> minutes = snapped.hour * 60 + snapped.minute },
                )
                Spacer(Modifier.height(12.dp))
                Text(summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onRemove != null) {
                        TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_start_time)) }
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { onPicked(nextOccurrence(minutes, System.currentTimeMillis())) }) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}
