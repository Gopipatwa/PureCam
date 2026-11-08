package com.purecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class LensKind { ULTRAWIDE, MAIN, TELE, MACRO, FRONT, OTHER }

/**
 * One selectable lens. [openId] is the camera device that gets opened; when [physicalId] is set,
 * the lens is a physical sub-camera of the logical camera [openId] and its streams are routed
 * with OutputConfiguration.setPhysicalCameraId().
 */
class Lens(
    val openId: String,
    val physicalId: String?,
    val facing: Int,
    /** Characteristics of the actual sensor (physical camera when [physicalId] is set). */
    val chars: CameraCharacteristics,
    /** Characteristics of the device that is opened. */
    val openChars: CameraCharacteristics,
    /** Not in CameraManager.cameraIdList – found by probing IDs. */
    val hidden: Boolean,
    val logical: Boolean,
    val focalMm: Float,
    val eqFocalMm: Float,
    val megapixels: Float,
    val maxMegapixels: Float,
) {
    var kind: LensKind = LensKind.OTHER
        internal set
    var label: String = "?"
        internal set
    /** Field of view relative to the main lens (0.6 = ultra-wide). */
    var relativeZoom: Float = 1f
        internal set

    val key: String get() = if (physicalId != null) "$openId:$physicalId" else openId
    val isFront: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT
    val sensorOrientation: Int get() = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    val description: String
        get() = buildString {
            append("ID ").append(openId)
            if (physicalId != null) append(" → physical ").append(physicalId)
            append(" · ").append(String.format(Locale.US, "%.2f mm (≈%.0f mm eq.)", focalMm, eqFocalMm))
            append(" · ").append(String.format(Locale.US, "%.1f MP", megapixels))
            if (maxMegapixels > megapixels + 1f) append(String.format(Locale.US, " (%.0f MP full sensor)", maxMegapixels))
            if (hidden) append(" · hidden ID")
            if (logical) append(" · logical multi-camera")
        }
}

object LensDiscovery {
    /** Xiaomi/MediaTek often expose auxiliary lenses only on IDs missing from cameraIdList. */
    private const val PROBE_MAX = 64

    fun discover(manager: CameraManager): List<Lens> {
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val ids = LinkedHashSet<String>(listed)
        for (i in 0 until PROBE_MAX) ids.add(i.toString())

        val cache = HashMap<String, CameraCharacteristics?>()
        fun query(id: String): CameraCharacteristics? = cache.getOrPut(id) {
            try {
                manager.getCameraCharacteristics(id)
            } catch (_: Throwable) {
                null
            }
        }

        val standalone = mutableListOf<Lens>()
        val physical = mutableListOf<Lens>()
        for (id in ids) {
            val ch = query(id) ?: continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) continue
            val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            make(id, null, ch, ch, id !in listed, logical)?.let { standalone += it }
            if (logical) {
                for (pid in ch.physicalCameraIds) {
                    val pch = query(pid) ?: continue
                    make(id, pid, pch, ch, hidden = true, logical = false)?.let { physical += it }
                }
            }
        }

        // De-duplicate: the same sensor can show up as a listed ID, a hidden ID and a physical sub-camera.
        val unique = mutableListOf<Lens>()
        for (l in standalone + physical) {
            val dup = unique.any {
                it.facing == l.facing &&
                    abs(it.focalMm - l.focalMm) < 0.05f &&
                    abs(it.megapixels - l.megapixels) < 0.5f
            }
            if (!dup) unique += l
        }

        classify(unique, listed)
        val backs = unique.filter { !it.isFront }
            .sortedWith(compareBy<Lens>({ it.kind == LensKind.MACRO }, { it.relativeZoom }))
        val fronts = unique.filter { it.isFront }
        return backs + fronts
    }

    private fun make(
        openId: String,
        physicalId: String?,
        ch: CameraCharacteristics,
        openCh: CameraCharacteristics,
        hidden: Boolean,
        logical: Boolean,
    ): Lens? {
        val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: return null
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = if (phys != null) sqrt(phys.width * phys.width + phys.height * phys.height) else 0f
        val eq = if (diag > 0f) focal * 43.27f / diag else focal * 7f
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val mp = if (active != null) active.width() * active.height() / 1_000_000f else 0f
        var maxMp = mp
        if (Build.VERSION.SDK_INT >= 31) {
            ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.let {
                maxMp = maxOf(mp, it.width() * it.height() / 1_000_000f)
            }
        }
        return Lens(openId, physicalId, facing, ch, openCh, hidden, logical, focal, eq, mp, maxMp)
    }

    private fun classify(lenses: List<Lens>, listed: List<String>) {
        val backs = lenses.filter { !it.isFront }
        val main = backs.firstOrNull { it.physicalId == null && it.openId == listed.firstOrNull { id -> backs.any { b -> b.openId == id } } }
            ?: backs.maxByOrNull { it.megapixels }
        for (l in backs) {
            if (l === main) {
                l.kind = LensKind.MAIN; l.label = "1x"; l.relativeZoom = 1f
                continue
            }
            val rel = if (main != null && main.eqFocalMm > 0f) l.eqFocalMm / main.eqFocalMm else 1f
            l.relativeZoom = rel
            when {
                l.megapixels < 4f && rel > 0.75f -> { l.kind = LensKind.MACRO; l.label = "Macro"; l.relativeZoom = 99f }
                rel < 0.85f -> { l.kind = LensKind.ULTRAWIDE; l.label = zoomLabel(rel) }
                rel > 1.3f -> { l.kind = LensKind.TELE; l.label = zoomLabel(rel) }
                else -> { l.kind = LensKind.OTHER; l.label = "1x·${l.key}" }
            }
        }
        lenses.filter { it.isFront }.forEachIndexed { i, l ->
            l.kind = LensKind.FRONT
            l.label = if (i == 0) "Front" else "Front ${i + 1}"
        }
    }

    fun zoomLabel(z: Float): String {
        val s = String.format(Locale.US, "%.1f", z)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + "x"
    }
}
