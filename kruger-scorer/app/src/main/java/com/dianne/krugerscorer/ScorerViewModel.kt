package com.dianne.krugerscorer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dianne.krugerscorer.model.AnalysisResult
import com.dianne.krugerscorer.model.ShotDetection
import com.dianne.krugerscorer.vision.DetectionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val bitmap: Bitmap? = null,
    val result: AnalysisResult? = null,
    val sensitivity: Float = 0.55f,
    val analyzing: Boolean = false,
    val error: String? = null
)

class ScorerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var analyzeJob: Job? = null
    private var sourceBitmap: Bitmap? = null

    fun loadSample() {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                getApplication<Application>().assets.open("sample_target.jpg").use {
                    BitmapFactory.decodeStream(it)
                }
            }
            setBitmap(bmp)
        }
    }

    fun loadFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                if (bmp == null) {
                    _state.update { it.copy(error = "No se pudo leer la imagen") }
                } else {
                    setBitmap(bmp)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Error al cargar") }
            }
        }
    }

    fun loadBitmap(bitmap: Bitmap) {
        setBitmap(bitmap)
    }

    private fun setBitmap(bitmap: Bitmap) {
        sourceBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        sourceBitmap = bitmap
        _state.update {
            it.copy(bitmap = bitmap, result = null, error = null)
        }
        analyze(preserveToggles = false)
    }

    fun setSensitivity(value: Float) {
        _state.update { it.copy(sensitivity = value) }
        if (sourceBitmap != null) analyze(preserveToggles = true)
    }

    fun toggleDetection(id: Int) {
        _state.update { ui ->
            val result = ui.result ?: return@update ui
            val updated = result.detections.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            }
            ui.copy(result = result.copy(detections = updated))
        }
    }

    fun clear() {
        analyzeJob?.cancel()
        sourceBitmap?.recycle()
        sourceBitmap = null
        _state.value = UiState(sensitivity = _state.value.sensitivity)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun analyze(preserveToggles: Boolean) {
        val source = sourceBitmap ?: return
        val sensitivity = _state.value.sensitivity
        val previous = _state.value.result?.detections.orEmpty()
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _state.update { it.copy(analyzing = true, error = null) }
            try {
                val output = withContext(Dispatchers.Default) {
                    if (preserveToggles) {
                        DetectionPipeline.reanalyzeKeepingToggles(source, sensitivity, previous)
                    } else {
                        DetectionPipeline.analyze(source, sensitivity)
                    }
                }
                // Prefer the working (possibly downscaled) bitmap for overlay alignment.
                if (output.workingBitmap !== source && sourceBitmap === source) {
                    sourceBitmap = output.workingBitmap
                }
                _state.update {
                    it.copy(
                        bitmap = output.workingBitmap,
                        result = output.result,
                        analyzing = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        analyzing = false,
                        error = "No se pudo analizar la imagen. Prueba otra foto más frontal y con buena luz."
                    )
                }
            }
        }
    }
}
