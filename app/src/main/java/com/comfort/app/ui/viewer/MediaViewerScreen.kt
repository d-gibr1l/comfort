/*
 * Ported from Photon Gallery (https://github.com/Bn5prS/Photon_Gallery), GPL-3.0, like Comfort.
 * Adapted for Comfort: package, imports and the pieces Photon's app provides that Comfort doesn't
 * (see the notes where they were changed).
 */
package com.comfort.app.ui.viewer

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.FilledTonalIconButton
import android.content.Context

import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.vectorResource
import com.comfort.app.R
import androidx.compose.ui.graphics.vector.ImageVector




// Comfort: the items and what the buttons do come from the caller (MediaViewerHost) instead of
// Photon's GalleryViewModel; Photon's own defaults stand in for its settings (no forced max
// brightness, no blurred backdrop, no delete confirmation — Comfort's delete has Undo instead of
// a recycle bin). Photon's OCR search highlight, album copy/move, private vault and photo editor
// have no counterpart here and were left out; the ⋮ menu moved into the bottom toolbar.
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun MediaViewerScreen(
    galleryItems: List<GalleryItem>,
    mediaId: String,
    favoriteIds: Set<String>,
    onToggleFavorite: (GalleryItem) -> Unit,
    onDelete: (GalleryItem) -> Unit,
    /** The name the Rename dialog starts from, and what saving one does. */
    renameText: (GalleryItem) -> String,
    onRename: (GalleryItem, String) -> Unit,
    /** Which post an item belongs to: a post with several files gets a filmstrip of them. */
    groupKey: (GalleryItem) -> String = { it.id },
    /** Extra entries for the ⋮ menu (the post's link, ...); call close() when one is picked. */
    extraMenuItems: @Composable (item: GalleryItem, close: () -> Unit) -> Unit = { _, _ -> },
    useFullScreenGlobal: Boolean = false,
    /** Videos start muted (the ⋮ menu's "Play videos with sound" off). */
    startMuted: Boolean = true,
    /** The ⋮ menu's "Play videos with sound": remembered for next time by the caller. */
    onSoundSettingChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val window = activity?.window
    val insetsController = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }

    val maxBrightnessEnabled = false
    val viewerBlurEffect = false

    // Overrides screen brightness to maximum in fullscreen and restores it when leaving
    androidx.compose.runtime.DisposableEffect(maxBrightnessEnabled) {
        if (maxBrightnessEnabled) {
            val layoutParams = window?.attributes
            if (layoutParams != null) {
                val originalBrightness = layoutParams.screenBrightness
                layoutParams.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                window.attributes = layoutParams

                onDispose {
                    val currentParams = window.attributes
                    if (currentParams != null) {
                        currentParams.screenBrightness = originalBrightness
                        window.attributes = currentParams
                    }
                }
            } else {
                onDispose {}
            }
        } else {
            onDispose {}
        }
    }

    // Ensure we don't crash if items is empty
    if (galleryItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest))
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val initialPage = remember(mediaId) {
        val index = galleryItems.indexOfFirst { it.id == mediaId }
        if (index >= 0) index else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { galleryItems.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        val imageLoader = context.imageLoader
        for (offset in -2..2) {
            if (offset == 0) continue
            val prefetchItem = galleryItems.getOrNull(pagerState.currentPage + offset) ?: continue
            if (prefetchItem.isVideo) continue
            val prefetchUri = prefetchItem.uri
            val req = coil.request.ImageRequest.Builder(context)
                .data(prefetchUri)
                .size(coil.size.Size.ORIGINAL)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .build()
            imageLoader.enqueue(req)
        }
    }

    val currentItem = galleryItems.getOrNull(pagerState.currentPage)

    var isLocalFileMissing by remember(currentItem) { mutableStateOf(false) }
    LaunchedEffect(currentItem) {
        if (currentItem != null) {
            isLocalFileMissing = withContext(Dispatchers.IO) {
                currentItem.path.isNotBlank() && !java.io.File(currentItem.path).exists()
            }
        }
    }


    val resolvedCurrentUri by produceState<Uri?>(initialValue = currentItem?.uri, key1 = currentItem) {
        val uri = currentItem?.uri
        value = uri
    }


    var activeHighlight by remember { mutableStateOf<String?>(null) }
    var highlightRects by remember { mutableStateOf<List<android.graphics.Rect>>(emptyList()) }
    var highlightImageSize by remember { mutableStateOf<androidx.compose.ui.geometry.Size?>(null) }
    var highlightOverlayVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
    val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()

    var showUi by remember { mutableStateOf(true) }

    // Comfort: videos' sound is one setting for the whole viewer (muted to start unless Settings'
    // "Play videos with sound" is on — Photon's "Autoplay with sound"),
    // so it carries over from video to video; volume up unmutes (see the key handler below).
    var videosMuted by remember { mutableStateOf(startMuted) }
    // Whether the video on screen is playing (reported by its VideoPlayerItem).
    var currentVideoPlaying by remember { mutableStateOf(true) }

    // Auto hide UI after 6 seconds of inactivity (e.g., no swiping/page change). Comfort: 6 s, not
    // Photon's 4, and never while the video on screen is paused, so its controls stay at hand.
    val pausedVideo = currentItem?.isVideo == true && !currentVideoPlaying
    LaunchedEffect(showUi, pagerState.isScrollInProgress, pagerState.currentPage, pausedVideo) {
        if (showUi && !pausedVideo) {
            kotlinx.coroutines.delay(6000)
            showUi = false
        }
    }

    // Comfort: volume up unmutes, as in most galleries; the key isn't consumed, so it still turns
    // the volume up. The viewer takes focus so the key reaches it.
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }

    var currentScale by remember { mutableStateOf(1f) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // Comfort: the tracks of the video on screen (with its page, so a page swiped away doesn't
    // leave its tracks behind), and which picker of the ⋮ menu is open.
    var pageTracks by remember { mutableStateOf<Pair<Int, VideoTracks?>?>(null) }
    var trackPicker by remember { mutableStateOf<Int?>(null) }

    androidx.compose.runtime.DisposableEffect(useFullScreenGlobal) {
        if (window != null && insetsController != null) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

            if (useFullScreenGlobal) {
                // Global full screen mode is enabled -> hide system bars
                insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // Global full screen mode is disabled -> show system bars
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }

        onDispose {
            // Restore to the global state when leaving DetailScreen
            if (window != null && insetsController != null) {
                if (!useFullScreenGlobal) {
                    insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    // Comfort: the ⋮ menu's track pickers.
    pageTracks?.takeIf { it.first == pagerState.currentPage }?.second?.let { tracks ->
        trackPicker?.let { type ->
            TrackPickerDialog(
                tracks = tracks,
                subtitles = type == androidx.media3.common.C.TRACK_TYPE_TEXT,
                onDismiss = { trackPicker = null },
            )
        }
    }
    if (showRenameDialog && currentItem != null) {
        var renameValue by remember(currentItem) { mutableStateOf(renameText(currentItem)) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.rename)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        label = { androidx.compose.material3.Text(stringResource(R.string.command_template_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val trimmed = renameValue.trim()
                        if (trimmed.isNotEmpty() && trimmed != renameText(currentItem)) {
                            onRename(currentItem, trimmed)
                        }
                        showRenameDialog = false
                    }
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showRenameDialog = false }
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    var showInfoCard by remember { mutableStateOf(false) }
    var currentExif by remember { mutableStateOf<DetailedExifData?>(null) }
    

    androidx.compose.runtime.LaunchedEffect(currentItem, showInfoCard) {
        if (showInfoCard && currentItem != null) {
            currentExif = extractDetailedExif(context, currentItem)
        }
    }


    // ── Swipe-to-dismiss state ─────────────────────────────────────────────
    // Vertical drag offset (px). When the user drags the image downward at
    // 1× zoom, this translates the pager and fades the black scrim.
    val dismissOffsetY = remember { Animatable(0f) }
    // Comfort: only a drag down closes the viewer (a drag up opens the info sheet), so only that
    // one fades and shrinks the page.
    val dismissProgress = (dismissOffsetY.value.coerceAtLeast(0f) / 600f).coerceIn(0f, 1f)

    // Comfort: with the controls hidden, the bands above and below the picture go black and the
    // system bars hide, as in other galleries; they come back with the controls.
    val backdrop by androidx.compose.animation.animateColorAsState(
        if (showUi) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Black,
        animationSpec = androidx.compose.animation.core.tween(250),
        label = "viewerBackdrop",
    )
    LaunchedEffect(showUi) {
        if (window != null && insetsController != null) {
            if (showUi) {
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    val bgAlpha = 1f - dismissProgress          // scrim fades out
    val dismissScale = 1f - dismissProgress * 0.15f // subtle shrink

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keyFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == androidx.compose.ui.input.key.Key.VolumeUp &&
                    event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                ) {
                    videosMuted = false
                }
                false
            }
            .background(backdrop.copy(alpha = bgAlpha)),
        contentAlignment = Alignment.Center
    ) {
        if (viewerBlurEffect && currentItem != null) {
            val blurRequest = remember(currentItem.uri) {
                ImageRequest.Builder(context)
                    .data(currentItem.uri)
                    .size(300, 300)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = blurRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(20.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .alpha(0.4f * bgAlpha)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f * bgAlpha))
                )
            }
        }



        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentScale <= 1.05f && dismissOffsetY.value == 0f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissOffsetY.value
                    scaleX = dismissScale
                    scaleY = dismissScale
                }
                .pointerInput(currentScale) {
                    // Swipe-to-dismiss: only when not zoomed in.
                    if (currentScale > 1.05f) return@pointerInput
                    val dismissThreshold = 200f
                    val velocityThreshold = 800f
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity().y
                            coroutineScope.launch {
                                val current = dismissOffsetY.value
                                // Comfort: down closes, up opens the info sheet (Photon closed on both).
                                if (current > 0f && (current > dismissThreshold || velocity > velocityThreshold)) {
                                    // Commit dismiss — trigger existing back transition
                                    onBack()
                                } else {
                                    if (current < 0f && (current < -INFO_SWIPE_PX || velocity < -velocityThreshold)) {
                                        showInfoCard = true
                                    }
                                    // Snap back with spring
                                    dismissOffsetY.animateTo(
                                        0f,
                                        animationSpec = MotionTokens.bouncySpring()
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                dismissOffsetY.animateTo(
                                    0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        }
                    ) { change, dragAmount ->
                        velocityTracker.addPointerInputChange(change)
                        coroutineScope.launch {
                            // Comfort: upward, the page only follows a little (it opens the sheet).
                            val step = if (dismissOffsetY.value + dragAmount < 0f) dragAmount * 0.35f else dragAmount
                            dismissOffsetY.snapTo(dismissOffsetY.value + step)
                        }
                    }
                },
            // Comfort: by id (unique per page, unlike the file's Uri across posts), so the page on
            // screen stays put while the host fills in the pages around it.
            key = { page -> galleryItems.getOrNull(page)?.id ?: page.toString() }
        ) { page ->
            val item = galleryItems.getOrNull(page) ?: return@HorizontalPager
            val resolvedUri by produceState(initialValue = item.uri, key1 = item) {
                value = item.uri
            }

            val request: coil.request.ImageRequest = remember(resolvedUri) {
                ImageRequest.Builder(context)
                    .data(resolvedUri)
                    .size(coil.size.Size.ORIGINAL)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    // Comfort: the Library's thumbnail of it (Photon's grid key isn't Comfort's).
                    .placeholderMemoryCacheKey(com.comfort.app.util.thumbnailCacheKey(item.id))
                    .crossfade(true)
                    .build()
            }
            

            
            var imageAspectRatio by remember(item) { mutableStateOf(1f) }
            val scale = remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
            val offsetX = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val offsetY = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val animJob = remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }

            androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage != page) {
                    scale.floatValue = 1f
                    offsetX.floatValue = 0f
                    offsetY.floatValue = 0f
                } else {
                    currentScale = scale.floatValue
                }
            }
            
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val absoluteOffset = kotlin.math.abs(pageOffset)
            val pagerScale = 1f - (absoluteOffset.coerceIn(0f, 1f) * 0.05f)
            val pagerAlpha = 1f - (absoluteOffset.coerceIn(0f, 1f) * 0.5f)

            androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val parentWidth = constraints.maxWidth.toFloat()
                val parentHeight = constraints.maxHeight.toFloat()
                if (item.isVideo) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        VideoPlayerItem(
                            uri = resolvedUri,
                            placeholderKey = com.comfort.app.util.thumbnailCacheKey(item.id),
                            isCurrentPage = page == pagerState.currentPage,
                            showControls = showUi,
                            muted = videosMuted,
                            onMutedChange = { videosMuted = it },
                            onPlayingChange = { playing -> if (page == pagerState.currentPage) currentVideoPlaying = playing },
                            onTracksChange = { tracks -> pageTracks = page to tracks },
                            // Comfort: above a carousel's filmstrip.
                            controlsBottomPadding = if (galleryItems.count { groupKey(it) == groupKey(item) } > 1) 64.dp + FILMSTRIP_SPACE else 64.dp,
                            modifier = Modifier.fillMaxSize(),
                            onTap = {
                                if (showInfoCard || showUi) {
                                    showInfoCard = false
                                    showUi = false
                                } else {
                                    showUi = true
                                }
                            }
                        )
                    }
                } else {
                    run {
                        AsyncImage(
                            model = request,
                            contentDescription = stringResource(R.string.full_screen_photo),
                            contentScale = ContentScale.Fit,
                            onSuccess = { state ->
                                val size = state.painter.intrinsicSize
                                if (size.width > 0f && size.height > 0f && size.width.isFinite() && size.height.isFinite()) {
                                    imageAspectRatio = size.width / size.height
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                // IMPORTANT: pointerInput MUST be before graphicsLayer.
                                // When pointerInput is placed after graphicsLayer, Compose
                                // inverse-transforms all pointer coordinates through the layer's
                                // scale matrix (divides by scaleX/Y). At 5x zoom that makes
                                // calculatePan() return 1/5 of the actual finger delta, causing
                                // pan to be 5x slower than the finger. With pointerInput first
                                // (outer), events arrive in screen/layout space — 1:1 with finger.
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            activeHighlight = null
                                            highlightOverlayVisible = false
                                            if (showInfoCard || showUi) {
                                                showInfoCard = false
                                                showUi = false
                                            } else {
                                                showUi = true
                                            }
                                        },
                                        onDoubleTap = { centroid ->
                                            animJob.value?.cancel()
                                            animJob.value = coroutineScope.launch {
                                                val composableW = size.width.toFloat()
                                                val composableH = size.height.toFloat()
                                                if (scale.floatValue > 1f) {
                                                    // Zoom out to fit
                                                    currentScale = 1f
                                                    launch { androidx.compose.animation.core.animate(scale.floatValue, 1f, animationSpec = MotionTokens.gentleSpring()) { v, _ -> scale.floatValue = v } }
                                                    launch { androidx.compose.animation.core.animate(offsetX.floatValue, 0f, animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetX.floatValue = v } }
                                                    launch { androidx.compose.animation.core.animate(offsetY.floatValue, 0f, animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetY.floatValue = v } }
                                                } else {
                                                    // Zoom in to tapped point
                                                    val targetScale = 3.5f
                                                    currentScale = targetScale
                                                    val targetX = -(centroid.x - composableW / 2f) * (targetScale - 1)
                                                    val targetY = -(centroid.y - composableH / 2f) * (targetScale - 1)
                                                    val maxX = (composableW * (targetScale - 1)) / 2f
                                                    val maxY = (composableH * (targetScale - 1)) / 2f
                                                    launch { androidx.compose.animation.core.animate(scale.floatValue, targetScale, animationSpec = MotionTokens.gentleSpring()) { v, _ -> scale.floatValue = v } }
                                                    launch { androidx.compose.animation.core.animate(offsetX.floatValue, targetX.coerceIn(-maxX, maxX), animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetX.floatValue = v } }
                                                    launch { androidx.compose.animation.core.animate(offsetY.floatValue, targetY.coerceIn(-maxY, maxY), animationSpec = MotionTokens.gentleSpring()) { v, _ -> offsetY.floatValue = v } }
                                                }
                                            }
                                        }
                                    )
                                }
                                .pointerInput(Unit) {
                                    // Unified gesture handler: pinch-zoom, pan
                                    awaitEachGesture {
                                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                                        val downTime = System.currentTimeMillis()
                                        val downPosition = firstDown.position
                                        
                                        var shouldStartZoomPan = scale.floatValue > 1f
                                        if (!shouldStartZoomPan) {
                                            // Wait to see if a second finger is placed down (pinch-to-zoom)
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.changes.any { it.isConsumed }) break
                                                if (event.changes.size > 1) {
                                                    shouldStartZoomPan = true
                                                    break
                                                }
                                                if (!event.changes.any { it.pressed }) break
                                            }
                                        }
                                        
                                        if (shouldStartZoomPan) {
                                            var accumulatedZoom = 1f
                                            var accumulatedPan = Offset.Zero
                                            var pastTouchSlop = false
                                            val touchSlop = viewConfiguration.touchSlop
                                            val composableW = size.width.toFloat()
                                            val composableH = size.height.toFloat()
                                            val velocityTracker = VelocityTracker()
                                            var maxPointerCount = 0

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.changes.any { it.isConsumed }) break

                                                val pressedCount = event.changes.count { it.pressed }
                                                maxPointerCount = maxOf(maxPointerCount, pressedCount)

                                                if (pressedCount == 1) {
                                                    event.changes.firstOrNull { it.pressed }
                                                        ?.let { velocityTracker.addPointerInputChange(it) }
                                                }

                                                val zoomChange = event.calculateZoom()
                                                val panChange = event.calculatePan()

                                                if (!pastTouchSlop) {
                                                    accumulatedZoom *= zoomChange
                                                    accumulatedPan += panChange
                                                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                                    val zoomMotion = kotlin.math.abs(1 - accumulatedZoom) * centroidSize
                                                    val panMotion = accumulatedPan.getDistance()
                                                    if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                                        pastTouchSlop = true
                                                    }
                                                }

                                                if (pastTouchSlop && scale.floatValue <= 1.05f && maxPointerCount == 1) {
                                                    break
                                                }

                                                if (pastTouchSlop && (zoomChange != 1f || panChange != Offset.Zero)) {
                                                    val centroid = event.calculateCentroid(useCurrent = false)
                                                    animJob.value?.cancel()
                                                    val newScale = (scale.floatValue * zoomChange).coerceIn(1f, 20f)

                                                    if (newScale > 1.02f) showUi = false
                                                    if (newScale > 1.05f && currentScale <= 1.05f) {
                                                        currentScale = newScale
                                                    } else if (newScale <= 1.05f && currentScale > 1.05f) {
                                                        currentScale = newScale
                                                    }

                                                    val maxX = (composableW * (newScale - 1)) / 2f
                                                    val maxY = (composableH * (newScale - 1)) / 2f

                                                    val focalX = centroid.x - composableW / 2f
                                                    val focalY = centroid.y - composableH / 2f
                                                    val effectiveZoom = newScale / scale.floatValue
                                                    var newOffsetX = (offsetX.floatValue - focalX) * effectiveZoom + focalX + panChange.x
                                                    var newOffsetY = (offsetY.floatValue - focalY) * effectiveZoom + focalY + panChange.y

                                                    newOffsetX = newOffsetX.coerceIn(-maxX, maxX)
                                                    newOffsetY = newOffsetY.coerceIn(-maxY, maxY)

                                                    scale.floatValue = newScale
                                                    offsetX.floatValue = newOffsetX
                                                    offsetY.floatValue = newOffsetY

                                                    if (newScale > 1f) {
                                                        val hittingLeft = newOffsetX >= maxX && panChange.x > 0
                                                        val hittingRight = newOffsetX <= -maxX && panChange.x < 0
                                                        if (!hittingLeft && !hittingRight) {
                                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                        }
                                                    }
                                                }

                                                if (!event.changes.any { it.pressed }) break
                                            }

                                            if (pastTouchSlop && scale.floatValue > 1.05f && maxPointerCount == 1) {
                                                val velocity = velocityTracker.calculateVelocity()
                                                val currentMaxX = (composableW * (scale.floatValue - 1)) / 2f
                                                val currentMaxY = (composableH * (scale.floatValue - 1)) / 2f
                                                val flingFactor = 0.4f
                                                val targetFlingX = (offsetX.floatValue + velocity.x * flingFactor)
                                                    .coerceIn(-currentMaxX, currentMaxX)
                                                val targetFlingY = (offsetY.floatValue + velocity.y * flingFactor)
                                                    .coerceIn(-currentMaxY, currentMaxY)
                                                animJob.value?.cancel()
                                                animJob.value = coroutineScope.launch {
                                                    launch {
                                                        androidx.compose.animation.core.animate(
                                                            initialValue = offsetX.floatValue,
                                                            targetValue = targetFlingX,
                                                            initialVelocity = velocity.x,
                                                            animationSpec = spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessVeryLow
                                                            )
                                                        ) { v, _ -> offsetX.floatValue = v }
                                                    }
                                                    launch {
                                                        androidx.compose.animation.core.animate(
                                                            initialValue = offsetY.floatValue,
                                                            targetValue = targetFlingY,
                                                            initialVelocity = velocity.y,
                                                            animationSpec = spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessVeryLow
                                                            )
                                                        ) { v, _ -> offsetY.floatValue = v }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = scale.floatValue * pagerScale
                                    scaleY = scale.floatValue * pagerScale
                                    alpha = pagerAlpha
                                    translationX = offsetX.floatValue
                                    translationY = offsetY.floatValue
                                }
                        )
                        
                        if (activeHighlight != null) {
                            val highlightColor = MaterialTheme.colorScheme.primary
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale.floatValue * pagerScale
                                        scaleY = scale.floatValue * pagerScale
                                        alpha = pagerAlpha
                                        translationX = offsetX.floatValue
                                        translationY = offsetY.floatValue
                                    }
                            ) {
                                val iSize = highlightImageSize
                                val hasRects = iSize != null && highlightRects.isNotEmpty()
                                
                                val fitScale = if (hasRects) kotlin.math.min(size.width / iSize!!.width, size.height / iSize.height) else 1f
                                val dx = if (hasRects) (size.width - iSize!!.width * fitScale) / 2f else 0f
                                val dy = if (hasRects) (size.height - iSize!!.height * fitScale) / 2f else 0f
                                val padding = 6.dp.toPx()

                                if (highlightOverlayVisible) {
                                    if (hasRects) {
                                        val clipPath = androidx.compose.ui.graphics.Path().apply {
                                            for (rect in highlightRects) {
                                                val rLeft = dx + rect.left * fitScale - padding
                                                val rTop = dy + rect.top * fitScale - padding
                                                val rWidth = (rect.right - rect.left) * fitScale + (padding * 2)
                                                val rHeight = (rect.bottom - rect.top) * fitScale + (padding * 2)
                                                addRoundRect(androidx.compose.ui.geometry.RoundRect(
                                                    rLeft, rTop, rLeft + rWidth, rTop + rHeight,
                                                    androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                                                ))
                                            }
                                        }
                                        clipPath(path = clipPath, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
                                            drawRect(Color.Black.copy(alpha = 0.6f))
                                        }
                                    } else {
                                        drawRect(Color.Black.copy(alpha = 0.6f))
                                    }
                                }
                                
                                if (hasRects) {
                                    for (rect in highlightRects) {
                                        val rLeft = dx + rect.left * fitScale - padding
                                        val rTop = dy + rect.top * fitScale - padding
                                        val rWidth = (rect.right - rect.left) * fitScale + (padding * 2)
                                        val rHeight = (rect.bottom - rect.top) * fitScale + (padding * 2)
                                        
                                        drawRoundRect(
                                            color = highlightColor.copy(alpha = 0.4f),
                                            topLeft = Offset(rLeft, rTop),
                                            size = androidx.compose.ui.geometry.Size(rWidth, rHeight),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                                        )
                                        drawRoundRect(
                                            color = highlightColor,
                                            topLeft = Offset(rLeft, rTop),
                                            size = androidx.compose.ui.geometry.Size(rWidth, rHeight),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            // Minimap Overlay — top-right corner
            AnimatedVisibility(
                visible = scale.floatValue >= 5f,
                enter = fadeIn(MotionTokens.gentleSpring()),
                exit = fadeOut(MotionTokens.gentleSpring()),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp)
            ) {
                val maxThumbnailWidth = 80.dp
                val maxThumbnailHeight = 120.dp

                val thumbnailWidth = remember(imageAspectRatio) {
                    if (imageAspectRatio > (80f / 120f)) {
                        maxThumbnailWidth
                    } else {
                        maxThumbnailHeight * imageAspectRatio
                    }
                }
                val thumbnailHeight = remember(imageAspectRatio) {
                    if (imageAspectRatio > (80f / 120f)) {
                        maxThumbnailWidth / imageAspectRatio
                    } else {
                        maxThumbnailHeight
                    }
                }

                Box(
                    modifier = Modifier
                        .size(thumbnailWidth, thumbnailHeight)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(0.5.dp, Color.White.copy(alpha = 0.5f))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(resolvedUri)
                            .size(240, 360)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = stringResource(R.string.minimap_map),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Draw the Viewport Indicator Map
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val viewportWidth = size.width / scale.floatValue
                        val viewportHeight = size.height / scale.floatValue
                        
                        val maxOffsetX = (parentWidth * (scale.floatValue - 1)) / 2
                        val maxOffsetY = (parentHeight * (scale.floatValue - 1)) / 2
                        
                        val pctX = if (maxOffsetX > 0) -offsetX.floatValue / maxOffsetX else 0f
                        val pctY = if (maxOffsetY > 0) -offsetY.floatValue / maxOffsetY else 0f
                        
                        val maxViewportW = maxOf(0f, size.width - viewportWidth)
                        val maxViewportH = maxOf(0f, size.height - viewportHeight)
                        val rectX = ((size.width - viewportWidth) / 2 * (1 + pctX)).coerceIn(0f, maxViewportW)
                        val rectY = ((size.height - viewportHeight) / 2 * (1 + pctY)).coerceIn(0f, maxViewportH)
                        drawRect(
                            color = androidx.compose.ui.graphics.Color.White,
                            topLeft = Offset(rectX, rectY),
                            size = androidx.compose.ui.geometry.Size(viewportWidth, viewportHeight),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
            }
        }
        }

        // ── Photo counter pill (e.g. "23 / 487") ──────────────────────────────
        // Appears on page change, auto-hides after 2 seconds.
        var showCounter by remember { mutableStateOf(true) }
        LaunchedEffect(pagerState.currentPage) {
            showCounter = true
            kotlinx.coroutines.delay(2000)
            showCounter = false
        }
        AnimatedVisibility(
            visible = showCounter && galleryItems.size > 1,
            enter = fadeIn(MotionTokens.gentleSpring()) +
                    scaleIn(MotionTokens.bouncySpring(), initialScale = 0.8f),
            exit  = fadeOut(MotionTokens.gentleSpring()) +
                    scaleOut(MotionTokens.gentleSpring(), targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${galleryItems.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }



        // UI Overlay — Top bar: back button (left) + info button (right)
        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn(MotionTokens.gentleSpring()) +
                    slideInVertically(MotionTokens.gentleSpring()) { -it },
            exit  = fadeOut(MotionTokens.gentleSpring()) +
                    slideOutVertically(MotionTokens.gentleSpring()) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExpressiveFilledIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_ms_arrow_back),
                        contentDescription = stringResource(R.string.go_back)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExpressiveFilledIconButton(
                        onClick = { showInfoCard = !showInfoCard },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (showInfoCard) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_ms_info),
                            contentDescription = stringResource(R.string.info)
                        )
                    }

                }
            }
        }

        ExifDetailsSheet(
            isOpen = showInfoCard,
            onDismiss = { showInfoCard = false },
            galleryItem = galleryItems.getOrNull(pagerState.currentPage),
            exifData = currentExif
        )

        AnimatedVisibility(
            visible = showUi,
            enter = fadeIn(MotionTokens.gentleSpring()) +
                    slideInVertically(MotionTokens.gentleSpring()) { it },
            exit  = fadeOut(MotionTokens.gentleSpring()) +
                    slideOutVertically(MotionTokens.gentleSpring()) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 4.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val currentItem = galleryItems.getOrNull(pagerState.currentPage)

                // Comfort: a carousel's files as a filmstrip over the toolbar.
                val group = remember(galleryItems, currentItem) {
                    currentItem?.let { cur -> galleryItems.filter { groupKey(it) == groupKey(cur) } }.orEmpty()
                }
                if (group.size > 1) {
                    Filmstrip(
                        items = group,
                        current = currentItem,
                        onPick = { target ->
                            val index = galleryItems.indexOf(target)
                            if (index >= 0) coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                    )
                    Spacer(Modifier.height(FILMSTRIP_SPACE - 56.dp))
                }

                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = androidx.compose.material3.FloatingToolbarDefaults.standardFloatingToolbarColors(
                        toolbarContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        toolbarContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Comfort: Photon's recycle-bin branch is gone (no bin here), sharing has no
                        // metadata-stripping setting, Edit hands the file to an editor app for
                        // pictures too (Photon's own editor isn't ported), Delete goes through the
                        // caller (with Undo), and the ⋮ menu lives here instead of the top bar.
                        IconButton(
                            onClick = {
                                val item = currentItem
                                if (item != null) {
                                    coroutineScope.launch {
                                        ShareUtils.shareMedia(context, listOf(item.uri), false)
                                    }
                                }
                            },
                            enabled = currentItem?.localExists == true
                        ) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_ms_share), contentDescription = stringResource(R.string.share))
                        }
                            val isFavorite = currentItem?.id?.let { favoriteIds.contains(it) } ?: false
                            // Heart pop: triggers a scale bounce when toggling to favorite
                            var heartBounce by remember { mutableStateOf(false) }
                            val heartScale by animateFloatAsState(
                                targetValue = if (heartBounce) 1.45f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "heartScale",
                                finishedListener = { if (heartBounce) heartBounce = false }
                            )
                            val heartView = androidx.compose.ui.platform.LocalView.current
                            IconButton(
                                onClick = {
                                    if (currentItem != null) {
                                        heartBounce = true
                                        heartView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                        onToggleFavorite(currentItem)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) ImageVector.vectorResource(R.drawable.ic_ms_favorite) else ImageVector.vectorResource(R.drawable.ic_ms_favorite),
                                    contentDescription = stringResource(R.string.favorite),
                                    tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = heartScale
                                        scaleY = heartScale
                                    }
                                )
                            }
                            IconButton(
                                enabled = currentItem?.localExists == true,
                                onClick = {
                                    if (currentItem != null) {
                                        val uri = resolvedCurrentUri ?: currentItem.uri
                                        val editIntent = Intent(Intent.ACTION_EDIT).apply {
                                            setDataAndType(uri, context.contentResolver.getType(uri) ?: if (currentItem.isVideo) "video/*" else "image/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        }
                                        try {
                                            context.startActivity(Intent.createChooser(editIntent, context.getString(R.string.edit_with)))
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.no_editor_available_on_device), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_ms_edit), contentDescription = stringResource(R.string.edit))
                            }
                            IconButton(
                                onClick = { currentItem?.let(onDelete) }
                            ) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_ms_delete), contentDescription = stringResource(R.string.delete))
                            }
                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(ImageVector.vectorResource(R.drawable.ic_ms_more_vert), contentDescription = stringResource(R.string.more_options))
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    shape = MaterialTheme.shapes.large,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    if (currentItem != null) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(stringResource(R.string.open_with)) },
                                            leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_ms_open_in_new), contentDescription = null) },
                                            enabled = currentItem.localExists,
                                            onClick = {
                                                showMoreMenu = false
                                                val uri = resolvedCurrentUri ?: currentItem.uri
                                                val mimeType = context.contentResolver.getType(uri) ?: if (currentItem.isVideo) "video/*" else "image/*"
                                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, mimeType)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                try {
                                                    context.startActivity(Intent.createChooser(viewIntent, context.getString(R.string.open_with)))
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, context.getString(R.string.no_app_available_to_open_this), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                    if (currentItem != null && !currentItem.isVideo) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(stringResource(R.string.set_as_wallpaper)) },
                                            leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_ms_image), contentDescription = null) },
                                            enabled = currentItem.localExists,
                                            onClick = {
                                                showMoreMenu = false
                                                val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                                                    setDataAndType(resolvedCurrentUri ?: currentItem.uri, "image/*")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    putExtra("mimeType", "image/*")
                                                }
                                                try {
                                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.set_as)))
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, context.getString(R.string.no_app_available_to_set_wallpaper), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                    if (currentItem != null) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(stringResource(R.string.rename)) },
                                            leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_ms_edit), contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                showRenameDialog = true
                                            }
                                        )
                                        extraMenuItems(currentItem) { showMoreMenu = false }
                                        // Comfort: a downloaded video's subtitles, and its audio tracks when it has dubs.
                                        val tracks = pageTracks?.takeIf { it.first == pagerState.currentPage }?.second
                                        if (currentItem.isVideo && tracks != null) {
                                            if (tracks.subtitles.isNotEmpty()) {
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.subtitles)) },
                                                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Subtitles, contentDescription = null) },
                                                    trailingIcon = {
                                                        Text(
                                                            tracks.subtitles.firstOrNull { it.selected }?.label ?: stringResource(R.string.subtitles_off_short),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        trackPicker = androidx.media3.common.C.TRACK_TYPE_TEXT
                                                    }
                                                )
                                            }
                                            if (tracks.audio.size > 1) {
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.audio_track)) },
                                                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Language, contentDescription = null) },
                                                    trailingIcon = {
                                                        Text(
                                                            tracks.audio.firstOrNull { it.selected }?.label.orEmpty(),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        trackPicker = androidx.media3.common.C.TRACK_TYPE_AUDIO
                                                    }
                                                )
                                            }
                                        }
                                        // Comfort: whether videos start with sound, here where they play.
                                        var soundOn by remember { mutableStateOf(!startMuted) }
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(stringResource(R.string.play_videos_with_sound)) },
                                            leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_ms_volume_up), contentDescription = null) },
                                            trailingIcon = {
                                                androidx.compose.material3.Checkbox(checked = soundOn, onCheckedChange = null)
                                            },
                                            onClick = {
                                                soundOn = !soundOn
                                                videosMuted = !soundOn
                                                onSoundSettingChange(soundOn)
                                            }
                                        )
                                    }
                                }
                            }
                    }
                }
        }
        }

    }
}


data class ExifData(
    val model: String?,
    val aperture: String?,
    val iso: String?,
    val shutterSpeed: String?,
    val focalLength: String?,
    val resolution: String?
)

suspend fun extractExif(context: android.content.Context, uri: Uri): ExifData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    var model: String? = null
    var aperture: String? = null
    var iso: String? = null
    var shutterSpeed: String? = null
    var focalLength: String? = null
    var resolution: String? = null

    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val exif = androidx.exifinterface.media.ExifInterface(inputStream)
            model = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)
            aperture = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
            iso = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }
            shutterSpeed = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)?.let { "${it}s" }
            focalLength = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it}mm" }
            
            val width = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH)
            val length = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH)
            if (width != null && length != null) {
                resolution = "${width}x${length}"
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    ExifData(model, aperture, iso, shutterSpeed, focalLength, resolution)
}

fun formatExifDate(timestamp: Long): String {
    return com.comfort.app.util.LocalFormat.date(timestamp, "yMMddHHmm")
}

fun formatSizeToMB(sizeBytes: Long): String {
    return com.comfort.app.util.LocalFormat.measure(sizeBytes / (1024.0 * 1024.0), android.icu.util.MeasureUnit.MEGABYTE, 2)
}

@Composable
fun ExifInfoCard(galleryItem: GalleryItem?, exifData: ExifData?, modifier: Modifier = Modifier) {
    if (galleryItem == null) return
    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 340.dp),
        shape = ShapeLargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // File Name — full wrap
            InfoBlock(label = stringResource(R.string.command_template_name), value = galleryItem.name)

            // Time + Size row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoBlock(
                    label = stringResource(R.string.modified),
                    value = formatExifDate(galleryItem.dateModified * 1000L),
                    modifier = Modifier.weight(1f)
                )
                InfoBlock(
                    label = stringResource(R.string.sort_size),
                    value = formatSizeToMB(galleryItem.size),
                    modifier = Modifier.weight(1f)
                )
            }

            // Resolution + Device row
            val resolution = exifData?.resolution
            val device = exifData?.model
            if (resolution != null || device != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (resolution != null) {
                        InfoBlock(label = stringResource(R.string.resolution), value = resolution, modifier = Modifier.weight(1f))
                    }
                    if (device != null) {
                        InfoBlock(label = stringResource(R.string.device), value = device, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Camera parameters
            val paramsParts = listOfNotNull(exifData?.focalLength, exifData?.shutterSpeed, exifData?.iso, exifData?.aperture).filter { it.isNotBlank() }
            if (paramsParts.isNotEmpty()) {
                InfoBlock(label = stringResource(R.string.parameters), value = paramsParts.joinToString("  •  "))
            }

            // Path — full wrap
            InfoBlock(label = stringResource(R.string.path), value = galleryItem.path)
        }
    }
}

@Composable
private fun InfoBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(

                letterSpacing = 0.8.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// How far up (px) a page is dragged to open the info sheet (after the 0.35 resistance).
private const val INFO_SWIPE_PX = 60f

/** A video's subtitle tracks ("Off" first) or audio tracks, as a radio list; picking one switches
 * the playing video to it. */
@Composable
private fun TrackPickerDialog(tracks: VideoTracks, subtitles: Boolean, onDismiss: () -> Unit) {
    val options: List<VideoTrack?> = if (subtitles) listOf<VideoTrack?>(null) + tracks.subtitles else tracks.audio
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (subtitles) R.string.subtitles else R.string.audio_track)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                options.forEach { option ->
                    val selected = if (option == null) tracks.subtitlesOff else option.selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                if (subtitles) tracks.selectSubtitles(option) else option?.let(tracks::selectAudio)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(option?.label ?: stringResource(R.string.subtitles_off_short), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
