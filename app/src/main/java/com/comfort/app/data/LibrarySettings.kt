package com.comfort.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Library's own settings (Settings › Library), as flows so the screens already open follow a
 * change at once:
 * - [deleteFromPhone]: what Delete does (the ⋮ menu, a swipe, the selection bar, the viewer): the
 *   files deleted from the phone and the download kept under Deleted, or the download taken out of
 *   the Library only, its files left where they are;
 * - [showDeleted] / [showDuplicates]: the Library's Deleted and Duplicates chips;
 * - [clearDeletedAfterDays]: a download under Deleted is forgotten this many days after it got
 *   there (0: kept until cleared by hand).
 */
object LibrarySettings {
    private const val PREFS = "library_settings"
    private const val KEY_DELETE_FROM_PHONE = "delete_from_phone"
    private const val KEY_SHOW_DELETED = "show_deleted"
    private const val KEY_SHOW_DUPLICATES = "show_duplicates"
    private const val KEY_CLEAR_DELETED_DAYS = "clear_deleted_days"
    // When each download was first seen under Deleted (id -> ms), for [clearDeletedAfterDays].
    private const val SEEN_PREFS = "library_deleted_seen"

    /** The choices offered for [clearDeletedAfterDays]. */
    val CLEAR_DELETED_CHOICES = listOf(0, 7, 30)

    private val _deleteFromPhone = MutableStateFlow(true)
    val deleteFromPhone: StateFlow<Boolean> = _deleteFromPhone.asStateFlow()
    private val _showDeleted = MutableStateFlow(true)
    val showDeleted: StateFlow<Boolean> = _showDeleted.asStateFlow()
    private val _showDuplicates = MutableStateFlow(true)
    val showDuplicates: StateFlow<Boolean> = _showDuplicates.asStateFlow()
    private val _clearDeletedAfterDays = MutableStateFlow(0)
    val clearDeletedAfterDays: StateFlow<Int> = _clearDeletedAfterDays.asStateFlow()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val p = prefs(context)
        _deleteFromPhone.value = p.getBoolean(KEY_DELETE_FROM_PHONE, true)
        _showDeleted.value = p.getBoolean(KEY_SHOW_DELETED, true)
        _showDuplicates.value = p.getBoolean(KEY_SHOW_DUPLICATES, true)
        _clearDeletedAfterDays.value = p.getInt(KEY_CLEAR_DELETED_DAYS, 0)
        loaded = true
    }

    fun setDeleteFromPhone(context: Context, on: Boolean) {
        _deleteFromPhone.value = on
        prefs(context).edit().putBoolean(KEY_DELETE_FROM_PHONE, on).apply()
    }

    fun setShowDeleted(context: Context, on: Boolean) {
        _showDeleted.value = on
        prefs(context).edit().putBoolean(KEY_SHOW_DELETED, on).apply()
    }

    fun setShowDuplicates(context: Context, on: Boolean) {
        _showDuplicates.value = on
        prefs(context).edit().putBoolean(KEY_SHOW_DUPLICATES, on).apply()
    }

    fun setClearDeletedAfterDays(context: Context, days: Int) {
        _clearDeletedAfterDays.value = days
        prefs(context).edit().putInt(KEY_CLEAR_DELETED_DAYS, days).apply()
    }

    /** Of the downloads under Deleted now ([deletedIds]), those there longer than
     * [clearDeletedAfterDays] (none when it's 0). Each is dated from when it was first seen there,
     * and ones no longer under Deleted are forgotten. */
    fun dueForClearing(context: Context, deletedIds: Collection<String>, now: Long = System.currentTimeMillis()): List<String> {
        val seen = context.applicationContext.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
        val edit = seen.edit()
        val known = seen.all
        known.keys.filter { it !in deletedIds }.forEach { edit.remove(it) }
        deletedIds.filter { it !in known }.forEach { edit.putLong(it, now) }
        val days = _clearDeletedAfterDays.value
        val due = if (days <= 0) emptyList() else deletedIds.filter { id ->
            val at = known[id] as? Long ?: return@filter false
            now - at >= days * 24L * 60 * 60 * 1000
        }
        due.forEach { edit.remove(it) }
        edit.apply()
        return due
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
