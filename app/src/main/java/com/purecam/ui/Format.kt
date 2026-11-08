package com.purecam.ui

import android.util.Range
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object Fmt {
    fun shutter(ns: Long): String {
        if (ns <= 0) return "--"
        if (ns >= 300_000_000L) {
            val s = ns / 1e9
            return if (abs(s - s.roundToLong()) < 0.05) "${s.roundToLong()}\"" else String.format(Locale.US, "%.1f\"", s)
        }
        return "1/${(1e9 / ns).roundToInt()}"
    }

    fun focus(diopters: Float): String {
        if (diopters <= 0.01f) return "∞"
        val m = 1f / diopters
        return if (m < 1f) "${(m * 100).roundToInt()} cm" else String.format(Locale.US, "%.1f m", m)
    }

    fun ev(index: Int, step: Float): String {
        val v = index * step
        if (abs(v) < 0.05f) return "0.0"
        return (if (v > 0) "+" else "") + String.format(Locale.US, "%.1f", v)
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun zoom(z: Float): String {
        val s = String.format(Locale.US, "%.1f", z)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + "x"
    }

    private val ISO = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600,
    )

    fun isoStops(range: Range<Int>?): List<Int> {
        if (range == null) return emptyList()
        val list = ISO.filter { it in range.lower..range.upper }.toMutableList()
        if (list.firstOrNull() != range.lower) list.add(0, range.lower)
        if (list.lastOrNull() != range.upper) list.add(range.upper)
        return list.distinct()
    }

    private val SHUTTER_DEN = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 10, 8, 6, 5, 4, 3, 2)
    private val SHUTTER_SEC = listOf(0.5, 0.8, 1.0, 1.6, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 15.0, 20.0, 30.0)

    fun shutterStops(range: Range<Long>?): List<Long> {
        if (range == null) return emptyList()
        val all = SHUTTER_DEN.map { 1_000_000_000L / it } + SHUTTER_SEC.map { (it * 1e9).toLong() }
        return all.filter { it in range.lower..range.upper }
    }
}
