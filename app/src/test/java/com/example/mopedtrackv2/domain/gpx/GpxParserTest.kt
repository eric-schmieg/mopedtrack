package com.example.mopedtrackv2.domain.gpx

import com.example.mopedtrackv2.utils.MathUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GpxParserTest {

    @Test
    fun `parseWithMetadata extracts points distance duration and recordedAt from trkpt`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Test">
              <metadata>
                <time>2026-11-24T09:55:00Z</time>
              </metadata>
              <trk>
                <trkseg>
                  <trkpt lat="37.7749" lon="-122.4194">
                    <ele>10.0</ele>
                    <time>2026-11-24T10:00:00Z</time>
                  </trkpt>
                  <trkpt lat="37.7749" lon="-122.4184">
                    <ele>12.0</ele>
                    <time>2026-11-24T10:00:30Z</time>
                  </trkpt>
                  <trkpt lat="37.7759" lon="-122.4184">
                    <ele>14.0</ele>
                    <time>2026-11-24T10:02:15Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = GpxParser.parseWithMetadata(gpx.byteInputStream())

        assertEquals(3, result.points.size)
        val expectedDist =
            MathUtils.haversineDistance(37.7749, -122.4194, 37.7749, -122.4184) +
                MathUtils.haversineDistance(37.7749, -122.4184, 37.7759, -122.4184)
        assertEquals(expectedDist, result.distanceMeters, 1e-3)
        assertEquals(135_000L, result.durationMillis)
        assertEquals(Instant.parse("2026-11-24T10:00:00Z").toEpochMilli(), result.recordedAt)
    }

    @Test
    fun `parseWithMetadata handles offset timestamps and metadata fallback`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <metadata>
                <time>2026-11-24T12:00:00+02:00</time>
              </metadata>
              <rte>
                <rtept lat="52.5200" lon="13.4050" />
                <rtept lat="52.5210" lon="13.4060" />
              </rte>
            </gpx>
        """.trimIndent()

        val result = GpxParser.parseWithMetadata(gpx.byteInputStream())

        assertEquals(2, result.points.size)
        assertTrue(result.distanceMeters > 100.0)
        assertNull(result.durationMillis)
        assertEquals(Instant.parse("2026-11-24T10:00:00Z").toEpochMilli(), result.recordedAt)
    }

    @Test
    fun `parseWithMetadata returns null time fields when no time tags exist`() {
        val gpx = """
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="37.7749" lon="-122.4194" />
                <trkpt lat="37.7750" lon="-122.4195" />
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val result = GpxParser.parseWithMetadata(gpx.byteInputStream())

        assertEquals(2, result.points.size)
        assertTrue(result.distanceMeters > 0.0)
        assertNull(result.durationMillis)
        assertNull(result.recordedAt)
    }

    @Test
    fun `parseWithMetadata ignores waypoint time tags`() {
        val gpx = """
            <gpx version="1.1">
              <wpt lat="37.7700" lon="-122.4100">
                <time>2026-11-24T08:00:00Z</time>
              </wpt>
              <trk><trkseg>
                <trkpt lat="37.7749" lon="-122.4194">
                  <time>2026-11-24T10:00:00Z</time>
                </trkpt>
                <trkpt lat="37.7759" lon="-122.4194">
                  <time>2026-11-24T10:01:00Z</time>
                </trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val result = GpxParser.parseWithMetadata(gpx.byteInputStream())
        assertEquals(2, result.points.size)
        assertEquals(60_000L, result.durationMillis)
        assertEquals(Instant.parse("2026-11-24T10:00:00Z").toEpochMilli(), result.recordedAt)
        assertNotNull(GpxParser.parse(gpx.byteInputStream()))
        assertEquals(2, GpxParser.parse(gpx.byteInputStream()).size)
    }
}
