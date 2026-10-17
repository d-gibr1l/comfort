package com.comfort.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings > Backup & restore: the app's settings, its cookies and the Library's history, saved
 * to one JSON file the user picks, and read back from one.
 *
 * History rows are copied column by column (whatever the table has), so a backup from an older
 * or newer version still restores: columns this version doesn't have are skipped, and ones the
 * backup lacks take their defaults. Restoring adds to what's there; a download already in the
 * Library is kept as it is.
 */
object BackupRestore {
    private const val FORMAT = "comfort-backup"
    private const val VERSION = 1

    // The settings files backed up: everything the user sets, and the theme.
    private val SETTINGS_FILES = listOf(GalleryDlPreferences.PREFS_NAME, "gallerydl_theme_prefs")

    // Folder choices are permissions granted to this install only: restored, they'd point
    // at folders the app can no longer write to.
    private val SKIPPED_SETTINGS = setOf(
        GalleryDlPreferences.KEY_DOWNLOAD_LOCATION_URI,
        GalleryDlPreferences.KEY_AUDIO_LOCATION_URI,
        GalleryDlPreferences.KEY_VIDEO_LOCATION_URI,
    )

    // What counts as history: downloads that finished (not the queue, errors or the bin).
    private const val HISTORY_WHERE = "status IN ('FINISHED', 'SAVED')"

    /** What a backup holds, or what was restored from one. */
    data class Contents(val settings: Int, val hasCookies: Boolean, val history: Int)

    /** Which parts to save or restore. */
    data class Parts(val settings: Boolean, val cookies: Boolean, val history: Boolean)

    suspend fun export(context: Context, uri: Uri, parts: Parts): Contents = withContext(Dispatchers.IO) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("created", System.currentTimeMillis())
        var settingsCount = 0
        if (parts.settings) {
            val settings = JSONObject()
            for (file in SETTINGS_FILES) {
                val values = JSONObject()
                context.getSharedPreferences(file, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    if (key in SKIPPED_SETTINGS) return@forEach
                    encodeSetting(value)?.let { values.put(key, it); settingsCount++ }
                }
                settings.put(file, values)
            }
            root.put("settings", settings)
        }
        val cookies = if (parts.cookies) CookieStore.read(context) else ""
        if (cookies.isNotBlank()) root.put("cookies", cookies)
        var historyCount = 0
        if (parts.history) {
            val db = AppDatabase.getDatabase(context).openHelper.readableDatabase
            val downloads = rows(db.query("SELECT * FROM downloads WHERE $HISTORY_WHERE"))
            val files = rows(db.query("SELECT * FROM saved_files WHERE downloadId IN (SELECT id FROM downloads WHERE $HISTORY_WHERE)"))
            historyCount = downloads.length()
            root.put("history", JSONObject().put("downloads", downloads).put("saved_files", files))
        }
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the file")
        out.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        Contents(settingsCount, cookies.isNotBlank(), historyCount)
    }

    /** A backup file, read and checked, ready to [restore]. */
    class Backup internal constructor(internal val root: JSONObject) {
        val contents: Contents
            get() = Contents(
                settings = root.optJSONObject("settings")?.let { s -> s.keys().asSequence().sumOf { s.optJSONObject(it)?.length() ?: 0 } } ?: 0,
                hasCookies = root.optString("cookies").isNotBlank(),
                history = root.optJSONObject("history")?.optJSONArray("downloads")?.length() ?: 0,
            )
    }

    /** Reads [uri]; throws [IllegalArgumentException] if it isn't a Comfort backup. */
    suspend fun read(context: Context, uri: Uri): Backup = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Couldn't open the file")
        val root = runCatching { JSONObject(text) }.getOrNull()
        require(root != null && root.optString("format") == FORMAT) { "Not a Comfort backup" }
        Backup(root)
    }

    suspend fun restore(context: Context, backup: Backup, parts: Parts): Contents = withContext(Dispatchers.IO) {
        val root = backup.root
        var settingsCount = 0
        if (parts.settings) {
            val settings = root.optJSONObject("settings")
            for (file in SETTINGS_FILES) {
                val values = settings?.optJSONObject(file) ?: continue
                val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                values.keys().forEach { key ->
                    if (key in SKIPPED_SETTINGS) return@forEach
                    if (decodeSetting(editor, key, values.optJSONObject(key))) settingsCount++
                }
                editor.commit()
            }
        }
        val cookies = root.optString("cookies")
        val restoreCookies = parts.cookies && cookies.isNotBlank()
        if (restoreCookies) CookieStore.write(context, cookies)
        var historyCount = 0
        val history = root.optJSONObject("history")
        if (parts.history && history != null) {
            val room = AppDatabase.getDatabase(context)
            // In a Room transaction, so the Library's lists see the new rows when it ends.
            room.runInTransaction {
                val db = room.openHelper.writableDatabase
                historyCount = insertRows(db, "downloads", history.optJSONArray("downloads"))
                insertRows(db, "saved_files", history.optJSONArray("saved_files"))
            }
        }
        Contents(settingsCount, restoreCookies, historyCount)
    }

    private fun encodeSetting(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun decodeSetting(editor: android.content.SharedPreferences.Editor, key: String, o: JSONObject?): Boolean {
        if (o == null || !o.has("v")) return false
        when (o.optString("t")) {
            "b" -> editor.putBoolean(key, o.optBoolean("v"))
            "i" -> editor.putInt(key, o.optInt("v"))
            "l" -> editor.putLong(key, o.optLong("v"))
            "f" -> editor.putFloat(key, o.optDouble("v").toFloat())
            "s" -> editor.putString(key, o.optString("v"))
            "ss" -> {
                val array = o.optJSONArray("v") ?: return false
                editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
            }
            else -> return false
        }
        return true
    }

    private fun rows(cursor: Cursor): JSONArray = cursor.use { c ->
        val array = JSONArray()
        while (c.moveToNext()) {
            val row = JSONObject()
            for (i in 0 until c.columnCount) {
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> row.put(c.getColumnName(i), c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> row.put(c.getColumnName(i), c.getDouble(i))
                    Cursor.FIELD_TYPE_STRING -> row.put(c.getColumnName(i), c.getString(i))
                    // Nulls are left out (the column's default); there are no blobs.
                }
            }
            array.put(row)
        }
        array
    }

    /** Inserts [rows] into [table], only the columns it has; a row already there is kept. */
    private fun insertRows(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, rows: JSONArray?): Int {
        if (rows == null) return 0
        val columns = db.query("PRAGMA table_info($table)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildSet { while (c.moveToNext()) add(c.getString(name)) }
        }
        var inserted = 0
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val values = ContentValues()
            row.keys().forEach { key ->
                if (key !in columns) return@forEach
                when (val v = row.get(key)) {
                    is Int -> values.put(key, v.toLong())
                    is Long -> values.put(key, v)
                    is Double -> values.put(key, v)
                    is String -> values.put(key, v)
                    is Boolean -> values.put(key, if (v) 1L else 0L)
                }
            }
            if (db.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values) != -1L) inserted++
        }
        return inserted
    }
}
