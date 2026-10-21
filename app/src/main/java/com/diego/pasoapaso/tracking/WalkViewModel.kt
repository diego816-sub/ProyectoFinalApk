package com.diego.pasoapaso.tracking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.diego.pasoapaso.R
import com.diego.pasoapaso.data.WalkDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WalkViewModel(application: Application) : AndroidViewModel(application) {
    private val database = WalkDatabase.get(application)
    private val recorder = WalkRecorder.get(application)
    private val mutableState = MutableStateFlow(WalkUiState())
    val ui = mutableState.asStateFlow()
    private var initialized = false
    private var recordingScreen = false
    private var recordingUpdates: Job? = null

    fun initialize(id: String?) {
        if (initialized) return
        initialized = true
        recordingScreen = id == null
        if (recordingScreen) {
            recorder.prepare()
            mutableState.value = recorder.ui.value
            recordingUpdates = viewModelScope.launch {
                recorder.ui.collect { state ->
                    mutableState.value = state
                    if (state.detail != null) {
                        recordingScreen = false
                        // El detalle guardado pasa a pertenecer a esta pantalla.
                        currentCoroutineContext().cancel()
                    }
                }
            }
            return
        }
        mutableState.value = ui.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { database.detail(id!!) }
                mutableState.value = if (detail == null) ui.value.copy(busy = false, missing = true)
                else WalkUiState(points = detail.points, distanceMeters = detail.walk.distanceMeters,
                    durationMs = detail.walk.durationMs, detail = detail)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = ui.value.copy(busy = false, missing = true, error = R.string.load_error)
            }
        }
    }

    fun start() { if (recordingScreen) recorder.requestStart() }
    fun pause() { if (recordingScreen) recorder.pause() }
    fun save() { if (recordingScreen) recorder.save() }
    fun discard() { if (recordingScreen) recorder.discard() }

    fun rename(name: String) {
        val detail = ui.value.detail ?: return
        if (ui.value.busy) return
        mutableState.value = ui.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { database.rename(detail.walk.id, name) }
                mutableState.value = ui.value.copy(busy = false, detail = detail.copy(walk = detail.walk.copy(name = name.trim())))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = ui.value.copy(busy = false, error = R.string.rename_error)
            }
        }
    }

    fun delete() {
        val detail = ui.value.detail ?: return
        if (ui.value.busy) return
        mutableState.value = ui.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { database.delete(detail.walk.id) }
                mutableState.value = ui.value.copy(busy = false, deleted = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = ui.value.copy(busy = false, error = R.string.delete_error)
            }
        }
    }

    fun clearError() {
        if (recordingScreen) recorder.clearError()
        else mutableState.value = ui.value.copy(error = null)
    }

    companion object { fun locationRequest() = WalkRecorder.locationRequest() }
}
