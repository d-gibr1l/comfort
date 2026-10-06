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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Cookies & Login page's data: the saved cookies.txt, parsed and grouped by site, and which
 * sites' cookies are switched off. Every change is written straight through CookieStore. */
class CookiesViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

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
        _content.value = CookieStore.read(context)
    }

    /** Merges a pasted cookies.txt in; false (nothing saved) when none of it parsed. */
    fun savePasted(text: String): Boolean {
        val merged = CookieStore.mergePasted(_content.value, text) ?: return false
        persist(merged)
        return true
    }

    fun saveEdits(edits: Map<ParsedCookie, String>) = persist(CookieStore.withValues(_content.value, edits))

    fun deleteSite(site: SiteCookies) = persist(CookieStore.without(_content.value, site.cookies.toSet()))

    fun clearAll() = persist("")

    /** Kept but not sent: a switched-off site's cookies are filtered out of every download and
     * preview (GalleryDlPreferences.filterCookiesByDisabledDomains) until switched back on. */
    fun setSiteEnabled(rootDomain: String, enabled: Boolean) {
        GalleryDlPreferences.setCookieDomainEnabled(context, rootDomain, enabled)
        _disabledDomains.value = GalleryDlPreferences.getDisabledCookieDomains(context)
    }

    private fun persist(content: String) {
        CookieStore.write(context, content)
        _content.value = content
    }
}
