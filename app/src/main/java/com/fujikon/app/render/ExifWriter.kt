package com.fujikon.app.render

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copies camera metadata from the source photo onto the exported JPEG. */
object ExifWriter {

    private val TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    fun copy(context: Context, sourceUri: Uri, targetFile: File, width: Int, height: Int) {
        runCatching {
            val source = context.contentResolver.openInputStream(sourceUri)?.use { ExifInterface(it) }
                ?: return
            val target = ExifInterface(targetFile.absolutePath)
            TAGS.forEach { tag ->
                val value = runCatching { source.getAttribute(tag) }.getOrNull()
                if (!value.isNullOrBlank()) {
                    runCatching { target.setAttribute(tag, value) }
                }
            }
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            target.setAttribute(ExifInterface.TAG_SOFTWARE, "FujiKon 1.0")
            target.saveAttributes()
        }
    }

    fun readDateTaken(context: Context, uri: Uri): Long? {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.takeUnless { it.isBlank() }
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            }
        }.getOrNull()?.trim() ?: return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text)?.time
        }.getOrNull()?.takeIf { it > 0L }
    }

    fun formatDate(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
}
