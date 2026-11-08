package com.purecam.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes into DCIM/PureCam through MediaStore (no storage permission needed on Android 10+). */
object MediaStoreSaver {
    const val FOLDER = "DCIM/PureCam"

    fun baseName(prefix: String, time: Long): String =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(time))

    private val imagesUri: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videosUri: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun saveImage(resolver: ContentResolver, name: String, mime: String, time: Long, write: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.DATE_TAKEN, time)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(imagesUri, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use(write)
            publish(resolver, uri)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun createVideo(resolver: ContentResolver, name: String, time: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.DATE_TAKEN, time)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(videosUri, values) ?: error("MediaStore insert failed")
    }

    fun saveVideoBytes(resolver: ContentResolver, name: String, bytes: ByteArray, time: Long): Uri {
        val uri = createVideo(resolver, name, time)
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            publish(resolver, uri)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun publish(resolver: ContentResolver, uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun delete(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /** Most recent photo or video this app saved (only our own files are visible without permissions). */
    fun latest(resolver: ContentResolver): Pair<Uri, Boolean>? {
        fun query(base: Uri): Pair<Uri, Long>? = runCatching {
            resolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                arrayOf("$FOLDER%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(base, c.getLong(0)) to c.getLong(1) else null
            }
        }.getOrNull()
        val img = query(imagesUri)
        val vid = query(videosUri)
        return when {
            img == null && vid == null -> null
            vid == null -> img!!.first to false
            img == null -> vid.first to true
            vid.second > img.second -> vid.first to true
            else -> img.first to false
        }
    }
}
