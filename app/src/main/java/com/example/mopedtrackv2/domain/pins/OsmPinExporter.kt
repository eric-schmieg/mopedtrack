package com.example.mopedtrackv2.domain.pins

import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.domain.gpx.GpxWriter
import com.example.mopedtrackv2.domain.model.OsmPinCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class OsmPinExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    GPX(
        label = "GPX 1.1 (.gpx)",
        extension = "gpx",
        mimeType = "application/gpx+xml"
    ),
    GEOJSON(
        label = "GeoJSON (.geojson)",
        extension = "geojson",
        mimeType = "application/geo+json"
    ),
    CSV(
        label = "CSV (.csv)",
        extension = "csv",
        mimeType = "text/csv"
    )
}

object OsmPinExporter {

    private val fileNameDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun buildFileName(
        format: OsmPinExportFormat,
        timestampMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val formatted = fileNameDateFormatter
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(timestampMs))
        return "MopedTrack_OSM_Pins_$formatted.${format.extension}"
    }

    fun export(
        pins: List<OsmPinEntity>,
        format: OsmPinExportFormat,
        metadataTimeMs: Long = System.currentTimeMillis()
    ): String {
        return when (format) {
            OsmPinExportFormat.GPX -> toGpx(pins, metadataTimeMs)
            OsmPinExportFormat.GEOJSON -> toGeoJson(pins)
            OsmPinExportFormat.CSV -> toCsv(pins)
        }
    }

    fun toGpx(
        pins: List<OsmPinEntity>,
        metadataTimeMs: Long = System.currentTimeMillis()
    ): String {
        val metadataTime = GpxWriter.formatIsoTimestamp(metadataTimeMs)
        return buildString(capacity = 512 + pins.size * 200) {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<gpx version=\"1.1\" creator=\"MopedTrackV2\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            appendLine("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">")
            appendLine("  <metadata>")
            appendLine("    <name>MopedTrack OSM Pins</name>")
            appendLine("    <time>$metadataTime</time>")
            appendLine("  </metadata>")
            for (pin in pins) {
                if (!pin.lat.isFinite() || !pin.lon.isFinite()) continue
                val latStr = formatDecimal(pin.lat)
                val lonStr = formatDecimal(pin.lon)
                val categoryLabel = categoryLabel(pin.category)
                val descText = buildString {
                    if (pin.note.isNotBlank()) {
                        append(pin.note)
                        if (pin.isResolved) append(" [Resolved]")
                    } else {
                        append(categoryLabel)
                        if (pin.isResolved) append(" [Resolved]")
                    }
                }
                appendLine("  <wpt lat=\"$latStr\" lon=\"$lonStr\">")
                appendLine("    <time>${GpxWriter.formatIsoTimestamp(pin.createdAt)}</time>")
                appendLine("    <name>${escapeXml(categoryLabel)}</name>")
                appendLine("    <desc>${escapeXml(descText)}</desc>")
                appendLine("    <type>${escapeXml(pin.category)}</type>")
                appendLine("  </wpt>")
            }
            append("</gpx>")
        }
    }

    fun toGeoJson(pins: List<OsmPinEntity>): String {
        val validPins = pins.filter { it.lat.isFinite() && it.lon.isFinite() }
        return buildString(capacity = 128 + validPins.size * 256) {
            appendLine("{")
            appendLine("  \"type\": \"FeatureCollection\",")
            if (validPins.isEmpty()) {
                appendLine("  \"features\": []")
            } else {
                appendLine("  \"features\": [")
                validPins.forEachIndexed { index, pin ->
                    val categoryLabel = categoryLabel(pin.category)
                    val createdIso = GpxWriter.formatIsoTimestamp(pin.createdAt)
                    appendLine("    {")
                    appendLine("      \"type\": \"Feature\",")
                    appendLine("      \"id\": \"${escapeJson(pin.pinId)}\",")
                    appendLine("      \"geometry\": {")
                    appendLine("        \"type\": \"Point\",")
                    appendLine("        \"coordinates\": [${formatDecimal(pin.lon)}, ${formatDecimal(pin.lat)}]")
                    appendLine("      },")
                    appendLine("      \"properties\": {")
                    appendLine("        \"id\": \"${escapeJson(pin.pinId)}\",")
                    appendLine("        \"category\": \"${escapeJson(pin.category)}\",")
                    appendLine("        \"category_label\": \"${escapeJson(categoryLabel)}\",")
                    appendLine("        \"note\": \"${escapeJson(pin.note)}\",")
                    appendLine("        \"created_at\": \"${escapeJson(createdIso)}\",")
                    appendLine("        \"created_at_ms\": ${pin.createdAt},")
                    appendLine("        \"resolved\": ${pin.isResolved}")
                    appendLine("      }")
                    val suffix = if (index < validPins.lastIndex) "," else ""
                    appendLine("    }$suffix")
                }
                appendLine("  ]")
            }
            append("}")
        }
    }

    fun toCsv(pins: List<OsmPinEntity>): String {
        return buildString(capacity = 64 + pins.size * 100) {
            appendLine("id,lat,lon,category,note,created_at,resolved")
            for (pin in pins) {
                if (!pin.lat.isFinite() || !pin.lon.isFinite()) continue
                val row = listOf(
                    escapeCsv(pin.pinId),
                    formatDecimal(pin.lat),
                    formatDecimal(pin.lon),
                    escapeCsv(pin.category),
                    escapeCsv(pin.note),
                    escapeCsv(GpxWriter.formatIsoTimestamp(pin.createdAt)),
                    pin.isResolved.toString()
                ).joinToString(",")
                appendLine(row)
            }
        }
    }

    private fun categoryLabel(categoryName: String): String {
        return OsmPinCategory.fromCategoryName(categoryName)?.label ?: categoryName
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

    private fun escapeJson(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun escapeCsv(input: String): String {
        val needsQuoting = input.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return input
        val escaped = input.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
