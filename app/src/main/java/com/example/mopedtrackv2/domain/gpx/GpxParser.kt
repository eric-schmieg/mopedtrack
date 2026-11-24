package com.example.mopedtrackv2.domain.gpx

import android.util.Xml
import com.example.mopedtrackv2.domain.engine.GpsPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object GpxParser {

    fun parse(inputStream: InputStream): List<GpsPoint> {
        val points = mutableListOf<GpsPoint>()

        try {
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val name = parser.name
                    if (name.equals("trkpt", ignoreCase = true) || name.equals("rtept", ignoreCase = true)) {
                        val latStr = parser.getAttributeValue(null, "lat")
                        val lonStr = parser.getAttributeValue(null, "lon")

                        val lat = latStr?.toDoubleOrNull()
                        val lon = lonStr?.toDoubleOrNull()

                        if (lat != null && lon != null) {
                            points.add(GpsPoint(lat, lon))
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return points
    }
}
