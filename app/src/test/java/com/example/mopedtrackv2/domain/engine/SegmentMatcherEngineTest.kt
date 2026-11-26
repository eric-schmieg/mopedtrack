package com.example.mopedtrackv2.domain.engine

import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.utils.PolylineUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMatcherEngineTest {

    private fun createSegment(points: List<Pair<Double, Double>>, isDeadEnd: Boolean = false): RoadSegmentEntity {
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        return RoadSegmentEntity(
            segmentId = "test_seg",
            regionId = "test_reg",
            osmWayId = 1L,
            name = "Test Street",
            highwayType = "residential",
            isOneway = false,
            pairedSegmentId = null,
            isDeadEnd = isDeadEnd,
            geometryEncoded = PolylineUtils.encode(points),
            lengthMeters = 100.0,
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
            isCompleted = false
        )
    }

    @Test
    fun `test standard sequential match A to B`() {
        val segmentPoints = listOf(Pair(0.0, 0.0), Pair(0.0, 0.001), Pair(0.0, 0.002))
        val segment = createSegment(segmentPoints)

        // Trace directly goes over the segment
        val trace = listOf(
            GpsPoint(0.0, 0.0),
            GpsPoint(0.0, 0.0005),
            GpsPoint(0.0, 0.001),
            GpsPoint(0.0, 0.0015),
            GpsPoint(0.0, 0.002)
        )

        val result = SegmentMatcherEngine.evaluateSegment(segment, trace, 15.0, 80.0)
        assertTrue(result)
    }

    @Test
    fun `test standard sequential match B to A`() {
        val segmentPoints = listOf(Pair(0.0, 0.0), Pair(0.0, 0.001), Pair(0.0, 0.002))
        val segment = createSegment(segmentPoints)

        val trace = listOf(
            GpsPoint(0.0, 0.002),
            GpsPoint(0.0, 0.0015),
            GpsPoint(0.0, 0.001),
            GpsPoint(0.0, 0.0005),
            GpsPoint(0.0, 0.0)
        )

        val result = SegmentMatcherEngine.evaluateSegment(segment, trace, 15.0, 80.0)
        assertTrue(result)
    }

    @Test
    fun `test perpendicular crossing rejection`() {
        // Segment goes East-West
        val segmentPoints = listOf(Pair(0.0, 0.0), Pair(0.0, 0.001), Pair(0.0, 0.002))
        val segment = createSegment(segmentPoints)

        // Trace goes North-South, crossing the midpoint
        val trace = listOf(
            GpsPoint(0.001, 0.001),
            GpsPoint(0.0, 0.001), // Midpoint
            GpsPoint(-0.001, 0.001)
        )

        val result = SegmentMatcherEngine.evaluateSegment(segment, trace, 15.0, 80.0)
        assertFalse("Should reject perpendicular crossing due to low coverage", result)
    }

    @Test
    fun `test dead-end segment matching`() {
        val segmentPoints = listOf(Pair(0.0, 0.0), Pair(0.0, 0.001), Pair(0.0, 0.002))
        val segment = createSegment(segmentPoints, isDeadEnd = true)

        val trace = listOf(
            GpsPoint(0.0, 0.0), // Hit start
            GpsPoint(0.0, 0.0005),
            GpsPoint(0.0, 0.001),
            GpsPoint(0.0, 0.0015),
            GpsPoint(0.0, 0.0018) // Near terminus
        )

        val result = SegmentMatcherEngine.evaluateSegment(segment, trace, 15.0, 80.0)
        assertTrue("Dead-end should match when start is hit and high coverage achieved", result)
    }

    @Test
    fun `test turning onto street past endpoint matches`() {
        val segmentPoints = listOf(Pair(0.0, 0.0), Pair(0.0, 0.001), Pair(0.0, 0.002), Pair(0.0, 0.003))
        val segment = createSegment(segmentPoints)

        // Driver turns onto the street slightly past (0.0, 0.0) but covers 80%+ of the remaining street
        val trace = listOf(
            GpsPoint(0.0, 0.0003),
            GpsPoint(0.0, 0.001),
            GpsPoint(0.0, 0.002),
            GpsPoint(0.0, 0.003)
        )

        val result = SegmentMatcherEngine.evaluateSegment(segment, trace, 15.0, 80.0)
        assertTrue("Should match if 80%+ of sub-points are covered even if start node was missed", result)
    }

    @Test
    fun `test short perpendicular side street rejection when driving past intersection`() {
        // Short horizontal side street (East-West) from (0.0, 0.0) to (0.0, 0.0002) (~22 meters long)
        val sideStreetPoints = listOf(Pair(0.0, 0.0), Pair(0.0, 0.0002))
        val sideStreet = createSegment(sideStreetPoints)

        // Trace drives North-South past the intersection at (0.0, 0.0002)
        val trace = listOf(
            GpsPoint(0.001, 0.0002),
            GpsPoint(0.0005, 0.0002),
            GpsPoint(0.0, 0.0002),
            GpsPoint(-0.0005, 0.0002),
            GpsPoint(-0.001, 0.0002)
        )

        val result = SegmentMatcherEngine.evaluateSegment(sideStreet, trace, 15.0, 80.0)
        assertFalse("Should reject short side street when driving perpendicular past it on main road", result)
    }

    @Test
    fun `test TraceSpatialIndex world bounds query does not OOM and returns candidate segments`() {
        val tracePoints = listOf(
            GpsPoint(47.6062, -122.3321),
            GpsPoint(47.6065, -122.3325),
            GpsPoint(47.6070, -122.3330)
        )
        val index = TraceSpatialIndex(tracePoints)

        val worldCandidates = index.getCandidateTraceSegments(-90.0, 90.0, -180.0, 180.0)
        org.junit.Assert.assertEquals(2, worldCandidates.size)

        val localCandidates = index.getCandidateTraceSegments(47.6060, 47.6075, -122.3335, -122.3315)
        org.junit.Assert.assertEquals(2, localCandidates.size)

        val emptyCandidates = index.getCandidateTraceSegments(0.0, 1.0, 0.0, 1.0)
        org.junit.Assert.assertTrue(emptyCandidates.isEmpty())
    }
}
