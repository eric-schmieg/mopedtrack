package com.example.mopedtrackv2.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

interface SettingsDataStore {
    val matchRadiusFlow: Flow<Int>
    val coverageThresholdFlow: Flow<Int>
    val debugOverlayEnabledFlow: Flow<Boolean>
    val showCompletedSegmentsFlow: Flow<Boolean>
    val showUncompletedSegmentsFlow: Flow<Boolean>
    val showGpxTracesFlow: Flow<Boolean>

    suspend fun setMatchRadius(radius: Int)
    suspend fun setCoverageThreshold(threshold: Int)
    suspend fun setDebugOverlayEnabled(enabled: Boolean)
    suspend fun setShowCompletedSegments(show: Boolean)
    suspend fun setShowUncompletedSegments(show: Boolean)
    suspend fun setShowGpxTraces(show: Boolean)
}

class SettingsDataStoreImpl(private val context: Context) : SettingsDataStore {
    private val MATCH_RADIUS = intPreferencesKey("match_radius_meters")
    private val COVERAGE_THRESHOLD = intPreferencesKey("coverage_threshold_percent")
    private val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay_enabled")
    private val SHOW_COMPLETED_SEGMENTS = booleanPreferencesKey("show_completed_segments")
    private val SHOW_UNCOMPLETED_SEGMENTS = booleanPreferencesKey("show_uncompleted_segments")
    private val SHOW_GPX_TRACES = booleanPreferencesKey("show_gpx_traces")

    override val matchRadiusFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[MATCH_RADIUS] ?: 15
    }

    override val coverageThresholdFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[COVERAGE_THRESHOLD] ?: 80
    }

    override val debugOverlayEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DEBUG_OVERLAY] ?: false
    }

    override val showCompletedSegmentsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_COMPLETED_SEGMENTS] ?: true
    }

    override val showUncompletedSegmentsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_UNCOMPLETED_SEGMENTS] ?: true
    }

    override val showGpxTracesFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_GPX_TRACES] ?: true
    }

    override suspend fun setMatchRadius(radius: Int) {
        context.dataStore.edit { preferences ->
            preferences[MATCH_RADIUS] = radius.coerceIn(5, 30)
        }
    }

    override suspend fun setCoverageThreshold(threshold: Int) {
        context.dataStore.edit { preferences ->
            preferences[COVERAGE_THRESHOLD] = threshold.coerceIn(50, 100)
        }
    }

    override suspend fun setDebugOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DEBUG_OVERLAY] = enabled
        }
    }

    override suspend fun setShowCompletedSegments(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_COMPLETED_SEGMENTS] = show
        }
    }

    override suspend fun setShowUncompletedSegments(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_UNCOMPLETED_SEGMENTS] = show
        }
    }

    override suspend fun setShowGpxTraces(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_GPX_TRACES] = show
        }
    }
}
