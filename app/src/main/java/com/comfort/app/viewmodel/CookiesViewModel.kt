package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.CookieStore
import com.comfort.app.data.GalleryDlPreferences
import com.comfort.app.data.ParsedCookie
import com.comfort.app.data.SiteCookies
import com.comfort.app.data.groupCookiesBySite
import com.comfort.app.data.parseCookiesFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Cookies & Login page's data: the saved cookies.txt, parsed and grouped by site, and which
 * sites' cookies are switched off. Every change is written straight through CookieStore. */
class CookiesViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val writeMutex = Mutex()

    private val _content = MutableStateFlow(CookieStore.read(application))
    /** The file as saved — what a download actually sends. */
    val content: StateFlow<String> = _content.asStateFlow()

    val cookies: StateFlow<List<ParsedCookie>> = _content.map { parseCookiesFile(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, parseCookiesFile(_content.value))

    /** One entry per site (registrable domain), not per cookie — a login is a dozen-plus cookies. */
    val sites: StateFlow<List<SiteCookies>> = cookies.map { groupCookiesBySite(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, groupCookiesBySite(cookies.value))

    private val _disabledDomains = MutableStateFlow(GalleryDlPreferences.getDisabledCookieDomains(application))
    val disabledDomains: StateFlow<Set<String>> = _disabledDomains.asStateFlow()

    /** Re-reads the file — after the login browser saved into it. */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                _content.value = CookieStore.read(context)
            }
        }
    }

    /** Merges a pasted cookies.txt in; false (nothing saved) when none of it parsed. */
    fun savePasted(text: String): Boolean {
        val current = CookieStore.read(context).ifBlank { _content.value }
        val merged = CookieStore.mergePasted(current, text) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                val latest = CookieStore.read(context).ifBlank { current }
                val remerged = CookieStore.mergePasted(latest, text) ?: merged
                CookieStore.write(context, remerged)
                _content.value = remerged
            }
        }
        return true
    }

    fun saveEdits(edits: Map<ParsedCookie, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                val current = CookieStore.read(context).ifBlank { _content.value }
                val updated = CookieStore.withValues(current, edits)
                CookieStore.write(context, updated)
                _content.value = updated
            }
        }
    }

    fun deleteSite(site: SiteCookies) {
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                val current = CookieStore.read(context).ifBlank { _content.value }
                val updated = CookieStore.without(current, site.cookies.toSet())
                CookieStore.write(context, updated)
                _content.value = updated
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                CookieStore.write(context, "")
                _content.value = ""
            }
        }
    }

    /** Kept but not sent: a switched-off site's cookies are filtered out of every download and
     * preview (GalleryDlPreferences.filterCookiesByDisabledDomains) until switched back on. */
    fun setSiteEnabled(rootDomain: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            GalleryDlPreferences.setCookieDomainEnabled(context, rootDomain, enabled)
            _disabledDomains.value = GalleryDlPreferences.getDisabledCookieDomains(context)
        }
    }
}
