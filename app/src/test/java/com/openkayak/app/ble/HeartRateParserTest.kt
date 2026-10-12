package com.openkayak.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateParserTest {
    @Test fun parsesEightBitMeasurement() {
        assertEquals(72, HeartRateParser.parse(byteArrayOf(0x00, 0x48)))
    }

    @Test fun parsesSixteenBitLittleEndianMeasurement() {
        assertEquals(180, HeartRateParser.parse(byteArrayOf(0x01, 0xB4.toByte(), 0x00)))
    }

    @Test fun rejectsMalformedAndOutOfRangeMeasurements() {
        assertNull(HeartRateParser.parse(null))
        assertNull(HeartRateParser.parse(byteArrayOf(0x00)))
        assertNull(HeartRateParser.parse(byteArrayOf(0x01, 0xB4.toByte())))
        assertNull(HeartRateParser.parse(byteArrayOf(0x00, 0xFA.toByte())))
    }
}
