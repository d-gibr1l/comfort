package com.comfort.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Every site each engine supports, split three ways for Settings › Updates and engines › Sites:
 * sites only gallery-dl supports, sites only yt-dlp supports, and sites both do. */
data class EngineSites(val galleryDlOnly: List<String>, val ytDlpOnly: List<String>, val both: List<String>)

/** Builds [EngineSites] from the engines themselves (engine_sites.py reads their extractors — about
 * a second) and caches the result in filesDir, keyed by the installed engine versions, so it's
 * rebuilt exactly when an engine changes (an update, a runtime re-unpack). */
object EngineSitesRepository {
    private const val CACHE_FILE = "engine_sites.json"
    @Volatile private var memory: Pair<String, EngineSites>? = null

    suspend fun load(context: Context): EngineSites? = withContext(Dispatchers.IO) {
        val key = listOf(EngineUpdater.YT_DLP, EngineUpdater.GALLERY_DL)
            .joinToString("|") { EngineUpdater.installedVersion(context, it).orEmpty() }
        memory?.takeIf { it.first == key }?.let { return@withContext it.second }
        val cache = File(context.filesDir, CACHE_FILE)
        val cached = runCatching { JSONObject(cache.readText()) }.getOrNull()?.takeIf { it.optString("key") == key }
        val json = cached ?: generate(context)?.also { fresh ->
            runCatching { cache.writeText(fresh.put("key", key).toString()) }
        } ?: return@withContext null
        val galleryDl = json.optJSONArray("gallery_dl").strings().toSet()
        val ytDlp = json.optJSONArray("yt_dlp").strings().toSet()
        EngineSites(
            galleryDlOnly = (galleryDl - ytDlp).sorted(),
            ytDlpOnly = (ytDlp - galleryDl).sorted(),
            both = (galleryDl intersect ytDlp).sorted(),
        ).also { memory = key to it }
    }

    private suspend fun generate(context: Context): JSONObject? {
        val lines = mutableListOf<String>()
        runCatching { PythonRuntime.run(context, "engine_sites.py", emptyList()) { lines += it } }
        return lines.asReversed().firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        }?.takeIf { (it.optJSONArray("gallery_dl")?.length() ?: 0) + (it.optJSONArray("yt_dlp")?.length() ?: 0) > 0 }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
