package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.comfort.app.R
import com.comfort.app.viewmodel.UpdatesViewModel
import com.comfort.app.viewmodel.UpdatesViewModel.UpdatePrompt
import kotlinx.coroutines.launch

/** The launch check's popup (UpdatesViewModel.prompt), a bottom sheet: a new Comfort, or engine
 * updates waiting because auto-update is off. Update starts it here and the sheet stays up with
 * its progress until it's done (the app's installer then opens); Later just closes it. Hard to
 * swipe away by accident, like the other sheets (rememberSturdySheetState). */
@Composable
fun UpdatePromptDialog(updates: UpdatesViewModel) {
    when (val prompt = updates.prompt.collectAsState().value) {
        is UpdatePrompt.App -> key(prompt) { AppUpdatePrompt(updates, prompt) }
        is UpdatePrompt.Engines -> key(prompt) { EngineUpdatePrompt(updates, prompt) }
        null -> Unit
    }
}

/** The sheet both prompts share: a centred hero (artwork in a glow, a badge, a big title), then
 * [content], then a big [confirmLabel] button and Later under it. Later (and [closeWhen] turning
 * true) slides it away, then drops the prompt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatePromptSheet(
    updates: UpdatesViewModel,
    badge: String,
    title: String,
    artwork: @Composable () -> Unit,
    confirmLabel: String,
    confirmIcon: ImageVector,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    closeWhen: Boolean,
    // While it downloads, in place of the confirm button: stops it (the app's update).
    onCancel: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val sheetState = rememberSturdySheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { updates.dismissPrompt() }
    }
    LaunchedEffect(closeWhen) { if (closeWhen) close() }
    ModalBottomSheet(
        onDismissRequest = { updates.dismissPrompt() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // Edge to edge: the content runs under the navigation bar instead of stopping above it.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The artwork in a soft glow of the theme's colour.
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) { artwork() }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.NewReleases,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) { content() }
            Spacer(Modifier.height(20.dp))
            if (onCancel != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cancel_download), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            } else {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(confirmIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(confirmLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            }
            TextButton(onClick = close, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.later))
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** "1.3.1 → 1.3.1.1": the old version dimmed, the new one in the theme's colour. */
@Composable
private fun VersionPill(from: String, to: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(from, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp).size(14.dp),
            )
            Text(to, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AppUpdatePrompt(updates: UpdatesViewModel, prompt: UpdatePrompt.App) {
    val status = prompt.status
    val progress by updates.appDownloadProgress.collectAsState()
    val error by updates.appDownloadError.collectAsState()
    var started by remember { mutableStateOf(false) }
    UpdatePromptSheet(
        updates = updates,
        badge = stringResource(R.string.new_update),
        title = stringResource(R.string.comfort_x, status.latestVersion.orEmpty()),
        artwork = { AppIconTile(76.dp) },
        // With the download's size: "Update now · 34.6 MB".
        confirmLabel = status.downloadBytes?.let { stringResource(R.string.update_now_x, formatFileSize(it)) }
            ?: stringResource(R.string.update_now),
        confirmIcon = Icons.Outlined.Download,
        confirmEnabled = progress == null,
        onConfirm = {
            started = true
            updates.startAppDownload(status)
        },
        // Downloaded: the installer is up (or Android's "allow installing" screen, with its error).
        closeWhen = started && progress == null && error == null,
        // Cancelled, it stays open on Update now (started is what closes it once done).
        onCancel = if (progress != null) ({ started = false; updates.cancelAppDownload() }) else null,
    ) {
        VersionPill(status.installedVersion, status.latestVersion.orEmpty(), Modifier.align(Alignment.CenterHorizontally))
        val notes = remember(status.releaseNotes) { status.releaseNotes?.let(::plainNotes).orEmpty() }
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(stringResource(R.string.whats_new), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        ReleaseNotes(notes)
                    }
                }
            }
        }
        progress?.let { p ->
            Spacer(Modifier.height(16.dp))
            // How much of it: "12.3 MB of 34.6 MB".
            val total = status.downloadBytes
            Text(
                if (total != null) stringResource(R.string.downloading_x_of_y, formatFileSize((total * p).toLong()), formatFileSize(total))
                else stringResource(R.string.downloading_the_update),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
        error?.takeIf { started }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The notes [plainNotes] left: a line on its own is a section heading, in the theme's colour;
 * "• " lines are bullets, with a dot of that colour. */
@Composable
private fun ReleaseNotes(notes: String) {
    notes.lines().forEach { line ->
        val text = line.trim()
        when {
            text.isEmpty() -> Spacer(Modifier.height(10.dp))
            text.startsWith("• ") -> Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, end = 10.dp)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Text(
                    text.removePrefix("• "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun EngineUpdatePrompt(updates: UpdatesViewModel, prompt: UpdatePrompt.Engines) {
    val updating by updates.updatingEngines.collectAsState()
    val error by updates.engineError.collectAsState()
    var started by remember { mutableStateOf(false) }
    UpdatePromptSheet(
        updates = updates,
        badge = stringResource(R.string.engine_updates),
        title = stringResource(R.string.updates_available),
        artwork = {
            Box(
                modifier = Modifier.size(76.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Update, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
            }
        },
        confirmLabel = stringResource(R.string.update_now),
        confirmIcon = Icons.Outlined.Download,
        confirmEnabled = updating.isEmpty(),
        onConfirm = {
            started = true
            updates.updateEngines(prompt.statuses)
        },
        // All installed (each announces itself in a snackbar): closed, unless one failed.
        closeWhen = started && updating.isEmpty() && error == null,
    ) {
        Text(
            stringResource(R.string.auto_update_is_off_these_wait),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                prompt.statuses.forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(s.engine.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(12.dp))
                        VersionPill(s.installedVersion ?: "–", s.latestVersion.orEmpty())
                    }
                }
            }
        }
        if (updating.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        error?.takeIf { started }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A GitHub release's Markdown as plain text: no heading marks, bold or link syntax. Without the
 * release's own title (the sheet's says it) and its "Downloads" section (which APK to pick: the
 * update picks it). */
private fun plainNotes(markdown: String): String = markdown
    .lines()
    .map { line -> line.replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^[*-]\\s+"), "• ") }
    .dropWhile { it.isBlank() || it.trim().startsWith("Comfort ") }
    .takeWhile { it.trim() != "Downloads" }
    .joinToString("\n")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
    .replace(Regex("`([^`]*)`"), "$1")
    .trim()
