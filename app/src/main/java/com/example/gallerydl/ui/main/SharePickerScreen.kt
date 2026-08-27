package com.example.gallerydl.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.ceil
import com.example.gallerydl.util.GalleryDlListing
import com.example.gallerydl.util.GalleryItem
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch

private enum class ListingState { LOADING, LOADED, UNAVAILABLE }

/** Shown when a link is shared in from another app (and instant mode is off): lets the user
 * preview the gallery's items and pick which ones to actually download, instead of always
 * fetching everything. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharePickerScreen(
    url: String,
    onDismiss: () -> Unit,
    onDownload: (url: String, itemFilter: String?, totalItems: Int) -> Unit,
    modifier: Modifier = Modifier,
    onHeightChange: (Dp) -> Unit = {},
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ListingState.LOADING) }
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var selectedNums by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val scope = rememberCoroutineScope()

    // Sizes the sheet to fit however many rows the picker actually needs (a 2-image post
    // shouldn't get the same tall sheet as a 40-image profile), capped so it never exceeds a
    // comfortable fraction of the screen — beyond that the grid scrolls internally instead.
    val configuration = LocalConfiguration.current
    LaunchedEffect(state, items.size, configuration.screenWidthDp, configuration.screenHeightDp) {
        val topBarHeight = 64.dp
        val bottomBarHeight = 84.dp
        val target = if (state == ListingState.LOADED && items.isNotEmpty()) {
            val gridPadding = 24.dp
            val spacing = 8.dp
            val columns = 3
            val cellSize = (configuration.screenWidthDp.dp - gridPadding - spacing * (columns - 1)) / columns
            val rows = ceil(items.size / columns.toFloat()).toInt()
            val gridHeight = cellSize * rows + spacing * (rows - 1).coerceAtLeast(0) + gridPadding
            topBarHeight + gridHeight + bottomBarHeight
        } else {
            280.dp
        }
        val maxHeight = configuration.screenHeightDp.dp * 0.92f
        val minHeight = 280.dp
        onHeightChange(target.coerceIn(minHeight, maxHeight))
    }

    LaunchedEffect(url) {
        state = ListingState.LOADING
        val found = GalleryDlListing.listItems(context, url)
        if (found.isEmpty()) {
            state = ListingState.UNAVAILABLE
        } else {
            items = found
            selectedNums = found.map { it.num }.toSet()
            state = ListingState.LOADED
        }
    }

    // A source that can't be listed (single-file links, unsupported extractors) falls back to
    // a normal whole-gallery download instead of leaving the user stuck on an empty picker.
    LaunchedEffect(state) {
        if (state == ListingState.UNAVAILABLE) {
            onDownload(url, null, 0)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state == ListingState.LOADED) "${selectedNums.size} of ${items.size} selected" else "Loading…",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(FeatherIcons.X, contentDescription = "Cancel")
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
        },
        bottomBar = {
            if (state == ListingState.LOADED) {
                Surface(color = Color.Transparent, tonalElevation = 3.dp) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            onClick = {
                                val filter = if (selectedNums.size == items.size) null
                                    else "num in {${selectedNums.sorted().joinToString(",")}}"
                                // The --filter above (when set) restricts the download to exactly
                                // these items, so this count is exact regardless of whether the
                                // picker's own listing got truncated at GalleryDlListing.MAX_ITEMS.
                                onDownload(url, filter, selectedNums.size)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                            enabled = selectedNums.isNotEmpty(),
                        ) {
                            Icon(FeatherIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download ${selectedNums.size}")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
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
                ListingState.UNAVAILABLE -> {
                    // Handled by the LaunchedEffect above (falls back to a full download); this
                    // frame is only visible for an instant before onDismiss/onDownload fires.
                }
                ListingState.LOADED -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.num }) { item ->
                            val selected = item.num in selectedNums
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable {
                                        selectedNums = if (selected) selectedNums - item.num else selectedNums + item.num
                                    },
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
                                                FeatherIcons.Image,
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
                                        Icon(FeatherIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
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
