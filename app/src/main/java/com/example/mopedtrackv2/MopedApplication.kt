package com.example.mopedtrackv2

import android.app.Application
import androidx.room.Room
import com.example.mopedtrackv2.data.local.AppDatabase
import com.example.mopedtrackv2.data.preferences.SettingsDataStoreImpl
import com.example.mopedtrackv2.data.repository.MopedRepositoryImpl
import com.example.mopedtrackv2.domain.repository.MopedRepository

class MopedApplication : Application() {
    lateinit var repository: MopedRepository

    override fun onCreate() {
        super.onCreate()
        org.maplibre.android.MapLibre.getInstance(this)
        
        // Configure MapLibre offline ambient cache limit (e.g. 500MB)
        org.maplibre.android.offline.OfflineManager.getInstance(this)
            .setMaximumAmbientCacheSize(
                500 * 1024 * 1024,
                object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                    override fun onSuccess() {}
                    override fun onError(message: String) {}
                }
            )

        val database = AppDatabase.getDatabase(this)
        val settingsDataStore = SettingsDataStoreImpl(this)

        repository = MopedRepositoryImpl(
            regionDao = database.regionDao(),
            roadSegmentDao = database.roadSegmentDao(),
            importedGpxFileDao = database.importedGpxFileDao(),
            debugTraceDao = database.debugTraceDao(),
            settingsDataStore = settingsDataStore
        )
    }
}
