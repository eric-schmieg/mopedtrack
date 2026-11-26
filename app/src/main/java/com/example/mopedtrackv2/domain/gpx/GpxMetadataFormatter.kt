package com.example.mopedtrackv2.domain.gpx

import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import java.util.Locale
import kotlin.math.roundToInt

object GpxMetadataFormatter {

    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val IMPERIAL_MILE_THRESHOLD_METERS = METERS_PER_MILE * 0.1 // 528 ft

    fun formatDistance(
        distanceMeters: Double,
        unitSystem: UnitSystem = UnitSystem.IMPERIAL
    ): String {
        return when (unitSystem) {
            UnitSystem.METRIC -> {
                if (!distanceMeters.isFinite() || distanceMeters <= 0.0) return "0 m"
                when {
                    distanceMeters < 1000.0 -> "${distanceMeters.roundToInt()} m"
                    distanceMeters < 100_000.0 -> String.format(Locale.US, "%.2f km", distanceMeters / 1000.0)
                    else -> String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
                }
            }
            UnitSystem.IMPERIAL -> {
                if (!distanceMeters.isFinite() || distanceMeters <= 0.0) return "0 ft"
                if (distanceMeters < IMPERIAL_MILE_THRESHOLD_METERS) {
                    val feet = (distanceMeters / METERS_PER_FOOT).roundToInt()
                    "$feet ft"
                } else {
                    val miles = distanceMeters / METERS_PER_MILE
                    if (miles < 100.0) {
                        String.format(Locale.US, "%.2f mi", miles)
                    } else {
                        String.format(Locale.US, "%.1f mi", miles)
                    }
                }
            }
        }
    }

    fun formatShortDistanceMeters(
        meters: Double,
        unitSystem: UnitSystem = UnitSystem.IMPERIAL
    ): String {
        val safeMeters = if (meters.isFinite() && meters > 0.0) meters else 0.0
        return when (unitSystem) {
            UnitSystem.METRIC -> "${safeMeters.roundToInt()} m"
            UnitSystem.IMPERIAL -> "${(safeMeters / METERS_PER_FOOT).roundToInt()} ft"
        }
    }

    fun formatDuration(durationMillis: Long?): String {
        if (durationMillis == null || durationMillis < 0L) return "--"
        val totalSeconds = durationMillis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            hours > 0L -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
            minutes > 0L -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
            else -> "${seconds}s"
        }
    }

    fun computeDistanceFromEncodedTrace(encodedTrace: String): Double {
        if (encodedTrace.isBlank()) return 0.0
        val decoded = PolylineUtils.decode(encodedTrace)
        if (decoded.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until decoded.size - 1) {
            val p1 = decoded[i]
            val p2 = decoded[i + 1]
            total += MathUtils.haversineDistance(p1.first, p1.second, p2.first, p2.second)
        }
        return total
    }
}
