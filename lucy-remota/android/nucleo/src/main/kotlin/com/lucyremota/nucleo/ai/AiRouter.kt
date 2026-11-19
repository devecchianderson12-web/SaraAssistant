package com.lucyremota.nucleo.ai

import com.lucyremota.nucleo.execution.ExecutionResult
import com.lucyremota.nucleo.execution.Planner
import com.lucyremota.nucleo.memory.ConversationalAssistant

/**
 * Reparte el trabajo entre proveedores:
 * - Preguntas con información actual: Google (búsqueda) primero; si no hay clave, Claude.
 * - Planificación y pedidos complejos: Claude primero (mejor razonamiento); si no hay clave o falla,
 *   Google. La preferencia de Ajustes decide cuál va primero cuando las dos sirven.
 */
/** IA para preguntas: Google (Gemini con búsqueda de Google) o Claude. */
enum class AiProvider { GOOGLE, CLAUDE }

class AiRouter(
    private val provider: () -> AiProvider,
    val google: GeminiAssistant,
    val claude: ClaudeAssistant,
) : ConversationalAssistant, Planner {

    private fun ordered(forPlanning: Boolean): List<Any> {
        val list = when {
            forPlanning -> listOf(claude, google)
            provider() == AiProvider.CLAUDE -> listOf(claude, google)
            else -> listOf(google, claude)
        }
        return list.filter { (it as ConversationalAssistant).isAvailable() }
    }

    override fun isAvailable(): Boolean = google.isAvailable() || claude.isAvailable()

    override suspend fun reply(text: String): String {
        val first = ordered(forPlanning = false).firstOrNull() as? ConversationalAssistant
            ?: return "Para eso activá la IA en Ajustes."
        return first.reply(text)
    }

    override suspend fun handle(text: String): ExecutionResult {
        val candidates = ordered(forPlanning = true).map { it as Planner }
        if (candidates.isEmpty()) return ExecutionResult(false, "Para eso activá la IA en Ajustes.")
        var last: ExecutionResult? = null
        for (planner in candidates) {
            val result = planner.handle(text)
            // Si el primero falló por la red o la clave, se prueba con el otro proveedor.
            if (result.success || result.confirmation != null || result.followUp != null) return result
            last = result
        }
        return last!!
    }

    fun reset() {
        google.reset()
        claude.reset()
    }
}
