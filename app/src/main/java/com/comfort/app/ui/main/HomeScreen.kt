package com.comfort.app.ui.main

// The Home tab: the link field, Browse the web, supported sources, recent downloads and tips.

import androidx.compose.ui.res.stringResource
import com.comfort.app.viewmodel.startedMessage
import com.comfort.app.viewmodel.LinkRouterViewModel
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comfort.app.data.DownloadStatus
import compose.icons.FeatherIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.launch
import compose.icons.feathericons.*
import com.comfort.app.viewmodel.DownloadsViewModel

// Remembers the last clipboard link already offered/dismissed, across tab switches, so the
// suggestion banner doesn't keep nagging about the same copied link.
private object ClipboardSuggestionState {
    var lastHandled: String? = null
}

@Composable
fun HomeScreen(
    // Opens the preview/picker sheets (per-download quality, items, trim, ...) for the link.
    onConfigure: (String) -> Unit,
    viewModel: DownloadsViewModel,
    onOpenLibrary: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    // Bumped by the "Paste & download" shortcut.
    pasteDownloadSignal: Int = 0,
    // Whether Home is the page on screen. It stays composed under the other pages (they reveal it
    // on back), and its strip's auto-scroll laid it out every frame there too: a page switch ran
    // on top of that the whole time (choppy, measured: ~70 frames a second with nothing moving).
    active: () -> Boolean = { true },
) {
    var url by remember { mutableStateOf("") }
    // The in-app browser (WebBrowser), open at this address; null when closed.
    var browserUrl by remember { mutableStateOf<String?>(null) }
    // YouTube search (YouTubeSearchScreen), open for this query; null when closed.
    var youtubeSearch by remember { mutableStateOf<String?>(null) }
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val router: LinkRouterViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    // Download: starts right away with the default settings — the same thing sharing a link into
    // the "Instant" Sharesheet entry does (ShareActivity's InstantShareHandler), duplicate handling
    // and message included. Configure is the other button.
    fun downloadNow(link: String) {
        router.download(link) { result ->
            android.widget.Toast.makeText(homeContext, startedMessage(homeContext, result), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val homeScope = rememberCoroutineScope()
    suspend fun readClipboardText(): String? {
        val clip = clipboard.getClipEntry() ?: return null
        return if (clip.clipData.itemCount > 0) clip.clipData.getItemAt(0)?.text?.toString()?.trim() else null
    }
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    val queueItems by viewModel.queueFlow.collectAsStateWithLifecycle()
    val historyItems by viewModel.historyFlow.collectAsStateWithLifecycle()
    // Most-recent RUNNING item — a summary screen only ever needs to surface one at a time; the
    // full Queue is one tap away (onOpenQueue) for anything more than that.
    val activeDownload = remember(queueItems) { queueItems.firstOrNull { it.status == DownloadStatus.RUNNING } }
    // Pre-seeded from RecentDownloadsCache on Frame 1 to prevent content layout shift and pop-in.
    val cachedRecent = remember { com.comfort.app.data.RecentDownloadsCache.get(homeContext) }
    val recentDownloads = remember(historyItems, cachedRecent) {
        if (historyItems.isNotEmpty()) {
            val filtered = historyItems.filter { !it.thumbnailPath.isNullOrBlank() }.take(10)
            com.comfort.app.data.RecentDownloadsCache.save(homeContext, filtered)
            filtered
        } else {
            cachedRecent
        }
    }

    fun normalizeInputUrl(input: String): String {
        val trimmed = input.trim()
        return if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            trimmed.contains('.') &&
            !trimmed.contains(' ')
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    // "Paste & download" shortcut: download the copied link straight away. Android only lets an
    // app read the clipboard while its window has focus, so this waits for that first.
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(pasteDownloadSignal) {
        if (pasteDownloadSignal == 0) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        val clipText = readClipboardText()
        if (!clipText.isNullOrBlank() && android.util.Patterns.WEB_URL.matcher(clipText).matches()) {
            val target = normalizeInputUrl(clipText)
            ClipboardSuggestionState.lastHandled = target
            clipboardSuggestion = null
            downloadNow(target)
        } else {
            android.widget.Toast.makeText(homeContext, homeContext.getString(R.string.no_link_copied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        val clipText = readClipboardText()
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
        // No background of its own: it scrolls with Home, nothing goes under it, and under it is
        // the activity's Surface in the same colour.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 12.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
        ) {
            // A big, chunky wordmark instead of a name + tagline pair — no separate app-name
            // caption above it (there's nothing to disambiguate it from anymore) and no subtitle
            // below, just the logo standing on its own the way a launcher icon does. Manually
            // shrinks to fit (this Compose Foundation version doesn't have the newer built-in
            // autoSize) rather than a guessed fixed fontSize — grows/shrinks to actually fill the
            // available width edge-to-edge like the reference image, on any screen width, instead
            // of only being right on one specific device.
            val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            // Pre-computed target size (availableWidth ~ screenWidth - 96dp across 7 glyphs) so COMFORT
            // renders at full opacity on Frame 1 without being hidden or looping font-size decrements.
            val initialFontSize = remember(screenWidth, fontScale) {
                ((screenWidth - 96.coerceAtMost(screenWidth - 100)) / 5.5f).coerceIn(24f, 68f).sp
            }
            var wordmarkFontSize by remember(screenWidth, fontScale) { mutableStateOf(initialFontSize) }
            BasicText(
                "COMFORT",
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = CrystalRadioKit,
                    textAlign = TextAlign.Center,
                    fontSize = wordmarkFontSize,
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                onTextLayout = { result ->
                    if (result.didOverflowWidth && wordmarkFontSize > 12.sp) {
                        val next = wordmarkFontSize * 0.94f
                        wordmarkFontSize = if (next > 12.sp) next else 12.sp
                    }
                },
            )
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
                    // Text that isn't a link is a YouTube search: the buttons below become one
                    // "Search YouTube", and the keyboard's action searches.
                    val isSearch = url.isNotBlank() && !looksLikeLink(url)
                    // The field lets go of focus when a sheet opens over it: still focused under
                    // the sheet, it brought the keyboard back up as the sheet closed.
                    val fieldFocus = androidx.compose.ui.platform.LocalFocusManager.current
                    fun openSearch() {
                        if (!isSearch) return
                        youtubeSearch = url.trim()
                        url = ""
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        // A plain placeholder (no floating label) so the field reads as one
                        // resting pill with "Paste a link" sitting centered inside it, rather than
                        // a caption perched above the field's own border.
                        placeholder = { Text(androidx.compose.ui.res.stringResource(com.comfort.app.R.string.home_link_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                        shape = MaterialTheme.shapes.extraLarge,
                        leadingIcon = {
                            androidx.compose.animation.Crossfade(targetState = isSearch, label = "fieldIcon") { search ->
                                Icon(if (search) Icons.Outlined.Search else Icons.Outlined.Link, contentDescription = null)
                            }
                        },
                        // One action whatever's typed. It used to switch from Done to Search as the
                        // first letter made the text a search, and changing it restarts the
                        // keyboard's connection to the field mid-word: Samsung's keyboard kept
                        // typing into the old one, so after "h" nothing more arrived (reproduced).
                        // Its key searches a search; for a link it just closes the keyboard.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            if (isSearch) openSearch() else defaultKeyboardAction(androidx.compose.ui.text.input.ImeAction.Done)
                        }),
                        trailingIcon = {
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { url = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_link))
                                }
                            }
                        },
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Turning into a search, Configure slides away while Download widens into
                    // "Search YouTube" (its icon and label crossfading), and back again.
                    val configureShare by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isSearch) 0f else 1f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        ),
                        label = "configureShare",
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (configureShare > 0.001f) {
                            OutlinedButton(
                                onClick = {
                                    if (url.isNotBlank() && !isSearch) {
                                        val target = normalizeInputUrl(url)
                                        fieldFocus.clearFocus()
                                        onConfigure(target)
                                        url = ""
                                    }
                                },
                                modifier = Modifier
                                    .weight(configureShare)
                                    .height(52.dp)
                                    .graphicsLayer { alpha = configureShare },
                                shape = MaterialTheme.shapes.extraLarge,
                                // Not switched off for a search: it greyed out at once, then faded. Its
                                // tap does nothing then (onClick), and it's gone a moment later.
                                enabled = url.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.configure), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                            }
                            Spacer(Modifier.width(12.dp * configureShare))
                        }

                        Button(
                            onClick = {
                                if (isSearch) {
                                    openSearch()
                                } else if (url.isNotBlank()) {
                                    val target = normalizeInputUrl(url)
                                    fieldFocus.clearFocus()
                                    downloadNow(target)
                                    url = ""
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            enabled = url.isNotBlank(),
                        ) {
                            androidx.compose.animation.AnimatedContent(
                                targetState = isSearch,
                                transitionSpec = {
                                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) +
                                        androidx.compose.animation.scaleIn(initialScale = 0.9f, animationSpec = androidx.compose.animation.core.tween(220, delayMillis = 60))) togetherWith
                                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) using
                                        androidx.compose.animation.SizeTransform(clip = false)
                                },
                                label = "primaryAction",
                            ) { search ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (search) Icons.Outlined.Search else Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (search) androidx.compose.ui.res.stringResource(com.comfort.app.R.string.home_search_youtube) else stringResource(R.string.search_download),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Browse: for sites the engines don't know — the browser finds the media a page loads
            // and downloads it from there. A link in the box above opens in it; otherwise search.
            Surface(
                onClick = {
                    val raw = url.trim()
                    browserUrl = if (raw.isBlank()) "https://www.google.com" else normalizeInputUrl(raw)
                    url = ""
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.TravelExplore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (url.isNotBlank() && looksLikeLink(url)) stringResource(R.string.open_this_link_in_the_browser) else stringResource(R.string.browse_the_web),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.find_videos_and_pictures_on_any),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            browserUrl?.let { start ->
                WebBrowser(startUrl = start, onDismiss = { browserUrl = null })
            }
            youtubeSearch?.let { query ->
                YouTubeSearchScreen(
                    initialQuery = query,
                    onDismiss = { youtubeSearch = null },
                    onConfigure = onConfigure,
                    onDownload = { downloadNow(it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.supported_sources),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // All four in primary: alternating primary/secondary looked mismatched in themes whose
            // secondary is a near-grey (reported live: Pixiv and "+ hundreds more" went grey).
            val sources = listOf(
                Triple("Twitter / X", FeatherIcons.Twitter, MaterialTheme.colorScheme.primary),
                Triple("Pixiv", Icons.Outlined.Image, MaterialTheme.colorScheme.primary),
                Triple("Instagram", FeatherIcons.Instagram, MaterialTheme.colorScheme.primary),
                Triple(stringResource(R.string.hundreds_more), Icons.Outlined.Public, MaterialTheme.colorScheme.primary),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sources.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (name, icon, tint) ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
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

            if (activeDownload != null) {
                Spacer(Modifier.height(24.dp))
                ActiveDownloadCard(item = activeDownload, onClick = onOpenQueue)
            }

            // Downloads held for a connection: WorkManager won't start them without one, and with
            // nothing running Home otherwise looked idle while the Queue had work in it.
            val networkOk = com.comfort.app.util.rememberIsNetworkAvailable()
            val waitingCount = remember(queueItems) { queueItems.count { it.status == DownloadStatus.QUEUED } }
            if (activeDownload == null && waitingCount > 0 && !networkOk) {
                Spacer(Modifier.height(24.dp))
                Surface(
                    onClick = onOpenQueue,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.waiting_for_a_network_connection),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                networkWaitSubtitle(homeContext, waitingCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            if (recentDownloads.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.recently_downloaded),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.comfort.app.theme.HeaderFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val recentListState = rememberLazyListState()
                // A genuinely continuous, slow drift (not the earlier per-item jump-and-pause)
                // once the user's left it alone for a while — an idle showcase, not something
                // fighting a real swipe. scrollBy() runs at MutatePriority.Default, the same
                // priority auto-scroll conventionally uses; a real drag gesture is handled by the
                // LazyRow's own built-in touch machinery at UserInput priority, which Compose's
                // scroll mutex always lets preempt a lower-priority caller, so a user grabbing the
                // list mid-drift interrupts this loop's current scrollBy call automatically — the
                // isDragged check below just avoids wastefully *starting* a new one while they're
                // still holding it, on top of that.
                val isRecentListDragged by recentListState.interactionSource.collectIsDraggedAsState()
                val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
                val homeWindowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                // One whole pixel per step at ~25 dp a second (the old 1.1 px every 16 ms on a
                // typical screen): a fraction of a pixel moved nothing on screen but still cost a
                // frame.
                val driftStepMs = with(androidx.compose.ui.platform.LocalDensity.current) { (1000f / 25.dp.toPx()).toLong().coerceAtLeast(8L) }
                LaunchedEffect(recentDownloads.size, reducedMotion, homeWindowFocused) {
                    if (recentDownloads.size <= 1 || reducedMotion || !homeWindowFocused) return@LaunchedEffect
                    // Only while Home is on screen and the app is in front (see `active`).
                    androidx.compose.runtime.snapshotFlow { active() }.collectLatest { onScreen ->
                    if (!onScreen) return@collectLatest
                    kotlinx.coroutines.delay(3500)
                    // One pass to the end and back, then it stays still: every step is a frame, and
                    // drifting for as long as Home was open kept the phone from ever idling there
                    // (~100 redraws a second, traced). Coming back to Home, or a new download,
                    // starts it again.
                    while (true) {
                        if (isRecentListDragged) {
                            kotlinx.coroutines.delay(200)
                            continue
                        }
                        if (!recentListState.canScrollForward) break
                        recentListState.scrollBy(1f)
                        kotlinx.coroutines.delay(driftStepMs)
                    }
                    kotlinx.coroutines.delay(1800)
                    recentListState.animateScrollToItem(0)
                    }
                }
                // Comfort: a tap opens the item in the media viewer, paging through the strip.
                val openViewer = com.comfort.app.ui.viewer.LocalOpenMediaViewer.current
                LazyRow(
                    state = recentListState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    // The earlier Modifier.padding() here was the actual bug: applied after the
                    // bleed widening, it shrinks the LazyRow's own *viewport*, which just moves
                    // where the clipped edge sits — it can never let scrolling actually reach past
                    // it, no matter how much extra width the bleed measures into. contentPadding
                    // is the real mechanism for "inset at rest, reachable by scrolling": it pads
                    // the *scrollable content* within a full-width viewport, not the viewport
                    // itself, so the leading/trailing item can still scroll into that space. The
                    // bleed layout below still does need to stay, though — it's the only thing
                    // giving this row a full-width viewport to begin with, escaping the parent
                    // Column's own 24dp margin that would otherwise cap it regardless.
                    modifier = Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val bleed = 24.dp.roundToPx()
                            val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                            layout(placeable.width - bleed * 2, placeable.height) {
                                placeable.placeRelative(-bleed, 0)
                            }
                        },
                    contentPadding = PaddingValues(horizontal = 24.dp),
                ) {
                    items(recentDownloads, key = { it.id }) { item ->
                        RecentDownloadThumbnail(
                            item = item,
                            onClick = {
                                val viewable = recentDownloads.filter { it.status != com.comfort.app.data.DownloadStatus.DELETED }
                                if (item in viewable) {
                                    openViewer(com.comfort.app.ui.viewer.MediaViewerRequest(viewable, item.id))
                                } else {
                                    onOpenLibrary()
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HomeTipsCarousel()

            // Matches navBarClearance() below — content needs to be able to scroll clear
            // of the floating pill now that it overlays on top instead of reserving its own
            // Scaffold-managed space (see MainScreen's own comment on that change).
            Spacer(Modifier.height(navBarClearance()))
        }
    }

        // Always here now that the card's own Paste button became Configure: reads the clipboard
        // at tap time (so a link copied after Home opened works too), and says "Paste copied link"
        // when a fresh link was already spotted on open.
        ExtendedFloatingActionButton(
            onClick = {
                val suggested = clipboardSuggestion
                if (!suggested.isNullOrBlank()) {
                    url = suggested
                    ClipboardSuggestionState.lastHandled = suggested
                    clipboardSuggestion = null
                } else {
                    homeScope.launch {
                        val clipText = readClipboardText()
                        if (clipText.isNullOrBlank()) {
                            android.widget.Toast.makeText(homeContext, homeContext.getString(R.string.nothing_to_paste), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            url = clipText
                            ClipboardSuggestionState.lastHandled = clipText
                            clipboardSuggestion = null
                        }
                    }
                }
            },
            icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
            text = {
                Text(
                    if (clipboardSuggestion != null) stringResource(R.string.paste_copied_link) else stringResource(R.string.paste),
                    fontFamily = com.comfort.app.theme.HeaderFontFamily,
                )
            },
            shape = com.comfort.app.theme.ContinuousCapsuleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = navBarClearance(), end = 24.dp),
        )
    }
}

/** Compact summary of the single most-recent RUNNING download, filling what used to be dead
 * space below "Supported sources" — tapping it opens the full Queue (onClick) for anything more
 * than the one item this shows. Deliberately much simpler than QueueScreen's own QueueItemCard
 * (no per-item action buttons, no network-stall messaging) — this is a glance/shortcut, not
 * another place to manage the download from. */
@Composable
private fun ActiveDownloadCard(item: com.comfort.app.data.DownloadEntity, onClick: () -> Unit) {
    val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
    val progress = when {
        item.totalItems > 1 -> (item.downloadedItems.toFloat() / item.totalItems).coerceIn(0f, 1f)
        item.expectedBytes > 0 -> ((item.totalBytes + item.liveBytes).toFloat() / item.expectedBytes).coerceIn(0f, 1f)
        else -> null
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // shapes.medium (12dp) — the spec's own card token; shapes.large (16dp) is for FABs/nav
        // drawers, same finding as the Queue screen's own running card had.
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            // 16dp, not 14dp — MD3's spacing system is built on an 8dp grid.
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    // shapes.small (8dp) — 10dp matched no real shape token.
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.thumbnailPath.isNullOrBlank()) {
                    HomeCardThumbnail(item.thumbnailPath, item.url, item.title, Modifier.fillMaxSize())
                } else {
                    // onPrimaryContainer, not the bare (unpaired) primary — same tonal-pairing
                    // fix as the Queue screen's own thumbnail icon: this box's background is
                    // primaryContainer, and MD3 only guarantees contrast for a container against
                    // its own matching "on" color, not an arbitrary combination.
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                }
            }
            // 16dp, not 12dp — same 8dp-grid reasoning as the padding above.
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title.ifBlank { item.url },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                if (progress != null) {
                    // Same wavy bar as the Queue card (QueueItemCard: tween-smoothed level, explicit
                    // waveSpeed so it keeps moving while the level sits still, flat near 100%).
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(durationMillis = 450),
                        label = "homeDownloadProgress",
                    )
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        // surfaceContainerHighest, not the older surfaceVariant token — same
                        // finding as the Queue screen's own progress track.
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        amplitude = { p -> if (reducedMotion || p > 0.9f) 0f else 0.4f },
                        wavelength = 32.dp,
                        waveSpeed = 8.dp,
                    )
                } else {
                    Text(
                        stringResource(R.string.downloading_2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (progress != null) {
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    val note = com.comfort.app.data.DownloadNotes.notes.collectAsState().value[item.id]
                    if (note != null) {
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    } else if (item.speedMbs > 0f) {
                        Text(
                            com.comfort.app.util.LocalFormat.measure(item.speedMbs.toDouble(), android.icu.util.MeasureUnit.MEGABYTE, 2, perSecond = true),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RecentDownloadThumbnail(item: com.comfort.app.data.DownloadEntity, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            // No ripple: unclipped (below), it would spread past the shape as a square.
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.open),
                onClick = onClick,
            ),
    ) {
        // The Library's Arch, drawn through the shape rather than clipped to it, as the Library
        // does (ShapedThumbnail): this strip drifts all the time, and a clip to a path rebuilds a
        // mask every frame.
        ShapedThumbnail(item, thumbnailShape(), MaterialTheme.colorScheme.surfaceContainer, Modifier.fillMaxSize())
    }
}

// Shared by ActiveDownloadCard/RecentDownloadThumbnail — same spoofed-header pattern QueueScreen's
// own QueueThumbnail uses (many sites reject hotlinked image requests without a browser-like UA
// and a same-site Referer), kept local here rather than exported since it's a small, self-contained
// image request and Home has no other reason to depend on QueueScreen's internals.
//
// SubcomposeAsyncImage with explicit loading/error composables, not a plain AsyncImage — the
// latter renders nothing at all on a failed load, leaving just the parent Box's flat background
// color showing (reproduced live: a finished download's own real content:// thumbnail URI
// intermittently failed to load here and showed as a blank tile in "Recently downloaded", while
// the exact same item's thumbnail rendered fine in Library, which already uses this same
// loading/error-icon pattern QueueScreen's own QueueThumbnail does). Matches both of those instead
// of being the one thumbnail spot in the app with no fallback.
@Composable
private fun HomeCardThumbnail(path: String?, refererUrl: String, title: String, modifier: Modifier = Modifier) {
    coil.compose.SubcomposeAsyncImage(
        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(path)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .addHeader("Referer", refererUrl)
            .build(),
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        },
        error = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(26.dp),
                )
            }
        },
    )
}

// Short, rotating tips highlighting features that are easy to miss otherwise — long-press
// multi-select and auto-updating engines especially, both added the same session this carousel
// was, with no other obvious discovery path on Home.
private val HOME_TIPS = listOf(
    R.string.long_press_a_card_in_queue to Icons.Outlined.CheckBox,
    R.string.yt_dlp_gallery_dl_and_instaloader to Icons.Outlined.Refresh,
    R.string.add_cookies_from_settings_to_unlock to Icons.Outlined.Lock,
    R.string.tap_a_queued_download_s_up to Icons.Outlined.Bolt,
    R.string.choose_mp4_or_mkv_output_and to Icons.Outlined.Settings,
)

@Composable
private fun HomeTipsCarousel() {
    var index by remember { mutableIntStateOf(0) }
    val reducedMotion = com.comfort.app.util.rememberIsReducedMotionEnabled()
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(reducedMotion, windowInfo.isWindowFocused) {
        if (reducedMotion || !windowInfo.isWindowFocused) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(6000)
            index = (index + 1) % HOME_TIPS.size
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.next_tip),
                role = androidx.compose.ui.semantics.Role.Button,
            ) {
                index = (index + 1) % HOME_TIPS.size
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                if (reducedMotion) {
                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                } else {
                    (slideInVertically { it / 3 } + androidx.compose.animation.fadeIn(tween(300))) togetherWith
                        (slideOutVertically { -it / 3 } + androidx.compose.animation.fadeOut(tween(300)))
                }
            },
            label = "homeTip",
        ) { i ->
            val (tipRes, icon) = HOME_TIPS[i]
            val tip = stringResource(tipRes)
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Whether Home's field holds a link (opened as a download) rather than words to search YouTube
 * for: a web address, with or without its https://. */
private fun looksLikeLink(text: String): Boolean {
    val t = text.trim()
    if (t.contains("://")) return true
    if (t.contains(' ')) return false
    return android.util.Patterns.WEB_URL.matcher(t).matches() && t.contains('.')
}
