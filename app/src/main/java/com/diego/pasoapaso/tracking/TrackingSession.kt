package com.diego.pasoapaso.tracking

import kotlin.math.*

data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val recordedAt: Long,
    val elapsedMs: Long
)

data class RoutePoint(val fix: GpsFix, val segment: Int)
enum class SessionState { IDLE, RECORDING, PAUSED }

/** Motor independiente de Android. Solo recibe ubicaciones de la sesión actual. */
class TrackingSession {
    var state = SessionState.IDLE
        private set
    var startedAt = 0L
        private set
    var distanceMeters = 0.0
        private set
    private val route = mutableListOf<RoutePoint>()
    val points: List<RoutePoint> get() = route.toList()
    val pointCount get() = route.size
    private var activeSince = 0L
    private var accumulatedMs = 0L
    private var segment = -1
    private var anchor: GpsFix? = null
    private var lastSampleElapsed: Long? = null
    private var lastValidFix: GpsFix? = null

    /** Recovery never joins the old segment to the next fix or counts time while dead. */
    fun restore(saved: List<RoutePoint>, start: Long, distance: Double, duration: Long) {
        require(state == SessionState.IDLE && start > 0 && distance.isFinite() && distance >= 0 && duration >= 0)
        require(saved.all { it.segment >= 0 && it.fix.latitude.isFinite() && it.fix.longitude.isFinite() &&
            it.fix.latitude in -90.0..90.0 && it.fix.longitude in -180.0..180.0 })
        route.addAll(saved); startedAt=start; distanceMeters=distance; accumulatedMs=duration
        segment=saved.maxOfOrNull { it.segment } ?: -1
        state=SessionState.PAUSED
        anchor=null; lastSampleElapsed=null; lastValidFix=null
    }

    fun start(nowElapsed: Long, nowWall: Long) {
        if (state == SessionState.RECORDING) return
        if (state == SessionState.IDLE) startedAt = nowWall
        state = SessionState.RECORDING
        activeSince = nowElapsed
        segment++
        anchor = null
        lastSampleElapsed = null
        lastValidFix = null
    }

    fun pause(nowElapsed: Long) {
        if (state != SessionState.RECORDING) return
        accumulatedMs += (nowElapsed - activeSince).coerceAtLeast(0)
        state = SessionState.PAUSED
    }

    fun durationMs(nowElapsed: Long): Long = accumulatedMs +
        if (state == SessionState.RECORDING) (nowElapsed - activeSince).coerceAtLeast(0) else 0

    fun isGpsStale(nowElapsed: Long): Boolean = state == SessionState.RECORDING &&
        nowElapsed - (lastSampleElapsed ?: activeSince) > MAX_SIGNAL_GAP_MS

    /** Retorna true solo cuando un punto se incorpora al trazado. */
    fun accept(fix: GpsFix, nowElapsed: Long): Boolean {
        if (state != SessionState.RECORDING || fix.elapsedMs < activeSince) return false
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            !fix.accuracy.isFinite() || fix.accuracy <= 0f || fix.accuracy > 25f) return false
        if (nowElapsed - fix.elapsedMs !in 0L..15_000L) return false
        val sampleTime = lastSampleElapsed
        if (sampleTime != null && fix.elapsedMs <= sampleTime) return false
        if (sampleTime != null && fix.elapsedMs - sampleTime > MAX_SIGNAL_GAP_MS) {
            // No inventar una línea a través de un período sin señal.
            segment++
            anchor = null
            lastValidFix = null
        }
        // Compare speed with the latest valid fix, including stationary samples.
        // Using only the distance anchor can hide a jump after a long traffic stop.
        lastValidFix?.let { previousFix ->
            val seconds = (fix.elapsedMs - previousFix.elapsedMs) / 1000.0
            val meters = haversine(previousFix.latitude, previousFix.longitude, fix.latitude, fix.longitude)
            if (seconds <= 0 || meters / seconds > MAX_SPEED_METERS_PER_SECOND) return false
        }
        val previous = anchor
        if (previous != null) {
            val meters = haversine(previous.latitude, previous.longitude, fix.latitude, fix.longitude)
            val seconds = (fix.elapsedMs - previous.elapsedMs) / 1000.0
            if (seconds <= 0 || meters / seconds > MAX_SPEED_METERS_PER_SECOND) return false
            lastSampleElapsed = fix.elapsedMs
            lastValidFix = fix
            // Evita acumular pequeñas oscilaciones cuando el usuario está quieto.
            if (meters < max(5.0, min(previous.accuracy, fix.accuracy) * 0.75)) return false
            distanceMeters += meters
        }
        lastSampleElapsed = fix.elapsedMs
        lastValidFix = fix
        anchor = fix
        route.add(RoutePoint(fix, segment))
        return true
    }

    companion object {
        // Plausibility ceiling for terrestrial trips (216 km/h), not a walking limit.
        private const val MAX_SPEED_METERS_PER_SECOND = 60.0
        private const val MAX_SIGNAL_GAP_MS = 30_000L
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 6_371_000.0 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
        }
    }
}
