package com.purecam.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import com.purecam.util.Exif
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * HEIC helpers. When the camera can't write HEIC itself, a JPEG is re-encoded with the phone's
 * hardware HEVC encoder (androidx HeifWriter), keeping the camera's EXIF.
 */
object HeicCodec {
    private const val TAG = "HeicCodec"

    /** HeifWriter needs the whole bitmap in memory; above this we keep the JPEG. */
    const val MAX_PIXELS = 52_000_000L

    /** JPEG → HEIC. Returns null if the image is too large or the encoder fails. */
    fun fromJpeg(jpeg: ByteArray, quality: Int, cacheDir: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null
        val degrees = runCatching {
            ExifInterface(ByteArrayInputStream(jpeg)).rotationDegrees
        }.getOrDefault(0)
        val exifBlock = exifWithNormalOrientation(jpeg, cacheDir)
        val bmp = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return null
        val out = File.createTempFile("heic", ".heic", cacheDir)
        return try {
            val writer = HeifWriter.Builder(out.absolutePath, bmp.width, bmp.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality.coerceIn(1, 100))
                .setRotation(degrees)
                .setGridEnabled(true)
                .setMaxImages(1)
                .setPrimaryIndex(0)
                .build()
            try {
                writer.start()
                writer.addBitmap(bmp)
                exifBlock?.let { writer.addExifData(0, it, 0, it.size) }
                writer.stop(30_000)
            } finally {
                writer.close()
            }
            out.readBytes().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "HEIC encode failed", e)
            null
        } finally {
            bmp.recycle()
            out.delete()
        }
    }

    /**
     * Camera HEIC → upright JPEG, so filters / crops / mirroring can be applied with the JPEG pipeline.
     */
    fun toJpeg(heic: ByteArray, quality: Int, cacheDir: File): ByteArray {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(heic))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val px = info.size.width.toLong() * info.size.height
            if (px > MAX_PIXELS) {
                val f = sqrt(MAX_PIXELS.toDouble() / px)
                decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
        }
        val bos = ByteArrayOutputStream(bmp.width * bmp.height / 3)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        val src = runCatching { ExifInterface(ByteArrayInputStream(heic)) }.getOrNull()
        // ImageDecoder already applied the rotation, so the copy must say "normal".
        return Exif.copyInto(src, bos.toByteArray(), w, h, cacheDir, ExifInterface.ORIENTATION_NORMAL)
    }

    /** The JPEG's EXIF APP1 payload ("Exif\0\0" + TIFF) with orientation reset, for embedding in HEIC. */
    private fun exifWithNormalOrientation(jpeg: ByteArray, cacheDir: File): ByteArray? {
        val tmp = File.createTempFile("exif", ".jpg", cacheDir)
        return try {
            tmp.writeBytes(jpeg)
            val e = ExifInterface(tmp.absolutePath)
            e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            e.setAttribute(ExifInterface.TAG_SOFTWARE, "PureCam")
            e.saveAttributes()
            app1Exif(tmp.readBytes())
        } catch (_: Exception) {
            app1Exif(jpeg)
        } finally {
            tmp.delete()
        }
    }

    private fun app1Exif(jpeg: ByteArray): ByteArray? {
        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            val end = pos + 2 + len
            if (end > jpeg.size) break
            if (marker == 0xE1 && len > 8 &&
                jpeg[pos + 4] == 'E'.code.toByte() && jpeg[pos + 5] == 'x'.code.toByte() &&
                jpeg[pos + 6] == 'i'.code.toByte() && jpeg[pos + 7] == 'f'.code.toByte()
            ) {
                return jpeg.copyOfRange(pos + 4, end)
            }
            pos = end
        }
        return null
    }
}
