package com.purecam.model

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.media.MediaFormat

enum class CameraMode(val label: String) { PHOTO("PHOTO"), PRO("PRO"), VIDEO("VIDEO") }

enum class AspectRatio(val label: String, val long: Int, val short: Int) {
    R4_3("4:3", 4, 3),
    R16_9("16:9", 16, 9),
    R1_1("1:1", 1, 1);

    /** Landscape width / height, always >= 1. */
    val landscape: Float get() = long.toFloat() / short
}

enum class FlashMode { OFF, AUTO, ON, TORCH }

enum class GridType(val label: String) {
    NONE("Off"), THIRDS("Rule of thirds"), GOLDEN("Golden ratio"), SQUARE("4 × 4")
}

/** How much of the camera's own (non-AI, classic ISP) noise reduction / sharpening is applied. */
enum class Processing(val label: String) {
    OFF("Off (pure sensor)"), MINIMAL("Minimal"), FAST("Fast"), HIGH_QUALITY("High quality")
}

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    UHD("4K", 3840, 2160), FHD("1080p", 1920, 1080), HD("720p", 1280, 720)
}

enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264 (AVC)", MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC("H.265 (HEVC)", MediaFormat.MIMETYPE_VIDEO_HEVC)
}

enum class PhotoFormat(val label: String) { JPEG("JPEG"), HEIC("HEIC (about half the size)") }

enum class VideoKind(val label: String) { NORMAL("Normal"), SLOW_MO("Slow motion"), TIMELAPSE("Time-lapse") }

enum class VolumeKeyAction(val label: String) {
    SHUTTER("Shutter"), ZOOM("Zoom"), OFF("System volume")
}

enum class WbPreset(val label: String, val awbMode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    INCANDESCENT("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    KELVIN("Kelvin", CameraMetadata.CONTROL_AWB_MODE_OFF)
}

data class AppSettings(
    val aspect: AspectRatio = AspectRatio.R4_3,
    val flash: FlashMode = FlashMode.OFF,
    val timerSec: Int = 0,
    val grid: GridType = GridType.NONE,
    val live: Boolean = false,
    val raw: Boolean = false,
    val jpegQuality: Int = 100,
    val noiseReduction: Processing = Processing.OFF,
    val sharpening: Processing = Processing.OFF,
    val keepOriginal: Boolean = false,
    val mirrorFront: Boolean = false,
    val liveSaveVideo: Boolean = false,
    val videoResolution: VideoResolution = VideoResolution.UHD,
    val videoFps: Int = 30,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val videoHighBitrate: Boolean = true,
    val stabilization: Boolean = true,
    val recordAudio: Boolean = true,
    val level: Boolean = true,
    val histogram: Boolean = true,
    val focusPeaking: Boolean = true,
    val shutterSound: Boolean = true,
    val volumeKeys: VolumeKeyAction = VolumeKeyAction.SHUTTER,
    val filterId: String = "original",
    val filterIntensity: Float = 1f,
    val geotag: Boolean = false,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val videoKind: VideoKind = VideoKind.NORMAL,
    val slowMoFps: Int = 240,
    val timelapseSpeed: Int = 10,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("purecam_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        val p = prefs
        return AppSettings(
            aspect = enumOr(p.getString("aspect", null), d.aspect),
            flash = enumOr(p.getString("flash", null), d.flash),
            timerSec = p.getInt("timer", d.timerSec),
            grid = enumOr(p.getString("grid", null), d.grid),
            live = p.getBoolean("live", d.live),
            raw = p.getBoolean("raw", d.raw),
            jpegQuality = p.getInt("jpegQuality", d.jpegQuality),
            noiseReduction = enumOr(p.getString("nr", null), d.noiseReduction),
            sharpening = enumOr(p.getString("edge", null), d.sharpening),
            keepOriginal = p.getBoolean("keepOriginalV2", d.keepOriginal),
            mirrorFront = p.getBoolean("mirrorFront", d.mirrorFront),
            liveSaveVideo = p.getBoolean("liveSaveVideo", d.liveSaveVideo),
            videoResolution = enumOr(p.getString("videoRes", null), d.videoResolution),
            videoFps = p.getInt("videoFps", d.videoFps),
            videoCodec = enumOr(p.getString("videoCodec", null), d.videoCodec),
            videoHighBitrate = p.getBoolean("videoHighBitrate", d.videoHighBitrate),
            stabilization = p.getBoolean("stabilization", d.stabilization),
            recordAudio = p.getBoolean("recordAudio", d.recordAudio),
            level = p.getBoolean("level", d.level),
            histogram = p.getBoolean("histogram", d.histogram),
            focusPeaking = p.getBoolean("peaking", d.focusPeaking),
            shutterSound = p.getBoolean("shutterSound", d.shutterSound),
            volumeKeys = enumOr(p.getString("volumeKeys", null), d.volumeKeys),
            filterId = p.getString("filter", d.filterId) ?: d.filterId,
            filterIntensity = p.getFloat("filterIntensity", d.filterIntensity),
            geotag = p.getBoolean("geotag", d.geotag),
            photoFormat = enumOr(p.getString("photoFormat", null), d.photoFormat),
            videoKind = enumOr(p.getString("videoKind", null), d.videoKind),
            slowMoFps = p.getInt("slowMoFps", d.slowMoFps),
            timelapseSpeed = p.getInt("timelapseSpeed", d.timelapseSpeed),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("aspect", s.aspect.name)
            .putString("flash", s.flash.name)
            .putInt("timer", s.timerSec)
            .putString("grid", s.grid.name)
            .putBoolean("live", s.live)
            .putBoolean("raw", s.raw)
            .putInt("jpegQuality", s.jpegQuality)
            .putString("nr", s.noiseReduction.name)
            .putString("edge", s.sharpening.name)
            .putBoolean("keepOriginalV2", s.keepOriginal)
            .putBoolean("mirrorFront", s.mirrorFront)
            .putBoolean("liveSaveVideo", s.liveSaveVideo)
            .putString("videoRes", s.videoResolution.name)
            .putInt("videoFps", s.videoFps)
            .putString("videoCodec", s.videoCodec.name)
            .putBoolean("videoHighBitrate", s.videoHighBitrate)
            .putBoolean("stabilization", s.stabilization)
            .putBoolean("recordAudio", s.recordAudio)
            .putBoolean("level", s.level)
            .putBoolean("histogram", s.histogram)
            .putBoolean("peaking", s.focusPeaking)
            .putBoolean("shutterSound", s.shutterSound)
            .putString("volumeKeys", s.volumeKeys.name)
            .putString("filter", s.filterId)
            .putFloat("filterIntensity", s.filterIntensity)
            .putBoolean("geotag", s.geotag)
            .putString("photoFormat", s.photoFormat.name)
            .putString("videoKind", s.videoKind.name)
            .putInt("slowMoFps", s.slowMoFps)
            .putInt("timelapseSpeed", s.timelapseSpeed)
            .apply()
    }

    fun photoSize(key: String): String? = prefs.getString("size_$key", null)

    fun setPhotoSize(key: String, id: String) {
        prefs.edit().putString("size_$key", id).apply()
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
    name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def
