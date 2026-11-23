package com.comfort.app.ui.comic

import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.composed
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.comfort.app.R
import com.comfort.app.theme.HeaderFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File
import java.util.zip.ZipFile

/** A comic to read: its .cbz ([uri]) and what to call it. */
data class ComicRequest(val uri: Uri, val title: String)

/** The comic open in the reader, over everything (MainActivity draws it): from the Library, or a
 * .cbz opened with Comfort from another app. */
object ComicReaderRequest {
    var request by mutableStateOf<ComicRequest?>(null)
}

/** One page of a comic, for Coil: [entry] inside the archive copied to [file]. */
data class ComicPage(val file: File, val entry: String)

/** Coil reads a [ComicPage] straight out of its archive. */
class ComicPageFetcher(private val page: ComicPage, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val zip = ZipFile(page.file)
        val entry = zip.getEntry(page.entry) ?: run { zip.close(); error("No page ${page.entry}") }
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        zip.close()
        SourceResult(
            source = ImageSource(bytes.inputStream().source().buffer(), options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ComicPage> {
        override fun create(data: ComicPage, options: Options, imageLoader: ImageLoader): Fetcher = ComicPageFetcher(data, options)
    }

    class PageKeyer : Keyer<ComicPage> {
        override fun key(data: ComicPage, options: Options): String = "comic:${data.file.name}:${data.entry}"
    }
}

/** How the pages turn: a manga's right to left, a western comic's left to right, or a webtoon's
 * one long strip scrolled down. */
enum class ReadingMode { RIGHT_TO_LEFT, LEFT_TO_RIGHT, VERTICAL }

/** How long a double-tap zoom takes. */
private const val ZOOM_MS = 320

private const val PREFS = "comic_reader"
private val IMAGE = Regex("""\.(jpe?g|png|webp|gif|avif|bmp)$""", RegexOption.IGNORE_CASE)

/** The archive copied into the app's cache (once per comic) so pages can be read from it at random,
 * and its pages, in reading order ("2.jpg" before "10.jpg"). */
private suspend fun openComic(context: Context, uri: Uri): Pair<File, List<String>> = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "comics").apply { mkdirs() }
    val file = File(dir, "${uri.toString().hashCode().toUInt()}.cbz")
    if (!file.isFile || file.length() == 0L) {
        val tmp = File(dir, file.name + ".tmp")
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(file)
    }
    // A few comics kept, not every one ever read.
    dir.listFiles()?.filter { it.name.endsWith(".cbz") && it != file }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
    file.setLastModified(System.currentTimeMillis())
    val pages = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory && IMAGE.containsMatchIn(it.name) && !it.name.startsWith("__MACOSX") }
            .map { it.name }.toList()
    }.sortedWith(NaturalOrder)
    file to pages
}

/** "page2" before "page10": numbers compared as numbers. */
private object NaturalOrder : Comparator<String> {
    private val parts = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = parts.findAll(a.lowercase()).map { it.value }.toList()
        val y = parts.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}

/**
 * The comic reader: a .cbz's pages, full screen. Right to left (manga, the default), left to right,
 * or one long vertical strip (webtoons), picked from the top bar and remembered. Pinch or
 * double-tap a page to zoom, a tap shows and hides the bars, and the slider at the bottom jumps
 * through the pages. The page reached is kept for next time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReader(request: ComicRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(runCatching { ReadingMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(ReadingMode.RIGHT_TO_LEFT)) }
    var bars by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val comic by produceState<Pair<File, List<String>>?>(null, request.uri) {
        value = runCatching { openComic(context, request.uri) }.onFailure { failed = true }.getOrNull()
    }
    val progressKey = "page:${request.uri}"
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val loaded = comic
        when {
            failed -> Text(stringResource(R.string.comic_cant_open), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            loaded.second.isEmpty() -> Text(stringResource(R.string.comic_no_pages), color = Color.White, modifier = Modifier.align(Alignment.Center))
            else -> {
                val (file, pages) = loaded
                val start = prefs.getInt(progressKey, 0).coerceIn(0, pages.lastIndex)
                var current by remember { mutableIntStateOf(start) }
                LaunchedEffect(current) { prefs.edit().putInt(progressKey, current).apply() }
                val scope = rememberCoroutineScope()
                if (mode == ReadingMode.VERTICAL) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex }.collect { current = it }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().webtoonZoom(listState, onTap = { bars = !bars }),
                    ) {
                        items(pages.size) { i ->
                            coil.compose.AsyncImage(
                                model = ComicPage(file, pages[i]),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            )
                        }
                    }
                    BottomBar(current, pages.size, rtl = false, visible = bars, mode = mode, onMode = { mode = it; prefs.edit().putString("mode", it.name).apply() }) { scope.launch { listState.scrollToItem(it) } }
                } else {
                    val rtl = mode == ReadingMode.RIGHT_TO_LEFT
                    val pager = rememberPagerState(initialPage = start) { pages.size }
                    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { current = it } }
                    HorizontalPager(
                        state = pager,
                        reverseLayout = rtl,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { i ->
                        ZoomablePage(ComicPage(file, pages[i]), onTap = { bars = !bars })
                    }
                    BottomBar(current, pages.size, rtl = rtl, visible = bars, mode = mode, onMode = { mode = it; prefs.edit().putString("mode", it.name).apply() }) { scope.launch { pager.scrollToPage(it) } }
                }
                // Page count, top.
                AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    TopBar(request.title, "${current + 1} / ${pages.size}", onClose = onClose)
                }
            }
        }
        if (comic == null || failed) {
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
            }
        }
    }
}

@Composable
private fun TopBar(title: String, pageText: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pageText, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The reading mode button and its menu (bottom right, beside the slider). */
@Composable
private fun ModeButton(mode: ReadingMode, onMode: (ReadingMode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(
                when (mode) {
                    ReadingMode.RIGHT_TO_LEFT -> Icons.Outlined.FormatTextdirectionRToL
                    ReadingMode.LEFT_TO_RIGHT -> Icons.Outlined.FormatTextdirectionLToR
                    ReadingMode.VERTICAL -> Icons.Outlined.SwapVert
                },
                contentDescription = stringResource(R.string.reading_mode),
                tint = Color.White,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(
                ReadingMode.RIGHT_TO_LEFT to R.string.reading_right_to_left,
                ReadingMode.LEFT_TO_RIGHT to R.string.reading_left_to_right,
                ReadingMode.VERTICAL to R.string.reading_vertical,
            ).forEach { (m, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { if (m == mode) Icon(Icons.Outlined.Check, contentDescription = null) },
                    onClick = { menu = false; onMode(m) },
                )
            }
        }
    }
}

/** The bottom bar, over the page with nothing behind it: the page slider, which runs the way the
 * pages do (right to left for manga), and the reading mode button at its right. */
@Composable
private fun BoxScope.BottomBar(current: Int, count: Int, rtl: Boolean, visible: Boolean, mode: ReadingMode, onMode: (ReadingMode) -> Unit, onJump: (Int) -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
        var dragging by remember { mutableStateOf<Float?>(null) }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (count >= 2) {
                    // Laid out right to left for manga, so the track fills from the right.
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Slider(
                            value = dragging ?: current.toFloat(),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = { dragging?.let { onJump(Math.round(it)) }; dragging = null },
                            valueRange = 0f..(count - 1).toFloat(),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            ModeButton(mode, onMode)
        }
    }
}

/** One page, fitted to the screen: pinch to zoom, drag to move while zoomed (the pager swipes at
 * normal size), double-tap to zoom in where tapped and back out. */
@Composable
private fun ZoomablePage(page: ComicPage, onTap: () -> Unit) {
    val zoomScope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        // Eased in (or out) toward where it was tapped, not snapped there.
                        val s0 = scale; val o0 = offset
                        val s1 = if (scale > 1f) 1f else 2.5f
                        val o1 = if (s1 == 1f) Offset.Zero else Offset((size.width / 2f - at.x) * (s1 - 1f), (size.height / 2f - at.y) * (s1 - 1f))
                        zoomScope.launch {
                            androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.tween(ZOOM_MS)) { t, _ ->
                                scale = s0 + (s1 - s0) * t
                                offset = o0 + (o1 - o0) * t
                            }
                        }
                    },
                )
            }
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.AsyncImage(
            model = page,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

/** Zoom for the vertical (webtoon) strip: pinch or double-tap to zoom the whole strip, then drag
 * to move sideways and scroll; at normal size the list scrolls and flings as usual. Zoomed, one
 * finger's drag is taken here so the strip follows the finger (the list alone would move a zoomed
 * strip [scale] times faster), with a fling of its own on release. A pinch zooms in where the
 * fingers are, and the zoomed strip also moves up and down by itself ([offsetY]) where the list
 * can't scroll further: the first page's top and the last page's bottom (zoomed around the middle,
 * they were off the screen with no way to reach them). */
private fun Modifier.webtoonZoom(listState: androidx.compose.foundation.lazy.LazyListState, onTap: () -> Unit): Modifier = composed {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val decay = androidx.compose.animation.rememberSplineBasedDecay<Float>()
    fun clampX(x: Float) = (width * (scale - 1f) / 2f).let { max -> x.coerceIn(-max, max) }
    fun clampY(y: Float) = (height * (scale - 1f) / 2f).let { max -> y.coerceIn(-max, max) }
    // A vertical move on the screen (down is positive): the strip's own offset back toward the
    // middle first, then the list, and what the list can't take (at its top or end) the offset.
    fun moveY(dy: Float) {
        var rest = dy
        if (rest > 0f && offsetY < 0f) { val m = minOf(rest, -offsetY); offsetY += m; rest -= m }
        if (rest < 0f && offsetY > 0f) { val m = maxOf(rest, -offsetY); offsetY += m; rest -= m }
        if (rest != 0f) rest += listState.dispatchRawDelta(-rest / scale) * scale
        if (rest != 0f) offsetY = clampY(offsetY + rest)
    }
    this
        .onSizeChanged { width = it.width; height = it.height }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { at ->
                    // Eased in (or out) toward where it was tapped, not snapped there.
                    val s0 = scale; val x0 = offsetX; val y0 = offsetY
                    val s1 = if (scale > 1f) 1f else 2.5f
                    val x1 = if (s1 == 1f) 0f else ((width / 2f - at.x) * (s1 - 1f)).coerceIn(-width * (s1 - 1f) / 2f, width * (s1 - 1f) / 2f)
                    val y1 = if (s1 == 1f) 0f else ((height / 2f - at.y) * (s1 - 1f)).coerceIn(-height * (s1 - 1f) / 2f, height * (s1 - 1f) / 2f)
                    scope.launch {
                        androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.tween(ZOOM_MS)) { t, _ ->
                            scale = s0 + (s1 - s0) * t
                            offsetX = x0 + (x1 - x0) * t
                            offsetY = y0 + (y1 - y0) * t
                        }
                    }
                },
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                var dragged = false
                do {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        // Zoomed around the fingers: the point between them stays under them.
                        val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                        val z = newScale / scale
                        val c = event.calculateCentroid()
                        if (c != Offset.Unspecified) {
                            offsetX = c.x - width / 2f - (c.x - width / 2f - offsetX) * z
                            offsetY = c.y - height / 2f - (c.y - height / 2f - offsetY) * z
                        }
                        scale = newScale
                        val pan = event.calculatePan()
                        offsetX = clampX(offsetX + pan.x)
                        offsetY = clampY(offsetY)
                        moveY(pan.y)
                        if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                        event.changes.forEach { it.consume() }
                        dragged = false
                    } else if (scale > 1f && pressed == 1) {
                        val change = event.changes.first { it.pressed }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val d = change.positionChange()
                        if (d != Offset.Zero) {
                            dragged = true
                            offsetX = clampX(offsetX + d.x)
                            moveY(d.y)
                            change.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (dragged && scale > 1f) {
                    val vy = tracker.calculateVelocity().y / scale
                    scope.launch {
                        listState.scroll {
                            var last = 0f
                            androidx.compose.animation.core.AnimationState(0f, -vy).animateDecay(decay) {
                                scrollBy(value - last); last = value
                            }
                        }
                    }
                }
            }
        }
        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY }
}
