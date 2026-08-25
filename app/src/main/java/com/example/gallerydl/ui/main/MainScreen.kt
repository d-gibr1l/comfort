package com.example.gallerydl.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

@Composable
fun MainScreen(viewModel: DownloadsViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(0) }
    var showQueueScreen by remember { mutableStateOf(false) }
    val hasActiveDownloads by viewModel.hasActiveDownloads.collectAsState()

    BackHandler(enabled = showQueueScreen) { showQueueScreen = false }

    if (showQueueScreen) {
        QueueScreen(
            viewModel = viewModel,
            onBack = { showQueueScreen = false }
        )
        return
    }

    // Back from a non-Home tab returns to Home first, matching standard bottom-nav behavior,
    // instead of immediately falling through to the empty nav backstack and quitting the app.
    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            FloatingNavBar(
                selectedTab = selectedTab,
                hasActiveDownloads = hasActiveDownloads,
                onSelect = { index ->
                    // Tapping the already-selected Library tab again jumps to the Queue,
                    // matching the "tap again for more" pattern used elsewhere in the app.
                    if (index == 1 && selectedTab == 1) {
                        showQueueScreen = true
                    } else {
                        selectedTab = index
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()).fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeScreen(onDownload = { url -> viewModel.enqueueDownload(url, "Downloading $url") })
                1 -> DownloadsHistoryScreen(
                    viewModel = viewModel,
                    onOpenQueue = { showQueueScreen = true }
                )
                2 -> MoreScreen()
            }
        }
    }
}

@Composable
private fun FloatingNavBar(
    selectedTab: Int,
    hasActiveDownloads: Boolean,
    onSelect: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(68.dp),
            shape = PillShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        if (index == 1 && hasActiveDownloads) {
                            BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
                            }
                        } else {
                            Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
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
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
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

            Spacer(Modifier.height(24.dp))
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
                    .padding(24.dp),
            )
        }
    }
}

