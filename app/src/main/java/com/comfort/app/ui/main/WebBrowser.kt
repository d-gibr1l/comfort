package com.comfort.app.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** The browser's one main action, shown as the bottom bar's button ("Extract cookies"); gets the
 * URL of the page on screen. */
data class BrowserAction(val label: String, val icon: ImageVector, val onClick: (url: String) -> Unit)

/**
 * Comfort's in-app browser, full screen. Used to sign in to a site (CookieLoginDialog) and meant to
 * be where a page's media gets found later.
 *
 * - Top: close, and a pill address bar — lock + site at rest, the full URL selected once tapped
 *   (typed words search), reload/stop. A real-progress bar under it.
 * - Bottom: back, forward, share, a ⋮ menu (find in page, desktop site, copy link, open in another
 *   browser), and [primaryAction] as the bar's button — in a bar of its own, so it never covers the
 *   one button a login page needs. Hidden while the keyboard is up.
 * - Pinch zoom, full-screen video, a page of its own when a page can't load, and app links
 *   (mailto:, tel:, intent://) handled: an intent:// link loads its web fallback when it has one.
 * - System back: leaves full screen, closes find, walks the page history, then closes.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WebBrowser(
    startUrl: String,
    onDismiss: () -> Unit,
    primaryAction: BrowserAction? = null,
    // Instagram's mobile site sends intent:// redirects to its app and goes blank; its desktop
    // site doesn't, so signing in starts in desktop mode.
    startDesktop: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Set from inside the Dialog: the dialog is its own window, and the focus manager read out
    // here (the app's window) can't clear the address bar's focus.
    var focusManager by remember { mutableStateOf<androidx.compose.ui.focus.FocusManager?>(null) }
    // What's actually loaded; the address field keeps an in-progress edit separately, so the
    // page's own callbacks don't overwrite what's being typed.
    var currentUrl by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var addressField by remember { mutableStateOf(TextFieldValue(startUrl)) }
    var editingAddress by remember { mutableStateOf(false) }

    LaunchedEffect(currentUrl) {
        if (!editingAddress) {
            addressField = TextFieldValue(currentUrl)
        }
    }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var desktopMode by remember { mutableStateOf(startDesktop) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findActive by remember { mutableIntStateOf(0) }
    var findTotal by remember { mutableIntStateOf(0) }
    var fullScreenView by remember { mutableStateOf<View?>(null) }
    var fullScreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    // The media this page loads (MediaSniffer), listed from the ⋮ menu.
    val sniffer = remember {
        com.comfort.app.util.MediaSniffer(com.comfort.app.util.MseRecorder(java.io.File(context.cacheDir, "browser-recording")))
    }
    val caughtMedia by sniffer.items.collectAsState()
    val pieceBuilt by sniffer.pieceBuilt.collectAsState()
    val drmProtected by sniffer.protected.collectAsState()
    val playerPoster by sniffer.playerPoster.collectAsState()
    val pagePicture by sniffer.pagePicture.collectAsState()
    val recording by sniffer.recorder.state.collectAsState()
    var savingRecording by remember { mutableStateOf(false) }
    val rankedMedia = remember(caughtMedia, currentUrl) { com.comfort.app.util.MediaSniffer.ranked(caughtMedia, currentUrl) }
    var mediaSheetOpen by remember { mutableStateOf(false) }
    // What's been queued from this browser, so a row keeps its check after the sheet closes.
    var queuedMedia by remember { mutableStateOf(setOf<String>()) }

    fun navigateTo(input: String) {
        val target = normalizeBrowserAddress(input)
        currentUrl = target
        webView?.loadUrl(target)
        focusManager?.clearFocus()
    }

    fun applyDesktopMode(view: WebView, desktop: Boolean) {
        // null puts WebView's own (mobile) User-Agent back.
        view.settings.userAgentString = if (desktop) DESKTOP_USER_AGENT else null
        view.settings.loadWithOverviewMode = desktop
        view.settings.useWideViewPort = true
    }

    fun exitFullScreen() {
        (fullScreenView?.parent as? ViewGroup)?.removeView(fullScreenView)
        fullScreenCallback?.onCustomViewHidden()
        fullScreenView = null
        fullScreenCallback = null
    }

    fun closeFind() {
        findOpen = false
        findQuery = ""
        findActive = 0
        findTotal = 0
        webView?.clearMatches()
    }

    DisposableEffect(Unit) {
        // Requests from the page's service workers don't reach the WebView's own client.
        val serviceWorkers = runCatching { android.webkit.ServiceWorkerController.getInstance() }.getOrNull()
        runCatching {
            serviceWorkers?.setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    sniffer.onRequest(request.url.toString(), request.requestHeaders.orEmpty())
                    return null
                }
            })
        }
        onDispose {
            runCatching { serviceWorkers?.setServiceWorkerClient(null) }
            sniffer.recorder.cancel()
            sniffer.close()
            exitFullScreen()
            webView?.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }

    Dialog(
        onDismissRequest = {
            exitFullScreen()
            onDismiss()
        },
        // Without this the platform's dialog width leaves margins around the page.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Resize for the keyboard rather than pan: panning slid the whole browser up, find bar and
        // address bar off the top, as soon as the keyboard opened.
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            @Suppress("DEPRECATION")
            dialogWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val dialogFocusManager = LocalFocusManager.current
        SideEffect { focusManager = dialogFocusManager }

        BackHandler(enabled = fullScreenView != null || findOpen || canGoBack) {
            when {
                fullScreenView != null -> exitFullScreen()
                findOpen -> closeFind()
                else -> webView?.goBack()
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape, color = MaterialTheme.colorScheme.background) {
            Column {
                if (fullScreenView == null) {
                    if (findOpen) {
                        FindInPageBar(
                            query = findQuery,
                            active = findActive,
                            total = findTotal,
                            onQueryChange = { q ->
                                findQuery = q
                                if (q.isEmpty()) {
                                    webView?.clearMatches()
                                    findActive = 0
                                    findTotal = 0
                                } else {
                                    webView?.findAllAsync(q)
                                }
                            },
                            onNext = { webView?.findNext(true) },
                            onPrevious = { webView?.findNext(false) },
                            onClose = { closeFind() },
                        )
                    } else {
                        AddressBar(
                            currentUrl = currentUrl,
                            addressField = addressField,
                            editing = editingAddress,
                            isLoading = isLoading,
                            onAddressChange = { addressField = it },
                            onEditingChange = { focused ->
                                if (focused && !editingAddress) {
                                    addressField = TextFieldValue(currentUrl, selection = TextRange(0, currentUrl.length))
                                } else if (!focused) {
                                    addressField = TextFieldValue(currentUrl)
                                }
                                editingAddress = focused
                            },
                            onGo = { navigateTo(addressField.text) },
                            onReloadOrStop = { if (isLoading) webView?.stopLoading() else { loadError = null; webView?.reload() } },
                            onClose = onDismiss,
                        )
                    }
                    // Real page progress; its 3dp is kept either way so the page doesn't jump.
                    Box(Modifier.fillMaxWidth().height(3.dp)) {
                        if (isLoading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize())
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // MATCH_PARENT, not AndroidView's WRAP_CONTENT: with wrap-content the
                                // page can't resolve CSS viewport units (100vh = 0), and Instagram's
                                // login sheet, sized calc(100vh - padding), collapsed to nothing.
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                applyDesktopMode(this, desktopMode)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                addJavascriptInterface(sniffer, com.comfort.app.util.MediaSniffer.JS_NAME)
                                // In every frame (embedded players are iframes) and before the page's
                                // own code, so its fetch/XHR and MediaSource are wrapped first.
                                // Otherwise it's run after each load (onPageFinished), main frame only.
                                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                    runCatching {
                                        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                            this, com.comfort.app.util.MediaSniffer.PAGE_SCRIPT, setOf("*"),
                                        )
                                    }
                                }
                                setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                                    findTotal = numberOfMatches
                                    findActive = if (numberOfMatches == 0) 0 else activeMatchOrdinal + 1
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        progress = newProgress / 100f
                                    }

                                    override fun onReceivedTitle(view: WebView, title: String?) {
                                        pageTitle = title.orEmpty()
                                    }

                                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                        if (fullScreenView != null) {
                                            callback.onCustomViewHidden()
                                            return
                                        }
                                        fullScreenView = view
                                        fullScreenCallback = callback
                                    }

                                    override fun onHideCustomView() {
                                        (fullScreenView?.parent as? ViewGroup)?.removeView(fullScreenView)
                                        fullScreenView = null
                                        fullScreenCallback = null
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                        handleNonWebLink(view.context, view, request)

                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                                        // Only noted; the request goes ahead untouched.
                                        sniffer.onRequest(request.url.toString(), request.requestHeaders.orEmpty())
                                        return null
                                    }

                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
                                        loadError = null
                                        sniffer.reset()
                                        if (url != null) currentUrl = url
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        isLoading = false
                                        view.evaluateJavascript(com.comfort.app.util.MediaSniffer.PAGE_SCRIPT, null)
                                        if (url != null) currentUrl = url
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                        // Single-page sites change the address without a page load.
                                        if (url != null) currentUrl = url
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) loadError = error.description?.toString()?.ifBlank { null } ?: "The page didn't load."
                                    }
                                }
                                loadUrl(normalizeBrowserAddress(startUrl))
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    loadError?.let { message ->
                        PageError(
                            message = message,
                            onRetry = {
                                loadError = null
                                webView?.reload()
                            },
                        )
                    }

                    fullScreenView?.let { view ->
                        key(view) {
                            AndroidView(
                                factory = { view },
                                modifier = Modifier.fillMaxSize().background(Color.Black),
                            )
                        }
                    }
                }

                if (mediaSheetOpen) {
                    CaughtMediaSheet(
                        shown = rankedMedia,
                        queued = queuedMedia,
                        pieceBuilt = pieceBuilt,
                        drmProtected = drmProtected,
                        streamPicture = playerPoster ?: pagePicture,
                        frameOf = sniffer::frameOf,
                        onRecord = {
                            mediaSheetOpen = false
                            sniffer.recorder.start()
                            // Again from the start, so the first pieces (the initialization) are caught.
                            webView?.reload()
                            Toast.makeText(context, "Recording. Play the video to the end, then tap Stop and save.", Toast.LENGTH_LONG).show()
                        },
                        onQueue = { item ->
                            queuedMedia = queuedMedia + item.url
                            val userAgent = webView?.settings?.userAgentString.orEmpty()
                            val page = currentUrl
                            val title = pageTitle
                            scope.launch {
                                queueCaught(context, item, page, title, userAgent)
                                android.widget.Toast.makeText(context, "Added to Queue", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDismiss = { mediaSheetOpen = false },
                    )
                }

                if (recording.recording || savingRecording) {
                    RecordingBar(
                        bytes = recording.bytes,
                        ended = recording.ended,
                        saving = savingRecording,
                        onStop = {
                            savingRecording = true
                            val title = pageTitle
                            val page = currentUrl
                            scope.launch {
                                val saved = sniffer.recorder.finish(context, title, page)
                                savingRecording = false
                                Toast.makeText(
                                    context,
                                    if (saved != null) "Recording saved to your Library" else "Couldn't save the recording",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onCancel = { sniffer.recorder.cancel() },
                    )
                }

                if (fullScreenView == null && !WindowInsets.isImeVisible) {
                    val mediaBadgeText = remember(rankedMedia.size) { badgeCountText(rankedMedia.size) }
                    val mediaContentDesc = remember(rankedMedia.size) {
                        when (rankedMedia.size) {
                            0 -> "Media on this page"
                            1 -> "Media on this page, 1 found"
                            else -> "Media on this page, ${rankedMedia.size} found"
                        }
                    }
                    BottomAppBar(
                        actions = {
                            IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                            IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Forward")
                            }
                            // The media found on the page, counted live. The badge sits in the
                            // button's own corner (as on the Library's queue button), where it
                            // can't be clipped.
                            Box {
                                IconButton(onClick = { mediaSheetOpen = true }) {
                                    Icon(
                                        Icons.Outlined.VideoLibrary,
                                        contentDescription = mediaContentDesc,
                                        tint = if (rankedMedia.isNotEmpty()) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                    )
                                }
                                if (rankedMedia.isNotEmpty()) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp),
                                    ) {
                                        Text(mediaBadgeText)
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Share page") },
                                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            sharePage(context, currentUrl, pageTitle)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Find in page") },
                                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            findOpen = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Desktop site") },
                                        leadingIcon = { Icon(Icons.Outlined.DesktopWindows, contentDescription = null) },
                                        trailingIcon = { Checkbox(checked = desktopMode, onCheckedChange = null) },
                                        modifier = Modifier.semantics {
                                            role = Role.Checkbox
                                            toggleableState = ToggleableState(desktopMode)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            desktopMode = !desktopMode
                                            webView?.let { applyDesktopMode(it, desktopMode); it.reload() }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy link") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                                ?.setPrimaryClip(ClipData.newPlainText("Link", currentUrl))
                                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Open in another browser") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            openExternally(context, currentUrl)
                                        },
                                    )
                                }
                            }
                        },
                        floatingActionButton = when {
                            primaryAction != null -> {
                                {
                                    ExtendedFloatingActionButton(
                                        onClick = { primaryAction.onClick(currentUrl) },
                                        icon = { Icon(primaryAction.icon, contentDescription = null) },
                                        text = { Text(primaryAction.label) },
                                        containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                                        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                                    )
                                }
                            }
                            rankedMedia.isNotEmpty() -> {
                                {
                                    ExtendedFloatingActionButton(
                                        onClick = { mediaSheetOpen = true },
                                        icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                        text = { Text("Download") },
                                        containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                                        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                                    )
                                }
                            }
                            else -> null
                        },
                    )
                }
            }
        }
    }
}

/**
 * The media worth downloading from the page, best first (MediaSniffer.ranked): a preview (the
 * picture itself for an image), what it is and how big, and a download button; the first row is
 * marked as the best match. A queued row shows a check instead. Empty, it says what usually helps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaughtMediaSheet(
    shown: List<com.comfort.app.util.MediaSniffer.Caught>,
    queued: Set<String>,
    pieceBuilt: Boolean,
    drmProtected: Boolean,
    streamPicture: String?,
    frameOf: suspend (com.comfort.app.util.MediaSniffer.Caught) -> android.graphics.Bitmap?,
    onRecord: () -> Unit,
    onQueue: (com.comfort.app.util.MediaSniffer.Caught) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
            Text("Media on this page", style = MaterialTheme.typography.titleLarge)
            Text(
                when (shown.size) {
                    0 -> "Nothing found yet"
                    1 -> "1 found"
                    else -> "${shown.size} found, best match first"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (shown.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Play the video, or scroll to where the pictures are. Media shows up here as the page loads it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // The order changes live (a video starts playing, a size comes in). A lazy list keeps
            // its first row in view when one is inserted above it, which scrolled a new best match
            // out of sight above the top; at the top, it stays at the top.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(shown.firstOrNull()?.url) {
                if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
            }
            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(shown.size, key = { shown[it].url }) { i ->
                    val item = shown[i]
                    val isQueued = item.url in queued
                    val uri = Uri.parse(item.url)
                    val size = com.comfort.app.util.MediaSniffer.sizeOf(item)
                    ListItem(
                        overlineContent = if (i == 0 && shown.size > 1) {
                            { Text("Best match", color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        headlineContent = {
                            Text(
                                listOfNotNull(
                                    when (item.kind) {
                                        com.comfort.app.util.MediaSniffer.Kind.STREAM -> "Video stream"
                                        com.comfort.app.util.MediaSniffer.Kind.AUDIO -> "Audio"
                                        com.comfort.app.util.MediaSniffer.Kind.IMAGE -> "Picture"
                                        else -> "Video"
                                    },
                                    size?.let { (w, h) -> qualityLabel(item.kind, w, h) },
                                    item.bytes.takeIf { it > 0 }?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
                                ).joinToString(" · "),
                            )
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(uri.lastPathSegment, uri.host?.removePrefix("www.")).joinToString(" · "),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { MediaThumb(item, streamPicture, frameOf) },
                        // The sheet's own color behind every row, not ListItem's surface white.
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = {
                            if (isQueued) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = "Added to Queue",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp).padding(8.dp),
                                )
                            } else {
                                FilledTonalIconButton(onClick = {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                                    onQueue(item)
                                }) {
                                    Icon(Icons.Outlined.Download, contentDescription = "Download")
                                }
                            }
                        },
                        modifier = Modifier
                            .semantics {
                                if (isQueued) {
                                    stateDescription = "Added to Queue"
                                }
                            }
                            .clickable(
                                enabled = !isQueued,
                                role = Role.Button,
                                onClickLabel = "Download",
                            ) {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                                onQueue(item)
                            },
                    )
                }
            }
        }
        // A player that builds its video from pieces (a blob: source) can have nothing above to
        // download; recording it while it plays is the way left.
        if (pieceBuilt) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (drmProtected) "This video is protected" else "Not the video you want?",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (drmProtected) {
                            "The site uses copy protection (DRM), so a recording of it wouldn't play."
                        } else {
                            "This player builds its video from pieces. Comfort can record them while it plays: the page reloads, you play the video to the end, then tap Stop and save."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!drmProtected) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = onRecord) {
                            Icon(Icons.Outlined.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Record while it plays")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Shown while a recording runs: how much has been caught, and Stop and save / Cancel. Once the
 * page's player says its stream is complete, the bar says so. */
@Composable
private fun RecordingBar(bytes: Long, ended: Boolean, saving: Boolean, onStop: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.FiberManualRecord,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    saving -> "Saving the recording…"
                    ended -> "Recorded to the end · " + android.text.format.Formatter.formatShortFileSize(context, bytes)
                    bytes > 0 -> "Recording · " + android.text.format.Formatter.formatShortFileSize(context, bytes)
                    else -> "Recording · play the video"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (!saving) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onStop, enabled = bytes > 0) { Text("Stop and save") }
            }
        }
    }
}

/** "1080p", "4K" for video (from its shorter side, so portrait clips read right); "1200×800" for
 * pictures. */
private fun qualityLabel(kind: com.comfort.app.util.MediaSniffer.Kind, w: Int, h: Int): String {
    if (kind == com.comfort.app.util.MediaSniffer.Kind.IMAGE) return "${w}×${h}"
    val short = minOf(w, h)
    return when {
        short >= 2160 -> "4K"
        short >= 1440 -> "1440p"
        else -> "${short}p"
    }
}

/** 56 dp rounded: the kind's icon on a tonal tile, covered by a preview once there is one — the
 * picture itself for an image, a frame of the file for a video (MediaSniffer.frameOf, else its
 * player's poster), the player's poster or the page's own picture for a stream. */
@Composable
private fun MediaThumb(
    item: com.comfort.app.util.MediaSniffer.Caught,
    streamPicture: String?,
    frameOf: suspend (com.comfort.app.util.MediaSniffer.Caught) -> android.graphics.Bitmap?,
) {
    val shape = RoundedCornerShape(12.dp)
    // (tried, frame): the poster stands in only once the frame has failed, so it can't flash in
    // before the real frame.
    val frameState by produceState<Pair<Boolean, android.graphics.Bitmap?>>(false to null, item.url) {
        value = true to if (item.kind == com.comfort.app.util.MediaSniffer.Kind.VIDEO) frameOf(item) else null
    }
    val (frameTried, frame) = frameState
    val picture = when (item.kind) {
        com.comfort.app.util.MediaSniffer.Kind.IMAGE -> item.url
        com.comfort.app.util.MediaSniffer.Kind.VIDEO -> item.poster.takeIf { frameTried && frame == null }
        com.comfort.app.util.MediaSniffer.Kind.STREAM -> item.poster ?: streamPicture
        else -> null
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        // Under the preview, so the tile isn't blank while it loads or if it can't be shown.
        Icon(
            when (item.kind) {
                com.comfort.app.util.MediaSniffer.Kind.AUDIO -> Icons.Outlined.MusicNote
                com.comfort.app.util.MediaSniffer.Kind.STREAM -> Icons.Outlined.Stream
                com.comfort.app.util.MediaSniffer.Kind.IMAGE -> Icons.Outlined.Image
                else -> Icons.Outlined.Movie
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        val bitmap = frame
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (picture != null) {
            coil.compose.AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Queues a caught file as an ordinary download, for yt-dlp alone: it takes a plain file, an HLS
 * playlist or a DASH manifest alike and merges streams with ffmpeg. It gets what the browser had:
 * the page as Referer (or the Referer the page itself sent), the browser's User-Agent, and — when
 * the browser holds cookies for the file's site — those cookies, saved to cookies.txt the same way
 * "Extract cookies" does, since that's where every download reads them from.
 */
private suspend fun queueCaught(
    context: Context,
    item: com.comfort.app.util.MediaSniffer.Caught,
    pageUrl: String,
    pageTitle: String,
    userAgent: String,
) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        if (pageUrl.isNotBlank()) {
            cookieManager.getCookie(pageUrl)?.takeIf { it.isNotBlank() }?.let { pageCookies ->
                com.comfort.app.data.CookieStore.saveFromBrowser(context, pageUrl, pageCookies)
            }
        }
        if (item.url.isNotBlank() && item.url != pageUrl) {
            cookieManager.getCookie(item.url)?.takeIf { it.isNotBlank() }?.let { itemCookies ->
                com.comfort.app.data.CookieStore.saveFromBrowser(context, item.url, itemCookies)
            }
        }
    }
    val referer = item.headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value ?: pageUrl
    // A bare file has no poster or title of its own ("Unknown - 14498465_1440_2560_30fps"): the
    // page's site and title stand in. yt-dlp's --parse-metadata sets them before the name is
    // worked out; the "#" keeps a one-word value from being read as a field name, and ":" / "%"
    // are escaped for its FROM:TO and template syntax.
    fun literal(value: String) = "#" + value.replace("\\", "/").replace("%", "%%").replace(":", "\\:")
    val site = runCatching { java.net.URI(pageUrl).host }.getOrNull()?.removePrefix("www.").orEmpty()
    val extra = buildString {
        append("--referer ").append(shellQuote(referer))
        if (userAgent.isNotBlank()) append(" --user-agent ").append(shellQuote(userAgent))
        val skippedHeaderKeys = setOf("referer", "user-agent", "cookie", "host", "content-length", "accept-encoding", "connection", "range")
        for ((key, value) in item.headers) {
            if (key.lowercase() !in skippedHeaderKeys && value.isNotBlank()) {
                append(" --add-header ").append(shellQuote("$key:$value"))
            }
        }
        // A plain file (not a stream) yt-dlp can't take is downloaded directly instead, with these
        // same headers and the saved cookies (yt_dlp_wrapper.py's _direct_download).
        if (item.kind != com.comfort.app.util.MediaSniffer.Kind.STREAM) append(" --comfort-direct-fallback")
        // A video whose sound the player took from a separate file: merged in once it's saved.
        item.pairedAudio?.let { append(" --comfort-merge-audio ").append(shellQuote(it)) }
        if (site.isNotBlank()) append(" --parse-metadata ").append(shellQuote(literal(site) + ":#(?P<uploader>.+)"))
        if (pageTitle.isNotBlank()) append(" --parse-metadata ").append(shellQuote(literal(pageTitle.trim()) + ":#(?P<title>.+)"))
    }
    com.comfort.app.data.DownloadDispatcher.enqueueDownload(
        context,
        url = item.url,
        title = pageTitle.ifBlank { Uri.parse(item.url).lastPathSegment ?: item.url },
        extraCommands = extra,
        engineOverride = com.comfort.app.data.DownloadEngine.YT_DLP,
    )
}

/** Single-quoted for the shell-style split the yt-dlp wrapper does (shlex). */
private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

@Composable
private fun AddressBar(
    currentUrl: String,
    addressField: TextFieldValue,
    editing: Boolean,
    isLoading: Boolean,
    onAddressChange: (TextFieldValue) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    onReloadOrStop: () -> Unit,
    onClose: () -> Unit,
) {
    val secure = currentUrl.startsWith("https://")
    val host = remember(currentUrl) {
        runCatching { java.net.URI(currentUrl).host }.getOrNull()?.removePrefix("www.") ?: currentUrl
    }
    // The tap that focuses the field also sends a cursor update for the text it tapped — the site
    // name shown at rest — which overwrote the full URL just put there, all selected. That one
    // update is dropped.
    var justFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "Close browser")
        }
        Surface(
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        editing -> Icons.Outlined.Search
                        secure -> Icons.Outlined.Lock
                        else -> Icons.Outlined.Public
                    },
                    contentDescription = if (!editing && secure) "Secure connection" else null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        // At rest just the site; once focused the full URL, all selected.
                        value = if (editing) addressField else TextFieldValue(host),
                        onValueChange = { value ->
                            val staleTap = justFocused && value.text == host && value.text != addressField.text
                            justFocused = false
                            if (editing && !staleTap) onAddressChange(value)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                if (it.isFocused && !editing) justFocused = true
                                onEditingChange(it.isFocused)
                            }
                            .clearFocusOnKeyboardDismiss(),
                    )
                    if (editing && addressField.text.isEmpty()) {
                        Text(
                            "Search or type a link",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (editing) {
                    if (addressField.text.isNotEmpty()) {
                        IconButton(onClick = { onAddressChange(TextFieldValue("")) }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear address", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    IconButton(onClick = onReloadOrStop) {
                        Icon(
                            if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                            contentDescription = if (isLoading) "Stop loading" else "Reload",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindInPageBar(
    query: String,
    active: Int,
    total: Int,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close find")
        }
        Surface(
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onNext() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                    if (query.isEmpty()) {
                        Text("Find in page", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (query.isNotEmpty()) {
                    Text(
                        "$active/$total",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = if (total == 0) "No matches found" else "$active of $total matches"
                        },
                    )
                }
            }
        }
        IconButton(onClick = onPrevious, enabled = total > 0) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match")
        }
        IconButton(onClick = onNext, enabled = total > 0) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match")
        }
    }
}

@Composable
private fun PageError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Couldn't open this page", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Try again")
        }
    }
}

/** Links that aren't web pages. An intent:// link loads its web fallback when it has one (the
 * page's own "open in the app" redirect otherwise blanks the browser); mailto:, tel:, market: and
 * the like open in their app, but only from a tap — a page can't send you off to an app by itself.
 * Web links stay in the browser. */
private fun handleNonWebLink(context: Context, view: WebView, request: WebResourceRequest): Boolean {
    val uri = request.url
    return when (uri.scheme?.lowercase()) {
        "http", "https", "about", "data", "blob", "javascript" -> false
        "intent" -> {
            val fallback = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }
                .getOrNull()?.getStringExtra("browser_fallback_url")
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) view.loadUrl(fallback)
            true
        }
        else -> {
            if (request.hasGesture()) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            true
        }
    }
}

private fun sharePage(context: Context, url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openExternally(context: Context, url: String) {
    // A chooser, since Comfort itself may be offered for the link.
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Whatever's typed in the address bar as a URL to load: a bare domain gets "https://", anything
 * else (no dot, a space, ...) is a search. */
private fun normalizeBrowserAddress(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isBlank() -> "https://www.google.com"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        !trimmed.contains(' ') && trimmed.contains('.') && !trimmed.contains("://") -> "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
