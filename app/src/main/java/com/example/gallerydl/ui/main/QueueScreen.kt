package com.example.gallerydl.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import com.example.gallerydl.viewmodel.DownloadsViewModel
import com.example.gallerydl.data.DownloadEntity
import com.example.gallerydl.data.DownloadStatus
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Matches the phrasing yt-dlp/gallery-dl actually use when a download failed because the site
// wants an authenticated session — e.g. "The web client only works when logged-in. Use --cookies,
// --cookies-from-browser, ..." (see DownloadWorker's real-error-surfacing fix). Deliberately
// keyword-based rather than parsing engine-specific error codes, since both engines phrase this
// in plain English rather than a structured error type.
private val COOKIE_ERROR_KEYWORDS = listOf(
    "cookie", "logged-in", "log in", "sign in", "login", "private", "authentication", "credentials", "netrc",
)

private fun isCookieRelatedError(message: String?): Boolean =
    message != null && COOKIE_ERROR_KEYWORDS.any { message.contains(it, ignoreCase = true) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit
) {
    val queueItems by viewModel.queueFlow.collectAsState()
    val isGloballyPaused by viewModel.isGloballyPaused.collectAsState()
    var selectedFilter by remember { mutableStateOf("Running") }
    val filters = listOf("Running", "In Queue", "Paused", "Errored", "Cancelled")

    // The "Add cookies" error-card action opens this for the failing item's own site, then
    // retries that same download once cookies are extracted.
    var cookieLoginTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // (loginUrl, downloadId)
    cookieLoginTarget?.let { (loginUrl, downloadId) ->
        CookieLoginDialog(
            loginUrl = loginUrl,
            onDismiss = { cookieLoginTarget = null },
            onCookiesSaved = {
                cookieLoginTarget = null
                viewModel.retryDownload(downloadId)
            },
        )
    }

    // Reflects reality even if every download ended up PAUSED one at a time (not via this FAB) —
    // the button should still offer "Resume" rather than staying stuck on "Pause" against a queue
    // that has nothing left to pause.
    val hasActiveDownload = queueItems.any {
        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED
    }
    val hasPausedDownload = queueItems.any { it.status == DownloadStatus.PAUSED }
    val showResumeAction = isGloballyPaused || (!hasActiveDownload && hasPausedDownload)

    // Hoisted above the Scaffold so the FAB can react to both the current filter tab (Errored/
    // Cancelled get "Retry All" instead of Pause/Resume) and what's actually in it. No more
    // catch-all "All" tab — every status has its own explicit chip now, so this is exhaustive.
    val filteredItems = queueItems.filter { item ->
        when (selectedFilter) {
            "Running" -> item.status == DownloadStatus.RUNNING
            "In Queue" -> item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.SCHEDULED
            "Paused" -> item.status == DownloadStatus.PAUSED
            "Errored" -> item.status == DownloadStatus.ERRORED
            "Cancelled" -> item.status == DownloadStatus.CANCELLED
            else -> false
        }
    }
    val retryAllStatus = when (selectedFilter) {
        "Errored" -> DownloadStatus.ERRORED
        "Cancelled" -> DownloadStatus.CANCELLED
        else -> null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (retryAllStatus != null && filteredItems.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.retryAll(retryAllStatus) },
                    icon = { Icon(FeatherIcons.RefreshCw, contentDescription = null) },
                    text = { Text("Retry All") },
                )
            } else if (hasActiveDownload || hasPausedDownload) {
                ExtendedFloatingActionButton(
                    onClick = { if (showResumeAction) viewModel.resumeAll() else viewModel.pauseAll() },
                    icon = {
                        Icon(
                            if (showResumeAction) FeatherIcons.Play else FeatherIcons.Pause,
                            contentDescription = null,
                        )
                    },
                    text = { Text(if (showResumeAction) "Resume" else "Pause") },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Download Queue", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Clear All */ }) {
                        Icon(FeatherIcons.Trash2, contentDescription = "Clear Queue")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyRow(
                // contentPadding (not an outer Modifier.padding) so the scrollable viewport spans
                // the full screen width — chips scroll flush to the true edge instead of getting
                // clipped mid-chip right at an inset boundary, which read as "cut off."
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { filter ->
                    val count = when (filter) {
                        "Running" -> queueItems.count { it.status == DownloadStatus.RUNNING }
                        "In Queue" -> queueItems.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED }
                        "Paused" -> queueItems.count { it.status == DownloadStatus.PAUSED }
                        "Errored" -> queueItems.count { it.status == DownloadStatus.ERRORED }
                        "Cancelled" -> queueItems.count { it.status == DownloadStatus.CANCELLED }
                        else -> 0
                    }
                    BadgedBox(
                        badge = {
                            if (count > 0) {
                                Badge(
                                    containerColor = if (selectedFilter == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                ) {
                                    Text(count.toString())
                                }
                            }
                        }
                    ) {
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            shape = com.example.gallerydl.theme.PillShape
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                EmptyState(
                    icon = FeatherIcons.Inbox,
                    title = "No downloads in queue",
                    subtitle = "Paste a link on Home to start one.",
                )
            } else {
                LazyColumn(
                    // Extra bottom inset beyond the normal 16dp: the Pause/Resume/Retry All FAB
                    // floats over the content rather than reserving space for itself, so without
                    // this the last card(s) end up scrolled underneath it, partly unreadable and
                    // with their own action buttons unreachable.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val row = @Composable {
                            if (item.status == DownloadStatus.CANCELLED || item.status == DownloadStatus.PAUSED) {
                                StoppedRow(
                                    item = item,
                                    onResume = { viewModel.retryDownload(item.id) },
                                    onDelete = { viewModel.deleteDownload(item.id) },
                                )
                            } else {
                                QueueItemCard(
                                    item = item,
                                    onCancel = { viewModel.cancelDownload(item.id) },
                                    onDelete = { viewModel.deleteDownload(item.id) },
                                    onPauseResume = { viewModel.pauseDownload(item.id) },
                                    onRetry = { viewModel.retryDownload(item.id) },
                                    onStartNow = { viewModel.startNow(item.id) },
                                    onAddCookies = {
                                        val host = runCatching { URI(item.url).host }.getOrNull()
                                        if (host != null) cookieLoginTarget = "https://$host" to item.id
                                    },
                                )
                            }
                        }

                        // Swipe-to-delete is only offered for downloads that are already stopped
                        // for good (errored or cancelled) — everything still active or resumable
                        // (running, queued, scheduled, paused) should require a deliberate tap
                        // instead of a stray swipe wiping it out.
                        if (item.status == DownloadStatus.CANCELLED || item.status == DownloadStatus.ERRORED) {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value != SwipeToDismissBoxValue.Settled) {
                                        viewModel.deleteDownload(item.id)
                                    }
                                    true
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
                                    ) {
                                        Icon(FeatherIcons.Trash2, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                },
                            ) {
                                row()
                            }
                        } else {
                            row()
                        }
                    }
                }
            }
        }
    }
}

// Shared by both the running-item card and the stopped-item row — item.thumbnailPath can be
// either a local content:// URI (once a file has actually landed) or a remote http(s) preview URL
// (set early from yt-dlp's own extracted thumbnail, before any bytes have downloaded; see
// DownloadWorker's [thumbnail] handling). The spoofed User-Agent/Referer only matter for the
// remote case — many sites (Instagram included) reject hotlinked image requests without them —
// but Coil simply ignores headers for a local URI, so there's no need to branch on which kind it is.
@Composable
private fun QueueThumbnail(item: DownloadEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(item.thumbnailPath)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .addHeader("Referer", item.url)
            .build(),
        contentDescription = item.title,
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
                    FeatherIcons.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(26.dp),
                )
            }
        },
    )
}

@Composable
private fun StoppedRow(
    item: DownloadEntity,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val isPaused = item.status == DownloadStatus.PAUSED
    var menuExpanded by remember { mutableStateOf(false) }
    val hasThumbnail = !item.thumbnailPath.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (hasThumbnail) {
                QueueThumbnail(item = item, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        FeatherIcons.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { item.url },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val domain = remember(item.url) { runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "Unknown" }
            Text(
                text = domain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))

            val sdf = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPaused) FeatherIcons.Pause else FeatherIcons.X,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "${if (isPaused) "Paused" else "Cancelled"} • ${item.downloadedItems} saved • ${sdf.format(Date(item.effectiveDate))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onResume) {
            Icon(FeatherIcons.Play, contentDescription = "Resume")
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(FeatherIcons.MoreVertical, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(FeatherIcons.Trash2, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueItemCard(
    item: DownloadEntity,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPauseResume: () -> Unit,
    onRetry: () -> Unit,
    onStartNow: () -> Unit = {},
    onAddCookies: () -> Unit = {},
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    // A RUNNING download gets a thumbnail as soon as yt-dlp's extractor picks one
                    // (a remote preview URL, well before any bytes land — see DownloadWorker's
                    // [thumbnail] handling) or, for gallery-dl / once the real file lands, from
                    // setThumbnail(IfAbsent). Falls back to the status icon until either happens,
                    // same as QUEUED/ERRORED which never have one.
                    if (!item.thumbnailPath.isNullOrBlank()) {
                        QueueThumbnail(item = item, modifier = Modifier.fillMaxSize())
                    } else {
                        val icon = when (item.status) {
                            DownloadStatus.ERRORED -> FeatherIcons.AlertTriangle
                            DownloadStatus.QUEUED -> FeatherIcons.Clock
                            DownloadStatus.SCHEDULED -> FeatherIcons.Calendar
                            else -> FeatherIcons.DownloadCloud
                        }
                        val tint = if (item.status == DownloadStatus.ERRORED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        Icon(icon, contentDescription = null, tint = tint)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { item.url },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusText = when (item.status) {
                        DownloadStatus.RUNNING -> {
                            if (item.expectedBytes > 0 || item.totalBytes > 0 || item.liveBytes > 0 || item.totalItems > 0) {
                                "Downloading"
                            } else {
                                "Fetching info..."
                            }
                        }
                        else -> item.status.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            DownloadStatus.ERRORED -> MaterialTheme.colorScheme.error
                            DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (item.status == DownloadStatus.RUNNING) {
                // Item count wins whenever there's more than one item, even once expectedBytes is
                // also known — the case that matters is a gallery-dl gallery (several pictures,
                // item-count progress climbing normally) that yt-dlp then supplements with the
                // same post's video (byte-accurate progress, but only for that one file): letting
                // expectedBytes take over there would snap the bar from, say, "6 of 8" straight
                // back down to a fresh 0% for just the video — a visible regression, since the
                // gallery's own completed items aren't reflected in a byte count that only ever
                // tracked the one file. Byte-accurate progress is reserved for genuinely
                // single-item downloads, which is what it's normally available for anyway.
                val itemProgress = if (item.totalItems > 1) {
                    (item.downloadedItems.toFloat() / item.totalItems).coerceIn(0f, 1f)
                } else null
                val byteProgress = if (itemProgress == null && item.expectedBytes > 0) {
                    ((item.totalBytes + item.liveBytes).toFloat() / item.expectedBytes).coerceIn(0f, 1f)
                } else null
                val rawProgress = itemProgress ?: byteProgress

                if (rawProgress != null) {
                    // tween-smoothed so a jump from (say) one file finishing to the next eases
                    // into place over a few frames instead of snapping straight to the new value
                    // — the wavy indicator's own animation only smooths the wave motion, not the
                    // progress level itself.
                    val animatedProgress by animateFloatAsState(
                        targetValue = rawProgress,
                        animationSpec = tween(durationMillis = 450),
                        label = "downloadProgress",
                    )
                    // The wave amplitude tapers to flat as progress nears 100% in the determinate
                    // overload — that's M3 Expressive's own spec, not something tuned here.
                    // waveSpeed/amplitude/wavelength must be passed explicitly (mirroring the
                    // indeterminate branch below) — the determinate overload's own defaults render
                    // a static, non-scrolling wave shape that only redraws when the progress level
                    // itself changes, which reads as "frozen" on any card whose progress happens to
                    // sit still for a beat.
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        amplitude = { progress -> if (progress > 0.9f) 0f else 0.4f },
                        wavelength = 40.dp,
                        waveSpeed = 8.dp,
                    )
                } else {
                    // Nothing to measure a real fraction against — either a single item with no
                    // known size (gallery-dl doesn't report one upfront the way yt-dlp does), or
                    // still extracting the link's metadata entirely (nothing downloaded yet at
                    // all). Indeterminate keeps animating either way rather than sitting frozen or
                    // jumping straight from empty to full; extraction runs at half the wave speed
                    // of an active transfer so the two are visually distinguishable.
                    val isExtracting = item.totalBytes <= 0 && item.liveBytes <= 0 && item.downloadedItems <= 0
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        amplitude = 0.4f,
                        wavelength = 40.dp,
                        waveSpeed = if (isExtracting) 4.dp else 8.dp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val leadingText = when {
                        item.totalItems > 1 -> "${item.downloadedItems} of ${item.totalItems}"
                        item.expectedBytes > 0 ->
                            "${formatFileSize(item.totalBytes + item.liveBytes)} of ${formatFileSize(item.expectedBytes)}"
                        // A single item with no known size — an item count of "0 of 1"/"1 of 1"
                        // says nothing useful, so this space is left blank instead.
                        else -> ""
                    }
                    Text(
                        text = leadingText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Only for the genuine single-item byte-progress case (see byteProgress's own
                    // comment above for why item-count downloads don't get one here) — SpaceBetween
                    // naturally lands this between the size and speed text either side of it.
                    if (byteProgress != null) {
                        Text(
                            text = "${(byteProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "Speed: ${String.format("%.2f", item.speedMbs)} MB/s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (item.status == DownloadStatus.QUEUED) {
                Text(
                    text = "Waiting to start…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (item.status == DownloadStatus.SCHEDULED) {
                Text(
                    text = "Waiting for the scheduled time window…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.status == DownloadStatus.ERRORED && item.errorMessage != null) {
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.status) {
                    DownloadStatus.RUNNING -> {
                        IconButton(onClick = onPauseResume) {
                            Icon(FeatherIcons.Pause, contentDescription = "Pause")
                        }
                        IconButton(onClick = onCancel) {
                            Icon(FeatherIcons.X, contentDescription = "Cancel")
                        }
                    }
                    DownloadStatus.ERRORED -> {
                        if (isCookieRelatedError(item.errorMessage)) {
                            TextButton(onClick = onAddCookies) {
                                Icon(FeatherIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add cookies")
                            }
                        }
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(FeatherIcons.Trash2, contentDescription = "Remove")
                        }
                    }
                    DownloadStatus.QUEUED, DownloadStatus.SCHEDULED -> {
                        TextButton(onClick = onStartNow) {
                            Icon(FeatherIcons.Zap, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start now")
                        }
                        IconButton(onClick = onCancel) {
                            Icon(FeatherIcons.X, contentDescription = "Cancel")
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
