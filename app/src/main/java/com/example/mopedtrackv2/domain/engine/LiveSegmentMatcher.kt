package com.example.mopedtrackv2.domain.engine

import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.utils.MathUtils
import kotlin.math.cos

data class GeoBoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

object LiveSegmentMatcher {
    private const val MIN_WINDOW_DISTANCE_METERS = 2000.0
    private const val MIN_WINDOW_POINTS = 100
    private const val MAX_WINDOW_POINTS = 1000
    const val BBOX_EXTRA_PADDING_METERS = 10.0

    /**
     * Extracts a sliding recent window of [TrackPoint]s covering at least [MIN_WINDOW_DISTANCE_METERS]
     * (or [MIN_WINDOW_POINTS] points), capped at [MAX_WINDOW_POINTS].
     */
    fun extractRecentTraceWindow(tracePoints: List<TrackPoint>): List<TrackPoint> {
        if (tracePoints.size <= MIN_WINDOW_POINTS) return tracePoints
        var accumulatedDistance = 0.0
        var startIndex = tracePoints.size - 1
        var count = 1
        while (startIndex > 0 && count < MAX_WINDOW_POINTS) {
            val curr = tracePoints[startIndex]
            val prev = tracePoints[startIndex - 1]
            accumulatedDistance += MathUtils.haversineDistance(prev.lat, prev.lon, curr.lat, curr.lon)
            startIndex--
            count++
            if (count >= MIN_WINDOW_POINTS && accumulatedDistance >= MIN_WINDOW_DISTANCE_METERS) {
                break
            }
        }
        return tracePoints.subList(startIndex, tracePoints.size)
    }

    /**
     * Computes a bounding box around [traceWindow] padded by [matchRadiusMeters] + [extraPaddingMeters].
     */
    fun computePaddedBoundingBox(
        traceWindow: List<TrackPoint>,
        matchRadiusMeters: Double,
        extraPaddingMeters: Double = BBOX_EXTRA_PADDING_METERS
    ): GeoBoundingBox {
        val rawMinLat = traceWindow.minOf { it.lat }
        val rawMaxLat = traceWindow.maxOf { it.lat }
        val rawMinLon = traceWindow.minOf { it.lon }
        val rawMaxLon = traceWindow.maxOf { it.lon }

        val padMeters = matchRadiusMeters + extraPaddingMeters
        val padLat = padMeters / 111000.0
        val latRad = Math.toRadians((rawMinLat + rawMaxLat) / 2.0)
        val cosLat = cos(latRad).let { if (it > 0.1) it else 1.0 }
        val padLon = padMeters / (111000.0 * cosLat)

        return GeoBoundingBox(
            minLat = rawMinLat - padLat,
            maxLat = rawMaxLat + padLat,
            minLon = rawMinLon - padLon,
            maxLon = rawMaxLon + padLon
        )
    }

    /**
     * Evaluates [candidates] against [traceWindow] using [TraceSpatialIndex] and
     * [SegmentMatcherEngine.evaluateSegment], returning the subset that matched.
     */
    fun findMatchedSegments(
        traceWindow: List<TrackPoint>,
        candidates: List<RoadSegmentEntity>,
        matchRadiusMeters: Double,
        coverageThresholdPercent: Double
    ): List<RoadSegmentEntity> {
        if (traceWindow.size < 2 || candidates.isEmpty()) return emptyList()

        val gpsPoints = traceWindow.map { GpsPoint(it.lat, it.lon) }
        val spatialIndex = TraceSpatialIndex(gpsPoints)

        return candidates.filter { segment ->
            !segment.isCompleted && SegmentMatcherEngine.evaluateSegment(
                segment = segment,
                spatialIndex = spatialIndex,
                matchRadiusMeters = matchRadiusMeters,
                coverageThresholdPercent = coverageThresholdPercent
            )
        }
    }
}
