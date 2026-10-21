package com.diego.pasoapaso.field

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.diego.pasoapaso.R
import com.diego.pasoapaso.ui.applySystemInsets
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject

class JourneyActivity : AppCompatActivity() {
    private lateinit var store: FieldStore
    private lateinit var visit: String
    private var viewerIdentity = ""
    private lateinit var message: TextView
    private lateinit var delivery: TextView
    private var map: GoogleMap? = null
    private var marker: Marker? = null
    private var circle: Circle? = null
    private val lines = mutableListOf<Polyline>()
    private var route = listOf<LatLng>()
    private var bounds: LatLngBounds? = null
    private var animator: ValueAnimator? = null
    private var replay: Job? = null
    private var busy = false
    private var initial = true
    private var follow = true
    private var canReplay = false
    private lateinit var destination: LatLng
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startSharing()
        else message.text = "Activa la ubicación precisa para compartir el traslado."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FieldStore(this); visit = intent.getStringExtra("visit") ?: run { finish(); return }
        viewerIdentity=store.endpoint+"|"+store.user?.optString("id")
        destination = LatLng(intent.getDoubleExtra("lat",-12.0464),intent.getDoubleExtra("lon",-77.0428))
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)) }
        setContentView(root); root.applySystemInsets()
        root.addView(TextView(this).apply { text=intent.getStringExtra("title") ?: "Traslado de la visita"; textSize=20f })
        message = TextView(this).apply { text="Consultando traslado…"; textSize=14f }; root.addView(message)
        delivery = TextView(this).apply { textSize=12f }; root.addView(delivery)
        val holder = FrameLayout(this).apply { id=R.id.nexo_map_container }
        root.addView(holder,LinearLayout.LayoutParams(-1,0,1f))
        val scroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(controls); root.addView(scroll,LinearLayout.LayoutParams(-1,dp(200)))
        fun button(label: String, action: () -> Unit) {
            controls.addView(MaterialButton(this).apply { text=label; setOnClickListener { action() } })
        }
        if(!store.coordinator) {
            button("Cómo llegar · Google Maps") { navigate() }
            button("Iniciar traslado compartido") {
                MaterialAlertDialogBuilder(this).setTitle("Compartir este traslado")
                    .setMessage("El coordinador verá tu recorrido y la última ubicación recibida durante el traslado a esta visita, incluso con la pantalla bloqueada. Puedes detenerlo aquí o desde la notificación. Requiere GPS e Internet para actualizar al coordinador.\n\nSi ya había un traslado terminado para esta visita, iniciar otro reemplazará su trazado compartido.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Compartir") { _,_ ->
                        val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
                        if(Build.VERSION.SDK_INT>=33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
                        permissions.launch(requested.toTypedArray())
                    }.show()
            }
            button("Detener y enviar traslado") {
                if(JourneyService.activeVisit==visit) startService(Intent(this,JourneyService::class.java).setAction(JourneyService.STOP))
                else message.text="No hay un traslado activo en este teléfono."
            }
            button("Enviar traslado pendiente") { retryPending() }
            button("Descartar copia pendiente de esta visita") {
                if(JourneyService.activeVisit!=null) { message.text="Detén el traslado antes de descartar su copia." }
                else MaterialAlertDialogBuilder(this).setTitle("¿Descartar copia local?")
                    .setMessage("Se eliminará únicamente el traslado pendiente de esta visita en este teléfono. Los puntos que no llegaron al servidor no podrán recuperarse.")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Descartar") { _,_ ->
                        getSharedPreferences("journey_pending",0).edit().remove(JourneyService.pendingKey(store,visit)).apply()
                        message.text="Copia pendiente descartada."
                    }.show()
            }
        }
        button("Centrar recorrido y destino") { follow=true; bounds?.let { map?.animateCamera(CameraUpdateFactory.newLatLngBounds(it,dp(35))) } }
        button("Mapa / satélite") { map?.let { it.mapType=if(it.mapType==GoogleMap.MAP_TYPE_NORMAL) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL } }
        button("Reproducir recorrido terminado") { replayRoute() }
        button("Volver") { finish() }
        val key = getString(R.string.maps_api_key)
        if(key.isNotBlank() && key!="YOUR_MAPS_API_KEY") {
            val fragment = supportFragmentManager.findFragmentById(holder.id) as? SupportMapFragment ?: SupportMapFragment.newInstance().also {
                supportFragmentManager.beginTransaction().replace(holder.id,it).commitNow()
            }
            fragment.getMapAsync {
                map=it; it.uiSettings.isZoomControlsEnabled=true
                it.addMarker(MarkerOptions().position(destination).title("Destino de la visita").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
                it.moveCamera(CameraUpdateFactory.newLatLngZoom(destination,15f))
                it.setOnCameraMoveStartedListener { reason -> if(reason==GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) follow=false }
            }
        } else holder.addView(TextView(this).apply { text="Configura Maps SDK para Android en secrets.properties para ver el mapa."; setPadding(dp(16),dp(24),dp(16),dp(16)) })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { JourneyService.status.collect { if(!store.coordinator) delivery.text=it } }
                launch {
                    while(isActive) {
                        try { draw(store.request("GET","/visits/$visit/journey")) }
                        catch(e: Exception) { if(e is CancellationException) throw e; message.text="No se pudo actualizar. El mapa conserva la última información; no es una posición en vivo." }
                        delay(15000)
                    }
                }
            }
        }
    }

    private fun startSharing() {
        if(busy) return
        if(JourneyService.activeVisit!=null) { message.text="Ya compartes un traslado. Deténlo antes de iniciar otro."; return }
        if(getSharedPreferences("journey_pending",0).contains(JourneyService.pendingKey(store,visit))) {
            message.text="Hay un traslado pendiente. Envíalo o descarta su copia antes de iniciar otro para esta visita."; return
        }
        busy=true
        message.text="Conectando para iniciar el traslado…"
        lifecycleScope.launch {
            try {
                val result=store.request("POST","/visits/$visit/journey",JSONObject().put("command","start"))
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    message.text="Regresa a esta pantalla y vuelve a iniciar el traslado dentro de dos minutos."; return@launch
                }
                ContextCompat.startForegroundService(this@JourneyActivity,Intent(this@JourneyActivity,JourneyService::class.java)
                    .putExtra("visit",visit).putExtra("run",result.getString("run_id")))
                message.text="Traslado iniciado. Ahora puedes abrir Cómo llegar."
            } catch(e: Exception) { if(e is CancellationException) throw e; message.text=e.message ?: "No se pudo iniciar." }
            finally { busy=false }
        }
    }

    private fun navigate() {
        MaterialAlertDialogBuilder(this).setTitle("Cómo llegar")
            .setItems(arrayOf("Caminando","Vehículo","Transporte público")) { _, which ->
                val mode=arrayOf("walking","driving","transit")[which]
                val url="https://www.google.com/maps/dir/?api=1&destination=${destination.latitude},${destination.longitude}&travelmode=$mode&dir_action=navigate"
                try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
                catch(_: android.content.ActivityNotFoundException) { message.text="Instala Google Maps o un navegador para abrir las indicaciones." }
            }.show()
    }

    private fun draw(data: JSONObject) {
        if(replay?.isActive==true) return
        val state=data.getString("state")
        canReplay=state=="stopped"
        message.text=when(state) {
            "not_started" -> "El técnico aún no ha compartido este traslado."
            "live" -> "Traslado activo · recibido hace ${data.optInt("age_seconds")} s"
            "stale" -> "Sin ubicación reciente · última entrega hace ${data.optInt("age_seconds")} s"
            else -> "Traslado detenido · recorrido recibido"
        } + if(state=="not_started") "" else "\n%.2f km · %d min · GPS ±%d m".format(data.optDouble("distance_m")/1000,data.optLong("duration_ms")/60000,data.optDouble("accuracy",0.0).toInt())
        val array=data.getJSONArray("points")
        val segments=linkedMapOf<Int,MutableList<LatLng>>()
        for(i in 0 until array.length()) {
            val p=array.getJSONObject(i)
            segments.getOrPut(p.getInt("segment")){ mutableListOf() }.add(LatLng(p.getDouble("lat"),p.getDouble("lon")))
        }
        route=segments.values.flatten()
        val google=map ?: return
        if(replay?.isActive==true) return
        lines.forEach { it.remove() }; lines.clear()
        segments.values.forEach { points ->
            lines.add(google.addPolyline(PolylineOptions().addAll(points).width(dp(5).toFloat())
                .color(0xff2563eb.toInt()).startCap(RoundCap()).endCap(RoundCap()).jointType(JointType.ROUND)))
        }
        val builder=LatLngBounds.builder().include(destination)
        route.forEach { builder.include(it) }; bounds=builder.build()
        val last=route.lastOrNull()
        if(last==null) { marker?.remove(); marker=null; circle?.remove(); circle=null; return }
        moveMarker(last, state!="live")
        circle?.remove()
        circle=google.addCircle(CircleOptions().center(last).radius(data.optDouble("accuracy",0.0))
            .fillColor(0x222563eb).strokeColor(0x552563eb).strokeWidth(2f))
        if(initial) { google.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds!!,dp(35))); initial=false }
        else if(follow && state=="live") google.animateCamera(CameraUpdateFactory.newLatLng(last))
    }

    private fun moveMarker(target: LatLng, old: Boolean) {
        val google=map ?: return
        if(marker==null) marker=google.addMarker(MarkerOptions().position(target))
        val m=marker ?: return
        m.title=if(old) "Última posición registrada" else "Técnico · última posición recibida"
        m.setIcon(BitmapDescriptorFactory.defaultMarker(if(old) BitmapDescriptorFactory.HUE_ORANGE else BitmapDescriptorFactory.HUE_AZURE))
        animator?.cancel()
        val from=m.position
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=900
            addUpdateListener { val f=it.animatedValue as Float; m.position=LatLng(from.latitude+(target.latitude-from.latitude)*f,from.longitude+(target.longitude-from.longitude)*f) }
            start()
        }
    }

    private fun replayRoute() {
        if(!canReplay || route.size<2) { message.text="La reproducción está disponible cuando el traslado terminó y tiene puntos."; return }
        replay?.cancel()
        val copy=route.toList()
        replay=lifecycleScope.launch {
            message.text="Reproducción histórica · no representa la ubicación actual"
            val step=(copy.size/120).coerceAtLeast(1)
            for(i in copy.indices step step) { moveMarker(copy[i],true); delay(950) }
            moveMarker(copy.last(),true)
        }
    }

    private fun retryPending() {
        if(busy || JourneyService.activeVisit!=null) { message.text="Detén el traslado antes de reenviar."; return }
        busy=true
        lifecycleScope.launch {
            try {
                val prefs=getSharedPreferences("journey_pending",0)
                val raw=prefs.getString(JourneyService.pendingKey(store,visit),null) ?: error("No hay traslado pendiente de envío.")
                val env=JSONObject(store.decryptLocal(raw))
                require(env.getString("identity")==store.endpoint+"|"+store.user?.optString("id") && env.getString("visit")==visit) { "El pendiente corresponde a otra visita o cuenta." }
                val body=env.getJSONObject("body")
                body.put("command","stop").put("sequence",body.getInt("sequence")+1)
                store.request("POST","/visits/$visit/journey",body)
                prefs.edit().remove(JourneyService.pendingKey(store,visit)).apply(); message.text="Traslado pendiente recibido por el servidor."
            } catch(e: Exception) { if(e is CancellationException) throw e; message.text=e.message ?: "No se pudo reenviar." }
            finally { busy=false }
        }
    }

    override fun onResume() {
        super.onResume()
        if(::store.isInitialized && (!store.loggedIn || viewerIdentity!=store.endpoint+"|"+store.user?.optString("id"))) {
            map?.clear(); finish()
        }
    }
    override fun onStop() { replay?.cancel(); animator?.cancel(); super.onStop() }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
}
