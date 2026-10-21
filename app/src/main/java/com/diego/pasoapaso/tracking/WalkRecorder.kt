package com.diego.pasoapaso.tracking

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.Looper
import android.os.SystemClock
import com.diego.pasoapaso.R
import com.diego.pasoapaso.data.Walk
import com.diego.pasoapaso.data.WalkDatabase
import com.diego.pasoapaso.data.WalkDetail
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class WalkUiState(
    val state: SessionState = SessionState.IDLE,
    val points: List<RoutePoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val durationMs: Long = 0,
    val accuracy: Float? = null,
    val gpsStale: Boolean = false,
    val detail: WalkDetail? = null,
    val busy: Boolean = false,
    val error: Int? = null,
    val missing: Boolean = false,
    val deleted: Boolean = false
)

/** Sesión compartida por la pantalla y el servicio; no depende de una Activity. */
class WalkRecorder private constructor(private val application: Application) {
    private val database = WalkDatabase.get(application)
    private var session = TrackingSession()
    private val client = LocationServices.getFusedLocationProviderClient(application)
    private val mutableState = MutableStateFlow(WalkUiState())
    val ui = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation = 0
    private var callback: LocationCallback? = null
    private var ticker: Job? = null
    private var sessionId = UUID.randomUUID().toString()
    private var sessionOwner: String? = null
    private var checkpoint: RouteCheckpoint? = null
    private var checkpointJob: Job? = null
    private var lastCheckpoint = 0L

    fun prepare() {
        if (ui.value.detail != null && !ui.value.busy) reset()
        if (ui.value.busy || session.state != SessionState.IDLE) return
        val ownerStore=com.diego.pasoapaso.field.FieldStore(application)
        val identity=ownerStore.endpoint+"|"+ownerStore.user?.optString("id").orEmpty()
        val saved=RouteCheckpoint(application).also { checkpoint=it }
        mutableState.value=ui.value.copy(busy=true)
        scope.launch {
            try {
                val data=withContext(Dispatchers.IO) { saved.read() }
                if(identity!=ownerStore.endpoint+"|"+ownerStore.user?.optString("id").orEmpty()) return@launch
                if(data!=null) {
                    val existing=withContext(Dispatchers.IO) { database.detail(data.getString("id")) }
                    if(existing==null) {
                        session.restore(RouteCheckpoint.points(data),data.getLong("started"),data.getDouble("distance"),data.getLong("duration"))
                        sessionId=data.getString("id"); sessionOwner=data.optString("owner").takeIf { it!="null" && it.isNotBlank() }
                        update(pointsChanged=true)
                    } else {
                        com.diego.pasoapaso.field.FieldStore(application).ownRoute(existing.walk.id,data.optString("owner").takeIf { it!="null" })
                        withContext(Dispatchers.IO) { saved.clear() }
                    }
                }
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                mutableState.value=ui.value.copy(error=R.string.load_error)
            } finally { mutableState.value=ui.value.copy(busy=false) }
        }
    }

    fun requestStart() {
        if (ui.value.busy || ui.value.detail != null || session.state == SessionState.RECORDING) return
        if (session.state == SessionState.IDLE) {
            sessionOwner = com.diego.pasoapaso.field.FieldStore(application).user?.optString("id")
            checkpoint=RouteCheckpoint(application)
        }
        mutableState.value = ui.value.copy(busy = true, error = null)
        try {
            ContextCompat.startForegroundService(application,
                Intent(application, WalkTrackingService::class.java).setAction(WalkTrackingService.ACTION_START))
        } catch (_: RuntimeException) {
            serviceFailed()
        }
    }

    fun serviceFailed() {
        pause()
        mutableState.value = ui.value.copy(busy = false, error = R.string.background_start_error)
    }

    @SuppressLint("MissingPermission") // Solo lo invoca el servicio después de entrar en primer plano.
    internal fun start() {
        mutableState.value = ui.value.copy(busy = false)
        if (ui.value.busy || ui.value.detail != null || ui.value.missing || session.state == SessionState.RECORDING) return
        session.start(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        val requestGeneration = ++generation
        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (requestGeneration != generation || session.state != SessionState.RECORDING) return
                var changed = false
                result.locations.forEach { location ->
                    changed = session.accept(GpsFix(location.latitude, location.longitude, location.accuracy,
                        location.time, location.elapsedRealtimeNanos / 1_000_000), SystemClock.elapsedRealtime()) || changed
                }
                update(pointsChanged = changed, accuracy = result.lastLocation?.accuracy)
            }
        }
        callback = newCallback
        update()
        try {
            client.requestLocationUpdates(locationRequest(), newCallback, Looper.getMainLooper())
                .addOnFailureListener {
                    if (requestGeneration == generation) {
                        pause()
                        mutableState.value = ui.value.copy(error = R.string.location_error)
                    }
                }
        } catch (_: SecurityException) {
            pause()
            mutableState.value = ui.value.copy(error = R.string.location_required)
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && session.state == SessionState.RECORDING) {
                update()
                if(SystemClock.elapsedRealtime()-lastCheckpoint>=10000) persistCheckpoint()
                delay(1000)
            }
        }
    }

    fun pause() {
        generation++
        session.pause(SystemClock.elapsedRealtime())
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        ticker?.cancel()
        if (ui.value.detail == null) { update(); persistCheckpoint() }
    }

    private fun persistCheckpoint() {
        if(session.state==SessionState.IDLE || checkpointJob?.isActive==true) return
        val target=checkpoint ?: return
        val ticket=target.ticket()
        val data=RouteCheckpoint.encode(sessionId,sessionOwner,session,SystemClock.elapsedRealtime())
        lastCheckpoint=SystemClock.elapsedRealtime()
        checkpointJob=scope.launch {
            try { withContext(Dispatchers.IO) { target.write(ticket,data) } }
            catch(e: Exception) { if(e is CancellationException) throw e; mutableState.value=ui.value.copy(error=R.string.save_error) }
        }
    }

    private fun update(pointsChanged: Boolean = false, accuracy: Float? = ui.value.accuracy) {
        mutableState.value = ui.value.copy(state = session.state,
            points = if (pointsChanged) session.points else ui.value.points,
            distanceMeters = session.distanceMeters,
            gpsStale = session.isGpsStale(SystemClock.elapsedRealtime()),
            durationMs = session.durationMs(SystemClock.elapsedRealtime()), accuracy = accuracy)
    }

    fun save() {
        if (ui.value.busy || ui.value.detail != null) return
        pause()
        if (session.pointCount < 2 || session.distanceMeters < 5.0) {
            mutableState.value = ui.value.copy(error = R.string.need_points)
            return
        }
        // El título automático se localiza al mostrarlo, sin modificar nombres personales.
        val detail = WalkDetail(Walk(sessionId, "", session.startedAt,
            System.currentTimeMillis(), session.durationMs(SystemClock.elapsedRealtime()), session.distanceMeters), session.points)
        mutableState.value = ui.value.copy(busy = true, error = null)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { database.save(detail) }
                com.diego.pasoapaso.field.FieldStore(application).ownRoute(detail.walk.id, sessionOwner)
                withContext(Dispatchers.IO) { checkpoint?.clear() }
                mutableState.value = ui.value.copy(busy = false, detail = detail)
                application.stopService(Intent(application, WalkTrackingService::class.java))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = ui.value.copy(busy = false, error = R.string.save_error)
            }
        }
    }

    fun discard() {
        if (ui.value.busy) return
        pause()
        application.stopService(Intent(application, WalkTrackingService::class.java))
        checkpoint?.clear()
        reset()
    }

    private fun reset() {
        session = TrackingSession()
        checkpoint=null
        sessionId = UUID.randomUUID().toString()
        sessionOwner = null
        mutableState.value = WalkUiState()
    }

    fun clearError() { mutableState.value = ui.value.copy(error = null) }

    companion object {
        @Volatile private var instance: WalkRecorder? = null
        fun get(application: Application): WalkRecorder = instance ?: synchronized(this) {
            instance ?: WalkRecorder(application).also { instance = it }
        }
        fun locationRequest(): LocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(1000).setMaxUpdateDelayMillis(0)
            .setMaxUpdateAgeMillis(0).setWaitForAccurateLocation(true).build()
    }
}
