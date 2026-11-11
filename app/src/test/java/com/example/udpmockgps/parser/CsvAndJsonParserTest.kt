package com.example.udpmockgps.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvAndJsonParserTest {

    @Test
    fun csv_fullLine() {
        val fix = CsvFixParser.parse("28.5,77.25,215.5,3.2,90,4.5").fixes.single()
        assertEquals(28.5, fix.latitude, 1e-9)
        assertEquals(77.25, fix.longitude, 1e-9)
        assertEquals(215.5, fix.altitude!!, 1e-9)
        assertEquals(3.2f, fix.speedMps!!, 1e-6f)
        assertEquals(90f, fix.bearingDeg!!, 1e-6f)
        assertEquals(4.5f, fix.accuracyM!!, 1e-6f)
    }

    @Test
    fun csv_onlyLatLonAndBlankFields() {
        val a = CsvFixParser.parse("-10.5,20.5").fixes.single()
        assertNull(a.altitude); assertNull(a.speedMps)
        val b = CsvFixParser.parse("-10.5,20.5,,,,7").fixes.single()
        assertNull(b.altitude); assertEquals(7f, b.accuracyM!!, 1e-6f)
    }

    @Test
    fun csv_errors() {
        assertEquals(1, CsvFixParser.parse("abc,def").errors.size)
        assertEquals(1, CsvFixParser.parse("91,0").errors.size)
        assertEquals(1, CsvFixParser.parse("1,2,x").errors.size)
        assertEquals(1, CsvFixParser.parse("1").errors.size)
        assertTrue(CsvFixParser.parse("1,2,3,4,5,6,7").fixes.isEmpty())
    }

    @Test
    fun csv_multipleLinesAndComments() {
        val r = CsvFixParser.parse("# header\n1,2\n\n3,4\n")
        assertEquals(2, r.fixes.size)
    }

    @Test
    fun json_fullObject() {
        val fix = JsonFixParser.parse("""{"lat":12.5,"lon":-45.25,"alt":10,"speed":2.5,"bearing":180,"acc":3}""")
            .fixes.single()
        assertEquals(12.5, fix.latitude, 1e-9)
        assertEquals(-45.25, fix.longitude, 1e-9)
        assertEquals(10.0, fix.altitude!!, 1e-9)
        assertEquals(2.5f, fix.speedMps!!, 1e-6f)
        assertEquals(180f, fix.bearingDeg!!, 1e-6f)
        assertEquals(3f, fix.accuracyM!!, 1e-6f)
    }

    @Test
    fun json_minimalAndAliases() {
        val a = JsonFixParser.parse("""{"lat":1,"lon":2}""").fixes.single()
        assertNull(a.altitude); assertNull(a.accuracyM)
        val b = JsonFixParser.parse("""{"latitude":1.5,"longitude":2.5,"accuracy":9}""").fixes.single()
        assertEquals(9f, b.accuracyM!!, 1e-6f)
    }

    @Test
    fun json_errors() {
        assertTrue(JsonFixParser.parse("not json").errors.isNotEmpty())
        assertTrue(JsonFixParser.parse("""{"lat":1}""").errors.isNotEmpty())
        assertTrue(JsonFixParser.parse("""{"lat":100,"lon":0}""").errors.isNotEmpty())
    }

    @Test
    fun json_arrayAndNewlineDelimited() {
        assertEquals(2, JsonFixParser.parse("""[{"lat":1,"lon":2},{"lat":3,"lon":4}]""").fixes.size)
        assertEquals(2, JsonFixParser.parse("{\"lat\":1,\"lon\":2}\n{\"lat\":3,\"lon\":4}").fixes.size)
    }
}
