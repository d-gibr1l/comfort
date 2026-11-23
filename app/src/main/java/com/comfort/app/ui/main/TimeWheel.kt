package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.comfort.app.theme.HeaderFontFamily
import kotlin.math.abs

private val ROW = 56.dp
private const val VISIBLE = 5 // rows shown: the picked one in the middle, two each side
// The wheels turn round: a long run of the values, started in its middle.
private const val LAPS = 200

/**
 * A time of day, picked on wheels (minutes since midnight, [minutes]): hours and minutes that turn
 * round, the picked row on a tinted band in the middle, the others fading and shrinking away from
 * it, in Google Sans; with the phone on a 12-hour clock, an AM/PM toggle beside them. A tick as
 * each value passes the middle. [onChange] as soon as a wheel settles.
 */
@Composable
internal fun TimeWheel(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val twentyFour = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val hour = minutes / 60
    val minute = minutes % 60
    val latest by rememberUpdatedState(minutes)
    val latestChange by rememberUpdatedState(onChange)

    Box(modifier.height(ROW * VISIBLE), contentAlignment = Alignment.Center) {
        // The band behind the picked row, across both wheels.
        Box(
            Modifier
                .fillMaxWidth()
                .height(ROW)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (twentyFour) {
                Wheel(count = 24, selected = hour, label = { "%02d".format(it) }) { h -> latestChange(h * 60 + latest % 60) }
            } else {
                val shown = (hour % 12).let { if (it == 0) 12 else it }
                Wheel(count = 12, selected = shown - 1, label = { (it + 1).toString() }) { i ->
                    val h12 = (i + 1) % 12 // 12 → 0
                    val pm = latest / 60 >= 12
                    latestChange((h12 + if (pm) 12 else 0) * 60 + latest % 60)
                }
            }
            Text(
                ":",
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = HeaderFontFamily),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Wheel(count = 60, selected = minute, label = { "%02d".format(it) }) { m -> latestChange(latest / 60 * 60 + m) }
            if (!twentyFour) {
                Spacer(Modifier.width(16.dp))
                AmPmToggle(pm = hour >= 12) { pm ->
                    val h = latest / 60 % 12 + if (pm) 12 else 0
                    latestChange(h * 60 + latest % 60)
                }
            }
        }
    }
}

@Composable
private fun Wheel(count: Int, selected: Int, label: (Int) -> String, width: Dp = 84.dp, onSelected: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val total = count * LAPS
    // The middle lap, at [selected]: the row shown in the middle is the list's first visible
    // one + VISIBLE / 2, so it starts that far before it.
    val start = count * (LAPS / 2) + selected - VISIBLE / 2
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val centre by remember { derivedStateOf { centreIndex(state) } }
    // A tick as each value passes the middle; the value, once the wheel stops.
    val firstCentre = remember { intArrayOf(-1) }
    LaunchedEffect(centre) {
        // Not for the value it opens on.
        if (firstCentre[0] == -1) firstCentre[0] = centre
        else haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            val value = centre.mod(count)
            if (value != selected) onSelected(value)
        }
    }
    // Turned to a new value from outside (a quick-pick chip): the wheel goes there.
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress && centre.mod(count) != selected) {
            val diff = ((selected - centre.mod(count)) % count + count) % count
            val step = if (diff > count / 2) diff - count else diff
            state.animateScrollToItem((state.firstVisibleItemIndex + step).coerceIn(0, total - 1))
        }
    }
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        modifier = Modifier.width(width).height(ROW * VISIBLE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(total) { i ->
            val distance by remember { derivedStateOf { rowDistance(state, i) } }
            val near = (1f - (distance / (VISIBLE / 2f + 0.5f))).coerceIn(0f, 1f)
            Box(Modifier.height(ROW).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    label(i.mod(count)),
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = HeaderFontFamily),
                    fontWeight = if (i == centre) FontWeight.Bold else FontWeight.Medium,
                    color = if (i == centre) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = 0.25f + 0.75f * near * near
                        val s = 0.7f + 0.3f * near
                        scaleX = s; scaleY = s
                    },
                )
            }
        }
    }
}

/** The item under the middle of the wheel. */
private fun centreIndex(state: LazyListState): Int {
    val info = state.layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index ?: (state.firstVisibleItemIndex + VISIBLE / 2)
}

/** How many rows [index] is from the middle (fractional while turning). */
private fun rowDistance(state: LazyListState, index: Int): Float {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return VISIBLE.toFloat()
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return abs(item.offset + item.size / 2f - middle) / item.size.coerceAtLeast(1)
}

/** AM / PM, stacked: the one in use filled. */
@Composable
private fun AmPmToggle(pm: Boolean, onChange: (Boolean) -> Unit) {
    val symbols = remember { java.text.DateFormatSymbols.getInstance().amPmStrings }
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        listOf(false, true).forEach { isPm ->
            val on = isPm == pm
            Box(
                Modifier
                    .width(64.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { if (!on) onChange(isPm) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    symbols[if (isPm) 1 else 0],
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily),
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
