package com.sara.assistant.ai

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaUserLocation
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.sara.assistant.data.SecretStore
import com.sara.core.memory.ConversationalAssistant
import com.sara.core.memory.MemoryKeys
import com.sara.core.memory.MemoryStore
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Sara con IA (Claude): contesta preguntas (con búsqueda web para el clima, noticias, etc.),
 * recuerda preferencias con la herramienta «recordar» y ejecuta órdenes de Sara con «ejecutar».
 * La conversación se mantiene unos minutos para poder repreguntar.
 */
class ClaudeAssistant(
    context: Context,
    private val secrets: SecretStore,
    private val memory: MemoryStore,
    private val logger: SaraLogger,
    /** Ejecuta una orden de Sara («poné música», «subí el volumen») y devuelve lo que respondió. */
    private val runCommand: suspend (String) -> String,
) : ConversationalAssistant {
    private val timeZone = TimeZone.getDefault().id
    private val country = Locale.getDefault().country.ifBlank { "AR" }

    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private val history = mutableListOf<BetaMessageParam>()
    private var lastTurnAt = 0L

    override fun isAvailable(): Boolean = secrets.has(SecretStore.ANTHROPIC_KEY)

    override suspend fun reply(text: String): String = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretStore.ANTHROPIC_KEY) ?: return@withContext "Para eso activá la IA en Ajustes."
        val api = clientFor(key)
        synchronized(history) {
            // Conversación nueva tras unos minutos sin hablar (o si se hizo muy larga).
            if (System.currentTimeMillis() - lastTurnAt > CONVERSATION_IDLE_MS || history.size > MAX_HISTORY) history.clear()
            history += BetaMessageParam.ofUser(contextLine() + "\n" + text)
        }
        try {
            val answer = runLoop(api)
            lastTurnAt = System.currentTimeMillis()
            answer
        } catch (e: Exception) {
            // Tras un error se empieza una conversación nueva (no queda un turno a medias).
            synchronized(history) { history.clear() }
            when (e) {
                is UnauthorizedException -> "La clave de Claude no es válida."
                is PermissionDeniedException -> "La clave de Claude no tiene permiso."
                is RateLimitException -> "Estoy saturada, probá en un rato."
                is AnthropicServiceException -> "No pude responder ahora.".also { logger.w(TAG, "Error de la API", e) }
                is AnthropicIoException -> "No tengo conexión."
                else -> throw e
            }
        }
    }

    private suspend fun runLoop(api: AnthropicClient): String {
        repeat(MAX_STEPS) {
            val response: BetaMessage = api.beta().messages().create(params())
            synchronized(history) { history += response.toParam() }
            val stop = response.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) {
                synchronized(history) { history.clear() }
                return "Prefiero no responder eso."
            }
            // Búsqueda web larga: el servidor pide continuar el mismo turno.
            if (stop == BetaStopReason.PAUSE_TURN) return@repeat
            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (stop != BetaStopReason.TOOL_USE || toolUses.isEmpty()) {
                return response.content().mapNotNull { it.text().orElse(null)?.text() }
                    .joinToString(" ").trim().ifBlank { "Listo." }
            }
            val results = toolUses.map { use ->
                val (output, error) = runCatching { runTool(use.name(), use._input()) to false }
                    .getOrElse { (it.message ?: "error") to true }
                BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder().toolUseId(use.id()).content(output).isError(error).build(),
                )
            }
            synchronized(history) { history += BetaMessageParam.ofUserOfContentBlockParams(results) }
        }
        synchronized(history) { history.clear() }
        return "No pude terminar eso."
    }

    private suspend fun runTool(name: String, input: JsonValue): String {
        @Suppress("UNCHECKED_CAST")
        val args = input.convert(Map::class.java) as Map<String, Any?>
        fun arg(key: String) = (args[key] as? String)?.trim().orEmpty()
        return when (name) {
            "recordar" -> {
                val key = arg("clave").ifBlank { error("Falta la clave") }
                val value = arg("valor").ifBlank { error("Falta el valor") }
                if (key == MemoryKeys.MUSIC_APP) {
                    // Pasa por Sara para verificar que la app exista.
                    runCommand("cambiá la app de música a $value")
                } else {
                    memory.put(key, value)
                    "Guardado."
                }
            }
            "olvidar" -> if (memory.remove(arg("clave"))) "Olvidado." else "No había nada con esa clave."
            "ejecutar" -> runCommand(arg("orden").ifBlank { error("Falta la orden") })
            else -> error("Herramienta desconocida: $name")
        }
    }

    private fun params(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4_000L)
            // Respuestas rápidas para voz: esfuerzo bajo.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // Si el modelo se niega por un falso positivo, el servidor reintenta con otro modelo.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacks(BetaFallbacksParam.ofDefault())
            .system(SYSTEM_PROMPT)
            .addTool(
                BetaWebSearchTool20260209.builder()
                    .maxUses(3L)
                    .userLocation(BetaUserLocation.builder().country(country).timezone(timeZone).build())
                    .build(),
            )
            .addTool(tool("recordar", "Guarda algo que el usuario quiere que Sara recuerde. Claves: «${MemoryKeys.MUSIC_APP}» (app para poner música), «${MemoryKeys.ROUTINE_PREFIX}<frase>» (orden a ejecutar cuando diga esa frase) o «${MemoryKeys.NOTE_PREFIX}<tema>» para cualquier dato.", "clave", "valor"))
            .addTool(tool("olvidar", "Borra un recuerdo por su clave exacta.", "clave"))
            .addTool(tool("ejecutar", "Ejecuta una orden de Sara en el teléfono, escrita como la diría el usuario: «poné música de Soda Stereo», «subí el volumen al 50», «abrí YouTube», «pausá», «cerrá las apps». Devuelve el resultado.", "orden"))
        synchronized(history) { history.forEach { builder.addMessage(it) } }
        return builder.build()
    }

    private fun tool(name: String, description: String, vararg fields: String): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        fields.forEach { props.putAdditionalProperty(it, JsonValue.from(mapOf("type" to "string"))) }
        return BetaTool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(props.build())
                    .required(fields.toList())
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .strict(true)
            .build()
    }

    /** Fecha, hora y recuerdos: van en el mensaje del usuario para no tocar el prompt de sistema. */
    private fun contextLine(): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy, HH:mm", Locale.forLanguageTag("es-AR")))
        val memories = memory.all().entries.joinToString("; ") { "${it.key} = ${it.value}" }.ifBlank { "nada todavía" }
        return "[Ahora: $now ($timeZone). Recuerdos guardados: $memories]"
    }

    private fun clientFor(key: String): AnthropicClient {
        client?.takeIf { clientKey == key }?.let { return it }
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(45))
            .build()
            .also { client = it; clientKey = key }
    }

    /** Borra la conversación en curso (por ejemplo, al cambiar la clave). */
    fun reset() = synchronized(history) { history.clear() }

    private companion object {
        const val TAG = "Claude"
        const val MODEL = "claude-opus-5-5"
        const val MAX_STEPS = 6
        const val MAX_HISTORY = 30
        const val CONVERSATION_IDLE_MS = 5 * 60_000L
        val SYSTEM_PROMPT = """
            Sos Sara, una asistente de voz en un teléfono Android. Hablás en español rioplatense, con voseo, cálida y con humor suave.
            Tus respuestas se leen en voz alta: respondé en una o dos oraciones cortas, sin listas, sin emojis, sin markdown y sin enlaces. Si el usuario pide una explicación, podés extenderte un poco más.
            Para el clima, noticias, resultados o cualquier dato actual, usá la búsqueda web y respondé con el dato concreto (por ejemplo, temperatura y si llueve).
            Si el usuario pide hacer algo en el teléfono (música, volumen, abrir apps, pausar, cerrar apps), usá la herramienta «ejecutar» con la orden en palabras simples.
            Si el usuario te pide que recuerdes algo, una preferencia o una rutina («cada vez que…», «cuando te diga…», «de ahora en más…»), usá «recordar»; si pide cambiarlo, guardalo de nuevo con la misma clave; si pide olvidarlo, usá «olvidar». Confirmá en pocas palabras.
            Cada mensaje del usuario empieza con la fecha, la hora y lo que tenés guardado entre corchetes: usalo como contexto, no lo repitas.
            Sobre vos: sos Sara, te gusta la música y ayudar; si te preguntan cómo estás, contestá con naturalidad.
        """.trimIndent()
    }
}
