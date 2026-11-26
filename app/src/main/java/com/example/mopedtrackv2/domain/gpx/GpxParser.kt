package com.example.mopedtrackv2.domain.gpx

import com.example.mopedtrackv2.domain.engine.GpsPoint
import com.example.mopedtrackv2.utils.MathUtils
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory

data class GpxParseResult(
    val points: List<GpsPoint>,
    val distanceMeters: Double,
    val durationMillis: Long?,
    val recordedAt: Long?
)

object GpxParser {

    fun parse(inputStream: InputStream): List<GpsPoint> = parseWithMetadata(inputStream).points

    fun parseWithMetadata(inputStream: InputStream): GpxParseResult {
        val points = mutableListOf<GpsPoint>()
        var metadataTime: Long? = null
        var firstPointTime: Long? = null
        var lastPointTime: Long? = null
        var minPointTime: Long? = null
        var maxPointTime: Long? = null
        var pointTimeCount = 0

        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
            }
            val parser = factory.newSAXParser()

            val handler = object : DefaultHandler() {
                private var inPoint = false
                private var inMetadata = false
                private var currentPointValid = false
                private var inTime = false
                private val timeBuffer = StringBuilder()

                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?
                ) {
                    val tag = qName ?: localName ?: return
                    when {
                        tag.equals("metadata", ignoreCase = true) -> {
                            inMetadata = true
                        }
                        tag.equals("trkpt", ignoreCase = true) || tag.equals("rtept", ignoreCase = true) -> {
                            inPoint = true
                            val lat = attributes?.getValue("lat")?.toDoubleOrNull()
                            val lon = attributes?.getValue("lon")?.toDoubleOrNull()
                            if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                                points.add(GpsPoint(lat, lon))
                                currentPointValid = true
                            } else {
                                currentPointValid = false
                            }
                        }
                        tag.equals("time", ignoreCase = true) -> {
                            inTime = true
                            timeBuffer.setLength(0)
                        }
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (inTime && ch != null && length > 0) {
                        timeBuffer.appendRange(ch, start, start + length)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val tag = qName ?: localName ?: return
                    when {
                        tag.equals("time", ignoreCase = true) -> {
                            if (inTime) {
                                inTime = false
                                val parsedTime = parseIsoTimestamp(timeBuffer.toString().trim())
                                timeBuffer.setLength(0)
                                if (parsedTime != null) {
                                    if (inPoint && currentPointValid) {
                                        pointTimeCount++
                                        if (firstPointTime == null) firstPointTime = parsedTime
                                        lastPointTime = parsedTime
                                        minPointTime = minPointTime?.let { minOf(it, parsedTime) } ?: parsedTime
                                        maxPointTime = maxPointTime?.let { maxOf(it, parsedTime) } ?: parsedTime
                                    } else if (inMetadata && metadataTime == null) {
                                        metadataTime = parsedTime
                                    }
                                }
                            }
                        }
                        tag.equals("trkpt", ignoreCase = true) || tag.equals("rtept", ignoreCase = true) -> {
                            inPoint = false
                            currentPointValid = false
                        }
                        tag.equals("metadata", ignoreCase = true) -> {
                            inMetadata = false
                        }
                    }
                }
            }

            parser.parse(inputStream, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val distanceMeters = computeDistanceMeters(points)
        val first = firstPointTime
        val last = lastPointTime
        val minTs = minPointTime
        val maxTs = maxPointTime
        val durationMillis = if (pointTimeCount >= 2 && first != null && last != null && minTs != null && maxTs != null) {
            if (last >= first) last - first else (maxTs - minTs).coerceAtLeast(0L)
        } else {
            null
        }
        val recordedAt = firstPointTime ?: metadataTime

        return GpxParseResult(
            points = points,
            distanceMeters = distanceMeters,
            durationMillis = durationMillis,
            recordedAt = recordedAt
        )
    }

    fun computeDistanceMeters(points: List<GpsPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            total += MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
        }
        return total
    }

    internal fun parseIsoTimestamp(raw: String): Long? {
        if (raw.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}
