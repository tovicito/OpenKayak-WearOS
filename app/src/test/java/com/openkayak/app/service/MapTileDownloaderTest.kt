package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MapTileDownloaderTest {

    @Test
    fun testDownloadStateErrorMessageDoesNotLeakExceptionDetails() {
        val state = DownloadState(
            isDownloading = false,
            statusMessage = "Error en la descarga del mapa."
        )

        assertFalse(state.isDownloading)
        assertEquals("Error en la descarga del mapa.", state.statusMessage)
        assertFalse(state.statusMessage.contains("Exception"))
        assertFalse(state.statusMessage.contains("java.lang"))
    }
}
