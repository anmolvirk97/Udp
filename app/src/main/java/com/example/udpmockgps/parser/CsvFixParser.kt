package com.example.udpmockgps.parser

import com.example.udpmockgps.data.GpsFix

/**
 * CSV: lat,lon,alt,speed,bearing,accuracy
 * Only lat and lon are mandatory; trailing/blank fields are treated as "not provided".
 * One fix per line; blank lines and lines starting with '#' are skipped.
 */
object CsvFixParser {

    fun parse(packet: String): ParseResult {
        val lines = packet.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return ParseResult(emptyList(), listOf("Empty packet"))

        val fixes = mutableListOf<GpsFix>()
        val errors = mutableListOf<String>()
        for (line in lines) {
            val parts = line.split(',').map { it.trim() }
            if (parts.size < 2 || parts.size > 6) {
                errors += "Expected 2-6 CSV fields (lat,lon,alt,speed,bearing,accuracy): '${line.take(60)}'"
                continue
            }
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat == null || lon == null || !isValidPosition(lat, lon)) {
                errors += "Invalid lat/lon: '${line.take(60)}'"
                continue
            }
            val alt = optional(parts, 2)
            val speed = optional(parts, 3)
            val bearing = optional(parts, 4)
            val acc = optional(parts, 5)
            if (listOf(alt, speed, bearing, acc).any { it != null && it.isNaN() }) {
                errors += "Non-numeric optional field: '${line.take(60)}'"
                continue
            }
            fixes += GpsFix(lat, lon, alt, speed?.toFloat(), bearing?.toFloat(), acc?.toFloat())
        }
        return ParseResult(fixes, errors)
    }

    /** null = not provided, NaN = provided but malformed. */
    private fun optional(parts: List<String>, index: Int): Double? {
        val s = parts.getOrNull(index)
        if (s.isNullOrEmpty()) return null
        val v = s.toDoubleOrNull()
        return if (v != null && v.isFinite()) v else Double.NaN
    }
}
