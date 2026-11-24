package com.example.mopedtrackv2.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "road_segments",
    foreignKeys = [
        ForeignKey(
            entity = RegionEntity::class,
            parentColumns = ["regionId"],
            childColumns = ["regionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["regionId"]),
        Index(value = ["minLat", "maxLat", "minLon", "maxLon"])
    ]
)
data class RoadSegmentEntity(
    @PrimaryKey
    val segmentId: String,
    val regionId: String,
    val osmWayId: Long,
    val name: String?,
    val highwayType: String,
    val isOneway: Boolean,
    val pairedSegmentId: String?,
    val isDeadEnd: Boolean,
    val geometryEncoded: String,
    val lengthMeters: Double,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null
)
