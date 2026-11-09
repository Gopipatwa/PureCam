package com.purecam.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PointF
import android.hardware.camera2.CameraManager
import android.hardware.camera2.DngCreator
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Surface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purecam.camera.CameraCaps
import com.purecam.camera.CameraController
import com.purecam.camera.ControlState
import com.purecam.camera.Lens
import com.purecam.camera.LensDiscovery
import com.purecam.camera.LensKind
import com.purecam.camera.LiveStats
import com.purecam.camera.PhotoSizeOption
import com.purecam.camera.SessionSpec
import com.purecam.camera.StillShot
import com.purecam.camera.StreamSpecs
import com.purecam.gl.Filter
import com.purecam.gl.FilterParams
import com.purecam.gl.Filters
import com.purecam.gl.GlRenderer
import com.purecam.gl.PhotoProcessor
import com.purecam.media.HeicCodec
import com.purecam.media.LiveClip
import com.purecam.media.LivePhotoRecorder
import com.purecam.media.MediaStoreSaver
import com.purecam.media.MotionPhoto
import com.purecam.media.VideoRecorder
import com.purecam.model.AppSettings
import com.purecam.model.AspectRatio
import com.purecam.model.CameraMode
import com.purecam.model.ColorProfile
import com.purecam.model.FlashMode
import com.purecam.model.PhotoFormat
import com.purecam.model.SettingsStore
import com.purecam.model.VideoCodec
import com.purecam.model.VideoKind
import com.purecam.model.VolumeKeyAction
import com.purecam.model.WbPreset
import com.purecam.util.DngCrop
import com.purecam.util.Exif
import com.purecam.util.LocationTracker
import com.purecam.util.OrientationTracker
import com.purecam.util.Orientations
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

data class FocusPoint(val x: Float, val y: Float, val id: Int)

data class LensChip(val label: String, val lens: Lens, val zoom: Float)

data class UiState(
    val mode: CameraMode = CameraMode.PHOTO,
    val lenses: List<Lens> = emptyList(),
    val lens: Lens? = null,
    val settings: AppSettings,
    val caps: CameraCaps = CameraCaps(),
    val zoom: Float = 1f,
    val ev: Int = 0,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focus: Float? = null,
    val wb: WbPreset = WbPreset.AUTO,
    val kelvin: Int = 5500,
    val aeAfLocked: Boolean = false,
    val focusPoint: FocusPoint? = null,
    val photoOptions: List<PhotoSizeOption> = emptyList(),
    val photoSize: PhotoSizeOption? = null,
    val cameraReady: Boolean = false,
    val rawActive: Boolean = false,
    val maxResActive: Boolean = false,
    val recording: Boolean = false,
    val recordingMs: Long = 0,
    val countdown: Int? = null,
    val burstCount: Int = 0,
    val captureFlash: Int = 0,
    val saving: Int = 0,
    val thumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    val message: String? = null,
    val messageId: Int = 0,
    val showSettings: Boolean = false,
    val showFilters: Boolean = false,
    val showBrightness: Boolean = false,
    val proControl: ProControl? = null,
    /** Front-camera "flash": the whole screen turns white at full brightness. */
    val screenFlash: Boolean = false,
    val recordingPaused: Boolean = false,
    val showZoomSlider: Boolean = false,
    /** Display rotation in quarter turns (landscape layouts). */
    val displayRotation: Int = 0,
    val slowMoOptions: Map<Int, Size> = emptyMap(),
    /** > 0 while the camera runs a slow-motion (high-speed) session. */
    val activeHighSpeedFps: Int = 0,
)

enum class ProControl { ISO, SHUTTER, FOCUS, WB, EV }

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = app.getSystemService(CameraManager::class.java)
    private val store = SettingsStore(app)
    val renderer = GlRenderer()
    private val controller = CameraController(app)
    private val processor = PhotoProcessor(app.cacheDir)
    private val videoRecorder = VideoRecorder(app)
    val orientation = OrientationTracker(app)
    private val sound = MediaActionSound()

    private val _ui = MutableStateFlow(UiState(settings = store.load()))
    val ui: StateFlow<UiState> = _ui
    val stats = MutableStateFlow(LiveStats())
    val histogram = MutableStateFlow<IntArray?>(null)

    /** Live preview tiles for the filter strip: one [GlRenderer.THUMB]-wide square per filter. */
    class FilterThumbs(val image: ImageBitmap, val version: Int)
    val filterThumbs = MutableStateFlow<FilterThumbs?>(null)
    private var thumbVersion = 0

    private val sessionMutex = Mutex()
    private var resumed = false
    private var permission = false
    private var lensesLoaded = false
    private var currentSpec: SessionSpec? = null
    private var liveRecorder: LivePhotoRecorder? = null
    private var focusResetJob: Job? = null
    private var countdownJob: Job? = null
    private var recordingJob: Job? = null
    private var burstJob: Job? = null
    @Volatile private var burstActive = false
    private var focusIds = 0

    val location = LocationTracker(app)
    /** HapticFeedbackConstants for the UI to play (automatic lens switches, zoom stops...). */
    val haptics = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    private var slowMoSurface: Surface? = null
    private var zoomSliderJob: Job? = null
    private val blockedLenses = mutableSetOf<String>()
    private var recordStartedAt = 0L
    private var pausedTotalMs = 0L
    private var pausedAt = 0L
    private var heicLimitShown = false
    private var brightnessJob: Job? = null

    init {
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        controller.listener = object : CameraController.Listener {
            override fun onStats(stats: LiveStats) {
                this@CameraViewModel.stats.value = stats
            }

            override fun onShutter() {
                if (shouldPlaySound()) sound.play(MediaActionSound.SHUTTER_CLICK)
                _ui.update { it.copy(captureFlash = it.captureFlash + 1) }
            }

            override fun onError(message: String) = showMessage(message)
        }
        renderer.histogramListener = { histogram.value = it }
        renderer.thumbnailListener = { bmp -> filterThumbs.value = FilterThumbs(bmp.asImageBitmap(), ++thumbVersion) }
        syncRenderer()
        viewModelScope.launch(Dispatchers.IO) { loadLatestThumbnail() }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onPermissionGranted() {
        if (permission) return
        permission = true
        viewModelScope.launch {
            if (!lensesLoaded) {
                val lenses = withContext(Dispatchers.IO) { LensDiscovery.discover(manager) }
                lensesLoaded = true
                if (lenses.isEmpty()) {
                    showMessage("No usable camera found")
                    return@launch
                }
                val main = lenses.firstOrNull { it.kind == LensKind.MAIN } ?: lenses.first()
                _ui.update { it.copy(lenses = lenses, lens = main) }
                refreshLensDerived()
            }
            reopen()
        }
    }

    fun onResume() {
        resumed = true
        viewModelScope.launch(Dispatchers.IO) { loadLatestThumbnail() }
        orientation.start()
        if (_ui.value.settings.geotag) location.start()
        reopen()
    }

    fun onPause() {
        resumed = false
        orientation.stop()
        location.stop()
        burstActive = false
        countdownJob?.cancel()
        _ui.update { it.copy(countdown = null) }
        viewModelScope.launch {
            sessionMutex.withLock {
                if (_ui.value.recording) stopRecordingInternal()
                stopLive()
                controller.close()
                _ui.update { it.copy(cameraReady = false) }
            }
        }
    }

    override fun onCleared() {
        stopLive()
        releaseSlowMoSurface()
        controller.release()
        renderer.release()
        processor.release()
        sound.release()
    }

    // ------------------------------------------------------------------ session management

    private fun reopen() {
        viewModelScope.launch {
            sessionMutex.withLock {
                if (resumed && permission) openCamera()
            }
        }
    }

    private suspend fun openCamera() {
        val st = _ui.value
        val lens = st.lens ?: return
        stopLive()
        controller.close()
        releaseSlowMoSurface()
        _ui.update { it.copy(cameraReady = false, activeHighSpeedFps = 0) }
        var spec = buildSpec(st, lens)
        if (st.mode == CameraMode.VIDEO && st.settings.videoKind == VideoKind.SLOW_MO) {
            val opts = StreamSpecs.highSpeedOptions(lens)
            if (opts.isEmpty()) {
                showMessage("Slow motion isn't offered to apps on this lens - recording normal video")
            } else {
                val fps = if (opts.containsKey(st.settings.slowMoFps)) st.settings.slowMoFps else opts.keys.max()
                val size = opts.getValue(fps)
                try {
                    val surf = withContext(Dispatchers.IO) {
                        VideoRecorder.createPersistentSurface(getApplication<Application>(), size.width, size.height)
                    }
                    slowMoSurface = surf
                    spec = SessionSpec(CameraMode.VIDEO, size, null, null, null, null, highSpeedFps = fps, recorderSurface = surf)
                } catch (e: Exception) {
                    Log.e(TAG, "slow-mo surface", e)
                    showMessage("Slow-motion encoder unavailable: ${e.message}")
                }
            }
        }
        renderer.contentRotated = lens.sensorOrientation % 180 != 0
        try {
            val surface = withContext(Dispatchers.Default) {
                renderer.prepareInput(spec.previewSize.width, spec.previewSize.height)
            }
            currentSpec = spec
            val res = controller.open(lens, spec, surface, controlState(_ui.value))
            _ui.update {
                it.copy(cameraReady = true, rawActive = res.rawActive, maxResActive = res.maxResActive, activeHighSpeedFps = spec.highSpeedFps)
            }
            res.warning?.let { showMessage(it) }
            if (st.mode == CameraMode.PHOTO && st.settings.live) startLive()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            if (spec.highSpeedFps > 0) {
                showMessage("Slow motion couldn't start on this lens - switched to normal video")
                releaseSlowMoSurface()
                val ns = _ui.value.settings.copy(videoKind = VideoKind.NORMAL)
                _ui.update { it.copy(settings = ns) }
                store.save(ns)
                reopen()
                return
            }
            val blocked = lens.hidden || lens.physicalId != null
            showMessage(
                if (blocked) {
                    "The system blocked lens “${lens.label}” (camera ${lens.key}). Xiaomi only lets whitelisted apps open some auxiliary cameras."
                } else {
                    "Couldn't open camera: ${e.message}"
                },
            )
            if (blocked) {
                // Remember it (so pinch-zoom won't hop to it again) and fall back to the main camera.
                blockedLenses += lens.key
                val fallback = st.lenses.firstOrNull { it.isFront == lens.isFront && (it.kind == LensKind.MAIN || it.kind == LensKind.FRONT) }
                if (fallback != null && fallback !== lens) {
                    _ui.update { it.copy(lens = fallback, zoom = 1f) }
                    refreshLensDerived()
                    reopen()
                }
            }
        }
    }

    private fun releaseSlowMoSurface() {
        slowMoSurface?.release()
        slowMoSurface = null
    }

    private fun buildSpec(st: UiState, lens: Lens): SessionSpec {
        val s = st.settings
        return if (st.mode == CameraMode.VIDEO) {
            val size = StreamSpecs.videoSize(lens, s.videoResolution)
            val fps = if (StreamSpecs.maxFps(lens, size) >= s.videoFps) s.videoFps else 30
            SessionSpec(CameraMode.VIDEO, size, null, null, null, StreamSpecs.fpsRange(lens, fps, true))
        } else {
            val photo = effectivePhoto(st)
            val aspect = photo?.size?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
            val preview = StreamSpecs.previewSize(lens, aspect)
            val raw = if (s.raw && st.caps.raw && photo?.maxRes != true) StreamSpecs.rawSize(lens) else null
            val liveOn = st.mode == CameraMode.PHOTO && s.live
            val heic = s.photoFormat == PhotoFormat.HEIC && !liveOn && StreamSpecs.supportsHeic(lens, photo)
            SessionSpec(
                st.mode, preview, photo, st.photoOptions.firstOrNull { !it.maxRes }, raw, StreamSpecs.fpsRange(lens, 30, false),
                stillFormat = if (heic) ImageFormat.HEIC else ImageFormat.JPEG,
            )
        }
    }

    /**
     * The photo size actually captured: with Live on, the sensor's binned ~12 MP output (like other
     * phones' Live/Motion photos); otherwise the user's chosen size (50/200 MP stays for normal shots).
     */
    fun effectivePhoto(st: UiState): PhotoSizeOption? {
        if (st.mode == CameraMode.PHOTO && st.settings.live) {
            val normal = st.photoOptions.filter { !it.maxRes }
            return normal.filter { it.megapixels <= LIVE_MAX_MP }.maxByOrNull { it.megapixels }
                ?: normal.minByOrNull { it.megapixels }
                ?: st.photoSize
        }
        return st.photoSize
    }

    private fun refreshLensDerived() {
        _ui.update { st ->
            val lens = st.lens ?: return@update st
            val caps = CameraCaps.from(lens)
            val opts = StreamSpecs.photoOptions(lens, st.settings.aspect)
            val saved = store.photoSize("${lens.key}|${st.settings.aspect.name}")
            val sel = opts.firstOrNull { it.id == saved } ?: opts.firstOrNull { !it.maxRes } ?: opts.firstOrNull()
            st.copy(
                caps = caps,
                slowMoOptions = StreamSpecs.highSpeedOptions(lens),
                photoOptions = opts,
                photoSize = sel,
                zoom = st.zoom.coerceIn(caps.zoomMin, caps.zoomMax),
                ev = st.ev.coerceIn(caps.evRange.lower, caps.evRange.upper),
            )
        }
    }

    /** Portrait viewfinder aspect, width / height. */
    fun viewfinderAspect(st: UiState): Float =
        if (st.mode == CameraMode.VIDEO) 9f / 16f else 1f / st.settings.aspect.landscape

    private fun controlState(st: UiState): ControlState {
        val pro = st.mode == CameraMode.PRO
        val spec = currentSpec
        val lens = st.lens
        var cropX = 1f
        var cropY = 1f
        if (spec != null && lens != null) {
            val rotated = lens.sensorOrientation % 180 != 0
            val cw = if (rotated) spec.previewSize.height else spec.previewSize.width
            val ch = if (rotated) spec.previewSize.width else spec.previewSize.height
            val ca = cw.toFloat() / ch
            val va = viewfinderAspect(st)
            if (va > ca) cropY = ca / va else cropX = va / ca
        }
        val flash = when {
            st.mode == CameraMode.VIDEO -> if (st.settings.flash == FlashMode.TORCH) FlashMode.TORCH else FlashMode.OFF
            else -> st.settings.flash
        }
        return ControlState(
            mode = st.mode,
            zoom = st.zoom,
            ev = st.ev,
            iso = if (pro) st.iso else null,
            exposureNs = if (pro) st.shutterNs else null,
            focusDiopters = if (pro) st.focus else null,
            wb = if (pro) st.wb else WbPreset.AUTO,
            kelvin = st.kelvin,
            flash = flash,
            aeLock = st.aeAfLocked,
            focusPoint = st.focusPoint?.let { toPortrait(it.x, it.y, st.displayRotation) },
            viewCropX = cropX,
            viewCropY = cropY,
            noiseReduction = st.settings.noiseReduction,
            sharpening = st.settings.sharpening,
            stabilization = st.settings.stabilization,
            toneCurve = if (st.caps.toneCurve) st.settings.colorProfile.curve else 0f,
        )
    }

    /** Viewfinder tap (window coordinates) -> portrait viewfinder coordinates. */
    private fun toPortrait(x: Float, y: Float, quarter: Int): PointF = when (quarter) {
        1 -> PointF(1f - y, x)
        2 -> PointF(1f - x, 1f - y)
        3 -> PointF(y, 1f - x)
        else -> PointF(x, y)
    }

    /** Called by the UI when the activity's display rotation changes (landscape layout). */
    fun setDisplayRotation(quarter: Int) {
        if (_ui.value.displayRotation == quarter) return
        renderer.displayRotation = quarter
        _ui.update { it.copy(displayRotation = quarter, focusPoint = null, aeAfLocked = false) }
        pushControls()
    }

    private fun pushControls(refocus: Boolean = false) {
        controller.update(controlState(_ui.value), refocus)
        syncRenderer()
    }

    /** Lenses without tone-curve support get the colour profile from the GPU instead. */
    private fun profileViaGl(st: UiState) = st.settings.colorProfile != ColorProfile.NATURAL && !st.caps.toneCurve

    private fun syncRenderer() {
        val st = _ui.value
        val f = Filters.byId(st.settings.filterId)
        renderer.params = f.params
        renderer.intensity = if (f.id == Filters.ORIGINAL) 0f else st.settings.filterIntensity
        renderer.peaking = st.mode == CameraMode.PRO && st.focus != null && st.settings.focusPeaking
        renderer.histogramEnabled = st.mode == CameraMode.PRO && st.settings.histogram
        val viaGl = profileViaGl(st)
        renderer.baseCurve = if (viaGl) st.settings.colorProfile.curve else 0f
        renderer.baseSat = if (viaGl) st.settings.colorProfile.saturation else 1f
    }

    // ------------------------------------------------------------------ Live Photo

    private fun startLive() {
        if (liveRecorder != null) return
        val st = _ui.value
        val lens = st.lens ?: return
        val spec = currentSpec ?: return
        val rotated = lens.sensorOrientation % 180 != 0
        val pw = if (rotated) spec.previewSize.height else spec.previewSize.width
        val ph = if (rotated) spec.previewSize.width else spec.previewSize.height
        // Small clips, like an iPhone Live Photo: <=1440 px long side, ~4 Mbit/s, HEVC when available.
        val size = StreamSpecs.clampSize(StreamSpecs.crop(pw, ph, viewfinderAspect(st)), LIVE_MAX_SIDE)
        val cache = getApplication<Application>().cacheDir
        val hevc = android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
        val avc = android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        try {
            val rec = try {
                if (!VideoRecorder.encoderSupports(hevc, size.width, size.height, 30)) error("no HEVC")
                LivePhotoRecorder(size.width, size.height, 30, LIVE_BITRATE_HEVC, hevc, cache)
            } catch (_: Exception) {
                LivePhotoRecorder(size.width, size.height, 30, LIVE_BITRATE_AVC, avc, cache)
            }
            renderer.addEncoder(LIVE_KEY, rec.inputSurface, size.width, size.height, lens.isFront && !st.settings.mirrorFront)
            liveRecorder = rec
        } catch (e: Exception) {
            Log.e(TAG, "live", e)
            showMessage("Live Photo isn't available: ${e.message}")
        }
    }

    private fun stopLive() {
        val r = liveRecorder ?: return
        liveRecorder = null
        renderer.removeEncoder(LIVE_KEY)
        r.release()
    }

    private fun updateLive() {
        viewModelScope.launch {
            sessionMutex.withLock {
                val st = _ui.value
                val want = resumed && st.cameraReady && st.mode == CameraMode.PHOTO && st.settings.live
                if (want) startLive() else stopLive()
            }
        }
    }

    // ------------------------------------------------------------------ user actions: modes, lenses, zoom

    fun setMode(m: CameraMode) {
        val st = _ui.value
        if (st.mode == m || st.recording) return
        countdownJob?.cancel()
        _ui.update { it.copy(mode = m, focusPoint = null, aeAfLocked = false, proControl = null, countdown = null) }
        val liveSizeChanges = st.settings.live && (st.mode == CameraMode.PHOTO || m == CameraMode.PHOTO)
        if (st.mode == CameraMode.VIDEO || m == CameraMode.VIDEO || liveSizeChanges) reopen() else {
            pushControls()
            updateLive()
        }
    }

    fun lensChips(st: UiState): List<LensChip> {
        val front = st.lens?.isFront == true
        val list = st.lenses.filter { it.isFront == front }
        val chips = mutableListOf<LensChip>()
        val main = list.firstOrNull { it.kind == LensKind.MAIN }
        if (main != null && list.none { it.kind == LensKind.ULTRAWIDE }) {
            val (zMin, _) = StreamSpecs.zoomRange(main)
            if (zMin < 0.95f) chips += LensChip(LensDiscovery.zoomLabel(zMin), main, zMin)
        }
        for (l in list) {
            chips += LensChip(l.label, l, 1f)
            if (l === main && list.none { it.kind == LensKind.TELE }) chips += LensChip("2x", l, 2f)
        }
        return chips
    }

    fun activeChip(st: UiState, chips: List<LensChip>): LensChip? =
        chips.filter { it.lens === st.lens && it.zoom <= st.zoom + 0.05f }.maxByOrNull { it.zoom }
            ?: chips.firstOrNull { it.lens === st.lens }

    fun selectChip(chip: LensChip) {
        val st = _ui.value
        if (st.recording) return
        if (chip.lens === st.lens) {
            setZoom(chip.zoom)
            return
        }
        _ui.update {
            it.copy(lens = chip.lens, zoom = chip.zoom, focus = null, focusPoint = null, aeAfLocked = false)
        }
        refreshLensDerived()
        reopen()
    }

    fun switchFacing() {
        val st = _ui.value
        if (st.recording) return
        val front = st.lens?.isFront == true
        val target = st.lenses.firstOrNull { it.isFront != front && (it.kind == LensKind.MAIN || it.kind == LensKind.FRONT) }
            ?: st.lenses.firstOrNull { it.isFront != front } ?: return
        _ui.update { it.copy(lens = target, zoom = 1f, focus = null, focusPoint = null, aeAfLocked = false) }
        refreshLensDerived()
        reopen()
    }

    fun onPinch(factor: Float) {
        setDisplayZoom(displayZoom(_ui.value) * factor)
        showZoomSliderBriefly()
    }

    private fun mainLens(st: UiState) = st.lenses.firstOrNull { it.kind == LensKind.MAIN && !it.isFront }
    private fun ultraWide(st: UiState) =
        st.lenses.filter { it.kind == LensKind.ULTRAWIDE && it.key !in blockedLenses }.minByOrNull { it.relativeZoom }
    private fun relOf(lens: Lens) = if (lens.kind == LensKind.MACRO || lens.kind == LensKind.FRONT) 1f else lens.relativeZoom

    /** Zoom range in "main camera = 1x" units, spanning ultra-wide + main when both are usable. */
    fun displayZoomRange(st: UiState): Pair<Float, Float> {
        val lens = st.lens ?: return 1f to 1f
        val rel = relOf(lens)
        var lo = st.caps.zoomMin * rel
        var hi = st.caps.zoomMax * rel
        val main = mainLens(st)
        val uw = ultraWide(st)
        if (main != null && uw != null && (lens === main || lens === uw) && st.activeHighSpeedFps == 0) {
            lo = min(lo, StreamSpecs.zoomRange(uw).first * uw.relativeZoom)
            hi = max(hi, StreamSpecs.zoomRange(main).second)
        }
        return lo to hi
    }

    /** Sets zoom in "1x = main camera" units, hopping between main and ultra-wide across 1x. */
    fun setDisplayZoom(target: Float) {
        val st = _ui.value
        val lens = st.lens ?: return
        val (lo, hi) = displayZoomRange(st)
        val z = target.coerceIn(lo, hi)
        val before = displayZoom(st)
        val main = mainLens(st)
        val uw = ultraWide(st)
        if (!st.recording && st.cameraReady && main != null && uw != null && st.activeHighSpeedFps == 0) {
            val (mainMin, mainMax) = StreamSpecs.zoomRange(main)
            if (lens === main && z < 0.99f && mainMin >= 0.99f) {
                val (uMin, uMax) = StreamSpecs.zoomRange(uw)
                autoSwitchLens(uw, (z / uw.relativeZoom).coerceIn(uMin, uMax))
                return
            }
            if (lens === uw && z >= 1f) {
                autoSwitchLens(main, z.coerceIn(mainMin, mainMax))
                return
            }
        }
        if (!st.cameraReady && lens === uw && z >= 1f) return // a switch is already in flight
        setZoom(z / relOf(lens))
        for (stop in ZOOM_STOPS) {
            if ((before < stop) != (z < stop)) {
                haptics.tryEmit(HapticFeedbackConstants.CLOCK_TICK)
                break
            }
        }
    }

    private fun autoSwitchLens(target: Lens, zoom: Float) {
        haptics.tryEmit(HapticFeedbackConstants.VIRTUAL_KEY)
        _ui.update { it.copy(lens = target, zoom = zoom, focus = null, focusPoint = null, aeAfLocked = false, cameraReady = false) }
        refreshLensDerived()
        _ui.update { it.copy(zoom = zoom.coerceIn(it.caps.zoomMin, it.caps.zoomMax)) }
        reopen()
    }

    fun toggleZoomSlider() {
        _ui.update { it.copy(showZoomSlider = !it.showZoomSlider) }
        scheduleZoomSliderHide()
    }

    fun showZoomSliderBriefly() {
        if (!_ui.value.showZoomSlider) _ui.update { it.copy(showZoomSlider = true) }
        scheduleZoomSliderHide()
    }

    fun scheduleZoomSliderHide() {
        zoomSliderJob?.cancel()
        zoomSliderJob = viewModelScope.launch {
            delay(3500)
            _ui.update { it.copy(showZoomSlider = false) }
        }
    }

    fun setZoom(z: Float) {
        val caps = _ui.value.caps
        val nz = z.coerceIn(caps.zoomMin, caps.zoomMax)
        if (nz == _ui.value.zoom) return
        _ui.update { it.copy(zoom = nz) }
        pushControls()
    }

    /** Zoom relative to the main camera (what the user thinks of as "1x"). */
    fun displayZoom(st: UiState): Float {
        val lens = st.lens ?: return st.zoom
        val rel = if (lens.kind == LensKind.MACRO) 1f else lens.relativeZoom
        return st.zoom * rel
    }

    // ------------------------------------------------------------------ focus / exposure

    fun onTap(x: Float, y: Float) {
        val st = _ui.value
        if (!st.cameraReady) return
        val evOk = st.caps.evRange.upper > st.caps.evRange.lower && st.mode != CameraMode.PRO
        _ui.update { it.copy(focusPoint = FocusPoint(x, y, ++focusIds), aeAfLocked = false, showBrightness = evOk, showFilters = false) }
        renderer.thumbnailFilters = null
        if (evOk) pokeBrightness()
        pushControls(refocus = true)
        focusResetJob?.cancel()
        focusResetJob = viewModelScope.launch {
            delay(8000)
            if (!_ui.value.aeAfLocked) {
                _ui.update { it.copy(focusPoint = null) }
                pushControls()
            }
        }
    }

    fun onLongPress(x: Float, y: Float) {
        if (!_ui.value.cameraReady) return
        focusResetJob?.cancel()
        _ui.update { it.copy(focusPoint = FocusPoint(x, y, ++focusIds), aeAfLocked = true) }
        pushControls(refocus = true)
    }

    fun clearFocusLock() {
        focusResetJob?.cancel()
        _ui.update { it.copy(focusPoint = null, aeAfLocked = false) }
        pushControls()
    }

    fun setEv(ev: Int) {
        if (_ui.value.showBrightness) pokeBrightness()
        if (ev == _ui.value.ev) return
        _ui.update { it.copy(ev = ev) }
        pushControls()
    }
    fun setIso(iso: Int?) { _ui.update { it.copy(iso = iso) }; pushControls() }
    fun setShutter(ns: Long?) { _ui.update { it.copy(shutterNs = ns) }; pushControls() }
    fun setFocus(d: Float?) { _ui.update { it.copy(focus = d) }; pushControls() }
    fun setWb(wb: WbPreset) { _ui.update { it.copy(wb = wb) }; pushControls() }
    fun setKelvin(k: Int) { _ui.update { it.copy(kelvin = k, wb = WbPreset.KELVIN) }; pushControls() }
    fun selectProControl(c: ProControl?) { _ui.update { it.copy(proControl = if (it.proControl == c) null else c) } }

    fun resetPro() {
        _ui.update { it.copy(iso = null, shutterNs = null, focus = null, wb = WbPreset.AUTO, ev = 0) }
        pushControls()
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(reopenCamera: Boolean = false, refreshSizes: Boolean = false, transform: (AppSettings) -> AppSettings) {
        val before = _ui.value.settings
        val after = transform(before)
        if (after == before) return
        _ui.update { it.copy(settings = after) }
        store.save(after)
        if (refreshSizes) refreshLensDerived()
        if (reopenCamera) reopen() else pushControls()
        if (before.live != after.live || before.mirrorFront != after.mirrorFront) {
            if (!reopenCamera) {
                viewModelScope.launch {
                    sessionMutex.withLock { stopLive() }
                    updateLive()
                }
            }
        }
    }

    fun cycleFlash() {
        val st = _ui.value
        val order = when {
            st.mode == CameraMode.VIDEO -> listOf(FlashMode.OFF, FlashMode.TORCH)
            st.lens?.isFront == true && !st.caps.flash -> listOf(FlashMode.OFF, FlashMode.ON) // screen flash
            else -> listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH)
        }
        val i = order.indexOf(st.settings.flash)
        updateSettings { it.copy(flash = order[(i + 1) % order.size]) }
    }

    fun cycleTimer() {
        val order = listOf(0, 3, 5, 10)
        updateSettings { it.copy(timerSec = order[(order.indexOf(it.timerSec) + 1) % order.size]) }
    }

    fun cycleAspect() {
        val order = AspectRatio.entries
        updateSettings(reopenCamera = true, refreshSizes = true) {
            it.copy(aspect = order[(order.indexOf(it.aspect) + 1) % order.size])
        }
    }

    fun toggleLive() = updateSettings(reopenCamera = true) { it.copy(live = !it.live) }

    fun toggleRaw() {
        if (!_ui.value.caps.raw) {
            showMessage("This lens doesn't offer RAW to apps")
            return
        }
        updateSettings(reopenCamera = true) { it.copy(raw = !it.raw) }
    }

    fun selectPhotoSize(opt: PhotoSizeOption) {
        val st = _ui.value
        val lens = st.lens ?: return
        store.setPhotoSize("${lens.key}|${st.settings.aspect.name}", opt.id)
        _ui.update { it.copy(photoSize = opt) }
        if (opt.maxRes && st.settings.raw) showMessage("RAW is not available in full-sensor mode")
        reopen()
    }

    fun setFilter(id: String) {
        updateSettings { it.copy(filterId = id, filterIntensity = if (it.filterId != id) 1f else it.filterIntensity) }
    }

    fun setFilterIntensity(v: Float) = updateSettings { it.copy(filterIntensity = v) }

    fun toggleFilters() {
        _ui.update { it.copy(showFilters = !it.showFilters, showBrightness = false) }
        val show = _ui.value.showFilters
        renderer.thumbnailFilters = if (show) Filters.all.map { it.params } else null
        if (!show) filterThumbs.value = null
    }

    fun toggleBrightness() {
        val show = !_ui.value.showBrightness
        _ui.update { it.copy(showBrightness = show, showFilters = false) }
        renderer.thumbnailFilters = null
        if (show) pokeBrightness()
    }

    /** Keeps the exposure slider visible while it's being used; it fades out ~2 s after the last touch. */
    fun pokeBrightness() {
        brightnessJob?.cancel()
        brightnessJob = viewModelScope.launch {
            delay(BRIGHTNESS_HIDE_MS)
            _ui.update { it.copy(showBrightness = false) }
        }
    }
    fun showSettings(show: Boolean) = _ui.update { it.copy(showSettings = show) }

    /** Front camera without a flash LED: the screen itself is the flash. */
    fun screenFlashAvailable(st: UiState) = st.lens?.isFront == true && !st.caps.flash && st.mode != CameraMode.VIDEO

    fun setGeotag(on: Boolean) {
        if (on && !location.hasPermission()) {
            showMessage("Location permission is needed to tag photos")
            return
        }
        updateSettings { it.copy(geotag = on) }
        if (on) location.start() else location.stop()
    }

    private fun currentLocation(st: UiState) = if (st.settings.geotag) location.current() else null

    fun setVideoKind(k: VideoKind) {
        val st = _ui.value
        if (st.recording || st.settings.videoKind == k) return
        if (k == VideoKind.SLOW_MO && st.slowMoOptions.isEmpty()) {
            showMessage("This lens doesn't offer slow motion to apps")
            return
        }
        updateSettings(reopenCamera = st.mode == CameraMode.VIDEO) { it.copy(videoKind = k) }
    }

    fun cycleSlowMoFps() {
        val st = _ui.value
        val opts = st.slowMoOptions.keys.sorted()
        if (opts.isEmpty() || st.recording) return
        val i = opts.indexOf(st.settings.slowMoFps)
        updateSettings(reopenCamera = st.mode == CameraMode.VIDEO) { it.copy(slowMoFps = opts[(i + 1) % opts.size]) }
    }

    fun cycleTimelapseSpeed() {
        if (_ui.value.recording) return
        val order = TIMELAPSE_SPEEDS
        updateSettings { it.copy(timelapseSpeed = order[(order.indexOf(it.timelapseSpeed) + 1) % order.size]) }
    }

    fun showMessage(text: String) {
        _ui.update { it.copy(message = text, messageId = it.messageId + 1) }
    }

    fun dismissMessage(id: Int) {
        _ui.update { if (it.messageId == id) it.copy(message = null) else it }
    }

    // ------------------------------------------------------------------ shutter

    fun onShutterTap() {
        val st = _ui.value
        if (!st.cameraReady && !st.recording) return
        when {
            st.mode == CameraMode.VIDEO -> toggleRecording()
            countdownJob?.isActive == true -> {
                countdownJob?.cancel()
                _ui.update { it.copy(countdown = null) }
            }
            st.settings.timerSec > 0 -> {
                countdownJob = viewModelScope.launch {
                    for (i in st.settings.timerSec downTo 1) {
                        _ui.update { it.copy(countdown = i) }
                        delay(1000)
                    }
                    _ui.update { it.copy(countdown = null) }
                    takePicture(burst = false)
                }
            }
            else -> viewModelScope.launch { takePicture(burst = false) }
        }
    }

    fun onShutterLongPress() {
        val st = _ui.value
        if (st.mode == CameraMode.VIDEO || !st.cameraReady || burstActive) return
        burstActive = true
        burstJob = viewModelScope.launch {
            var n = 0
            while (isActive && burstActive && n < MAX_BURST) {
                if (!takePicture(burst = true)) break
                n++
                _ui.update { it.copy(burstCount = n) }
            }
            burstActive = false
            delay(600)
            _ui.update { it.copy(burstCount = 0) }
        }
    }

    fun onShutterRelease() {
        burstActive = false
    }

    private fun shouldPlaySound(): Boolean =
        _ui.value.settings.shutterSound || (Build.VERSION.SDK_INT >= 33 && MediaActionSound.mustPlayShutterSound())

    /** Returns false if the capture failed. */
    private suspend fun takePicture(burst: Boolean): Boolean {
        val st = _ui.value
        val lens = st.lens ?: return false
        val rot = orientation.rotation.value
        val jpegOrientation = Orientations.jpeg(lens.sensorOrientation, rot, lens.isFront)
        val live = liveRecorder.takeIf { !burst && st.mode == CameraMode.PHOTO && st.settings.live }
        val liveDeferred = live?.capture(
            renderer.lastFrameTimestampNs / 1000,
            Orientations.videoHint(rot, lens.isFront, st.settings.mirrorFront),
        )
        val useScreenFlash = !burst && screenFlashAvailable(st) && st.settings.flash != FlashMode.OFF
        val shot = try {
            if (useScreenFlash) {
                _ui.update { it.copy(screenFlash = true) }
                delay(SCREEN_FLASH_SETTLE_MS) // let the display reach full brightness and AE adapt
            }
            controller.capture(jpegOrientation, st.settings.jpegQuality, includeRaw = !burst, location = currentLocation(st))
        } catch (e: Exception) {
            Log.e(TAG, "capture", e)
            showMessage("Capture failed: ${e.message}")
            return false
        } finally {
            if (useScreenFlash) _ui.update { it.copy(screenFlash = false) }
        }
        val filter = Filters.byId(st.settings.filterId)
        _ui.update { it.copy(saving = it.saving + 1) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                savePhoto(shot, st, lens, filter, liveDeferred)
            } catch (e: Exception) {
                Log.e(TAG, "save", e)
                showMessage("Saving failed: ${e.message}")
            } finally {
                runCatching { shot.raw?.close() }
                _ui.update { it.copy(saving = it.saving - 1) }
            }
        }
        return true
    }

    private suspend fun savePhoto(shot: StillShot, st: UiState, lens: Lens, filter: Filter, liveDeferred: Deferred<LiveClip?>?) {
        val resolver = getApplication<Application>().contentResolver
        val s = st.settings
        val time = System.currentTimeMillis()
        val base = MediaStoreSaver.baseName("IMG", time)
        val crop = effectivePhoto(st)?.cropTo?.landscape
        val mirror = lens.isFront && s.mirrorFront
        val filtered = filter.id != Filters.ORIGINAL && s.filterIntensity > 0.01f
        val cache = getApplication<Application>().cacheDir
        var jpeg = shot.jpeg
        var isHeic = shot.heic

        val viaGl = profileViaGl(st)
        if (filtered || crop != null || mirror || viaGl) {
            if (filtered && s.keepOriginal) {
                // The extra unfiltered copy uses the same format as the main photo.
                var orig = shot.jpeg
                var origHeic = shot.heic
                if (s.photoFormat == PhotoFormat.HEIC && !origHeic && liveDeferred == null) {
                    HeicCodec.fromJpeg(orig, s.jpegQuality, cache)?.let { orig = it; origHeic = true }
                }
                val o = orig
                MediaStoreSaver.saveImage(
                    resolver, "${base}_ORIGINAL." + (if (origHeic) "heic" else "jpg"),
                    if (origHeic) "image/heic" else "image/jpeg", time,
                ) { it.write(o) }
            }
            if (shot.heic) jpeg = HeicCodec.toJpeg(jpeg, 100, cache)
            isHeic = false
            jpeg = processor.process(
                jpeg,
                if (filtered) filter.params else FilterParams.NONE,
                if (filtered) s.filterIntensity else 0f,
                mirror,
                crop,
                s.jpegQuality,
                baseCurve = if (viaGl) s.colorProfile.curve else 0f,
                baseSat = if (viaGl) s.colorProfile.saturation else 1f,
            )
        }

        var name = "$base.jpg"
        if (liveDeferred != null) {
            val clip = withTimeoutOrNull(8000) { liveDeferred.await() }
            if (clip != null) {
                jpeg = MotionPhoto.build(jpeg, clip.bytes, clip.presentationUs)
                name = "$base.MP.jpg"
                if (s.liveSaveVideo) MediaStoreSaver.saveVideoBytes(resolver, "${base}_LIVE.mp4", clip.bytes, time)
            } else {
                showMessage("Live clip wasn't ready – saved a still photo")
            }
        } else if (s.photoFormat == PhotoFormat.HEIC && !isHeic) {
            val heic = HeicCodec.fromJpeg(jpeg, s.jpegQuality, cache)
            if (heic != null) {
                jpeg = heic
                isHeic = true
            } else if (!heicLimitShown) {
                heicLimitShown = true
                showMessage("HEIC is limited to about 50 MP on this phone - this one was saved as JPEG")
            }
        }
        if (isHeic) name = "$base.heic"
        val finalJpeg = jpeg
        val uri = MediaStoreSaver.saveImage(resolver, name, if (isHeic) "image/heic" else "image/jpeg", time) { it.write(finalJpeg) }

        val raw = shot.raw
        val result = shot.result
        if (raw != null && result != null) {
            try {
                val bos = java.io.ByteArrayOutputStream(raw.width * raw.height * 2 + 65536)
                DngCreator(shot.chars, result).use { dng ->
                    dng.setOrientation(Exif.orientationFromDegrees(shot.jpegOrientation))
                    currentLocation(st)?.let { dng.setLocation(it) }
                    dng.writeImage(bos, raw)
                }
                // RAW streams ignore zoom: frame the DNG like the JPEG (2x, 16:9, 1:1...).
                val out = effectivePhoto(st)?.outSize
                val aspect = if (out != null) maxOf(out.width, out.height).toFloat() / minOf(out.width, out.height) else 4f / 3f
                val dngBytes = DngCrop.apply(bos.toByteArray(), st.zoom, aspect)
                MediaStoreSaver.saveImage(resolver, "$base.dng", "image/x-adobe-dng", time) { it.write(dngBytes) }
            } catch (e: Exception) {
                Log.e(TAG, "dng", e)
                showMessage("RAW (DNG) save failed: ${e.message}")
            }
        }

        val thumb = if (isHeic) {
            runCatching { resolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
        } else {
            Exif.thumbnail(finalJpeg)
        }
        _ui.update { it.copy(thumbnail = thumb ?: it.thumbnail, lastUri = uri, lastIsVideo = false) }
    }

    // ------------------------------------------------------------------ video

    private fun toggleRecording() {
        viewModelScope.launch {
            sessionMutex.withLock {
                if (_ui.value.recording) stopRecordingInternal() else startRecordingInternal()
            }
        }
    }

    private suspend fun startRecordingInternal() {
        val st = _ui.value
        val lens = st.lens ?: return
        val spec = currentSpec ?: return
        if (spec.mode != CameraMode.VIDEO || !st.cameraReady) return
        val loc = currentLocation(st)
        val now = System.currentTimeMillis()
        val slowSurface = slowMoSurface
        if (spec.highSpeedFps > 0 && slowSurface != null) {
            // Slow motion: the camera writes straight into the encoder; timestamps are stretched to 30 fps.
            val w = spec.previewSize.width
            val h = spec.previewSize.height
            val hint = Orientations.jpeg(lens.sensorOrientation, orientation.rotation.value, lens.isFront)
            try {
                withContext(Dispatchers.IO) {
                    videoRecorder.start(
                        w, h, 30, VideoRecorder.bitrate(w, h, 30, false, st.settings.videoHighBitrate), VideoCodec.H264,
                        false, hint, now, loc, captureRate = spec.highSpeedFps.toDouble(), inputSurface = slowSurface,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "slow-mo record", e)
                showMessage("Couldn't start slow motion: ${e.message}")
                return
            }
            controller.setHighSpeedRecording(true)
            onRecordingStarted()
            return
        }
        val rotated = lens.sensorOrientation % 180 != 0
        val w = StreamSpecs.minEven(if (rotated) spec.previewSize.height else spec.previewSize.width, 2)
        val h = StreamSpecs.minEven(if (rotated) spec.previewSize.width else spec.previewSize.height, 2)
        val s = st.settings
        val timelapse = s.videoKind == VideoKind.TIMELAPSE
        val fps = if (timelapse) 30 else spec.fpsRange?.upper ?: 30
        val codec = when {
            s.videoCodec == VideoCodec.HEVC && VideoRecorder.encoderSupports(VideoCodec.HEVC.mime, w, h, fps) -> VideoCodec.HEVC
            VideoRecorder.encoderSupports(VideoCodec.H264.mime, w, h, fps) -> VideoCodec.H264
            else -> {
                showMessage("The video encoder can't do ${h}p at $fps fps – pick a lower resolution")
                return
            }
        }
        val bitrate = VideoRecorder.bitrate(w, h, fps, codec == VideoCodec.HEVC, s.videoHighBitrate)
        val hint = Orientations.videoHint(orientation.rotation.value, lens.isFront, s.mirrorFront)
        val audio = !timelapse && s.recordAudio && ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val surface = try {
            withContext(Dispatchers.IO) {
                videoRecorder.start(
                    w, h, fps, bitrate, codec, audio, hint, now, loc,
                    captureRate = if (timelapse) 30.0 / s.timelapseSpeed else null,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "record", e)
            showMessage("Couldn't start recording: ${e.message}")
            return
        }
        renderer.addEncoder(VIDEO_KEY, surface, w, h, lens.isFront && !s.mirrorFront)
        onRecordingStarted()
    }

    private fun onRecordingStarted() {
        if (shouldPlaySound()) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        _ui.update { it.copy(recording = true, recordingPaused = false, recordingMs = 0) }
        recordStartedAt = SystemClock.elapsedRealtime()
        pausedTotalMs = 0
        recordingJob = viewModelScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val pausedNow = if (_ui.value.recordingPaused) now - pausedAt else 0L
                _ui.update { it.copy(recordingMs = now - recordStartedAt - pausedTotalMs - pausedNow) }
                delay(250)
            }
        }
    }

    fun togglePause() {
        viewModelScope.launch {
            sessionMutex.withLock {
                val st = _ui.value
                if (!st.recording) return@withLock
                if (st.recordingPaused) {
                    withContext(Dispatchers.IO) { videoRecorder.resume() }
                    pausedTotalMs += SystemClock.elapsedRealtime() - pausedAt
                    _ui.update { it.copy(recordingPaused = false) }
                } else {
                    withContext(Dispatchers.IO) { videoRecorder.pause() }
                    pausedAt = SystemClock.elapsedRealtime()
                    _ui.update { it.copy(recordingPaused = true) }
                }
            }
        }
    }

    /** Still photo while recording: grabs the current (filtered) frame at video resolution. */
    fun takeVideoSnapshot() {
        viewModelScope.launch {
            val st = _ui.value
            val lens = st.lens ?: return@launch
            val spec = currentSpec ?: return@launch
            val rotated = lens.sensorOrientation % 180 != 0
            val w = StreamSpecs.minEven(if (rotated) spec.previewSize.height else spec.previewSize.width, 2)
            val h = StreamSpecs.minEven(if (rotated) spec.previewSize.width else spec.previewSize.height, 2)
            val mirror = st.settings.mirrorFront
            val hint = Orientations.videoHint(orientation.rotation.value, lens.isFront, mirror)
            if (shouldPlaySound()) sound.play(MediaActionSound.SHUTTER_CLICK)
            _ui.update { it.copy(captureFlash = it.captureFlash + 1, saving = it.saving + 1) }
            try {
                val bmp = withContext(Dispatchers.Default) { renderer.snapshot(w, h, lens.isFront && !mirror) }
                if (bmp == null) {
                    showMessage("No frame to capture yet")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val bos = java.io.ByteArrayOutputStream(w * h / 3)
                    bmp.compress(Bitmap.CompressFormat.JPEG, st.settings.jpegQuality, bos)
                    bmp.recycle()
                    val app = getApplication<Application>()
                    val jpeg = Exif.tag(bos.toByteArray(), hint, currentLocation(st), app.cacheDir)
                    val time = System.currentTimeMillis()
                    MediaStoreSaver.saveImage(app.contentResolver, MediaStoreSaver.baseName("IMG", time) + ".jpg", "image/jpeg", time) {
                        it.write(jpeg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "snapshot", e)
                showMessage("Couldn't save the still: ${e.message}")
            } finally {
                _ui.update { it.copy(saving = it.saving - 1) }
            }
        }
    }

    private suspend fun stopRecordingInternal() {
        recordingJob?.cancel()
        if ((currentSpec?.highSpeedFps ?: 0) > 0) {
            controller.setHighSpeedRecording(false)
            delay(150) // let in-flight high-speed bursts drain before the encoder goes away
        } else {
            renderer.removeEncoder(VIDEO_KEY)
        }
        val uri = withContext(Dispatchers.IO) { videoRecorder.stop() }
        if (shouldPlaySound()) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        _ui.update { it.copy(recording = false, recordingPaused = false, recordingMs = 0) }
        if (uri != null) {
            val thumb = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.loadThumbnail(uri, Size(256, 256), null)
                }.getOrNull()
            }
            _ui.update { it.copy(thumbnail = thumb ?: it.thumbnail, lastUri = uri, lastIsVideo = true) }
        } else {
            showMessage("Video was too short to save")
        }
    }

    // ------------------------------------------------------------------ gallery / keys

    /**
     * Re-reads the newest photo/video from MediaStore, so a capture deleted in the gallery doesn't
     * leave a stale thumbnail behind.
     */
    private fun loadLatestThumbnail() {
        val resolver = getApplication<Application>().contentResolver
        val latest = MediaStoreSaver.latest(resolver)
        if (latest == null) {
            if (_ui.value.saving == 0) _ui.update { it.copy(thumbnail = null, lastUri = null) }
            return
        }
        if (latest.first == _ui.value.lastUri && _ui.value.thumbnail != null) return
        val thumb = runCatching { resolver.loadThumbnail(latest.first, Size(256, 256), null) }.getOrNull()
        _ui.update { it.copy(thumbnail = thumb, lastUri = latest.first, lastIsVideo = latest.second) }
    }

    private fun uriExists(uri: Uri): Boolean = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    fun galleryIntent(): Intent? {
        val st = _ui.value
        val uri = st.lastUri ?: return null
        if (!uriExists(uri)) {
            viewModelScope.launch(Dispatchers.IO) { loadLatestThumbnail() }
            showMessage("That item was deleted")
            return null
        }
        val mime = if (st.lastIsVideo) "video/*" else "image/*"
        return Intent("com.android.camera.action.REVIEW").apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun fallbackGalleryIntent(): Intent? {
        val st = _ui.value
        val uri = st.lastUri ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (st.lastIsVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Volume / camera hardware keys. Returns true when consumed. */
    fun onKeyDown(keyCode: Int, repeat: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (repeat == 0) onShutterTap()
            return true
        }
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        return when (_ui.value.settings.volumeKeys) {
            VolumeKeyAction.OFF -> false
            VolumeKeyAction.SHUTTER -> {
                if (repeat == 0) onShutterTap()
                true
            }
            VolumeKeyAction.ZOOM -> {
                onPinch(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.1f else 1f / 1.1f)
                true
            }
        }
    }

    fun consumesKeyUp(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_CAMERA ||
            ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
                _ui.value.settings.volumeKeys != VolumeKeyAction.OFF)

    companion object {
        private const val TAG = "PureCam"
        private const val LIVE_KEY = "live"
        private const val VIDEO_KEY = "video"
        private const val MAX_BURST = 50
        private const val SCREEN_FLASH_SETTLE_MS = 650L
        private const val BRIGHTNESS_HIDE_MS = 2200L
        private const val LIVE_MAX_SIDE = 1440
        private const val LIVE_BITRATE_HEVC = 4_000_000
        private const val LIVE_BITRATE_AVC = 6_000_000
        /** Live Photos use the sensor's binned (~12 MP) output, like other phones do. */
        private const val LIVE_MAX_MP = 12.6f
        private val ZOOM_STOPS = listOf(1f, 2f, 5f, 10f)
        val TIMELAPSE_SPEEDS = listOf(5, 10, 30, 60, 120)
    }
}
