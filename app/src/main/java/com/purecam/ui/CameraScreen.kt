@file:OptIn(ExperimentalMaterial3Api::class)

package com.purecam.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.display.DisplayManager
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MotionPhotosOff
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.purecam.camera.LensKind
import com.purecam.camera.LiveStats
import com.purecam.gl.Filters
import com.purecam.gl.GlRenderer
import com.purecam.model.CameraMode
import com.purecam.model.FlashMode
import com.purecam.model.GridType
import com.purecam.model.VideoKind
import com.purecam.model.VideoResolution
import com.purecam.util.OrientationTracker
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun CameraApp(vm: CameraViewModel) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.onPermissionGranted()
    }
    LaunchedEffect(Unit) {
        if (granted) vm.onPermissionGranted()
        else launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    if (granted) {
        CameraScreen(vm)
    } else {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("PureCam needs camera access", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                    Text("Grant permission")
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun View.haptic(type: Int) {
    performHapticFeedback(type)
}

/** Display rotation in quarter turns, tracked with a DisplayListener (90°↔270° flips fire no config change). */
@Composable
private fun rememberDisplayQuarter(): Int {
    val view = LocalView.current
    val ctx = LocalContext.current
    var q by remember { mutableIntStateOf(view.display?.rotation ?: 0) }
    DisposableEffect(view) {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                view.display?.rotation?.let { q = it }
            }
        }
        dm?.registerDisplayListener(listener, view.handler)
        view.display?.rotation?.let { q = it }
        onDispose { dm?.unregisterDisplayListener(listener) }
    }
    return q
}

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val hold by vm.orientation.rotation.collectAsStateWithLifecycle()
    val quarter = rememberDisplayQuarter()
    val landscape = quarter % 2 == 1
    // icons stay upright relative to how the phone is held, accounting for the display's own rotation
    val rot = rememberIconRotation((hold + 90 * quarter) % 360)
    val s = ui.settings
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    val ctx = LocalContext.current

    LaunchedEffect(quarter) { vm.setDisplayRotation(quarter) }
    LaunchedEffect(Unit) { vm.haptics.collect { view.haptic(it) } }
    LaunchedEffect(ui.messageId) {
        val msg = ui.message ?: return@LaunchedEffect
        val id = ui.messageId
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(msg, withDismissAction = true)
        vm.dismissMessage(id)
    }
    // Front "flash": full white screen at maximum brightness while the selfie is taken.
    DisposableEffect(ui.screenFlash) {
        val w = ctx.findActivity()?.window
        if (ui.screenFlash && w != null) {
            val lp = w.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            w.attributes = lp
        }
        onDispose {
            if (w != null) {
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    // The frame glides between aspect ratios / modes instead of jumping.
    val portraitAspect by animateFloatAsState(
        vm.viewfinderAspect(ui),
        tween(380, easing = FastOutSlowInEasing),
        label = "viewfinderAspect",
    )
    val switchingDim by animateFloatAsState(if (ui.cameraReady) 0f else 0.45f, tween(220), label = "switchDim")
    val surfaceOrigin = remember { FloatArray(2) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // One full-screen camera surface that never resizes; the GL renderer draws the frame into the
        // animated viewfinder rectangle below.
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { c ->
                val o = c.positionInWindow()
                surfaceOrigin[0] = o.x
                surfaceOrigin[1] = o.y
            },
        ) {
            CameraSurface(vm.renderer)
        }
        // Viewfinder: always centred.
        Box(
            (
                if (landscape) {
                    Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(1f / portraitAspect)
                } else {
                    Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(portraitAspect)
                }
                ).onGloballyPositioned { c ->
                val o = c.positionInWindow()
                vm.renderer.setViewport(
                    (o.x - surfaceOrigin[0]).roundToInt(),
                    (o.y - surfaceOrigin[1]).roundToInt(),
                    c.size.width,
                    c.size.height,
                )
            },
        ) {
            Viewfinder(vm, ui, rot, quarter)
            // While a lens / mode switch is in progress the last frame stays, gently dimmed.
            if (switchingDim > 0.01f) Box(Modifier.fillMaxSize().background(CamColors.Scrim.copy(alpha = switchingDim)))
        }

        if (landscape) {
            LandscapeChrome(vm, ui, rot)
        } else {
            PortraitChrome(vm, ui, rot)
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.Center).padding(16.dp))

        if (ui.screenFlash) Box(Modifier.fillMaxSize().background(Color.White))
        if (ui.showSettings) SettingsDialog(vm, ui)
    }
}

@Composable
private fun Viewfinder(vm: CameraViewModel, ui: UiState, rot: Float, quarter: Int) {
    val s = ui.settings
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o -> vm.onTap(o.x / size.width, o.y / size.height) },
                        onLongPress = { o -> vm.onLongPress(o.x / size.width, o.y / size.height) },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) vm.onPinch(zoom) }
                },
        )
        GridOverlay(s.grid)
        if (s.level) LevelOverlay(vm.orientation, quarter)
        ui.focusPoint?.let { FocusRing(it, ui.aeAfLocked, vm.stats) }
        AnimatedVisibility(
            ui.showBrightness && ui.mode != CameraMode.PRO && ui.caps.evRange.upper > ui.caps.evRange.lower,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(350)),
        ) {
            ExposureSlider(vm, ui)
        }
        if (ui.mode == CameraMode.PRO && s.histogram) {
            Histogram(vm.histogram, Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        if (vm.screenFlashAvailable(ui) && s.flash != FlashMode.OFF) {
            // ring-light hint that the screen flash is armed
            Box(Modifier.fillMaxSize().border(6.dp, Color.White.copy(alpha = 0.85f)))
        }
        CaptureFlash(ui.captureFlash)
        Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.recording) {
                StatusBadge(
                    (if (ui.recordingPaused) "❚❚ " else "● ") + Fmt.duration(ui.recordingMs),
                    if (ui.recordingPaused) CamColors.OnScrim else CamColors.Record,
                )
            }
            if (ui.mode == CameraMode.VIDEO && ui.activeHighSpeedFps > 0) StatusBadge("SLOW-MO ${ui.activeHighSpeedFps}", CamColors.Accent)
            if (ui.mode == CameraMode.VIDEO && s.videoKind == VideoKind.TIMELAPSE) StatusBadge("TIME-LAPSE ${s.timelapseSpeed}x", CamColors.Accent)
            if (ui.aeAfLocked) StatusBadge("AE/AF LOCK", CamColors.Accent, onClick = vm::clearFocusLock)
            if (ui.burstCount > 0) StatusBadge("Burst ${ui.burstCount}", CamColors.Accent)
            if (s.live && ui.mode == CameraMode.PHOTO) StatusBadge("LIVE", CamColors.Accent)
            if (ui.maxResActive && ui.mode != CameraMode.VIDEO) StatusBadge("FULL SENSOR", CamColors.OnScrim)
        }
        ui.countdown?.let {
            Text(
                "$it",
                Modifier.align(Alignment.Center).graphicsLayer { rotationZ = rot },
                color = CamColors.OnScrim,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

// ---------------------------------------------------------------------------- layouts

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PortraitChrome(vm: CameraViewModel, ui: UiState, rot: Float) {
    // keep the status bar readable over bright scenes
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(CamColors.Scrim.copy(alpha = 0.6f), Color.Transparent)))
            .statusBarsPadding()
            .height(12.dp),
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, CamColors.Scrim.copy(alpha = 0.7f))))
            .navigationBarsPadding()
            .padding(bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PanelArea(vm, ui, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        AnimatedVisibility(ui.showZoomSlider, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ZoomSlider(vm, ui, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarItems(vm, ui, rot)
        }
        LensChips(vm, ui, rot, vertical = false)
        AnimatedVisibility(ui.mode == CameraMode.VIDEO && !ui.recording, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            VideoKindChips(vm, ui, vertical = false)
        }
        ModeSelector(vm, ui, vertical = false)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShutterSideStart(vm, ui, rot)
            ShutterButton(vm, ui)
            ShutterSideEnd(vm, ui, rot)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.LandscapeChrome(vm: CameraViewModel, ui: UiState, rot: Float) {
    // Left rail: quick toggles.
    Column(
        Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(CamColors.Scrim.copy(alpha = 0.7f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBarItems(vm, ui, rot)
    }
    // Right rail: lenses, modes and the shutter column.
    Row(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, CamColors.Scrim.copy(alpha = 0.7f))))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LensChips(vm, ui, rot, vertical = true)
        }
        Column(
            Modifier.padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (ui.mode == CameraMode.VIDEO && !ui.recording) VideoKindChips(vm, ui, vertical = true)
            ModeSelector(vm, ui, vertical = true)
        }
        Column(
            Modifier.fillMaxHeight().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShutterSideEnd(vm, ui, rot)
            ShutterButton(vm, ui)
            ShutterSideStart(vm, ui, rot)
        }
    }
    // Panels float at the bottom centre, over the image.
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .widthIn(max = 560.dp)
            .fillMaxWidth(0.62f)
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(ui.showZoomSlider, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ZoomSlider(vm, ui, Modifier.fillMaxWidth())
        }
        PanelArea(vm, ui, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PanelArea(vm: CameraViewModel, ui: UiState, modifier: Modifier) {
    val panel = when {
        ui.showFilters -> 1
        ui.mode == CameraMode.PRO -> 2
        else -> 0
    }
    // remember the last panel so it can animate out
    val last = remember { IntArray(1) }
    if (panel != 0) last[0] = panel
    AnimatedVisibility(
        visible = panel != 0,
        enter = fadeIn(tween(180)) + expandVertically(tween(220)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
    ) {
        Surface(
            modifier,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Crossfade(last[0], label = "panel") { kind ->
                Box(Modifier.padding(vertical = 8.dp)) {
                    when (kind) {
                        1 -> FilterStrip(vm, ui)
                        else -> ProPanel(vm, ui)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- overlays

/** Slim vertical exposure slider with a sun thumb, like other phone cameras. Drag up = brighter. */
@Composable
private fun ExposureSlider(vm: CameraViewModel, ui: UiState) {
    val r = ui.caps.evRange
    val lo = r.lower.toFloat()
    val hi = r.upper.toFloat()
    val span = (hi - lo).coerceAtLeast(1f)
    val view = LocalView.current
    val accent = MaterialTheme.colorScheme.primary
    var evF by remember { mutableFloatStateOf(ui.ev.toFloat()) }
    LaunchedEffect(ui.ev) { if (ui.ev != evF.roundToInt()) evF = ui.ev.toFloat() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fp = ui.focusPoint
        val fx = fp?.x ?: 0.5f
        val fy = fp?.y ?: 0.5f
        val track = 132.dp
        val w = 40.dp
        val cx = maxWidth * fx
        val x = (if (fx < 0.78f) cx + 42.dp else cx - 42.dp - w).coerceIn(0.dp, maxWidth - w)
        val y = (maxHeight * fy - track / 2).coerceIn(0.dp, maxHeight - track)
        Box(
            Modifier
                .offset(x, y)
                .size(w, track)
                .pointerInput(lo, hi) {
                    detectVerticalDragGestures(onDragStart = { vm.pokeBrightness() }) { change, dy ->
                        change.consume()
                        val old = evF.roundToInt()
                        evF = (evF - dy / size.height * span).coerceIn(lo, hi)
                        val now = evF.roundToInt()
                        if (now != old) {
                            view.haptic(HapticFeedbackConstants.CLOCK_TICK)
                            vm.setEv(now)
                        } else {
                            vm.pokeBrightness()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val frac = (evF - lo) / span
                val ty = size.height * (1f - frac)
                val mx = size.width / 2
                val gap = 15.dp.toPx()
                val line = 1.2.dp.toPx()
                val c = CamColors.OnScrim.copy(alpha = 0.7f)
                if (ty - gap > 0f) drawLine(c, Offset(mx, 0f), Offset(mx, ty - gap), line)
                if (ty + gap < size.height) drawLine(c, Offset(mx, ty + gap), Offset(mx, size.height), line)
                drawCircle(accent, radius = 4.5.dp.toPx(), center = Offset(mx, ty))
                val r1 = 7.5.dp.toPx()
                val r2 = 10.5.dp.toPx()
                for (k in 0 until 8) {
                    val a = k * PI / 4
                    drawLine(
                        accent,
                        Offset(mx + (cos(a) * r1).toFloat(), ty + (sin(a) * r1).toFloat()),
                        Offset(mx + (cos(a) * r2).toFloat(), ty + (sin(a) * r2).toFloat()),
                        1.5.dp.toPx(),
                    )
                }
            }
        }
        if (ui.ev != 0) {
            Text(
                Fmt.ev(ui.ev, ui.caps.evStep),
                Modifier.offset(x, (y + track).coerceAtMost(maxHeight - 16.dp)).width(w),
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun rememberIconRotation(rotation: Int): Float {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(rotation) {
        val desired = -rotation.toFloat()
        var delta = (desired - target) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        target += delta
    }
    val anim by animateFloatAsState(target, tween(250), label = "iconRotation")
    return anim
}

@Composable
private fun CameraSurface(renderer: GlRenderer) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        renderer.setDisplay(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        renderer.setDisplay(null, 0, 0)
                    }
                })
            }
        },
    )
}

@Composable
private fun GridOverlay(type: GridType) {
    if (type == GridType.NONE) return
    Canvas(Modifier.fillMaxSize()) {
        val c = CamColors.OnScrim.copy(alpha = 0.45f)
        val sw = 1.dp.toPx()
        val fr = when (type) {
            GridType.THIRDS -> listOf(1f / 3f, 2f / 3f)
            GridType.GOLDEN -> listOf(0.382f, 0.618f)
            GridType.SQUARE -> listOf(0.25f, 0.5f, 0.75f)
            GridType.NONE -> emptyList()
        }
        for (f in fr) {
            drawLine(c, Offset(size.width * f, 0f), Offset(size.width * f, size.height), sw)
            drawLine(c, Offset(0f, size.height * f), Offset(size.width, size.height * f), sw)
        }
    }
}

@Composable
private fun LevelOverlay(tracker: OrientationTracker, quarter: Int) {
    val lv by tracker.level.collectAsStateWithLifecycle()
    val hold by tracker.rotation.collectAsStateWithLifecycle()
    if (!lv.valid) return
    val ok = abs(lv.deviation) < 1f
    val screenOffset = -90f * quarter
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val len = size.minDimension * 0.22f
        val sw = 2.dp.toPx()
        rotate(-hold.toFloat() + screenOffset, center) {
            val c = CamColors.OnScrim.copy(alpha = 0.7f)
            drawLine(c, Offset(center.x - len * 1.35f, center.y), Offset(center.x - len * 1.1f, center.y), sw)
            drawLine(c, Offset(center.x + len * 1.1f, center.y), Offset(center.x + len * 1.35f, center.y), sw)
        }
        rotate(lv.horizon + screenOffset, center) {
            drawLine(
                if (ok) CamColors.Ok else CamColors.OnScrim,
                Offset(center.x - len, center.y),
                Offset(center.x + len, center.y),
                sw,
            )
        }
    }
}

@Composable
private fun FocusRing(fp: FocusPoint, locked: Boolean, statsFlow: StateFlow<LiveStats>) {
    val stats by statsFlow.collectAsStateWithLifecycle()
    var visible by remember(fp.id) { mutableStateOf(true) }
    LaunchedEffect(fp.id, locked) {
        visible = true
        if (!locked) {
            kotlinx.coroutines.delay(2500)
            visible = false
        }
    }
    if (!visible) return
    val focused = stats.afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
        stats.afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED
    val color = when {
        locked -> CamColors.Accent
        focused -> CamColors.Ok
        else -> CamColors.OnScrim
    }
    val scale by animateFloatAsState(if (focused || locked) 1f else 1.2f, label = "focusScale")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ring = 72.dp
        Box(
            Modifier
                .offset(maxWidth * fp.x - ring / 2, maxHeight * fp.y - ring / 2)
                .size(ring)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(2.dp, color, CircleShape),
        )
    }
}

@Composable
private fun Histogram(flow: StateFlow<IntArray?>, modifier: Modifier) {
    val h by flow.collectAsStateWithLifecycle()
    val bins = h ?: return
    Canvas(
        modifier
            .size(128.dp, 56.dp)
            .background(CamColors.Scrim.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(4.dp),
    ) {
        val sorted = bins.sorted()
        val max = maxOf(1, sorted[sorted.size - 2])
        val bw = size.width / bins.size
        bins.forEachIndexed { i, v ->
            val bh = (size.height * v / max).coerceAtMost(size.height)
            drawRect(CamColors.OnScrim.copy(alpha = 0.85f), Offset(i * bw, size.height - bh), Size(bw, bh))
        }
    }
}

@Composable
private fun CaptureFlash(trigger: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            alpha.snapTo(0.75f)
            alpha.animateTo(0f, tween(200))
        }
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().background(CamColors.Scrim.copy(alpha = alpha.value)))
}

@Composable
private fun StatusBadge(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(8.dp),
        color = CamColors.Scrim.copy(alpha = 0.55f),
        contentColor = color,
        modifier = Modifier.padding(2.dp),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

// ---------------------------------------------------------------------------- quick toggles

@Composable
private fun TopBarItems(vm: CameraViewModel, ui: UiState, rot: Float) {
    val s = ui.settings
    val busy = ui.recording
    if (ui.caps.flash || vm.screenFlashAvailable(ui)) {
        val icon = when (s.flash) {
            FlashMode.OFF -> Icons.Filled.FlashOff
            FlashMode.AUTO -> if (ui.mode == CameraMode.VIDEO) Icons.Filled.FlashOff else Icons.Filled.FlashAuto
            FlashMode.ON -> if (ui.mode == CameraMode.VIDEO) Icons.Filled.FlashOff else Icons.Filled.FlashOn
            FlashMode.TORCH -> Icons.Filled.Highlight
        }
        BarIcon(icon, "Flash", rot, active = s.flash != FlashMode.OFF) { vm.cycleFlash() }
    }
    if (ui.mode == CameraMode.PHOTO) {
        BarIcon(if (s.live) Icons.Filled.MotionPhotosOn else Icons.Filled.MotionPhotosOff, "Live photo", rot, active = s.live) { vm.toggleLive() }
    }
    if (ui.mode != CameraMode.PRO) {
        BarIcon(Icons.Filled.Brightness6, "Brightness", rot, active = ui.showBrightness || ui.ev != 0) { vm.toggleBrightness() }
    }
    when (ui.mode) {
        CameraMode.PHOTO, CameraMode.PRO -> {
            val timerIcon = when (s.timerSec) {
                0 -> Icons.Filled.TimerOff
                3 -> Icons.Filled.Timer3
                10 -> Icons.Filled.Timer10
                else -> Icons.Filled.Timer
            }
            Box(contentAlignment = Alignment.Center) {
                BarIcon(timerIcon, "Timer", rot, active = s.timerSec > 0) { vm.cycleTimer() }
                if (s.timerSec == 5) {
                    Text("5", Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp), color = CamColors.Accent, fontSize = 10.sp)
                }
            }
            BarText(s.aspect.label, rot) { vm.cycleAspect() }
            if (ui.caps.raw) BarText("RAW", rot, active = s.raw) { vm.toggleRaw() }
        }

        CameraMode.VIDEO -> when (s.videoKind) {
            VideoKind.SLOW_MO -> {
                BarText("${if (ui.activeHighSpeedFps > 0) ui.activeHighSpeedFps else s.slowMoFps}fps", rot, active = true, enabled = !busy) {
                    vm.cycleSlowMoFps()
                }
            }
            VideoKind.TIMELAPSE -> {
                BarText(s.videoResolution.label, rot, enabled = !busy) {
                    val order = VideoResolution.entries
                    vm.updateSettings(reopenCamera = true) { it.copy(videoResolution = order[(order.indexOf(it.videoResolution) + 1) % order.size]) }
                }
                BarText("${s.timelapseSpeed}x", rot, active = true, enabled = !busy) { vm.cycleTimelapseSpeed() }
            }
            VideoKind.NORMAL -> {
                BarText(s.videoResolution.label, rot, enabled = !busy) {
                    val order = VideoResolution.entries
                    vm.updateSettings(reopenCamera = true) { it.copy(videoResolution = order[(order.indexOf(it.videoResolution) + 1) % order.size]) }
                }
                BarText("${s.videoFps}fps", rot, enabled = !busy) {
                    val order = listOf(24, 30, 60)
                    vm.updateSettings(reopenCamera = true) { it.copy(videoFps = order[(order.indexOf(it.videoFps) + 1) % order.size]) }
                }
                BarText("EIS", rot, active = s.stabilization, enabled = !busy) {
                    vm.updateSettings { it.copy(stabilization = !it.stabilization) }
                }
            }
        }
    }
    BarIcon(Icons.Filled.PhotoFilter, "Filters", rot, active = s.filterId != Filters.ORIGINAL || ui.showFilters) { vm.toggleFilters() }
    BarIcon(Icons.Filled.Settings, "Settings", rot, enabled = !busy) { vm.showSettings(true) }
}

@Composable
private fun BarIcon(icon: ImageVector, desc: String, rot: Float, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val view = LocalView.current
    IconButton(
        onClick = {
            view.haptic(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        },
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary else CamColors.OnScrim,
            disabledContentColor = CamColors.OnScrim.copy(alpha = 0.38f),
        ),
    ) {
        Icon(icon, desc, Modifier.graphicsLayer { rotationZ = rot })
    }
}

@Composable
private fun BarText(text: String, rot: Float, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val view = LocalView.current
    Surface(
        onClick = {
            view.haptic(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        contentColor = (if (active) MaterialTheme.colorScheme.primary else CamColors.OnScrim).copy(alpha = if (enabled) 1f else 0.38f),
    ) {
        Box(Modifier.height(40.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, Modifier.graphicsLayer { rotationZ = rot }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------------------- controls

@Composable
private fun BrightnessSlider(vm: CameraViewModel, ui: UiState) {
    val r = ui.caps.evRange
    val view = LocalView.current
    if (r.upper <= r.lower) {
        Text(
            "Brightness can't be adjusted on this lens",
            Modifier.fillMaxWidth().padding(8.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.BrightnessLow, "Darker", Modifier.size(20.dp))
        Slider(
            value = ui.ev.toFloat(),
            onValueChange = {
                val v = it.roundToInt()
                if (v != ui.ev) view.haptic(HapticFeedbackConstants.CLOCK_TICK)
                vm.setEv(v)
            },
            valueRange = r.lower.toFloat()..r.upper.toFloat(),
            steps = (r.upper - r.lower - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(Icons.Filled.BrightnessHigh, "Brighter", Modifier.size(20.dp))
        Surface(onClick = { vm.setEv(0) }, shape = RoundedCornerShape(50), color = Color.Transparent) {
            Text(
                Fmt.ev(ui.ev, ui.caps.evStep),
                Modifier.width(52.dp).padding(vertical = 6.dp),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Logarithmic zoom slider spanning every lens it can hop between, with the current zoom shown. */
@Composable
private fun ZoomSlider(vm: CameraViewModel, ui: UiState, modifier: Modifier) {
    val (lo, hi) = vm.displayZoomRange(ui)
    if (hi <= lo * 1.01f) return
    val z = vm.displayZoom(ui)
    val pos = (ln(z / lo) / ln(hi / lo)).coerceIn(0f, 1f)
    Surface(
        modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(50),
        color = CamColors.Scrim.copy(alpha = 0.55f),
        contentColor = CamColors.OnScrim,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.zoom(lo), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = pos,
                onValueChange = { v ->
                    vm.setDisplayZoom(lo * (hi / lo).pow(v))
                    vm.scheduleZoomSliderHide()
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(Fmt.zoom(hi), style = MaterialTheme.typography.labelSmall)
            Text(
                Fmt.zoom(z),
                Modifier.width(52.dp),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LensChips(vm: CameraViewModel, ui: UiState, rot: Float, vertical: Boolean) {
    val chips = vm.lensChips(ui)
    val view = LocalView.current
    if (chips.size <= 1) {
        if (vm.displayZoom(ui) > 1.05f || ui.showZoomSlider) {
            Surface(
                onClick = { vm.toggleZoomSlider() },
                shape = CircleShape,
                color = CamColors.Scrim.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(6.dp),
            ) {
                Text(Fmt.zoom(vm.displayZoom(ui)), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Spacer(Modifier.size(8.dp))
        }
        return
    }
    val active = vm.activeChip(ui, chips)
    val content: @Composable () -> Unit = {
        chips.forEach { chip ->
            val sel = chip == active
            val label = when {
                !sel -> chip.label
                chip.lens.kind == LensKind.MACRO || chip.lens.kind == LensKind.FRONT -> chip.label
                else -> Fmt.zoom(vm.displayZoom(ui))
            }
            Surface(
                onClick = {
                    view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (sel) vm.toggleZoomSlider() else vm.selectChip(chip)
                },
                enabled = !ui.recording || chip.lens === ui.lens,
                shape = CircleShape,
                color = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else CamColors.OnScrim,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        Modifier.graphicsLayer { rotationZ = rot },
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = if (label.length > 4) 10.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    Surface(Modifier.padding(6.dp), shape = CircleShape, color = CamColors.Scrim.copy(alpha = 0.45f)) {
        if (vertical) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        } else {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}

@Composable
private fun VideoKindChips(vm: CameraViewModel, ui: UiState, vertical: Boolean) {
    val view = LocalView.current
    val content: @Composable () -> Unit = {
        VideoKind.entries.forEach { k ->
            val available = k != VideoKind.SLOW_MO || ui.slowMoOptions.isNotEmpty()
            FilterChip(
                selected = ui.settings.videoKind == k,
                onClick = {
                    view.haptic(HapticFeedbackConstants.CLOCK_TICK)
                    vm.setVideoKind(k)
                },
                enabled = available,
                label = { Text(k.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun ModeSelector(vm: CameraViewModel, ui: UiState, vertical: Boolean) {
    val view = LocalView.current
    val content: @Composable () -> Unit = {
        CameraMode.entries.forEach { m ->
            val sel = m == ui.mode
            Surface(
                onClick = {
                    if (!sel) view.haptic(HapticFeedbackConstants.CLOCK_TICK)
                    vm.setMode(m)
                },
                enabled = !ui.recording,
                shape = CircleShape,
                color = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (sel) MaterialTheme.colorScheme.onSecondaryContainer else CamColors.OnScrim,
            ) {
                Text(
                    m.label,
                    Modifier.padding(horizontal = if (vertical) 12.dp else 18.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
    if (vertical) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    } else {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** Gallery thumbnail, or pause/resume while recording. */
@Composable
private fun ShutterSideStart(vm: CameraViewModel, ui: UiState, rot: Float) {
    val ctx = LocalContext.current
    val view = LocalView.current
    if (ui.recording) {
        FilledTonalIconButton(
            onClick = {
                view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                vm.togglePause()
            },
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                if (ui.recordingPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                if (ui.recordingPaused) "Resume" else "Pause",
                Modifier.graphicsLayer { rotationZ = rot },
            )
        }
        return
    }
    Surface(
        onClick = {
            val intent = vm.galleryIntent()
            if (intent != null) {
                try {
                    ctx.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    vm.fallbackGalleryIntent()?.let { runCatching { ctx.startActivity(it) } }
                } catch (_: SecurityException) {
                    vm.fallbackGalleryIntent()?.let { runCatching { ctx.startActivity(it) } }
                }
            }
        },
        enabled = ui.lastUri != null,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.5.dp, CamColors.OnScrim.copy(alpha = 0.8f)),
        modifier = Modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            ui.thumbnail?.let {
                Image(
                    it.asImageBitmap(), "Last capture",
                    Modifier.fillMaxSize().clip(CircleShape).graphicsLayer { rotationZ = rot },
                    contentScale = ContentScale.Crop,
                )
            }
            if (ui.saving > 0) {
                CircularProgressIndicator(Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            }
        }
    }
}

/** Switch camera, or take a still while recording. */
@Composable
private fun ShutterSideEnd(vm: CameraViewModel, ui: UiState, rot: Float) {
    val view = LocalView.current
    FilledTonalIconButton(
        onClick = {
            view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            if (ui.recording) vm.takeVideoSnapshot() else vm.switchFacing()
        },
        modifier = Modifier.size(56.dp),
    ) {
        Icon(
            if (ui.recording) Icons.Filled.PhotoCamera else Icons.Filled.Cameraswitch,
            if (ui.recording) "Take photo" else "Switch camera",
            Modifier.graphicsLayer { rotationZ = rot },
        )
    }
}

@Composable
private fun ShutterButton(vm: CameraViewModel, ui: UiState) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val video = ui.mode == CameraMode.VIDEO
    val innerScale by animateFloatAsState(if (pressed) 0.86f else 1f, label = "shutterPress")
    val photoFill = if (ui.countdown != null) MaterialTheme.colorScheme.primary else CamColors.OnScrim
    Box(
        Modifier
            .size(84.dp)
            .border(4.dp, CamColors.OnScrim, CircleShape)
            .padding(8.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        vm.onShutterRelease()
                    },
                    onTap = {
                        view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        vm.onShutterTap()
                    },
                    onLongPress = {
                        view.haptic(HapticFeedbackConstants.LONG_PRESS)
                        vm.onShutterLongPress()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            video && ui.recording -> Box(Modifier.size(28.dp).background(CamColors.Record, RoundedCornerShape(6.dp)))
            video -> Box(Modifier.fillMaxSize().graphicsLayer { scaleX = innerScale; scaleY = innerScale }.background(CamColors.Record, CircleShape))
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = innerScale; scaleY = innerScale }
                    .background(photoFill, CircleShape),
            )
        }
    }
}
