package com.diego.pasoapaso.field

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.diego.pasoapaso.R
import com.diego.pasoapaso.tracking.*
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One explicit visit per foreground service. Never auto-starts at boot. */
class JourneyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = TrackingSession()
    private lateinit var store: FieldStore
    private var identity = ""
    private var visit = ""
    private var run = ""
    private var sequence = 0
    private var lastFix = 0L
    private var accuracy = 0f
    private var callback: LocationCallback? = null
    private var sender: Job? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        store = FieldStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("visit_journey", "Traslado compartido", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSharing(); return START_NOT_STICKY }
        if (visit.isNotEmpty() || intent == null) return START_NOT_STICKY
        visit = intent.getStringExtra("visit").orEmpty()
        run = intent.getStringExtra("run").orEmpty()
        if (visit.isEmpty() || run.isEmpty() || !store.loggedIn || store.coordinator) { stopSelf(); return START_NOT_STICKY }
        identity = store.endpoint + "|" + store.user?.optString("id")
        try {
            ServiceCompat.startForeground(this, 1901, notification("Compartiendo traslado con el coordinador"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            activeVisit = visit
            status.value = "Esperando GPS. Tu ubicación se compartirá con el coordinador."
            session.start(SystemClock.elapsedRealtime(), System.currentTimeMillis())
            startGps()
            sender = scope.launch {
                while (isActive) {
                    send(false)
                    delay(15000)
                }
            }
        } catch (_: RuntimeException) {
            status.value = "No se pudo iniciar el traslado. Revisa el permiso de ubicación."
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps() {
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { loc ->
                    if (session.pointCount >= 30000) { stopSharing(); return }
                    val now = SystemClock.elapsedRealtime()
                    val accepted = session.accept(GpsFix(loc.latitude, loc.longitude, loc.accuracy, loc.time,
                        loc.elapsedRealtimeNanos/1000000), now)
                    val last = session.points.lastOrNull()?.fix
                    val stationary = last != null && TrackingSession.haversine(last.latitude,last.longitude,loc.latitude,loc.longitude)<20
                    if ((accepted || stationary) && loc.hasAccuracy() && loc.accuracy in 0.01f..25f && now-loc.elapsedRealtimeNanos/1000000 in 0L..15000L) {
                        lastFix = loc.time; accuracy = loc.accuracy
                    }
                }
            }
        }
        callback = cb
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1000).setMaxUpdateDelayMillis(5000).build()
        LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(request, cb, Looper.getMainLooper())
            .addOnFailureListener { status.value = "No se recibió ubicación. Revisa el GPS."; stopSharing() }
    }

    private fun payload(stop: Boolean): JSONObject {
        val points = JSONArray()
        session.points.forEach { points.put(JSONObject().put("lat",it.fix.latitude).put("lon",it.fix.longitude).put("segment",it.segment)) }
        return JSONObject().put("command",if(stop) "stop" else "update").put("run_id",run)
            .put("sequence",++sequence).put("points",points).put("last_fix_at",lastFix).put("accuracy",accuracy.toDouble())
            .put("distance_m",session.distanceMeters).put("duration_ms",session.durationMs(SystemClock.elapsedRealtime()))
    }

    private suspend fun send(stop: Boolean) {
        if (!store.loggedIn || identity != store.endpoint+"|"+store.user?.optString("id")) {
            status.value = "Seguimiento detenido: cambió la sesión."; stopSelf(); return
        }
        val body = payload(stop)
        // Keep an encrypted final snapshot for explicit retry, scoped to endpoint and account.
        val envelope = JSONObject().put("identity",identity).put("visit",visit).put("body",body)
        var persisted = false
        try {
            val key = pendingKey(store,visit)
            check(withContext(Dispatchers.IO) {
                getSharedPreferences("journey_pending",0).edit().putString(key,store.encryptLocal(envelope.toString())).commit()
            }) { "No se pudo guardar la copia pendiente." }
            persisted = true
            store.request("POST","/visits/$visit/journey",body)
            getSharedPreferences("journey_pending",0).edit().remove(key).apply()
            status.value = if(stop) "Traslado detenido y enviado." else "Compartiendo · última entrega: ${java.time.LocalTime.now().withNano(0)}"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status.value = if(persisted) "Sin sincronizar: el recorrido se conserva en este teléfono."
                else "No se pudo guardar ni enviar esta actualización. Revisa el almacenamiento del teléfono."
            if(e is FieldApiException && e.status in listOf(401,403,409)) {
                status.value = "Seguimiento detenido: ${e.message}"; stopSelf()
            }
        }
    }

    private fun stopSharing() {
        if(stopping) return
        stopping = true
        callback?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        session.pause(SystemClock.elapsedRealtime())
        scope.launch {
            sender?.cancelAndJoin()
            try { send(true) } finally { stopSelf() }
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this,1901,Intent(this,FieldActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this,1902,Intent(this,JourneyService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"visit_journey").setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("NexoVisita · traslado compartido").setContentText(message).setOngoing(true)
            .setContentIntent(open).addAction(0,"Detener",stop).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }

    override fun onDestroy() {
        callback?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        scope.cancel(); activeVisit = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val STOP = "nexo.STOP_JOURNEY"
        var activeVisit: String? = null
            private set
        fun pendingKey(store: FieldStore, visit: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest((store.endpoint+"|"+store.user?.optString("id")+"|"+visit).toByteArray()).joinToString("") { "%02x".format(it) }
        val status = MutableStateFlow("El seguimiento está detenido.")
    }
}
