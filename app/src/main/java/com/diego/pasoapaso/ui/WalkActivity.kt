package com.diego.pasoapaso.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.diego.pasoapaso.R
import com.diego.pasoapaso.databinding.ActivityWalkBinding
import com.diego.pasoapaso.tracking.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class WalkActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWalkBinding
    private val model: WalkViewModel by viewModels()
    private var map: GoogleMap? = null
    private var drawnPoints = -1
    private var currentLocation: LatLng? = null
    private var previewToken: CancellationTokenSource? = null
    private var firstCenter = true
    private var showingSavedBounds = false
    private var checkingSettings = false
    private var pendingStart = false
    private var screenActive = false

    private var notificationPrompted = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) toast(R.string.notifications_disabled)
        pendingStart = true
        startWhenVisible()
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) checkLocationSettings() else showPermissionHelp()
    }
    private val settingsResolution = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        checkingSettings = false
        if (result.resultCode == RESULT_OK && hasLocation()) {
            pendingStart = true
            startWhenVisible()
        } else toast(R.string.settings_required)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWalkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()
        notificationPrompted = savedInstanceState?.getBoolean("notification_prompted") ?: false
        model.initialize(savedInstanceState?.getString(EXTRA_ID) ?: intent.getStringExtra(EXTRA_ID))
        binding.toolbar.setNavigationOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        binding.startPause.setOnClickListener {
            if (model.ui.value.state == SessionState.RECORDING) model.pause() else requestStart()
        }
        binding.finish.setOnClickListener { model.save() }
        binding.locate.setOnClickListener {
            val points = model.ui.value.points
            if (model.ui.value.detail != null) fitRoute(points)
            else (points.lastOrNull()?.let { LatLng(it.fix.latitude, it.fix.longitude) } ?: currentLocation)?.let {
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 17f))
            }
        }
        binding.rename.setOnClickListener { rename() }
        binding.delete.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.delete_title).setMessage(R.string.delete_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> model.delete() }.show()
        }
        setupMap()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.ui.collect { state ->
                    render(state)
                    if (state.deleted) finish()
                    if (state.missing) {
                        toast(state.error ?: R.string.not_found)
                        finish()
                    } else state.error?.let { toast(it); model.clearError() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screenActive = true
        if (!hasLocation()) model.pause()
        startWhenVisible()
        if (hasLocation() && model.ui.value.detail == null && intent.getStringExtra(EXTRA_ID) == null) previewLocation()
    }

    override fun onPause() {
        screenActive = false
        super.onPause()
    }

    override fun onStop() {
        previewToken?.cancel()
        if (!isChangingConfigurations) {
            pendingStart = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notification_prompted", notificationPrompted)
        outState.putString(EXTRA_ID, model.ui.value.detail?.walk?.id ?: intent.getStringExtra(EXTRA_ID))
        super.onSaveInstanceState(outState)
    }

    private fun setupMap() {
        val key = getString(R.string.maps_api_key)
        if (key.isBlank() || key == "YOUR_MAPS_API_KEY") {
            binding.mapMessage.isVisible = true
            return
        }
        if (!servicesAvailable()) {
            binding.mapMessage.setText(R.string.map_services)
            binding.mapMessage.isVisible = true
            return
        }
        val fragment = (supportFragmentManager.findFragmentById(R.id.map_container) as? SupportMapFragment)
            ?: SupportMapFragment.newInstance(GoogleMapOptions().mapToolbarEnabled(false)).also {
                supportFragmentManager.beginTransaction().replace(R.id.map_container, it).commitNow()
            }
        fragment.getMapAsync { googleMap ->
            map = googleMap
            googleMap.uiSettings.isZoomControlsEnabled = true
            googleMap.uiSettings.isMyLocationButtonEnabled = false
            googleMap.setOnMapLoadedCallback { if (model.ui.value.detail != null) fitRoute(model.ui.value.points) }
            render(model.ui.value)
            if (hasLocation() && model.ui.value.detail == null) previewLocation()
        }
    }

    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun servicesAvailable() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

    private fun requestStart() {
        if (!servicesAvailable()) { toast(R.string.services_error); return }
        if (hasLocation()) checkLocationSettings()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun showPermissionHelp() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.permission_title).setMessage(R.string.location_required)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.show()
    }

    private fun checkLocationSettings() {
        if (checkingSettings) return
        checkingSettings = true
        LocationServices.getSettingsClient(this).checkLocationSettings(
            LocationSettingsRequest.Builder().addLocationRequest(WalkViewModel.locationRequest()).build()
        ).addOnSuccessListener {
            checkingSettings = false
            if (!isFinishing && !isDestroyed && screenActive) { pendingStart = true; startWhenVisible() }
        }.addOnFailureListener { error ->
            checkingSettings = false
            if (!isFinishing && !isDestroyed && screenActive) {
                if (error is ResolvableApiException) {
                    checkingSettings = true
                    settingsResolution.launch(IntentSenderRequest.Builder(error.resolution).build())
                } else toast(R.string.settings_required)
            }
        }
    }

    private fun startWhenVisible() {
        if (pendingStart && screenActive && hasLocation()) {
            pendingStart = false
            if (Build.VERSION.SDK_INT >= 33 && !notificationPrompted &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPrompted = true
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            model.start()
            previewLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun previewLocation() {
        if (!screenActive || !hasLocation() || !servicesAvailable()) return
        try {
            map?.isMyLocationEnabled = true
            previewToken?.cancel()
            val token = CancellationTokenSource().also { previewToken = it }
            LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(
                CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0).setDurationMillis(15_000).build(), token.token
            ).addOnSuccessListener { location ->
                if (location != null && screenActive && !token.token.isCancellationRequested) {
                    currentLocation = LatLng(location.latitude, location.longitude)
                    if (firstCenter && model.ui.value.detail == null) {
                        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation!!, 17f))
                        if (map != null) firstCenter = false
                    }
                }
            }.addOnFailureListener { /* El registro maneja sus propios errores; la vista previa es opcional. */ }
        } catch (_: SecurityException) { model.pause() }
    }

    private fun render(state: WalkUiState) {
        val saved = state.detail != null
        if (saved && intent.getStringExtra("field_owner") != null) {
            val store = com.diego.pasoapaso.field.FieldStore(this)
            if (store.user?.optString("id") == intent.getStringExtra("field_owner")) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, state.detail!!.walk.id))
            }
        }
        binding.toolbar.title = state.detail?.walk?.let { walkName(it) } ?: getString(R.string.walk_title)
        binding.distance.text = getString(R.string.metric_distance, kilometers(state.distanceMeters))
        binding.duration.text = elapsedText(state.durationMs)
        binding.status.setText(when {
            state.busy -> R.string.processing
            saved -> R.string.saved
            state.state == SessionState.PAUSED -> R.string.paused
            state.gpsStale -> R.string.gps_interrupted
            state.state == SessionState.RECORDING && state.points.isEmpty() -> R.string.gps_wait
            state.state == SessionState.RECORDING -> R.string.recording
            else -> R.string.ready
        })
        binding.accuracy.text = if (saved) dateText(state.detail!!.walk.startedAt) else state.accuracy?.let {
            if (it > 25) getString(R.string.gps_weak) else getString(R.string.gps_accuracy, it.toInt())
        } ?: ""
        binding.startPause.isVisible = !saved
        binding.finish.isVisible = !saved
        val segmented = state.points.map { it.segment }.distinct().size > 1
        binding.notice.setText(if (segmented) R.string.route_incomplete else R.string.foreground_notice)
        binding.notice.isVisible = !saved || segmented
        binding.rename.isVisible = saved
        binding.delete.isVisible = saved
        binding.startPause.isEnabled = !state.busy
        binding.finish.isEnabled = !state.busy && state.state != SessionState.IDLE
        binding.rename.isEnabled = !state.busy
        binding.delete.isEnabled = !state.busy
        binding.locate.isEnabled = map != null
        binding.startPause.setText(when (state.state) {
            SessionState.IDLE -> R.string.start
            SessionState.RECORDING -> R.string.pause
            SessionState.PAUSED -> R.string.resume
        })
        drawRoute(state.points)
        if (saved && !showingSavedBounds && map != null) {
            fitRoute(state.points)
            showingSavedBounds = true
        }
    }

    private fun drawRoute(points: List<RoutePoint>) {
        val googleMap = map ?: return
        if (drawnPoints == points.size) return
        drawnPoints = points.size
        googleMap.clear()
        if (points.isEmpty()) return
        points.groupBy { it.segment }.values.forEach { segment ->
            googleMap.addPolyline(PolylineOptions().addAll(segment.map { LatLng(it.fix.latitude, it.fix.longitude) })
                .color(ContextCompat.getColor(this, R.color.primary)).width(12f).geodesic(true))
        }
        val first = points.first().fix
        val last = points.last().fix
        googleMap.addMarker(MarkerOptions().position(LatLng(first.latitude, first.longitude)).title(getString(R.string.route_start))
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
        if (points.size > 1) googleMap.addMarker(MarkerOptions().position(LatLng(last.latitude, last.longitude)).title(getString(R.string.route_last_point)))
        if (firstCenter) {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), 17f))
            firstCenter = false
        }
    }

    private fun fitRoute(points: List<RoutePoint>) {
        val googleMap = map ?: return
        if (points.isEmpty()) return
        binding.mapContainer.post {
            if (binding.mapContainer.width == 0 || binding.mapContainer.height == 0) return@post
            val bounds = LatLngBounds.builder()
            points.forEach { bounds.include(LatLng(it.fix.latitude, it.fix.longitude)) }
            googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), (40 * resources.displayMetrics.density).toInt()))
        }
    }

    private fun leave() {
        val state = model.ui.value
        if (state.busy) return
        if (state.detail != null || state.state == SessionState.IDLE) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.leave_title).setMessage(R.string.leave_body)
            .setPositiveButton(R.string.save) { _, _ -> model.save() }
            .setNegativeButton(R.string.discard) { _, _ -> model.discard(); finish() }
            .setNeutralButton(R.string.continue_background) { _, _ -> finish() }.show()
    }

    private fun rename() {
        val detail = model.ui.value.detail ?: return
        val input = EditText(this).apply {
            setText(this@WalkActivity.walkName(detail.walk))
            hint = getString(R.string.name_hint)
            filters = arrayOf(InputFilter.LengthFilter(60))
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.rename).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) input.error = getString(R.string.name_hint)
                else { model.rename(name); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    companion object { const val EXTRA_ID = "walk_id" }
}
