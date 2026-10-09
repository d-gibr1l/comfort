package com.comfort.app.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
    // Set from inside the Dialog: the dialog is its own window, and the focus manager read out
    // here (the app's window) can't clear the address bar's focus.
    var focusManager by remember { mutableStateOf<androidx.compose.ui.focus.FocusManager?>(null) }
    // What's actually loaded; the address field keeps an in-progress edit separately, so the
    // page's own callbacks don't overwrite what's being typed.
    var currentUrl by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var addressField by remember { mutableStateOf(TextFieldValue(startUrl)) }
    var editingAddress by remember { mutableStateOf(false) }
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
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Without this the platform's dialog width leaves margins around the page.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Resize for the keyboard rather than pan: panning slid the whole browser up, find bar and
        // address bar off the top, as soon as the keyboard opened.
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
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
                                        fullScreenView = null
                                        fullScreenCallback = null
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                        handleNonWebLink(view.context, view, request)

                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
                                        loadError = null
                                        if (url != null) currentUrl = url
                                        canGoBack = view.canGoBack()
                                        canGoForward = view.canGoForward()
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        isLoading = false
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
                                loadUrl(startUrl)
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

                if (fullScreenView == null && !WindowInsets.isImeVisible) {
                    BottomAppBar(
                        actions = {
                            IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                            IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Forward")
                            }
                            IconButton(onClick = { sharePage(context, currentUrl, pageTitle) }) {
                                Icon(Icons.Outlined.Share, contentDescription = "Share page")
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                        floatingActionButton = primaryAction?.let { action ->
                            {
                                ExtendedFloatingActionButton(
                                    onClick = { action.onClick(currentUrl) },
                                    icon = { Icon(action.icon, contentDescription = null) },
                                    text = { Text(action.label) },
                                    containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

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
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
