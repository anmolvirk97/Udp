package com.example.udpmockgps.parser

import com.example.udpmockgps.data.GpsFix
import java.util.Locale

/**
 * NMEA 0183 parser for RMC and GGA sentences (any talker: GP, GN, GL, GA, BD ...).
 *
 * - The checksum (XOR of all characters between '$' and '*') is validated. A missing checksum is
 *   rejected unless [parse] is called with requireChecksum = false.
 * - Several sentences per packet are supported (CRLF separated or simply concatenated).
 * - RMC with status V and GGA with fix quality 0 are ignored.
 * - An RMC and a GGA with the same UTC time in one packet are merged into one fix.
 */
object NmeaParser {
    const val KNOTS_TO_MPS = 0.514444
    /** Rough user-equivalent range error used to turn HDOP into an accuracy estimate in metres. */
    const val HDOP_TO_ACCURACY_M = 5.0

    private sealed interface Parsed {
        data class Rmc(
            val time: String, val lat: Double, val lon: Double,
            val speedMps: Float?, val course: Float?,
        ) : Parsed

        data class Gga(
            val time: String, val lat: Double, val lon: Double,
            val altitude: Double?, val accuracy: Float?,
        ) : Parsed

        data object Ignored : Parsed
        data object Unsupported : Parsed
        data class Error(val message: String) : Parsed
    }

    private class Pending {
        var rmc: Parsed.Rmc? = null
        var gga: Parsed.Gga? = null
    }

    fun checksum(body: String): Int {
        var c = 0
        for (ch in body) c = c xor ch.code
        return c and 0xFF
    }

    /** Builds "$<body>*<CS>" (no line terminator). */
    fun buildSentence(body: String): String =
        "\$" + body + "*" + String.format(Locale.US, "%02X", checksum(body))

    fun parse(packet: String, requireChecksum: Boolean = true): ParseResult {
        if (packet.isBlank()) return ParseResult(emptyList(), listOf("Empty packet"))
        val sentences = extractSentences(packet)
        if (sentences.isEmpty()) {
            return ParseResult(emptyList(), listOf("No NMEA sentence found (expected \$...*CS)"))
        }

        val fixes = mutableListOf<GpsFix>()
        val errors = mutableListOf<String>()
        var ignored = 0
        val pending = Pending()

        for (sentence in sentences) {
            when (val r = parseSentence(sentence, requireChecksum)) {
                is Parsed.Error -> errors += r.message
                Parsed.Ignored -> ignored++
                Parsed.Unsupported -> Unit
                is Parsed.Rmc -> {
                    val g = pending.gga
                    if (pending.rmc != null || (g != null && timesConflict(g.time, r.time))) flush(pending, fixes)
                    pending.rmc = r
                }
                is Parsed.Gga -> {
                    val m = pending.rmc
                    if (pending.gga != null || (m != null && timesConflict(m.time, r.time))) flush(pending, fixes)
                    pending.gga = r
                }
            }
        }
        flush(pending, fixes)
        return ParseResult(fixes, errors, ignored)
    }

    private fun timesConflict(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && a != b

    private fun flush(p: Pending, out: MutableList<GpsFix>) {
        val rmc = p.rmc
        val gga = p.gga
        if (gga != null) {
            out += GpsFix(gga.lat, gga.lon, gga.altitude, rmc?.speedMps, rmc?.course, gga.accuracy)
        } else if (rmc != null) {
            out += GpsFix(rmc.lat, rmc.lon, null, rmc.speedMps, rmc.course, null)
        }
        p.rmc = null
        p.gga = null
    }

    private fun extractSentences(packet: String): List<String> =
        packet.split('\r', '\n').flatMap { line ->
            if (line.indexOf('$') < 0) emptyList()
            else line.split('$').drop(1).map { it.trim() }.filter { it.isNotEmpty() }
        }

    private fun parseSentence(body: String, requireChecksum: Boolean): Parsed {
        val star = body.lastIndexOf('*')
        val data: String
        if (star >= 0) {
            data = body.substring(0, star)
            val given = body.substring(star + 1).trim()
            val givenValue = (if (given.length == 2) given.toIntOrNull(16) else null)
                ?: return Parsed.Error("Malformed checksum in '\$${body.take(40)}'")
            val expected = checksum(data)
            if (givenValue != expected) {
                return Parsed.Error(
                    "Checksum mismatch in '\$${data.take(24)}…' (got ${given.uppercase()}, expected %02X)".format(expected),
                )
            }
        } else {
            if (requireChecksum) return Parsed.Error("Missing checksum in '\$${body.take(40)}'")
            data = body
        }

        val f = data.split(',')
        val id = f[0].uppercase()
        if (id.length != 5) return Parsed.Unsupported
        return when (id.substring(2)) {
            "RMC" -> parseRmc(f)
            "GGA" -> parseGga(f)
            else -> Parsed.Unsupported
        }
    }

    // $--RMC,time,status,lat,N/S,lon,E/W,speed(kn),course,date,magvar,E/W,mode
    private fun parseRmc(f: List<String>): Parsed {
        if (f.size < 9) return Parsed.Error("RMC sentence too short (${f.size} fields)")
        if (f[2] != "A") return Parsed.Ignored
        val lat = parseCoordinate(f[3], f[4], isLatitude = true)
        val lon = parseCoordinate(f[5], f[6], isLatitude = false)
        if (lat == null || lon == null) return Parsed.Error("RMC has invalid lat/lon")
        val speed = f[7].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { (it * KNOTS_TO_MPS).toFloat() }
        val course = f[8].toDoubleOrNull()?.takeIf { it.isFinite() }?.toFloat()
        return Parsed.Rmc(f[1], lat, lon, speed, course)
    }

    // $--GGA,time,lat,N/S,lon,E/W,quality,sats,hdop,alt,M,geoid,M,age,station
    private fun parseGga(f: List<String>): Parsed {
        if (f.size < 10) return Parsed.Error("GGA sentence too short (${f.size} fields)")
        val quality = f[6].toIntOrNull()
        if (quality == null || quality == 0) return Parsed.Ignored
        val lat = parseCoordinate(f[2], f[3], isLatitude = true)
        val lon = parseCoordinate(f[4], f[5], isLatitude = false)
        if (lat == null || lon == null) return Parsed.Error("GGA has invalid lat/lon")
        val alt = f[9].toDoubleOrNull()?.takeIf { it.isFinite() }
        val accuracy = f[8].toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 }
            ?.let { (it * HDOP_TO_ACCURACY_M).toFloat() }
        return Parsed.Gga(f[1], lat, lon, alt, accuracy)
    }

    /**
     * Converts NMEA ddmm.mmmm (latitude) / dddmm.mmmm (longitude) plus hemisphere letter into signed
     * decimal degrees. Returns null for malformed or out-of-range input.
     */
    internal fun parseCoordinate(value: String, hemisphere: String, isLatitude: Boolean): Double? {
        if (value.isEmpty()) return null
        val dot = value.indexOf('.')
        val intLen = if (dot < 0) value.length else dot
        if (intLen < 3) return null
        val degrees = value.substring(0, intLen - 2).toIntOrNull() ?: return null
        val minutes = value.substring(intLen - 2).toDoubleOrNull() ?: return null
        if (!minutes.isFinite() || minutes < 0.0 || minutes >= 60.0) return null
        val magnitude = degrees + minutes / 60.0
        val h = hemisphere.uppercase()
        val signed = when {
            isLatitude && h == "N" -> magnitude
            isLatitude && h == "S" -> -magnitude
            !isLatitude && h == "E" -> magnitude
            !isLatitude && h == "W" -> -magnitude
            else -> return null
        }
        val limit = if (isLatitude) 90.0 else 180.0
        return if (signed in -limit..limit) signed else null
    }
}
