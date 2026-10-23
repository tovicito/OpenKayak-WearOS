package com.openkayak.app.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.text.Normalizer

/**
 * Opt-in "hola reloj" detector. Uses Android's on-device Spanish speech recognizer,
 * and only runs while the activity is foreground and the display is interactive.
 * This is not an OS-level DSP hotword service and intentionally does not run in the background.
 */
class WakeWordController(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onCommand: (String) -> Unit,
    private val onPrompt: () -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    private var recognizer: SpeechRecognizer? = null
    private var waitingForCommand = false
    private var commandTimeout: Runnable? = null

    fun start(): Boolean {
        if (enabled && recognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onStatus("Reconocimiento de voz no disponible en este reloj.")
            return false
        }
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            onStatus("«Hola reloj» necesita reconocimiento de voz local. Instala/activa el servicio de voz offline compatible.")
            return false
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Falta el permiso de micrófono.")
            return false
        }
        enabled = true
        waitingForCommand = false
        createRecognizerAndListen()
        return recognizer != null
    }

    fun stop(reportStatus: Boolean = false) {
        enabled = false
        waitingForCommand = false
        commandTimeout?.let(handler::removeCallbacks)
        commandTimeout = null
        handler.removeCallbacksAndMessages(null)
        val old = recognizer
        recognizer = null
        if (old != null) {
            runCatching { old.cancel() }
            runCatching { old.destroy() }
        }
        if (reportStatus) onStatus("Escucha de «hola reloj» desactivada.")
    }

    private fun createRecognizerAndListen() {
        if (!enabled) return
        val current = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
            .getOrElse {
                onStatus("No se pudo iniciar el reconocimiento local.")
                enabled = false
                return
            }
        recognizer = current
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (enabled && recognizer === current) onStatus(if (waitingForCommand) "Te escucho…" else "Esperando «hola reloj»…")
            }
            override fun onBeginningOfSpeech() {
                if (enabled && recognizer === current) onStatus("Reconociendo en el dispositivo…")
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                if (!enabled || recognizer !== current) return
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                    onStatus(if (waitingForCommand) "Di el comando…" else "Esperando «hola reloj»…")
                    handler.postDelayed({
                        if (enabled && recognizer === current) runCatching { current.startListening(recognitionIntent()) }
                            .onFailure { replaceRecognizerAfter(500L) }
                    }, 250L)
                } else {
                    onStatus("Reiniciando escucha local…")
                    replaceRecognizerAfter(700L)
                }
            }
            override fun onResults(results: Bundle?) {
                if (!enabled || recognizer !== current) return
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val handled = processTranscript(phrase)
                if (!handled && enabled && recognizer === current) {
                    handler.postDelayed({
                        if (enabled && recognizer === current) runCatching { current.startListening(recognitionIntent()) }
                            .onFailure { replaceRecognizerAfter(500L) }
                    }, 250L)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching { current.startListening(recognitionIntent()) }
            .onFailure {
                onStatus("No se pudo activar la escucha local.")
                replaceRecognizerAfter(900L)
            }
    }

    /** Returns true when it schedules a fresh recognizer rather than reusing this one. */
    private fun processTranscript(raw: String): Boolean {
        val text = normalize(raw)
        if (waitingForCommand) {
            waitingForCommand = false
            commandTimeout?.let(handler::removeCallbacks)
            commandTimeout = null
            if (text.isNotBlank()) {
                replaceRecognizerAfter(1700L)
                onCommand(text)
                return true
            }
            return false
        }

        val wakeWords = listOf("hola reloj", "ola reloj", "oye reloj", "eh reloj", "hola relo", "ola relo")
        val matched = wakeWords.firstOrNull { text.contains(it) } ?: return false
        val command = text.substring(text.indexOf(matched) + matched.length).trim()
        if (command.isNotBlank()) {
            replaceRecognizerAfter(1700L)
            onCommand(command)
        } else {
            waitingForCommand = true
            onStatus("Palabra clave detectada.")
            replaceRecognizerAfter(850L)
            onPrompt()
            val timeout = Runnable {
                waitingForCommand = false
                commandTimeout = null
                if (enabled) onStatus("Esperando «hola reloj»…")
            }
            commandTimeout = timeout
            handler.postDelayed(timeout, 7_000L)
        }
        return true
    }

    private fun replaceRecognizerAfter(delayMs: Long) {
        val old = recognizer
        recognizer = null
        if (old != null) {
            runCatching { old.cancel() }
            runCatching { old.destroy() }
        }
        handler.postDelayed({ if (enabled) createRecognizerAndListen() }, delayMs)
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
