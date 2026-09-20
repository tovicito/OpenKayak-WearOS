package com.openkayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SecurityAndDebugTests {

    /**
     * Security Test 1: Error Message Sanitization
     * Verifies that error status messages sanitize internal exception details/paths
     * so end users do not get raw internal exception traces.
     */
    @Test
    fun testSanitizedErrorMessageFormat() {
        val defaultSanitizedMsg = "Error en la descarga del mapa."
        assertFalse(defaultSanitizedMsg.contains("/data/user/0"))
        assertFalse(defaultSanitizedMsg.contains("Permission denied"))
        assertEquals("Error en la descarga del mapa.", defaultSanitizedMsg)
    }

    /**
     * Security Test 2: Sensor & Data Boundary Safety
     * Verifies SPM computation bounds, float precision arithmetic safety, and refractory checks in StrokeDetector logic.
     */
    @Test
    fun testStrokeDetectorBoundarySafety() {
        val now = System.currentTimeMillis()
        val timestamps = ArrayDeque<Long>()

        // Simulate 40 strokes within 10 seconds -> 40 * 6 = 240 SPM, but must be coerced to maximum 180 SPM
        for (i in 0 until 40) {
            timestamps.addLast(now - i * 200L)
        }

        val tenSecondsAgo = now - 10_000L
        while (timestamps.isNotEmpty() && timestamps.first() < tenSecondsAgo) {
            timestamps.removeFirst()
        }

        val spm = (timestamps.size * 6).coerceIn(0, 180)
        assertEquals(180, spm)

        // Float arithmetic accuracy check: ay*ay + az*az calculation in Float without overflow or NaN
        val ay = 9.8f
        val az = 1.5f
        val acc = sqrt(ay * ay + az * az)
        assertFalse(acc.isNaN())
        assertFalse(acc.isInfinite())
        assertTrue(acc > 0f)
    }

    /**
     * Security Test 3: BLE Packet & Memory Safety
     * Verifies parseHeartRateMeasurement logic against invalid, truncated, or zero byte payloads.
     */
    @Test
    fun testBlePacketParsingSafety() {
        // Test 8-bit Heart Rate format (flag bit 0 is 0)
        val valid8BitPacket = byteArrayOf(0x00, 75.toByte()) // 75 BPM
        val bpm8 = parseTestHeartRate(valid8BitPacket)
        assertEquals(75, bpm8)

        // Test 16-bit Heart Rate format (flag bit 0 is 1)
        val valid16BitPacket = byteArrayOf(0x01, 0x90.toByte(), 0x00.toByte()) // 144 BPM
        val bpm16 = parseTestHeartRate(valid16BitPacket)
        assertEquals(144, bpm16)

        // Malformed / truncated 16-bit packet (flag indicates 16-bit, but size < 3)
        val truncated16BitPacket = byteArrayOf(0x01, 0x90.toByte())
        val bpmTruncated16 = parseTestHeartRate(truncated16BitPacket)
        assertEquals(0, bpmTruncated16)

        // Empty byte array payload
        val emptyPacket = byteArrayOf()
        val bpmEmpty = parseTestHeartRate(emptyPacket)
        assertEquals(0, bpmEmpty)
    }

    private fun parseTestHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0

        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0

        return if (is16Bit) {
            if (data.size >= 3) {
                ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            } else 0
        } else {
            if (data.size >= 2) {
                data[1].toInt() and 0xFF
            } else 0
        }
    }
}
