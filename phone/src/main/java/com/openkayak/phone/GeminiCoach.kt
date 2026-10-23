package com.openkayak.phone

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GeminiChatTurn(val isUser: Boolean, val text: String)

/** Direct Gemini REST client for the phone coach. The API key is passed only in a header. */
object GeminiCoach {
    private const val MODEL = "gemini-3.8-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    fun reply(
        apiKey: String,
        question: String,
        workouts: List<PhoneWorkout>,
        previousTurns: List<GeminiChatTurn>
    ): String {
        require(apiKey.isNotBlank()) { "Añade tu clave de Gemini en Ajustes antes de usar el coach." }
        require(question.isNotBlank()) { "Escribe una pregunta para el coach." }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        val workoutSummary = workouts.sortedByDescending { it.endAt }.take(10).joinToString("\n") { workout ->
            val date = dateFormat.format(Date(workout.endAt.takeIf { it > 0L } ?: workout.startAt))
            val elapsedSeconds = (workout.elapsedMs.coerceAtLeast(0L) / 1000L)
            val duration = "%d:%02d".format(Locale.ROOT, elapsedSeconds / 60L, elapsedSeconds % 60L)
            val km = workout.distanceM.coerceAtLeast(0.0) / 1000.0
            "$date | distancia %.2f km | duración $duration | paladas %d | FC media %d, máxima %d lpm | calorías estimadas %d"
                .format(Locale.ROOT, km, workout.strokes, workout.averageBpm, workout.maxBpm, workout.calories)
        }.ifBlank { "Todavía no hay entrenamientos sincronizados en el móvil." }

        val contents = JSONArray()
        previousTurns.filter { it.text.isNotBlank() }.takeLast(12).forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.isUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
            )
        }
        val prompt = """
            Pregunta actual: $question

            Resumen de entrenamientos sincronizados (máximo 10 recientes):
            $workoutSummary
        """.trimIndent()
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Eres el coach de piragüismo de OpenKayak. Responde en español, de forma clara y práctica. " +
                                "Usa los datos proporcionados, distingue las mediciones de las estimaciones y no inventes sesiones, " +
                                "tiempos ni métricas ausentes. No des diagnósticos médicos. Si faltan datos, dilo."
                        )
                    )
                )
            )
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.45).put("maxOutputTokens", 900))

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey.trim())
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val response = runCatching { JSONObject(responseText) }.getOrElse {
                throw IllegalStateException("Respuesta no válida de Gemini (HTTP $status).")
            }
            if (status !in 200..299) {
                val message = response.optJSONObject("error")?.optString("message").orEmpty()
                throw IllegalStateException(
                    if (message.isNotBlank()) "Gemini (HTTP $status): $message"
                    else "Gemini devolvió HTTP $status. Comprueba la clave y la conexión."
                )
            }
            val candidates = response.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini no devolvió una respuesta.")
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini devolvió una respuesta vacía.")
            val answer = (0 until parts.length()).mapNotNull { index ->
                parts.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n").trim()
            answer.ifBlank { throw IllegalStateException("Gemini devolvió una respuesta vacía.") }
        } finally {
            connection.disconnect()
        }
    }
}
