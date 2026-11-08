package com.purecam.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import com.purecam.model.AspectRatio
import com.purecam.model.VideoResolution
import java.util.Locale
import kotlin.math.abs

/** A selectable photo resolution. [cropTo] is set when the sensor has no native size for the aspect. */
data class PhotoSizeOption(val size: Size, val maxRes: Boolean, val cropTo: AspectRatio?) {
    val outSize: Size
        get() {
            val crop = cropTo ?: return size
            val w = size.width; val h = size.height
            val a = crop.landscape
            return if (w.toFloat() / h > a) Size((h * a).toInt(), h) else Size(w, (w / a).toInt())
        }
    val megapixels: Float get() = outSize.width * outSize.height / 1_000_000f
    val id: String get() = "${size.width}x${size.height}" + (if (maxRes) "M" else "") + (cropTo?.name ?: "")
    val label: String
        get() = String.format(Locale.US, "%.1f MP · %d×%d", megapixels, outSize.width, outSize.height) +
            (if (maxRes) "  (full sensor)" else "") + (if (cropTo != null) "  (cropped)" else "")
}

data class CameraCaps(
    val isoRange: Range<Int>? = null,
    val exposureRange: Range<Long>? = null,
    val minFocusDiopters: Float = 0f,
    val evRange: Range<Int> = Range(0, 0),
    val evStep: Float = 1f,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val flash: Boolean = false,
    val raw: Boolean = false,
    val manualSensor: Boolean = false,
    val manualWb: Boolean = false,
    val maxRes: Boolean = false,
    val hardwareLevel: String = "",
) {
    companion object {
        fun from(lens: Lens): CameraCaps {
            val ch = lens.chars
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val (zMin, zMax) = StreamSpecs.zoomRange(lens)
            val level = when (lens.openChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            return CameraCaps(
                isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
                zoomMin = zMin,
                zoomMax = zMax,
                flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && StreamSpecs.rawSize(lens) != null,
                manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                manualWb = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
                maxRes = StreamSpecs.supportsMaxRes(lens),
                hardwareLevel = level,
            )
        }
    }
}

object StreamSpecs {
    private fun map(lens: Lens): StreamConfigurationMap? =
        lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

    private fun maxResMap(lens: Lens): StreamConfigurationMap? =
        if (Build.VERSION.SDK_INT >= 31) lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) else null

    fun supportsMaxRes(lens: Lens): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val caps = lens.chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) &&
            maxResMap(lens)?.getOutputSizes(ImageFormat.JPEG)?.isNotEmpty() == true
    }

    private fun matches(s: Size, aspect: Float) = abs(s.width.toFloat() / s.height - aspect) < 0.02f
    private fun area(s: Size) = s.width.toLong() * s.height

    fun photoOptions(lens: Lens, aspect: AspectRatio): List<PhotoSizeOption> {
        val normal = map(lens)?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val largestNormal = normal.maxOfOrNull { area(it) } ?: 0L
        val max = if (supportsMaxRes(lens)) {
            maxResMap(lens)?.getOutputSizes(ImageFormat.JPEG)?.filter { area(it) > largestNormal }.orEmpty()
        } else emptyList()

        fun pick(list: List<Size>, maxRes: Boolean): List<PhotoSizeOption> {
            val exact = list.filter { matches(it, aspect.landscape) }
            if (exact.isNotEmpty()) return exact.map { PhotoSizeOption(it, maxRes, null) }
            return list.filter { matches(it, 4f / 3f) }.map { PhotoSizeOption(it, maxRes, aspect) }
        }

        return (pick(normal, false) + pick(max, true))
            .filter { it.megapixels >= 0.9f }
            .distinctBy { it.id }
            .sortedByDescending { area(it.size) }
            .take(10)
    }

    fun previewSize(lens: Lens, aspect: Float, maxW: Int = 1920, maxH: Int = 1440): Size {
        val sizes = map(lens)?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        return sizes.filter { it.width <= maxW && it.height <= maxH && matches(it, aspect) }.maxByOrNull { area(it) }
            ?: sizes.filter { it.width <= maxW && it.height <= maxH }
                .minByOrNull { abs(it.width.toFloat() / it.height - aspect) * 10 - area(it) / 1e7f }
            ?: Size(1440, 1080)
    }

    fun videoSize(lens: Lens, res: VideoResolution): Size {
        val sizes = map(lens)?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        sizes.firstOrNull { it.width == res.width && it.height == res.height }?.let { return it }
        return sizes.filter { matches(it, 16f / 9f) && it.width <= res.width }.maxByOrNull { area(it) }
            ?: Size(1920, 1080)
    }

    /** Highest frame rate this stream size can run at. */
    fun maxFps(lens: Lens, size: Size): Int {
        val d = runCatching { map(lens)?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) }.getOrNull() ?: 0L
        return if (d > 0) (1_000_000_000.0 / d + 0.5).toInt() else 30
    }

    /** Slow-motion frame rates (≥120) this lens offers, mapped to the best size (≤1080p) for each. */
    fun highSpeedOptions(lens: Lens): Map<Int, Size> {
        val caps = lens.chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return emptyMap()
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) return emptyMap()
        val m = map(lens) ?: return emptyMap()
        val out = sortedMapOf<Int, Size>()
        val sizes = runCatching { m.highSpeedVideoSizes.toList() }.getOrDefault(emptyList())
        for (s in sizes) {
            if (s.width > 1920 || s.height > 1080) continue
            val ranges = runCatching { m.getHighSpeedVideoFpsRangesFor(s).toList() }.getOrDefault(emptyList())
            for (r in ranges) {
                if (r.upper < 120 || r.lower != r.upper) continue
                val cur = out[r.upper]
                if (cur == null || area(s) > area(cur)) out[r.upper] = s
            }
        }
        return out
    }

    fun highSpeedPreviewRange(lens: Lens, size: Size, fps: Int): Range<Int> {
        val ranges = runCatching { map(lens)?.getHighSpeedVideoFpsRangesFor(size)?.toList() }.getOrNull().orEmpty()
        return ranges.filter { it.upper == fps && it.lower < fps }.minByOrNull { it.lower } ?: Range(fps, fps)
    }

    /** True when the camera can write HEIC itself at this photo size. */
    fun supportsHeic(lens: Lens, opt: PhotoSizeOption?): Boolean {
        if (opt == null) return false
        val m = if (opt.maxRes) maxResMap(lens) else map(lens)
        return runCatching { m?.getOutputSizes(ImageFormat.HEIC)?.contains(opt.size) == true }.getOrDefault(false)
    }

    fun rawSize(lens: Lens): Size? =
        map(lens)?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { area(it) }

    fun fpsRange(lens: Lens, fps: Int, video: Boolean): Range<Int>? {
        val ranges = lens.openChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return if (video) {
            ranges.firstOrNull { it.lower == fps && it.upper == fps }
                ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
                ?: ranges.filter { it.upper <= fps }.maxByOrNull { it.upper * 100 + it.lower }
        } else {
            ranges.filter { it.upper == 30 }.minByOrNull { it.lower }
        }
    }

    fun zoomRange(lens: Lens): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            lens.openChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it.lower to it.upper }
        }
        val max = lens.openChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to maxOf(1f, max)
    }

    fun usesZoomRatio(lens: Lens): Boolean =
        Build.VERSION.SDK_INT >= 30 && lens.openChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null

    fun minEven(v: Int, mult: Int = 16) = maxOf(mult, v / mult * mult)

    fun crop(w: Int, h: Int, aspectWOverH: Float): Size =
        if (w.toFloat() / h > aspectWOverH) Size(minEven((h * aspectWOverH).toInt()), minEven(h))
        else Size(minEven(w), minEven((w / aspectWOverH).toInt()))

    fun clampSize(s: Size, maxSide: Int): Size {
        val m = maxOf(s.width, s.height)
        if (m <= maxSide) return s
        val f = maxSide.toFloat() / m
        return Size(minEven((s.width * f).toInt()), minEven((s.height * f).toInt()))
    }
}
