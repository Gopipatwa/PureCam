package com.purecam.media

import android.content.Context
import android.location.Location
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.purecam.model.VideoCodec
import java.io.File

/**
 * MediaRecorder-based video. Normal / time-lapse video takes frames the GL renderer draws (so filters
 * apply); slow motion feeds the camera straight into a persistent input surface at 120/240 fps.
 */
class VideoRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    var paused = false
        private set

    /**
     * @param captureRate frames per second actually captured. Higher than [fps] = slow motion,
     *   lower = time-lapse. Null = normal real-time video.
     * @param inputSurface persistent surface the camera writes into directly (slow motion).
     * @return the surface to draw frames into.
     */
    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        codec: VideoCodec,
        audio: Boolean,
        orientationHint: Int,
        time: Long,
        location: Location? = null,
        captureRate: Double? = null,
        inputSurface: Surface? = null,
    ): Surface {
        val resolver = context.contentResolver
        val u = MediaStoreSaver.createVideo(resolver, MediaStoreSaver.baseName("VID", time) + ".mp4", time)
        uri = u
        val fd = resolver.openFileDescriptor(u, "rw") ?: error("Can't open output")
        pfd = fd
        val r = newRecorder(context)
        val withAudio = audio && captureRate == null
        try {
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncoder(if (codec == VideoCodec.HEVC) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(bitrate)
            r.setVideoFrameRate(fps)
            captureRate?.let { r.setCaptureRate(it) }
            r.setVideoSize(width, height)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(256_000)
                r.setAudioSamplingRate(48_000)
                r.setAudioChannels(2)
            }
            location?.let { r.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
            r.setOrientationHint(orientationHint)
            inputSurface?.let { r.setInputSurface(it) }
            r.prepare()
            val s = inputSurface ?: r.surface
            r.start()
            recorder = r
            paused = false
            return s
        } catch (e: Exception) {
            r.release()
            fd.close()
            MediaStoreSaver.delete(resolver, u)
            pfd = null; uri = null
            throw e
        }
    }

    fun pause() {
        val r = recorder ?: return
        if (!paused) runCatching { r.pause(); paused = true }
    }

    fun resume() {
        val r = recorder ?: return
        if (paused) runCatching { r.resume(); paused = false }
    }

    /** Stops and publishes the file. Returns null when nothing usable was recorded. */
    fun stop(): Uri? {
        val r = recorder ?: return null
        recorder = null
        paused = false
        val u = uri
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            Log.w("VideoRecorder", "stop failed (too short?)", e)
            ok = false
        } finally {
            r.release()
            pfd?.close()
            pfd = null
        }
        if (u == null) return null
        return if (ok) {
            MediaStoreSaver.publish(context.contentResolver, u); u
        } else {
            MediaStoreSaver.delete(context.contentResolver, u); null
        }
    }

    val isRecording: Boolean get() = recorder != null

    companion object {
        @Suppress("DEPRECATION")
        private fun newRecorder(context: Context) = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()

        /**
         * A persistent encoder input surface sized for [width]×[height], usable as a camera output before
         * recording starts (needed for constrained high-speed sessions). A throw-away prepare() gives it its size.
         */
        fun createPersistentSurface(context: Context, width: Int, height: Int): Surface {
            val s = MediaCodec.createPersistentInputSurface()
            val tmp = File(context.cacheDir, "persistent_probe.mp4")
            val r = newRecorder(context)
            try {
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(tmp.absolutePath)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                r.setVideoEncodingBitRate(10_000_000)
                r.setVideoFrameRate(30)
                r.setVideoSize(width, height)
                r.setInputSurface(s)
                r.prepare()
            } finally {
                r.release()
                tmp.delete()
            }
            return s
        }

        fun encoderSupports(mime: String, w: Int, h: Int, fps: Int): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    runCatching {
                        info.getCapabilitiesForType(mime).videoCapabilities.areSizeAndRateSupported(w, h, fps.toDouble())
                    }.getOrDefault(false)
            }

        fun bitrate(w: Int, h: Int, fps: Int, hevc: Boolean, high: Boolean): Int {
            val pixels = w.toLong() * h
            val base = when {
                pixels >= 3840L * 2160 -> if (high) 56_000_000 else 40_000_000
                pixels >= 1920L * 1080 -> if (high) 24_000_000 else 16_000_000
                else -> if (high) 12_000_000 else 8_000_000
            }
            val fpsF = if (fps > 30) 1.5 else 1.0
            val codecF = if (hevc) 0.65 else 1.0
            return (base * fpsF * codecF).toInt()
        }
    }
}
