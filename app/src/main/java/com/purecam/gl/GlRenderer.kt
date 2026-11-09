package com.purecam.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera frames arrive on a SurfaceTexture (OES texture). Each frame is drawn through the filter
 * shader to the viewfinder and to any number of encoder surfaces (video recorder, Live Photo ring buffer).
 */
class GlRenderer {
    private val thread = HandlerThread("GlRenderer").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var pbuffer: EGLSurface
    private lateinit var program: FilterProgram
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputW = 1920
    private var inputH = 1440
    private val texMatrix = FloatArray(16)

    private class Target(val surface: Surface, val egl: EGLSurface, var w: Int, var h: Int, val flipX: Boolean)

    private var display: Target? = null
    private val encoders = LinkedHashMap<String, Target>()
    private val args = FilterProgram.DrawArgs()

    @Volatile var params: FilterParams = FilterParams.NONE
    @Volatile var intensity: Float = 1f
    @Volatile var peaking: Boolean = false
    /** True when the sensor is mounted at 90/270° so portrait content is the buffer transposed. */
    @Volatile var contentRotated: Boolean = true
    @Volatile var lastFrameTimestampNs: Long = 0L
        private set
    @Volatile var histogramListener: ((IntArray) -> Unit)? = null
    /** Histogram read-back stalls the GPU, so it only runs while the histogram is on screen. */
    @Volatile var histogramEnabled: Boolean = false
    /** Colour-profile fallback (only when the camera can't apply a tone curve itself). */
    @Volatile var baseCurve: Float = 0f
    @Volatile var baseSat: Float = 1f
    /** Display rotation in quarter turns (Surface.ROTATION_*), applied to the viewfinder only. */
    @Volatile var displayRotation: Int = 0

    /** When set (filter strip open), every filter is rendered into a strip of small live tiles. */
    @Volatile var thumbnailFilters: List<FilterParams>? = null
    @Volatile var thumbnailListener: ((Bitmap) -> Unit)? = null
    private var thumbFbo = 0
    private var thumbTex = 0
    private var thumbCount = 0
    private var thumbBuf: ByteBuffer? = null
    private val thumbBitmaps = arrayOfNulls<Bitmap>(3)
    private var thumbIdx = 0

    private var frame = 0L

    /**
     * Viewfinder rectangle inside the (full-screen) display surface, top-left origin, in pixels.
     * Animating this instead of resizing the SurfaceView makes aspect / mode changes smooth.
     */
    @Volatile private var viewport: IntArray? = null
    /** Portrait dimensions of the frame currently in the texture (kept until a new-size frame arrives). */
    private var texW = 0
    private var texH = 0
    private val redrawPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var histFbo = 0
    private var histTex = 0
    private val histBuf: ByteBuffer = ByteBuffer.allocateDirect(HIST_W * HIST_H * 4).order(ByteOrder.nativeOrder())

    init {
        handler.post {
            egl = EglCore()
            pbuffer = egl.createPbuffer(1, 1)
            egl.makeCurrent(pbuffer)
            program = FilterProgram(external = true)
            val (f, t) = GlUtil.framebuffer(HIST_W, HIST_H)
            histFbo = f; histTex = t
        }
    }

    private fun <T> runSync(block: () -> T): T? {
        if (Thread.currentThread() === thread) return block()
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, TimeUnit.SECONDS)) Log.w(TAG, "GL call timed out")
        error?.let { throw it }
        return result
    }

    /** Returns the Surface the camera should render into, sized [w]×[h] (landscape sensor dims). */
    fun prepareInput(w: Int, h: Int): Surface = runSync {
        if (surfaceTexture == null) {
            oesTex = GlUtil.oesTexture()
            val st = SurfaceTexture(oesTex)
            st.setOnFrameAvailableListener({ onFrame() }, handler)
            surfaceTexture = st
            inputSurface = Surface(st)
        }
        surfaceTexture!!.setDefaultBufferSize(w, h)
        inputW = w; inputH = h
        inputSurface!!
    }!!

    fun setDisplay(surface: Surface?, w: Int, h: Int) {
        runSync {
            val cur = display
            if (cur != null && surface != null && cur.surface === surface) {
                cur.w = w; cur.h = h
                return@runSync
            }
            if (cur != null) {
                egl.makeCurrent(pbuffer)
                egl.releaseSurface(cur.egl)
                display = null
            }
            if (surface != null && surface.isValid) {
                try {
                    val es = egl.createWindowSurface(surface)
                    display = Target(surface, es, w, h, false)
                    // Never block the GL thread on vsync: keeps encoders and high frame rates flowing.
                    if (egl.makeCurrent(es)) EGL14.eglSwapInterval(egl.display, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "display surface", e)
                }
            }
        }
    }

    /** Moves the viewfinder within the display surface and redraws the last frame immediately. */
    fun setViewport(x: Int, y: Int, w: Int, h: Int) {
        val cur = viewport
        if (cur != null && cur[0] == x && cur[1] == y && cur[2] == w && cur[3] == h) return
        viewport = intArrayOf(x, y, w, h)
        requestRedraw()
    }

    /** Redraw the display from the current texture (no new camera frame needed, e.g. while switching). */
    fun requestRedraw() {
        if (redrawPending.compareAndSet(false, true)) {
            handler.post {
                redrawPending.set(false)
                val d = display ?: return@post
                if (texW == 0 || !egl.makeCurrent(d.egl)) return@post
                drawDisplay(d, withHistogram = false)
                egl.swap(d.egl)
            }
        }
    }

    private fun drawDisplay(d: Target, withHistogram: Boolean) {
        val vp = viewport
        val vx: Int
        val vy: Int
        val vw: Int
        val vh: Int
        if (vp != null && vp[2] > 0 && vp[3] > 0) {
            vx = vp[0]; vy = vp[1]; vw = vp[2]; vh = vp[3]
        } else {
            vx = 0; vy = 0; vw = d.w; vh = d.h
        }
        GLES20.glViewport(0, 0, d.w, d.h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val p = params
        val k = intensity
        draw(vw, vh, texW, texH, false, p, k, peaking, displayRotation, vx, d.h - vy - vh)
        if (withHistogram) {
            val portraitW = if (displayRotation % 2 == 1) vh else vw
            val portraitH = if (displayRotation % 2 == 1) vw else vh
            histogram(portraitW, portraitH, texW, texH, p, k)
        }
    }

    fun addEncoder(key: String, surface: Surface, w: Int, h: Int, flipX: Boolean) {
        runSync {
            encoders.remove(key)?.let { egl.makeCurrent(pbuffer); egl.releaseSurface(it.egl) }
            encoders[key] = Target(surface, egl.createWindowSurface(surface), w, h, flipX)
        }
    }

    fun removeEncoder(key: String) {
        runSync {
            encoders.remove(key)?.let {
                egl.makeCurrent(pbuffer)
                egl.releaseSurface(it.egl)
            }
        }
    }

    fun release() {
        handler.post {
            encoders.values.forEach { egl.releaseSurface(it.egl) }
            encoders.clear()
            display?.let { egl.releaseSurface(it.egl) }
            display = null
            surfaceTexture?.release()
            inputSurface?.release()
            egl.makeCurrent(pbuffer)
            program.release()
            GlUtil.deleteFramebuffer(histFbo, histTex)
            if (thumbFbo != 0) GlUtil.deleteFramebuffer(thumbFbo, thumbTex)
            egl.releaseSurface(pbuffer)
            egl.release()
            thread.quitSafely()
        }
    }

    private fun onFrame() {
        val st = surfaceTexture ?: return
        val d = display
        if (!egl.makeCurrent(d?.egl ?: pbuffer)) egl.makeCurrent(pbuffer)
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage", e)
            return
        }
        st.getTransformMatrix(texMatrix)
        val ts = st.timestamp
        lastFrameTimestampNs = ts
        frame++
        texW = if (contentRotated) inputH else inputW
        texH = if (contentRotated) inputW else inputH
        val cw = texW
        val ch = texH
        val p = params
        val k = intensity

        if (d != null) {
            drawDisplay(d, withHistogram = histogramEnabled && histogramListener != null && frame % 6 == 0L)
            val tf = thumbnailFilters
            if (tf != null && tf.isNotEmpty() && thumbnailListener != null && frame % 6 == 3L) thumbnails(tf, cw, ch)
            egl.swap(d.egl)
        }
        for (t in encoders.values) {
            if (!egl.makeCurrent(t.egl)) continue
            draw(t.w, t.h, cw, ch, t.flipX, p, k, false)
            egl.setPresentationTime(t.egl, ts)
            egl.swap(t.egl)
        }
    }

    private fun draw(
        w: Int, h: Int, cw: Int, ch: Int, flipX: Boolean, p: FilterParams, k: Float, peak: Boolean,
        rot: Int = 0, ox: Int = 0, oy: Int = 0,
    ) {
        GLES20.glViewport(ox, oy, w, h)
        val ca = cw.toFloat() / ch
        // target aspect expressed in (portrait) content axes
        val ta = if (rot % 2 == 1) h.toFloat() / w else w.toFloat() / h
        args.rotation = rot
        args.cropX = if (ta > ca) 1f else ta / ca
        args.cropY = if (ta > ca) ca / ta else 1f
        args.flipX = flipX
        args.flipY = false
        args.outW = w
        args.outH = h
        args.texelX = 1f / cw
        args.texelY = 1f / ch
        args.time = (frame % 997).toFloat()
        args.peaking = peak
        args.baseCurve = baseCurve
        args.baseSat = baseSat
        args.fast = !peak && (k <= 0.001f || p === FilterParams.NONE)
        program.draw(oesTex, texMatrix, p, k, args)
    }

    private fun histogram(w: Int, h: Int, cw: Int, ch: Int, p: FilterParams, k: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFbo)
        // keep display crop so the histogram matches what is framed
        val ca = cw.toFloat() / ch
        val ta = w.toFloat() / h
        GLES20.glViewport(0, 0, HIST_W, HIST_H)
        args.rotation = 0
        args.cropX = if (ta > ca) 1f else ta / ca
        args.cropY = if (ta > ca) ca / ta else 1f
        args.flipX = false; args.flipY = false
        args.outW = HIST_W; args.outH = HIST_H
        args.peaking = false
        program.draw(oesTex, texMatrix, p, k, args)
        histBuf.position(0)
        GLES20.glReadPixels(0, 0, HIST_W, HIST_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, histBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bins = IntArray(64)
        val n = HIST_W * HIST_H
        for (i in 0 until n) {
            val r = histBuf.get(i * 4).toInt() and 0xFF
            val g = histBuf.get(i * 4 + 1).toInt() and 0xFF
            val b = histBuf.get(i * 4 + 2).toInt() and 0xFF
            val l = (r * 54 + g * 183 + b * 19) shr 8
            bins[l shr 2]++
        }
        histogramListener?.invoke(bins)
    }

    /**
     * Renders the current camera frame (with the active filter) to a [w]×[h] portrait bitmap –
     * used for taking stills while recording video.
     */
    fun snapshot(w: Int, h: Int, flipX: Boolean): Bitmap? = runSync {
        if (surfaceTexture == null || lastFrameTimestampNs == 0L) return@runSync null
        egl.makeCurrent(pbuffer)
        val (fbo, tex) = GlUtil.framebuffer(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        val cw = if (contentRotated) inputH else inputW
        val ch = if (contentRotated) inputW else inputH
        val ca = cw.toFloat() / ch
        val ta = w.toFloat() / h
        val a = FilterProgram.DrawArgs(
            cropX = if (ta > ca) 1f else ta / ca,
            cropY = if (ta > ca) ca / ta else 1f,
            flipX = flipX,
            flipY = true,
            outW = w,
            outH = h,
            texelX = 1f / cw,
            texelY = 1f / ch,
            time = 3f,
            baseCurve = baseCurve,
            baseSat = baseSat,
            fast = intensity <= 0.001f || params === FilterParams.NONE,
        )
        program.draw(oesTex, texMatrix, params, intensity, a)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GlUtil.deleteFramebuffer(fbo, tex)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        bmp
    }

    private fun thumbnails(list: List<FilterParams>, cw: Int, ch: Int) {
        val n = list.size
        val w = THUMB * n
        if (n != thumbCount) {
            if (thumbFbo != 0) GlUtil.deleteFramebuffer(thumbFbo, thumbTex)
            val (f, t) = GlUtil.framebuffer(w, THUMB)
            thumbFbo = f; thumbTex = t; thumbCount = n
            thumbBuf = ByteBuffer.allocateDirect(w * THUMB * 4).order(ByteOrder.nativeOrder())
            for (i in thumbBitmaps.indices) thumbBitmaps[i] = null
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, thumbFbo)
        val ca = cw.toFloat() / ch
        args.rotation = 0
        for (i in 0 until n) {
            GLES20.glViewport(i * THUMB, 0, THUMB, THUMB)
            args.cropX = if (1f > ca) 1f else 1f / ca
            args.cropY = if (1f > ca) ca else 1f
            args.flipX = false
            args.flipY = true // glReadPixels returns rows bottom-up
            args.outW = THUMB; args.outH = THUMB
            args.texelX = 1f / cw; args.texelY = 1f / ch
            args.peaking = false
            args.baseCurve = baseCurve
            args.baseSat = baseSat
            args.fast = list[i] === FilterParams.NONE
            program.draw(oesTex, texMatrix, list[i], 1f, args)
        }
        val buf = thumbBuf!!
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, THUMB, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bmp = thumbBitmaps[thumbIdx] ?: Bitmap.createBitmap(w, THUMB, Bitmap.Config.ARGB_8888).also { thumbBitmaps[thumbIdx] = it }
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        thumbIdx = (thumbIdx + 1) % thumbBitmaps.size
        thumbnailListener?.invoke(bmp)
    }

    companion object {
        /** Filter tiles are tiny on screen; 64 px keeps the read-back cheap. */
        const val THUMB = 64
        private const val TAG = "GlRenderer"
        private const val HIST_W = 96
        private const val HIST_H = 96
    }
}
