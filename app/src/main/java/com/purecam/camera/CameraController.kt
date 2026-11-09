package com.purecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.purecam.model.CameraMode
import com.purecam.model.FlashMode
import com.purecam.model.Processing
import com.purecam.model.WbPreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class CameraException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class LiveStats(
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val focusDiopters: Float = 0f,
    val afState: Int = 0,
    val aeState: Int = 0,
)

/** Everything the controller needs from the UI to build a capture request. */
data class ControlState(
    val mode: CameraMode = CameraMode.PHOTO,
    val zoom: Float = 1f,
    val ev: Int = 0,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focusDiopters: Float? = null,
    val wb: WbPreset = WbPreset.AUTO,
    val kelvin: Int = 5500,
    val flash: FlashMode = FlashMode.OFF,
    val aeLock: Boolean = false,
    /** Tap point in normalised viewfinder coordinates (portrait, as displayed). */
    val focusPoint: PointF? = null,
    /** Fraction of the camera image visible in the viewfinder on each axis (centre crop). */
    val viewCropX: Float = 1f,
    val viewCropY: Float = 1f,
    val noiseReduction: Processing = Processing.HIGH_QUALITY,
    val sharpening: Processing = Processing.HIGH_QUALITY,
    val stabilization: Boolean = true,
)

class SessionSpec(
    val mode: CameraMode,
    val previewSize: Size,
    val photo: PhotoSizeOption?,
    /** Used when the full-sensor (maximum resolution) mode can't be configured. */
    val fallbackPhoto: PhotoSizeOption?,
    val rawSize: Size?,
    val fpsRange: Range<Int>?,
    /** > 0: constrained high-speed (slow motion) session at this frame rate. */
    val highSpeedFps: Int = 0,
    /** Encoder surface the camera writes into directly (slow motion). */
    val recorderSurface: Surface? = null,
    /** ImageFormat.JPEG or ImageFormat.HEIC for the still stream. */
    val stillFormat: Int = ImageFormat.JPEG,
)

/** The frames of a night shot, in capture order. */
class NightFrames(val jpegs: List<ByteArray>, val chars: CameraCharacteristics, val jpegOrientation: Int)

class OpenResult(val warning: String?, val rawActive: Boolean, val maxResActive: Boolean, val heicActive: Boolean)

class StillShot(
    /** Encoded still: JPEG, or HEIC when [heic] is true. */
    val jpeg: ByteArray,
    val raw: Image?,
    val result: TotalCaptureResult?,
    val chars: CameraCharacteristics,
    val jpegOrientation: Int,
    val heic: Boolean = false,
)

private class PendingShot(val wantRaw: Boolean) {
    val jpeg = CompletableDeferred<ByteArray>()
    val raw = CompletableDeferred<Image?>()
    val result = CompletableDeferred<TotalCaptureResult>()

    /** Caller gave up waiting; late RAW images must be closed instead of handed over. */
    @Volatile
    var abandoned = false

    init {
        if (!wantRaw) raw.complete(null)
    }

    fun fail(e: Throwable) {
        jpeg.completeExceptionally(e)
        if (!raw.isCompleted) raw.complete(null)
        result.completeExceptionally(e)
    }

    val done get() = jpeg.isCompleted && raw.isCompleted
}

/**
 * Camera2 wrapper. Every Camera2 call happens on one HandlerThread; public suspend functions hop onto it.
 * No vendor "AI" / scene-detection / beauty pipelines are used: the requests only ever set standard
 * Camera2 keys, scene mode is disabled and noise reduction / edge enhancement are user controlled.
 */
class CameraController(context: Context) {
    interface Listener {
        fun onStats(stats: LiveStats)
        fun onShutter()
        fun onError(message: String)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CameraThread").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val executor = Executor { handler.post(it) }

    var listener: Listener? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var lens: Lens? = null
    private var spec: SessionSpec? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var maxResActive = false
    private var heicActive = false
    private var controls = ControlState()
    private val pending = ArrayDeque<PendingShot>()
    private var lastStatsAt = 0L
    private var autoIso = 100
    private var autoExposure = 16_000_000L
    private var autoTransform: ColorSpaceTransform? = null
    private var precaptureWaiter: ((Int?) -> Unit)? = null
    private var hsRecording = false

    // ---------------------------------------------------------------- open / close

    suspend fun open(lens: Lens, spec: SessionSpec, surface: Surface, controls: ControlState): OpenResult =
        withContext(dispatcher) {
            closeInternal()
            hsRecording = false
            this@CameraController.lens = lens
            this@CameraController.spec = spec
            this@CameraController.controls = controls
            previewSurface = surface
            val dev = openDevice(lens.openId)
            device = dev
            var warning: String? = null
            val wantRaw = spec.rawSize != null
            val wantMax = spec.photo?.maxRes == true
            val wantHeic = spec.stillFormat == ImageFormat.HEIC
            val ses = try {
                configure(dev, lens, spec, surface, wantRaw, wantMax, wantHeic)
            } catch (e: Exception) {
                if (!wantRaw && !wantMax && !wantHeic) throw e
                Log.w(TAG, "Session with RAW/max-res/HEIC failed, retrying plain", e)
                if (wantRaw || wantMax) {
                    warning = "This lens can't combine RAW / full-sensor mode with the preview. Using standard JPEG."
                }
                configure(dev, lens, spec, surface, useRaw = false, useMax = false, useHeic = false)
            }
            session = ses
            startRepeating()
            OpenResult(warning, rawReader != null, maxResActive, heicActive)
        }

    suspend fun close() = withContext(dispatcher) { closeInternal() }

    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
    }

    private fun closeInternal() {
        try {
            session?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        closeReaders()
        val err = CameraException("Camera closed")
        pending.forEach { it.fail(err) }
        pending.clear()
        precaptureWaiter = null
    }

    private fun closeReaders() {
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        maxResActive = false
        heicActive = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        val cb = object : CameraDevice.StateCallback() {
            var opened = false
            override fun onOpened(d: CameraDevice) {
                opened = true
                if (cont.isActive) cont.resume(d) else d.close()
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (!opened && cont.isActive) cont.resumeWithException(CameraException("Camera $id disconnected"))
                else if (device === d) {
                    device = null; session = null
                    listener?.onError("Camera was disconnected (another app may be using it)")
                }
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                val msg = when (error) {
                    ERROR_CAMERA_IN_USE -> "Camera $id is in use"
                    ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open"
                    ERROR_CAMERA_DISABLED -> "Camera $id is disabled by the system"
                    ERROR_CAMERA_DEVICE -> "Camera $id device error"
                    ERROR_CAMERA_SERVICE -> "Camera service error"
                    else -> "Camera $id error $error"
                }
                if (!opened && cont.isActive) cont.resumeWithException(CameraException(msg))
                else if (device === d) {
                    device = null; session = null
                    listener?.onError(msg)
                }
            }
        }
        try {
            manager.openCamera(id, cb, handler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(CameraException("Can't open camera $id: ${e.message}", e))
        }
    }

    private suspend fun configure(
        dev: CameraDevice,
        lens: Lens,
        spec: SessionSpec,
        surface: Surface,
        useRaw: Boolean,
        useMax: Boolean,
        useHeic: Boolean = false,
    ): CameraCaptureSession {
        closeReaders()
        val outputs = mutableListOf(outputConfig(surface, lens))
        val highSpeed = spec.highSpeedFps > 0 && spec.recorderSurface != null
        if (highSpeed) outputs += outputConfig(spec.recorderSurface!!, lens)
        if (spec.mode != CameraMode.VIDEO) {
            val photo = if (useMax) spec.photo else (spec.photo?.takeIf { !it.maxRes } ?: spec.fallbackPhoto)
            if (photo != null) {
                val fmt = if (useHeic) ImageFormat.HEIC else ImageFormat.JPEG
                heicActive = useHeic
                val r = ImageReader.newInstance(photo.size.width, photo.size.height, fmt, 3)
                r.setOnImageAvailableListener({ onJpeg(it) }, handler)
                jpegReader = r
                val oc = outputConfig(r.surface, lens)
                if (photo.maxRes && Build.VERSION.SDK_INT >= 31) {
                    oc.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    maxResActive = true
                }
                outputs += oc
            }
            val rawSize = spec.rawSize
            if (useRaw && rawSize != null && !maxResActive) {
                val r = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)
                r.setOnImageAvailableListener({ onRaw(it) }, handler)
                rawReader = r
                outputs += outputConfig(r.surface, lens)
            }
        }
        return suspendCancellableCoroutine { cont ->
            val cfg = SessionConfiguration(
                if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
                outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s) else s.close()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(CameraException("Session configuration failed"))
                    }
                },
            )
            try {
                dev.createCaptureSession(cfg)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    private fun outputConfig(s: Surface, lens: Lens) = OutputConfiguration(s).apply {
        lens.physicalId?.let { setPhysicalCameraId(it) }
    }

    // ---------------------------------------------------------------- repeating preview

    fun update(c: ControlState, refocus: Boolean = false) {
        handler.post {
            controls = c
            if (refocus) triggerFocus() else startRepeating()
        }
    }

    private fun previewBuilder(dev: CameraDevice): CaptureRequest.Builder {
        val tpl = if (spec?.mode == CameraMode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        return dev.createCaptureRequest(tpl).apply {
            addTarget(previewSurface!!)
            applyControls(this, still = false)
        }
    }

    /** Slow motion: route frames to the encoder surface too (or stop doing so). */
    fun setHighSpeedRecording(on: Boolean) {
        handler.post {
            hsRecording = on
            startRepeating()
        }
    }

    private fun startHighSpeedRepeating(s: CameraConstrainedHighSpeedCaptureSession, dev: CameraDevice) {
        val lens = lens ?: return
        val sp = spec ?: return
        val c = controls
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(previewSurface!!)
        if (hsRecording) sp.recorderSurface?.let { b.addTarget(it) }
        val fps = sp.highSpeedFps
        b.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            if (hsRecording) Range(fps, fps) else StreamSpecs.highSpeedPreviewRange(lens, sp.previewSize, fps),
        )
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.ev)
        b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
        val flashAvailable = lens.chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        b.set(
            CaptureRequest.FLASH_MODE,
            if (flashAvailable && c.flash == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )
        val afModes = lens.chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        c.focusPoint?.let { p ->
            val maxAf = lens.chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            if (maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect(lens, p)))
        }
        if (StreamSpecs.usesZoomRatio(lens) && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom)
        } else {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(lens, c.zoom))
        }
        s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), repeatingCallback, handler)
    }

    private fun startRepeating() {
        val s = session ?: return
        val dev = device ?: return
        if (s is CameraConstrainedHighSpeedCaptureSession) {
            try {
                startHighSpeedRepeating(s, dev)
            } catch (e: Exception) {
                Log.w(TAG, "high-speed repeating failed", e)
            }
            return
        }
        try {
            s.setRepeatingRequest(previewBuilder(dev).build(), repeatingCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest failed", e)
        }
    }

    private fun triggerFocus() {
        val s = session ?: return
        val dev = device ?: return
        if (s is CameraConstrainedHighSpeedCaptureSession) {
            startRepeating()
            return
        }
        try {
            val b = previewBuilder(dev)
            if (b.get(CaptureRequest.CONTROL_AF_MODE) == CameraMetadata.CONTROL_AF_MODE_AUTO) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            s.setRepeatingRequest(b.build(), repeatingCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "focus trigger failed", e)
        }
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            if (result.get(CaptureResult.CONTROL_AE_MODE) != CameraMetadata.CONTROL_AE_MODE_OFF) {
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { autoIso = it }
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { autoExposure = it }
            }
            if (result.get(CaptureResult.CONTROL_AWB_MODE) != CameraMetadata.CONTROL_AWB_MODE_OFF) {
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { autoTransform = it }
            }
            precaptureWaiter?.invoke(ae)
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatsAt > 120) {
                lastStatsAt = now
                listener?.onStats(
                    LiveStats(
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
                        focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                        afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: 0,
                        aeState = ae ?: 0,
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- request building

    private fun applyControls(b: CaptureRequest.Builder, still: Boolean) {
        val lens = lens ?: return
        val ch = lens.chars
        val c = controls
        val video = c.mode == CameraMode.VIDEO
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val flashAvailable = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // Zoom
        if (StreamSpecs.usesZoomRatio(lens) && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom)
        } else {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(lens, c.zoom))
        }

        // Exposure
        val manualExposure = manualSensor && (c.iso != null || c.exposureNs != null)
        if (manualExposure) {
            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            var iso = c.iso ?: autoIso
            var exp = c.exposureNs ?: autoExposure
            if (!still && exp > PREVIEW_MAX_EXPOSURE_NS) {
                // Keep the viewfinder fluid for long exposures; simulate brightness with gain.
                val f = exp.toDouble() / PREVIEW_MAX_EXPOSURE_NS
                iso = (iso * f).toInt()
                exp = PREVIEW_MAX_EXPOSURE_NS
            }
            if (isoRange != null) iso = iso.coerceIn(isoRange.lower, isoRange.upper)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, exp)
            val flashMode = when {
                !flashAvailable -> CameraMetadata.FLASH_MODE_OFF
                c.flash == FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                still && (c.flash == FlashMode.ON || c.flash == FlashMode.AUTO) -> CameraMetadata.FLASH_MODE_SINGLE
                else -> CameraMetadata.FLASH_MODE_OFF
            }
            b.set(CaptureRequest.FLASH_MODE, flashMode)
        } else {
            val aeMode = if (!flashAvailable || video) CameraMetadata.CONTROL_AE_MODE_ON else when (c.flash) {
                FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            b.set(
                CaptureRequest.FLASH_MODE,
                if (flashAvailable && c.flash == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.ev)
            b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
            spec?.fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }

        // Focus
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val region = c.focusPoint?.let { meteringRect(lens, it) }
        val maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        when {
            minFocus <= 0f || afModes.none { it != CameraMetadata.CONTROL_AF_MODE_OFF } ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)

            c.focusDiopters != null && manualSensor -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDiopters.coerceIn(0f, minFocus))
            }

            region != null && afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                if (maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }

            else -> {
                val cont = if (video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                val mode = when {
                    afModes.contains(cont) -> cont
                    afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                }
                b.set(CaptureRequest.CONTROL_AF_MODE, mode)
            }
        }
        if (region != null && maxAe > 0 && !manualExposure) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))

        // White balance
        val awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        if (c.wb == WbPreset.KELVIN && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToGains(c.kelvin))
            autoTransform?.let { b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        } else {
            val mode = if (c.wb != WbPreset.KELVIN && awbModes.contains(c.wb.awbMode)) c.wb.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO
            b.set(CaptureRequest.CONTROL_AWB_MODE, mode)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, c.aeLock && mode == CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }

        // Classic ISP processing – user controlled, default OFF for an untouched sensor look.
        val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        pickNr(c.noiseReduction, still, nrModes)?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        val edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        pickEdge(c.sharpening, still, edgeModes)?.let { b.set(CaptureRequest.EDGE_MODE, it) }

        // Photos always use the camera's own high-quality tone mapping. (A custom contrast curve here
        // replaced the sensor-tuned shadow roll-off and made photos noisy; the colour profile is now
        // drawn by the GPU instead.)
        val tmModes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
        if (still && tmModes.contains(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }

        // Stabilisation: OIS is pure optics, always on when present. EIS only for video and only if enabled.
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        if (ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        val eis = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val eisOn = video && c.stabilization && eis.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (eisOn) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
    }

    private fun pickNr(p: Processing, still: Boolean, avail: IntArray): Int? {
        if (avail.isEmpty()) return null
        val off = CameraMetadata.NOISE_REDUCTION_MODE_OFF
        val min = CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        val fast = CameraMetadata.NOISE_REDUCTION_MODE_FAST
        val hq = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        val order = when (p) {
            Processing.OFF -> listOf(off, min, fast)
            Processing.MINIMAL -> listOf(min, off, fast)
            Processing.FAST -> listOf(fast, min, hq)
            Processing.HIGH_QUALITY -> if (still) listOf(hq, fast) else listOf(fast, hq)
        }
        return order.firstOrNull { avail.contains(it) } ?: avail.first()
    }

    private fun pickEdge(p: Processing, still: Boolean, avail: IntArray): Int? {
        if (avail.isEmpty()) return null
        val off = CameraMetadata.EDGE_MODE_OFF
        val fast = CameraMetadata.EDGE_MODE_FAST
        val hq = CameraMetadata.EDGE_MODE_HIGH_QUALITY
        val order = when (p) {
            Processing.OFF -> listOf(off, fast)
            Processing.MINIMAL, Processing.FAST -> listOf(fast, off)
            Processing.HIGH_QUALITY -> if (still) listOf(hq, fast) else listOf(fast, hq)
        }
        return order.firstOrNull { avail.contains(it) } ?: avail.first()
    }

    private fun activeArray(lens: Lens): Rect =
        lens.openChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)

    private fun cropRegion(lens: Lens, zoom: Float): Rect {
        val a = activeArray(lens)
        val z = max(1f, zoom)
        val w = (a.width() / z).toInt()
        val h = (a.height() / z).toInt()
        val l = (a.width() - w) / 2
        val t = (a.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    private fun meteringRect(lens: Lens, p: PointF): MeteringRectangle {
        val c = controls
        val u = 0.5f + (p.x - 0.5f) * c.viewCropX
        val v = 0.5f + (p.y - 0.5f) * c.viewCropY
        val s = viewToSensor(u, v, lens.sensorOrientation, lens.isFront)
        val a = activeArray(lens)
        val base = if (StreamSpecs.usesZoomRatio(lens)) Rect(0, 0, a.width(), a.height()) else cropRegion(lens, c.zoom)
        val cx = base.left + s.x * base.width()
        val cy = base.top + s.y * base.height()
        val half = 0.07f * min(base.width(), base.height())
        val l = (cx - half).toInt().coerceIn(0, a.width() - 2)
        val t = (cy - half).toInt().coerceIn(0, a.height() - 2)
        val r = (cx + half).toInt().coerceIn(l + 1, a.width() - 1)
        val btm = (cy + half).toInt().coerceIn(t + 1, a.height() - 1)
        return MeteringRectangle(l, t, r - l, btm - t, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    // ---------------------------------------------------------------- still capture

    suspend fun capture(jpegOrientation: Int, quality: Int, includeRaw: Boolean, location: Location? = null): StillShot {
        val shot = withContext(dispatcher) {
            val s = session ?: throw CameraException("Camera not ready")
            val dev = device ?: throw CameraException("Camera not ready")
            val jr = jpegReader ?: throw CameraException("Photo output not configured")
            val lens = lens!!
            val c = controls
            val flashAvailable = lens.chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val manualExposure = c.iso != null || c.exposureNs != null
            if (flashAvailable && !manualExposure && (c.flash == FlashMode.AUTO || c.flash == FlashMode.ON)) {
                runPrecapture(s, dev)
            }
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            val raw = rawReader?.takeIf { includeRaw }
            raw?.let { b.addTarget(it.surface) }
            if (!maxResActive) b.addTarget(previewSurface!!)
            applyControls(b, still = true)
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, quality.coerceIn(1, 100).toByte())
            location?.let { b.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
            if (maxResActive && Build.VERSION.SDK_INT >= 31) {
                b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            val p = PendingShot(raw != null)
            pending.addLast(p)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    listener?.onShutter()
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    p.result.complete(result)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    pending.remove(p)
                    p.fail(CameraException("Capture failed (reason ${failure.reason})"))
                }
            }, handler)
            p to lens
        }
        val (p, lens) = shot
        try {
            val jpeg = withTimeout(if (maxResActive) 30_000 else 12_000) { p.jpeg.await() }
            val rawImg = if (p.wantRaw) withTimeoutOrNull(8_000) { p.raw.await() } else null
            if (p.wantRaw && rawImg == null) p.abandoned = true
            val result = withTimeoutOrNull(3_000) { p.result.await() }
            return StillShot(jpeg, rawImg, result, lens.chars, jpegOrientation, heicActive)
        } catch (e: Throwable) {
            p.abandoned = true
            throw e
        }
    }

    /**
     * Night: a burst of identically exposed frames for [com.purecam.media.NightStack] to average.
     * With manual sensor control each frame gets a longer exposure (up to 1/8 s, OIS keeps it sharp)
     * at a lower ISO than the preview uses, and a bit more light in dark scenes.
     */
    suspend fun captureNight(jpegOrientation: Int, location: Location?, onFrame: (done: Int, total: Int) -> Unit): NightFrames {
        val shots = withContext(dispatcher) {
            val s = session ?: throw CameraException("Camera not ready")
            val dev = device ?: throw CameraException("Camera not ready")
            val jr = jpegReader ?: throw CameraException("Photo output not configured")
            if (heicActive) throw CameraException("Night needs the JPEG photo stream")
            val lens = lens!!
            val ch = lens.chars
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val c = controls
            val evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
            // Scene light as metered by the preview's auto exposure (ISO x nanoseconds).
            val metered = autoIso.toDouble() * autoExposure * 2.0.pow((c.ev * evStep).toDouble())
            val dark = metered >= 800.0 * 33_000_000.0 // ISO 800 at 1/30 s or darker
            val frames = if (dark) NIGHT_FRAMES_DARK else NIGHT_FRAMES_DIM
            val target = metered * (if (dark) 1.5 else 1.15)
            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            var iso = 0
            var exp = 0L
            if (manual && isoRange != null && expRange != null) {
                val maxT = min(NIGHT_MAX_FRAME_NS, expRange.upper)
                exp = max(autoExposure, (target / isoRange.lower).toLong()).coerceAtMost(maxT).coerceIn(expRange.lower, expRange.upper)
                iso = (target / exp).toInt().coerceIn(isoRange.lower, isoRange.upper)
            }
            val requests = (0 until frames).map {
                val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(jr.surface)
                if (!maxResActive) b.addTarget(previewSurface!!)
                applyControls(b, still = true)
                if (iso > 0) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, exp)
                } else {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                location?.let { l -> b.set(CaptureRequest.JPEG_GPS_LOCATION, l) }
                b.build()
            }
            val list = requests.map { PendingShot(false) }
            list.forEach { pending.addLast(it) }
            var completed = 0
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (request === requests[0]) listener?.onShutter()
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val i = requests.indexOf(request)
                    if (i >= 0) list[i].result.complete(result)
                    onFrame(++completed, frames)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    val i = requests.indexOf(request)
                    if (i >= 0) {
                        pending.remove(list[i])
                        list[i].fail(CameraException("Night frame failed (reason ${failure.reason})"))
                    }
                    onFrame(++completed, frames)
                }
            }, handler)
            Triple(list, lens, frames)
        }
        val (list, lens, frames) = shots
        val jpegs = ArrayList<ByteArray>()
        withTimeoutOrNull(4_000L + frames * 1_500L) {
            for (p in list) {
                try {
                    jpegs += p.jpeg.await()
                } catch (_: CameraException) {
                }
            }
        }
        list.forEach { if (!it.jpeg.isCompleted) it.abandoned = true }
        if (jpegs.isEmpty()) throw CameraException("Night capture failed")
        return NightFrames(jpegs, lens.chars, jpegOrientation)
    }

    private suspend fun runPrecapture(s: CameraCaptureSession, dev: CameraDevice) {
        val done = CompletableDeferred<Unit>()
        var started = false
        var frames = 0
        precaptureWaiter = { ae ->
            frames++
            if (ae == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) started = true
            val settled = ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                ae == CameraMetadata.CONTROL_AE_STATE_LOCKED
            if ((started && ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) || (!started && frames > 4 && settled)) {
                done.complete(Unit)
            }
        }
        try {
            val b = previewBuilder(dev)
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            s.capture(b.build(), null, handler)
            withTimeoutOrNull(1500) { done.await() }
        } catch (e: Exception) {
            Log.w(TAG, "precapture failed", e)
        } finally {
            precaptureWaiter = null
        }
    }

    private fun onJpeg(reader: ImageReader) {
        val img = try {
            reader.acquireNextImage()
        } catch (_: Exception) {
            null
        } ?: return
        val bytes = try {
            val buf = img.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            img.close()
        }
        val p = pending.firstOrNull { !it.jpeg.isCompleted }
        p?.jpeg?.complete(bytes)
        cleanupPending()
    }

    private fun onRaw(reader: ImageReader) {
        val img = try {
            reader.acquireNextImage()
        } catch (_: Exception) {
            null
        } ?: return
        val p = pending.firstOrNull { it.wantRaw && !it.raw.isCompleted }
        if (p == null || p.abandoned || !p.raw.complete(img)) img.close()
        cleanupPending()
    }

    private fun cleanupPending() {
        while (pending.isNotEmpty() && pending.first().done) pending.removeFirst()
    }

    companion object {
        private const val TAG = "CameraController"
        private const val PREVIEW_MAX_EXPOSURE_NS = 100_000_000L
        private const val NIGHT_MAX_FRAME_NS = 125_000_000L
        private const val NIGHT_FRAMES_DARK = 8
        private const val NIGHT_FRAMES_DIM = 5

        /** Map a tap in portrait viewfinder space to normalised sensor coordinates. */
        fun viewToSensor(u: Float, v: Float, sensorOrientation: Int, front: Boolean): PointF {
            val x = if (front) 1f - u else u
            return when (sensorOrientation) {
                90 -> PointF(v, 1f - x)
                180 -> PointF(1f - x, 1f - v)
                270 -> PointF(1f - v, x)
                else -> PointF(x, v)
            }
        }

        /** Approximate white-balance gains that neutralise a black-body illuminant of [kelvin]. */
        fun kelvinToGains(kelvin: Int): RggbChannelVector {
            val t = kelvin.coerceIn(1500, 15000) / 100.0
            val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
            val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
            val b = when {
                t >= 66 -> 255.0
                t <= 19 -> 1.0
                else -> 138.5177312231 * ln(t - 10) - 305.0447927307
            }
            val rc = r.coerceIn(1.0, 255.0)
            val gc = g.coerceIn(1.0, 255.0)
            val bc = b.coerceIn(1.0, 255.0)
            val rGain = (gc / rc).toFloat().coerceIn(0.25f, 8f)
            val bGain = (gc / bc).toFloat().coerceIn(0.25f, 8f)
            return RggbChannelVector(rGain, 1f, 1f, bGain)
        }
    }
}
