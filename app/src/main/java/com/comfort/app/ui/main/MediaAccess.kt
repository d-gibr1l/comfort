package com.comfort.app.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.comfort.app.R
import com.comfort.app.data.DownloadEntity
import com.comfort.app.theme.HeaderFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reading the phone's photos, videos and music. Android lets an app open only the media files it
 * saved itself; downloads brought back by a restore were saved by another install (a reinstall,
 * another phone, the side-by-side build), so without this they show nothing and don't play.
 * Asked for only then (see [MediaAccessCard]).
 */
object MediaAccess {
    val permissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun granted(context: Context): Boolean = permissions.all { has(context, it) } ||
        // Android 14+: "Allow selected photos" also counts (the user's choice of what to share).
        (Build.VERSION.SDK_INT >= 34 && has(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private const val PREFS = "media_access"

    /** Android stops showing the prompt after it's been turned down twice: then Allow access opens
     * the app's own settings page instead of doing nothing. */
    fun promptBlocked(activity: android.app.Activity): Boolean =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("asked", false) &&
            permissions.none { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    fun markAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()

    /**
     * With access allowed, points the restored downloads at links this app can open. Comfort saves
     * through MediaStore's Downloads collection, and a Downloads link opens only for the app that
     * saved the file, whatever the permissions; the same file's link in the Images / Video / Audio
     * collection (the same row id) opens with them. Each link that's refused is swapped, in the
     * downloads and their saved files. Returns how many were.
     */
    suspend fun rehome(context: Context): Int = withContext(Dispatchers.IO) {
        if (!granted(context)) return@withContext 0
        val room = com.comfort.app.data.AppDatabase.getDatabase(context)
        val db = room.openHelper.writableDatabase
        val uris = HashSet<String>()
        db.query("SELECT mediaUri, thumbnailPath FROM downloads").use { c ->
            while (c.moveToNext()) { c.getString(0)?.let(uris::add); c.getString(1)?.let(uris::add) }
        }
        db.query("SELECT uri FROM saved_files").use { c -> while (c.moveToNext()) c.getString(0)?.let(uris::add) }
        val moves = HashMap<String, String>()
        for (old in uris) {
            val uri = Uri.parse(old)
            if (uri.authority != MediaStore.AUTHORITY || "downloads" !in uri.pathSegments) continue
            if (!lockedOut(context, uri)) continue
            val id = uri.lastPathSegment?.toLongOrNull() ?: continue
            val type = runCatching {
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE),
                    "${MediaStore.MediaColumns._ID} = ?", arrayOf(id.toString()), null,
                )?.use { if (it.moveToFirst()) it.getInt(0) else null }
            }.getOrNull() ?: continue
            val collection = when (type) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> continue
            }
            val new = android.content.ContentUris.withAppendedId(collection, id)
            if (lockedOut(context, new)) continue
            moves[old] = new.toString()
        }
        // Files restored from another phone (copied over, so under new ids there) or moved by an
        // app that copied them: their old links lead nowhere; found again by their saved name now
        // that the phone's media can be seen (BackupRestore.relinkByName).
        db.query("SELECT uri, path FROM saved_files WHERE path IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                val old = c.getString(0) ?: continue
                if (old in moves) continue
                val uri = runCatching { Uri.parse(old) }.getOrNull() ?: continue
                if (uri.authority != MediaStore.AUTHORITY || !com.comfort.app.util.MediaStoreHelper.reallyGone(context, uri)) continue
                val name = c.getString(1).substringAfterLast('/').takeIf { it.isNotBlank() } ?: continue
                com.comfort.app.data.BackupRestore.relinkByName(context, old, name)?.takeIf { it != old }?.let { moves[old] = it }
            }
        }
        // In Room's own transaction, so the Library's lists see the change at once (written
        // straight to the database, they kept the old links until the app restarted).
        if (moves.isNotEmpty()) room.runInTransaction {
            for ((old, n) in moves) {
                db.execSQL("UPDATE downloads SET mediaUri = ? WHERE mediaUri = ?", arrayOf(n, old))
                db.execSQL("UPDATE downloads SET thumbnailPath = ? WHERE thumbnailPath = ?", arrayOf(n, old))
                db.execSQL("UPDATE saved_files SET uri = ? WHERE uri = ?", arrayOf(n, old))
            }
        }
        // The music player's queue, if a song in it was on one of these links.
        if (moves.isNotEmpty()) withContext(Dispatchers.Main) { com.comfort.app.music.MusicPlayer.relink(moves) }
        moves.size
    }

    private fun lockedOut(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.close()
        false
    } catch (_: SecurityException) {
        true
    } catch (_: Exception) {
        false
    }

    /** Whether this launch has looked for moved files yet (see MediaAccessCard). */
    @Volatile var relinkedThisRun = false

    /** Whether any of [downloads]' files is on the phone but closed to this app (a few checked). */
    suspend fun anyLockedOut(context: Context, downloads: List<DownloadEntity>): Boolean = withContext(Dispatchers.IO) {
        downloads.asSequence()
            .mapNotNull { (it.mediaUri ?: it.thumbnailPath)?.let(Uri::parse) }
            .filter { it.authority == MediaStore.AUTHORITY }
            .take(30)
            .any { uri ->
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.close()
                    false
                } catch (_: SecurityException) {
                    true
                } catch (_: Exception) {
                    false
                }
            }
    }
}

/** Over the Library while restored downloads can't be opened: what's wrong, and Allow access. */
@Composable
internal fun MediaAccessCard(downloads: List<DownloadEntity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(MediaAccess.granted(context)) }
    var lockedOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Allowed in Android's settings meanwhile: seen on coming back.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = MediaAccess.granted(context)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(downloads.size, granted) {
        val closed = MediaAccess.anyLockedOut(context, downloads)
        // Allowed: once a launch, links that lead nowhere looked for by name too (a restore from
        // another phone, whose files came over under new ids), not only the closed ones.
        if (granted && (closed || !MediaAccess.relinkedThisRun)) {
            MediaAccess.relinkedThisRun = true
            // Allowed (here or in Android's settings) but still on links that stay closed: moved
            // over, and every picture and player built while they were closed built again.
            if (MediaAccess.rehome(context) > 0) (context as? android.app.Activity)?.recreate()
        }
        lockedOut = closed && !granted
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MediaAccess.markAsked(context)
        granted = MediaAccess.granted(context)
        if (granted) scope.launch {
            MediaAccess.rehome(context)
            (context as? android.app.Activity)?.recreate()
        }
    }
    if (!lockedOut) return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.media_access_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = HeaderFontFamily),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.media_access_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val activity = context as? android.app.Activity
                    if (activity != null && MediaAccess.promptBlocked(activity)) {
                        // The prompt won't show again: the app's settings, where Permissions are.
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    } else {
                        launcher.launch(MediaAccess.permissions)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.media_access_allow), fontFamily = HeaderFontFamily)
            }
        }
    }
}
