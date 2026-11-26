package com.example.mopedtrackv2.domain.model

data class StatsSummary(
    val totalSegments: Int = 0,
    val completedSegments: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val completedDistanceMeters: Double = 0.0,
    val unnamedCompletedSegments: Int = 0,
    val regionBreakdown: List<RegionStats> = emptyList(),
    val totalGpxFiles: Int = 0,
    val totalTrackPoints: Long = 0L
) {
    val remainingSegments: Int
        get() = (totalSegments - completedSegments).coerceAtLeast(0)

    val remainingDistanceMeters: Double
        get() = (totalDistanceMeters - completedDistanceMeters).coerceAtLeast(0.0)

    val completionPercent: Double
        get() = when {
            totalDistanceMeters > 0.0 -> ((completedDistanceMeters / totalDistanceMeters) * 100.0).coerceIn(0.0, 100.0)
            totalSegments > 0 -> ((completedSegments.toDouble() / totalSegments.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            else -> 0.0
        }
}

data class RegionStats(
    val regionId: String,
    val regionName: String,
    val totalSegments: Int,
    val completedSegments: Int,
    val totalDistanceMeters: Double,
    val completedDistanceMeters: Double
) {
    val remainingSegments: Int
        get() = (totalSegments - completedSegments).coerceAtLeast(0)

    val remainingDistanceMeters: Double
        get() = (totalDistanceMeters - completedDistanceMeters).coerceAtLeast(0.0)

    val completionPercent: Double
        get() = when {
            totalDistanceMeters > 0.0 -> ((completedDistanceMeters / totalDistanceMeters) * 100.0).coerceIn(0.0, 100.0)
            totalSegments > 0 -> ((completedSegments.toDouble() / totalSegments.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            else -> 0.0
        }
}
