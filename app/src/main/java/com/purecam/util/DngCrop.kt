package com.purecam.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Android's RAW stream always contains the whole sensor, even when the preview / JPEG is zoomed.
 * DNG has a "DefaultCrop" (tags 50719/50720) that every RAW editor applies on open, so we rewrite it
 * to the same field of view and aspect ratio as the JPEG. The pixels outside stay in the file.
 */
object DngCrop {
    private const val TAG_DEFAULT_CROP_ORIGIN = 50719
    private const val TAG_DEFAULT_CROP_SIZE = 50720
    private const val TAG_SUB_IFDS = 330

    /**
     * @param zoom zoom applied to the JPEG on this lens (>= 1 to crop).
     * @param aspect landscape width / height of the JPEG (e.g. 1.333, 1.778, 1.0).
     */
    fun apply(dng: ByteArray, zoom: Float, aspect: Float): ByteArray {
        try {
            val bb = ByteBuffer.wrap(dng)
            bb.order(
                when {
                    dng[0] == 'I'.code.toByte() && dng[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                    dng[0] == 'M'.code.toByte() && dng[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                    else -> return dng
                },
            )
            val queue = ArrayDeque<Int>()
            queue.add(bb.getInt(4))
            val seen = HashSet<Int>()
            while (queue.isNotEmpty()) {
                val ifd = queue.removeFirst()
                if (ifd <= 0 || ifd + 2 > dng.size || !seen.add(ifd)) continue
                val count = bb.getShort(ifd).toInt() and 0xFFFF
                var origin: Entry? = null
                var size: Entry? = null
                for (i in 0 until count) {
                    val e = ifd + 2 + i * 12
                    if (e + 12 > dng.size) break
                    val tag = bb.getShort(e).toInt() and 0xFFFF
                    when (tag) {
                        TAG_DEFAULT_CROP_ORIGIN -> origin = Entry.read(bb, e)
                        TAG_DEFAULT_CROP_SIZE -> size = Entry.read(bb, e)
                        TAG_SUB_IFDS -> Entry.read(bb, e)?.let { sub ->
                            for (k in 0 until sub.count) queue.add(sub.get(bb, k).toInt())
                        }
                    }
                }
                if (origin != null && size != null && origin.count >= 2 && size.count >= 2) {
                    val ox = origin.get(bb, 0); val oy = origin.get(bb, 1)
                    val sw = size.get(bb, 0); val sh = size.get(bb, 1)
                    val z = zoom.coerceAtLeast(1f).toDouble()
                    var cw = sw / z
                    var ch = sh / z
                    if (cw / ch > aspect) cw = ch * aspect else ch = cw / aspect
                    val nw = (cw / 2).roundToInt() * 2
                    val nh = (ch / 2).roundToInt() * 2
                    val nx = ox + ((sw - nw) / 2).roundToInt()
                    val ny = oy + ((sh - nh) / 2).roundToInt()
                    origin.set(bb, 0, nx.toDouble()); origin.set(bb, 1, ny.toDouble())
                    size.set(bb, 0, nw.toDouble()); size.set(bb, 1, nh.toDouble())
                }
                val next = ifd + 2 + count * 12
                if (next + 4 <= dng.size) queue.add(bb.getInt(next))
            }
        } catch (_: Exception) {
            // leave the DNG untouched if anything looks unexpected
        }
        return dng
    }

    private class Entry(val type: Int, val count: Int, val dataOffset: Int) {
        private val unit = when (type) { 3 -> 2; 4 -> 4; 5 -> 8; else -> 0 }

        fun get(bb: ByteBuffer, i: Int): Double = when (type) {
            3 -> (bb.getShort(dataOffset + i * 2).toInt() and 0xFFFF).toDouble()
            4 -> (bb.getInt(dataOffset + i * 4).toLong() and 0xFFFFFFFFL).toDouble()
            5 -> {
                val n = bb.getInt(dataOffset + i * 8).toLong() and 0xFFFFFFFFL
                val d = bb.getInt(dataOffset + i * 8 + 4).toLong() and 0xFFFFFFFFL
                if (d == 0L) 0.0 else n.toDouble() / d
            }
            else -> 0.0
        }

        fun set(bb: ByteBuffer, i: Int, v: Double) {
            when (type) {
                3 -> bb.putShort(dataOffset + i * 2, v.roundToInt().toShort())
                4 -> bb.putInt(dataOffset + i * 4, v.roundToInt())
                5 -> {
                    bb.putInt(dataOffset + i * 8, v.roundToInt())
                    bb.putInt(dataOffset + i * 8 + 4, 1)
                }
            }
        }

        companion object {
            fun read(bb: ByteBuffer, e: Int): Entry? {
                val type = bb.getShort(e + 2).toInt() and 0xFFFF
                val count = bb.getInt(e + 4)
                val unit = when (type) { 3 -> 2; 4 -> 4; 5 -> 8; else -> return null }
                val total = unit * count
                val offset = if (total <= 4) e + 8 else bb.getInt(e + 8)
                if (offset < 0 || offset + total > bb.capacity()) return null
                return Entry(type, count, offset)
            }
        }
    }
}
