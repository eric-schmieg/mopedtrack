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

    @Query("""
        SELECT * FROM road_segments 
        WHERE isCompleted = 0
        AND maxLat >= :minLat AND minLat <= :maxLat 
        AND maxLon >= :minLon AND minLon <= :maxLon
    """)
    suspend fun getUncompletedCandidateSegments(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): List<RoadSegmentEntity>

    @Query("UPDATE road_segments SET isCompleted = 1, completedAt = :completedAt WHERE segmentId IN (:segmentIds) AND isCompleted = 0")
    suspend fun markSegmentsCompletedByIds(segmentIds: List<String>, completedAt: Long)

    @Query("UPDATE road_segments SET isCompleted = 1, completedAt = :completedAt WHERE pairedSegmentId IN (:pairedIds) AND isCompleted = 0")
    suspend fun markSegmentsCompletedByPairedIds(pairedIds: List<String>, completedAt: Long)

    @Transaction
    suspend fun markMatchedSegmentsCompleted(
        segmentIds: List<String>,
        pairedIds: List<String>,
        completedAt: Long
    ) {
        segmentIds.chunked(500).forEach { chunk ->
            markSegmentsCompletedByIds(chunk, completedAt)
        }
        pairedIds.chunked(500).forEach { chunk ->
            markSegmentsCompletedByPairedIds(chunk, completedAt)
            markSegmentsCompletedByIds(chunk, completedAt)
        }
    }

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

    @Query("SELECT * FROM road_segments WHERE regionId = :regionId")
    suspend fun getRoadSegmentsForRegion(regionId: String): List<RoadSegmentEntity>

    @Query("DELETE FROM road_segments WHERE regionId = :regionId")
    suspend fun deleteRoadSegmentsByRegion(regionId: String)

    @Transaction
    suspend fun replaceRegionSegments(regionId: String, segments: List<RoadSegmentEntity>) {
        deleteRoadSegmentsByRegion(regionId)
        segments.chunked(500).forEach { chunk ->
            insertRoadSegments(chunk)
        }
    }

    @Query("DELETE FROM road_segments")
    suspend fun clearRoadSegments()
    
    @Query("UPDATE road_segments SET isCompleted = 0, completedAt = NULL")
    suspend fun resetAllCompletionStatus()

    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN isUnnamed = 0 THEN 1 ELSE 0 END), 0) AS totalSegments,
            COALESCE(SUM(CASE WHEN isUnnamed = 0 AND isCompleted = 1 THEN 1 ELSE 0 END), 0) AS completedSegments,
            COALESCE(SUM(CASE WHEN isUnnamed = 0 THEN lengthMeters ELSE 0.0 END), 0.0) AS totalLengthMeters,
            COALESCE(SUM(CASE WHEN isUnnamed = 0 AND isCompleted = 1 THEN lengthMeters ELSE 0.0 END), 0.0) AS completedLengthMeters,
            COALESCE(SUM(CASE WHEN isUnnamed = 1 AND isCompleted = 1 THEN 1 ELSE 0 END), 0) AS unnamedCompletedSegments
        FROM (
            SELECT
                regionId,
                MAX(isCompleted) AS isCompleted,
                AVG(lengthMeters) AS lengthMeters,
                MIN(CASE WHEN name IS NULL OR name = '' OR name = 'Unnamed Road' THEN 1 ELSE 0 END) AS isUnnamed
            FROM road_segments
            GROUP BY regionId,
                CASE
                    WHEN pairedSegmentId IS NULL OR pairedSegmentId = '' THEN segmentId
                    WHEN EXISTS (
                        SELECT 1 FROM road_segments p
                        WHERE p.segmentId = road_segments.pairedSegmentId
                    ) THEN CASE
                        WHEN segmentId < pairedSegmentId THEN segmentId
                        ELSE pairedSegmentId
                    END
                    ELSE pairedSegmentId
                END
        )
    """)
    fun getOverallRoadStatsFlow(): Flow<RoadStatsAggregate>

    @Query("""
        SELECT
            r.regionId AS regionId,
            r.name AS regionName,
            COUNT(s.canonicalId) AS totalSegments,
            COALESCE(SUM(s.isCompleted), 0) AS completedSegments,
            COALESCE(SUM(s.lengthMeters), 0.0) AS totalLengthMeters,
            COALESCE(SUM(CASE WHEN s.isCompleted = 1 THEN s.lengthMeters ELSE 0.0 END), 0.0) AS completedLengthMeters
        FROM regions r
        LEFT JOIN (
            SELECT
                regionId,
                CASE
                    WHEN pairedSegmentId IS NULL OR pairedSegmentId = '' THEN segmentId
                    WHEN EXISTS (
                        SELECT 1 FROM road_segments p
                        WHERE p.segmentId = road_segments.pairedSegmentId
                    ) THEN CASE
                        WHEN segmentId < pairedSegmentId THEN segmentId
                        ELSE pairedSegmentId
                    END
                    ELSE pairedSegmentId
                END AS canonicalId,
                MAX(isCompleted) AS isCompleted,
                AVG(lengthMeters) AS lengthMeters
            FROM road_segments
            WHERE name IS NOT NULL AND name != '' AND name != 'Unnamed Road'
            GROUP BY regionId,
                CASE
                    WHEN pairedSegmentId IS NULL OR pairedSegmentId = '' THEN segmentId
                    WHEN EXISTS (
                        SELECT 1 FROM road_segments p
                        WHERE p.segmentId = road_segments.pairedSegmentId
                    ) THEN CASE
                        WHEN segmentId < pairedSegmentId THEN segmentId
                        ELSE pairedSegmentId
                    END
                    ELSE pairedSegmentId
                END
        ) s ON r.regionId = s.regionId
        GROUP BY r.regionId, r.name
        ORDER BY r.name ASC
    """)
    fun getRegionStatsFlow(): Flow<List<RegionStatsRow>>
}

data class RoadStatsAggregate(
    val totalSegments: Int,
    val completedSegments: Int,
    val totalLengthMeters: Double,
    val completedLengthMeters: Double,
    val unnamedCompletedSegments: Int = 0
)

data class RegionStatsRow(
    val regionId: String,
    val regionName: String,
    val totalSegments: Int,
    val completedSegments: Int,
    val totalLengthMeters: Double,
    val completedLengthMeters: Double
)
