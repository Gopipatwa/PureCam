package com.purecam.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Keeps a recent GPS / network fix for photo geotagging. Uses the platform LocationManager only. */
class LocationTracker(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile private var last: Location? = null
    private var running = false

    private val listener = LocationListener { loc -> consider(loc) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || lm == null || !hasPermission()) return
        running = true
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { p -> runCatching { lm.allProviders.contains(p) && lm.isProviderEnabled(p) }.getOrDefault(false) }
        for (p in providers) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { consider(it) }
            runCatching { lm.requestLocationUpdates(p, 3000L, 5f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { lm?.removeUpdates(listener) }
    }

    /** A fix no older than 10 minutes, or null. */
    fun current(): Location? {
        val l = last ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs < 10 * 60_000) l else null
    }

    private fun consider(loc: Location) {
        val cur = last
        if (cur == null || loc.elapsedRealtimeNanos > cur.elapsedRealtimeNanos + 30_000_000_000L ||
            (loc.elapsedRealtimeNanos >= cur.elapsedRealtimeNanos && loc.accuracy <= cur.accuracy * 1.5f)
        ) {
            last = loc
        }
    }
}
