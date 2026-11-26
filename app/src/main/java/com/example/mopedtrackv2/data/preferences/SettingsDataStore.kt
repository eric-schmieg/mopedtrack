package com.example.mopedtrackv2.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.mopedtrackv2.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SavedCameraPosition(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0
)

interface SettingsDataStore {
    val matchRadiusFlow: Flow<Int>
    val coverageThresholdFlow: Flow<Int>
    val debugOverlayEnabledFlow: Flow<Boolean>
    val showCompletedSegmentsFlow: Flow<Boolean>
    val showUncompletedSegmentsFlow: Flow<Boolean>
    val showGpxTracesFlow: Flow<Boolean>
    val hiddenRegionIdsFlow: Flow<Set<String>>
    val savedCameraPositionFlow: Flow<SavedCameraPosition?>
    val pollingIntervalMsFlow: Flow<Long>
    val minDisplacementMetersFlow: Flow<Float>
    val gpxExportTreeUriFlow: Flow<String?>
    val unitSystemFlow: Flow<UnitSystem>
    val weeklyAutoRefreshFlow: Flow<Boolean>

    suspend fun setMatchRadius(radius: Int)
    suspend fun setCoverageThreshold(threshold: Int)
    suspend fun setDebugOverlayEnabled(enabled: Boolean)
    suspend fun setShowCompletedSegments(show: Boolean)
    suspend fun setShowUncompletedSegments(show: Boolean)
    suspend fun setShowGpxTraces(show: Boolean)
    suspend fun setRegionVisibility(regionId: String, isVisible: Boolean)
    suspend fun setHiddenRegionIds(regionIds: Set<String>)
    suspend fun setSavedCameraPosition(lat: Double, lon: Double, zoom: Double, bearing: Double = 0.0, tilt: Double = 0.0)
    suspend fun setPollingIntervalMs(intervalMs: Long)
    suspend fun setMinDisplacementMeters(meters: Float)
    suspend fun setGpxExportTreeUri(uri: String?)
    suspend fun setUnitSystem(unitSystem: UnitSystem)
    suspend fun setWeeklyAutoRefresh(enabled: Boolean)
}

class SettingsDataStoreImpl(private val context: Context) : SettingsDataStore {
    private val MATCH_RADIUS = intPreferencesKey("match_radius_meters")
    private val COVERAGE_THRESHOLD = intPreferencesKey("coverage_threshold_percent")
    private val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay_enabled")
    private val SHOW_COMPLETED_SEGMENTS = booleanPreferencesKey("show_completed_segments")
    private val SHOW_UNCOMPLETED_SEGMENTS = booleanPreferencesKey("show_uncompleted_segments")
    private val SHOW_GPX_TRACES = booleanPreferencesKey("show_gpx_traces")
    private val HIDDEN_REGION_IDS = stringSetPreferencesKey("hidden_region_ids")
    private val LAST_CAMERA_LAT = doublePreferencesKey("last_camera_lat")
    private val LAST_CAMERA_LON = doublePreferencesKey("last_camera_lon")
    private val LAST_CAMERA_ZOOM = doublePreferencesKey("last_camera_zoom")
    private val LAST_CAMERA_BEARING = doublePreferencesKey("last_camera_bearing")
    private val LAST_CAMERA_TILT = doublePreferencesKey("last_camera_tilt")
    private val POLLING_INTERVAL_MS = longPreferencesKey("polling_interval_ms")
    private val MIN_DISPLACEMENT_METERS = floatPreferencesKey("min_displacement_meters")
    private val GPX_EXPORT_TREE_URI = stringPreferencesKey("gpx_export_tree_uri")
    private val UNIT_SYSTEM = stringPreferencesKey("unit_system")
    private val WEEKLY_AUTO_REFRESH = booleanPreferencesKey("weekly_auto_refresh")

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

    override val hiddenRegionIdsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[HIDDEN_REGION_IDS] ?: emptySet()
    }

    override val savedCameraPositionFlow: Flow<SavedCameraPosition?> = context.dataStore.data.map { preferences ->
        val lat = preferences[LAST_CAMERA_LAT]
        val lon = preferences[LAST_CAMERA_LON]
        val zoom = preferences[LAST_CAMERA_ZOOM]
        val bearing = preferences[LAST_CAMERA_BEARING] ?: 0.0
        val tilt = preferences[LAST_CAMERA_TILT] ?: 0.0

        if (lat != null && lon != null && zoom != null) {
            SavedCameraPosition(lat, lon, zoom, bearing, tilt)
        } else {
            null
        }
    }

    override val pollingIntervalMsFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[POLLING_INTERVAL_MS] ?: 1000L
    }

    override val minDisplacementMetersFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[MIN_DISPLACEMENT_METERS] ?: 2.0f
    }

    override val gpxExportTreeUriFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[GPX_EXPORT_TREE_URI]
    }

    override val unitSystemFlow: Flow<UnitSystem> = context.dataStore.data.map { preferences ->
        val raw = preferences[UNIT_SYSTEM]
        UnitSystem.entries.firstOrNull { it.name == raw } ?: UnitSystem.IMPERIAL
    }

    override val weeklyAutoRefreshFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WEEKLY_AUTO_REFRESH] ?: false
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

    override suspend fun setRegionVisibility(regionId: String, isVisible: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[HIDDEN_REGION_IDS].orEmpty()
            preferences[HIDDEN_REGION_IDS] = if (isVisible) {
                current - regionId
            } else {
                current + regionId
            }
        }
    }

    override suspend fun setHiddenRegionIds(regionIds: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[HIDDEN_REGION_IDS] = regionIds.toSet()
        }
    }

    override suspend fun setSavedCameraPosition(lat: Double, lon: Double, zoom: Double, bearing: Double, tilt: Double) {
        context.dataStore.edit { preferences ->
            preferences[LAST_CAMERA_LAT] = lat
            preferences[LAST_CAMERA_LON] = lon
            preferences[LAST_CAMERA_ZOOM] = zoom
            preferences[LAST_CAMERA_BEARING] = bearing
            preferences[LAST_CAMERA_TILT] = tilt
        }
    }

    override suspend fun setPollingIntervalMs(intervalMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[POLLING_INTERVAL_MS] = intervalMs.coerceIn(500L, 5000L)
        }
    }

    override suspend fun setMinDisplacementMeters(meters: Float) {
        context.dataStore.edit { preferences ->
            preferences[MIN_DISPLACEMENT_METERS] = meters.coerceIn(0.0f, 15.0f)
        }
    }

    override suspend fun setGpxExportTreeUri(uri: String?) {
        context.dataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(GPX_EXPORT_TREE_URI)
            } else {
                preferences[GPX_EXPORT_TREE_URI] = uri
            }
        }
    }

    override suspend fun setUnitSystem(unitSystem: UnitSystem) {
        context.dataStore.edit { preferences ->
            preferences[UNIT_SYSTEM] = unitSystem.name
        }
    }

    override suspend fun setWeeklyAutoRefresh(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WEEKLY_AUTO_REFRESH] = enabled
        }
    }
}
