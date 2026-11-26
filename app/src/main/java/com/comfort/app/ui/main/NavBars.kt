package com.comfort.app.ui.main

// The navigation between Home, Library and Settings: the floating bar, and the side rail in wide windows.

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import compose.icons.feathericons.*

/** Side navigation for wide windows (tablets, phones in landscape) — same three destinations,
 * badges and tap behavior as [FloatingNavBar], which only suits a narrow portrait window. */
@Composable
internal fun SideNavRail(
    selectedTab: Int,
    activeDownloadsCount: Int,
    hasEngineUpdate: Boolean,
    onSelect: (Int) -> Unit,
) {
    val activeDownloadsDescription = pluralStringResource(R.plurals.active_downloads_count, activeDownloadsCount, activeDownloadsCount)
    val updatesAvailableDescription = stringResource(R.string.updates_available)
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.weight(1f))
        tabs.forEachIndexed { index, tab ->
            NavigationRailItem(
                selected = selectedTab == index,
                onClick = { onSelect(index) },
                icon = {
                    when {
                        index == 1 && activeDownloadsCount > 0 -> BadgedBox(badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics {
                                    contentDescription = activeDownloadsDescription
                                },
                            ) { Text(badgeCountText(activeDownloadsCount)) }
                        }) { Icon(tab.icon, contentDescription = tab.label) }
                        index == 2 && hasEngineUpdate -> BadgedBox(badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics {
                                    contentDescription = updatesAvailableDescription
                                },
                            )
                        }) { Icon(tab.icon, contentDescription = tab.label) }
                        else -> Icon(tab.icon, contentDescription = tab.label)
                    }
                },
                label = { Text(tab.label, fontFamily = com.comfort.app.theme.HeaderFontFamily) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The floating navigation bar: a pill in the theme's colour, the open tab's icon filled and
 * named in a lighter pill. That pill is one shape that slides from tab to tab, its front end first,
 * so it stretches as it goes and closes up on arrival (no overshoot, no wobble). */
@Composable
internal fun FloatingNavBar(
    selectedTab: Int,
    activeDownloadsCount: Int,
    hasEngineUpdate: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeDownloadsDescription = pluralStringResource(R.plurals.active_downloads_count, activeDownloadsCount, activeDownloadsCount)
    val updatesAvailableDescription = stringResource(R.string.updates_available)
    // Where each tab sits in the row (px), as laid out: the open one widens as its label shows.
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }
    // The pill's two ends, moved apart: the one in front gets there quickly, the one behind follows.
    // Both critically damped. As states, which follow a moving target (the widening tab) without
    // starting over.
    val previousTab = remember { androidx.compose.runtime.mutableIntStateOf(selectedTab) }
    val movingRight = remember(selectedTab) { selectedTab > previousTab.intValue }
    SideEffect { previousTab.intValue = selectedTab }
    val lead = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 700f)
    val trail = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 260f)
    // Hold and drag: while held, the pill follows the finger (x in the row, px) and the tab it's
    // let go over opens.
    var dragX by remember { mutableStateOf<Float?>(null) }
    // The tab under the finger while dragging.
    var dragTab by remember { mutableStateOf<Int?>(null) }
    // A tab's own tap still lands when a hold is let go without moving: this drops it, the release
    // having opened the tab already.
    var swallowClick by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The gesture below is set up once: it reads these at the release, not the values of its start.
    val currentTab by androidx.compose.runtime.rememberUpdatedState(selectedTab)
    val currentOnSelect by androidx.compose.runtime.rememberUpdatedState(onSelect)
    fun tabAt(x: Float): Int? = bounds.entries.minByOrNull { kotlin.math.abs(it.value.center.x - x) }?.key
    // Sized to the tab it's on: the one under the finger while dragging, which opens out to its name.
    val home = bounds[dragTab ?: selectedTab]
    val target = home?.let { t ->
        val x = dragX ?: return@let t
        val first = bounds[0]?.left ?: t.left
        val last = bounds[tabs.lastIndex]?.right ?: t.right
        val left = (x - t.width / 2f).coerceIn(first, (last - t.width).coerceAtLeast(first))
        androidx.compose.ui.geometry.Rect(left, t.top, left + t.width, t.bottom)
    }
    // Under the finger, both ends follow it quickly.
    val follow = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 1500f)
    // Only once laid out, so they start where the tab is rather than sliding in from the edge.
    val leftState = if (target != null) androidx.compose.animation.core.animateFloatAsState(target.left, if (dragX != null) follow else if (movingRight) trail else lead, label = "navLeft") else null
    val rightState = if (target != null) androidx.compose.animation.core.animateFloatAsState(target.right, if (dragX != null) follow else if (movingRight) lead else trail, label = "navRight") else null
    val indicatorColor = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Real system nav bar inset first (so the pill sits clear of it on every device), then
            // this bar's own fixed margin — see NAV_BAR_RESERVED_HEIGHT.
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Ends that flow into the sides (ContinuousCapsuleShape). Drawn in that shape, not clipped
        // to it (nothing inside reaches the ends): a clip to a path costs a mask every frame.
        Box(modifier = Modifier.fillMaxWidth().height(68.dp)) {
            // The bar's own shape, 28dp in from the tabs' space at each end: the tabs stay where
            // they were, the ends close in to about the highlight's gap above and below (11dp)
            // when the outer tab is open.
            Box(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 28.dp)
                    .shadow(12.dp, com.comfort.app.theme.ContinuousCapsuleShape, clip = false)
                    .background(MaterialTheme.colorScheme.primary, com.comfort.app.theme.ContinuousCapsuleShape)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos ->
                                swallowClick = true
                                dragX = pos.x
                                dragTab = tabAt(pos.x)
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragX = change.position.x
                                val over = tabAt(change.position.x)
                                if (over != dragTab) {
                                    dragTab = over
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                val over = dragTab
                                dragX = null
                                dragTab = null
                                swallowClick = false
                                if (over != null && over != currentTab) currentOnSelect(over)
                            },
                            onDragCancel = {
                                dragX = null
                                dragTab = null
                                swallowClick = false
                            },
                        )
                    }
                    // The one highlight pill, under the tabs: a plain rounded rectangle, no layer.
                    .drawBehind {
                        val l = leftState?.value ?: return@drawBehind
                        val r = rightState?.value ?: return@drawBehind
                        val t = bounds[selectedTab] ?: return@drawBehind
                        if (r <= l) return@drawBehind
                        drawRoundRect(
                            color = indicatorColor,
                            topLeft = androidx.compose.ui.geometry.Offset(l, t.top),
                            size = androidx.compose.ui.geometry.Size(r - l, t.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(t.height / 2f),
                        )
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    // While dragging, the tab under the finger is lit (colour, filled icon, its name).
                    val lit = (dragTab ?: selectedTab) == index
                    val tint by animateColorAsState(
                        targetValue = if (lit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                        animationSpec = tween(200),
                        label = "navTint",
                    )
                    Row(
                        modifier = Modifier
                            .onGloballyPositioned { c ->
                                val pos = c.positionInParent()
                                bounds[index] = androidx.compose.ui.geometry.Rect(pos.x, pos.y, pos.x + c.size.width, pos.y + c.size.height)
                            }
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                            }
                            // No ripple: the sliding pill answers the tap.
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                                onClickLabel = if (selected) {
                                    if (index == 1) stringResource(R.string.open_queue) else if (index == 2) stringResource(R.string.reset_settings) else null
                                } else stringResource(R.string.switch_to_x, tab.label),
                            ) {
                                // After a hold let go without moving: the tab still opens, but the open
                                // tab's second action (the queue, resetting Settings) doesn't run.
                                if (!swallowClick) onSelect(index) else { swallowClick = false; if (index != selectedTab) onSelect(index) }
                            }
                            .padding(horizontal = if (lit) 20.dp else 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = if (lit) tab.selectedIcon else tab.icon
                        val iconModifier = Modifier.size(22.dp)
                        if (index == 1 && activeDownloadsCount > 0) {
                            BadgedBox(badge = {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { contentDescription = activeDownloadsDescription },
                                ) { Text(badgeCountText(activeDownloadsCount)) }
                            }) { Icon(icon, contentDescription = tab.label, tint = tint, modifier = iconModifier) }
                        } else if (index == 2 && hasEngineUpdate) {
                            BadgedBox(badge = {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { contentDescription = updatesAvailableDescription },
                                )
                            }) { Icon(icon, contentDescription = tab.label, tint = tint, modifier = iconModifier) }
                        } else {
                            Icon(icon, contentDescription = tab.label, tint = tint, modifier = iconModifier)
                        }
                        // From its start, next to the icon (the default grew it from its end).
                        AnimatedVisibility(
                            visible = lit,
                            enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.Start) + androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.Start) + androidx.compose.animation.fadeOut(),
                        ) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Text(tab.label, color = tint, style = MaterialTheme.typography.labelLarge.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
