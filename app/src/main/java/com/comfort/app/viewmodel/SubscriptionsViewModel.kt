package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.Subscription
import com.comfort.app.data.Subscriptions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings › Subscriptions' data: the saved sources, live from the database, and which are being
 * checked right now. Every action writes straight through [Subscriptions]. */
class SubscriptionsViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    /** Null until the first read, so the page doesn't flash its empty state while loading. */
    val subscriptions: StateFlow<List<Subscription>?> = Subscriptions.observeAll(application)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val checking: StateFlow<Set<String>> = Subscriptions.checking

    fun checkNow(id: String) = Subscriptions.requestCheck(context, id)

    fun setPaused(id: String, paused: Boolean) {
        viewModelScope.launch { Subscriptions.setPaused(context, id, paused) }
    }

    fun setInterval(id: String, hours: Int) {
        viewModelScope.launch { Subscriptions.setInterval(context, id, hours) }
    }

    fun remove(id: String) {
        viewModelScope.launch { Subscriptions.remove(context, id) }
    }
}
