/*
 * Ported from Photon Gallery (https://github.com/Bn5prS/Photon_Gallery), GPL-3.0, like Comfort.
 * Adapted for Comfort: package, imports and the pieces Photon's app provides that Comfort doesn't
 * (see the notes where they were changed).
 */
package com.comfort.app.ui.viewer

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class GalleryItem(
    val id: String,
    val uri: Uri,
    val bucketName: String,
    val dateAdded: Long,
    val size: Long,
    val name: String,
    val dateModified: Long,
    val path: String,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    val searchScore: Float? = null,
    /** Pre-computed on IO: whether the local file exists on disk. */
    val localExists: Boolean = true,
    /** Pre-computed on IO: the URI to use for thumbnail loading. */
    val resolvedUri: Uri = uri,
    val pHash: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val fileHash: String? = null
)
