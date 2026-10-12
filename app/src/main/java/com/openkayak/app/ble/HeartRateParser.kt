package com.openkayak.app.ble

/**
 * Bluetooth SIG Heart Rate Measurement (0x2A37).
 * Bit 0 of flags chooses unsigned 8-bit BPM or little-endian unsigned 16-bit BPM.
 */
object HeartRateParser {
    fun parse(value: ByteArray?): Int? {
        if (value == null || value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0
        if (is16Bit && value.size < 3) return null
        val bpm = if (is16Bit) {
            ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        } else {
            value[1].toInt() and 0xFF
        }
        return bpm.takeIf { it in 25..240 }
    }
}
