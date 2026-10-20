package org.openkayak.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * OpenKayak Native Wear OS 5 Standalone Activity
 * Designed for deck-mounted Xiaomi Watch 2 Pro, Galaxy Watch, and Pixel Watch
 */
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private var tts: TextToSpeech? = null
    private var isListeningWakeWord = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake while kayak session is mounted on boat deck
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Text-To-Speech for phase changes
        tts = TextToSpeech(this, this)

        // Request Wear OS runtime permissions
        requestWearPermissions()

        // Initialize continuous microphone hotword listener ("Hola reloj")
        initContinuousVoiceHotword()
    }

    private fun requestWearPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.VIBRATE
        )
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun initContinuousVoiceHotword() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // Auto-restart to maintain permanent listening while paddling
                if (isListeningWakeWord) {
                    speechRecognizer.startListening(recognizerIntent)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { speech ->
                    handleSpeechInput(speech)
                }
                if (isListeningWakeWord) {
                    speechRecognizer.startListening(recognizerIntent)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { speech ->
                    if (speech.lowercase().contains("hola reloj")) {
                        vibrateAlert()
                        tts?.speak("Dime", TextToSpeech.QUEUE_FLUSH, null, "WAKE")
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(recognizerIntent)
    }

    private fun handleSpeechInput(text: String) {
        val lower = text.lowercase()
        if (lower.contains("fase")) {
            tts?.speak("Fase actual: Paleo foto y técnica", TextToSpeech.QUEUE_FLUSH, null, "PHASE")
        } else if (lower.contains("vuelta")) {
            tts?.speak("Llevas 2 vueltas completadas", TextToSpeech.QUEUE_FLUSH, null, "LAPS")
        }
    }

    private fun vibrateAlert() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningWakeWord = false
        speechRecognizer.destroy()
        tts?.shutdown()
    }
}
