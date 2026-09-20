package com.openkayak.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateManagerTest {

    @Test
    fun testBleConnectionStateEnum() {
        assertEquals(4, BleConnectionState.values().size)
        assertEquals(BleConnectionState.DISCONNECTED, BleConnectionState.valueOf("DISCONNECTED"))
        assertEquals(BleConnectionState.CONNECTED, BleConnectionState.valueOf("CONNECTED"))
    }

    @Test
    fun testBleHeartRateStateDefaults() {
        val state = BleHeartRateState()
        assertEquals(BleConnectionState.DISCONNECTED, state.connectionState)
        assertEquals(0, state.heartRateBpm)
        assertNull(state.deviceName)
        assertNull(state.deviceAddress)
        assertFalse(state.isPulseActive)
        assertFalse(state.isUsingInternalSensor)
    }

    @Test
    fun testParse8BitHeartRate() {
        // Flags byte 0x00 -> 8-bit BPM in byte[1]
        val data = byteArrayOf(0x00, 145.toByte())
        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0

        val bpm = if (is16Bit) {
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        } else {
            data[1].toInt() and 0xFF
        }

        assertEquals(145, bpm)
        assertTrue(bpm in 30..240)
    }

    @Test
    fun testParse16BitHeartRate() {
        // Flags byte 0x01 -> 16-bit BPM in byte[1] (LSB) and byte[2] (MSB)
        // e.g. 175 BPM = 0x00AF
        val data = byteArrayOf(0x01, 0xAF.toByte(), 0x00)
        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0

        val bpm = if (is16Bit) {
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        } else {
            data[1].toInt() and 0xFF
        }

        assertEquals(175, bpm)
        assertTrue(bpm in 30..240)
    }

    @Test
    fun testInvalidHeartRateRange() {
        // Sample with BPM = 10 (too low)
        val lowData = byteArrayOf(0x00, 10.toByte())
        val lowBpm = lowData[1].toInt() and 0xFF
        assertFalse(lowBpm in 30..240)

        // Sample with BPM = 250 (too high)
        val highData = byteArrayOf(0x00, 250.toByte())
        val highBpm = highData[1].toInt() and 0xFF
        assertFalse(highBpm in 30..240)
    }
}
