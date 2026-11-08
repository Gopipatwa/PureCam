package com.purecam.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.nio.ByteBuffer

class LiveClip(val bytes: ByteArray, /** Position of the still photo inside the clip. */ val presentationUs: Long)

/**
 * Keeps the last few seconds of the viewfinder H.264-encoded in memory. When the shutter is pressed,
 * [capture] waits for the post-roll and muxes ~1.5 s before + ~1.5 s after into an MP4 – the video
 * half of a Live / Motion Photo.
 */
class LivePhotoRecorder(
    val width: Int,
    val height: Int,
    fps: Int,
    bitrate: Int,
    /** MediaFormat.MIMETYPE_VIDEO_HEVC (much smaller clips) or _AVC. */
    val mime: String,
    private val cacheDir: File,
) {
    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private class Request(val shutterUs: Long, val rotation: Int, val result: CompletableDeferred<LiveClip?>)

    private val thread = HandlerThread("LiveEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec = MediaCodec.createEncoderByType(mime)
    val inputSurface: Surface
    private var format: MediaFormat? = null
    private val samples = ArrayDeque<Sample>()
    private val requests = mutableListOf<Request>()
    private val lock = Any()
    @Volatile private var released = false

    init {
        val f = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buf = c.getOutputBuffer(index)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buf.get(data)
                            add(Sample(data, info.presentationTimeUs, info.flags))
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "output", e)
                }
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "encoder error", e)
            }

            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                format = f
            }
        }, handler)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    private fun add(s: Sample) {
        val ready = mutableListOf<Pair<Request, List<Sample>>>()
        synchronized(lock) {
            samples.addLast(s)
            val keepFrom = s.ptsUs - KEEP_US
            // drop old samples, but never past the oldest still-pending request's pre-roll
            val oldestNeeded = requests.minOfOrNull { it.shutterUs - PRE_US - 1_000_000 } ?: Long.MAX_VALUE
            while (samples.size > 1 && samples.first().ptsUs < minOf(keepFrom, oldestNeeded)) samples.removeFirst()
            val it = requests.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (s.ptsUs >= r.shutterUs + POST_US) {
                    it.remove()
                    ready += r to clipSamples(r.shutterUs)
                }
            }
        }
        for ((r, list) in ready) {
            r.result.complete(runCatching { mux(list, r) }.onFailure { Log.e(TAG, "mux", it) }.getOrNull())
        }
    }

    private fun clipSamples(shutterUs: Long): List<Sample> {
        val list = samples.toList()
        val startTarget = shutterUs - PRE_US
        var startIdx = list.indexOfLast { it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && it.ptsUs <= startTarget }
        if (startIdx < 0) startIdx = list.indexOfFirst { it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
        if (startIdx < 0) return emptyList()
        return list.subList(startIdx, list.size).filter { it.ptsUs <= shutterUs + POST_US }
    }

    private fun mux(list: List<Sample>, r: Request): LiveClip? {
        val fmt = format ?: return null
        if (list.size < 5) return null
        val file = File.createTempFile("live", ".mp4", cacheDir)
        try {
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(r.rotation)
            val track = muxer.addTrack(fmt)
            muxer.start()
            val first = list.first().ptsUs
            val info = MediaCodec.BufferInfo()
            for (s in list) {
                val flags = if (s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, s.data.size, s.ptsUs - first, flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
            muxer.release()
            return LiveClip(file.readBytes(), (r.shutterUs - first).coerceAtLeast(0))
        } finally {
            file.delete()
        }
    }

    /**
     * @param shutterUs timestamp (µs, camera timebase) of the frame on screen when the shutter was pressed.
     * @param rotation clockwise rotation hint for the clip.
     */
    fun capture(shutterUs: Long, rotation: Int): CompletableDeferred<LiveClip?> {
        val d = CompletableDeferred<LiveClip?>()
        if (released) {
            d.complete(null); return d
        }
        synchronized(lock) { requests += Request(shutterUs, rotation, d) }
        return d
    }

    fun release() {
        released = true
        synchronized(lock) {
            requests.forEach { it.result.complete(null) }
            requests.clear()
            samples.clear()
        }
        handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            inputSurface.release()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "LivePhoto"
        const val PRE_US = 1_500_000L
        const val POST_US = 1_500_000L
        private const val KEEP_US = 4_000_000L
    }
}
