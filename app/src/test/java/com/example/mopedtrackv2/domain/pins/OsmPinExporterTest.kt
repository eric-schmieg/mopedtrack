package com.example.mopedtrackv2.domain.pins

import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.domain.model.OsmPinCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

class OsmPinExporterTest {

    private val t1 = Instant.parse("2026-11-25T10:15:30Z").toEpochMilli()
    private val t2 = Instant.parse("2026-11-25T11:20:45Z").toEpochMilli()

    private val samplePins = listOf(
        OsmPinEntity(
            pinId = "pin-1",
            lat = 37.7749,
            lon = -122.4194,
            category = OsmPinCategory.MISSING_ROAD.name,
            note = "New alley & bike path <cut-through>",
            createdAt = t1,
            isResolved = false
        ),
        OsmPinEntity(
            pinId = "pin-2",
            lat = 37.7812,
            lon = -122.4055,
            category = OsmPinCategory.ROAD_CLOSED_REMOVED.name,
            note = "Gated, \"no entry\"\nverified",
            createdAt = t2,
            isResolved = true
        )
    )

    @Test
    fun `buildFileName generates expected filenames for each format`() {
        val ts = Instant.parse("2026-11-25T15:07:09Z").toEpochMilli()
        assertEquals(
            "MopedTrack_OSM_Pins_2026-11-25_15-07-09.gpx",
            OsmPinExporter.buildFileName(OsmPinExportFormat.GPX, ts, ZoneOffset.UTC)
        )
        assertEquals(
            "MopedTrack_OSM_Pins_2026-11-25_15-07-09.geojson",
            OsmPinExporter.buildFileName(OsmPinExportFormat.GEOJSON, ts, ZoneOffset.UTC)
        )
        assertEquals(
            "MopedTrack_OSM_Pins_2026-11-25_15-07-09.csv",
            OsmPinExporter.buildFileName(OsmPinExportFormat.CSV, ts, ZoneOffset.UTC)
        )
    }

    @Test
    fun `toGpx produces valid GPX 1_1 XML with wpt elements`() {
        val gpx = OsmPinExporter.export(samplePins, OsmPinExportFormat.GPX, metadataTimeMs = t1)

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement

        assertEquals("gpx", root.localName)
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))

        val wptNodes = root.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "wpt")
        assertEquals(2, wptNodes.length)

        val wpt1 = wptNodes.item(0) as Element
        assertEquals("37.7749", wpt1.getAttribute("lat"))
        assertEquals("-122.4194", wpt1.getAttribute("lon"))
        assertEquals("Missing Road", wpt1.getElementsByTagName("name").item(0).textContent)
        assertEquals("New alley & bike path <cut-through>", wpt1.getElementsByTagName("desc").item(0).textContent)
        assertEquals("2026-11-25T10:15:30Z", wpt1.getElementsByTagName("time").item(0).textContent)
        assertEquals("MISSING_ROAD", wpt1.getElementsByTagName("type").item(0).textContent)

        val wpt2 = wptNodes.item(1) as Element
        assertEquals("Road Closed / Removed", wpt2.getElementsByTagName("name").item(0).textContent)
        assertTrue(wpt2.getElementsByTagName("desc").item(0).textContent.contains("[Resolved]"))
        assertFalse(gpx.contains("NaN"))
    }

    @Test
    fun `toGeoJson produces valid FeatureCollection of Point features`() {
        val geoJson = OsmPinExporter.export(samplePins, OsmPinExportFormat.GEOJSON)

        assertTrue(geoJson.contains("\"type\": \"FeatureCollection\""))
        assertTrue(geoJson.contains("\"type\": \"Feature\""))
        assertTrue(geoJson.contains("\"type\": \"Point\""))
        assertTrue(geoJson.contains("\"coordinates\": [-122.4194, 37.7749]"))
        assertTrue(geoJson.contains("\"coordinates\": [-122.4055, 37.7812]"))
        assertTrue(geoJson.contains("\"category\": \"MISSING_ROAD\""))
        assertTrue(geoJson.contains("\"category_label\": \"Missing Road\""))
        assertTrue(geoJson.contains("\"note\": \"Gated, \\\"no entry\\\"\\nverified\""))
        assertTrue(geoJson.contains("\"created_at\": \"2026-11-25T10:15:30Z\""))
        assertTrue(geoJson.contains("\"resolved\": false"))
        assertTrue(geoJson.contains("\"resolved\": true"))
    }

    @Test
    fun `toGeoJson handles empty list cleanly`() {
        val geoJson = OsmPinExporter.toGeoJson(emptyList())
        assertTrue(geoJson.contains("\"type\": \"FeatureCollection\""))
        assertTrue(geoJson.contains("\"features\": []"))
    }

    @Test
    fun `toCsv produces header and properly escaped rows`() {
        val csv = OsmPinExporter.export(samplePins, OsmPinExportFormat.CSV)
        val lines = csv.trimEnd().split("\n")

        assertEquals("id,lat,lon,category,note,created_at,resolved", lines[0].trimEnd('\r'))
        assertEquals(
            "pin-1,37.7749,-122.4194,MISSING_ROAD,New alley & bike path <cut-through>,2026-11-25T10:15:30Z,false",
            lines[1].trimEnd('\r')
        )
        // Second row contains comma, quotes, and newline inside note -> wrapped in quotes with doubled ""
        assertTrue(csv.contains("pin-2,37.7812,-122.4055,ROAD_CLOSED_REMOVED,\"Gated, \"\"no entry\"\"\nverified\",2026-11-25T11:20:45Z,true"))
    }

    @Test
    fun `exporters skip non-finite coordinates`() {
        val invalidPin = OsmPinEntity(
            pinId = "bad",
            lat = Double.NaN,
            lon = Double.POSITIVE_INFINITY,
            category = OsmPinCategory.WRONG_NAME.name
        )
        val gpx = OsmPinExporter.toGpx(listOf(invalidPin))
        val geoJson = OsmPinExporter.toGeoJson(listOf(invalidPin))
        val csv = OsmPinExporter.toCsv(listOf(invalidPin))

        assertFalse(gpx.contains("<wpt"))
        assertTrue(geoJson.contains("\"features\": []"))
        assertEquals("id,lat,lon,category,note,created_at,resolved", csv.trim())
    }
}
