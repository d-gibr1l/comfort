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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
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

private val ROW = 36.dp
private const val VISIBLE = 5 // rows shown: the picked one in the middle, two each side
// The wheels turn round: a long run of the values, started in its middle.
private const val LAPS = 200

/**
 * A time of day, picked on wheels (minutes since midnight, [minutes]): hours and minutes that turn
 * round like a drum — the picked row on a pill just behind the digits, the others tilting back,
 * shrinking and fading away from it, in Google Sans with even-width digits; with the phone on a
 * 12-hour clock, an AM/PM toggle under them. A tick as each value passes the middle. [onChange]
 * as soon as a wheel settles.
 */
@Composable
/** [amPm]: the 12-hour clock's AM/PM toggle under the wheels; false where the caller shows
 * [TimeWheelAmPm] itself (beside its buttons, to save height). */
internal fun TimeWheel(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, amPm: Boolean = true) {
    val context = LocalContext.current
    val twentyFour = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val hour = minutes / 60
    val minute = minutes % 60
    val latest by rememberUpdatedState(minutes)
    val latestChange by rememberUpdatedState(onChange)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
      // The pill behind the picked row: as wide as the wheels, no wider.
      Box(Modifier.height(ROW * VISIBLE), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                // A little taller than a row, for the picked time's bigger digits; the nav bar's
                // continuous capsule (ends flowing into the sides), not a plain round end.
                .padding(vertical = ROW * (VISIBLE / 2) - 9.dp)
                .clip(com.comfort.app.theme.ContinuousCapsuleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (twentyFour) {
                Wheel(count = 24, selected = hour, label = { "%02d".format(it) }, parse = { it.toIntOrNull()?.takeIf { n -> n in 0..23 } }) { h -> latestChange(h * 60 + latest % 60) }
            } else {
                val shown = (hour % 12).let { if (it == 0) 12 else it }
                Wheel(count = 12, selected = shown - 1, label = { (it + 1).toString() }, parse = { it.toIntOrNull()?.takeIf { n -> n in 1..12 }?.minus(1) }) { i ->
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
            Wheel(count = 60, selected = minute, label = { "%02d".format(it) }, parse = { it.toIntOrNull()?.takeIf { n -> n in 0..59 } }) { m -> latestChange(latest / 60 * 60 + m) }
        }
      }
            // AM / PM under the wheels, so the time itself sits in the middle.
            if (!twentyFour && amPm) {
                Spacer(Modifier.height(6.dp))
                AmPmToggle(pm = hour >= 12) { pm ->
                    val h = latest / 60 % 12 + if (pm) 12 else 0
                    latestChange(h * 60 + latest % 60)
                }
            }
    }
}

@Composable
/** [parse]: a typed number to the wheel's value (null if it isn't one): a tap on the picked
 * row turns it into a field to type the value in, set on Done or when the field is left. */
private fun Wheel(count: Int, selected: Int, label: (Int) -> String, parse: (String) -> Int?, width: Dp = 76.dp, onSelected: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
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
    Box(contentAlignment = Alignment.Center) {
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        modifier = Modifier.width(width).height(ROW * VISIBLE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(total) { i ->
            val distance by remember { derivedStateOf { rowDistance(state, i) } }
            val near = (1f - (distance / (VISIBLE / 2f + 0.5f))).coerceIn(0f, 1f)
            // Signed: above the middle (negative) tilts one way, below the other, as on a drum.
            val above by remember { derivedStateOf { i < centre } }
            Box(
                Modifier
                    .height(ROW)
                    .fillMaxWidth()
                    // The picked row: tap to type it.
                    .then(if (i == centre) Modifier.clickable(interactionSource = null, indication = null) { editing = true } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(i.mod(count)),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = HeaderFontFamily, fontFeatureSettings = "tnum"),
                    fontWeight = if (i == centre) FontWeight.Bold else FontWeight.Medium,
                    color = if (i == centre) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = 0.18f + 0.82f * near * near
                        // The picked row bigger (1.35×), its neighbours shrinking away from it.
                        val s = 0.5f + 0.85f * near * near
                        scaleX = s; scaleY = s
                        rotationX = (if (above) 1f else -1f) * (distance * 34f).coerceAtMost(70f)
                        cameraDistance = 12f * density
                    },
                )
            }
        }
    }
    if (editing) {
        // Typed over the picked row, on the same pill.
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(label(selected), androidx.compose.ui.text.TextRange(0, label(selected).length))) }
        var focused by remember { mutableStateOf(false) }
        fun commit() {
            parse(text.text.trim())?.let { if (it != selected) onSelected(it) }
            editing = false
        }
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { v -> if (v.text.length <= 2 && v.text.all(Char::isDigit)) text = v },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = HeaderFontFamily, fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onPrimaryContainer),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(width)
                .height(ROW)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .wrapContentHeight(Alignment.CenterVertically)
                .focusRequester(focus)
                .onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused },
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
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

/** The 12-hour clock's AM/PM toggle for [minutes], on its own (nothing on a 24-hour clock). */
@Composable
internal fun TimeWheelAmPm(minutes: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    if (remember { android.text.format.DateFormat.is24HourFormat(context) }) return
    val latest by rememberUpdatedState(minutes)
    AmPmToggle(pm = minutes / 60 >= 12) { pm -> onChange((latest / 60 % 12 + if (pm) 12 else 0) * 60 + latest % 60) }
}

/** AM / PM, side by side: the one in use filled, the fill sliding across as the nav bar's does
 * (its leading edge quick, its trailing one catching up). */
@Composable
private fun AmPmToggle(pm: Boolean, onChange: (Boolean) -> Unit) {
    val symbols = remember { java.text.DateFormatSymbols.getInstance().amPmStrings }
    val segment = 72f
    // FloatingNavBar's springs: the edge in front moves fast, the one behind follows slower.
    val lead = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 700f)
    val trail = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 260f)
    val left by androidx.compose.animation.core.animateFloatAsState(if (pm) segment else 0f, if (pm) trail else lead, label = "amPmLeft")
    val right by androidx.compose.animation.core.animateFloatAsState(if (pm) segment * 2 else segment, if (pm) lead else trail, label = "amPmRight")
    Box(
        Modifier.width((segment * 2).dp).height(36.dp)
            .clip(com.comfort.app.theme.ContinuousCapsuleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .offset(x = left.dp)
                .width((right - left).dp)
                .fillMaxHeight()
                .clip(com.comfort.app.theme.ContinuousCapsuleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Row {
            listOf(false, true).forEach { isPm ->
                val on = isPm == pm
                val color by androidx.compose.animation.animateColorAsState(
                    if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "amPmText",
                )
                Box(
                    Modifier
                        .width(segment.dp)
                        .fillMaxHeight()
                        .clip(com.comfort.app.theme.ContinuousCapsuleShape)
                        .clickable { if (!on) onChange(isPm) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        symbols[if (isPm) 1 else 0],
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = HeaderFontFamily),
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
            }
        }
    }
}
