package com.comfort.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved, named set of command-line arguments, added to a download from its Commands screen. */
data class CommandTemplate(val name: String, val args: String)

/** The saved command templates (Settings > Advanced > Command templates), kept in the app's
 * settings as a JSON list, in the order they were made. */
object CommandTemplates {
    const val KEY = "command_templates"

    fun getAll(context: Context): List<CommandTemplate> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                val args = o.optString("args").trim()
                if (name.isEmpty() || args.isEmpty()) null else CommandTemplate(name, args)
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, templates: List<CommandTemplate>) {
        val array = JSONArray()
        templates.forEach { array.put(JSONObject().put("name", it.name).put("args", it.args)) }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    /** Adds [template], or replaces the one named [replacing] (an edit) in its place. A template
     * already using the new name is replaced too, so names stay unique. */
    fun put(context: Context, template: CommandTemplate, replacing: String? = null) {
        val current = getAll(context)
        val at = current.indexOfFirst { it.name == (replacing ?: template.name) }
        val others = current.filter { it.name != template.name && it.name != replacing }
        val next = if (at < 0) others + template else others.toMutableList().apply { add(at.coerceAtMost(size), template) }
        save(context, next)
    }

    fun remove(context: Context, name: String) {
        save(context, getAll(context).filter { it.name != name })
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(GalleryDlPreferences.PREFS_NAME, Context.MODE_PRIVATE)
}
