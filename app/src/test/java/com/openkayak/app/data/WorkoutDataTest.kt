package com.openkayak.app.data

import com.openkayak.app.ui.parseJsonRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutDataTest {

    @Test
    fun testParseJsonRoute_validJson() {
        val json = """[{"lat": 43.3614, "lon": -5.8593}, {"lat": 43.3615, "lon": -5.8594}]"""
        val points = parseJsonRoute(json)

        assertEquals(2, points.size)
        assertEquals(43.3614, points[0].latitude, 0.0001)
        assertEquals(-5.8593, points[0].longitude, 0.0001)
        assertEquals(43.3615, points[1].latitude, 0.0001)
        assertEquals(-5.8594, points[1].longitude, 0.0001)
    }

    @Test
    fun testParseJsonRoute_emptyJson() {
        val points = parseJsonRoute("[]")
        assertTrue(points.isEmpty())
    }

    @Test
    fun testParseJsonRoute_invalidJson() {
        val points = parseJsonRoute("invalid_json_string")
        assertTrue(points.isEmpty())
    }

    @Test
    fun testWorkoutEntityCreation() {
        val entity = WorkoutEntity(
            id = 1,
            timestamp = 1000L,
            durationSeconds = 300L,
            distanceMeters = 1500f,
            maxSpeedKmh = 12.5f,
            avgSpeedKmh = 10.0f,
            totalStrokes = 250,
            avgStrokeRateSpm = 50,
            estimatedCalories = 120,
            routeGpsJson = "[]"
        )

        assertEquals(1, entity.id)
        assertEquals(300L, entity.durationSeconds)
        assertEquals(1500f, entity.distanceMeters, 0.1f)
        assertEquals(250, entity.totalStrokes)
    }
}
