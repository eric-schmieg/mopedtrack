package com.example.mopedtrackv2.utils

object PolylineUtils {
    fun encode(path: List<Pair<Double, Double>>): String {
        val result = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L

        for (point in path) {
            val lat = Math.round(point.first * 1e5)
            val lng = Math.round(point.second * 1e5)

            val dLat = lat - lastLat
            val dLng = lng - lastLng

            encodeCoordinate(dLat, result)
            encodeCoordinate(dLng, result)

            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    fun encodeToJson(path: List<Pair<Double, Double>>): String {
        return path.joinToString(prefix = "[", postfix = "]") { point ->
            "{\"lat\":${point.first},\"lng\":${point.second}}"
        }
    }

    private fun encodeCoordinate(coordinate: Long, result: StringBuilder) {
        var coord = coordinate
        coord = if (coord < 0) (coord shl 1).inv() else (coord shl 1)
        while (coord >= 0x20) {
            result.append(((0x20 or (coord and 0x1f).toInt()) + 63).toChar())
            coord = coord shr 5
        }
        result.append((coord.toInt() + 63).toChar())
    }

    fun decode(encoded: String): List<Pair<Double, Double>> {
        if (encoded.isBlank()) return emptyList()

        val trimmed = encoded.trim()
        if (trimmed.startsWith("[")) {
            return parseJsonGeometry(trimmed)
        }

        val poly = ArrayList<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        try {
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                var truncated = false

                do {
                    if (index >= len) {
                        truncated = true
                        break
                    }
                    b = encoded[index++].code - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)

                if (truncated) break

                val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lat += dlat

                if (index >= len) break

                shift = 0
                result = 0
                do {
                    if (index >= len) {
                        truncated = true
                        break
                    }
                    b = encoded[index++].code - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)

                if (truncated) break

                val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lng += dlng

                val p = Pair(lat.toDouble() / 1e5, lng.toDouble() / 1e5)
                poly.add(p)
            }
        } catch (_: Exception) {
            // Fallback gracefully without crashing or logging spam
        }

        return poly
    }

    fun parseJsonGeometry(jsonStr: String): List<Pair<Double, Double>> {
        val result = mutableListOf<Pair<Double, Double>>()
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return result

        try {
            if (trimmed.contains("{")) {
                val latFirstRegex = """\{\s*"lat(?:itude)?"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*,\s*"l(?:ng|on|ongitude)?"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*\}""".toRegex(RegexOption.IGNORE_CASE)
                val matches = latFirstRegex.findAll(trimmed)
                for (match in matches) {
                    val lat = match.groupValues[1].toDoubleOrNull()
                    val lon = match.groupValues[2].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        result.add(Pair(lat, lon))
                    }
                }
                if (result.isNotEmpty()) return result

                val lonFirstRegex = """\{\s*"l(?:ng|on|ongitude)?"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*,\s*"lat(?:itude)?"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*\}""".toRegex(RegexOption.IGNORE_CASE)
                val revMatches = lonFirstRegex.findAll(trimmed)
                for (match in revMatches) {
                    val lon = match.groupValues[1].toDoubleOrNull()
                    val lat = match.groupValues[2].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        result.add(Pair(lat, lon))
                    }
                }
            } else {
                val pairRegex = """\[\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*,\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*]""".toRegex()
                val matches = pairRegex.findAll(trimmed)
                for (match in matches) {
                    val lat = match.groupValues[1].toDoubleOrNull()
                    val lon = match.groupValues[2].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        result.add(Pair(lat, lon))
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback gracefully
        }

        return result
    }
}

