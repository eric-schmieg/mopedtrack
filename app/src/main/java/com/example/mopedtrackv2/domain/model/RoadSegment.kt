package com.example.mopedtrackv2.domain.model

data class RoadSegment(
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
    val isCompleted: Boolean,
    val completedAt: Long?
)
