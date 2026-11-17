package com.sara.core.ai

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
import com.sara.core.execution.ExecutionResult
import com.sara.core.execution.Planner
import com.sara.core.memory.ConversationalAssistant
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.Locale
import java.util.TimeZone

/**
 * Sara con Claude. Dos usos:
 * - [reply]: preguntas (con búsqueda web), respuesta corta.
 * - [handle]: planificador. Recibe el pedido, decide qué habilidades usar y en qué orden, las
 *   ejecuta (las sensibles con confirmación del usuario) y resume qué logró.
 */
class ClaudeAssistant(
    /** Clave de Claude (null o vacía = no configurada). */
    private val apiKey: () -> String?,
    private val support: PlannerSupport,
    private val logger: SaraLogger,
) : ConversationalAssistant, Planner {
    private val timeZone = TimeZone.getDefault().id
    private val country = Locale.getDefault().country.ifBlank { "AR" }

    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private val history = mutableListOf<BetaMessageParam>()
    private var lastTurnAt = 0L

    override fun isAvailable(): Boolean = !apiKey().isNullOrBlank()

    override suspend fun reply(text: String): String {
        val result = run(text, withSkills = false)
        return result.response
    }

    override suspend fun handle(text: String): ExecutionResult = run(text, withSkills = true)

    private suspend fun run(text: String, withSkills: Boolean): ExecutionResult = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext support.failure("Para eso activá la IA en Ajustes.")
        val api = clientFor(key)
        support.beginTurn()
        synchronized(history) {
            // Conversación nueva tras unos minutos sin hablar (o si se hizo muy larga).
            if (System.currentTimeMillis() - lastTurnAt > CONVERSATION_IDLE_MS || history.size > MAX_HISTORY) history.clear()
            history += BetaMessageParam.ofUser(support.contextLine() + "\n" + text)
        }
        guarded { loop(api, withSkills) }
    }

    /** Errores de la API: mensaje claro y conversación nueva (no queda un turno a medias). */
    private suspend fun guarded(block: suspend () -> ExecutionResult): ExecutionResult = try {
        block().also { lastTurnAt = System.currentTimeMillis() }
    } catch (e: Exception) {
        reset()
        when (e) {
            is UnauthorizedException -> support.failure("La clave de Claude no es válida.")
            is PermissionDeniedException -> support.failure("La clave de Claude no tiene permiso.")
            is RateLimitException -> support.failure("Claude está saturado, probá en un rato.")
            is AnthropicServiceException -> support.failure("No pude responder ahora.").also { logger.w(TAG, "Error de la API", e) }
            is AnthropicIoException -> support.failure("No tengo conexión.")
            else -> throw e
        }
    }

    private suspend fun loop(api: AnthropicClient, withSkills: Boolean): ExecutionResult {
        repeat(PlannerSupport.MAX_STEPS) {
            val response: BetaMessage = api.beta().messages().create(params(withSkills))
            synchronized(history) { history += response.toParam() }
            val stop = response.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) {
                reset()
                return support.finalResult("Prefiero no responder eso.")
            }
            // Búsqueda web larga: el servidor pide continuar el mismo turno.
            if (stop == BetaStopReason.PAUSE_TURN) return@repeat
            val calls = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                @Suppress("UNCHECKED_CAST")
                PlannerSupport.Call(use.id(), use.name(), support.stringify(use._input().convert(Map::class.java) as Map<*, *>))
            }
            if (stop != BetaStopReason.TOOL_USE || calls.isEmpty()) {
                return support.finalResult(response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" "))
            }
            // Acción sensible: Sara pregunta y sigue sólo si le dicen que sí.
            support.confirmationFor(calls, resume = { withContext(Dispatchers.IO) { guarded { answer(calls); loop(api, withSkills) } } }, onDecline = {
                pendingReset = true
            })?.let { return it }
            answer(calls)
        }
        reset()
        return support.failure("No pude terminar eso en pocos pasos.")
    }

    @Volatile private var pendingReset = false

    private suspend fun answer(calls: List<PlannerSupport.Call>) {
        pendingReset = false
        val results = calls.map { call ->
            val (output, error) = support.run(call)
            BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder().toolUseId(call.id).content(output).isError(error).build())
        }
        synchronized(history) { history += BetaMessageParam.ofUserOfContentBlockParams(results) }
    }

    private fun params(withSkills: Boolean): MessageCreateParams {
        if (pendingReset) reset()
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4_000L)
            // Respuestas rápidas para voz: esfuerzo bajo.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // Si el modelo se niega por un falso positivo, el servidor reintenta con otro modelo.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacks(BetaFallbacksParam.ofDefault())
            .system(PlannerSupport.PLANNER_PROMPT)
            .addTool(
                BetaWebSearchTool20260209.builder()
                    .maxUses(3L)
                    .userLocation(BetaUserLocation.builder().country(country).timezone(timeZone).build())
                    .build(),
            )
        if (withSkills) support.tools().forEach { (name, description, schema) -> builder.addTool(tool(name, description, schema)) }
        synchronized(history) { history.forEach { builder.addMessage(it) } }
        return builder.build()
    }

    private fun tool(name: String, description: String, schema: Map<String, Any>): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        @Suppress("UNCHECKED_CAST")
        (schema["properties"] as Map<String, Any>).forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)) }
        @Suppress("UNCHECKED_CAST")
        return BetaTool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(props.build())
                    .required(schema["required"] as List<String>)
                    .build(),
            )
            .build()
    }

    private fun clientFor(key: String): AnthropicClient {
        client?.takeIf { clientKey == key }?.let { return it }
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(30))
            .maxRetries(1)
            .build()
            .also { client = it; clientKey = key }
    }

    /** Borra la conversación en curso (por ejemplo, al cambiar la clave). */
    fun reset() = synchronized(history) { history.clear(); pendingReset = false }

    private companion object {
        const val TAG = "Claude"
        const val MODEL = "claude-opus-5-5"
        const val MAX_HISTORY = 40
        const val CONVERSATION_IDLE_MS = 5 * 60_000L
    }
}
