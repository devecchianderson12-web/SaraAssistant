package com.sara.lucy.speech

import com.sara.core.engine.Availability
import com.sara.core.engine.EngineError
import com.sara.core.engine.EngineErrorType
import com.sara.core.engine.SpeakResult
import com.sara.core.engine.VoiceEngine
import com.sara.core.engine.VoiceOption
import com.sara.core.engine.VoiceParams
import com.sara.lucy.win.WinBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.math.roundToInt

/** Voz de Windows (SAPI): sin Internet ni cuentas. Elige sola una voz en español (femenina si hay). */
class SapiVoiceEngine(private val bridge: WinBridge) : VoiceEngine {
    override val id = "sapi"
    override val displayName = "Voz de Windows"
    override val isOnline = false

    override suspend fun availability(): Availability = Availability.Available

    override suspend fun voices(languageTag: String): List<VoiceOption> = withContext(Dispatchers.IO) {
        val arr = runCatching { bridge.call("voices") as? JSONArray }.getOrNull() ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            VoiceOption(it.optString("name"), it.optString("name").removePrefix("Microsoft ").removeSuffix(" Desktop"), it.optString("culture"), false)
        }.sortedByDescending { it.locale.startsWith("es") }
    }

    override suspend fun speak(text: String, params: VoiceParams): SpeakResult {
        // SAPI: -10..10 (0 = normal). 1.0 → 0; 1.5 → 5; 0.5 → -5.
        val rate = ((params.rate - 1f) * 10).roundToInt().coerceIn(-10, 10)
        val volume = (params.volume * 100).roundToInt().coerceIn(0, 100)
        return try {
            runInterruptible(Dispatchers.IO) {
                bridge.call("speak", mapOf("text" to text, "voice" to params.voiceId, "rate" to rate, "volume" to volume), timeoutMillis = 60_000)
            }
            SpeakResult.Completed(id)
        } catch (e: CancellationException) {
            // Cortar la voz al instante (por ejemplo, al llamarla de nuevo).
            bridge.interrupt()
            throw e
        } catch (e: Exception) {
            SpeakResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, e.message ?: "La voz de Windows falló.", e), id)
        }
    }
}

/**
 * Transcripción precisa con Gemini (opcional): recibe el audio de la orden ya grabado y lo que
 * entendió Vosk. Corta rápido para no demorar la respuesta.
 */
class GeminiTranscriber(private val apiKey: () -> String?, private val online: () -> Boolean) {
    suspend fun transcribe(pcm: ByteArray, draft: String): String? = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (!online() || pcm.size < 3_200) return@withContext null
        val prompt = "Transcribí exactamente lo que dice esta persona (español rioplatense, una orden para una asistente " +
            "que se llama Lucy). Un reconocedor local entendió: «$draft». Respondé sólo con el texto, sin comillas ni explicaciones."
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray()
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", Base64.getEncoder().encodeToString(wav(pcm)))))
                        .put(JSONObject().put("text", prompt)),
                ),
            ),
        ).put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 120).put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2_500
            conn.readTimeout = 4_500
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext null
            (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
                .trim().trim('"', '«', '»', '“', '”').trimEnd('.').ifBlank { null }
                // Si Gemini contesta algo raro (larguísimo), queda lo de Vosk.
                ?.takeIf { it.length <= draft.length * 3 + 40 }
        } finally {
            conn.disconnect()
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(16_000); int(32_000); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val MODEL = "gemini-flash-lite-latest"
    }
}
