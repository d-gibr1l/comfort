package com.comfort.app.ui.main

import androidx.compose.runtime.collectAsState
import com.comfort.app.viewmodel.rememberSheetViewModelStoreOwner
import com.comfort.app.viewmodel.CookiesViewModel
import com.comfort.app.data.SiteCookies
import com.comfort.app.data.ParsedCookie
import com.comfort.app.data.CookieStore
import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import compose.icons.feathericons.Instagram
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfort.app.data.VideoSiteRouter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*

// Settings > Cookies & Login: saved cookies per site, and the in-app login browser.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookiesSettingsScreen(onBack: () -> Unit, highlightKey: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Same fix as IconToggleRow's own Switch — Compose's Switch doesn't call
    // performHapticFeedback internally, so this per-site toggle was the one Switch in Settings
    // still silent on tap.
    val haptics = LocalHapticFeedback.current
    var showBrowser by remember { mutableStateOf(false) }
    var extractedCookies by remember { mutableStateOf("") }
    // Deliberately empty, not seeded from whatever's already saved — this box is for pasting a
    // *new* cookies.txt in, not for displaying/re-editing what's already saved (that's what the
    // per-site table below is for). Reported live: pre-filling it with the current save meant this
    // one field alone could end up showing every cookie for every site as one giant wall of raw
    // text, duplicating what the table already shows more usefully.
    var pastedCookies by remember { mutableStateOf("") }
    var savedConfirmation by remember { mutableStateOf(false) }
    // Distinct from savedConfirmation, not just its negation — persist()/clearing pastedCookies
    // only happens on an actual successful parse now (see the Save cookies button below), so this
    // and savedConfirmation are never both true from the same click.
    var pasteError by remember { mutableStateOf<String?>(null) }
    // The saved file, parsed and grouped by site, and the per-site on/off toggles — read fresh
    // from cookies.txt (what a download actually uses) each time the page opens.
    val cookiesOwner = rememberSheetViewModelStoreOwner()
    val cookiesViewModel: CookiesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = cookiesOwner)
    val savedCookiesContent by cookiesViewModel.content.collectAsState()
    val parsedCookies by cookiesViewModel.cookies.collectAsState()
    val cookieSites by cookiesViewModel.sites.collectAsState()
    val disabledDomains by cookiesViewModel.disabledDomains.collectAsState()
    // Non-null while the confirm-delete sheet is up — both delete paths below (a single site's
    // Trash2 button, and the "Clear all" button) go through it, so neither can wipe a saved login
    // from one stray tap with no way back.
    var pendingDelete by remember { mutableStateOf<PendingCookieDelete?>(null) }
    // Non-null while a site's cookie viewer/editor sheet is open (tapping its row).
    var viewingSite by remember { mutableStateOf<SiteCookies?>(null) }

    if (showBrowser) {
        CookieLoginDialog(
            loginUrl = "https://instagram.com",
            onDismiss = { showBrowser = false },
            onCookiesSaved = { merged ->
                extractedCookies = merged
                cookiesViewModel.reload()
                showBrowser = false
            },
        )
    }

    SettingsSubScaffold(title = "Cookies & Login", topicIcon = Icons.Outlined.Lock, onBack = onBack, highlightKey = highlightKey) {
        SettingsSection(title = "Cookies", icon = Icons.Outlined.Lock) {
            Text(
                "Sign in through the built-in browser to unlock private/age-restricted content, or paste a cookies.txt below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // A site's own rate-limiting/anti-bot systems associate a session with the account
            // behind it, not just the device — a heavy download session with real personal cookies
            // risks a site-level restriction on that actual account, not just this app or a fresh
            // IP the way an unauthenticated 429 does (see the rate-limit tip on the Queue's own
            // ERRORED cards). A throwaway account sidesteps that entirely.
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Consider using a secondary/throwaway account rather than your primary one — heavy download activity risks a site-level restriction on the account behind the cookies, not just this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { showBrowser = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log in via built-in browser")
            }

            if (extractedCookies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cookies extracted successfully", tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(20.dp))
            Text("Or paste raw cookies manually", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = pastedCookies,
                onValueChange = { pastedCookies = it; pasteError = null },
                modifier = Modifier.fillMaxWidth().height(160.dp).clearFocusOnKeyboardDismiss(),
                label = { Text("cookies.txt contents") },
                maxLines = 10,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    // Merges into whatever's already saved rather than replacing it wholesale (see
                    // CookieStore.mergePasted). A paste where nothing parses is refused, and the
                    // textbox kept so it can be fixed — it used to merge as a no-op and still say
                    // "Cookies saved and applied" (reproduced live with Instagram cookies that
                    // weren't real tab-separated rows).
                    if (pastedCookies.isNotBlank() && !cookiesViewModel.savePasted(pastedCookies)) {
                        pasteError = "Couldn't find any valid cookies in that text — it needs to be real tab-separated Netscape format (domain, includeSubdomains, path, secure, expiry, name, value per line), not just \"name=value\" pairs."
                        savedConfirmation = false
                    } else {
                        pastedCookies = ""
                        pasteError = null
                        savedConfirmation = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save cookies")
            }

            if (savedConfirmation) {
                Spacer(Modifier.height(8.dp))
                StatusRow(icon = Icons.Outlined.CheckCircle, text = "Cookies saved and applied", tint = MaterialTheme.colorScheme.primary)
            }
            pasteError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // One row per *site*, not per individual cookie — a real login legitimately saves a dozen-
        // plus individual cookies together (sessionid, csrftoken, a device id, ...; see
        // groupCookiesBySite's own doc comment for why), and showing every single one as its own
        // row read as "the app is duplicating my cookies" rather than "this is what one login
        // actually consists of" (reported live). Grouped by registrable domain instead — Instagram
        // shows as one row regardless of how many individual cookies back that session, same for
        // Reddit, etc. — with a per-site delete that removes that whole group's cookies at once.
        // Still sourced from the real cookies.txt (see savedCookiesContent's own comment), so it
        // always reflects exactly what a download would actually send. (cookieSites itself is
        // declared up with parsedCookies, not here — see that declaration's own comment for why.)
        SettingsSection(title = "Saved cookies (${cookieSites.size})", icon = Icons.Outlined.List) {
            if (cookieSites.isEmpty()) {
                Text(
                    "No cookies saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                cookieSites.forEachIndexed { index, site ->
                    val enabled = site.rootDomain !in disabledDomains
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { viewingSite = site }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Delete stays at the lead position; Copy moved to sit right beside the
                        // toggle at the trailing end instead, next to the one other cookie-related
                        // action a user might reach for around the same time as flipping the switch.
                        IconButton(onClick = { pendingDelete = PendingCookieDelete.Site(site) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove ${site.label}'s cookies", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                site.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${site.cookies.size} cookie${if (site.cookies.size == 1) "" else "s"} · ${formatCookieExpiry(site.soonestExpiryEpochSeconds)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            val text = site.cookies.joinToString("\n") { it.rawLine }
                            scope.launch {
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("${site.label} cookies", text)))
                            }
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ${site.label}'s cookies", modifier = Modifier.size(18.dp))
                        }
                        // Kept, not deleted — a saved login the user just doesn't want *sent* right
                        // now (a stale account, testing anonymous behavior, ...) without losing it
                        // outright. Filters this site's cookies out of every download/preview from
                        // here on (see GalleryDlPreferences.filterCookiesByDisabledDomains and its
                        // call sites in DownloadWorker.kt/GalleryDlListing.kt) until switched back.
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                cookiesViewModel.setSiteEnabled(site.rootDomain, checked)
                            },
                            // Same fix as IconToggleRow's own Switch: the default unchecked thumb
                            // color is nearly invisible against the unchecked track in this theme
                            // — an off site (e.g. Instagram/Reddit above) read as a dead, unlabeled
                            // gray blob instead of a working control resting in its off position.
                            colors = SwitchDefaults.colors(
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                    if (index != cookieSites.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("cookies.txt", savedCookiesContent)))
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy all")
                    }
                    OutlinedButton(
                        onClick = { pendingDelete = PendingCookieDelete.All },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear all")
                    }
                }
            }
        }
    }

    viewingSite?.let { site ->
        SiteCookiesSheet(
            site = site,
            onCopy = { label, text ->
                scope.launch {
                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(label, text)))
                }
            },
            onSave = { editedValues ->
                cookiesViewModel.saveEdits(editedValues)
                viewingSite = null
            },
            onDismiss = { viewingSite = null },
        )
    }

    pendingDelete?.let { pending ->
        val (title, message) = when (pending) {
            is PendingCookieDelete.Site -> "Remove ${pending.site.label}?" to
                "This deletes ${pending.site.cookies.size} saved cookie${if (pending.site.cookies.size == 1) "" else "s"} for ${pending.site.label}. You'll need to sign in there again next time."
            PendingCookieDelete.All -> "Clear all cookies?" to
                "This deletes all ${parsedCookies.size} saved cookie${if (parsedCookies.size == 1) "" else "s"} across ${cookieSites.size} site${if (cookieSites.size == 1) "" else "s"}. You'll need to sign in again everywhere."
        }
        ConfirmDeleteSheet(
            title = title,
            message = message,
            confirmLabel = "Delete",
            onConfirm = {
                when (pending) {
                    is PendingCookieDelete.Site -> cookiesViewModel.deleteSite(pending.site)
                    PendingCookieDelete.All -> cookiesViewModel.clearAll()
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// Which delete action the confirm sheet is confirming — a single site's cookies (the per-row
// Trash2 button) or every saved cookie at once (the "Clear all" button). Both otherwise silently
// discarded a real signed-in session with no way back before this existed.
private sealed class PendingCookieDelete {
    data class Site(val site: SiteCookies) : PendingCookieDelete()
    data object All : PendingCookieDelete()
}

/** Viewer/editor for one site's saved cookies: each cookie's name with its value in an editable
 * field and its own copy button, plus Copy all and Save. Save only reports the cookies whose value
 * actually changed; the caller rewrites just those lines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteCookiesSheet(
    site: SiteCookies,
    onCopy: (label: String, text: String) -> Unit,
    onSave: (Map<ParsedCookie, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val edits = remember(site) { mutableStateMapOf<ParsedCookie, String>() }
    fun currentValue(cookie: ParsedCookie) = edits[cookie] ?: cookie.value
    val changed = edits.filter { (cookie, value) -> value != cookie.value }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding()) {
            Text(site.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${site.cookies.size} cookie${if (site.cookies.size == 1) "" else "s"} · ${formatCookieExpiry(site.soonestExpiryEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                site.cookies.forEach { cookie ->
                    OutlinedTextField(
                        value = currentValue(cookie),
                        onValueChange = { edits[cookie] = it },
                        label = { Text(cookie.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium,
                        trailingIcon = {
                            IconButton(onClick = { onCopy("${cookie.name} cookie", currentValue(cookie)) }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ${cookie.name}", modifier = Modifier.size(18.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clearFocusOnKeyboardDismiss(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // Copy all stays its own button on the left; Save / Cancel is the same split button
            // as Settings' other edit sheets, on the right.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        val text = site.cookies.joinToString("\n") { cookie ->
                            (edits[cookie]?.let { cookie.withValue(it) } ?: cookie).rawLine
                        }
                        onCopy("${site.label} cookies", text)
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy all")
                }
                Spacer(Modifier.weight(1f))
                ConfirmCancelSplitButton(
                    label = "Save",
                    icon = Icons.Outlined.Save,
                    onConfirm = { onSave(changed) },
                    onCancel = onDismiss,
                    confirmEnabled = changed.isNotEmpty(),
                )
            }
        }
    }
}

private fun formatCookieExpiry(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Session"
    val millis = epochSeconds * 1000
    if (millis < System.currentTimeMillis()) return "Expired"
    return java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
}

/** Shared by the Cookies & Login settings screen, the Queue's per-download "Add cookies" error-card
 * action and the share picker — a real navigable browser starting at [loginUrl] so the user can
 * sign in normally, or browse anywhere else the site sends them (an OAuth redirect, a "verify it's
 * you" subdomain, ...) without getting stuck on one fixed page.
 *
 * Layout (redesigned): close + a pill address bar on top (lock + site name at rest, the full URL
 * selected for editing once tapped, reload/stop inside it) and a real-progress bar under it. The
 * system back gesture walks the page history before closing. The "Extract cookies" FAB can be
 * dragged anywhere on the page (it snaps to the nearest side on release) so it never
 * has to sit on top of the one button a login page needs; a plain tap still extracts whatever
 * CookieManager captured for the page on screen and merges it into cookies.txt (replacing only
 * that site's prior lines), then closes. */
@Composable
fun CookieLoginDialog(
    loginUrl: String,
    onDismiss: () -> Unit,
    onCookiesSaved: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // The single source of truth for what's actually loaded — the address field keeps the user's
    // in-progress edit separately, so typing a new URL doesn't fight the WebView's own page
    // callbacks overwriting the field mid-edit.
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var addressField by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(loginUrl)) }
    var editingAddress by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun navigateTo(input: String) {
        val target = normalizeBrowserAddress(input)
        currentUrl = target
        webView?.loadUrl(target)
        focusManager.clearFocus()
    }

    fun extractCookies() {
        val merged = CookieStore.saveFromBrowser(context, currentUrl, CookieManager.getInstance().getCookie(currentUrl))
        if (merged != null) onCookiesSaved(merged) else onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Dialog's default width policy caps the window well short of the screen (platform
        // "dialog" sizing), which is what was leaving the WebView inset with visible margins —
        // this makes the window itself the full screen instead of just the content inside it.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Back walks the page history first; only an empty history closes the browser.
        androidx.activity.compose.BackHandler(enabled = canGoBack) { webView?.goBack() }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close browser")
                    }
                    val secure = currentUrl.startsWith("https://")
                    val host = remember(currentUrl) {
                        runCatching { java.net.URI(currentUrl).host }.getOrNull()?.removePrefix("www.") ?: currentUrl
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
                                if (secure) Icons.Outlined.Lock else Icons.Outlined.Public,
                                contentDescription = if (secure) "Secure connection" else null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                BasicTextField(
                                    // At rest the pill reads as just the site; once focused it
                                    // holds the full URL, all selected, ready to type over.
                                    value = if (editingAddress) addressField else androidx.compose.ui.text.input.TextFieldValue(host),
                                    onValueChange = { addressField = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { navigateTo(addressField.text) }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { state ->
                                            if (state.isFocused && !editingAddress) {
                                                addressField = androidx.compose.ui.text.input.TextFieldValue(
                                                    currentUrl, selection = androidx.compose.ui.text.TextRange(0, currentUrl.length),
                                                )
                                            }
                                            editingAddress = state.isFocused
                                        }
                                        .clearFocusOnKeyboardDismiss(),
                                )
                            }
                            IconButton(onClick = { if (isLoading) webView?.stopLoading() else webView?.reload() }) {
                                Icon(
                                    if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                                    contentDescription = if (isLoading) "Stop loading" else "Reload",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // Real page progress rather than an endless spinner; reserves its 3dp either way
                // so the page below doesn't jump when loading starts/stops.
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (isLoading) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize())
                    }
                }

                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // MATCH_PARENT, not AndroidView's default WRAP_CONTENT: with a
                                // wrap-content height the WebView can't resolve CSS viewport units,
                                // so 100vh/dvh/svh all computed to 0px. Instagram sizes its "Log into
                                // your Meta Account" sheet as calc(100vh - padding), so it collapsed
                                // to zero height and only its dark backdrop showed (found live via the
                                // WebView devtools: max-height 0px, 100vh = 0).
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Force a Desktop Chrome User-Agent. Instagram's mobile site often sends intent:// redirects
                                // to force opening their native app, which causes WebViews to go completely blank.
                                // The desktop site works flawlessly and doesn't try to deep-link you away.
                                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        progress = newProgress / 100f
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        // Block Android app intents (intent://) which crash the WebView into a blank screen
                                        if (url.startsWith("intent://") || url.startsWith("android-app://")) {
                                            return true
                                        }
                                        return super.shouldOverrideUrlLoading(view, request)
                                    }

                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
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
                                }
                                loadUrl(loginUrl)
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Draggable "Extract cookies" FAB. Positioned by its own top-left offset
                    // within this box; starts bottom-right above the system nav bar, can't be
                    // dragged off-screen, and snaps to the nearer side on release.
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)
                    val marginPx = with(density) { 16.dp.toPx() }
                    val toolbarReservePx = marginPx + navBarBottomPx
                    val boxWidthPx = constraints.maxWidth.toFloat()
                    val boxHeightPx = constraints.maxHeight.toFloat()
                    var fabSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                    val fabX = remember { Animatable(0f) }
                    val fabY = remember { Animatable(0f) }
                    var fabPlaced by remember { mutableStateOf(false) }
                    val maxX = (boxWidthPx - fabSize.width - marginPx).coerceAtLeast(marginPx)
                    val maxY = (boxHeightPx - fabSize.height - toolbarReservePx).coerceAtLeast(marginPx)
                    LaunchedEffect(fabSize, boxWidthPx, boxHeightPx) {
                        if (fabSize == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
                        if (!fabPlaced) {
                            fabX.snapTo(maxX)
                            fabY.snapTo(maxY)
                            fabPlaced = true
                        } else {
                            // Keep it on screen if the space changes (keyboard, rotation).
                            fabX.snapTo(fabX.value.coerceIn(marginPx, maxX))
                            fabY.snapTo(fabY.value.coerceIn(marginPx, maxY))
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = { extractCookies() },
                        icon = { Icon(Icons.Outlined.Cookie, contentDescription = null) },
                        text = { Text("Extract cookies") },
                        modifier = Modifier
                            .onSizeChanged { fabSize = it }
                            .offset { androidx.compose.ui.unit.IntOffset(fabX.value.roundToInt(), fabY.value.roundToInt()) }
                            .graphicsLayer { alpha = if (fabPlaced) 1f else 0f }
                            .pointerInput(maxX, maxY) {
                                detectDragGestures(
                                    onDrag = { change, drag ->
                                        change.consume()
                                        scope.launch {
                                            fabX.snapTo((fabX.value + drag.x).coerceIn(marginPx, maxX))
                                            fabY.snapTo((fabY.value + drag.y).coerceIn(marginPx, maxY))
                                        }
                                    },
                                    onDragEnd = {
                                        val snapLeft = fabX.value + fabSize.width / 2f < boxWidthPx / 2f
                                        scope.launch {
                                            fabX.animateTo(
                                                if (snapLeft) marginPx else maxX,
                                                androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 400f),
                                            )
                                        }
                                    },
                                )
                            },
                    )
                }
            }
        }
    }
}

/** Turns whatever's typed in the address bar into a real URL to load — a bare host/domain gets
 * "https://" prefixed, anything else (no dot, contains a space, ...) is treated as a search query
 * instead of a broken navigation attempt. */
private fun normalizeBrowserAddress(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isBlank() -> "https://www.google.com"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        !trimmed.contains(' ') && trimmed.contains('.') && !trimmed.contains("://") ->
            "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
