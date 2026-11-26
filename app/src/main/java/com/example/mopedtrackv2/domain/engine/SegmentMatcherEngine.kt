package com.example.mopedtrackv2.domain.engine

import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import kotlin.math.cos
import kotlin.math.max

data class GpsPoint(val lat: Double, val lon: Double)

data class TraceSegment(
    val p1: GpsPoint,
    val p2: GpsPoint,
    val bearing: Double? = null
)

data class InterpolatedSubPoint(
    val point: GpsPoint,
    val bearing: Double
)

class TraceSpatialIndex(
    tracePoints: List<GpsPoint>,
    maxGapMeters: Double = 150.0
) {
    private val cellSize = 0.005 // ~550m spatial grid cell
    private val grid = mutableMapOf<Pair<Int, Int>, MutableList<TraceSegment>>()

    init {
        if (tracePoints.size >= 2) {
            val segmentCount = tracePoints.size - 1
            val bearings = arrayOfNulls<Double>(segmentCount)
            for (i in 0 until segmentCount) {
                val p1 = tracePoints[i]
                val p2 = tracePoints[i + 1]
                val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
                if (dist >= 1.0) {
                    bearings[i] = MathUtils.bearing(p1.lat, p1.lon, p2.lat, p2.lon)
                }
            }
            for (i in 0 until segmentCount) {
                if (bearings[i] == null) {
                    var resolved: Double? = null
                    for (offset in 1..20) {
                        if (i - offset >= 0 && bearings[i - offset] != null) {
                            resolved = bearings[i - offset]
                            break
                        }
                        if (i + offset < segmentCount && bearings[i + offset] != null) {
                            resolved = bearings[i + offset]
                            break
                        }
                    }
                    bearings[i] = resolved
                }
            }

            for (i in 0 until segmentCount) {
                val p1 = tracePoints[i]
                val p2 = tracePoints[i + 1]
                val len = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
                if (len <= maxGapMeters) {
                    val traceSeg = TraceSegment(p1, p2, bearings[i])
                    val minLatCell = (minOf(p1.lat, p2.lat) / cellSize).toInt()
                    val maxLatCell = (maxOf(p1.lat, p2.lat) / cellSize).toInt()
                    val minLonCell = (minOf(p1.lon, p2.lon) / cellSize).toInt()
                    val maxLonCell = (maxOf(p1.lon, p2.lon) / cellSize).toInt()

                    for (cx in minLatCell..maxLatCell) {
                        for (cy in minLonCell..maxLonCell) {
                            grid.getOrPut(Pair(cx, cy)) { mutableListOf() }.add(traceSeg)
                        }
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
    ): List<TraceSegment> {
        val minLatCell = (minLat.coerceIn(-90.0, 90.0) / cellSize).toInt()
        val maxLatCell = (maxLat.coerceIn(-90.0, 90.0) / cellSize).toInt()
        val minLonCell = (minLon.coerceIn(-180.0, 180.0) / cellSize).toInt()
        val maxLonCell = (maxLon.coerceIn(-180.0, 180.0) / cellSize).toInt()

        val numLatCells = (maxLatCell.toLong() - minLatCell.toLong() + 1L).coerceAtLeast(0L)
        val numLonCells = (maxLonCell.toLong() - minLonCell.toLong() + 1L).coerceAtLeast(0L)
        val queryCellCount = if (numLatCells > 0 && Long.MAX_VALUE / numLatCells < numLonCells) Long.MAX_VALUE else numLatCells * numLonCells

        val candidateList = mutableListOf<TraceSegment>()
        val visitedSegments = mutableSetOf<TraceSegment>()

        if (queryCellCount > grid.size) {
            for ((key, segments) in grid) {
                if (key.first in minLatCell..maxLatCell && key.second in minLonCell..maxLonCell) {
                    for (seg in segments) {
                        if (visitedSegments.add(seg)) {
                            candidateList.add(seg)
                        }
                    }
                }
            }
        } else {
            for (cx in minLatCell..maxLatCell) {
                for (cy in minLonCell..maxLonCell) {
                    grid[Pair(cx, cy)]?.let { segments ->
                        for (seg in segments) {
                            if (visitedSegments.add(seg)) {
                                candidateList.add(seg)
                            }
                        }
                    }
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
     * Interpolate a sequence of coordinates so they are separated by `spacingMeters`,
     * capturing the local segment bearing at each sub-point.
     */
    fun interpolateSegmentWithBearing(
        points: List<GpsPoint>,
        spacingMeters: Double = INTERPOLATION_SPACING_METERS
    ): List<InterpolatedSubPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) {
            return listOf(InterpolatedSubPoint(points.first(), 0.0))
        }

        val interpolated = mutableListOf<InterpolatedSubPoint>()

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            val segBearing = MathUtils.bearing(p1.lat, p1.lon, p2.lat, p2.lon)

            if (dist > spacingMeters) {
                val numPoints = (dist / spacingMeters).toInt()
                for (j in 0 until numPoints) {
                    val fraction = j.toDouble() / (dist / spacingMeters)
                    val intLat = p1.lat + (p2.lat - p1.lat) * fraction
                    val intLon = p1.lon + (p2.lon - p1.lon) * fraction
                    interpolated.add(InterpolatedSubPoint(GpsPoint(intLat, intLon), segBearing))
                }
            } else {
                interpolated.add(InterpolatedSubPoint(p1, segBearing))
            }
        }

        val lastP = points.last()
        val prevP = points[points.size - 2]
        val lastBearing = MathUtils.bearing(prevP.lat, prevP.lon, lastP.lat, lastP.lon)
        if (interpolated.isEmpty() || interpolated.last().point != lastP) {
            interpolated.add(InterpolatedSubPoint(lastP, lastBearing))
        }

        return interpolated
    }

    /**
     * Legacy helper to interpolate a sequence of coordinates without bearings.
     */
    fun interpolateSegment(points: List<GpsPoint>, spacingMeters: Double = INTERPOLATION_SPACING_METERS): List<GpsPoint> {
        return interpolateSegmentWithBearing(points, spacingMeters).map { it.point }
    }

    /**
     * Checks whether a single road sub-point is covered by candidate trace segments,
     * taking into account both spatial distance and heading/bearing alignment.
     */
    fun isSubPointCovered(
        subPt: GpsPoint,
        subPtBearing: Double,
        traceSegments: List<TraceSegment>,
        matchRadiusMeters: Double = 15.0,
        maxHeadingDiffDegrees: Double = 50.0
    ): Boolean {
        return traceSegments.any { traceSeg ->
            val dist = MathUtils.distanceToLineSegmentMeters(
                subPt.lat, subPt.lon,
                traceSeg.p1.lat, traceSeg.p1.lon,
                traceSeg.p2.lat, traceSeg.p2.lon
            )
            if (dist <= matchRadiusMeters) {
                val tBearing = traceSeg.bearing
                if (tBearing != null) {
                    val headingDiff = MathUtils.parallelAngleDifferenceDegrees(subPtBearing, tBearing)
                    headingDiff <= maxHeadingDiffDegrees
                } else {
                    true
                }
            } else {
                false
            }
        }
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

        val interpolatedSegment = interpolateSegmentWithBearing(segmentPoints)
        if (interpolatedSegment.isEmpty()) return false

        val padLat = matchRadiusMeters / 111000.0
        val latRad = Math.toRadians((segment.minLat + segment.maxLat) / 2.0)
        val cosLat = if (cos(latRad) > 0.1) cos(latRad) else 1.0
        val padLon = matchRadiusMeters / (111000.0 * cosLat)

        val segmentMinLat = segment.minLat - padLat
        val segmentMaxLat = segment.maxLat + padLat
        val segmentMinLon = segment.minLon - padLon
        val segmentMaxLon = segment.maxLon + padLon

        val candidateTraceSegments = spatialIndex.getCandidateTraceSegments(
            segmentMinLat, segmentMaxLat, segmentMinLon, segmentMaxLon
        )
        if (candidateTraceSegments.isEmpty()) return false

        val traceSegments = candidateTraceSegments.filter { traceSeg ->
            val boxMinLat = minOf(traceSeg.p1.lat, traceSeg.p2.lat)
            val boxMaxLat = maxOf(traceSeg.p1.lat, traceSeg.p2.lat)
            val boxMinLon = minOf(traceSeg.p1.lon, traceSeg.p2.lon)
            val boxMaxLon = maxOf(traceSeg.p1.lon, traceSeg.p2.lon)

            boxMinLat <= segmentMaxLat && boxMaxLat >= segmentMinLat &&
                    boxMinLon <= segmentMaxLon && boxMaxLon >= segmentMinLon
        }

        if (traceSegments.isEmpty()) return false

        val totalSubPoints = interpolatedSegment.size
        val coveredIndices = mutableSetOf<Int>()

        for ((index, subPtObj) in interpolatedSegment.withIndex()) {
            val isCovered = isSubPointCovered(
                subPt = subPtObj.point,
                subPtBearing = subPtObj.bearing,
                traceSegments = traceSegments,
                matchRadiusMeters = matchRadiusMeters
            )
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
