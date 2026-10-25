/*
 * Ported from Photon Gallery (https://github.com/Bn5prS/Photon_Gallery), GPL-3.0, like Comfort.
 * Adapted for Comfort: package, imports and the pieces Photon's app provides that Comfort doesn't
 * (see the notes where they were changed).
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.comfort.app.ui.viewer

import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import android.icu.util.MeasureUnit
import com.comfort.app.util.LocalFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Bitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import com.comfort.app.R


// ── Detailed EXIF Data Model ──────────────────────────────────────────────────

data class DeepExifTag(
    val category: String,
    val tag: String,
    val value: String
)

data class DetailedExifData(
    // File & Format
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String?,
    val formatDescription: String,
    val dateCreated: Long?,
    val dateModified: Long?,
    val storagePath: String,
    val albumName: String,

    // Resolution & Geometry
    val width: Int,
    val height: Int,
    val resolutionString: String,
    val megapixelsString: String,
    val aspectRatioString: String,
    val colorSpace: String?,
    val orientation: String?,

    // Camera & Optics
    val cameraMaker: String?,
    val cameraModel: String?,
    val lensModel: String?,
    val software: String?,
    val aperture: String?,
    val iso: String?,
    val shutterSpeed: String?,
    val focalLength: String?,
    val focalLength35mm: String?,
    val flash: String?,
    val whiteBalance: String?,
    val exposureMode: String?,
    val meteringMode: String?,

    // GPS & Location
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val formattedCoordinates: String?,

    // Video Specific (if video)
    val isVideo: Boolean,
    val durationMs: Long?,
    val bitrate: Long?,

    // All extracted raw tags
    val allTags: List<DeepExifTag>
)

// ── Raw EXIF Tag Names to Inspect ─────────────────────────────────────────────

private val KNOWN_EXIF_TAGS = listOf(
    // Image Structure
    R.string.image_structure to listOf(
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION,
        ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
        ExifInterface.TAG_Y_CB_CR_POSITIONING,
        ExifInterface.TAG_X_RESOLUTION,
        ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_RESOLUTION_UNIT
    ),
    // Camera & Hardware
    R.string.camera_hardware to listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION
    ),
    // Capture & Exposure Parameters
    R.string.capture_parameters to listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_SENSING_METHOD,
        ExifInterface.TAG_SCENE_TYPE
    ),
    // GPS & Positioning
    R.string.gps_location to listOf(
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR
    )
)

// ── Extraction Helper ─────────────────────────────────────────────────────────

suspend fun extractDetailedExif(context: Context, item: GalleryItem): DetailedExifData = withContext(Dispatchers.IO) {
    val uri = item.uri
    val allTagsList = mutableListOf<DeepExifTag>()

    var make: String? = null
    var model: String? = null
    var lensModel: String? = null
    var software: String? = null
    var aperture: String? = null
    var iso: String? = null
    var shutterSpeed: String? = null
    var focalLength: String? = null
    var focalLength35mm: String? = null
    var flash: String? = null
    var whiteBalance: String? = null
    var exposureMode: String? = null
    var meteringMode: String? = null
    var colorSpaceStr: String? = null
    var orientationStr: String? = null
    var lat: Double? = null
    var lng: Double? = null
    var alt: Double? = null
    var dateCreatedParsed: Long? = null
    var imgW = 0
    var imgH = 0

    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)

            // Extract known tags into raw tag list
            for ((categoryRes, tags) in KNOWN_EXIF_TAGS) {
                val category = context.getString(categoryRes)
                for (tagName in tags) {
                    val value = exif.getAttribute(tagName)
                    if (!value.isNullOrBlank()) {
                        allTagsList.add(DeepExifTag(category, tagName, value.trim()))
                    }
                }
            }

            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()

            aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
            iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }

            val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            shutterSpeed = when {
                exposureTime <= 0.0 -> null
                exposureTime < 1.0 -> {
                    val denominator = (1.0 / exposureTime).roundToInt()
                    "1/${denominator}s"
                }
                else -> String.format(Locale.US, "%.1fs", exposureTime)
            }

            focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it}mm" }
            focalLength35mm = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let { "${it}mm (35mm eq)" }

            val flashVal = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
            if (flashVal != -1) {
                flash = if (flashVal and 1 != 0) context.getString(R.string.fired) else context.getString(R.string.did_not_fire)
            }

            val wbVal = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)
            if (wbVal != -1) {
                whiteBalance = if (wbVal == ExifInterface.WHITE_BALANCE_AUTO.toInt()) context.getString(R.string.auto_white_balance) else context.getString(R.string.manual_white_balance)
            }

            val expModeVal = exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_MODE, -1)
            if (expModeVal != -1) {
                exposureMode = when (expModeVal) {
                    ExifInterface.EXPOSURE_MODE_AUTO.toInt() -> context.getString(R.string.auto_exposure)
                    ExifInterface.EXPOSURE_MODE_MANUAL.toInt() -> context.getString(R.string.manual_exposure)
                    else -> context.getString(R.string.auto_bracket)
                }
            }

            val meterVal = exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1)
            if (meterVal != -1) {
                meteringMode = when (meterVal) {
                    1 -> context.getString(R.string.average)
                    2 -> context.getString(R.string.center_weighted_average)
                    3 -> context.getString(R.string.spot)
                    4 -> context.getString(R.string.multi_spot)
                    5 -> context.getString(R.string.pattern_multi_segment)
                    6 -> context.getString(R.string.partial)
                    else -> context.getString(R.string.other)
                }
            }

            val csVal = exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)
            if (csVal != -1) {
                colorSpaceStr = if (csVal == 1) "sRGB" else context.getString(R.string.uncalibrated_adobe_rgb)
            }

            val orientVal = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            orientationStr = when (orientVal) {
                ExifInterface.ORIENTATION_NORMAL -> context.getString(R.string.n_0_normal)
                ExifInterface.ORIENTATION_ROTATE_90 -> context.getString(R.string.n_90_clockwise)
                ExifInterface.ORIENTATION_ROTATE_180 -> "180°"
                ExifInterface.ORIENTATION_ROTATE_270 -> context.getString(R.string.n_270_clockwise)
                else -> context.getString(R.string.default_text)
            }

            imgW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            imgH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

            val latLong = exif.latLong
            if (latLong != null && latLong.size >= 2) {
                lat = latLong[0]
                lng = latLong[1]
            }
            alt = exif.getAltitude(0.0).takeIf { it != 0.0 }

            val dateOrigStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (dateOrigStr != null) {
                try {
                    val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    dateCreatedParsed = sdf.parse(dateOrigStr)?.time
                } catch (_: Exception) {}
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Fallback image dimensions from BitmapFactory or MediaStore if EXIF was empty
    if (imgW <= 0 || imgH <= 0) {
        try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            imgW = opts.outWidth
            imgH = opts.outHeight
        } catch (_: Exception) {}
    }

    // Comfort: a video has no EXIF and BitmapFactory can't read one — its own track size,
    // turned for its rotation (Photon showed "Unknown Resolution" for every video).
    if ((imgW <= 0 || imgH <= 0) && item.isVideo) {
        try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) { imgW = h; imgH = w } else { imgW = w; imgH = h }
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {}
    }

    val resStr = if (imgW > 0 && imgH > 0) "$imgW × $imgH px" else context.getString(R.string.unknown_resolution)
    val mpStr = if (imgW > 0 && imgH > 0) {
        val mp = (imgW.toLong() * imgH.toLong()) / 1_000_000.0
        String.format(Locale.US, "%.1f MP", mp)
    } else ""

    val aspectStr = if (imgW > 0 && imgH > 0) {
        val gcd = computeGcd(imgW, imgH)
        val wRatio = imgW / gcd
        val hRatio = imgH / gcd
        if (wRatio <= 20 && hRatio <= 20) "$wRatio:$hRatio" else String.format(Locale.US, "%.2f:1", imgW.toFloat() / imgH.toFloat())
    } else ""

    val extension = item.name.substringAfterLast(".", "").uppercase(Locale.US)
    val formatDesc = when {
        item.isVideo -> context.getString(R.string.video_x, extension)
        extension in listOf("JPG", "JPEG") -> context.getString(R.string.jpeg_image)
        extension == "PNG" -> context.getString(R.string.png_image)
        extension == "WEBP" -> context.getString(R.string.webp_image)
        extension == "HEIC" -> context.getString(R.string.heif_heic_image)
        extension in listOf("DNG", "RAW", "CR2", "NEF") -> context.getString(R.string.raw_digital_negative)
        extension == "GIF" -> context.getString(R.string.animated_gif)
        else -> context.getString(R.string.x_file, extension)
    }

    val album = item.path.substringBeforeLast("/", "").substringAfterLast("/", "Gallery")

    val finalLat = lat
    val finalLng = lng
    val formattedCoords = if (finalLat != null && finalLng != null) {
        val latDir = if (finalLat >= 0) "N" else "S"
        val lngDir = if (finalLng >= 0) "E" else "W"
        "${String.format(Locale.US, "%.5f", kotlin.math.abs(finalLat))}° $latDir, ${String.format(Locale.US, "%.5f", kotlin.math.abs(finalLng))}° $lngDir"
    } else null

    DetailedExifData(
        fileName = item.name,
        fileSizeBytes = item.size,
        mimeType = if (item.isVideo) "video/*" else "image/${extension.lowercase()}",
        formatDescription = formatDesc,
        dateCreated = dateCreatedParsed ?: (item.dateModified * 1000L),
        dateModified = item.dateModified * 1000L,
        storagePath = item.path,
        albumName = album,
        width = imgW,
        height = imgH,
        resolutionString = resStr,
        megapixelsString = mpStr,
        aspectRatioString = aspectStr,
        colorSpace = colorSpaceStr,
        orientation = orientationStr,
        cameraMaker = make,
        cameraModel = model,
        lensModel = lensModel,
        software = software,
        aperture = aperture,
        iso = iso,
        shutterSpeed = shutterSpeed,
        focalLength = focalLength,
        focalLength35mm = focalLength35mm,
        flash = flash,
        whiteBalance = whiteBalance,
        exposureMode = exposureMode,
        meteringMode = meteringMode,
        latitude = lat,
        longitude = lng,
        altitude = alt,
        formattedCoordinates = formattedCoords,
        isVideo = item.isVideo,
        durationMs = null,
        bitrate = null,
        allTags = allTagsList
    )
}

private fun computeGcd(a: Int, b: Int): Int {
    var n1 = a
    var n2 = b
    while (n2 != 0) {
        val temp = n2
        n2 = n1 % n2
        n1 = temp
    }
    return if (n1 > 0) n1 else 1
}

// ── ExifDetailsSheet Composable ───────────────────────────────────────────────

@Composable
fun ExifDetailsSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    galleryItem: GalleryItem?,
    exifData: DetailedExifData?
) {
    if (!isOpen || galleryItem == null) return
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeepMetadataSheet by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = ShapeEdgeTop,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.info_metadata),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = exifData?.formatDescription ?: stringResource(R.string.media_details),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_ms_close), contentDescription = stringResource(R.string.close))
                    }
                }
            }


            // ── File & Timeline Card ─────────────────────────────────────────
            item {
                Surface(
                    shape = ShapeLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // File Name Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = ShapeFull,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (galleryItem.isVideo) ImageVector.vectorResource(R.drawable.ic_ms_movie) else ImageVector.vectorResource(R.drawable.ic_ms_image),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = galleryItem.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = exifData?.formatDescription ?: stringResource(R.string.image_file),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // Dates Table
                        val createdDate = exifData?.dateCreated
                        val modifiedDate = exifData?.dateModified ?: (galleryItem.dateModified * 1000L)
                        val isModifiedDifferent = createdDate != null && kotlin.math.abs(modifiedDate - createdDate) > 60_000L

                        if (createdDate != null) {
                            DetailKeyValueRow(
                                label = stringResource(R.string.date_taken_created),
                                value = formatFullTimestamp(createdDate),
                                icon = ImageVector.vectorResource(R.drawable.ic_ms_calendar_month)
                            )
                        }

                        if (isModifiedDifferent) {
                            DetailKeyValueRow(
                                label = stringResource(R.string.date_modified),
                                value = formatFullTimestamp(modifiedDate),
                                icon = ImageVector.vectorResource(R.drawable.ic_ms_schedule)
                            )
                        }

                        // File Size Row
                        DetailKeyValueRow(
                            label = stringResource(R.string.file_size),
                            value = formatDetailedSize(galleryItem.size),
                            icon = ImageVector.vectorResource(R.drawable.ic_ms_description)
                        )
                    }
                }
            }

            // ── Resolution, Pixel Dimensions & Aspect Ratio Card ─────────────
            item {
                Surface(
                    shape = ShapeLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.geometry_dimensions),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Pixel Dimensions
                            MetricBadgeBlock(
                                label = stringResource(R.string.dimensions),
                                value = exifData?.resolutionString ?: stringResource(R.string.unknown_site),
                                modifier = Modifier.weight(1f)
                            )
                            // Megapixels
                            if (!exifData?.megapixelsString.isNullOrEmpty()) {
                                MetricBadgeBlock(
                                    label = stringResource(R.string.pixel_count),
                                    value = exifData!!.megapixelsString,
                                    modifier = Modifier.weight(0.7f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Aspect Ratio
                            if (!exifData?.aspectRatioString.isNullOrEmpty()) {
                                MetricBadgeBlock(
                                    label = stringResource(R.string.aspect_ratio),
                                    value = exifData!!.aspectRatioString,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Color Space
                            exifData?.colorSpace?.let { cs ->
                                MetricBadgeBlock(
                                    label = stringResource(R.string.color_space),
                                    value = cs,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // ── Camera Optics & Shooting Parameters Card ─────────────────────
            val hasCamera = exifData?.cameraModel != null || exifData?.aperture != null || exifData?.iso != null || exifData?.shutterSpeed != null
            if (hasCamera) {
                item {
                    Surface(
                        shape = ShapeLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_ms_photo_camera),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = exifData?.cameraModel ?: exifData?.cameraMaker ?: stringResource(R.string.camera_optics),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (exifData?.lensModel != null) {
                                        Text(
                                            text = exifData.lensModel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // 4-Quadrant Exposure Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                exifData?.aperture?.let { ap ->
                                    ExposureBadge(
                                        label = stringResource(R.string.aperture),
                                        value = ap,
                                        icon = ImageVector.vectorResource(R.drawable.ic_ms_photo_camera),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                exifData?.shutterSpeed?.let { ss ->
                                    ExposureBadge(
                                        label = stringResource(R.string.shutter),
                                        value = ss,
                                        icon = ImageVector.vectorResource(R.drawable.ic_ms_schedule),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                exifData?.iso?.let { iso ->
                                    ExposureBadge(
                                        label = "ISO",
                                        value = iso,
                                        icon = ImageVector.vectorResource(R.drawable.ic_ms_light_mode),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                (exifData?.focalLength35mm ?: exifData?.focalLength)?.let { fl ->
                                    ExposureBadge(
                                        label = stringResource(R.string.focal_length),
                                        value = fl,
                                        icon = ImageVector.vectorResource(R.drawable.ic_ms_center_focus_strong),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Additional parameters
                            exifData?.flash?.let { fl ->
                                DetailKeyValueRow(label = stringResource(R.string.flash), value = fl, icon = ImageVector.vectorResource(R.drawable.ic_ms_bolt))
                            }
                            exifData?.whiteBalance?.let { wb ->
                                DetailKeyValueRow(label = stringResource(R.string.white_balance), value = wb, icon = ImageVector.vectorResource(R.drawable.ic_ms_tune))
                            }
                        }
                    }
                }
            }

            // ── GPS Location & Coordinates Card ──────────────────────────────
            if (exifData?.latitude != null && exifData.longitude != null) {
                val lat = exifData.latitude
                val lng = exifData.longitude
                val coordString = exifData.formattedCoordinates ?: "$lat, $lng"

                item {
                    Surface(
                        shape = ShapeLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_ms_location_on),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.location_coordinates),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                Surface(
                                    shape = ShapeFull,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.coordinates), "$lat, $lng"))
                                        Toast.makeText(context, context.getString(R.string.coordinates_copied), Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(ImageVector.vectorResource(R.drawable.ic_ms_content_copy), contentDescription = stringResource(R.string.copy), modifier = Modifier.size(14.dp))
                                        Text(
                                            stringResource(R.string.copy),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            Text(
                                text = coordString,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )

                            // Open in Maps Button
                            FilledTonalButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(Photo Location)"))
                                    mapIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    try {
                                        context.startActivity(mapIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, context.getString(R.string.no_map_application_available), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = ShapeFull,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_ms_map), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.open_in_maps),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // ── Storage Path & Album Card ────────────────────────────────────
            item {
                Surface(
                    shape = ShapeLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.storage_path_album),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_ms_folder), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                text = exifData?.albumName ?: stringResource(R.string.gallery),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = galleryItem.path,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ── Deep Metadata Action Button ──────────────────────────────────
            item {
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showDeepMetadataSheet = true
                    },
                    shape = ShapeFull,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_ms_code), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.view_deep_metadata_x_tags, exifData?.allTags?.size ?: 0),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }

    // ── Deep Metadata Inspector Modal ─────────────────────────────────────────
    if (showDeepMetadataSheet && exifData != null) {
        DeepMetadataInspectorSheet(
            allTags = exifData.allTags,
            onDismiss = { showDeepMetadataSheet = false }
        )
    }
}

// ── Deep Metadata Inspector Modal Sheet ───────────────────────────────────────

@Composable
private fun DeepMetadataInspectorSheet(
    allTags: List<DeepExifTag>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredTags = remember(searchQuery, allTags) {
        if (searchQuery.isBlank()) allTags
        else allTags.filter {
            it.tag.contains(searchQuery, ignoreCase = true) ||
            it.value.contains(searchQuery, ignoreCase = true) ||
            it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = ShapeEdgeTop,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Header with Copy All
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.deep_exif_metadata),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = stringResource(R.string.x_tags_available, filteredTags.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Surface(
                    shape = ShapeFull,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val formattedDump = allTags.joinToString("\n") { "[${it.category}] ${it.tag} = ${it.value}" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.exif_dump), formattedDump))
                        Toast.makeText(context, context.getString(R.string.all_metadata_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_ms_content_copy), contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.copy_all_2),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                placeholder = { Text(stringResource(R.string.filter_metadata_tags), style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_ms_search), contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_ms_close), contentDescription = stringResource(R.string.search_clear), modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = ShapeLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            // Tags List
            if (filteredTags.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_metadata_tags_match_x, searchQuery),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredTags) { tagItem ->
                        Surface(
                            shape = ShapeMedium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText(tagItem.tag, tagItem.value))
                                    Toast.makeText(context, context.getString(R.string.x_copied, tagItem.tag), Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tagItem.tag,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Surface(
                                        shape = ShapeFull,
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ) {
                                        Text(
                                            text = tagItem.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = tagItem.value,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Reusable Component Helpers ────────────────────────────────────────────────

@Composable
private fun DetailKeyValueRow(
    label: String,
    value: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricBadgeBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = ShapeMedium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExposureBadge(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = ShapeMedium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = label.uppercase(),
                    style = TechnicalParamLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = value,
                    style = TechnicalParamValueStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatFullTimestamp(timestamp: Long): String {
    return com.comfort.app.util.LocalFormat.date(timestamp, "yMMMMdjmmss")
}

private fun formatDetailedSize(sizeBytes: Long): String {
    return when {
        sizeBytes < 1024 -> LocalFormat.measure(sizeBytes.toDouble(), MeasureUnit.BYTE, 0)
        sizeBytes < 1024 * 1024 -> LocalFormat.measure(sizeBytes / 1024.0, MeasureUnit.KILOBYTE, 1)
        sizeBytes < 1024 * 1024 * 1024 -> LocalFormat.measure(sizeBytes / (1024.0 * 1024.0), MeasureUnit.MEGABYTE, 2)
        else -> LocalFormat.measure(sizeBytes / (1024.0 * 1024.0 * 1024.0), MeasureUnit.GIGABYTE, 2)
    }
}
