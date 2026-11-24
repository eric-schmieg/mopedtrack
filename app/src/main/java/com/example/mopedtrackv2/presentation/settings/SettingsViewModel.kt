package com.example.mopedtrackv2.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: MopedRepository) : ViewModel() {

    val matchRadius = repository.matchRadiusFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 15
    )

    val coverageThreshold = repository.coverageThresholdFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 80
    )

    val debugOverlayEnabled = repository.debugOverlayEnabledFlow.stateIn(
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
}
