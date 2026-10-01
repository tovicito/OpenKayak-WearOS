package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class PointsToJsonTest {

    @Test
    fun testParseJsonRouteWithJSONArray() {
        val points = listOf(
            GpsPoint(43.3614, -5.8593, 0.0, 1000L),
            GpsPoint(43.3620, -5.8580, 0.0, 2000L)
        )

        val array = JSONArray()
        for (p in points) {
            val obj = org.json.JSONObject()
            obj.put("lat", p.latitude)
            obj.put("lon", p.longitude)
            array.put(obj)
        }
        val jsonStr = array.toString()

        val parsedPoints = parseJsonRoute(jsonStr)
        assertEquals(2, parsedPoints.size)
        assertEquals(43.3614, parsedPoints[0].latitude, 0.0001)
        assertEquals(-5.8593, parsedPoints[0].longitude, 0.0001)
        assertEquals(43.3620, parsedPoints[1].latitude, 0.0001)
        assertEquals(-5.8580, parsedPoints[1].longitude, 0.0001)
    }

    @Test
    fun testParseJsonRouteEmpty() {
        val array = JSONArray()
        val jsonStr = array.toString()
        val parsedPoints = parseJsonRoute(jsonStr)
        assertEquals(0, parsedPoints.size)
    }
}
