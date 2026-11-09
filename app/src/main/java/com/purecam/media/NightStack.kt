package com.purecam.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.purecam.util.Exif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Night mode merge: plain multi-frame averaging, no AI.
 *
 * 1. Pick the sharpest frame as the reference.
 * 2. Align every other frame to it with a whole-image shift (coarse-to-fine block matching).
 * 3. Average the aligned frames. Pixels that differ too much from the reference (something moved)
 *    are left out, so moving people / cars don't ghost.
 *
 * Averaging N frames cuts random sensor noise by about √N while keeping real detail.
 */
object NightStack {
    /** Weight of a fully accepted pixel; partially accepted pixels get less. */
    private const val FULL = 4

    private class Small(val luma: IntArray, val w: Int, val h: Int)

    suspend fun merge(jpegs: List<ByteArray>, quality: Int, cacheDir: File, onProgress: (Float) -> Unit): ByteArray =
        withContext(Dispatchers.Default) {
            require(jpegs.isNotEmpty())
            if (jpegs.size == 1) return@withContext jpegs[0]

            // --- 1. small previews for alignment + sharpness
            val small = jpegs.map { smallLuma(it) }
            val refIdx = small.indices.maxByOrNull { sharpness(small[it]) } ?: 0
            val ref = small[refIdx]
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegs[refIdx], 0, jpegs[refIdx].size, bounds)
            var sample = 1
            while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
            val full = BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val refBmp = BitmapFactory.decodeByteArray(jpegs[refIdx], 0, jpegs[refIdx].size, full)
                ?: error("Could not decode night frame")
            val w = refBmp.width
            val h = refBmp.height
            val scale = w.toFloat() / ref.w // small -> full
            onProgress(0.1f)

            // --- accumulators, seeded with the reference
            val n = w * h
            val sumR = ShortArray(n)
            val sumG = ShortArray(n)
            val sumB = ShortArray(n)
            val wt = ByteArray(n)
            bands(h) { y0, y1 ->
                val px = IntArray(w * (y1 - y0))
                refBmp.getPixels(px, 0, w, 0, y0, w, y1 - y0)
                for (i in px.indices) {
                    val c = px[i]
                    val o = y0 * w + i
                    sumR[o] = (((c shr 16) and 0xFF) * FULL).toShort()
                    sumG[o] = (((c shr 8) and 0xFF) * FULL).toShort()
                    sumB[o] = ((c and 0xFF) * FULL).toShort()
                    wt[o] = FULL.toByte()
                }
            }

            // --- 2 + 3. align and accumulate every other frame
            var cur: Bitmap? = null
            val others = jpegs.indices.filter { it != refIdx }
            for ((k, idx) in others.withIndex()) {
                val sm = small[idx]
                // coarse: 1/4 of the small preview, wide search
                val r4 = downsample(ref)
                val c4 = downsample(sm)
                val (cx, cy) = bestShift(r4.luma, c4.luma, r4.w, r4.h, 0, 0, 8, 1)
                // fine: the small preview itself
                val (sx, sy) = bestShift(ref.luma, sm.luma, ref.w, ref.h, cx * 4, cy * 4, 4, 2)

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    cur?.let { inBitmap = it }
                }
                val bmp = runCatching { BitmapFactory.decodeByteArray(jpegs[idx], 0, jpegs[idx].size, opts) }.getOrNull()
                    ?: continue
                cur = bmp
                if (bmp.width != w || bmp.height != h) continue

                // finest: full resolution on a centre patch
                val (dx, dy, mad) = refineFull(refBmp, bmp, (sx * scale).toInt(), (sy * scale).toInt(), (scale / 2 + 2).toInt().coerceIn(2, 8))
                // Accept differences up to ~2.5x the typical frame-to-frame noise.
                val t = (mad * 2.5f).coerceIn(10f, 48f).toInt()
                accumulate(refBmp, bmp, dx, dy, t, sumR, sumG, sumB, wt)
                onProgress(0.1f + 0.8f * (k + 1) / others.size)
            }
            cur?.recycle()

            // --- write the average back into the reference bitmap
            bands(h) { y0, y1 ->
                val px = IntArray(w * (y1 - y0))
                for (i in px.indices) {
                    val o = y0 * w + i
                    val d = wt[o].toInt().coerceAtLeast(1)
                    val half = d / 2
                    val r = (sumR[o] + half) / d
                    val g = (sumG[o] + half) / d
                    val b = (sumB[o] + half) / d
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                synchronized(refBmp) { refBmp.setPixels(px, 0, w, 0, y0, w, y1 - y0) }
            }
            val bos = ByteArrayOutputStream(w * h / 3)
            refBmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
            refBmp.recycle()
            onProgress(1f)
            val exif = runCatching { ExifInterface(ByteArrayInputStream(jpegs[refIdx])) }.getOrNull()
            Exif.copyInto(exif, bos.toByteArray(), w, h, cacheDir)
        }

    /** Night photos stay at the binned ~12 MP size; anything bigger is merged at a reduced size. */
    private const val MAX_PIXELS = 26_000_000L

    // ------------------------------------------------------------------ helpers

    private suspend fun bands(h: Int, block: (Int, Int) -> Unit) = coroutineScope {
        val step = 128
        val parts = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val ranges = (0 until h step step).map { it to min(h, it + step) }
        ranges.chunked((ranges.size + parts - 1) / parts).map { chunk ->
            async(Dispatchers.Default) { chunk.forEach { (a, b) -> block(a, b) } }
        }.awaitAll()
    }

    /** About 1/8 size green-weighted luma, decoded cheaply by the JPEG decoder itself. */
    private fun smallLuma(jpeg: ByteArray): Small {
        val bmp = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = 8
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("Could not decode night frame")
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Small(IntArray(w * h) { luma(px[it]) }, w, h)
    }

    private fun luma(c: Int) = (((c shr 16) and 0xFF) + 2 * ((c shr 8) and 0xFF) + (c and 0xFF)) shr 2

    private fun downsample(s: Small): Small {
        val w = s.w / 4
        val h = s.h / 4
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (j in 0 until 4) {
                val row = (y * 4 + j) * s.w + x * 4
                sum += s.luma[row] + s.luma[row + 1] + s.luma[row + 2] + s.luma[row + 3]
            }
            out[y * w + x] = sum shr 4
        }
        return Small(out, w, h)
    }

    private fun sharpness(s: Small): Long {
        var sum = 0L
        for (y in 1 until s.h) {
            val row = y * s.w
            for (x in 1 until s.w) {
                val v = s.luma[row + x]
                sum += abs(v - s.luma[row + x - 1]) + abs(v - s.luma[row - s.w + x])
            }
        }
        return sum
    }

    /** Shift (dx, dy) of [cur] relative to [ref] around (cx, cy) with the lowest mean absolute difference. */
    private fun bestShift(ref: IntArray, cur: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int, step: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        val m = abs(cx) + abs(cy) + radius + 1
        if (w <= 2 * m + 8 || h <= 2 * m + 8) return cx to cy
        for (dy in cy - radius..cy + radius) for (dx in cx - radius..cx + radius) {
            var sad = 0L
            var y = m
            while (y < h - m) {
                val rr = y * w
                val cr = (y + dy) * w + dx
                var x = m
                while (x < w - m) {
                    sad += abs(ref[rr + x] - cur[cr + x])
                    x += step
                }
                y += step
            }
            if (sad < best) {
                best = sad; bx = dx; by = dy
            }
        }
        return bx to by
    }

    private data class Fine(val dx: Int, val dy: Int, val mad: Float)

    /** Full-resolution refinement on a centre patch; also returns the typical residual difference (noise). */
    private fun refineFull(ref: Bitmap, cur: Bitmap, cx: Int, cy: Int, radius: Int): Fine {
        val w = ref.width
        val h = ref.height
        val p = min(768, min(w, h) / 2)
        val m = radius + max(abs(cx), abs(cy))
        val x0 = ((w - p) / 2).coerceIn(m, max(m, w - p - m))
        val y0 = ((h - p) / 2).coerceIn(m, max(m, h - p - m))
        if (x0 + p + m > w || y0 + p + m > h) return Fine(cx, cy, 16f)
        val rp = IntArray(p * p)
        ref.getPixels(rp, 0, p, x0, y0, p, p)
        val q = p + 2 * m
        val cp = IntArray(q * q)
        cur.getPixels(cp, 0, q, x0 - m, y0 - m, q, q)
        val rl = IntArray(rp.size) { luma(rp[it]) }
        val cl = IntArray(cp.size) { luma(cp[it]) }
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - radius..cy + radius) for (dx in cx - radius..cx + radius) {
            var sad = 0L
            for (y in 0 until p step 2) {
                val rr = y * p
                val cr = (y + dy + m) * q + dx + m
                for (x in 0 until p step 2) sad += abs(rl[rr + x] - cl[cr + x])
            }
            if (sad < best) {
                best = sad; bx = dx; by = dy
            }
        }
        val samples = ((p + 1) / 2).toLong() * ((p + 1) / 2)
        return Fine(bx, by, best.toFloat() / samples)
    }

    private suspend fun accumulate(
        ref: Bitmap, cur: Bitmap, dx: Int, dy: Int, t: Int,
        sumR: ShortArray, sumG: ShortArray, sumB: ShortArray, wt: ByteArray,
    ) {
        val w = ref.width
        val h = ref.height
        val x0 = max(0, -dx)
        val x1 = min(w, w - dx)
        if (x1 <= x0) return
        bands(h) { y0, y1 ->
            val cy0 = max(0, y0 + dy)
            val cy1 = min(h, y1 + dy)
            if (cy1 <= cy0) return@bands
            val rp = IntArray(w * (y1 - y0))
            ref.getPixels(rp, 0, w, 0, y0, w, y1 - y0)
            val cp = IntArray(w * (cy1 - cy0))
            cur.getPixels(cp, 0, w, 0, cy0, w, cy1 - cy0)
            for (y in y0 until y1) {
                val yy = y + dy
                if (yy < cy0 || yy >= cy1) continue
                val rr = (y - y0) * w
                val cr = (yy - cy0) * w + dx
                val out = y * w
                for (x in x0 until x1) {
                    val c = cp[cr + x]
                    val d = abs(luma(c) - luma(rp[rr + x]))
                    val k = when {
                        d <= t -> FULL
                        d >= 2 * t -> 0
                        else -> FULL * (2 * t - d) / t
                    }
                    if (k == 0) continue
                    val o = out + x
                    sumR[o] = (sumR[o] + ((c shr 16) and 0xFF) * k).toShort()
                    sumG[o] = (sumG[o] + ((c shr 8) and 0xFF) * k).toShort()
                    sumB[o] = (sumB[o] + (c and 0xFF) * k).toShort()
                    wt[o] = (wt[o] + k).toByte()
                }
            }
        }
    }
}
