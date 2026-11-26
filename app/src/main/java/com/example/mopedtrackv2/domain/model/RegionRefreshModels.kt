package com.example.mopedtrackv2.domain.model

data class RegionRefreshResult(
    val regionId: String,
    val regionName: String,
    val totalSegments: Int,
    val preservedCompletedCount: Int,
    val gpxMatchedNewSegmentsCount: Int,
    val newSegmentsCount: Int
)
