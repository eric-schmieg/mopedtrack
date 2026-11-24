package com.example.mopedtrackv2.domain.repository

import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RoadSegment
import kotlinx.coroutines.flow.Flow

interface MopedRepository {
    fun getAllRegions(): Flow<List<Region>>
    suspend fun getRegionById(regionId: String): Region?
    fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegment>>
    fun getRoadSegmentsInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<RoadSegment>>
    suspend fun getRoadSegmentById(segmentId: String): RoadSegment?
    suspend fun toggleSegmentCompletion(segmentId: String, isCompleted: Boolean, completedAt: Long?)
    suspend fun searchRegions(query: String): List<Region>
    suspend fun importRegion(region: Region, onProgress: (Float, String) -> Unit = { _, _ -> })
    suspend fun deleteRegion(regionId: String)
    suspend fun clearAllData()

    // Settings
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

    // GPX & Debug Trace
    fun getImportedFilesFlow(): Flow<List<ImportedGpxFileEntity>>
    suspend fun getImportedFileByHash(hash: String): ImportedGpxFileEntity?
    suspend fun insertImportedFile(file: ImportedGpxFileEntity)
    suspend fun getImportedFilesCount(): Int
    suspend fun deleteLegacyTraces()
    
    suspend fun toggleGpxVisibility(hash: String, isVisible: Boolean)
    fun getVisibleGpxFilesFlow(): Flow<List<ImportedGpxFileEntity>>
    
    fun getLastDebugTraceFlow(): Flow<DebugTraceEntity?>
    suspend fun insertDebugTrace(trace: DebugTraceEntity)
    suspend fun clearDebugTraces()
    
    // Bulk Updates
    suspend fun resetAllProgress()
    suspend fun rescanGpxTraces()
    suspend fun getCandidateSegmentsForBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity>
    suspend fun updateRoadSegments(segments: List<RoadSegmentEntity>)
}
