package com.purecam.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import androidx.exifinterface.media.ExifInterface
import com.purecam.util.Exif
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a filter / crop / mirror to a captured JPEG with the same shader as the preview,
 * on an off-screen EGL context. Unfiltered photos never pass through here – their JPEG bytes are
 * written exactly as the camera produced them.
 */
class PhotoProcessor(private val cacheDir: File) {
    private val thread = HandlerThread("PhotoProcessor").apply { start() }
    private val dispatcher = Handler(thread.looper).asCoroutineDispatcher()
    private var egl: EglCore? = null
    private var surface: EGLSurface? = null
    private var program: FilterProgram? = null
    private var maxTex = 4096

    private fun ensureGl(): FilterProgram {
        program?.let { return it }
        val e = EglCore()
        val s = e.createPbuffer(1, 1)
        e.makeCurrent(s)
        val v = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, v, 0)
        maxTex = if (v[0] > 0) v[0] else 4096
        egl = e; surface = s
        return FilterProgram(external = false).also { program = it }
    }

    /**
     * @param cropAspect desired long/short aspect (>= 1) when the sensor had no native size for it.
     * @param mirror flip horizontally as the photo is displayed (front camera "mirror" option).
     */
    suspend fun process(
        jpeg: ByteArray,
        params: FilterParams,
        intensity: Float,
        mirror: Boolean,
        cropAspect: Float?,
        quality: Int,
    ): ByteArray = withContext(dispatcher) {
        val prog = ensureGl()
        val exif = ExifInterface(ByteArrayInputStream(jpeg))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val transposed = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxTex || bounds.outHeight / sample > maxTex ||
            bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_PIXELS
        ) sample *= 2
        val src = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: error("Could not decode photo")
        val sw = src.width
        val sh = src.height

        var outW = sw
        var outH = sh
        if (cropAspect != null) {
            val target = if (sw >= sh) cropAspect else 1f / cropAspect
            if (sw.toFloat() / sh > target) outW = (sh * target).toInt() else outH = (sw / target).toInt()
        }

        val inTex = GlUtil.texture2d(0, 0)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)
        src.recycle()
        val (fbo, outTex) = GlUtil.framebuffer(outW, outH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, outW, outH)
        val args = FilterProgram.DrawArgs(
            cropX = outW.toFloat() / sw,
            cropY = outH.toFloat() / sh,
            // A horizontal mirror of the *displayed* photo is a vertical flip of the stored pixels when rotated.
            flipX = mirror && !transposed,
            flipY = mirror && transposed,
            outW = outW,
            outH = outH,
            texelX = 1f / sw,
            texelY = 1f / sh,
            time = 17f,
            peaking = false,
        )
        prog.draw(inTex, GlUtil.IDENTITY, params, intensity, args)
        GLES20.glFinish()
        val buf = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GlUtil.deleteFramebuffer(fbo, outTex)
        GLES20.glDeleteTextures(1, intArrayOf(inTex), 0)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        buf.position(0)
        out.copyPixelsFromBuffer(buf)
        val bos = ByteArrayOutputStream(outW * outH / 3)
        out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
        out.recycle()
        Exif.copyInto(exif, bos.toByteArray(), outW, outH, cacheDir)
    }

    fun release() {
        Handler(thread.looper).post {
            program?.release()
            surface?.let { egl?.releaseSurface(it) }
            egl?.release()
            thread.quitSafely()
        }
    }

    companion object {
        /** Cap the processing size (≈ 16.8 MP) so filtered 50/200 MP shots don't exhaust memory. */
        private const val MAX_PIXELS = 4096L * 4096L
    }
}
