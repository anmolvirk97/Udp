package com.example.udpmockgps.parser

import com.example.udpmockgps.data.GpsFix
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * {"lat":..,"lon":..,"alt":..,"speed":..,"bearing":..,"acc":..}
 * Accepts a single object, an array of objects, or newline-delimited objects.
 * Aliases: latitude, lng/longitude, altitude, course/heading, accuracy.
 */
object JsonFixParser {

    fun parse(packet: String): ParseResult {
        val text = packet.trim()
        if (text.isEmpty()) return ParseResult(emptyList(), listOf("Empty packet"))

        val errors = mutableListOf<String>()
        val objects = mutableListOf<JSONObject>()
        try {
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i)
                    if (o != null) objects += o else errors += "Array element $i is not a JSON object"
                }
            } else {
                objects += JSONObject(text)
            }
        } catch (e: JSONException) {
            objects.clear()
            errors.clear()
            for (line in text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
                try {
                    objects += JSONObject(line)
                } catch (e2: JSONException) {
                    errors += "Invalid JSON: ${e2.message}"
                }
            }
        }
        val fixes = objects.mapNotNull { toFix(it, errors) }
        return ParseResult(fixes, errors)
    }

    private fun toFix(o: JSONObject, errors: MutableList<String>): GpsFix? {
        val lat = number(o, "lat", "latitude")
        val lon = number(o, "lon", "lng", "longitude")
        if (lat == null || lon == null) {
            errors += "JSON is missing numeric lat/lon"
            return null
        }
        if (!isValidPosition(lat, lon)) {
            errors += "Coordinates out of range: $lat, $lon"
            return null
        }
        return GpsFix(
            latitude = lat,
            longitude = lon,
            altitude = number(o, "alt", "altitude"),
            speedMps = number(o, "speed")?.toFloat(),
            bearingDeg = number(o, "bearing", "course", "heading")?.toFloat(),
            accuracyM = number(o, "acc", "accuracy")?.toFloat(),
        )
    }

    private fun number(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val v = o.optDouble(k, Double.NaN)
                if (v.isFinite()) return v
            }
        }
        return null
    }
}
