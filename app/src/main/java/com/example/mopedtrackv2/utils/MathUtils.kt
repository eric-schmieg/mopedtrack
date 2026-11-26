package com.example.mopedtrackv2.utils

import kotlin.math.*

object MathUtils {
    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3 // metres
        val phi1 = lat1 * Math.PI / 180.0
        val phi2 = lat2 * Math.PI / 180.0
        val deltaPhi = (lat2 - lat1) * Math.PI / 180.0
        val deltaLambda = (lon2 - lon1) * Math.PI / 180.0

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * Math.PI / 180.0
        val phi2 = lat2 * Math.PI / 180.0
        val lambda1 = lon1 * Math.PI / 180.0
        val lambda2 = lon2 * Math.PI / 180.0
        val y = sin(lambda2 - lambda1) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(lambda2 - lambda1)
        var theta = atan2(y, x)
        theta = (theta * 180.0 / Math.PI + 360) % 360
        return theta
    }

    /**
     * Calculates the minimum angle difference in degrees (0 to 90) between two bearings,
     * taking into account that driving in either direction along a road (same or reverse orientation)
     * is considered parallel.
     * 0 degrees = perfectly parallel (same or opposite direction)
     * 90 degrees = perfectly perpendicular
     */
    fun parallelAngleDifferenceDegrees(b1: Double, b2: Double): Double {
        val diff = abs(b1 - b2) % 360.0
        val normalized = if (diff > 180.0) 360.0 - diff else diff
        return if (normalized > 90.0) 180.0 - normalized else normalized
    }

    /**
     * Calculates perpendicular or endpoint distance in meters from point P to line segment A->B.
     */
    fun distanceToLineSegmentMeters(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        val latRad = Math.toRadians(pLat)
        val cosLat = if (cos(latRad) > 0.1) cos(latRad) else 1.0

        val ax = (aLon - pLon) * 111000.0 * cosLat
        val ay = (aLat - pLat) * 111000.0
        val bx = (bLon - pLon) * 111000.0 * cosLat
        val by = (bLat - pLat) * 111000.0

        val dx = bx - ax
        val dy = by - ay

        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) {
            return haversineDistance(pLat, pLon, aLat, aLon)
        }

        val t = ((-ax * dx) + (-ay * dy)) / lenSq
        val clampedT = t.coerceIn(0.0, 1.0)
        val projX = ax + clampedT * dx
        val projY = ay + clampedT * dy

        return sqrt(projX * projX + projY * projY)
    }
}
