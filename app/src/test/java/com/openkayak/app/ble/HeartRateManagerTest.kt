package com.openkayak.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateManagerTest {

    @Test
    fun testMaskAddress() {
        assertEquals("null", HeartRateManager.maskAddress(null))
        assertEquals("***", HeartRateManager.maskAddress("12345"))
        assertEquals("AA:**:**:**:FF", HeartRateManager.maskAddress("AA:BB:CC:DD:EE:FF"))
        assertEquals("00:**:**:**:11", HeartRateManager.maskAddress("00:11:22:33:44:11"))
    }
}
