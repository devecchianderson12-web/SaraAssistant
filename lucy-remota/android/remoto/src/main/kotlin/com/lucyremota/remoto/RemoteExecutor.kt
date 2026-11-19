package com.lucyremota.remoto

import com.lucyremota.nucleo.execution.CommandExecutor
import com.lucyremota.nucleo.execution.Confirmation
import com.lucyremota.nucleo.execution.ExecutionResult
import com.lucyremota.nucleo.model.CommandIntent
import com.lucyremota.nucleo.model.ErrorKind
import com.lucyremota.nucleo.model.VolumeChange
import com.lucyremota.nucleo.model.label
import com.lucyremota.protocolo.CommandStatus
import com.lucyremota.protocolo.PhoneLink
import org.json.JSONObject

/**
 * Ejecutor de Lucy Remota: convierte cada intención en una orden estructurada para Windows, la manda
 * y espera la confirmación de la PC antes de decir que se hizo. Lo que no es de la PC (charla,
 * preguntas, cuentas, recordar cosas) lo resuelve [local] en el teléfono.
 */
class RemoteExecutor(
    private val link: PhoneLink,
    private val local: CommandExecutor,
    private val orders: OrderLog,
    /** Lo último que se escuchó (para el historial de la pantalla). */
    private val lastHeard: () -> String,
) : CommandExecutor {

    /** Una orden para Windows: intención + parámetros con su tipo. */
    data class Remote(val intent: String, val params: JSONObject = JSONObject())

    override suspend fun execute(intent: CommandIntent): ExecutionResult {
        if (intent is CommandIntent.Sequence) {
            // «Bajá el volumen y abrí Chrome»: una por una, se dice el resultado de cada una.
            val said = mutableListOf<String>()
            for (step in intent.steps) {
                val r = execute(step)
                if (r.confirmation != null || r.followUp != null) return r.copy(response = (said + r.response).joinToString(" "))
                said += r.response
                if (!r.success) return r.copy(response = said.joinToString(" "))
            }
            return ExecutionResult(true, said.joinToString(" "), informative = true)
        }
        val remote = toRemote(intent) ?: return localOrExplain(intent)
        return send(remote, confirmed = false)
    }

    private suspend fun localOrExplain(intent: CommandIntent): ExecutionResult = when (intent) {
        is CommandIntent.CloseBackgroundApps -> ExecutionResult(true, "En la computadora no cierro todo junto: decime qué programa cierro.", informative = true)
        is CommandIntent.Skill -> when {
            intent.skill == "calculadora" || intent.skill == "listas" || intent.skill == "gustos" -> local.execute(intent)
            intent.skill == "dispositivo" && intent.action == "desbloquear" ->
                ExecutionResult(true, "Por seguridad Windows no deja desbloquearla a distancia: entrá con tu PIN o contraseña.", informative = true)
            else -> ExecutionResult(false, "Eso todavía no lo hago en la computadora.", ErrorKind.EXECUTION_FAILED)
        }
        else -> local.execute(intent) // charla, preguntas, memoria, cancelar
    }

    /** Intención del intérprete → orden de Windows (o null si se resuelve en el teléfono). */
    private fun toRemote(intent: CommandIntent): Remote? = when (intent) {
        is CommandIntent.PlayMusic -> Remote("MEDIA_PLAY", JSONObject().apply {
            intent.query?.let { put("query", it.take(120)) }
            intent.app?.lowercase()?.let { a -> listOf("spotify", "youtube").firstOrNull { a.contains(it) }?.let { put("app", it) } }
        })
        CommandIntent.Pause, CommandIntent.StopMusic -> Remote("MEDIA_PAUSE")
        CommandIntent.Resume -> Remote("MEDIA_RESUME")
        CommandIntent.Next -> Remote("MEDIA_NEXT")
        CommandIntent.Previous -> Remote("MEDIA_PREVIOUS")
        CommandIntent.NowPlaying -> Remote("MEDIA_NOW_PLAYING")
        is CommandIntent.AdjustVolume -> when (val c = intent.change) {
            is VolumeChange.Increase -> Remote("SET_VOLUME", JSONObject().put("delta", c.points ?: 10))
            is VolumeChange.Decrease -> Remote("SET_VOLUME", JSONObject().put("delta", -(c.points ?: 10)))
            is VolumeChange.SetTo -> Remote("SET_VOLUME", JSONObject().put("level", c.percent.coerceIn(0, 100)))
            VolumeChange.Max -> Remote("SET_VOLUME", JSONObject().put("level", 100))
            VolumeChange.Mute -> Remote("MUTE_AUDIO", JSONObject().put("muted", true))
            VolumeChange.Unmute -> Remote("MUTE_AUDIO", JSONObject().put("muted", false))
        }
        is CommandIntent.OpenApp -> Remote("OPEN_APPLICATION", JSONObject().put("name", intent.name.take(80)))
        is CommandIntent.Skill -> when {
            intent.skill == PcInterpreter.PC -> Remote(intent.action, JSONObject(intent.args as Map<*, *>))
            intent.skill == "dispositivo" && intent.action == "bloquear" -> Remote("LOCK_SESSION")
            intent.skill == "dispositivo" && intent.action in setOf("bateria", "info") -> Remote("GET_SYSTEM_INFO")
            intent.skill == "dispositivo" && intent.action == "brillo" -> {
                val pct = intent.args["porcentaje"]?.toIntOrNull()
                when (intent.args["accion"]) {
                    "subir" -> Remote("SET_BRIGHTNESS", JSONObject().put("delta", pct ?: 20))
                    "bajar" -> Remote("SET_BRIGHTNESS", JSONObject().put("delta", -(pct ?: 20)))
                    "fijar" -> pct?.let { Remote("SET_BRIGHTNESS", JSONObject().put("level", it.coerceIn(0, 100))) }
                    else -> null
                }
            }
            else -> null
        }
        else -> null
    }

    private suspend fun send(remote: Remote, confirmed: Boolean): ExecutionResult {
        // La PC dice qué sabe hacer al conectarse: si no está en su catálogo, se avisa sin mandar.
        val catalog = link.catalog.value
        if (catalog.isNotEmpty() && catalog.none { it.name == remote.intent }) {
            return ExecutionResult(false, "Esa computadora todavía no sabe hacer eso: actualizá Lucy Remota en la PC.", ErrorKind.EXECUTION_FAILED)
        }
        val heard = lastHeard()
        var orderId = ""
        val final = link.send(remote.intent, remote.params, confirmed = confirmed) { st ->
            orderId = st.commandId
            orders.upsert(SentOrder(st.commandId, System.currentTimeMillis(), heard, remote.intent, st.status, st.message))
        }
        if (orderId.isEmpty() || orderId != final.commandId) {
            orders.upsert(SentOrder(final.commandId, System.currentTimeMillis(), heard, remote.intent, final.status, final.message))
        }
        return when (final.status) {
            CommandStatus.SUCCESS -> ExecutionResult(true, final.message.ifBlank { "Listo." }, informative = true)
            CommandStatus.NEEDS_CONFIRMATION -> ExecutionResult(
                true, "",
                confirmation = Confirmation(final.message.ifBlank { "¿Lo hago?" }, "Listo, no hago nada.") { send(remote, confirmed = true) },
            )
            CommandStatus.NOT_CONNECTED -> ExecutionResult(false, "La computadora no está conectada. Abrí Lucy Remota en la PC, en la misma red.", ErrorKind.NETWORK, informative = true)
            CommandStatus.NO_ANSWER -> ExecutionResult(false, "La computadora no contestó; puede que lo haya hecho igual.", ErrorKind.NETWORK, informative = true)
            CommandStatus.EXPIRED -> ExecutionResult(false, "La orden llegó tarde a la computadora y no la hice.", ErrorKind.NETWORK, informative = true)
            else -> ExecutionResult(false, final.message.ifBlank { "La computadora no pudo hacerlo." }, ErrorKind.EXECUTION_FAILED, informative = true)
        }
    }

    companion object {
        /** Para el historial: «Volumen al 70 %» en vez de SET_VOLUME. */
        fun describe(intent: CommandIntent) = intent.label()
    }
}
