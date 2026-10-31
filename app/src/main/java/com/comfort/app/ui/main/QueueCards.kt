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

// HTTP 429 ("Too Many Requests") is a real, temporary IP-level block from the site, not something
// retrying the same download fixes on its own — most mobile connections and home routers sit
// behind a dynamic IP (often Carrier-Grade NAT), so toggling airplane mode or power-cycling the
// router usually gets a fresh one and clears it. Keyword-based for the same reason
// COOKIE_ERROR_KEYWORDS is: both engines phrase this in plain English, not a structured error type.
private val RATE_LIMIT_ERROR_KEYWORDS = listOf("too many requests", "rate-limit", "rate limit", "rate limited")

// A plain substring check for "429" (like the phrase keywords above use) false-positives on any
// error message that happens to contain those digits for an unrelated reason — a URL/video id, a
// byte count, anything numeric. \b429\b requires it to stand alone as its own token instead.
private val RATE_LIMIT_429_RE = Regex("""\b429\b""")

private fun isRateLimitError(message: String?): Boolean =
    message != null &&
        (RATE_LIMIT_ERROR_KEYWORDS.any { message.contains(it, ignoreCase = true) } || RATE_LIMIT_429_RE.containsMatchIn(message))

// Shared by both the running-item card and the stopped-item row — item.thumbnailPath can be
// either a local content:// URI (once a file has actually landed) or a remote http(s) preview URL
// (set early from yt-dlp's own extracted thumbnail, before any bytes have downloaded; see
// DownloadWorker's [thumbnail] handling). The spoofed User-Agent/Referer only matter for the
// remote case — many sites (Instagram included) reject hotlinked image requests without them —
// but Coil simply ignores headers for a local URI, so there's no need to branch on which kind it is.
@Composable
private fun QueueThumbnail(item: DownloadEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageRequest = remember(item.thumbnailPath, item.url, context) {
        val safeReferer = runCatching { android.net.Uri.encode(item.url, ":/?#[]@!$&'()*+,;=") }.getOrNull() ?: item.url
        ImageRequest.Builder(context)
            .data(item.thumbnailPath)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .addHeader("Referer", safeReferer)
            .build()
    }
    SubcomposeAsyncImage(
        model = imageRequest,
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
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(26.dp),
                )
            }
        },
    )
}

/** A card's thumbnail, which turns into a round checkbox while selecting (and back), like a mail
 * app's avatar. Purely visual: the long-press/tap that selects lives on the whole card (StoppedRow's
 * and QueueItemCard's own combinedClickable), not just here. It used to stay the thumbnail, dimmed,
 * with a small check badge over its corner that muddied the status icon underneath. */
@Composable
private fun SelectableThumbnail(
    selectionMode: Boolean,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    androidx.compose.animation.AnimatedContent(
        targetState = selectionMode,
        transitionSpec = {
            (fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(160), initialScale = 0.6f))
                .togetherWith(fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(120), targetScale = 0.6f))
        },
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
        label = "selection checkbox",
    ) { selecting ->
        if (!selecting) {
            Box(modifier = modifier, content = content)
        } else {
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                SelectionCheck(selected)
            }
        }
    }
}

/** The round checkbox: an outline, filled with a check once selected. */
@Composable
private fun SelectionCheck(selected: Boolean) {
    val fill by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "check fill",
    )
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StoppedRow(
    item: DownloadEntity,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onStartNow: () -> Unit = {},
    onStartAt: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val isPaused = item.status == DownloadStatus.PAUSED
    var menuExpanded by remember { mutableStateOf(false) }
    val hasThumbnail = !item.thumbnailPath.isNullOrBlank()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                // Selected: tinted and outlined, so the selection reads from the whole card.
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier)
                // Always present, not just once selectionMode is already true — this is what
                // actually *enters* selection mode via a long-press anywhere on the row (not just
                // the thumbnail; reported live that only the thumbnail worked before this). Short
                // taps stay a no-op outside selection mode, so the Resume/More buttons further
                // right keep working normally: Compose routes a tap to the deepest element under
                // it first, so a direct tap on one of those buttons is consumed there and never
                // reaches this outer handler at all — only taps that land on otherwise-inert areas
                // of the row (thumbnail, title, blank space) ever hit this onClick/onLongClick.
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = onToggleSelect,
                )
                .semantics {
                    if (selectionMode) {
                        this.selected = selected
                        this.role = Role.Checkbox
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectableThumbnail(
                selectionMode = selectionMode,
                selected = selected,
                size = 76.dp,
                modifier = Modifier.size(76.dp).clip(thumbnailShape()).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (hasThumbnail) {
                    QueueThumbnail(item = item, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Image,
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
            val domain = remember(item.url) {
                runCatching { android.net.Uri.parse(item.url).host?.removePrefix("www.") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: context.getString(R.string.unknown_site)
            }
            // When, beside the site: on the status line below it was cut off ("Paused · nothing
            // saved yet · a…").
            val whenText = remember(item.downloadStartTime, item.dateAdded) {
                context.getString(if (item.downloadStartTime > 0) R.string.started_when else R.string.added_when, shortWhen(context, item.effectiveDate))
            }
            Text(
                text = "$domain · $whenText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))

            // How far it got, measured the way the running card measures it (item count for a
            // gallery, bytes for a single file): a paused download keeps a thin bar where it stopped.
            val savedBytes = item.totalBytes + item.liveBytes
            val stoppedProgress = when {
                item.totalItems > 1 -> (item.downloadedItems.toFloat() / item.totalItems).coerceIn(0f, 1f)
                item.expectedBytes > 0 -> (savedBytes.toFloat() / item.expectedBytes).coerceIn(0f, 1f)
                else -> null
            }
            if (isPaused && stoppedProgress != null) {
                LinearProgressIndicator(
                    progress = { stoppedProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(6.dp))
            }
            val progressText = when {
                item.totalItems > 1 -> stringResource(R.string.x_of_x, item.downloadedItems, item.totalItems)
                item.expectedBytes > 0 && savedBytes > 0 -> stringResource(R.string.x_of_x, formatFileSize(savedBytes), formatFileSize(item.expectedBytes))
                item.downloadedItems > 0 -> stringResource(R.string.x_saved, item.downloadedItems)
                else -> stringResource(R.string.nothing_saved_yet)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPaused) Icons.Outlined.Pause else Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(if (isPaused) R.string.paused_progress else R.string.cancelled_progress, progressText),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QueueLink(item.url, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), onSelectionTap = if (selectionMode) onToggleSelect else null)
        }

            if (!selectionMode) {
                IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.resume))
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // Resume waits for a free slot and a closed schedule window; Start now, like
                        // on a waiting card, starts it right away (making room if every slot is busy).
                        if (isPaused) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.up_next)) },
                                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                                onClick = { menuExpanded = false; onStartNow() },
                            )
                            // Or later, at a time picked for it (StartAtDialog).
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.start_at)) },
                                leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                                onClick = { menuExpanded = false; onStartAt() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_link)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText(context.getString(R.string.download_link), item.url)))
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sponsorblock_remove)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun QueueItemCard(
    item: DownloadEntity,
    isNetworkAvailable: Boolean = true,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPauseResume: () -> Unit,
    onRetry: () -> Unit,
    onStartNow: () -> Unit = {},
    onStartAt: () -> Unit = {},
    onAddCookies: () -> Unit = {},
    onShowError: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // better-interface review: the wavy progress indicator below animated its wave motion
    // unconditionally. Flattening amplitude to 0 under reduced motion keeps the progress *level*
    // itself moving (functional feedback, not decorative) while dropping the continuous undulation.
    val reducedMotion = rememberIsReducedMotionEnabled()
    // Plain Card, not ElevatedCard-with-elevation-zeroed-out — MD3 communicates elevation
    // through tonal surface color (surfaceContainer below), not shadow; choosing the Elevated
    // variant only to strip its own elevation back to 0dp was self-contradictory. shapes.medium
    // (12dp) is the spec's own card token — shapes.large (16dp) is for FABs/nav drawers.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        // Selected: tinted and outlined, so the selection reads from the whole card.
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Same reasoning as StoppedRow's own combinedClickable — always present so a
                // long-press anywhere on the card (not just the thumbnail) enters/extends
                // selection, while a direct tap on one of the buttons further down is still
                // consumed there first and never reaches this outer handler.
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = onToggleSelect,
                )
                .semantics {
                    if (selectionMode) {
                        this.selected = selected
                        this.role = Role.Checkbox
                    }
                }
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // shapes.small (8dp token) instead of a hardcoded RoundedCornerShape(8.dp) — same
                // radius, but tied to the theme's own shape scale instead of a magic number.
                // Container/icon now pair correctly per MD3's tonal-pairing rule (errorContainer +
                // onErrorContainer for ERRORED, same as the Cancel button below; primaryContainer +
                // onPrimaryContainer otherwise) — previously the background stayed primaryContainer
                // unconditionally while only the icon switched to the bare (unpaired) error color,
                // an arbitrary combination the spec explicitly calls out as breaking contrast
                // guarantees in dynamic color and high-contrast modes.
                val isErrored = item.status == DownloadStatus.ERRORED
                SelectableThumbnail(
                    selectionMode = selectionMode,
                    selected = selected,
                    size = 40.dp,
                    modifier = Modifier.size(40.dp).clip(thumbnailShape()).background(
                        if (isErrored) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    ),
                ) {
                    // A RUNNING download gets a thumbnail as soon as yt-dlp's extractor picks one
                    // (a remote preview URL, well before any bytes land — see DownloadWorker's
                    // [thumbnail] handling) or, for gallery-dl / once the real file lands, from
                    // setThumbnail(IfAbsent). Falls back to the status icon until either happens,
                    // same as QUEUED/ERRORED which never have one.
                    if (!item.thumbnailPath.isNullOrBlank()) {
                        QueueThumbnail(item = item, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val icon = when (item.status) {
                                DownloadStatus.ERRORED -> Icons.Outlined.Warning
                                DownloadStatus.QUEUED -> Icons.Outlined.Schedule
                                DownloadStatus.SCHEDULED -> Icons.Outlined.CalendarMonth
                                else -> Icons.Outlined.CloudDownload
                            }
                            val tint = if (isErrored) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                            Icon(icon, contentDescription = null, tint = tint)
                        }
                    }
                }

                // 8dp, not 12dp — MD3's own spacing system is built on an 8dp grid so margins/
                // padding/gaps stay consistent and can adapt programmatically across densities.
                Spacer(modifier = Modifier.width(8.dp))

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
                            if (item.downloadingAudioTrack) {
                                // A video+audio merge's own separate audio sub-file looks, from
                                // the queue card alone, like a second download starting out of
                                // nowhere with a much smaller size — this is what actually
                                // distinguishes it from a stuck/wrong progress bar.
                                stringResource(R.string.extracting_audio)
                            } else if (item.expectedBytes > 0 || item.totalBytes > 0 || item.liveBytes > 0 || item.totalItems > 0) {
                                stringResource(R.string.downloading)
                            } else {
                                stringResource(R.string.fetching_info)
                            }
                        }
                        // The Queue tabs' own names, so the card reads in the app's language (it
                        // showed the status's English code name, "Queued", in every language).
                        DownloadStatus.QUEUED -> stringResource(R.string.queue_filter_in_queue)
                        DownloadStatus.SCHEDULED -> stringResource(R.string.queue_filter_scheduled)
                        DownloadStatus.PAUSED -> stringResource(R.string.queue_filter_paused)
                        DownloadStatus.ERRORED -> stringResource(R.string.queue_filter_errored)
                        DownloadStatus.CANCELLED -> stringResource(R.string.queue_filter_cancelled)
                        DownloadStatus.FINISHED, DownloadStatus.SAVED -> stringResource(R.string.succeeded)
                        DownloadStatus.DELETED -> stringResource(R.string.deleted)
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

            // Same 8dp-grid reasoning as the thumbnail gap above.
            Spacer(modifier = Modifier.height(8.dp))

            if (item.status == DownloadStatus.RUNNING) {
                if (!isNetworkAvailable) {
                    // The subprocess itself is still running and will eventually time out and error
                    // on its own once the network is actually gone — this just gives an immediate,
                    // visible reason for a running download that's stopped making progress, instead
                    // of leaving the user watching a stalled progress bar with no explanation.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(
                            Icons.Outlined.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.no_internet_connection),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
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
                        // surfaceContainerHighest, not the older surfaceVariant token this app's
                        // current MD3 color-role set doesn't otherwise use anywhere else.
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        amplitude = { progress -> if (reducedMotion || progress > 0.9f) 0f else 0.4f },
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
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        amplitude = if (reducedMotion) 0f else 0.4f,
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
                        item.totalItems > 1 -> stringResource(R.string.x_of_x, item.downloadedItems, item.totalItems)
                        item.expectedBytes > 0 ->
                            stringResource(R.string.x_of_x, formatFileSize(item.totalBytes + item.liveBytes), formatFileSize(item.expectedBytes))
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
                    // A live note (an engine waiting out a site's rate limit) replaces the speed,
                    // which would otherwise sit at 0 KB/s with no explanation — see DownloadNotes.
                    val note = com.comfort.app.data.DownloadNotes.notes.collectAsStateWithLifecycle().value[item.id]
                    val speedStr = when {
                        !isNetworkAvailable -> com.comfort.app.util.LocalFormat.speed(0f, 2)
                        else -> com.comfort.app.util.LocalFormat.speed(item.speedMbs, 2)
                    }
                    Text(
                        text = note ?: stringResource(R.string.speed_x, speedStr),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Fills what used to be dead space below the size/speed row on a running
                // download's card — a site badge (titles are frequently in the source post's own
                // language/script, giving no hint where a download actually came from) and a
                // rough ETA, only ever shown for the same genuine single-item byte-progress case
                // the "X%" text above is already gated on, since remaining-bytes÷speed means
                // nothing for an item-count-only gallery download. A tinted pill rather than bare
                // icon+text — a plain label read as an afterthought floating in a lot of empty
                // card, not a deliberate piece of the layout.
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Format tags, if any, then the site badge — grouped as a single connected
                    // pill row (same rounded-outer/tight-inner shape as the Pause/Cancel chips
                    // below): the site badge is always index 0, so this list's own size decides
                    // where each pill's "outer end" actually falls.
                    val formatTagList = item.formatTags?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
                    val pillCount = 1 + formatTagList.size
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoPill(shape = groupedChipShape(0, pillCount, height = 24.dp)) {
                            Icon(
                                Icons.Outlined.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = VideoSiteRouter.siteName(item.url, stringResource(R.string.this_link)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Exactly what got picked for the file currently downloading — resolution/
                        // fps/container for video, bitrate+codec for audio (see
                        // yt_dlp_wrapper.py's own "[format]" signal) — at a glance, no need to
                        // open anything to find out. Gallery-dl-routed downloads never populate
                        // this at all, so the row is just the site badge for those, same as before.
                        formatTagList.forEachIndexed { i, tag ->
                            InfoPill(shape = groupedChipShape(i + 1, pillCount, height = 24.dp)) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // The link this download came from, in the space the pills leave free (QueueLink).
                    QueueLink(item.url, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), onSelectionTap = if (selectionMode) onToggleSelect else null)
                    if (byteProgress != null && item.speedMbs > 0f) {
                        val remainingBytes = item.expectedBytes - (item.totalBytes + item.liveBytes)
                        val remainingSeconds = (remainingBytes / (item.speedMbs * 1024f * 1024f)).toInt()
                        if (remainingSeconds > 0) {
                            Text(
                                text = formatEta(remainingSeconds),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (item.status == DownloadStatus.QUEUED) {
                if (isNetworkAvailable) {
                    Text(
                        text = stringResource(R.string.waiting_to_start),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // WorkManager's own CONNECTIVITY constraint (see DownloadDispatcher.enqueueWork)
                    // never resolves without this, so a QUEUED item just sits at the generic
                    // "Waiting to start…" forever with no visible reason otherwise — reproduced live
                    // against a Wi-Fi network that was connected but never validated by the OS.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.waiting_for_a_network_connection_2),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else if (item.status == DownloadStatus.SCHEDULED) {
                // When it starts, counted down live, and the window from Settings › Downloads.
                val context = LocalContext.current
                val now by produceState(System.currentTimeMillis()) {
                    while (true) {
                        kotlinx.coroutines.delay(30_000)
                        value = System.currentTimeMillis()
                    }
                }
                // Its own start time ("Start at…"), or the window's.
                val (startsText, windowText) = remember(now, item.scheduledAt) {
                    item.scheduledAt?.let { ownStartText(context, it) to context.getString(R.string.your_start_time) }
                        ?: scheduleTexts(context)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = startsText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(R.string.x_up_next_starts_it_now, windowText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (isRateLimitError(item.errorMessage)) {
                    Text(
                        text = stringResource(R.string.temporarily_rate_limited_by_the_site),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                QueueLink(item.url, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), onSelectionTap = if (selectionMode) onToggleSelect else null)
            }

            if (!selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // Explicit gap rather than relying on each TextButton/IconButton's own default
                    // touch-target padding for spacing — better-interface review: on the 4-action
                    // ERRORED row (Add cookies / Retry / Copy / Remove) that default padding was the
                    // only thing separating adjacent actions, tighter and less deliberate than a
                    // real gap.
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item.status) {
                        DownloadStatus.RUNNING -> {
                            // Stop now and continue at a picked time (StartAtDialog).
                            IconButton(onClick = onStartAt) {
                                Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.start_at))
                            }
                            // Real AssistChips (8dp corners, 32dp tall — M3's own chip spec) with
                            // the connected-group shape treatment: fully rounded on the pair's own
                            // outer ends (Pause's left, Cancel's right), a tighter corner where
                            // they face each other — instead of the previous icon-only
                            // FilledTonalIconButtons, whose bare glyph made Pause and Cancel hard
                            // to tell apart at a glance.
                            // Cancel first, Pause last (swapped on request) — Pause now sits on the
                            // right edge, where the Queued cards' own primary action also is.
                            AssistChip(
                                onClick = onCancel,
                                shape = groupedChipShape(0, 2),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = MaterialTheme.colorScheme.error,
                                ),
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = MaterialTheme.colorScheme.error,
                                    leadingIconContentColor = MaterialTheme.colorScheme.error,
                                ),
                                leadingIcon = { Icon(Icons.Outlined.CancelPresentation, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge) },
                            )
                            AssistChip(
                                onClick = onPauseResume,
                                shape = groupedChipShape(1, 2),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = MaterialTheme.colorScheme.outline,
                                ),
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                leadingIcon = { Icon(Icons.Outlined.PausePresentation, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text(stringResource(R.string.widget_music_pause), style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                        DownloadStatus.ERRORED -> {
                            IconButton(onClick = onShowError) {
                                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.error_details))
                            }
                            if (isCookieRelatedError(item.errorMessage)) {
                                TextButton(onClick = onAddCookies) {
                                    Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.add_cookies))
                                }
                            }
                            // Retry and delete as one split button, like "Start now" / cancel on a
                            // queued card. No copy button: a long-press on the card's link copies it
                            // (QueueLink).
                            ConfirmCancelSplitButton(
                                label = stringResource(R.string.retry),
                                icon = Icons.Outlined.Refresh,
                                onConfirm = onRetry,
                                onCancel = onDelete,
                                cancelDescription = stringResource(R.string.sponsorblock_remove),
                                cancelIcon = Icons.Outlined.Delete,
                            )
                        }
                        DownloadStatus.QUEUED, DownloadStatus.SCHEDULED -> {
                            // Same split button as Settings' edit sheets: "Start now" starts this
                            // download right away, past any schedule wait — when every slot is busy
                            // the newest running download steps aside for it (see
                            // DownloadDispatcher.startNow); cancel on the trailing half.
                            // Tapped, it turns into "Starting…" with a spinner until the card moves
                            // to Running: making room can take a few seconds (see startNow).
                            var starting by remember(item.id) { mutableStateOf(false) }
                            LaunchedEffect(starting) {
                                // Back to the button if it never started (it stays in the queue).
                                if (starting) { kotlinx.coroutines.delay(20_000L); starting = false }
                            }
                            // Set or change when it starts (StartAtDialog).
                            IconButton(onClick = onStartAt) {
                                Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.start_at))
                            }
                            ConfirmCancelSplitButton(
                                label = stringResource(R.string.up_next),
                                icon = Icons.Outlined.PlayArrow,
                                // Taps while starting are ignored rather than the button disabled:
                                // disabled, it turned a dim grey that hid the spinner.
                                onConfirm = { if (!starting) { starting = true; onStartNow() } },
                                onCancel = onCancel,
                                cancelDescription = stringResource(R.string.cancel_download),
                                leadingContent = { StartNowLabel(starting) },
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** A waiting card's Start now: the play icon slides away and "Starting…" with a spinner comes up
 * from below once tapped, and back if it never starts. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
private fun StartNowLabel(starting: Boolean) {
    androidx.compose.animation.AnimatedContent(
        targetState = starting,
        transitionSpec = {
            if (targetState) {
                (slideInVertically(tween(260)) { it } + fadeIn(tween(260))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(tween(220)) { it / 2 } + fadeOut(tween(180)))
            } else {
                fadeIn(tween(220)) togetherWith fadeOut(tween(180))
            } using androidx.compose.animation.SizeTransform(clip = false)
        },
        label = "startNow",
    ) { isStarting ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isStarting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (isStarting) R.string.starting else R.string.up_next))
        }
    }
}

/** A queue card's link, without the scheme/"www." and cut off with an ellipsis so it never pushes
 * anything around: a tap opens it, a long-press copies it (with a buzz and "Link copied"). Its
 * own handler, so a long-press here copies instead of starting the card's selection. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueueLink(
    url: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // While its card is in selection mode: a tap or long-press here selects/deselects the card.
    // The link used to be merely disabled then, which still swallowed the tap, so this strip was
    // the one part of a card that didn't respond.
    onSelectionTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shortLink = remember(url) { shortQueueLink(url) }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                // While selecting, only this: the link's own handler, even disabled, still took the
                // tap before it could reach the card.
                if (onSelectionTap != null) Modifier.combinedClickable(onClick = onSelectionTap, onLongClick = onSelectionTap)
                else Modifier.combinedClickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.open_link),
                onLongClickLabel = stringResource(R.string.copy_link),
                onClick = { runCatching { uriHandler.openUri(url) } },
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.download_link), url))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                    }
                },
            ))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = shortLink,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [url] without the scheme, "www." and the share-tracking parameters ("?si=", "?igsh=", "utm_..."),
 * but keeping the ones that say what it is: a YouTube link's video ID lives in "?v=", and cutting
 * the whole query showed every one of them as just "youtube.com/watch". */
internal fun shortQueueLink(url: String): String {
    val base = url.substringAfter("://").removePrefix("www.").substringBefore('#')
    val path = base.substringBefore('?').trimEnd('/')
    val kept = base.substringAfter('?', "").split('&').filter { param ->
        val name = param.substringBefore('=').lowercase()
        param.isNotBlank() && !com.comfort.app.data.UrlKey.isTracking(name)
    }
    return if (kept.isEmpty()) path else path + "?" + kept.joinToString("&")
}
