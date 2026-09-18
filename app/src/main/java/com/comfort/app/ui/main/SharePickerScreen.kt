package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.ceil
import com.comfort.app.data.DownloadDispatcher
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.VideoQuality
import com.comfort.app.data.VideoSiteRouter
import com.comfort.app.util.GalleryDlListing
import com.comfort.app.util.GalleryItem
import com.comfort.app.util.ListingResult
import com.comfort.app.util.rememberIsNetworkAvailable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.launch

private enum class ListingState { LOADING, LOADED, UNAVAILABLE, ERROR }

/** Shown when a link is shared in from another app (and instant mode is off): lets the user
 * preview the gallery's items and pick which ones to actually download, instead of always
 * fetching everything. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharePickerScreen(
    url: String,
    onDismiss: () -> Unit,
    onDownload: (url: String, itemFilter: String?, totalItems: Int, videoQuality: VideoQuality?, forceDuplicate: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onHeightChange: (Dp) -> Unit = {},
    // ShareActivity already runs this exact listing pass once itself, to decide whether this
    // gallery item-picker is even the right UI to show (see its own doc comment) or a single
    // detected video should skip straight to DownloadPreviewSheet instead. Reusing that result
    // here avoids a second, redundant network round trip for every share — real cost on sites
    // like Twitter/Reddit that need a fresh guest-token/auth request per listing attempt. Null
    // (the default) means "fetch it myself," unchanged from every other caller of this screen.
    preloadedResult: ListingResult? = null,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ListingState.LOADING) }
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var selectedNums by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Seeded from the global Settings default once the listing loads, then only ever changed by
    // the quality chips below — a per-download override, not a change to the global default.
    var selectedQuality by remember { mutableStateOf<VideoQuality?>(null) }
    val scope = rememberCoroutineScope()
    val isNetworkAvailable = rememberIsNetworkAvailable()

    // Whether any item this listing found is a video — gates both the play-icon overlay on that
    // item's thumbnail and the quality picker strip, since there's nothing to pick a quality for
    // in an all-image gallery.
    val hasVideoItems = remember(items) { items.any { it.filename?.let(VideoSiteRouter::isVideoFilename) == true } }

    // Mirrors the Download button's own onClick computation below exactly (null means "the whole
    // gallery," matching what a plain shared link with no selection at all would enqueue as) —
    // computed here too so the duplicate check and the button label agree on exactly what
    // download this selection actually represents.
    val itemFilter = if (selectedNums.isEmpty() || selectedNums.size == items.size) null
        else "num in {${selectedNums.sorted().joinToString(",")}}"

    // Re-checked on every selection change, not just once — DownloadDao.findActiveOrFinishedByUrl
    // now matches on (url, itemFilter) together (see its own doc comment), so switching which
    // items are selected can genuinely flip this: a previously-downloaded 3-item subset of a
    // 10-item gallery is a real duplicate only while that same subset (or "whole gallery," if
    // itemFilter is null on both sides) is what's currently selected.
    var isDuplicate by remember { mutableStateOf(false) }
    LaunchedEffect(url, itemFilter) {
        isDuplicate = DownloadDispatcher.isDuplicate(context, url, itemFilter)
    }

    LaunchedEffect(hasVideoItems) {
        if (hasVideoItems && selectedQuality == null) {
            selectedQuality = GalleryDlPreferences.getVideoQuality(context)
        }
    }

    // Sizes the sheet to fit however many rows the picker actually needs (a 2-image post
    // shouldn't get the same tall sheet as a 40-image profile), capped so it never exceeds a
    // comfortable fraction of the screen — beyond that the grid scrolls internally instead.
    val configuration = LocalConfiguration.current
    LaunchedEffect(state, items.size, hasVideoItems, configuration.screenWidthDp, configuration.screenHeightDp) {
        val topBarHeight = 84.dp
        val bottomBarHeight = 84.dp
        // The quality picker strip (section label + a row of chips) only renders when the
        // listing actually contains a video — has to be accounted for here too, or the sheet
        // ends up too short and the strip gets clipped/scrolled instead of just fitting.
        val qualityStripHeight = if (hasVideoItems) 76.dp else 0.dp
        val target = if (state == ListingState.LOADED && items.isNotEmpty()) {
            val gridPadding = 24.dp
            val spacing = 8.dp
            // Matches the grid below: fewer than 3 items gets that many columns instead of always
            // reserving 3, so a lone item's cell actually fills the row's width instead of sitting
            // pinned to the left with two empty columns of dead space next to it.
            val columns = minOf(items.size, 3)
            val cellSize = (configuration.screenWidthDp.dp - gridPadding - spacing * (columns - 1)) / columns
            val rows = ceil(items.size / columns.toFloat()).toInt()
            // The compact 16:9-plus-caption treatment only ever applies to a single, full-row video
            // item (see the grid below) — a multi-item grid (whether all-video or mixed) uses the
            // same square cells as photos throughout, so the shorter estimate only applies to that
            // one specific case.
            val singleVideoItem = items.size == 1 && items.first().filename?.let(VideoSiteRouter::isVideoFilename) == true
            val rowHeight = if (singleVideoItem) cellSize * 9f / 16f + 36.dp else cellSize
            val gridHeight = rowHeight * rows + spacing * (rows - 1).coerceAtLeast(0) + gridPadding
            topBarHeight + qualityStripHeight + gridHeight + bottomBarHeight
        } else if (state == ListingState.ERROR) {
            // Icon + title + message + up to three stacked buttons (Log in / Try anyway / Cancel)
            // needs more room than the plain LOADING/UNAVAILABLE spinner frame below.
            440.dp
        } else {
            280.dp
        }
        val maxHeight = configuration.screenHeightDp.dp * 0.92f
        val minHeight = 280.dp
        onHeightChange(target.coerceIn(minHeight, maxHeight))
    }

    LaunchedEffect(url) {
        state = ListingState.LOADING
        errorMessage = null
        val result = preloadedResult ?: GalleryDlListing.listItems(context, url)
        when {
            result.items.isNotEmpty() -> {
                items = result.items
                selectedNums = result.items.map { it.num }.toSet()
                state = ListingState.LOADED
            }
            // A genuine failure (needs login, network error, ...) — surfaced to the user instead
            // of silently falling through to a download that's just going to fail the same way a
            // moment later with no explanation (reproduced live: a login-gated post went straight
            // to the queue and errored there with no indication why).
            result.errorMessage != null -> {
                errorMessage = result.errorMessage
                state = ListingState.ERROR
            }
            // A source that genuinely can't be listed this way (single-file links, unsupported
            // extractors) falls back to a normal whole-gallery download — there's no error here,
            // just nothing this picker knows how to preview ahead of time.
            else -> state = ListingState.UNAVAILABLE
        }
    }

    LaunchedEffect(state) {
        if (state == ListingState.UNAVAILABLE) {
            // No picker ever shown for this case (listing genuinely couldn't be previewed) — same
            // "no button, so no confirmation to already have given" reasoning as MultiLinkHandler,
            // not forced: a duplicate here still just folds into the normal skip-and-record.
            onDownload(url, null, 0, null, false)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // A drag-handle-style bar so the picker reads visually as a standard M3 bottom sheet
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 4.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                TopAppBar(
                // TopAppBar reserves top system-bar inset padding by default, assuming it sits at
                // the physical top of the screen — this one floats inside a bottom sheet well
                // below the real status bar, so that reserved padding was pure dead space above
                // the title (reproduced live: a noticeably oversized gap before "N of M selected").
                windowInsets = WindowInsets(0.dp),
                title = {
                    Text(
                        when (state) {
                            ListingState.LOADED -> "${selectedNums.size} of ${items.size} selected"
                            ListingState.ERROR -> "Preview unavailable"
                            else -> "Loading…"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (state == ListingState.LOADED) {
                        TextButton(onClick = {
                            selectedNums = if (selectedNums.size == items.size) emptySet() else items.map { it.num }.toSet()
                        }) {
                            Text(if (selectedNums.size == items.size) "Deselect all" else "Select all")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
            }
        },
        bottomBar = {
            if (state == ListingState.LOADED) {
                Surface(color = Color.Transparent, tonalElevation = 3.dp) {
                    // 24dp horizontal inset matches MainScreen's own bottom nav pill's margin,
                    // so this button reads the same width as that pill rather than a narrower one.
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Button(
                            onClick = {
                                // itemFilter (hoisted above, shared with the isDuplicate check)
                                // restricts the download to exactly these items, so this count is
                                // exact regardless of whether the picker's own listing got
                                // truncated at GalleryDlListing.MAX_ITEMS. forceDuplicate = true:
                                // this button already said "Redownload" when isDuplicate was true,
                                // so tapping it is the confirmation — same reasoning
                                // DownloadPreviewSheet's own Download button already uses.
                                onDownload(url, itemFilter, selectedNums.size, if (hasVideoItems) selectedQuality else null, isDuplicate)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                            enabled = selectedNums.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isDuplicate) "Redownload ${selectedNums.size}" else "Download ${selectedNums.size}")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            // Shown above whatever the picker is doing (still loading, or already showing the
            // grid) — the ERROR state already explains itself in detail, so this would just be
            // redundant clutter there. A dropped connection here means the listing that's either
            // in flight or about to be acted on (tapping Download) is heading for the same kind of
            // silent failure the picker's own error card exists to catch, just before it happens.
            if (!isNetworkAvailable && state != ListingState.ERROR) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "No internet connection",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
            when (state) {
                ListingState.LOADING -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularWavyProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Looking at what's there…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ListingState.ERROR -> {
                    val needsLogin = errorMessage?.let { msg ->
                        listOf("login", "cookie", "sign in", "sign-in", "authentication").any { msg.contains(it, ignoreCase = true) }
                    } == true
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Couldn't load a preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        if (needsLogin) {
                            Button(
                                onClick = { showLoginDialog = true },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Log in")
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        OutlinedButton(
                            // Same "no confirmed duplicate-aware button" reasoning as the
                            // UNAVAILABLE branch above — a genuine listing failure means there was
                            // never a real isDuplicate check to have shown the user in the first
                            // place, so this still just folds into the normal skip-and-record
                            // rather than forcing through.
                            onClick = { onDownload(url, null, 0, null, false) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text("Try downloading anyway")
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                    if (showLoginDialog) {
                        CookieLoginDialog(
                            loginUrl = "https://instagram.com",
                            onDismiss = { showLoginDialog = false },
                            onCookiesSaved = {
                                showLoginDialog = false
                                // Cookies just changed — the same URL is worth re-listing rather
                                // than leaving the user stuck on the same error they just fixed.
                                state = ListingState.LOADING
                                scope.launch {
                                    val retry = GalleryDlListing.listItems(context, url)
                                    when {
                                        retry.items.isNotEmpty() -> {
                                            items = retry.items
                                            selectedNums = retry.items.map { it.num }.toSet()
                                            state = ListingState.LOADED
                                        }
                                        retry.errorMessage != null -> {
                                            errorMessage = retry.errorMessage
                                            state = ListingState.ERROR
                                        }
                                        else -> state = ListingState.UNAVAILABLE
                                    }
                                }
                            },
                        )
                    }
                }
                ListingState.UNAVAILABLE -> {
                    // Handled by the LaunchedEffect above (falls back to a full download); this
                    // frame is only visible for an instant before onDismiss/onDownload fires.
                }
                ListingState.LOADED -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (hasVideoItems) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    "Video quality",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    // Bleed past this section's own 16dp side margin (pulled out of
                                    // the parent Column above, which used to apply it around both
                                    // this row and the label) so the scrollable viewport spans the
                                    // full screen width instead of stopping short at that margin on
                                    // either side — same bug/fix as the Downloads settings page's
                                    // own Video quality row (MoreScreen.kt) and Home's
                                    // Recently-downloaded strip (MainScreen.kt): a trailing
                                    // Modifier.padding() looks identical at rest but caps the row's
                                    // own scrollable width, a bleed measured via a custom layout{}
                                    // plus content padding *after* horizontalScroll() doesn't.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .layout { measurable, constraints ->
                                            val bleed = 16.dp.roundToPx()
                                            val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                                            layout(placeable.width - bleed * 2, placeable.height) {
                                                placeable.placeRelative(-bleed, 0)
                                            }
                                        }
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    VideoQuality.entries.forEach { quality ->
                                        val isSelected = selectedQuality == quality
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            onClick = { selectedQuality = quality },
                                        ) {
                                            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                Text(
                                                    quality.label,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        LazyVerticalGrid(
                            // Same reasoning as the height calculation above — caps at 3 but never
                            // reserves more columns than there are items to fill them.
                            columns = GridCells.Fixed(minOf(items.size, 3)),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                        // listIndex, not num — see GalleryItem's own doc comment: num is only
                        // guaranteed unique within one source's own file sequence, not across a
                        // listing spanning several separate posts (a subreddit-index URL, e.g.),
                        // and reused duplicate keys crash LazyVerticalGrid outright.
                        items(items, key = { it.listIndex }) { item ->
                            val selected = item.num in selectedNums
                            val isVideo = item.filename?.let(VideoSiteRouter::isVideoFilename) == true
                            val toggle = { selectedNums = if (selected) selectedNums - item.num else selectedNums + item.num }

                            // The shorter 16:9-plus-caption treatment is reserved for the single,
                            // full-row video case — a lone video post filling the entire row at a
                            // full 1:1 square read as oversized for just a play icon. In a multi-item
                            // grid (a carousel mixing photos and videos, or several videos at once)
                            // every cell stays the same square size regardless of type instead —
                            // a video tile a different shape/size than its photo neighbors in the
                            // same grid looked broken rather than intentional (reported live: "make
                            // the video thumbnail the same size as the picture thumbnail").
                            val compactVideo = isVideo && items.size == 1
                            Column(
                                modifier = Modifier.clickable(onClick = toggle),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(if (compactVideo) 16f / 9f else 1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            ) {
                                coil.compose.SubcomposeAsyncImage(
                                    model = coil.request.ImageRequest.Builder(context)
                                        .data(item.url)
                                        // Many sites (Instagram included) reject hotlinked image
                                        // requests without a browser-like UA and a same-site
                                        // Referer, so the preview would otherwise come back blank.
                                        .addHeader(
                                            "User-Agent",
                                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                                        )
                                        .addHeader("Referer", url)
                                        .build(),
                                    contentDescription = item.filename,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    loading = {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        }
                                    },
                                    error = {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.Image,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    },
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(if (selected) Color.Black.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.35f))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                                    }
                                }
                                if (isVideo) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.PlayArrow,
                                            contentDescription = "Video",
                                            tint = Color.White,
                                            // Nudged right so the triangle's own visual weight
                                            // (its point sits left of the glyph's bounding box)
                                            // actually looks centered inside the circle.
                                            modifier = Modifier.size(16.dp).padding(start = 2.dp),
                                        )
                                    }
                                }
                            }
                            if (compactVideo && item.title != null) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                            }
                        }
                        }
                    }
                }
            }
            }
        }
    }
}
