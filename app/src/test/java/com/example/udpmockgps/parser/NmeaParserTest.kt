package com.example.udpmockgps.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaParserTest {

    // Canonical examples from the NMEA 0183 reference material (checksums 6A / 47).
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

    private fun sentence(body: String) = NmeaParser.buildSentence(body)

    // ------------------------------------------------------------ checksum

    @Test
    fun checksum_matchesKnownValues() {
        assertEquals(0x6A, NmeaParser.checksum(rmc.substring(1, rmc.indexOf('*'))))
        assertEquals(0x47, NmeaParser.checksum(gga.substring(1, gga.indexOf('*'))))
    }

    @Test
    fun checksum_mismatchIsRejected() {
        val bad = rmc.replace("*6A", "*6B")
        val r = NmeaParser.parse(bad)
        assertTrue(r.fixes.isEmpty())
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0].contains("Checksum mismatch"))
    }

    @Test
    fun checksum_lowercaseHexIsAccepted() {
        val r = NmeaParser.parse(rmc.replace("*6A", "*6a"))
        assertEquals(1, r.fixes.size)
    }

    @Test
    fun checksum_malformedIsRejected() {
        val r = NmeaParser.parse(rmc.replace("*6A", "*ZZ"))
        assertTrue(r.fixes.isEmpty())
        assertTrue(r.errors[0].contains("Malformed checksum"))
    }

    @Test
    fun checksum_missingRejectedByDefaultAcceptedWhenOptional() {
        val noChecksum = rmc.substringBefore('*')
        assertTrue(NmeaParser.parse(noChecksum).fixes.isEmpty())
        assertTrue(NmeaParser.parse(noChecksum).errors[0].contains("Missing checksum"))
        assertEquals(1, NmeaParser.parse(noChecksum, requireChecksum = false).fixes.size)
    }

    // ------------------------------------------------------------ conversion

    @Test
    fun ddmmConversion_latitudeAndLongitude() {
        assertEquals(48.1173, NmeaParser.parseCoordinate("4807.038", "N", true)!!, 1e-9)
        assertEquals(11.516667, NmeaParser.parseCoordinate("01131.000", "E", false)!!, 1e-6)
        assertEquals(0.5, NmeaParser.parseCoordinate("0030.0000", "N", true)!!, 1e-9)
        assertEquals(179.999833, NmeaParser.parseCoordinate("17959.9900", "E", false)!!, 1e-6)
    }

    @Test
    fun ddmmConversion_rejectsMalformedAndOutOfRange() {
        assertNull(NmeaParser.parseCoordinate("", "N", true))
        assertNull(NmeaParser.parseCoordinate("7.5", "N", true))        // no degree digits
        assertNull(NmeaParser.parseCoordinate("4860.000", "N", true))   // minutes >= 60
        assertNull(NmeaParser.parseCoordinate("9100.000", "N", true))   // lat > 90
        assertNull(NmeaParser.parseCoordinate("18100.000", "E", false)) // lon > 180
        assertNull(NmeaParser.parseCoordinate("4807.038", "X", true))   // bad hemisphere
        assertNull(NmeaParser.parseCoordinate("4807.038", "E", true))   // wrong hemisphere for lat
        assertNull(NmeaParser.parseCoordinate("01131.000", "N", false)) // wrong hemisphere for lon
    }

    @Test
    fun hemisphereSigns() {
        assertEquals(-48.1173, NmeaParser.parseCoordinate("4807.038", "S", true)!!, 1e-9)
        assertEquals(-11.516667, NmeaParser.parseCoordinate("01131.000", "W", false)!!, 1e-6)

        fun fixFor(latH: String, lonH: String): com.example.udpmockgps.data.GpsFix {
            val s = sentence("GPRMC,123519,A,3352.0000,$latH,15112.0000,$lonH,0.0,0.0,230394,,,A")
            return NmeaParser.parse(s).fixes.single()
        }
        val ne = fixFor("N", "E")
        assertEquals(33.866667, ne.latitude, 1e-6); assertEquals(151.2, ne.longitude, 1e-9)
        val se = fixFor("S", "E")
        assertEquals(-33.866667, se.latitude, 1e-6); assertEquals(151.2, se.longitude, 1e-9)
        val sw = fixFor("S", "W")
        assertEquals(-33.866667, sw.latitude, 1e-6); assertEquals(-151.2, sw.longitude, 1e-9)
        val nw = fixFor("N", "W")
        assertEquals(33.866667, nw.latitude, 1e-6); assertEquals(-151.2, nw.longitude, 1e-9)
    }

    @Test
    fun knotsToMetresPerSecond() {
        val r = NmeaParser.parse(rmc)
        val fix = r.fixes.single()
        assertEquals(22.4 * 0.514444, fix.speedMps!!.toDouble(), 1e-3)
        assertEquals(84.4f, fix.bearingDeg!!, 1e-3f)

        val ten = sentence("GPRMC,123519,A,4807.038,N,01131.000,E,10.0,0.0,230394,,,A")
        assertEquals(5.14444f, NmeaParser.parse(ten).fixes.single().speedMps!!, 1e-4f)
    }

    // ------------------------------------------------------------ sentence handling

    @Test
    fun rmc_validFix() {
        val r = NmeaParser.parse(rmc)
        assertTrue(r.errors.isEmpty())
        val fix = r.fixes.single()
        assertEquals(48.1173, fix.latitude, 1e-9)
        assertEquals(11.516667, fix.longitude, 1e-6)
        assertNull(fix.altitude)
    }

    @Test
    fun rmc_statusVIsIgnored() {
        val s = sentence("GPRMC,123519,V,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")
        val r = NmeaParser.parse(s)
        assertTrue(r.fixes.isEmpty())
        assertTrue(r.errors.isEmpty())
        assertEquals(1, r.ignored)
    }

    @Test
    fun gga_altitudeAndHdopAccuracy() {
        val fix = NmeaParser.parse(gga).fixes.single()
        assertEquals(48.1173, fix.latitude, 1e-9)
        assertEquals(545.4, fix.altitude!!, 1e-9)
        assertEquals(0.9f * 5f, fix.accuracyM!!, 1e-3f)
        assertNull(fix.speedMps)
    }

    @Test
    fun gga_fixQualityZeroIsIgnored() {
        val s = sentence("GPGGA,123519,4807.038,N,01131.000,E,0,00,99.9,545.4,M,46.9,M,,")
        val r = NmeaParser.parse(s)
        assertTrue(r.fixes.isEmpty())
        assertEquals(1, r.ignored)
    }

    @Test
    fun gnTalkerIsAccepted() {
        val s = sentence("GNRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")
        assertEquals(1, NmeaParser.parse(s).fixes.size)
        val g = sentence("GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        assertEquals(1, NmeaParser.parse(g).fixes.size)
    }

    @Test
    fun multipleSentencesInOnePacket_areMergedIntoOneFix() {
        val packet = rmc + "\r\n" + gga + "\r\n" +
            sentence("GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00") + "\r\n"
        val r = NmeaParser.parse(packet)
        assertTrue(r.errors.isEmpty())
        val fix = r.fixes.single()
        assertEquals(545.4, fix.altitude!!, 1e-9)
        assertEquals(22.4 * 0.514444, fix.speedMps!!.toDouble(), 1e-3)
        assertEquals(84.4f, fix.bearingDeg!!, 1e-3f)
        assertEquals(4.5f, fix.accuracyM!!, 1e-3f)
    }

    @Test
    fun differentEpochsInOnePacket_produceTwoFixes() {
        val a = sentence("GPRMC,123519,A,4807.038,N,01131.000,E,1.0,10.0,230394,,,A")
        val b = sentence("GPRMC,123520,A,4807.040,N,01131.000,E,1.0,10.0,230394,,,A")
        val c = sentence("GPGGA,123521,4807.042,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        val fixes = NmeaParser.parse(a + b + c).fixes   // concatenated without separators
        assertEquals(3, fixes.size)
    }

    @Test
    fun validSentencesSurviveAlongsideCorruptOnes() {
        val packet = rmc.replace("*6A", "*00") + "\n" + gga
        val r = NmeaParser.parse(packet)
        assertEquals(1, r.errors.size)
        assertEquals(1, r.fixes.size)
        assertNotNull(r.fixes[0].altitude)
    }

    @Test
    fun garbageAndEmptyPacketsReportErrors() {
        assertEquals(1, NmeaParser.parse("hello world").errors.size)
        assertEquals(1, NmeaParser.parse("   ").errors.size)
        assertTrue(NmeaParser.parse("hello world").fixes.isEmpty())
    }

    @Test
    fun tooShortSentenceIsAnError() {
        val r = NmeaParser.parse(sentence("GPRMC,123519,A,4807.038,N"))
        assertTrue(r.fixes.isEmpty())
        assertEquals(1, r.errors.size)
    }
}
