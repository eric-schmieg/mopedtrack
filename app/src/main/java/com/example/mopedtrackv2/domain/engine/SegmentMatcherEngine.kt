package com.example.mopedtrackv2.domain.engine

import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import kotlin.math.cos
import kotlin.math.max

data class GpsPoint(val lat: Double, val lon: Double)

class TraceSpatialIndex(
    tracePoints: List<GpsPoint>,
    maxGapMeters: Double = 150.0
) {
    private val cellSize = 0.005 // ~550m spatial grid cell
    private val grid = mutableMapOf<Pair<Int, Int>, MutableList<Pair<GpsPoint, GpsPoint>>>()

    init {
        for (i in 0 until tracePoints.size - 1) {
            val p1 = tracePoints[i]
            val p2 = tracePoints[i + 1]
            val len = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            if (len <= maxGapMeters) {
                val minLatCell = (minOf(p1.lat, p2.lat) / cellSize).toInt()
                val maxLatCell = (maxOf(p1.lat, p2.lat) / cellSize).toInt()
                val minLonCell = (minOf(p1.lon, p2.lon) / cellSize).toInt()
                val maxLonCell = (maxOf(p1.lon, p2.lon) / cellSize).toInt()

                for (cx in minLatCell..maxLatCell) {
                    for (cy in minLonCell..maxLonCell) {
                        grid.getOrPut(Pair(cx, cy)) { mutableListOf() }.add(Pair(p1, p2))
                    }
                }
            }
        }
    }

    fun getCandidateTraceSegments(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<Pair<GpsPoint, GpsPoint>> {
        val minLatCell = (minLat / cellSize).toInt()
        val maxLatCell = (maxLat / cellSize).toInt()
        val minLonCell = (minLon / cellSize).toInt()
        val maxLonCell = (maxLon / cellSize).toInt()

        val candidateList = mutableListOf<Pair<GpsPoint, GpsPoint>>()
        val visitedCells = mutableSetOf<Pair<Int, Int>>()

        for (cx in minLatCell..maxLatCell) {
            for (cy in minLonCell..maxLonCell) {
                val key = Pair(cx, cy)
                if (visitedCells.add(key)) {
                    grid[key]?.let { candidateList.addAll(it) }
                }
            }
        }
        return candidateList
    }
}

object SegmentMatcherEngine {

    // Config
    private const val INTERPOLATION_SPACING_METERS = 5.0

    /**
     * Interpolate a sequence of coordinates so they are separated by `spacingMeters`.
     */
    fun interpolateSegment(points: List<GpsPoint>, spacingMeters: Double = INTERPOLATION_SPACING_METERS): List<GpsPoint> {
        if (points.isEmpty()) return emptyList()
        val interpolated = mutableListOf<GpsPoint>()
        interpolated.add(points.first())

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)

            if (dist > spacingMeters) {
                val numPoints = (dist / spacingMeters).toInt()
                for (j in 1..numPoints) {
                    val fraction = j.toDouble() / (dist / spacingMeters)
                    val intLat = p1.lat + (p2.lat - p1.lat) * fraction
                    val intLon = p1.lon + (p2.lon - p1.lon) * fraction
                    interpolated.add(GpsPoint(intLat, intLon))
                }
            } else {
                interpolated.add(p2)
            }
        }
        if (interpolated.last() != points.last()) {
            interpolated.add(points.last())
        }
        return interpolated
    }

    /**
     * Evaluates a single RoadSegment against a Spatial Index of trace points.
     */
    fun evaluateSegment(
        segment: RoadSegmentEntity,
        spatialIndex: TraceSpatialIndex,
        matchRadiusMeters: Double = 15.0,
        coverageThresholdPercent: Double = 80.0
    ): Boolean {
        if (segment.isCompleted) return true // Already completed

        val decodedPairs = PolylineUtils.decode(segment.geometryEncoded)
        if (decodedPairs.size < 2) return false
        val segmentPoints = decodedPairs.map { GpsPoint(it.first, it.second) }

        val interpolatedSegment = interpolateSegment(segmentPoints)
        if (interpolatedSegment.isEmpty()) return false

        val padLat = matchRadiusMeters / 111000.0
        val latRad = Math.toRadians((segment.minLat + segment.maxLat) / 2.0)
        val cosLat = if (cos(latRad) > 0.1) cos(latRad) else 1.0
        val padLon = matchRadiusMeters / (111000.0 * cosLat)

        val segmentMinLat = segment.minLat - padLat
        val segmentMaxLat = segment.maxLat + padLat
        val segmentMinLon = segment.minLon - padLon
        val segmentMaxLon = segment.maxLon + padLon

        // Fast spatial grid lookup
        val candidateTraceSegments = spatialIndex.getCandidateTraceSegments(
            segmentMinLat, segmentMaxLat, segmentMinLon, segmentMaxLon
        )
        if (candidateTraceSegments.isEmpty()) return false

        // Precise 2D Bounding Box Overlap Filter
        val traceSegments = candidateTraceSegments.filter { (p1, p2) ->
            val boxMinLat = minOf(p1.lat, p2.lat)
            val boxMaxLat = maxOf(p1.lat, p2.lat)
            val boxMinLon = minOf(p1.lon, p2.lon)
            val boxMaxLon = maxOf(p1.lon, p2.lon)

            boxMinLat <= segmentMaxLat && boxMaxLat >= segmentMinLat &&
                    boxMinLon <= segmentMaxLon && boxMaxLon >= segmentMinLon
        }

        if (traceSegments.isEmpty()) return false

        val totalSubPoints = interpolatedSegment.size
        val coveredIndices = mutableSetOf<Int>()

        for ((index, segPt) in interpolatedSegment.withIndex()) {
            val isCovered = traceSegments.any { (t1, t2) ->
                MathUtils.distanceToLineSegmentMeters(
                    segPt.lat, segPt.lon,
                    t1.lat, t1.lon,
                    t2.lat, t2.lon
                ) <= matchRadiusMeters
            }
            if (isCovered) {
                coveredIndices.add(index)
            }
        }

        val coveragePercent = (coveredIndices.size.toDouble() / totalSubPoints) * 100.0

        if (segment.isDeadEnd) {
            val effectiveThreshold = max(50.0, coverageThresholdPercent - 15.0)
            if (coveragePercent >= effectiveThreshold) {
                val hitStart = coveredIndices.contains(0) || coveredIndices.contains(1)
                val hitEnd = coveredIndices.contains(totalSubPoints - 1) || coveredIndices.contains(totalSubPoints - 2)
                if (hitStart || hitEnd) {
                    return true
                }
            }
            return false
        } else {
            if (coveragePercent >= coverageThresholdPercent) {
                return true
            }

            if (coveragePercent >= 70.0) {
                val startRegionSize = max(1, (totalSubPoints * 0.30).toInt())
                val endRegionStart = totalSubPoints - startRegionSize

                val hasStartCovered = (0 until startRegionSize).any { it in coveredIndices }
                val hasEndCovered = (endRegionStart until totalSubPoints).any { it in coveredIndices }

                if (hasStartCovered && hasEndCovered) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Evaluates a single RoadSegment against a raw sequence of GPS trace points.
     */
    fun evaluateSegment(
        segment: RoadSegmentEntity,
        tracePoints: List<GpsPoint>,
        matchRadiusMeters: Double = 15.0,
        coverageThresholdPercent: Double = 80.0
    ): Boolean {
        val index = TraceSpatialIndex(tracePoints)
        return evaluateSegment(segment, index, matchRadiusMeters, coverageThresholdPercent)
    }
}
