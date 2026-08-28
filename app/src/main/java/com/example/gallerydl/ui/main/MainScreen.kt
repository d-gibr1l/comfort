package com.example.gallerydl.ui.main

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import com.example.gallerydl.data.VideoSiteRouter
import com.example.gallerydl.theme.PillShape
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import com.example.gallerydl.viewmodel.DownloadsViewModel

private data class NavTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    NavTab("Home", FeatherIcons.Home),
    NavTab("Library", FeatherIcons.Image),
    NavTab("Settings", FeatherIcons.Settings),
)

// FloatingNavBar's own footprint: 16dp padding + 68dp pill + 16dp padding. Screens that now
// overlay it (instead of Scaffold reserving space for it) use this so their own scrollable
// content and floating buttons can still clear the pill instead of sitting behind it.
val NAV_BAR_RESERVED_HEIGHT = 100.dp


@Composable
fun MainScreen(viewModel: DownloadsViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(0) }
    var showQueueScreen by remember { mutableStateOf(false) }
    val hasActiveDownloads by viewModel.hasActiveDownloads.collectAsState()

    // Drives the predictive-back "peek" animation below — 0 = QueueScreen fully covering the
    // screen, 1 = fully swiped away. Tracks the gesture's live progress while a finger is down,
    // then either finishes the dismiss (gesture completed) or springs back to 0 (gesture
    // cancelled partway through a swipe).
    val queueDismissProgress = remember { Animatable(0f) }

    // PredictiveBackHandler (not plain BackHandler) so a system back gesture can be *previewed*
    // mid-swipe instead of only firing once fully committed — same "peek behind the current
    // screen as you drag" effect apps like Tachiyomi use, requires
    // android:enableOnBackInvokedCallback="true" in the manifest to actually animate rather than
    // fire instantly. A plain tap of QueueScreen's own back button (onBack below) skips the
    // gesture-progress dance entirely and just dismisses immediately, same as before.
    PredictiveBackHandler(enabled = showQueueScreen) { progress ->
        try {
            progress.collect { backEvent -> queueDismissProgress.snapTo(backEvent.progress) }
            // Gesture completed (finger lifted past the commit threshold) — finish the dismiss.
            showQueueScreen = false
            queueDismissProgress.snapTo(0f)
        } catch (e: CancellationException) {
            // Gesture cancelled (finger dragged back, or lifted too early) — spring the sheet
            // back to fully covering the screen instead of leaving it stuck mid-peek.
            queueDismissProgress.animateTo(0f, animationSpec = tween(200))
        }
    }

    // Back from a non-Home tab returns to Home first, matching standard bottom-nav behavior,
    // instead of immediately falling through to the empty nav backstack and quitting the app.
    // Only active when QueueScreen isn't up — its own PredictiveBackHandler above takes priority.
    BackHandler(enabled = !showQueueScreen && selectedTab != 0) { selectedTab = 0 }

    // Deliberately not Scaffold's own bottomBar slot: Scaffold reserves that whole slot's
    // measured region — pill height plus FloatingNavBar's own 24dp/16dp padding — and paints
    // containerColor behind all of it, not just the pill itself. That turned the padding around
    // the floating pill into a solid opaque block sitting on top of (and cutting off) whatever
    // list content would otherwise be visible there. Overlaying it on a plain Box instead lets
    // content scroll underneath the pill's transparent padding for real, which is what "floating"
    // is supposed to look like.
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (selectedTab) {
            0 -> HomeScreen(onDownload = { url -> viewModel.enqueueDownload(url, "Downloading from ${VideoSiteRouter.siteName(url)}") })
            1 -> DownloadsHistoryScreen(
                viewModel = viewModel,
                onOpenQueue = { showQueueScreen = true }
            )
            2 -> MoreScreen()
        }

        FloatingNavBar(
            selectedTab = selectedTab,
            hasActiveDownloads = hasActiveDownloads,
            onSelect = { index ->
                // Tapping the already-selected Library tab again jumps to the Queue, matching
                // the "tap again for more" pattern used elsewhere in the app.
                if (index == 1 && selectedTab == 1) {
                    showQueueScreen = true
                } else {
                    selectedTab = index
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Rendered on top of (not instead of) the tab content above, specifically so the tab
        // underneath is actually visible while mid-swipe — an early-return here (the previous
        // approach) would mean there's nothing behind QueueScreen to peek at during the gesture.
        if (showQueueScreen) {
            val progress = queueDismissProgress.value
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Shrinks and nudges toward the right/bottom as the gesture progresses —
                        // reveals the tab content already sitting underneath instead of just
                        // instantly vanishing once the gesture commits.
                        val scale = 1f - progress * 0.15f
                        scaleX = scale
                        scaleY = scale
                        translationX = size.width * 0.05f * progress
                        translationY = size.height * 0.03f * progress
                    }
                    .clip(RoundedCornerShape((progress * 28).dp))
            ) {
                QueueScreen(
                    viewModel = viewModel,
                    onBack = { showQueueScreen = false }
                )
            }
        }
    }
}

@Composable
private fun FloatingNavBar(
    selectedTab: Int,
    hasActiveDownloads: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read via graphicsLayer's deferred block below (not destructured with `by`), so this
    // continuous animation only re-triggers the settings icon's draw phase, not a recomposition
    // of the whole nav bar on every frame.
    val settingsRotation = rememberInfiniteTransition(label = "settingsSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "settingsRotation",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(68.dp),
            shape = PillShape,
            color = MaterialTheme.colorScheme.primary,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    val tint by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                        animationSpec = tween(200),
                        label = "navTint",
                    )
                    val bg by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        animationSpec = tween(200),
                        label = "navBg",
                    )

                    Row(
                        modifier = Modifier
                            .clip(PillShape)
                            .background(bg)
                            .clickable { onSelect(index) }
                            .padding(horizontal = if (selected) 20.dp else 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val iconModifier = Modifier
                            .size(22.dp)
                            .then(
                                if (index == 2) {
                                    Modifier.graphicsLayer {
                                        rotationZ = if (selected) settingsRotation.value else 0f
                                    }
                                } else {
                                    Modifier
                                }
                            )
                        if (index == 1 && hasActiveDownloads) {
                            BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = iconModifier)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = iconModifier)
                        }
                        AnimatedVisibility(visible = selected) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Text(tab.label, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Remembers the last clipboard link already offered/dismissed, across tab switches, so the
// suggestion banner doesn't keep nagging about the same copied link.
private object ClipboardSuggestionState {
    var lastHandled: String? = null
}

@Composable
fun HomeScreen(onDownload: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text?.trim()
        if (!clipText.isNullOrBlank() &&
            clipText != ClipboardSuggestionState.lastHandled &&
            android.util.Patterns.WEB_URL.matcher(clipText).matches()
        ) {
            clipboardSuggestion = clipText
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
                .statusBarsPadding()
                .padding(top = 32.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
        ) {
            Column {
                Text(
                    "gallery-dl",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Save from anywhere",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .offset(y = (-28).dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Paste a link") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        leadingIcon = { Icon(FeatherIcons.Link, contentDescription = null) },
                        trailingIcon = {
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { url = "" }) {
                                    Icon(FeatherIcons.X, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipText = clipboardManager.getText()?.text
                                if (!clipText.isNullOrBlank()) url = clipText
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(FeatherIcons.Clipboard, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Paste")
                        }

                        Button(
                            onClick = {
                                if (url.isNotBlank()) {
                                    onDownload(url)
                                    url = ""
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                            enabled = url.isNotBlank(),
                        ) {
                            Icon(FeatherIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Supported sources",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val sources = listOf(
                Triple("Twitter / X", FeatherIcons.Twitter, MaterialTheme.colorScheme.primary),
                Triple("Pixiv", FeatherIcons.Image, MaterialTheme.colorScheme.secondary),
                Triple("Instagram", FeatherIcons.Instagram, MaterialTheme.colorScheme.primary),
                Triple("+ hundreds more", FeatherIcons.Globe, MaterialTheme.colorScheme.secondary),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sources.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (name, icon, tint) ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(tint.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // Matches NAV_BAR_RESERVED_HEIGHT below — content needs to be able to scroll clear
            // of the floating pill now that it overlays on top instead of reserving its own
            // Scaffold-managed space (see MainScreen's own comment on that change).
            Spacer(Modifier.height(NAV_BAR_RESERVED_HEIGHT))
        }
    }

        clipboardSuggestion?.let { suggestion ->
            ExtendedFloatingActionButton(
                onClick = {
                    url = suggestion
                    ClipboardSuggestionState.lastHandled = suggestion
                    clipboardSuggestion = null
                },
                icon = { Icon(FeatherIcons.Clipboard, contentDescription = null) },
                text = { Text("Paste copied link") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = NAV_BAR_RESERVED_HEIGHT, end = 24.dp),
            )
        }
    }
}

