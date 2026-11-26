package com.example.mopedtrackv2.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.mopedtrackv2.data.worker.RegionRefreshWorker
import com.example.mopedtrackv2.domain.repository.MopedRepository
import com.example.mopedtrackv2.presentation.home.HomeViewModel
import com.example.mopedtrackv2.presentation.importgpx.ImportGpxViewModel
import com.example.mopedtrackv2.presentation.pins.OsmPinsViewModel
import com.example.mopedtrackv2.presentation.regions.ManageRegionsViewModel
import com.example.mopedtrackv2.presentation.settings.SettingsViewModel
import com.example.mopedtrackv2.presentation.stats.StatsViewModel

class ViewModelFactory(
    private val repository: MopedRepository,
    private val context: Context? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ManageRegionsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ManageRegionsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val appContext = context?.applicationContext
            return SettingsViewModel(
                repository = repository,
                onWeeklyAutoRefreshChanged = appContext?.let { ctx ->
                    { enabled ->
                        if (enabled) {
                            RegionRefreshWorker.enqueuePeriodicRefresh(ctx)
                        } else {
                            RegionRefreshWorker.cancelPeriodicRefresh(ctx)
                        }
                    }
                }
            ) as T
        }
        if (modelClass.isAssignableFrom(ImportGpxViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            if (context == null) throw IllegalArgumentException("Context required for ImportGpxViewModel")
            return ImportGpxViewModel(repository, context) as T
        }
        if (modelClass.isAssignableFrom(OsmPinsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OsmPinsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(StatsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
