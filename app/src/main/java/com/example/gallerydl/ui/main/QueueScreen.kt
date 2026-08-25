package com.example.gallerydl.ui.main

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import com.example.gallerydl.viewmodel.DownloadsViewModel
import com.example.gallerydl.data.DownloadEntity
import com.example.gallerydl.data.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit
) {
    val queueItems by viewModel.queueFlow.collectAsState()
    val isGloballyPaused by viewModel.isGloballyPaused.collectAsState()
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Running", "In Queue", "Errored", "Cancelled")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (queueItems.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { if (isGloballyPaused) viewModel.resumeAll() else viewModel.pauseAll() },
                    icon = {
                        Icon(
                            if (isGloballyPaused) FeatherIcons.Play else FeatherIcons.Pause,
                            contentDescription = null,
                        )
                    },
                    text = { Text(if (isGloballyPaused) "Resume" else "Pause") },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) },
                        shape = com.example.gallerydl.theme.PillShape
                    )
                }
            }

            val filteredItems = queueItems.filter { item ->
                when (selectedFilter) {
                    "Running" -> item.status == DownloadStatus.RUNNING
                    "In Queue" -> item.status == DownloadStatus.QUEUED
                    "Errored" -> item.status == DownloadStatus.ERRORED
                    "Cancelled" -> item.status == DownloadStatus.CANCELLED
                    else -> true
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredItems, key = { it.id }) { item ->
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
                            QueueItemCard(
                                item = item,
                                onCancel = { viewModel.deleteDownload(item.id) },
                                onPauseResume = { viewModel.pauseDownload(item.id) }, // TODO toggle
                                onRetry = { viewModel.retryDownload(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueItemCard(
    item: DownloadEntity,
    onCancel: () -> Unit,
    onPauseResume: () -> Unit,
    onRetry: () -> Unit
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
                    val icon = when (item.status) {
                        DownloadStatus.ERRORED -> FeatherIcons.AlertTriangle
                        DownloadStatus.QUEUED -> FeatherIcons.Clock
                        else -> FeatherIcons.DownloadCloud
                    }
                    val tint = if (item.status == DownloadStatus.ERRORED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Icon(icon, contentDescription = null, tint = tint)
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
                    Text(
                        text = if (item.status == DownloadStatus.CANCELLED) "Paused" else item.status.name.lowercase().replaceFirstChar { it.uppercase() },
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
                // M3 Expressive's wavy indeterminate track — gallery-dl never reports a total
                // item count, so there's no real fraction to animate toward, only "in progress".
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    amplitude = 0.4f,
                    wavelength = 40.dp,
                    waveSpeed = 8.dp,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${item.downloadedItems} downloaded",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    DownloadStatus.ERRORED, DownloadStatus.CANCELLED -> {
                        TextButton(onClick = onRetry) {
                            Text(if (item.status == DownloadStatus.CANCELLED) "Resume" else "Retry")
                        }
                        IconButton(onClick = onCancel) {
                            Icon(FeatherIcons.Trash2, contentDescription = "Remove")
                        }
                    }
                    DownloadStatus.QUEUED -> {
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
