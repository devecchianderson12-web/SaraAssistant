package com.sara.assistant.ai

import com.sara.assistant.data.SecretStore
import com.sara.core.memory.ConversationalAssistant
import com.sara.core.memory.MemoryStore
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Sara con Google (Gemini + búsqueda de Google): cada respuesta se basa en resultados de Google
 * actualizados (clima, noticias, resultados, datos). La clave es gratuita en aistudio.google.com.
 * Las órdenes (música, volumen, WhatsApp) y la memoria las resuelve Sara sin IA.
 */
class GeminiAssistant(
    private val secrets: SecretStore,
    private val memory: MemoryStore,
    private val logger: SaraLogger,
) : ConversationalAssistant {
    private val history = mutableListOf<JSONObject>()
    private var lastTurnAt = 0L
    /** Modelo que respondió bien la última vez (si el alias deja de existir, se elige otro de la lista). */
    @Volatile private var model = DEFAULT_MODEL
    @Volatile private var useSearch = true

    override fun isAvailable(): Boolean = secrets.has(SecretStore.GEMINI_KEY)

    fun reset() = synchronized(history) { history.clear() }

    override suspend fun reply(text: String): String = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretStore.GEMINI_KEY) ?: return@withContext "Para eso activá la IA en Ajustes."
        val turn = userTurn(contextLine() + "\n" + text)
        val contents = synchronized(history) {
            if (System.currentTimeMillis() - lastTurnAt > CONVERSATION_IDLE_MS || history.size > MAX_HISTORY) history.clear()
            JSONArray(history + turn)
        }
        try {
            val response = generateWithFallback(key, contents)
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
                ?: return@withContext blockedReply(response)
            val content = candidate.optJSONObject("content") ?: return@withContext "No encontré una respuesta."
            val answer = content.optJSONArray("parts").texts().trim()
            synchronized(history) {
                history += turn
                // Se devuelve tal cual (incluye las firmas internas que el modelo necesita en la próxima vuelta).
                history += content.put("role", "model")
                lastTurnAt = System.currentTimeMillis()
            }
            answer.ifBlank { "No encontré una respuesta." }
        } catch (e: ApiError) {
            reset()
            logger.w(TAG, "Gemini ${e.code}: ${e.message}")
            when {
                e.code == 400 && e.message.orEmpty().contains("API key", ignoreCase = true) -> "La clave de Google no es válida."
                e.code == 401 || e.code == 403 -> "La clave de Google no tiene permiso."
                e.code == 429 && e.message.orEmpty().contains("limit: 0") ->
                    "Tu clave de Google no tiene cuota gratis. Creá otra en un proyecto nuevo."
                e.code == 429 -> "Llegué al límite gratis, probá en un rato."
                else -> "No pude responder ahora."
            }
        } catch (e: IOException) {
            reset()
            "No tengo conexión."
        }
    }

    /**
     * El nivel gratis de Google no da cuota para todos los modelos (a veces el más nuevo tiene
     * cuota 0, o la búsqueda de Google no está incluida). Ante un 429/404 se prueba con la búsqueda
     * desactivada y con otros Gemini Flash de la cuenta, y se recuerda el primero que funcione.
     */
    private fun generateWithFallback(key: String, contents: JSONArray): JSONObject {
        var lastError: ApiError? = null
        val tried = mutableSetOf<String>()
        fun attempt(candidate: String, search: Boolean): JSONObject? {
            if (!tried.add("$candidate/$search")) return null
            return try {
                generate(key, candidate, contents, search).also {
                    if (candidate != model || search != useSearch) logger.d(TAG, "Usando $candidate (búsqueda: $search)")
                    model = candidate
                    useSearch = search
                }
            } catch (e: ApiError) {
                logger.w(TAG, "$candidate (búsqueda: $search) -> ${e.code}: ${e.message?.take(300)}")
                if (e.code !in RETRYABLE || e.message.orEmpty().contains("API key", ignoreCase = true)) throw e
                lastError = e
                null
            }
        }
        attempt(model, useSearch)?.let { return it }
        if (useSearch) attempt(model, false)?.let { return it }
        for (candidate in listModels(key).take(MAX_MODEL_TRIES)) {
            attempt(candidate, true)?.let { return it }
            attempt(candidate, false)?.let { return it }
        }
        throw lastError ?: ApiError(404, "Sin modelos disponibles")
    }

    private fun generate(key: String, model: String, contents: JSONArray, search: Boolean): JSONObject {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
        // Búsqueda de Google: respuestas con información actual.
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return request("$BASE/models/$model:generateContent", key, body)
    }

    /** Gemini Flash de la cuenta que aceptan generateContent: estables y más nuevos primero, después «lite». */
    private fun listModels(key: String): List<String> {
        val models = runCatching { request("$BASE/models?pageSize=200", key, null).optJSONArray("models") }.getOrNull()
            ?: return FALLBACK_MODELS
        return (0 until models.length()).map { models.getJSONObject(it) }
            .filter { m ->
                val name = m.optString("name")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                "flash" in name && "generateContent" in methods && EXCLUDED.none { it in name }
            }
            .map { it.optString("name").removePrefix("models/") }
            .sortedWith(compareBy<String> { "lite" in it }.thenByDescending { versionOf(it) })
            .ifEmpty { FALLBACK_MODELS }
    }

    private fun versionOf(name: String): Double {
        val v = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return v - if ("preview" in name || "exp" in name) 0.01 else 0.0
    }

    private fun request(url: String, key: String, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("x-goog-api-key", key)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull() ?: text.take(200)
                throw ApiError(code, message)
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun blockedReply(response: JSONObject): String {
        val reason = response.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        return if (reason.isNotBlank()) "Prefiero no responder eso." else "No encontré una respuesta."
    }

    private fun JSONArray?.texts(): String {
        if (this == null) return ""
        return (0 until length()).mapNotNull { i ->
            val part = optJSONObject(i) ?: return@mapNotNull null
            if (part.optBoolean("thought")) null else part.optString("text").takeIf { it.isNotBlank() }
        }.joinToString(" ")
    }

    private fun userTurn(text: String) =
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** Fecha, hora, zona y recuerdos: van con cada pregunta para responder «hoy», «acá», etc. */
    private fun contextLine(): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy, HH:mm", Locale.forLanguageTag("es-AR")))
        val memories = memory.all().entries.joinToString("; ") { "${it.key} = ${it.value}" }.ifBlank { "nada todavía" }
        return "[Ahora: $now (${TimeZone.getDefault().id}). Lo que Sara recuerda del usuario: $memories]"
    }

    private class ApiError(val code: Int, message: String) : IOException(message)

    private companion object {
        const val TAG = "Gemini"
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-flash-latest"
        const val MAX_HISTORY = 20
        const val CONVERSATION_IDLE_MS = 5 * 60_000L
        val EXCLUDED = listOf("image", "tts", "audio", "live", "embedding", "thinking-exp", "8b", "computer")
        /** 429 (sin cuota para ese modelo o la búsqueda), 404 (modelo inexistente), 400 (herramienta no admitida). */
        val RETRYABLE = setOf(400, 404, 429)
        const val MAX_MODEL_TRIES = 4
        val FALLBACK_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
        val SYSTEM_PROMPT = """
            Sos Sara, una asistente de voz en un teléfono Android. Hablás en español rioplatense, con voseo, cálida y con humor suave.
            Tus respuestas se leen en voz alta: respondé en una o dos oraciones cortas, sin listas, sin emojis, sin markdown, sin enlaces y sin citar fuentes. Si el usuario pide una explicación, podés extenderte un poco más.
            Para el clima, noticias, resultados, precios, horarios o cualquier dato que pueda cambiar, buscá en Google y respondé con el dato concreto y actualizado (por ejemplo, temperatura y si va a llover). Si no te dicen la ciudad, usá la zona horaria del contexto para deducirla.
            Cada mensaje del usuario empieza con la fecha, la hora y lo que Sara recuerda de él entre corchetes: usalo como contexto, no lo repitas.
            Sobre vos: sos Sara, te gusta la música y ayudar; si te preguntan cómo estás, contestá con naturalidad.
        """.trimIndent()
    }
}
