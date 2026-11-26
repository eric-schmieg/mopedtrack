package com.example.mopedtrackv2.presentation.pins

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.domain.pins.OsmPinExportFormat
import com.example.mopedtrackv2.domain.pins.OsmPinExporter
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OsmPinExportPayload(
    val fileName: String,
    val content: String,
    val format: OsmPinExportFormat,
    val pinCount: Int
)

class OsmPinsViewModel(
    private val repository: MopedRepository
) : ViewModel() {

    val pins: StateFlow<List<OsmPinEntity>> = repository.getAllOsmPinsFlow()
        .map { list -> list.sortedByDescending { it.createdAt } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    fun togglePinResolved(pin: OsmPinEntity) {
        viewModelScope.launch {
            try {
                val updated = pin.copy(isResolved = !pin.isResolved)
                repository.updateOsmPin(updated)
                _snackbarMessages.tryEmit(
                    if (updated.isResolved) "Pin marked resolved" else "Pin marked unresolved"
                )
            } catch (e: Exception) {
                Log.e("OsmPinsViewModel", "Failed to update pin ${pin.pinId}", e)
                _snackbarMessages.tryEmit("Failed to update pin")
            }
        }
    }

    fun deletePin(pin: OsmPinEntity) {
        viewModelScope.launch {
            try {
                repository.deleteOsmPin(pin.pinId)
                _snackbarMessages.tryEmit("Pin deleted")
            } catch (e: Exception) {
                Log.e("OsmPinsViewModel", "Failed to delete pin ${pin.pinId}", e)
                _snackbarMessages.tryEmit("Failed to delete pin")
            }
        }
    }

    fun buildExportPayload(
        format: OsmPinExportFormat,
        unresolvedOnly: Boolean = false
    ): OsmPinExportPayload? {
        val targetPins = if (unresolvedOnly) {
            pins.value.filter { !it.isResolved }
        } else {
            pins.value
        }
        if (targetPins.isEmpty()) {
            _snackbarMessages.tryEmit("No pins to export")
            return null
        }
        return OsmPinExportPayload(
            fileName = OsmPinExporter.buildFileName(format),
            content = OsmPinExporter.export(targetPins, format),
            format = format,
            pinCount = targetPins.size
        )
    }

    fun notifyMessage(message: String) {
        _snackbarMessages.tryEmit(message)
    }
}
