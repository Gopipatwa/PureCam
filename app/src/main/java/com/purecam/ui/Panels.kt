@file:OptIn(ExperimentalMaterial3Api::class)

package com.purecam.ui

import android.Manifest
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.purecam.gl.Filter
import com.purecam.gl.Filters
import com.purecam.gl.GlRenderer
import com.purecam.model.CameraMode
import com.purecam.model.GridType
import com.purecam.model.PhotoFormat
import com.purecam.model.ColorProfile
import com.purecam.model.Processing
import com.purecam.model.VideoCodec
import com.purecam.model.VideoKind
import com.purecam.model.VideoResolution
import com.purecam.model.VolumeKeyAction
import com.purecam.model.WbPreset
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------- Pro controls

@Composable
fun ProPanel(vm: CameraViewModel, ui: UiState) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val caps = ui.caps
    val manual = caps.manualSensor
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        when (ui.proControl) {
            ProControl.ISO -> {
                val stops = remember(caps.isoRange) { Fmt.isoStops(caps.isoRange) }
                StopSlider(stops, ui.iso, stats.iso, { "$it" }) { vm.setIso(it) }
            }
            ProControl.SHUTTER -> {
                val stops = remember(caps.exposureRange) { Fmt.shutterStops(caps.exposureRange) }
                StopSlider(stops, ui.shutterNs, stats.exposureNs, Fmt::shutter) { vm.setShutter(it) }
            }
            ProControl.FOCUS -> FocusSlider(vm, ui)
            ProControl.WB -> WbControl(vm, ui)
            ProControl.EV -> {
                val r = caps.evRange
                val values = (r.lower..r.upper).toList()
                StopSlider(values, ui.ev.takeIf { it != 0 }, 0, { Fmt.ev(it, caps.evStep) }) { vm.setEv(it ?: 0) }
            }
            null -> Unit
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProItem("ISO", ui.iso?.toString() ?: "A ${stats.iso}", ui.iso != null, ui.proControl == ProControl.ISO, manual) {
                vm.selectProControl(ProControl.ISO)
            }
            ProItem("S", ui.shutterNs?.let { Fmt.shutter(it) } ?: "A ${Fmt.shutter(stats.exposureNs)}", ui.shutterNs != null, ui.proControl == ProControl.SHUTTER, manual) {
                vm.selectProControl(ProControl.SHUTTER)
            }
            ProItem("F", ui.focus?.let { Fmt.focus(it) } ?: "AF", ui.focus != null, ui.proControl == ProControl.FOCUS, manual && caps.minFocusDiopters > 0f) {
                vm.selectProControl(ProControl.FOCUS)
            }
            ProItem("WB", if (ui.wb == WbPreset.KELVIN) "${ui.kelvin}K" else ui.wb.label, ui.wb != WbPreset.AUTO, ui.proControl == ProControl.WB, true) {
                vm.selectProControl(ProControl.WB)
            }
            ProItem("EV", Fmt.ev(ui.ev, caps.evStep), ui.ev != 0, ui.proControl == ProControl.EV, ui.iso == null && ui.shutterNs == null) {
                vm.selectProControl(ProControl.EV)
            }
            IconButton(onClick = { vm.resetPro() }) { Icon(Icons.Filled.Refresh, "Reset to auto") }
        }
        if (!manual) {
            Text(
                "This lens doesn't expose manual sensor control to apps",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProItem(title: String, value: String, manual: Boolean, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val a = if (enabled) 1f else 0.38f
            Text(title, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = a))
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = (if (manual) scheme.primary else scheme.onSurface).copy(alpha = a),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AutoChip(active: Boolean, onClick: () -> Unit) {
    FilterChip(selected = active, onClick = onClick, label = { Text("AUTO") })
}

/** Slider over discrete values (ISO stops, shutter speeds…). `null` selection = automatic. */
@Composable
private fun <T : Comparable<T>> StopSlider(
    values: List<T>,
    selected: T?,
    autoValue: T,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    if (values.size < 2) {
        Text("Not adjustable on this lens", style = MaterialTheme.typography.bodySmall)
        return
    }
    val idx = selected?.let { s -> values.indexOf(s).takeIf { it >= 0 } } ?: closestIndex(values, selected ?: autoValue)
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AutoChip(selected == null) { onSelect(null) }
        Slider(
            value = idx.toFloat(),
            onValueChange = {
                val i = it.roundToInt().coerceIn(values.indices)
                if (i != idx || selected == null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelect(values[i])
            },
            valueRange = 0f..(values.size - 1).toFloat(),
            steps = (values.size - 2).coerceAtLeast(0),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            label(values[idx]),
            color = if (selected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** [values] are sorted ascending: first value >= target, else the last one. */
private fun <T : Comparable<T>> closestIndex(values: List<T>, target: T): Int {
    val i = values.indexOfFirst { it >= target }
    return if (i < 0) values.size - 1 else i
}

@Composable
private fun FocusSlider(vm: CameraViewModel, ui: UiState) {
    val min = ui.caps.minFocusDiopters
    if (min <= 0f) {
        Text("Fixed-focus lens", style = MaterialTheme.typography.bodySmall)
        return
    }
    val pos = ui.focus?.let { sqrt((it / min).coerceIn(0f, 1f)) } ?: 0f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AutoChip(ui.focus == null) { vm.setFocus(null) }
        Text("∞", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        Slider(
            value = pos,
            onValueChange = { s -> vm.setFocus(min * s * s) },
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        Text("Near", style = MaterialTheme.typography.labelSmall)
        Text(
            ui.focus?.let { Fmt.focus(it) } ?: "AF",
            color = if (ui.focus != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun WbControl(vm: CameraViewModel, ui: UiState) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(WbPreset.entries.filter { it != WbPreset.KELVIN || ui.caps.manualWb }) { p ->
                FilterChip(selected = p == ui.wb, onClick = { vm.setWb(p) }, label = { Text(p.label) })
            }
        }
        if (ui.wb == WbPreset.KELVIN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2000K", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = ui.kelvin.toFloat(),
                    onValueChange = { vm.setKelvin(((it / 100f).roundToInt() * 100)) },
                    valueRange = 2000f..10000f,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                Text("10000K", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------- Filters

/** Shown on a filter tile until the first live preview frame arrives. */
private val SWATCH = listOf(
    floatArrayOf(0.36f, 0.62f, 0.92f),
    floatArrayOf(0.93f, 0.74f, 0.62f),
    floatArrayOf(0.30f, 0.58f, 0.26f),
    floatArrayOf(0.96f, 0.82f, 0.30f),
    floatArrayOf(0.14f, 0.14f, 0.17f),
)

@Composable
fun FilterStrip(vm: CameraViewModel, ui: UiState) {
    val s = ui.settings
    // Read inside the draw phase only, so new preview frames redraw tiles without recomposing.
    val thumbs = vm.filterThumbs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        if (s.filterId != Filters.ORIGINAL) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Strength", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = s.filterIntensity,
                    onValueChange = { vm.setFilterIntensity(it) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    "${(s.filterIntensity * 100).roundToInt()}%",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(Filters.all, key = { _, f -> f.id }) { i, f ->
                FilterItem(f, i, thumbs, f.id == s.filterId) { vm.setFilter(f.id) }
            }
        }
    }
}

@Composable
private fun FilterItem(
    f: Filter,
    index: Int,
    thumbs: State<CameraViewModel.FilterThumbs?>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val swatch = remember(f.id) {
        SWATCH.map { c -> f.params.applyCpu(c).let { Color(it[0], it[1], it[2]) } }
    }
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) scheme.primary else scheme.outlineVariant,
                    RoundedCornerShape(14.dp),
                ),
        ) {
            val t = thumbs.value
            if (t != null && (index + 1) * GlRenderer.THUMB <= t.image.width) {
                drawImage(
                    t.image,
                    srcOffset = IntOffset(index * GlRenderer.THUMB, 0),
                    srcSize = IntSize(GlRenderer.THUMB, GlRenderer.THUMB),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            } else {
                val w = size.width / swatch.size
                swatch.forEachIndexed { i, c -> drawRect(c, Offset(i * w, 0f), Size(w + 1f, size.height)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            f.name,
            color = if (selected) scheme.primary else scheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------- Settings

@Composable
fun SettingsDialog(vm: CameraViewModel, ui: UiState) {
    val s = ui.settings
    val videoMode = ui.mode == CameraMode.VIDEO
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) vm.setGeotag(true) else vm.showMessage("Location permission denied - photos won't be tagged")
    }
    Dialog(
        onDismissRequest = { vm.showSettings(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = { vm.showSettings(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                Section("Photo")
                if (ui.photoOptions.isNotEmpty()) {
                    ChoiceRow("Resolution (${ui.lens?.label ?: ""})", ui.photoOptions, ui.photoSize, { it.label }) { vm.selectPhotoSize(it) }
                    if (s.live) InfoText("Live Photos are captured at ~12 MP to keep files small; your choice applies to normal shots.")
                }
                SwitchRow(
                    "RAW (DNG) alongside JPEG",
                    s.raw,
                    enabled = ui.caps.raw,
                    subtitle = if (ui.caps.raw) "Unprocessed sensor data for Lightroom / Snapseed" else "Not offered by this lens",
                ) { vm.toggleRaw() }
                ChoiceRow("Colour profile", ColorProfile.entries.toList(), s.colorProfile, { it.label }) { c ->
                    vm.updateSettings { it.copy(colorProfile = c) }
                }
                InfoText(
                    "A fixed contrast curve applied by the camera's own tone-mapping hardware (no AI). " +
                        "Natural is the camera's untouched output.",
                )
                ChoiceRow("Photo format", PhotoFormat.entries.toList(), s.photoFormat, { it.label }) { f ->
                    vm.updateSettings(reopenCamera = true) { it.copy(photoFormat = f) }
                }
                if (s.photoFormat == PhotoFormat.HEIC) {
                    InfoText("Live Photos stay JPEG Motion Photos (the format galleries understand). HEIC needs about 50 MP or less unless the camera writes HEIC itself.")
                }
                ChoiceRow("Image quality", listOf(80, 90, 95, 100), s.jpegQuality, { "$it" }) { q -> vm.updateSettings { it.copy(jpegQuality = q) } }
                ChoiceRow("Noise reduction", Processing.entries.toList(), s.noiseReduction, { it.label }) { p ->
                    vm.updateSettings { it.copy(noiseReduction = p) }
                }
                ChoiceRow("Sharpening", listOf(Processing.OFF, Processing.FAST, Processing.HIGH_QUALITY), s.sharpening, { it.label }) { p ->
                    vm.updateSettings { it.copy(sharpening = p) }
                }
                SwitchRow("Keep unfiltered original", s.keepOriginal, subtitle = "Off: one file per photo. On: with a filter, also save an unfiltered copy") {
                    vm.updateSettings { it.copy(keepOriginal = !it.keepOriginal) }
                }
                SwitchRow("Mirror front-camera photos", s.mirrorFront) { vm.updateSettings { it.copy(mirrorFront = !it.mirrorFront) } }
                SwitchRow("Live Photo: also save separate MP4", s.liveSaveVideo) { vm.updateSettings { it.copy(liveSaveVideo = !it.liveSaveVideo) } }
                SwitchRow("Shutter sound", s.shutterSound, subtitle = "Some regions always require it") {
                    vm.updateSettings { it.copy(shutterSound = !it.shutterSound) }
                }
                SwitchRow("Save location", s.geotag, subtitle = "Adds GPS coordinates to photos and videos") {
                    when {
                        s.geotag -> vm.setGeotag(false)
                        vm.location.hasPermission() -> vm.setGeotag(true)
                        else -> locationLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }
                }

                Section("Video")
                ChoiceRow("Video type", VideoKind.entries.toList(), s.videoKind, { it.label }) { k -> vm.setVideoKind(k) }
                if (ui.slowMoOptions.isNotEmpty()) {
                    ChoiceRow(
                        "Slow-motion frame rate",
                        ui.slowMoOptions.keys.sorted(),
                        s.slowMoFps.takeIf { it in ui.slowMoOptions } ?: ui.slowMoOptions.keys.max(),
                        { fps -> "$fps fps · ${ui.slowMoOptions[fps]?.let { "${it.height}p" } ?: ""} · ${fps / 30}x slower" },
                    ) { f -> vm.updateSettings(reopenCamera = videoMode) { it.copy(slowMoFps = f) } }
                } else {
                    InfoText("Slow motion: this lens doesn't offer high-speed video to apps.")
                }
                ChoiceRow("Time-lapse speed", CameraViewModel.TIMELAPSE_SPEEDS, s.timelapseSpeed, { "${it}x faster" }) { v ->
                    vm.updateSettings { it.copy(timelapseSpeed = v) }
                }
                ChoiceRow("Resolution", VideoResolution.entries.toList(), s.videoResolution, { it.label }) { r ->
                    vm.updateSettings(reopenCamera = videoMode) { it.copy(videoResolution = r) }
                }
                ChoiceRow("Frame rate", listOf(24, 30, 60), s.videoFps, { "$it fps" }) { f ->
                    vm.updateSettings(reopenCamera = videoMode) { it.copy(videoFps = f) }
                }
                ChoiceRow("Codec", VideoCodec.entries.toList(), s.videoCodec, { it.label }) { c -> vm.updateSettings { it.copy(videoCodec = c) } }
                SwitchRow("High bitrate", s.videoHighBitrate) { vm.updateSettings { it.copy(videoHighBitrate = !it.videoHighBitrate) } }
                SwitchRow("Electronic stabilisation (EIS)", s.stabilization, subtitle = "Optical stabilisation is always on when the lens has it") {
                    vm.updateSettings { it.copy(stabilization = !it.stabilization) }
                }
                SwitchRow("Record audio", s.recordAudio) { vm.updateSettings { it.copy(recordAudio = !it.recordAudio) } }

                Section("Viewfinder")
                ChoiceRow("Grid", GridType.entries.toList(), s.grid, { it.label }) { g -> vm.updateSettings { it.copy(grid = g) } }
                SwitchRow("Level indicator", s.level) { vm.updateSettings { it.copy(level = !it.level) } }
                SwitchRow("Histogram (Pro)", s.histogram) { vm.updateSettings { it.copy(histogram = !it.histogram) } }
                SwitchRow("Focus peaking (Pro, manual focus)", s.focusPeaking) { vm.updateSettings { it.copy(focusPeaking = !it.focusPeaking) } }

                Section("Controls")
                ChoiceRow("Volume keys", VolumeKeyAction.entries.toList(), s.volumeKeys, { it.label }) { a -> vm.updateSettings { it.copy(volumeKeys = a) } }

                Section("Lenses detected")
                if (ui.lenses.isEmpty()) InfoText("No lenses found yet")
                ui.lenses.forEach { l -> InfoText("${l.label} — ${l.description}") }
                InfoText("Current lens hardware level: ${ui.caps.hardwareLevel}")
                InfoText(
                    "If a lens (often the macro) fails to open, Xiaomi's firmware is restricting it to its own " +
                        "camera app. Nothing an app can do without root changes that.",
                )

                Section("About")
                InfoText(
                    "PureCam uses only the standard Android Camera2 API. There is no AI scene detection, " +
                        "beautification, multi-frame merging or super-resolution. With noise reduction and " +
                        "sharpening set to Off you get the sensor's image with only the essential ISP steps " +
                        "(demosaic, white balance, colour and tone mapping). Use RAW for completely unprocessed data. " +
                        "The Leica-style filters are PureCam's own approximations of those looks.",
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, subtitle: String? = null, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onToggle),
    )
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(selected?.let(label) ?: "—", color = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { o ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    open = false
                                    onSelect(o)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = o == selected, onClick = {
                                open = false
                                onSelect(o)
                            })
                            Text(label(o), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
        )
    }
}
