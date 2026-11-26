package com.example.mopedtrackv2.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mopedtrackv2.domain.model.StatsSummary
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.domain.repository.MopedRepository
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val summary: StatsSummary = StatsSummary(),
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val isLoading: Boolean = true
)

class StatsViewModel(
    repository: MopedRepository
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = combine(
        repository.getStatsSummaryFlow(),
        repository.unitSystemFlow
    ) { summary, unitSystem ->
        StatsUiState(
            summary = summary,
            unitSystem = unitSystem,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StatsUiState()
    )

    companion object {
        fun formatPercent(percent: Double): String {
            val safe = if (percent.isFinite()) percent.coerceIn(0.0, 100.0) else 0.0
            return String.format(Locale.US, "%.1f%%", safe)
        }

        fun formatCount(count: Number): String {
            return NumberFormat.getIntegerInstance(Locale.US).format(count)
        }
    }
}
