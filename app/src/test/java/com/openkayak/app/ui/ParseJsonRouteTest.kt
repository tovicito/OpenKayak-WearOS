package com.openkayak.app.ui

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ParseJsonRouteTest {

    @Test
    fun testParseJsonRoute_validJson() {
        val json = "[{\"lat\":43.3614,\"lon\":-5.8593},{\"lat\":43.3615,\"lon\":-5.8594}]"
        val points = parseJsonRoute(json)
        assertEquals(2, points.size)
        assertEquals(43.3614, points[0].latitude, 0.0001)
        assertEquals(-5.8593, points[0].longitude, 0.0001)
        assertEquals(43.3615, points[1].latitude, 0.0001)
        assertEquals(-5.8594, points[1].longitude, 0.0001)
    }

    @Test
    fun testParseJsonRoute_malformedJson() {
        val json = "[{\"lat\":43.3614,\"lon\":-5.8593},invalid_json]"
        val points = parseJsonRoute(json)
        assertTrue(points.isEmpty())
    }

    @Test
    fun testParseJsonRoute_emptyString() {
        val points = parseJsonRoute("")
        assertTrue(points.isEmpty())
    }

    @Test
    fun testParseJsonRoute_missingKeys() {
        val json = "[{\"other\":123}]"
        val points = parseJsonRoute(json)
        assertTrue(points.isEmpty())
    }
}
