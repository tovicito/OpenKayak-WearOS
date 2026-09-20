package com.openkayak.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutAndCircuitTest {

    @Test
    fun testWorkoutEntityCreation() {
        val entity = WorkoutEntity(
            id = 1L,
            timestamp = 1700000000000L,
            durationSeconds = 1800L,
            distanceMeters = 5000.0f,
            maxSpeedKmh = 12.5f,
            avgSpeedKmh = 10.0f,
            totalStrokes = 1500,
            avgStrokeRateSpm = 50,
            estimatedCalories = 350,
            routeGpsJson = "[{\"lat\":43.56,\"lon\":-5.87}]"
        )

        assertEquals(1L, entity.id)
        assertEquals(1800L, entity.durationSeconds)
        assertEquals(5000.0f, entity.distanceMeters, 0.01f)
        assertEquals(12.5f, entity.maxSpeedKmh, 0.01f)
        assertEquals(1500, entity.totalStrokes)
        assertEquals(350, entity.estimatedCalories)
    }

    @Test
    fun testCalorieCalculationKeytelFormulaMale() {
        val durationSec = 3600L // 60 minutes
        val avgBpm = 140
        val age = 30
        val weightKg = 75f
        val isMale = true

        val minutes = durationSec / 60f
        val bpm = if (avgBpm > 0) avgBpm else 125

        val calories = if (isMale) {
            ((-55.0969 + (0.6309 * bpm) + (0.1988 * weightKg) + (0.2017 * age)) / 4.184) * minutes
        } else {
            ((-20.4022 + (0.4472 * bpm) - (0.1263 * weightKg) + (0.074 * age)) / 4.184) * minutes
        }
        val result = calories.coerceAtLeast(0.0).toInt()

        assertTrue(result > 500)
        assertTrue(result < 800)
    }

    @Test
    fun testCalorieCalculationKeytelFormulaFemale() {
        val durationSec = 1800L // 30 minutes
        val avgBpm = 130
        val age = 28
        val weightKg = 60f
        val isMale = false

        val minutes = durationSec / 60f
        val bpm = if (avgBpm > 0) avgBpm else 125

        val calories = if (isMale) {
            ((-55.0969 + (0.6309 * bpm) + (0.1988 * weightKg) + (0.2017 * age)) / 4.184) * minutes
        } else {
            ((-20.4022 + (0.4472 * bpm) - (0.1263 * weightKg) + (0.074 * age)) / 4.184) * minutes
        }
        val result = calories.coerceAtLeast(0.0).toInt()

        assertTrue(result > 150)
        assertTrue(result < 400)
    }

    @Test
    fun testRouteJsonSerializationDeserialization() {
        val jsonArray = JSONArray()
        val pt1 = JSONObject().apply {
            put("lat", 43.5612)
            put("lon", -5.8712)
        }
        val pt2 = JSONObject().apply {
            put("lat", 43.5620)
            put("lon", -5.8720)
        }
        jsonArray.put(pt1)
        jsonArray.put(pt2)

        val jsonString = jsonArray.toString()
        val parsedArray = JSONArray(jsonString)

        assertEquals(2, parsedArray.length())
        assertEquals(43.5612, parsedArray.getJSONObject(0).getDouble("lat"), 0.0001)
        assertEquals(-5.8712, parsedArray.getJSONObject(0).getDouble("lon"), 0.0001)
    }

    @Test
    fun testLearnedCircuitJsonFormat() {
        val circuitJson = JSONObject().apply {
            put("id", 101L)
            put("name", "Embalse Trasona 1000m")
            put("startLat", 43.53)
            put("startLon", -5.90)
            put("turnLat", 43.55)
            put("turnLon", -5.92)
            put("totalLaps", 5)
            put("isDeleted", false)
        }

        assertEquals(101L, circuitJson.getLong("id"))
        assertEquals("Embalse Trasona 1000m", circuitJson.getString("name"))
        assertEquals(5, circuitJson.getInt("totalLaps"))
    }
}
