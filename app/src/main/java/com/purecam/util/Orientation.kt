package com.purecam.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.OrientationEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class LevelState(
    /** Rotation to apply to an on-screen line so it stays parallel to the horizon (degrees). */
    val horizon: Float = 0f,
    /** Deviation from perfectly level for the current hold orientation (degrees). */
    val deviation: Float = 0f,
    /** False when the phone is lying (nearly) flat and a horizon is meaningless. */
    val valid: Boolean = false,
)

/** Tracks how the phone is held (the activity itself is locked to portrait, like most camera apps). */
class OrientationTracker(context: Context) : SensorEventListener {
    private val _rotation = MutableStateFlow(0)
    /** 0, 90, 180, 270 – degrees the device is rotated clockwise from natural portrait. */
    val rotation: StateFlow<Int> = _rotation

    private val _level = MutableStateFlow(LevelState())
    val level: StateFlow<LevelState> = _level

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity = FloatArray(3)

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return
            val cur = _rotation.value
            // hysteresis so the icons don't flicker around 45°
            val diff = abs(((o - cur + 540) % 360) - 180)
            if (diff > 55) _rotation.value = ((o + 45) / 90 * 90) % 360
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
        accel?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        listener.disable()
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        for (i in 0..2) gravity[i] = gravity[i] * 0.8f + e.values[i] * 0.2f
        val (x, y, z) = Triple(gravity[0], gravity[1], gravity[2])
        val norm = sqrt(x * x + y * y + z * z)
        val valid = norm > 1f && abs(z) / norm < 0.85f
        val horizon = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        val tilt = -horizon
        val rot = _rotation.value.toFloat()
        var dev = ((tilt - rot) % 360f + 540f) % 360f - 180f
        if (dev > 90f) dev -= 180f
        if (dev < -90f) dev += 180f
        _level.value = LevelState(horizon, dev, valid)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

object Orientations {
    /** JPEG_ORIENTATION per the Camera2 documentation. */
    fun jpeg(sensorOrientation: Int, deviceRotation: Int, front: Boolean): Int {
        val d = if (front) -deviceRotation else deviceRotation
        return (sensorOrientation + d + 360) % 360
    }

    /**
     * Rotation hint for clips rendered in portrait space. Front clips are un-mirrored unless the user
     * asked for mirroring, which flips the direction of rotation.
     */
    fun videoHint(deviceRotation: Int, front: Boolean, mirrored: Boolean): Int =
        if (front && !mirrored) (360 - deviceRotation) % 360 else deviceRotation
}
