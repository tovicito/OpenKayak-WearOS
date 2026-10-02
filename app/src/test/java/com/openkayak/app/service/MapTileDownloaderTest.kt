package com.openkayak.app.service

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MapTileDownloaderTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class ThrowingContext : ContextWrapper(null) {
        override fun getDir(name: String?, mode: Int): File {
            throw RuntimeException("Sensitive path /data/user/0/com.openkayak.app/files leaked!")
        }
    }

    @Test
    fun testDownloadAsturiasOfflineMapSanitizesExceptionDetails() {
        val context = ThrowingContext()
        val downloader = MapTileDownloader(context)
        downloader.downloadAsturiasOfflineMap()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = downloader.downloadState.value
        assertFalse(state.isDownloading)
        assertEquals("No se pudo descargar el mapa: error al iniciar la descarga", state.statusMessage)
    }
}
