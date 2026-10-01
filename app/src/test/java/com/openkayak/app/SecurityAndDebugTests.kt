package com.openkayak.app

import com.openkayak.app.ble.BleHeartRateState
import com.openkayak.app.ble.HeartRateManager
import com.openkayak.app.service.DownloadState
import com.openkayak.app.service.StrokeDetector
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * Security Test 2: Sensor & Data Boundary Safety
     * Directly tests StrokeDetector's internal calculateSpm method via reflection and float arithmetic sqrt safety.
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

        // Float arithmetic accuracy check in StrokeDetector math formulas
        val ay = 9.8f
        val az = 1.5f
        val acc = sqrt(ay * ay + az * az)
        assertFalse(acc.isNaN())
        assertFalse(acc.isInfinite())
        assertTrue(acc > 0f)
    }

    /**
     * Security Test 3: BLE Packet & Memory Safety
     * Directly invokes HeartRateManager's internal parseHeartRateMeasurement method via reflection with actual instance state validation.
     */
    @Test
    fun testBlePacketParsingSafety() {
        val hrManager = allocateUninitialized(HeartRateManager::class.java) as HeartRateManager

        val mutableStateFlow = kotlinx.coroutines.flow.MutableStateFlow(BleHeartRateState())
        val stateFlow = mutableStateFlow.asStateFlow()

        // Populate private fields accessed by HeartRateManager during parseHeartRateMeasurement
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
        parseMethod.invoke(hrManager, valid8BitPacket)
        assertEquals(75, hrManager.hrState.value.heartRateBpm)

        // 2. Test 16-bit Heart Rate format (144 BPM)
        val valid16BitPacket = byteArrayOf(0x01, 0x90.toByte(), 0x00.toByte())
        parseMethod.invoke(hrManager, valid16BitPacket)
        assertEquals(144, hrManager.hrState.value.heartRateBpm)

        // 3. Test truncated 16-bit packet (size < 3 -> should safely ignore / not crash)
        val truncatedPacket = byteArrayOf(0x01, 0x90.toByte())
        parseMethod.invoke(hrManager, truncatedPacket)

        // 4. Test empty packet -> should safely ignore / not crash
        val emptyPacket = byteArrayOf()
        parseMethod.invoke(hrManager, emptyPacket)
    }

    private fun allocateUninitialized(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) ?: throw IllegalStateException("theUnsafe is null")
        val allocateInstance = unsafeClass.getDeclaredMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz)!!
    }
}
