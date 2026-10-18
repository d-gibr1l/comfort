package com.comfort.app.ui.viewer

import androidx.compose.ui.res.stringResource
import com.comfort.app.R
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** The filmstrip's height plus the gap under it: what a video's controls move up by over it. */
val FILMSTRIP_SPACE: Dp = 64.dp

/**
 * A carousel's files as a strip of small previews above the toolbar, the way Samsung's Gallery
 * shows its neighbours: the current one wider and outlined, a play mark on a video; tapping one
 * goes to it. Keeps the current one in view as the pages change.
 */
@Composable
fun Filmstrip(items: List<GalleryItem>, current: GalleryItem?, onPick: (GalleryItem) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val currentIndex = items.indexOfFirst { it.id == current?.id }
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().height(56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val selected = index == currentIndex
            val width by animateDpAsState(if (selected) 64.dp else 40.dp, label = "filmstripWidth")
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .width(width)
                    .height(if (selected) 56.dp else 48.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .clickable { onPick(item) },
            ) {
                AsyncImage(
                    // Thumbnail-sized: served from the system's own thumbnail cache.
                    model = ImageRequest.Builder(context).data(item.uri).size(192).build(),
                    contentDescription = stringResource(R.string.item_x_2, index + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(14.dp),
                    )
                }
            }
        }
    }
}
