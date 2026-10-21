package com.diego.pasoapaso.field

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.diego.pasoapaso.R
import com.diego.pasoapaso.ui.applySystemInsets
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class FieldMapActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = intent.getBooleanExtra("pick", false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12) }
        setContentView(root); root.applySystemInsets()
        val title = TextView(this).apply { textSize = 20f; text = if (pick) "Mantén pulsado para ubicar al cliente" else intent.getStringExtra("title"); setPadding(8,16,8,16) }
        root.addView(title)
        val holder = FrameLayout(this).apply { id = R.id.nexo_map_container }
        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        val done = MaterialButton(this).apply { text = if(pick) "Usar ubicación" else "Volver" }
        root.addView(done)
        done.setOnClickListener { finish() }
        if (getString(R.string.maps_api_key).isBlank() || getString(R.string.maps_api_key) == "YOUR_MAPS_API_KEY") {
            title.text = "Configura MAPS_API_KEY en secrets.properties para mostrar Google Maps."
            return
        }
        val fragment = supportFragmentManager.findFragmentById(holder.id) as? SupportMapFragment ?: SupportMapFragment.newInstance().also {
            supportFragmentManager.beginTransaction().replace(holder.id, it).commitNow()
        }
        var selected = LatLng(savedInstanceState?.getDouble("lat") ?: intent.getDoubleExtra("lat", -12.0464),
            savedInstanceState?.getDouble("lon") ?: intent.getDoubleExtra("lon", -77.0428))
        fragment.getMapAsync { map ->
            map.uiSettings.isZoomControlsEnabled = true
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(selected, 15f))
            map.addMarker(MarkerOptions().position(selected).title(intent.getStringExtra("title") ?: "Cliente"))
            if (pick) {
                done.isEnabled = savedInstanceState?.getBoolean("selected") ?: false
                map.setOnMapLongClickListener {
                    selected = it; chosen = it
                    map.clear(); map.addMarker(MarkerOptions().position(it).title("Ubicación seleccionada"))
                    done.isEnabled = true
                }
                done.setOnClickListener { setResult(RESULT_OK, Intent().putExtra("lat", selected.latitude).putExtra("lon", selected.longitude)); finish() }
            }
            intent.getStringExtra("visit")?.let { id -> lifecycleScope.launch {
                try {
                    val route = FieldStore(this@FieldMapActivity).request("GET", "/visits/$id/route")
                    val array = route.getJSONArray("points")
                    val segments = linkedMapOf<Int, MutableList<LatLng>>()
                    val bounds = LatLngBounds.builder()
                    for (i in 0 until array.length()) {
                        val p = array.getJSONObject(i); val point = LatLng(p.getDouble("lat"), p.getDouble("lon"))
                        segments.getOrPut(p.getInt("segment")) { mutableListOf() }.add(point); bounds.include(point)
                    }
                    segments.values.forEach { map.addPolyline(PolylineOptions().addAll(it).width(10f).color(0xff2563eb.toInt())) }
                    title.text = "${intent.getStringExtra("title")}\n%.2f km · %d min".format(route.getDouble("distance_m") / 1000, route.getLong("duration_ms") / 60000)
                    map.setOnMapLoadedCallback { if (array.length() > 0) map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80)) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    title.text = "No se pudo cargar el recorrido. Regresa e intenta de nuevo."
                }
            } }
        }
        chosen = savedInstanceState?.takeIf { it.getBoolean("selected") }?.let { selected }
    }
    private var chosen: LatLng? = null
    override fun onSaveInstanceState(outState: Bundle) {
        chosen?.let { outState.putDouble("lat",it.latitude); outState.putDouble("lon",it.longitude); outState.putBoolean("selected",true) }
        super.onSaveInstanceState(outState)
    }
}
