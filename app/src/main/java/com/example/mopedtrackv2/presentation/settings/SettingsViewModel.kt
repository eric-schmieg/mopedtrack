package com.example.mopedtrackv2.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: MopedRepository,
    private val onWeeklyAutoRefreshChanged: ((Boolean) -> Unit)? = null
) : ViewModel() {

    private val _isCopyingTracesToExternal = MutableStateFlow(false)
    val isCopyingTracesToExternal: StateFlow<Boolean> = _isCopyingTracesToExternal.asStateFlow()

    private val _copyProgressText = MutableStateFlow<String?>(null)
    val copyProgressText: StateFlow<String?> = _copyProgressText.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    val matchRadius = repository.matchRadiusFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 15
    )

    val coverageThreshold = repository.coverageThresholdFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 80
    )

    val debugOverlayEnabled = repository.debugOverlayEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val unitSystem = repository.unitSystemFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), UnitSystem.IMPERIAL
    )

    val pollingIntervalMs = repository.pollingIntervalMsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1000L
    )

    val minDisplacementMeters = repository.minDisplacementMetersFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 2.0f
    )

    val gpxExportTreeUri = repository.gpxExportTreeUriFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    val weeklyAutoRefresh = repository.weeklyAutoRefreshFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    fun setMatchRadius(radius: Int) {
        viewModelScope.launch {
            repository.setMatchRadius(radius)
        }
    }

    fun setCoverageThreshold(threshold: Int) {
        viewModelScope.launch {
            repository.setCoverageThreshold(threshold)
        }
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDebugOverlayEnabled(enabled)
        }
    }

    fun setUnitSystem(unitSystem: UnitSystem) {
        viewModelScope.launch {
            repository.setUnitSystem(unitSystem)
        }
    }

    fun setPollingIntervalMs(intervalMs: Long) {
        viewModelScope.launch {
            repository.setPollingIntervalMs(intervalMs)
        }
    }

    fun setMinDisplacementMeters(meters: Float) {
        viewModelScope.launch {
            repository.setMinDisplacementMeters(meters)
        }
    }

    fun setGpxExportTreeUri(uri: String?) {
        viewModelScope.launch {
            val normalized = uri?.takeIf { it.isNotBlank() }
            repository.setGpxExportTreeUri(normalized)
            if (normalized == null) {
                _toastMessages.tryEmit("External trace folder cleared")
                return@launch
            }

            _isCopyingTracesToExternal.value = true
            _copyProgressText.value = "Copying traces to external folder..."
            try {
                val summary = repository.copyAllTracesToExternalFolder(normalized) { current, total ->
                    _copyProgressText.value = "Copying traces ($current/$total)..."
                }
                val message = when {
                    !summary.folderWritable -> "Selected folder is not writable"
                    summary.totalTraces == 0 -> "External trace folder updated"
                    summary.failedCount > 0 ->
                        "Copied ${summary.copiedCount} traces (${summary.failedCount} failed)"
                    summary.copiedCount > 0 && summary.skippedExistingCount > 0 ->
                        "Copied ${summary.copiedCount} traces (${summary.skippedExistingCount} already in folder)"
                    summary.copiedCount > 0 ->
                        "Copied ${summary.copiedCount} trace${if (summary.copiedCount == 1) "" else "s"} to external folder"
                    else ->
                        "All ${summary.skippedExistingCount} traces already in external folder"
                }
                _toastMessages.tryEmit(message)
            } catch (_: Exception) {
                _toastMessages.tryEmit("Failed to copy traces to external folder")
            } finally {
                _isCopyingTracesToExternal.value = false
                _copyProgressText.value = null
            }
        }
    }

    fun setWeeklyAutoRefresh(enabled: Boolean) {
        viewModelScope.launch {
            repository.setWeeklyAutoRefresh(enabled)
            onWeeklyAutoRefreshChanged?.invoke(enabled)
        }
    }
}
