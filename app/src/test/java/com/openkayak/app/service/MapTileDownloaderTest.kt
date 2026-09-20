package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTileDownloaderTest {

    @Test
    fun testDownloadStateDefaults() {
        val state = DownloadState()
        assertFalse(state.isDownloading)
        assertEquals(0, state.progressPercent)
        assertEquals(0, state.downloadedTiles)
        assertEquals(0, state.totalTiles)
        assertEquals("Listo para descargar Asturias + Embalse de Trasona", state.statusMessage)
    }

    @Test
    fun testDownloadProgressCalculationPhase1() {
        val tilesAsturias = 150
        val tilesTrasona = 100
        val totalCombinedTiles = tilesAsturias + tilesTrasona // 250

        val progress = 75 // 75 tiles done in Asturias
        val percent = ((progress.toFloat() / totalCombinedTiles) * 100).toInt()

        assertEquals(30, percent)
        val statusMessage = "Asturias Z10-Z14: $progress/$tilesAsturias ($percent%)"
        assertEquals("Asturias Z10-Z14: 75/150 (30%)", statusMessage)
    }

    @Test
    fun testDownloadProgressCalculationPhase2() {
        val tilesAsturias = 150
        val tilesTrasona = 100
        val totalCombinedTiles = tilesAsturias + tilesTrasona // 250

        val progressTrasona = 50 // 50 tiles done in Trasona
        val totalDone = tilesAsturias + progressTrasona // 200
        val percent = ((totalDone.toFloat() / totalCombinedTiles) * 100).toInt()

        assertEquals(80, percent)
        val statusMessage = "Trasona Z13-Z14: $progressTrasona/$tilesTrasona ($percent%)"
        assertEquals("Trasona Z13-Z14: 50/100 (80%)", statusMessage)
    }

    @Test
    fun testDownloadCompletionState() {
        val state = DownloadState(
            isDownloading = false,
            progressPercent = 100,
            downloadedTiles = 250,
            totalTiles = 250,
            currentPhaseText = "Completado",
            statusMessage = "¡Mapa de Asturias y Trasona completado!"
        )

        assertFalse(state.isDownloading)
        assertEquals(100, state.progressPercent)
        assertEquals("Completado", state.currentPhaseText)
    }
}
