package com.sara.core.ai

import com.sara.core.execution.Confirmation
import com.sara.core.execution.ExecutionResult
import com.sara.core.memory.MemoryStore
import com.sara.core.model.ErrorKind
import com.sara.core.model.PermissionKind
import com.sara.core.skills.Risk
import com.sara.core.skills.SkillRegistry
import com.sara.core.skills.SkillResult
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Lo común a los planificadores (Claude y Gemini): las herramientas son las acciones de las
 * habilidades, se validan antes de ejecutarse, las sensibles piden confirmación y el resultado que
 * ve la IA dice claramente si se hizo, si quedó a medias o si falló (para que nunca diga que hizo
 * algo que no pasó).
 */
class PlannerSupport(private val registry: () -> SkillRegistry, private val memory: MemoryStore) {
    data class Call(val id: String, val name: String, val args: Map<String, String>)

    /** Permiso que pidió alguna herramienta en este pedido (para mostrar el botón). */
    var lastPermission: PermissionKind? = null
        private set

    fun beginTurn() { lastPermission = null }

    fun toolName(skill: String, action: String) = "${skill}__$action"

    private fun split(name: String): Pair<String, String>? = name.split("__").takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun isSensitive(name: String): Boolean {
        val (skill, action) = split(name) ?: return false
        return registry().find(skill, action)?.risk == Risk.SENSITIVE
    }

    fun confirmQuestion(call: Call): String {
        val (skill, action) = split(call.name) ?: return "¿Lo hago?"
        return registry().find(skill, action)?.confirm?.invoke(call.args) ?: "¿Lo hago?"
    }

    /** Ejecuta la herramienta y devuelve (texto para la IA, ¿error?). */
    suspend fun run(call: Call): Pair<String, Boolean> {
        val (skill, action) = split(call.name) ?: return "ERROR: herramienta desconocida." to true
        return when (val r = registry().run(skill, action, call.args)) {
            is SkillResult.Done -> (if (r.verified) "HECHO: " else "PEDIDO (sin confirmación del sistema): ") + r.message to false
            is SkillResult.Partial -> "A MEDIAS (el usuario tiene que terminarlo): ${r.message}" to false
            is SkillResult.NeedsPermission -> {
                lastPermission = r.kind
                "FALTA PERMISO: ${r.message}" to true
            }
            is SkillResult.Failed -> "FALLÓ: ${r.message}" to true
        }
    }

    /** Convierte los valores que manda la IA (números, booleanos, texto) a texto para validar. */
    fun stringify(input: Map<*, *>): Map<String, String> = input.mapNotNull { (k, v) ->
        val key = k?.toString() ?: return@mapNotNull null
        val value = when (v) {
            null -> return@mapNotNull null
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
            else -> v.toString()
        }
        key to value
    }.toMap()

    /** Esquema JSON de cada acción (formato común a Claude y Gemini). */
    fun tools(): List<Triple<String, String, Map<String, Any>>> = registry().catalog().map { (skill, action) ->
        val props = action.params.associate { p ->
            p.name to buildMap<String, Any> {
                put("type", when (p.type) {
                    com.sara.core.skills.ParamType.STRING -> "string"
                    com.sara.core.skills.ParamType.INTEGER -> "integer"
                    com.sara.core.skills.ParamType.NUMBER -> "number"
                    com.sara.core.skills.ParamType.BOOLEAN -> "boolean"
                })
                put("description", p.description)
                if (p.options.isNotEmpty()) put("enum", p.options)
            }
        }
        val schema = mapOf("type" to "object", "properties" to props, "required" to action.params.filter { it.required }.map { it.name })
        val risk = when (action.risk) { Risk.INFO -> "consulta"; Risk.CHANGE -> "cambia algo en el ${com.sara.core.model.AssistantIdentity.device}"; Risk.SENSITIVE -> "sensible, ${com.sara.core.model.AssistantIdentity.name} pide confirmación" }
        Triple(toolName(skill.id, action.name), "${skill.description} ${action.description} ($risk)", schema)
    }

    /**
     * Si una herramienta es sensible, Sara pregunta antes y sigue con [resume] sólo si el usuario
     * dice que sí. Devuelve null si no hace falta confirmar.
     */
    fun confirmationFor(calls: List<Call>, resume: suspend () -> ExecutionResult, onDecline: () -> Unit): ExecutionResult? {
        val sensitive = calls.firstOrNull { isSensitive(it.name) } ?: return null
        return ExecutionResult(true, "", confirmation = Confirmation(confirmQuestion(sensitive), "Listo, no lo hago.") { resume() }).also {
            // Si responde que no, la próxima conversación empieza de cero (quedó una herramienta sin respuesta).
            onDecline()
        }
    }

    fun finalResult(text: String): ExecutionResult =
        ExecutionResult(true, text.trim().ifBlank { "Listo." }, needsPermission = lastPermission)

    fun failure(text: String): ExecutionResult = ExecutionResult(false, text, ErrorKind.EXECUTION_FAILED, needsPermission = lastPermission)

    fun contextLine(): String {
        val zone = TimeZone.getDefault().id
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy, HH:mm", Locale.forLanguageTag("es-AR")))
        val memories = memory.all().entries.joinToString("; ") { "${it.key} = ${it.value}" }.ifBlank { "nada todavía" }
        return "[Ahora: $now ($zone). Recuerdos guardados: $memories]"
    }

    companion object {
        const val MAX_STEPS = 8

        val PLANNER_PROMPT: String get() = """
            Sos ${com.sara.core.model.AssistantIdentity.name}, una asistente de voz en ${com.sara.core.model.AssistantIdentity.platform}, al estilo de JARVIS. Hablás en español rioplatense, con voseo, cálida y breve.
            Tus respuestas se leen en voz alta: una o dos oraciones cortas, sin listas, sin emojis, sin markdown y sin enlaces (salvo que pidan una explicación).
            Tenés herramientas para actuar en el ${com.sara.core.model.AssistantIdentity.device} (alarmas, recordatorios, clima, música, volumen, apps, WhatsApp, llamadas, calendario, notas, listas, mapas, ajustes, rutinas, búsquedas en Google y más).
            Para resolver un pedido: identificá el objetivo, dividilo en pasos, usá las herramientas necesarias en orden y, al final, contá en una frase qué lograste.
            Reglas de seguridad y verdad:
            - Usá sólo las herramientas; nunca inventes resultados.
            - Cada herramienta responde HECHO, PEDIDO, A MEDIAS, FALTA PERMISO o FALLÓ. Decí que algo está hecho sólo si dice HECHO. Si dice A MEDIAS o PEDIDO, explicá qué falta. Si falló, decilo y, si es seguro, probá una alternativa (una sola vez).
            - No repitas una herramienta que ya falló con los mismos datos.
            - Para datos actuales (clima, noticias, resultados, precios) usá las herramientas de clima o de búsqueda.
            - Guardá recuerdos sólo si el usuario lo pide; nunca guardes datos sensibles (claves, documentos, salud) sin que lo pida explícitamente.
            - Si falta un dato imprescindible (por ejemplo, a quién mandar un mensaje), preguntalo en una frase en vez de adivinar.
            Cada mensaje del usuario empieza con la fecha, la hora y lo que recordás de él entre corchetes: usalo como contexto, no lo repitas.
        """.trimIndent()
    }
}
