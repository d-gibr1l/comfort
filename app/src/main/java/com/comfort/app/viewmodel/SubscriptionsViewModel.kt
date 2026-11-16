package com.comfort.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comfort.app.data.Subscription
import com.comfort.app.data.Subscriptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _adding = MutableStateFlow(false)
    /** A link typed on the page is being looked up. */
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    private val _addError = MutableStateFlow<String?>(null)
    /** Why the last link couldn't be added, under the field; cleared as it's edited. */
    val addError: StateFlow<String?> = _addError.asStateFlow()

    fun clearAddError() { _addError.value = null }

    /** Subscribes to a channel or playlist link typed or pasted on the page. Looked up first (its
     * newest upload only): a wrong or private link says so here and now, not at its first check,
     * and the card shows its real name and picture at once. [onAdded] gets the toast's text. */
    fun add(input: String, onAdded: (String) -> Unit) {
        if (_adding.value) return
        val url = Subscriptions.youTubeSourceUrl(input)
        if (url == null) {
            _addError.value = context.getString(com.comfort.app.R.string.subscriptions_not_a_source)
            return
        }
        _addError.value = null
        _adding.value = true
        viewModelScope.launch {
            try {
                val feed = com.comfort.app.util.SubscriptionFeed.fetch(context, url, 1).getOrElse { e ->
                    _addError.value = context.getString(com.comfort.app.R.string.subscribe_failed, e.message ?: e.javaClass.simpleName)
                    return@launch
                }
                val options = com.comfort.app.data.SubscriptionOptions(null, null, null, null, null, null)
                when (val result = Subscriptions.subscribe(context, url, feed.title, feed.thumbnail, options)) {
                    is Subscriptions.SubscribeResult.Added -> onAdded(context.getString(com.comfort.app.R.string.subscribed))
                    is Subscriptions.SubscribeResult.AlreadySubscribed ->
                        _addError.value = context.getString(com.comfort.app.R.string.already_subscribed_to_x, result.subscription.title)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _addError.value = context.getString(com.comfort.app.R.string.subscribe_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                _adding.value = false
            }
        }
    }
}
