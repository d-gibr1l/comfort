package com.comfort.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Swipe gestures that can be turned off (Settings › Look & feel › Gestures), as flows so the screens
 * already open follow a change at once:
 * - [swipeToRemove]: a Library card swiped sideways is removed (its ⋮ menu's Remove stays);
 * - [swipeBetweenPages]: swiping sideways moves between Home, Library and Settings, and between the
 *   Queue's tabs (the nav bar and the tabs stay).
 */
object GestureSettings {
    private const val PREFS = "gesture_settings"
    private const val KEY_SWIPE_TO_REMOVE = "swipe_to_remove"
    private const val KEY_SWIPE_BETWEEN_PAGES = "swipe_between_pages"

    private val _swipeToRemove = MutableStateFlow(true)
    val swipeToRemove: StateFlow<Boolean> = _swipeToRemove.asStateFlow()
    private val _swipeBetweenPages = MutableStateFlow(true)
    val swipeBetweenPages: StateFlow<Boolean> = _swipeBetweenPages.asStateFlow()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _swipeToRemove.value = p.getBoolean(KEY_SWIPE_TO_REMOVE, true)
        _swipeBetweenPages.value = p.getBoolean(KEY_SWIPE_BETWEEN_PAGES, true)
        loaded = true
    }

    fun setSwipeToRemove(context: Context, on: Boolean) {
        _swipeToRemove.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SWIPE_TO_REMOVE, on).apply()
    }

    fun setSwipeBetweenPages(context: Context, on: Boolean) {
        _swipeBetweenPages.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SWIPE_BETWEEN_PAGES, on).apply()
    }
}
