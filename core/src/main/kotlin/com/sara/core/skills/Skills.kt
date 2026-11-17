package com.sara.core.skills

import com.sara.core.model.PermissionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Habilidades de Sara. Cada una declara qué acciones sabe hacer, qué parámetros necesitan, qué
 * permisos usan, si cambian algo en el mundo real (y por eso piden confirmación) y cómo se verifica
 * el resultado. El cerebro (reglas locales o IA) sólo puede actuar a través de estas acciones:
 * nunca ejecuta código arbitrario.
 */
enum class Risk {
    /** Sólo consulta (clima, batería, calendario). */
    INFO,
    /** Cambia algo reversible en el teléfono (volumen, linterna, música, notas). */
    CHANGE,
    /** Efecto externo o difícil de deshacer (mandar mensajes, llamar, borrar): pide confirmación. */
    SENSITIVE,
}

enum class ParamType { STRING, INTEGER, NUMBER, BOOLEAN }

data class Param(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val options: List<String> = emptyList(),
)

data class SkillAction(
    val name: String,
    val description: String,
    val params: List<Param> = emptyList(),
    val risk: Risk = Risk.INFO,
    val permissions: Set<PermissionKind> = emptySet(),
    /** Cómo se comprueba que se hizo (para la documentación y el diagnóstico). */
    val verification: String = "",
    /** Pregunta de confirmación para acciones [Risk.SENSITIVE]. */
    val confirm: ((Map<String, String>) -> String)? = null,
)

sealed interface SkillResult {
    val message: String

    /** Hecho. [verified] = hay evidencia de que se completó (no sólo que se pidió). */
    data class Done(override val message: String, val verified: Boolean = true) : SkillResult
    /** Se dejó listo para que el usuario lo termine (por ejemplo, una pantalla abierta). */
    data class Partial(override val message: String) : SkillResult
    data class NeedsPermission(val kind: PermissionKind, override val message: String) : SkillResult
    data class Failed(override val message: String, val retryable: Boolean = false) : SkillResult
}

interface Skill {
    val id: String
    val description: String
    val actions: List<SkillAction>
    suspend fun run(action: String, args: Map<String, String>): SkillResult
}

/** Registro de habilidades: valida, controla tiempos y reintenta sólo lo que es seguro reintentar. */
class SkillRegistry(
    skills: List<Skill>,
    private val isGranted: (PermissionKind) -> Boolean = { true },
    private val timeoutMillis: Long = 20_000,
) {
    private val byId = skills.associateBy { it.id }

    val skills: Collection<Skill> get() = byId.values

    fun catalog(): List<Pair<Skill, SkillAction>> = byId.values.flatMap { s -> s.actions.map { s to it } }

    fun find(skill: String, action: String): SkillAction? = byId[skill]?.actions?.firstOrNull { it.name == action }

    /** Devuelve un mensaje de error si los parámetros no sirven (faltan o tienen otro tipo). */
    fun validate(action: SkillAction, args: Map<String, String>): String? {
        for (p in action.params) {
            val value = args[p.name]?.trim()
            if (value.isNullOrEmpty()) {
                if (p.required) return "Falta «${p.name}»."
                continue
            }
            val ok = when (p.type) {
                ParamType.STRING -> true
                ParamType.INTEGER -> value.toLongOrNull() != null
                ParamType.NUMBER -> value.replace(',', '.').toDoubleOrNull() != null
                ParamType.BOOLEAN -> value.lowercase() in setOf("true", "false", "si", "sí", "no")
            }
            if (!ok) return "«${p.name}» no es válido."
            if (p.options.isNotEmpty() && value !in p.options) return "«${p.name}» tiene que ser ${p.options.joinToString(" o ")}."
        }
        val unknown = args.keys - action.params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) return "No conozco «${unknown.first()}»."
        return null
    }

    suspend fun run(skillId: String, actionName: String, args: Map<String, String>): SkillResult {
        val skill = byId[skillId] ?: return SkillResult.Failed("No tengo la habilidad «$skillId».")
        val action = skill.actions.firstOrNull { it.name == actionName }
            ?: return SkillResult.Failed("«$skillId» no sabe hacer «$actionName».")
        validate(action, args)?.let { return SkillResult.Failed(it) }
        action.permissions.firstOrNull { !isGranted(it) }?.let { return SkillResult.NeedsPermission(it, "Me falta un permiso. Te dejé el botón.") }
        // Sólo se reintenta lo que no cambia nada (consultas) y una sola vez.
        val attempts = if (action.risk == Risk.INFO) 2 else 1
        var last: SkillResult = SkillResult.Failed("No pude hacerlo.")
        repeat(attempts) {
            last = try {
                withTimeoutOrNull(timeoutMillis) { skill.run(actionName, args) }
                    ?: SkillResult.Failed("Tardó demasiado.", retryable = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SkillResult.Failed(e.message ?: "Error inesperado.", retryable = true)
            }
            if (!(last is SkillResult.Failed && (last as SkillResult.Failed).retryable)) return last
        }
        return last
    }
}
