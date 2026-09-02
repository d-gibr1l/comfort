package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfort.app.data.DownloadEntity
import com.comfort.app.data.DownloadStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.X
import kotlinx.coroutines.launch

/** A finished/failed-download toast's own [SnackbarVisuals] — [isSuccess] is read back out in
 * [DownloadEventSnackbarHost] to pick the icon and color, since plain SnackbarHostState.showSnackbar
 * only carries a bare message string otherwise. */
data class DownloadEventVisuals(
    val isSuccess: Boolean,
    override val message: String,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = if (isSuccess) SnackbarDuration.Short else SnackbarDuration.Long
}

/** Shared "download finished/failed" toast styling for both Library and the Download Queue — a
 * free-floating rounded card with a solid-color circular icon badge, matching the icon-badge
 * language used throughout the rest of the app (Home's tip rows, About's engine rows, ...) instead
 * of Material's plain flat Snackbar default, which reads as the same flat grey block for both a
 * success and a failure until you actually read the text. A custom Surface (not Snackbar's own
 * shape/color slots) is what makes the solid icon badge and the pill dismiss button possible —
 * Snackbar's own API only exposes a single flat containerColor for the whole bar. */
@Composable
fun DownloadEventSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val visuals = data.visuals
        val isSuccess = (visuals as? DownloadEventVisuals)?.isSuccess ?: true
        // Success reads as "finished" more clearly in the app's own teal (already reserved for
        // progress/success states — see Color.kt) than the brand indigo Snackbar used to borrow
        // from primaryContainer, which reads as "just another accent," not specifically "done."
        val accent = if (isSuccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        val onAccent = if (isSuccess) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onError
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isSuccess) FeatherIcons.CheckCircle else FeatherIcons.AlertTriangle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = onAccent,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Capped regardless of how long visuals.message turns out to be — error text is
                // sanitized at the source now (see GalleryDlListing.sanitizeErrorMessage), but this
                // is the last line of defense against a toast ever filling the whole screen the way
                // one reproduced live before that existed, not something to rely on that alone for.
                Text(
                    visuals.message,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Fires a [DownloadEventVisuals] snackbar for any download that finishes or fails while the
 * caller is actively composed — never for ones that were already finished/errored *before* this
 * call's first composition (each backing "seen" set starts at null specifically so the very first
 * emission from each flow only seeds a baseline to diff against, it never itself fires a toast).
 * Call once per screen that wants this (Library, the Download Queue), each with its own
 * [snackbarHostState] — the two calls don't share any state, so a download finishing while the
 * user is on Library doesn't also toast if they later open the Queue, or vice versa: whichever
 * screen is actually on screen at the moment a transition happens is the one that shows it, and
 * never again after that, on either screen. */
@Composable
fun DownloadEventSnackbars(
    historyItems: List<DownloadEntity>,
    queueItems: List<DownloadEntity>,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()

    var seenFinishedIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(historyItems) {
        val currentIds = historyItems.map { it.id }.toSet()
        val previous = seenFinishedIds
        if (previous != null) {
            (currentIds - previous).forEach { id ->
                val item = historyItems.firstOrNull { it.id == id } ?: return@forEach
                scope.launch {
                    snackbarHostState.showSnackbar(
                        DownloadEventVisuals(isSuccess = true, message = "${item.title.ifBlank { "Download" }} finished")
                    )
                }
            }
        }
        seenFinishedIds = currentIds
    }

    var seenErroredIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(queueItems) {
        val erroredNow = queueItems.filter { it.status == DownloadStatus.ERRORED }.map { it.id }.toSet()
        val previous = seenErroredIds
        if (previous != null) {
            (erroredNow - previous).forEach { id ->
                val item = queueItems.firstOrNull { it.id == id } ?: return@forEach
                scope.launch {
                    snackbarHostState.showSnackbar(
                        DownloadEventVisuals(
                            isSuccess = false,
                            message = "${item.title.ifBlank { "Download" }} failed" +
                                (item.errorMessage?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                        )
                    )
                }
            }
        }
        seenErroredIds = erroredNow
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(top = 96.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
