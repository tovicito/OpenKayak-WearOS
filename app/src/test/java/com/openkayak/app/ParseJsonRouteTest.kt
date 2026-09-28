package com.openkayak.app

import com.openkayak.app.ui.parseJsonRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseJsonRouteTest {

    @Test
    fun parseJsonRoute_validCoordinates_returnsPoints() {
        val json = """[{"lat": 43.3614, "lon": -5.8593}, {"lat": 43.3620, "lon": -5.8580}]"""
        val points = parseJsonRoute(json)

        assertEquals(2, points.size)
        assertEquals(43.3614, points[0].latitude, 0.0001)
        assertEquals(-5.8593, points[0].longitude, 0.0001)
        assertEquals(43.3620, points[1].latitude, 0.0001)
        assertEquals(-5.8580, points[1].longitude, 0.0001)
    }

    @Test
    fun parseJsonRoute_invalidCoordinates_filtersOutBadPoints() {
        val json = """[
            {"lat": 43.3614, "lon": -5.8593},
            {"lat": 100.0, "lon": -5.8580},
            {"lat": 43.3620, "lon": -200.0},
            {"lat": "invalid", "lon": -5.8580},
            {"lat": 43.3630, "lon": -5.8570}
        ]"""
        val points = parseJsonRoute(json)

        assertEquals(2, points.size)
        assertEquals(43.3614, points[0].latitude, 0.0001)
        assertEquals(43.3630, points[1].latitude, 0.0001)
    }

    @Test
    fun parseJsonRoute_malformedJson_returnsEmptyList() {
        val json = "{ bad json }"
        val points = parseJsonRoute(json)

        assertTrue(points.isEmpty())
    }
}
