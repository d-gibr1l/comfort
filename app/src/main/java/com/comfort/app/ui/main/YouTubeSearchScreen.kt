package com.comfort.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.comfort.app.R
import com.comfort.app.util.SearchResult
import com.comfort.app.util.YouTubeSearch
import kotlinx.coroutines.launch

/**
 * YouTube search, opened from Home when what's typed isn't a link. A tap on a result opens it
 * like a pasted link (the preview sheet, to pick quality and so on); its download button starts
 * it straight away. More results load as the list nears its end.
 */
@Composable
internal fun YouTubeSearchScreen(
    initialQuery: String,
    onDismiss: () -> Unit,
    onConfigure: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf(initialQuery) }
    // The query the results are for (the field can be edited without searching yet).
    var searched by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by each new search, so a slow older one can't land on top of it.
    var generation by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    fun loadPage(next: Int) {
        if (loading) return
        val gen = generation
        loading = true
        error = null
        scope.launch {
            val result = YouTubeSearch.search(context, searched, next)
            if (gen != generation) return@launch
            result.onSuccess { found ->
                val known = results.mapTo(HashSet()) { it.id }
                val fresh = found.filter { it.id !in known }
                results = results + fresh
                page = next
                if (found.size < YouTubeSearch.PAGE_SIZE || fresh.isEmpty()) endReached = true
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    fun newSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        focusManager.clearFocus()
        generation++
        loading = false
        searched = q
        results = emptyList()
        page = 0
        endReached = false
        scope.launch { listState.scrollToItem(0) }
        loadPage(1)
    }

    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) loadPage(1) }

    // The next page once the last few results are on screen.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            results.isNotEmpty() && last >= results.size - 4
        }
    }
    LaunchedEffect(nearEnd, loading, endReached, error) {
        if (nearEnd && !loading && !endReached && error == null && page > 0) loadPage(page + 1)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.search_youtube_hint)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { newSearch() }),
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(results, key = { it.id }) { result ->
                            SearchResultRow(
                                result = result,
                                onClick = { onConfigure(result.url) },
                                onDownload = { onDownload(result.url) },
                            )
                        }
                        if (results.isNotEmpty() && (loading || error != null)) item(key = "footer") {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (loading) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                else TextButton(onClick = { loadPage(page + 1) }) { Text(stringResource(R.string.search_retry)) }
                            }
                        }
                    }
                    when {
                        results.isEmpty() && loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        results.isEmpty() && error != null -> Column(
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(stringResource(R.string.search_failed), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { loadPage(1) }) { Text(stringResource(R.string.search_retry)) }
                        }
                        results.isEmpty() && page > 0 -> Text(
                            stringResource(R.string.search_no_results, searched),
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(144.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = result.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val badge = when {
                result.live -> stringResource(R.string.search_live)
                result.durationSeconds != null -> formatDuration(result.durationSeconds)
                else -> null
            }
            if (badge != null) Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val details = listOfNotNull(
                result.channel.takeIf { it.isNotBlank() },
                result.views?.let { pluralStringResource(R.plurals.search_views, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compactCount(it)) },
            ).joinToString(" · ")
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.search_download))
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 1234567 as "1.2M": the locale's own short form. */
private fun compactCount(n: Long): String =
    android.icu.text.CompactDecimalFormat
        .getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT)
        .format(n)
