package com.example.mopedtrackv2.domain.gpx

import com.example.mopedtrackv2.domain.model.TrackPoint
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object GpxWriter {

    private val fileNameDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val isoTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_INSTANT

    /**
     * Generates a standardized GPX filename: MopedTrack_YYYY-MM-DD_HH-mm-ss.gpx
     */
    fun buildFileName(
        timestampMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val formatted = fileNameDateFormatter
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(timestampMs))
        return "MopedTrack_$formatted.gpx"
    }

    /**
     * Formats an epoch millisecond timestamp as an ISO-8601 UTC string (e.g. 2026-11-24T20:45:00Z).
     */
    fun formatIsoTimestamp(timestampMs: Long): String {
        return isoTimestampFormatter.format(Instant.ofEpochMilli(timestampMs))
    }

    /**
     * Serializes a list of [TrackPoint]s into a standards-compliant GPX 1.1 XML document.
     */
    fun serialize(
        trackPoints: List<TrackPoint>,
        trackName: String = "MopedTrack Ride",
        metadataTimeMs: Long = trackPoints.firstOrNull()?.timestamp ?: System.currentTimeMillis()
    ): String {
        val escapedName = escapeXml(trackName)
        val metadataTime = formatIsoTimestamp(metadataTimeMs)

        return buildString(capacity = 512 + trackPoints.size * 128) {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<gpx version=\"1.1\" creator=\"MopedTrackV2\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            appendLine("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">")
            appendLine("  <metadata>")
            appendLine("    <name>$escapedName</name>")
            appendLine("    <time>$metadataTime</time>")
            appendLine("  </metadata>")
            appendLine("  <trk>")
            appendLine("    <name>$escapedName</name>")
            appendLine("    <trkseg>")
            for (point in trackPoints) {
                if (!point.lat.isFinite() || !point.lon.isFinite()) continue
                val latStr = formatDecimal(point.lat)
                val lonStr = formatDecimal(point.lon)
                appendLine("      <trkpt lat=\"$latStr\" lon=\"$lonStr\">")
                point.altitude?.takeIf { it.isFinite() }?.let { ele ->
                    appendLine("        <ele>${formatDecimal(ele)}</ele>")
                }
                appendLine("        <time>${formatIsoTimestamp(point.timestamp)}</time>")
                appendLine("      </trkpt>")
            }
            appendLine("    </trkseg>")
            appendLine("  </trk>")
            append("</gpx>")
        }
    }

    private fun formatDecimal(value: Double): String {
        return BigDecimal.valueOf(value).toPlainString()
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
