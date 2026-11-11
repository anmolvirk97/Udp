package com.example.udpmockgps.parser

import com.example.udpmockgps.data.DataFormat
import com.example.udpmockgps.data.GpsFix

/**
 * Result of parsing one UDP packet.
 * [ignored] counts well-formed sentences that were deliberately skipped (e.g. RMC status "V", GGA fix quality 0).
 */
data class ParseResult(
    val fixes: List<GpsFix>,
    val errors: List<String> = emptyList(),
    val ignored: Int = 0,
)

internal fun isValidPosition(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

object PacketParser {
    fun parse(text: String, format: DataFormat): ParseResult = when (format) {
        DataFormat.NMEA -> NmeaParser.parse(text)
        DataFormat.JSON -> JsonFixParser.parse(text)
        DataFormat.CSV -> CsvFixParser.parse(text)
    }
}
