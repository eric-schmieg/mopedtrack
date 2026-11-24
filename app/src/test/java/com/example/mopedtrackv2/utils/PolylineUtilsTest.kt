package com.example.mopedtrackv2.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineUtilsTest {

    @Test
    fun testEncodeAndDecodeRoundtrip() {
        val originalPoints = listOf(
            Pair(37.77493, -122.41942),
            Pair(37.77500, -122.41950),
            Pair(-33.86882, 151.20930),
            Pair(0.0, 0.0),
            Pair(51.50735, -0.12776)
        )

        val encoded = PolylineUtils.encode(originalPoints)
        val decoded = PolylineUtils.decode(encoded)

        assertEquals(originalPoints.size, decoded.size)
        for (i in originalPoints.indices) {
            assertEquals(originalPoints[i].first, decoded[i].first, 1e-5)
            assertEquals(originalPoints[i].second, decoded[i].second, 1e-5)
        }
    }

    @Test
    fun testTruncatedPolylineStringDoesNotCrash() {
        val originalPoints = listOf(
            Pair(37.77493, -122.41942),
            Pair(37.77500, -122.41950),
            Pair(-33.86882, 151.20930)
        )
        val encoded = PolylineUtils.encode(originalPoints)

        // Truncate encoded string at all possible lengths from 0 to encoded.length
        for (len in 0..encoded.length) {
            val truncated = encoded.substring(0, len)
            val decoded = PolylineUtils.decode(truncated)
            // Must not throw StringIndexOutOfBoundsException
            assertTrue(decoded.size <= originalPoints.size)
        }
    }

    @Test
    fun testCorruptedPolylineStringReturnsSafely() {
        val corruptedStrings = listOf(
            "???invalid???",
            "~",
            "_p~iF~ps|U",
            "1234567890123456789",
            "  "
        )
        for (str in corruptedStrings) {
            val decoded = PolylineUtils.decode(str)
            // Ensures graceful return without exceptions
            org.junit.Assert.assertNotNull(decoded)
        }
    }

    @Test
    fun testJsonCoordinateFormatDecoding() {
        val jsonObjectArray = """[{"lat": 37.77493, "lng": -122.41942}, {"lat": 37.77500, "lng": -122.41950}]"""
        val decodedObjects = PolylineUtils.decode(jsonObjectArray)
        assertEquals(2, decodedObjects.size)
        assertEquals(37.77493, decodedObjects[0].first, 1e-5)
        assertEquals(-122.41942, decodedObjects[0].second, 1e-5)

        val jsonPairArray = """[[37.77493, -122.41942], [37.77500, -122.41950]]"""
        val decodedPairs = PolylineUtils.decode(jsonPairArray)
        assertEquals(2, decodedPairs.size)
        assertEquals(37.77493, decodedPairs[0].first, 1e-5)
        assertEquals(-122.41942, decodedPairs[0].second, 1e-5)
    }

    @Test
    fun testEncodeToJsonAndDecode() {
        val originalPoints = listOf(
            Pair(37.77493, -122.41942),
            Pair(37.77500, -122.41950)
        )
        val json = PolylineUtils.encodeToJson(originalPoints)
        val decoded = PolylineUtils.decode(json)
        assertEquals(2, decoded.size)
        assertEquals(37.77493, decoded[0].first, 1e-5)
        assertEquals(-122.41942, decoded[0].second, 1e-5)
    }
}
