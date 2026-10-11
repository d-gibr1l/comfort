package com.comfort.app.ui.main

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.comfort.app.data.DownloadEntity
import com.comfort.app.util.VideoCompressor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Library ⋮ > Compress video: pick a smaller size, then the same dialog shows the re-encode's
 * progress until it's saved. Only sizes below the video's own are offered. Closing the dialog
 * while it runs (Cancel, back) stops it and keeps nothing. */
@Composable
internal fun CompressDialog(item: DownloadEntity, fileUri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by produceState<VideoCompressor.Info?>(null, fileUri) { value = VideoCompressor.info(context, fileUri) }
    val targets = remember(info) { VideoCompressor.TARGETS.filter { t -> info?.let { t < it.shortSide } ?: false } }
    var target by remember(targets) { mutableStateOf(targets.firstOrNull()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val running = job?.isActive == true

    fun close() {
        job?.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(if (running) "Compressing…" else "Compress video") },
        text = {
            Column {
                val current = info
                when {
                    running -> {
                        Text(
                            "Making a ${target}p copy. The original stays as it is.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    current == null -> Text("Reading the video…", style = MaterialTheme.typography.bodyMedium)
                    targets.isEmpty() -> Text(
                        "This video is already ${current.shortSide}p, as small as Compress makes it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            "Saves a smaller copy next to the original (${current.shortSide}p, ${formatFileSize(current.bytes)}).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            targets.forEachIndexed { index, t ->
                                val selected = target == t
                                val interactionSource = remember { MutableInteractionSource() }
                                FilterChip(
                                    selected = selected,
                                    onClick = { target = t },
                                    modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.RadioButton },
                                    interactionSource = interactionSource,
                                    shape = rememberMorphingChipShape(index, targets.size, selected = selected, interactionSource = interactionSource, height = 40.dp),
                                    label = {
                                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text("${t}p", style = MaterialTheme.typography.labelLarge)
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    border = null,
                                )
                            }
                        }
                        target?.let { t ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "About ${formatFileSize(VideoCompressor.estimateBytes(current, t))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!running && targets.isNotEmpty()) {
                TextButton(
                    enabled = target != null,
                    onClick = {
                        val t = target ?: return@TextButton
                        progress = 0f
                        job = scope.launch {
                            val saved = VideoCompressor.compress(context, item, fileUri, t) { progress = it }
                            Toast.makeText(
                                context,
                                if (saved != null) "Saved the ${t}p copy" else "Couldn't compress this video",
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDismiss()
                        }
                    },
                ) { Text("Compress") }
            }
        },
        dismissButton = {
            TextButton(onClick = ::close) { Text("Cancel") }
        },
    )
}
