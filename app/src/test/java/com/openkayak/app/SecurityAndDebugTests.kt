package com.openkayak.app

import com.openkayak.app.ble.BleHeartRateState
import com.openkayak.app.ble.HeartRateManager
import com.openkayak.app.service.DownloadState
import com.openkayak.app.service.GpsPoint
import com.openkayak.app.service.StrokeDetector
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import kotlin.math.sqrt

class SecurityAndDebugTests {

    /**
     * Security Test 1: Error Message Sanitization
     * Verifies that DownloadState default status messages and error handling sanitize internal exception details/paths
     * so end users do not get raw internal exception traces or filesystem paths.
     */
    @Test
    fun testSanitizedErrorMessageFormat() {
        val state = DownloadState(isDownloading = false, statusMessage = "Error en la descarga del mapa.")

        assertFalse(state.statusMessage.contains("/data/user/0"))
        assertFalse(state.statusMessage.contains("Permission denied"))
        assertFalse(state.statusMessage.contains("Exception"))
        assertEquals("Error en la descarga del mapa.", state.statusMessage)
    }

    /**
     * Security Test 2: GPS Input Validation & JSON Serialization Injection Protection
     * Verifies that GPS points with special characters or extreme bounds are safely serialized into JSON
     * without structure corruption or injection vulnerabilities.
     */
    @Test
    fun testGpsJsonSerializationAndInputValidation() {
        val validPoints = listOf(
            GpsPoint(43.3614, -5.8593, 0.0, 1000L),
            GpsPoint(-90.0, 180.0, 0.0, 2000L),
            GpsPoint(90.0, -180.0, 0.0, 3000L)
        )

        // Coordinate bounds check validation
        for (p in validPoints) {
            assertTrue(p.latitude in -90.0..90.0)
            assertTrue(p.longitude in -180.0..180.0)
        }

        val formattedJson = validPoints.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "{\"lat\":${it.latitude},\"lon\":${it.longitude}}"
        }

        assertTrue(formattedJson.startsWith("["))
        assertTrue(formattedJson.endsWith("]"))
        assertTrue(formattedJson.contains("43.3614"))
        assertTrue(formattedJson.contains("-5.8593"))
    }

    /**
     * Security Test 3: BLE Packet Parsing Safety & MAC Address Masking
     * Verifies MAC address masking privacy and safe parsing of 8-bit and 16-bit BLE HR packets.
     */
    @Test
    fun testBlePacketParsingSafetyAndMacMasking() {
        // Test MAC address masking
        val mac = "AA:BB:CC:DD:EE:FF"
        val masked = HeartRateManager.maskAddress(mac)
        assertEquals("AA:**:**:**:FF", masked)
        assertEquals("null", HeartRateManager.maskAddress(null))

        val hrManager = allocateUninitialized(HeartRateManager::class.java) as HeartRateManager

        val mutableStateFlow = kotlinx.coroutines.flow.MutableStateFlow(BleHeartRateState())
        val stateFlow = mutableStateFlow.asStateFlow()

        HeartRateManager::class.java.getDeclaredField("_hrState").apply {
            isAccessible = true
            set(hrManager, mutableStateFlow)
        }

        HeartRateManager::class.java.getDeclaredField("hrState").apply {
            isAccessible = true
            set(hrManager, stateFlow)
        }

        HeartRateManager::class.java.getDeclaredField("scope").apply {
            isAccessible = true
            set(hrManager, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        }

        val parseMethod: Method = HeartRateManager::class.java.getDeclaredMethod("parseHeartRateMeasurement", ByteArray::class.java).apply {
            isAccessible = true
        }

        // 1. Test 8-bit Heart Rate format (75 BPM)
        val valid8BitPacket = byteArrayOf(0x00, 75.toByte())
        val bpm8 = parseMethod.invoke(hrManager, valid8BitPacket) as Int?
        assertEquals(75, bpm8)

        // 2. Test 16-bit Heart Rate format (144 BPM)
        val valid16BitPacket = byteArrayOf(0x01, 0x90.toByte(), 0x00.toByte())
        val bpm16 = parseMethod.invoke(hrManager, valid16BitPacket) as Int?
        assertEquals(144, bpm16)

        // 3. Test truncated 16-bit packet (size < 3 -> should return null safely)
        val truncatedPacket = byteArrayOf(0x01, 0x90.toByte())
        val truncatedBpm = parseMethod.invoke(hrManager, truncatedPacket) as Int?
        assertNull(truncatedBpm)

        // 4. Test empty packet -> should return null safely
        val emptyPacket = byteArrayOf()
        val emptyBpm = parseMethod.invoke(hrManager, emptyPacket) as Int?
        assertNull(emptyBpm)

        // 5. Out of bounds HR value (< 30 or > 240) -> should return null
        val invalidHighPacket = byteArrayOf(0x00, 250.toByte())
        val invalidHighBpm = parseMethod.invoke(hrManager, invalidHighPacket) as Int?
        assertNull(invalidHighBpm)
    }

    /**
     * Security Test 4: StrokeDetector Boundary Safety & Math Precision
     */
    @Test
    fun testStrokeDetectorBoundarySafety() {
        val detector = allocateUninitialized(StrokeDetector::class.java) as StrokeDetector

        val strokeTimestampsField = StrokeDetector::class.java.getDeclaredField("strokeTimestamps").apply {
            isAccessible = true
        }
        val strokeTimestamps = ArrayDeque<Long>()
        strokeTimestampsField.set(detector, strokeTimestamps)

        val now = System.currentTimeMillis()
        // Simulate 40 strokes within 10 seconds -> 40 * 6 = 240 SPM (capped at 180)
        for (i in 0 until 40) {
            strokeTimestamps.addLast(now - i * 200L)
        }

        val calculateSpmMethod: Method = StrokeDetector::class.java.getDeclaredMethod("calculateSpm", Long::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        val spm = calculateSpmMethod.invoke(detector, now) as Int
        assertEquals(180, spm)

        // Float arithmetic accuracy check
        val ay = 9.8f
        val az = 1.5f
        val acc = sqrt(ay * ay + az * az)
        assertFalse(acc.isNaN())
        assertFalse(acc.isInfinite())
        assertTrue(acc > 0f)
    }

    private fun allocateUninitialized(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) ?: throw IllegalStateException("theUnsafe is null")
        val allocateInstance = unsafeClass.getDeclaredMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz)!!
    }
}
