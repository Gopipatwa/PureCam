package com.purecam.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File

object Exif {
    private val TAGS = listOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** Copy camera EXIF from [from] onto a freshly encoded JPEG. */
    fun copyInto(
        from: ExifInterface?,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        cacheDir: File,
        orientationOverride: Int? = null,
    ): ByteArray {
        val tmp = File.createTempFile("exif", ".jpg", cacheDir)
        return try {
            tmp.writeBytes(jpeg)
            val to = ExifInterface(tmp.absolutePath)
            if (from != null) for (t in TAGS) from.getAttribute(t)?.let { to.setAttribute(t, it) }
            orientationOverride?.let { to.setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
            to.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            to.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            to.setAttribute(ExifInterface.TAG_SOFTWARE, "PureCam")
            to.saveAttributes()
            tmp.readBytes()
        } catch (_: Exception) {
            jpeg
        } finally {
            tmp.delete()
        }
    }

    /** Adds orientation, timestamp and optional GPS to a JPEG we encoded ourselves (video snapshots). */
    fun tag(jpeg: ByteArray, degrees: Int, location: android.location.Location?, cacheDir: File): ByteArray {
        val tmp = File.createTempFile("snap", ".jpg", cacheDir)
        return try {
            tmp.writeBytes(jpeg)
            val e = ExifInterface(tmp.absolutePath)
            e.setAttribute(ExifInterface.TAG_ORIENTATION, orientationFromDegrees(degrees).toString())
            val now = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
            e.setAttribute(ExifInterface.TAG_DATETIME, now)
            e.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
            e.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
            e.setAttribute(ExifInterface.TAG_SOFTWARE, "PureCam")
            location?.let { e.setGpsInfo(it) }
            e.saveAttributes()
            tmp.readBytes()
        } catch (_: Exception) {
            jpeg
        } finally {
            tmp.delete()
        }
    }

    fun orientationFromDegrees(deg: Int): Int = when ((deg % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** Small, upright thumbnail from JPEG bytes. */
    fun thumbnail(jpeg: ByteArray, target: Int = 256): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = s })
        val deg = when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (bmp != null && deg != 0) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        } else bmp
    } catch (_: Exception) {
        null
    }
}
