package com.openkayak.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteParsingTest {

    @Test
    fun testParseJsonRouteWithValidAndInvalidCoordinates() {
        val json = """
            [
                {"lat": 43.3614, "lon": -5.8593},
                {"lat": 100.0, "lon": -5.8593},
                {"lat": 43.3614, "lon": -200.0},
                {"lat": 43.5000, "lon": -5.6600}
            ]
        """.trimIndent()

        val points = parseJsonRoute(json)

        // Only two points are valid: (43.3614, -5.8593) and (43.5000, -5.6600)
        assertEquals(2, points.size)
        assertEquals(43.3614, points[0].latitude, 0.0001)
        assertEquals(-5.8593, points[0].longitude, 0.0001)
        assertEquals(43.5000, points[1].latitude, 0.0001)
        assertEquals(-5.6600, points[1].longitude, 0.0001)
    }

    @Test
    fun testParseJsonRouteWithEmptyOrMalformedJson() {
        assertEquals(0, parseJsonRoute("").size)
        assertEquals(0, parseJsonRoute("invalid json").size)
    }
}
