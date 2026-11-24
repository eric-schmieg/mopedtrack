package com.example.mopedtrackv2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoadSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoadSegments(segments: List<RoadSegmentEntity>)

    @Query("SELECT * FROM road_segments WHERE regionId = :regionId")
    fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegmentEntity>>

    @Query("""
        SELECT * FROM road_segments 
        WHERE maxLat >= :minLat AND minLat <= :maxLat 
        AND maxLon >= :minLon AND minLon <= :maxLon
    """)
    fun getRoadSegmentsInBoundingBox(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): Flow<List<RoadSegmentEntity>>

    @Query("""
        SELECT * FROM road_segments 
        WHERE maxLat >= :minLat AND minLat <= :maxLat 
        AND maxLon >= :minLon AND minLon <= :maxLon
    """)
    suspend fun getCandidateSegments(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): List<RoadSegmentEntity>

    @androidx.room.Update
    suspend fun updateSegments(segments: List<RoadSegmentEntity>)

    @Query("SELECT * FROM road_segments WHERE segmentId = :segmentId")
    suspend fun getRoadSegmentById(segmentId: String): RoadSegmentEntity?

    @Query("UPDATE road_segments SET isCompleted = :isCompleted, completedAt = :completedAt WHERE segmentId = :segmentId")
    suspend fun updateCompletionStatus(segmentId: String, isCompleted: Boolean, completedAt: Long?)

    @Query("SELECT pairedSegmentId FROM road_segments WHERE segmentId = :segmentId")
    suspend fun getPairedSegmentId(segmentId: String): String?

    @Query("UPDATE road_segments SET isCompleted = :isCompleted, completedAt = :completedAt WHERE pairedSegmentId = :pairedId")
    suspend fun updateCompletionStatusByPairedId(pairedId: String, isCompleted: Boolean, completedAt: Long?)

    @Transaction
    suspend fun toggleIsCompleted(segmentId: String, isCompleted: Boolean, completedAt: Long?) {
        updateCompletionStatus(segmentId, isCompleted, completedAt)
        updateCompletionStatusByPairedId(segmentId, isCompleted, completedAt)
        val pairedId = getPairedSegmentId(segmentId)
        if (pairedId != null) {
            updateCompletionStatus(pairedId, isCompleted, completedAt)
            updateCompletionStatusByPairedId(pairedId, isCompleted, completedAt)
        }
    }

    @Query("DELETE FROM road_segments WHERE regionId = :regionId")
    suspend fun deleteRoadSegmentsByRegion(regionId: String)

    @Query("DELETE FROM road_segments")
    suspend fun clearRoadSegments()
    
    @Query("UPDATE road_segments SET isCompleted = 0, completedAt = NULL")
    suspend fun resetAllCompletionStatus()
}
