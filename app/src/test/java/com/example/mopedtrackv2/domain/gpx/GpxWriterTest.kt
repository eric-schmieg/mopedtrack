package com.example.mopedtrackv2.domain.gpx

import com.example.mopedtrackv2.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

class GpxWriterTest {

    @Test
    fun `buildFileName formats UTC or specified ZoneId timestamp cleanly`() {
        val epochMs = Instant.parse("2026-11-24T15:07:09Z").toEpochMilli()
        val fileName = GpxWriter.buildFileName(epochMs, ZoneOffset.UTC)
        assertEquals("MopedTrack_2026-11-24_15-07-09.gpx", fileName)
    }

    @Test
    fun `formatIsoTimestamp produces ISO-8601 UTC strings`() {
        val exactSecondMs = Instant.parse("2026-11-24T20:30:45Z").toEpochMilli()
        assertEquals("2026-11-24T20:30:45Z", GpxWriter.formatIsoTimestamp(exactSecondMs))

        val withMillisMs = Instant.parse("2026-11-24T20:30:45.250Z").toEpochMilli()
        assertEquals("2026-11-24T20:30:45.250Z", GpxWriter.formatIsoTimestamp(withMillisMs))
    }

    @Test
    fun `serialize produces valid GPX 1_1 XML with metadata trk trkseg and trkpt elements`() {
        val t1 = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()
        val t2 = Instant.parse("2026-11-24T12:00:05Z").toEpochMilli()

        val points = listOf(
            TrackPoint(lat = 37.7749, lon = -122.4194, altitude = 18.5, timestamp = t1, speed = 8.2f, accuracy = 3.0f),
            TrackPoint(lat = 37.7755, lon = -122.4180, altitude = null, timestamp = t2)
        )

        val xml = GpxWriter.serialize(
            trackPoints = points,
            trackName = "Morning & Evening <Ride>",
            metadataTimeMs = t1
        )

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement

        assertEquals("gpx", root.localName)
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("MopedTrackV2", root.getAttribute("creator"))

        val metadataNodes = root.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "metadata")
        assertEquals(1, metadataNodes.length)
        val metadataEl = metadataNodes.item(0) as Element
        assertEquals("Morning & Evening <Ride>", metadataEl.getElementsByTagName("name").item(0).textContent)
        assertEquals("2026-11-24T12:00:00Z", metadataEl.getElementsByTagName("time").item(0).textContent)

        val trkNodes = root.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trk")
        assertEquals(1, trkNodes.length)
        val trkEl = trkNodes.item(0) as Element
        assertEquals("Morning & Evening <Ride>", trkEl.getElementsByTagName("name").item(0).textContent)

        val trksegNodes = trkEl.getElementsByTagName("trkseg")
        assertEquals(1, trksegNodes.length)

        val trkptNodes = (trksegNodes.item(0) as Element).getElementsByTagName("trkpt")
        assertEquals(2, trkptNodes.length)

        val firstPt = trkptNodes.item(0) as Element
        assertEquals("37.7749", firstPt.getAttribute("lat"))
        assertEquals("-122.4194", firstPt.getAttribute("lon"))
        assertEquals("18.5", firstPt.getElementsByTagName("ele").item(0).textContent)
        assertEquals("2026-11-24T12:00:00Z", firstPt.getElementsByTagName("time").item(0).textContent)

        val secondPt = trkptNodes.item(1) as Element
        assertEquals("37.7755", secondPt.getAttribute("lat"))
        assertEquals("-122.418", secondPt.getAttribute("lon"))
        assertEquals(0, secondPt.getElementsByTagName("ele").length)
        assertEquals("2026-11-24T12:00:05Z", secondPt.getElementsByTagName("time").item(0).textContent)

        assertNotNull(xml)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertFalse(xml.contains("NaN"))
    }
}
