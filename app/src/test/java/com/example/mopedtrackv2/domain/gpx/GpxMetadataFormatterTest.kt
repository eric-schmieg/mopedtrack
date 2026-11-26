package com.example.mopedtrackv2.domain.gpx

import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class GpxMetadataFormatterTest {

    @Test
    fun `formatDistance formats metric meters and kilometers appropriately`() {
        assertEquals("0 m", GpxMetadataFormatter.formatDistance(0.0, UnitSystem.METRIC))
        assertEquals("0 m", GpxMetadataFormatter.formatDistance(-5.0, UnitSystem.METRIC))
        assertEquals("450 m", GpxMetadataFormatter.formatDistance(450.2, UnitSystem.METRIC))
        assertEquals("1.00 km", GpxMetadataFormatter.formatDistance(1000.0, UnitSystem.METRIC))
        assertEquals("12.46 km", GpxMetadataFormatter.formatDistance(12_456.0, UnitSystem.METRIC))
        assertEquals("124.5 km", GpxMetadataFormatter.formatDistance(124_500.0, UnitSystem.METRIC))
    }

    @Test
    fun `formatDistance formats imperial feet and miles and defaults to imperial`() {
        assertEquals("0 ft", GpxMetadataFormatter.formatDistance(0.0))
        assertEquals("0 ft", GpxMetadataFormatter.formatDistance(-5.0, UnitSystem.IMPERIAL))
        assertEquals("328 ft", GpxMetadataFormatter.formatDistance(100.0, UnitSystem.IMPERIAL))
        assertEquals("1.00 mi", GpxMetadataFormatter.formatDistance(1609.344, UnitSystem.IMPERIAL))
        assertEquals("10.00 mi", GpxMetadataFormatter.formatDistance(16_093.44, UnitSystem.IMPERIAL))
        assertEquals("124.5 mi", GpxMetadataFormatter.formatDistance(200_363.328, UnitSystem.IMPERIAL))
    }

    @Test
    fun `formatShortDistanceMeters formats short distances in feet or meters`() {
        assertEquals("49 ft", GpxMetadataFormatter.formatShortDistanceMeters(15.0, UnitSystem.IMPERIAL))
        assertEquals("0 ft", GpxMetadataFormatter.formatShortDistanceMeters(0.0, UnitSystem.IMPERIAL))
        assertEquals("15 m", GpxMetadataFormatter.formatShortDistanceMeters(15.0, UnitSystem.METRIC))
        assertEquals("0 m", GpxMetadataFormatter.formatShortDistanceMeters(0.0, UnitSystem.METRIC))
    }

    @Test
    fun `formatDuration formats null seconds minutes and hours`() {
        assertEquals("--", GpxMetadataFormatter.formatDuration(null))
        assertEquals("--", GpxMetadataFormatter.formatDuration(-1000L))
        assertEquals("0s", GpxMetadataFormatter.formatDuration(0L))
        assertEquals("45s", GpxMetadataFormatter.formatDuration(45_000L))
        assertEquals("3m 05s", GpxMetadataFormatter.formatDuration(185_000L))
        assertEquals("1h 05m", GpxMetadataFormatter.formatDuration(3_900_000L))
    }

    @Test
    fun `computeDistanceFromEncodedTrace computes haversine total`() {
        val points = listOf(
            Pair(37.7749, -122.4194),
            Pair(37.7759, -122.4194)
        )
        val encoded = PolylineUtils.encode(points)
        val expected = MathUtils.haversineDistance(37.7749, -122.4194, 37.7759, -122.4194)
        assertEquals(expected, GpxMetadataFormatter.computeDistanceFromEncodedTrace(encoded), 2.0)
        assertEquals(0.0, GpxMetadataFormatter.computeDistanceFromEncodedTrace(""), 0.0)
    }
}
