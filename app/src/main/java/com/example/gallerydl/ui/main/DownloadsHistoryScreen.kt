package com.example.gallerydl.ui.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import com.example.gallerydl.viewmodel.DownloadsViewModel
import com.example.gallerydl.data.DownloadEntity
import com.example.gallerydl.data.DownloadStatus
import kotlinx.coroutines.launch
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LibrarySort(val label: String) {
    DATE_NEWEST("Newest first"),
    DATE_OLDEST("Oldest first"),
    NAME("Name"),
    SIZE("Size"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsHistoryScreen(viewModel: DownloadsViewModel, onOpenQueue: () -> Unit) {
    val historyItems by viewModel.historyFlow.collectAsState()
    val hasActiveDownloads by viewModel.hasActiveDownloads.collectAsState()
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(LibrarySort.DATE_NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }
    BackHandler(enabled = showSearch && !selectionMode) { showSearch = false; searchQuery = "" }

    val visibleItems = historyItems
        .let { if (favoritesOnly) it.filter { item -> item.isFavorite } else it }
        .let { list ->
            if (searchQuery.isBlank()) list
            else list.filter {
                it.title.contains(searchQuery, ignoreCase = true) || it.url.contains(searchQuery, ignoreCase = true)
            }
        }
        .let { list ->
            when (sortOption) {
                LibrarySort.DATE_NEWEST -> list.sortedByDescending { it.dateAdded }
                LibrarySort.DATE_OLDEST -> list.sortedBy { it.dateAdded }
                LibrarySort.NAME -> list.sortedBy { it.title.ifBlank { it.url }.lowercase() }
                LibrarySort.SIZE -> list.sortedByDescending { it.totalBytes }
            }
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(FeatherIcons.X, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val selectedItems = historyItems.filter { it.id in selectedIds }
                            val uris = selectedItems.mapNotNull { it.thumbnailPath?.let { p -> Uri.parse(p) } }
                            if (uris.isNotEmpty()) {
                                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "image/*"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(Intent.createChooser(intent, "Share images")) }
                            }
                        }) {
                            Icon(FeatherIcons.Share2, contentDescription = "Share selected")
                        }
                        IconButton(onClick = {
                            selectedIds.forEach { viewModel.setFavorite(it, true) }
                            selectedIds = emptySet()
                        }) {
                            Icon(FeatherIcons.Star, contentDescription = "Add selected to favorites")
                        }
                        IconButton(onClick = {
                            selectedIds.forEach { viewModel.deleteDownload(it) }
                            selectedIds = emptySet()
                        }) {
                            Icon(FeatherIcons.Trash2, contentDescription = "Remove selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            } else if (showSearch) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search downloads") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { showSearch = false; searchQuery = "" }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Close search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Library", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(FeatherIcons.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(FeatherIcons.Sliders, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                                LibrarySort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = if (option == sortOption) {
                                            { Icon(FeatherIcons.Check, contentDescription = null) }
                                        } else null,
                                        onClick = { sortOption = option; sortMenuExpanded = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { favoritesOnly = !favoritesOnly }) {
                            Icon(
                                FeatherIcons.Star,
                                contentDescription = if (favoritesOnly) "Show all" else "Show favorites",
                                tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = onOpenQueue) {
                            if (hasActiveDownloads) {
                                BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                                    Icon(FeatherIcons.Download, contentDescription = "Active downloads")
                                }
                            } else {
                                Icon(FeatherIcons.Download, contentDescription = "Active downloads")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            }
        }
    ) { paddingValues ->
        if (visibleItems.isEmpty()) {
            val searching = searchQuery.isNotBlank()
            EmptyState(
                icon = if (searching) FeatherIcons.Search else if (favoritesOnly) FeatherIcons.Star else FeatherIcons.Image,
                title = if (searching) "No matches" else if (favoritesOnly) "No favorites yet" else "Nothing here yet",
                subtitle = if (searching) "Try a different search." else if (favoritesOnly) "Star a download to pin it here." else "Downloaded pictures will show up in this gallery.",
                modifier = Modifier.padding(paddingValues),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visibleItems, key = { it.id }) { item ->
                    val row = @Composable {
                        HistoryRow(
                            item = item,
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onTap = {
                                if (selectionMode) {
                                    selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                                }
                            },
                            onLongPress = { selectedIds = selectedIds + item.id },
                            onDelete = { viewModel.deleteDownload(item.id) },
                            onToggleFavorite = { viewModel.setFavorite(item.id, !item.isFavorite) },
                            onRename = { newTitle -> viewModel.renameDownload(item.id, newTitle) },
                        )
                    }
                    if (selectionMode) {
                        row()
                    } else {
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
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    item: DownloadEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val hasThumbnail = !item.thumbnailPath.isNullOrBlank()
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            currentTitle = item.title.ifBlank { item.url },
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle -> onRename(newTitle); showRenameDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onTap()
                    } else if (hasThumbnail) {
                        val uri = Uri.parse(item.thumbnailPath)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(76.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                if (hasThumbnail) {
                    AsyncImage(
                        model = item.thumbnailPath,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val icon = if (item.status == DownloadStatus.ERRORED) FeatherIcons.AlertTriangle else FeatherIcons.Image
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
                    }
                }

                if (item.downloadedItems > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(FeatherIcons.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${item.downloadedItems}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (item.isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(FeatherIcons.Star, contentDescription = "Favorite", tint = Color(0xFFFACC15), modifier = Modifier.size(11.dp))
                    }
                }
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FeatherIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
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
            MetaRow(icon = FeatherIcons.Layers, text = "${item.downloadedItems} • ${sdf.format(Date(item.dateAdded))}")
            Spacer(Modifier.height(2.dp))
            MetaRow(icon = FeatherIcons.HardDrive, text = "Size • ${formatFileSize(item.totalBytes)}")
        }

        // Bulk actions in the selection-mode top bar replace the per-item menu while selecting.
        if (!selectionMode) Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(FeatherIcons.MoreVertical, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (item.isFavorite) "Remove from Favorites" else "Add to Favorites") },
                    leadingIcon = { Icon(FeatherIcons.Star, contentDescription = null) },
                    onClick = { menuExpanded = false; onToggleFavorite() },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(FeatherIcons.Edit2, contentDescription = null) },
                    onClick = { menuExpanded = false; showRenameDialog = true },
                )
                DropdownMenuItem(
                    text = { Text("Share Image") },
                    leadingIcon = { Icon(FeatherIcons.Share2, contentDescription = null) },
                    enabled = hasThumbnail,
                    onClick = {
                        menuExpanded = false
                        val uri = Uri.parse(item.thumbnailPath)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(intent, "Share image")) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("View Original Source") },
                    leadingIcon = { Icon(FeatherIcons.ExternalLink, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy Post Link") },
                    leadingIcon = { Icon(FeatherIcons.Copy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        scope.launch {
                            clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("Post link", item.url)))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(FeatherIcons.Trash2, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun MetaRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
