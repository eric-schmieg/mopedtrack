package com.example.mopedtrackv2.domain.repository

import com.example.mopedtrackv2.data.gpx.ExternalTraceCopySummary
import com.example.mopedtrackv2.data.gpx.GpxBatchStorageSession
import com.example.mopedtrackv2.data.gpx.NoOpGpxBatchStorageSession
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SavedCameraPosition
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RegionRefreshResult
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.model.StatsSummary
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MopedRepository {
    fun getAllRegions(): Flow<List<Region>>
    suspend fun getRegionById(regionId: String): Region?
    fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegment>>
    fun getRoadSegmentsInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<RoadSegment>>
    suspend fun getRoadSegmentById(segmentId: String): RoadSegment?
    suspend fun toggleSegmentCompletion(segmentId: String, isCompleted: Boolean, completedAt: Long?)
    suspend fun searchRegions(query: String): List<Region>
    suspend fun importRegion(region: Region, onProgress: (Float, String) -> Unit = { _, _ -> })
    suspend fun refreshRegion(regionId: String, onProgress: (Float, String) -> Unit = { _, _ -> }): RegionRefreshResult
    suspend fun deleteRegion(regionId: String)
    suspend fun clearAllData()

    // Settings
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

    // GPX & Debug Trace
    fun getImportedFilesFlow(): Flow<List<ImportedGpxFileEntity>>
    suspend fun getImportedFileByHash(hash: String): ImportedGpxFileEntity?
    suspend fun insertImportedFile(file: ImportedGpxFileEntity)
    suspend fun deleteImportedFile(hash: String)
    suspend fun openGpxStorageSession(): GpxBatchStorageSession = NoOpGpxBatchStorageSession
    suspend fun copyAllTracesToExternalFolder(
        treeUriString: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ExternalTraceCopySummary = ExternalTraceCopySummary(
        totalTraces = 0,
        copiedCount = 0,
        skippedExistingCount = 0,
        failedCount = 0,
        folderWritable = true
    )
    suspend fun getImportedFilesCount(): Int
    suspend fun deleteLegacyTraces()
    suspend fun backfillGpxMetadata()

    suspend fun toggleGpxVisibility(hash: String, isVisible: Boolean)
    fun getVisibleGpxFilesFlow(): Flow<List<ImportedGpxFileEntity>>
    suspend fun setAllGpxVisibility(isVisible: Boolean)

    fun getLastDebugTraceFlow(): Flow<DebugTraceEntity?>
    suspend fun insertDebugTrace(trace: DebugTraceEntity)
    suspend fun clearDebugTraces()

    // OSM Pins
    fun getAllOsmPinsFlow(): Flow<List<OsmPinEntity>>
    fun getUnresolvedOsmPinsFlow(): Flow<List<OsmPinEntity>>
    suspend fun getOsmPinById(pinId: String): OsmPinEntity?
    suspend fun insertOsmPin(pin: OsmPinEntity)
    suspend fun updateOsmPin(pin: OsmPinEntity)
    suspend fun deleteOsmPin(pinId: String)

    // Ride Recording
    val recordingState: StateFlow<RideRecordingState>
    val activeTrace: StateFlow<List<TrackPoint>>

    fun startRideRecording()
    fun pauseRideRecording()
    fun resumeRideRecording()
    fun stopRideRecording()
    fun discardRideRecording()
    fun consumeRecoveredRidePointCount(): Int = 0
    suspend fun matchLiveTraceWindow(tracePoints: List<TrackPoint>): Int
    suspend fun saveRideRecording(): Boolean

    // Bulk Updates
    suspend fun resetAllProgress()
    suspend fun rescanGpxTraces()
    suspend fun getCandidateSegmentsForBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity>
    suspend fun updateRoadSegments(segments: List<RoadSegmentEntity>)

    // Stats
    fun getStatsSummaryFlow(): Flow<StatsSummary>
}
