package com.sara.core.execution

import com.sara.core.model.CommandIntent
import com.sara.core.model.ErrorKind
import com.sara.core.model.PermissionKind
import com.sara.core.memory.ConversationalAssistant
import com.sara.core.memory.MemoryKeys
import com.sara.core.memory.MemoryStore
import com.sara.core.memory.SmallTalk
import com.sara.core.interpreter.TextNormalizer
import com.sara.core.model.VolumeChange
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Volumen
// ---------------------------------------------------------------------------------------------

/** Estado del canal multimedia en índices del sistema. */
data class VolumeState(
    val current: Int,
    val min: Int,
    val max: Int,
    val isFixed: Boolean = false,
    val isMuted: Boolean = false,
)

sealed interface VolumeTarget {
    data class Index(val index: Int, val percent: Int) : VolumeTarget
    data object Mute : VolumeTarget
    data object Unmute : VolumeTarget
}

/**
 * Conversión entre porcentajes y los índices discretos del sistema.
 * - Relativo ("subí un 30%"): suma 30 puntos porcentuales al nivel actual.
 * - Absoluto ("poné el volumen al 30%"): fija el 30% del rango disponible.
 */
object VolumeCalculator {
    const val DEFAULT_STEP = 10

    fun percentOf(state: VolumeState): Int {
        val range = state.max - state.min
        if (range <= 0) return 0
        return (((state.current - state.min) * 100.0) / range).roundToInt().coerceIn(0, 100)
    }

    fun indexFor(percent: Int, state: VolumeState): Int {
        val range = state.max - state.min
        if (range <= 0) return state.min
        return (state.min + (percent.coerceIn(0, 100) * range / 100.0).roundToInt()).coerceIn(state.min, state.max)
    }

    fun target(state: VolumeState, change: VolumeChange): VolumeTarget {
        val currentPercent = percentOf(state)
        return when (change) {
            is VolumeChange.Increase -> {
                val percent = (currentPercent + (change.points ?: DEFAULT_STEP)).coerceIn(0, 100)
                var index = indexFor(percent, state)
                if (index <= state.current && state.current < state.max) index = state.current + 1
                VolumeTarget.Index(index, percentOf(state.copy(current = index)))
            }
            is VolumeChange.Decrease -> {
                val percent = (currentPercent - (change.points ?: DEFAULT_STEP)).coerceIn(0, 100)
                var index = indexFor(percent, state)
                if (index >= state.current && state.current > state.min) index = state.current - 1
                VolumeTarget.Index(index, percentOf(state.copy(current = index)))
            }
            is VolumeChange.SetTo -> {
                val index = indexFor(change.percent, state)
                VolumeTarget.Index(index, percentOf(state.copy(current = index)))
            }
            VolumeChange.Max -> VolumeTarget.Index(state.max, 100)
            VolumeChange.Mute -> VolumeTarget.Mute
            VolumeChange.Unmute -> VolumeTarget.Unmute
        }
    }
}

interface VolumeControl {
    fun state(): VolumeState

    /** Devuelve `false` si el sistema rechazó el cambio (No molestar, política del dispositivo, etc.). */
    fun setIndex(index: Int): Boolean
    fun setMuted(muted: Boolean): Boolean
}

// ---------------------------------------------------------------------------------------------
// Multimedia
// ---------------------------------------------------------------------------------------------

enum class MediaAction { PLAY, PAUSE, RESUME, NEXT, PREVIOUS, STOP }

sealed interface MediaActionResult {
    /** Acción confirmada por la sesión multimedia. */
    data class Done(val appName: String?) : MediaActionResult

    /** STOP no soportado pero se pausó en su lugar. */
    data class PausedInstead(val appName: String?) : MediaActionResult

    /** Se envió la orden pero la app no confirmó el cambio de estado. */
    data class NotConfirmed(val appName: String?) : MediaActionResult

    /** Se envió una tecla multimedia sin poder verificar (falta acceso a notificaciones). */
    data object SentWithoutVerification : MediaActionResult
    data object NothingPlaying : MediaActionResult
    data object NoMediaApp : MediaActionResult

    /** Android no deja abrir apps desde segundo plano: se dejó una notificación para tocar. */
    data class NeedsTap(val appName: String?) : MediaActionResult
    data class Unsupported(val appName: String?) : MediaActionResult
    data class Failed(val message: String) : MediaActionResult
}

interface MediaControl {
    /** [app]: app de música pedida («en Spotify»), o `null` para la que esté sonando. */
    suspend fun perform(action: MediaAction, query: String? = null, app: String? = null): MediaActionResult
}

sealed interface AppLaunchResult {
    data class Opened(val label: String, val deviceLocked: Boolean) : AppLaunchResult
    data class NeedsTap(val label: String) : AppLaunchResult
    data class NotFound(val query: String) : AppLaunchResult
    data class Failed(val message: String) : AppLaunchResult
}

sealed interface CloseAppsResult {
    data object Closed : CloseAppsResult
    /** Falta activar el servicio de accesibilidad de Sara. */
    data object NeedsAccessibility : CloseAppsResult
    /** Activada en Ajustes pero el sistema no la está ejecutando (pasa en algunos teléfonos). */
    data object NotResponding : CloseAppsResult
    data object DeviceLocked : CloseAppsResult
    data class Failed(val message: String) : CloseAppsResult
}

interface AppLauncher {
    suspend fun open(name: String): AppLaunchResult

    /** Nombre real de la app instalada que coincide con [name], o null si no existe. */
    suspend fun findLabel(name: String): String? = name

    /** Cierra las apps recientes (Android sólo lo permite con accesibilidad). */
    suspend fun closeBackgroundApps(): CloseAppsResult = CloseAppsResult.Failed("no disponible")
}

// ---------------------------------------------------------------------------------------------
// Ejecutor
// ---------------------------------------------------------------------------------------------

data class ExecutionResult(
    val success: Boolean,
    val response: String,
    val errorKind: ErrorKind? = null,
    /** Permiso que faltó: la app muestra un botón para activarlo. */
    val needsPermission: PermissionKind? = null,
    /** Si no es nulo, Sara pregunta [Confirmation.question] y sólo ejecuta si le dicen que sí. */
    val confirmation: Confirmation? = null,
    /** Si no es nulo, Sara pregunta [FollowUp.question] y le pasa la respuesta a [FollowUp.onAnswer]. */
    val followUp: FollowUp? = null,
) {
    val needsNotificationAccess: Boolean get() = needsPermission == PermissionKind.NOTIFICATION_ACCESS
}

class FollowUp(
    val question: String,
    val onAnswer: suspend (String) -> ExecutionResult,
)

class Confirmation(
    val question: String,
    val declined: String,
    val onConfirm: suspend () -> ExecutionResult,
)

data class MessageContact(val name: String, val address: String)

sealed interface MessagePrepareResult {
    data class Found(val contact: MessageContact) : MessagePrepareResult
    data object ContactNotFound : MessagePrepareResult
    data object NoApp : MessagePrepareResult
    data class NeedsPermission(val kind: PermissionKind) : MessagePrepareResult
}

sealed interface MessageSendResult {
    data object Sent : MessageSendResult
    /** Quedó escrito en el chat; falta tocar «Enviar» (sin accesibilidad no se puede tocar solo). */
    data object ReadyToSend : MessageSendResult
    data object NeedsTap : MessageSendResult
    data object DeviceLocked : MessageSendResult
    /** Se buscó el chat dentro de WhatsApp y no apareció. */
    data object ChatNotFound : MessageSendResult
    data class Failed(val message: String) : MessageSendResult
}

/** Mensajería (WhatsApp). */
interface Messenger {
    /** [group]: es un grupo o chat que no está en contactos; se busca dentro de WhatsApp. */
    suspend fun prepare(contact: String, group: Boolean = false): MessagePrepareResult
    suspend fun send(contact: MessageContact, message: String): MessageSendResult
}

interface CommandExecutor {
    suspend fun execute(intent: CommandIntent): ExecutionResult
}

class DefaultCommandExecutor(
    private val media: MediaControl,
    private val volume: VolumeControl,
    private val responses: ResponseCatalog = ResponseCatalog(),
    /** Indica si hay acceso a sesiones multimedia (Notification Listener). */
    private val hasMediaSessionAccess: () -> Boolean = { true },
    private val apps: AppLauncher? = null,
    private val messenger: Messenger? = null,
    /** Preguntar «¿Lo mando?» antes de enviar mensajes. */
    private val confirmMessages: () -> Boolean = { true },
    private val memory: MemoryStore? = null,
    private val assistant: ConversationalAssistant? = null,
) : CommandExecutor {

    override suspend fun execute(intent: CommandIntent): ExecutionResult = when (intent) {
        // Sin app pedida, la que el usuario eligió («cada vez que pida música abrí BitChord»).
        is CommandIntent.PlayMusic -> media(MediaAction.PLAY, intent.query, intent.app ?: memory?.get(MemoryKeys.MUSIC_APP))
        is CommandIntent.OpenApp -> openApp(intent.name)
        CommandIntent.Pause -> media(MediaAction.PAUSE)
        CommandIntent.Resume -> media(MediaAction.RESUME)
        CommandIntent.Next -> media(MediaAction.NEXT)
        CommandIntent.Previous -> media(MediaAction.PREVIOUS)
        CommandIntent.StopMusic -> media(MediaAction.STOP)
        is CommandIntent.AdjustVolume -> adjustVolume(intent.change)
        CommandIntent.CloseBackgroundApps -> closeBackgroundApps()
        is CommandIntent.SendMessage -> sendMessage(intent)
        is CommandIntent.Remember -> remember(intent)
        is CommandIntent.Forget -> forget(intent.key)
        CommandIntent.RecallMemory -> recall()
        is CommandIntent.Chat -> chat(intent.text)
        CommandIntent.Cancel -> ExecutionResult(true, responses.cancelled())
        is CommandIntent.Unknown -> ExecutionResult(false, responses.notUnderstood(), ErrorKind.RECOGNITION_FAILED)
    }

    private suspend fun openApp(name: String): ExecutionResult {
        val launcher = apps ?: return ExecutionResult(false, responses.cannotOpenApps(), ErrorKind.EXECUTION_FAILED)
        return when (val result = runCatching { launcher.open(name) }.getOrElse { AppLaunchResult.Failed(it.message ?: "error") }) {
            is AppLaunchResult.Opened -> ExecutionResult(true, responses.appOpened(result.label, result.deviceLocked))
            is AppLaunchResult.NeedsTap -> ExecutionResult(true, responses.tapToOpen(result.label), needsPermission = PermissionKind.OVERLAY)
            is AppLaunchResult.NotFound -> ExecutionResult(false, responses.appNotFound(result.query), ErrorKind.EXECUTION_FAILED)
            is AppLaunchResult.Failed -> ExecutionResult(false, responses.failed(result.message), ErrorKind.EXECUTION_FAILED)
        }
    }

    private suspend fun remember(intent: CommandIntent.Remember): ExecutionResult {
        val store = memory ?: return ExecutionResult(false, responses.cannotRemember(), ErrorKind.EXECUTION_FAILED)
        var value = intent.value
        if (intent.key == MemoryKeys.MUSIC_APP) {
            value = apps?.findLabel(value)
                ?: return ExecutionResult(false, responses.appNotFound(value), ErrorKind.EXECUTION_FAILED)
        }
        store.put(intent.key, value)
        return ExecutionResult(true, if (intent.key == MemoryKeys.MUSIC_APP) responses.musicAppSaved(value) else responses.remembered())
    }

    private fun forget(spoken: String): ExecutionResult {
        val store = memory ?: return ExecutionResult(false, responses.cannotRemember(), ErrorKind.EXECUTION_FAILED)
        val all = store.all()
        val wanted = TextNormalizer.normalize(spoken)
        val key = all.keys.firstOrNull { it == spoken }
            ?: all.keys.firstOrNull { TextNormalizer.normalize(it).contains(wanted) }
            ?: all.entries.firstOrNull { TextNormalizer.normalize(it.value).contains(wanted) }?.key
            ?: return ExecutionResult(
                spoken == MemoryKeys.MUSIC_APP,
                if (spoken == MemoryKeys.MUSIC_APP) responses.musicAppDefault() else responses.nothingToForget(),
            )
        store.remove(key)
        return ExecutionResult(true, if (key == MemoryKeys.MUSIC_APP) responses.musicAppDefault() else responses.forgotten())
    }

    private fun recall(): ExecutionResult {
        val all = memory?.all().orEmpty()
        if (all.isEmpty()) return ExecutionResult(true, responses.nothingRemembered())
        return ExecutionResult(true, responses.recall(all.map { (k, v) -> MemoryKeys.describe(k, v) }))
    }

    private suspend fun chat(text: String): ExecutionResult {
        SmallTalk.answer(text)?.let { return ExecutionResult(true, it) }
        val ai = assistant?.takeIf { it.isAvailable() }
            ?: return ExecutionResult(false, responses.needsAi(), ErrorKind.EXECUTION_FAILED, needsPermission = PermissionKind.AI_KEY)
        return runCatching { ExecutionResult(true, ai.reply(text)) }
            .getOrElse { ExecutionResult(false, it.message ?: responses.aiFailed(), ErrorKind.EXECUTION_FAILED) }
    }

    private suspend fun sendMessage(intent: CommandIntent.SendMessage): ExecutionResult {
        val m = messenger ?: return ExecutionResult(false, responses.cannotSendMessages(), ErrorKind.EXECUTION_FAILED)
        if (intent.message.isBlank()) {
            return ExecutionResult(true, "", followUp = FollowUp(responses.whatToSay()) { answer ->
                sendMessage(intent.copy(message = answer.trim().trim('"', '«', '»').replaceFirstChar { it.uppercase() }))
            })
        }
        return when (val prepared = runCatching { m.prepare(intent.contact, intent.group) }.getOrElse { return ExecutionResult(false, responses.failed(it.message ?: "error"), ErrorKind.EXECUTION_FAILED) }) {
            is MessagePrepareResult.Found -> {
                val contact = prepared.contact
                val send: suspend () -> ExecutionResult = { deliver(m, contact, intent.message) }
                if (confirmMessages()) {
                    ExecutionResult(
                        true, "",
                        confirmation = Confirmation(responses.confirmMessage(contact.name, intent.message), responses.messageNotSent(), send),
                    )
                } else {
                    send()
                }
            }
            MessagePrepareResult.ContactNotFound -> ExecutionResult(false, responses.contactNotFound(intent.contact), ErrorKind.EXECUTION_FAILED)
            MessagePrepareResult.NoApp -> ExecutionResult(false, responses.noWhatsApp(), ErrorKind.EXECUTION_FAILED)
            is MessagePrepareResult.NeedsPermission ->
                ExecutionResult(false, responses.needsPermission(prepared.kind), ErrorKind.PERMISSION_MISSING, needsPermission = prepared.kind)
        }
    }

    private suspend fun deliver(m: Messenger, contact: MessageContact, message: String): ExecutionResult =
        when (val r = runCatching { m.send(contact, message) }.getOrElse { MessageSendResult.Failed(it.message ?: "error") }) {
            MessageSendResult.Sent -> ExecutionResult(true, responses.messageSent(contact.name))
            MessageSendResult.ReadyToSend -> ExecutionResult(
                true, responses.messageReady(contact.name), needsPermission = PermissionKind.ACCESSIBILITY,
            )
            MessageSendResult.NeedsTap -> ExecutionResult(true, responses.tapToOpen("WhatsApp"), needsPermission = PermissionKind.OVERLAY)
            MessageSendResult.DeviceLocked -> ExecutionResult(false, responses.unlockToSend(), ErrorKind.EXECUTION_FAILED)
            MessageSendResult.ChatNotFound -> ExecutionResult(false, responses.chatNotFound(contact.name), ErrorKind.EXECUTION_FAILED)
            is MessageSendResult.Failed -> ExecutionResult(false, responses.messageFailed(), ErrorKind.EXECUTION_FAILED)
        }

    private suspend fun closeBackgroundApps(): ExecutionResult {
        // Primero se corta la música: así queda en silencio aunque no se puedan cerrar las apps.
        val music = runCatching { media.perform(MediaAction.STOP) }.getOrNull()
        val musicStopped = music is MediaActionResult.Done || music is MediaActionResult.PausedInstead
        val launcher = apps ?: return ExecutionResult(false, responses.cannotCloseApps(), ErrorKind.EXECUTION_FAILED)
        return when (val r = runCatching { launcher.closeBackgroundApps() }.getOrElse { CloseAppsResult.Failed(it.message ?: "error") }) {
            CloseAppsResult.Closed -> ExecutionResult(true, responses.appsClosed())
            CloseAppsResult.NeedsAccessibility -> ExecutionResult(musicStopped, responses.needsAccessibility(musicStopped), ErrorKind.EXECUTION_FAILED.takeUnless { musicStopped }, needsPermission = PermissionKind.ACCESSIBILITY)
            CloseAppsResult.NotResponding -> ExecutionResult(musicStopped, responses.accessibilityStuck(musicStopped), ErrorKind.EXECUTION_FAILED.takeUnless { musicStopped }, needsPermission = PermissionKind.ACCESSIBILITY)
            CloseAppsResult.DeviceLocked -> ExecutionResult(musicStopped, responses.unlockToCloseApps(musicStopped), ErrorKind.EXECUTION_FAILED.takeUnless { musicStopped })
            is CloseAppsResult.Failed -> ExecutionResult(musicStopped, responses.cannotCloseApps(musicStopped), ErrorKind.EXECUTION_FAILED.takeUnless { musicStopped })
        }
    }

    private suspend fun media(action: MediaAction, query: String? = null, app: String? = null): ExecutionResult {
        val result = try {
            media.perform(action, query, app)
        } catch (e: Exception) {
            MediaActionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        val noAccess = !hasMediaSessionAccess()
        return when (result) {
            is MediaActionResult.Done -> ExecutionResult(true, responses.mediaDone(action, result.appName ?: app, query))
            is MediaActionResult.NeedsTap -> ExecutionResult(true, responses.tapToOpen(result.appName ?: app ?: "la música"), needsPermission = PermissionKind.OVERLAY)
            is MediaActionResult.PausedInstead -> ExecutionResult(true, responses.pausedInsteadOfStop(result.appName))
            is MediaActionResult.NotConfirmed -> ExecutionResult(false, responses.notConfirmed(action, result.appName), ErrorKind.EXECUTION_FAILED)
            MediaActionResult.SentWithoutVerification -> ExecutionResult(true, responses.sentBlind(action), needsPermission = PermissionKind.NOTIFICATION_ACCESS.takeIf { noAccess })
            MediaActionResult.NothingPlaying -> ExecutionResult(false, responses.nothingPlaying(), ErrorKind.EXECUTION_FAILED)
            MediaActionResult.NoMediaApp -> ExecutionResult(false, responses.noMediaApp(), ErrorKind.EXECUTION_FAILED, needsPermission = PermissionKind.NOTIFICATION_ACCESS.takeIf { noAccess })
            is MediaActionResult.Unsupported -> ExecutionResult(false, responses.unsupported(action, result.appName), ErrorKind.EXECUTION_FAILED)
            is MediaActionResult.Failed -> ExecutionResult(false, responses.failed(result.message), ErrorKind.EXECUTION_FAILED)
        }
    }

    private fun adjustVolume(change: VolumeChange): ExecutionResult {
        return try {
            val before = volume.state()
            if (before.isFixed) return ExecutionResult(false, responses.volumeFixed(), ErrorKind.EXECUTION_FAILED)
            when (val target = VolumeCalculator.target(before, change)) {
                VolumeTarget.Mute -> {
                    if (!volume.setMuted(true)) return ExecutionResult(false, responses.volumeRejected(), ErrorKind.EXECUTION_FAILED)
                    ExecutionResult(true, responses.muted())
                }
                VolumeTarget.Unmute -> {
                    if (!volume.setMuted(false)) return ExecutionResult(false, responses.volumeRejected(), ErrorKind.EXECUTION_FAILED)
                    ExecutionResult(true, responses.unmuted(VolumeCalculator.percentOf(volume.state())))
                }
                is VolumeTarget.Index -> {
                    if (change is VolumeChange.Increase && before.current >= before.max && !before.isMuted) {
                        return ExecutionResult(true, responses.alreadyMax())
                    }
                    if (change is VolumeChange.Decrease && before.isMuted) {
                        return ExecutionResult(true, responses.alreadyMuted())
                    }
                    if (change is VolumeChange.Decrease && before.current <= before.min) {
                        return ExecutionResult(true, responses.alreadyMin())
                    }
                    if (before.isMuted && target.index > before.min) volume.setMuted(false)
                    if (!volume.setIndex(target.index)) return ExecutionResult(false, responses.volumeRejected(), ErrorKind.EXECUTION_FAILED)
                    val after = volume.state()
                    val percent = VolumeCalculator.percentOf(after)
                    when {
                        after.current < target.index -> ExecutionResult(true, responses.volumeLimited(percent))
                        change is VolumeChange.Max -> ExecutionResult(true, responses.volumeMax())
                        change is VolumeChange.Increase -> ExecutionResult(true, responses.volumeUp(percent))
                        change is VolumeChange.Decrease -> ExecutionResult(true, responses.volumeDown(percent))
                        else -> ExecutionResult(true, responses.volumeSet(percent))
                    }
                }
            }
        } catch (e: Exception) {
            ExecutionResult(false, responses.failed(e.message ?: "error de audio"), ErrorKind.EXECUTION_FAILED)
        }
    }
}

/** Frases de Sara en español rioplatense. Las pausas se marcan con comas para una prosodia natural. */
class ResponseCatalog(private val random: Random = Random.Default) {
    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    /** Frases de confirmación: son fijas para poder tenerlas pregrabadas y responder al instante. */
    val listeningPhrases = listOf("Te escucho.", "Decime.", "Te escucho, decime.")
    fun listening() = listeningPhrases[random.nextInt(listeningPhrases.size)]

    /** Cuando no escuchó o no entendió: vuelve a preguntar una vez (también pregrabadas). */
    val retryPhrases = listOf("¿Qué dijiste?", "No te entendí, decímelo de nuevo.")
    fun askAgain() = retryPhrases[0]
    fun didNotUnderstandRetry() = retryPhrases[1]
    fun cancelled() = pick("Listo.", "Dale, cancelado.")
    fun notUnderstood() = "No te entendí bien. Podés pedirme, por ejemplo, que ponga música o que suba el volumen."
    fun didNotHear() = "No te escuché bien. Probá de nuevo."

    fun mediaDone(action: MediaAction, app: String?, query: String?) = when (action) {
        MediaAction.PLAY -> when {
            query != null && app != null -> "Perfecto, busco $query en $app."
            query != null -> "Perfecto, busco $query."
            app != null -> "Perfecto, voy a reproducir música en $app."
            else -> "Perfecto, voy a reproducir música."
        }
        MediaAction.RESUME -> pick("Dale, sigo reproduciendo.", "Listo, continúo con la música.")
        MediaAction.PAUSE -> "Listo, pausé la música."
        MediaAction.NEXT -> pick("Pasando a la siguiente.", "Listo, siguiente canción.")
        MediaAction.PREVIOUS -> "Vuelvo a la canción anterior."
        MediaAction.STOP -> "Listo, detuve la música."
    }

    fun pausedInsteadOfStop(app: String?) = "${app ?: "La app"} no permite detener, así que pausé la música."
    fun notConfirmed(action: MediaAction, app: String?) =
        "Le mandé la orden a ${app ?: "la app de música"}, pero no confirmó el cambio."
    fun sentBlind(action: MediaAction) = when (action) {
        MediaAction.PLAY, MediaAction.RESUME -> "Listo, le pedí al reproductor que siga con la música."
        else -> "Listo, mandé la orden al reproductor."
    }
    fun nothingPlaying() = "No hay música sonando ahora."
    fun noMediaApp() = "No encontré una app de música activa. Abrí tu app de música una vez y volvé a pedírmelo."
    fun unsupported(action: MediaAction, app: String?): String {
        val what = when (action) {
            MediaAction.PLAY, MediaAction.RESUME -> "reproducir desde acá"
            MediaAction.PAUSE -> "pausar desde acá"
            MediaAction.NEXT -> "pasar a la siguiente canción"
            MediaAction.PREVIOUS -> "volver a la canción anterior"
            MediaAction.STOP -> "detener la reproducción"
        }
        return "${app ?: "Esa app"} no permite $what."
    }
    fun failed(detail: String) = "No pude hacerlo. $detail"

    fun appOpened(label: String, locked: Boolean) =
        if (locked) "Abro $label. Desbloqueá el teléfono para verla." else "Abro $label."
    fun tapToOpen(label: String) = "Tocá la notificación para abrir $label."
    fun needsPermission(kind: PermissionKind) = when (kind) {
        PermissionKind.CONTACTS -> "Necesito ver tus contactos. Te dejé el botón."
        PermissionKind.ACCESSIBILITY -> "Activame en Accesibilidad. Te dejé el botón."
        PermissionKind.OVERLAY -> "Necesito un permiso. Te dejé el botón."
        else -> "Me falta un permiso. Te dejé el botón."
    }
    fun cannotSendMessages() = "Todavía no puedo mandar mensajes."
    fun cannotRemember() = "No puedo guardar recuerdos."
    fun musicAppSaved(app: String) = "Listo, la música en $app."
    fun musicAppDefault() = "Listo, vuelvo a la de siempre."
    fun remembered() = "Listo, lo recuerdo."
    fun forgotten() = "Listo, lo olvidé."
    fun nothingToForget() = "No tenía eso guardado."
    fun nothingRemembered() = "Todavía no recuerdo nada."
    fun recall(items: List<String>) = "Recuerdo que " + items.joinToString("; ") + "."
    fun needsAi() = "Para preguntas cargá la clave de Google. Te dejé el botón."
    fun whatToSay() = "¿Qué le digo?"
    fun aiFailed() = "No pude responder ahora."
    fun confirmMessage(name: String, message: String) = "¿Le mando a $name: $message?"
    fun messageNotSent() = "Listo, no lo mando."
    fun messageSent(name: String) = "Listo, se lo mandé a $name."
    fun messageReady(name: String) = "Te dejé el mensaje listo. Tocá enviar."
    fun messageFailed() = "No pude mandar el mensaje."
    fun contactNotFound(query: String) = "No encontré a $query en tus contactos."
    fun chatNotFound(name: String) = "No encontré el chat $name en WhatsApp."
    fun noWhatsApp() = "No encontré WhatsApp."
    fun unlockToSend() = "Desbloqueá el teléfono para mandarlo."
    fun appNotFound(query: String) = "No encontré ninguna app que se llame $query."
    fun cannotOpenApps() = "Todavía no puedo abrir apps en este dispositivo."
    fun appsClosed() = "Listo, cerré todo."
    private fun musicPrefix(stopped: Boolean) = if (stopped) "Saqué la música. " else ""
    fun needsAccessibility(musicStopped: Boolean = false) =
        musicPrefix(musicStopped) + "Activame en Accesibilidad. Te dejé el botón."
    fun accessibilityStuck(musicStopped: Boolean = false) =
        musicPrefix(musicStopped) + "Mi accesibilidad está trabada. Apagala y prendela."
    fun unlockToCloseApps(musicStopped: Boolean = false) = musicPrefix(musicStopped) + "Desbloqueá el teléfono para cerrar apps."
    fun cannotCloseApps(musicStopped: Boolean = false) = musicPrefix(musicStopped) + "No pude cerrar las apps."

    fun volumeUp(percent: Int) = "Listo, subí el volumen al $percent por ciento."
    fun volumeDown(percent: Int) = "Listo, bajé el volumen al $percent por ciento."
    fun volumeSet(percent: Int) = "Puse el volumen al $percent por ciento."
    fun volumeMax() = "Volumen al máximo."
    fun volumeLimited(percent: Int) = "El sistema limitó el volumen al $percent por ciento, para cuidar tus oídos."
    fun alreadyMax() = "El volumen ya está al máximo."
    fun alreadyMin() = "El volumen ya está al mínimo."
    fun muted() = "Silencié la música."
    fun alreadyMuted() = "La música está silenciada. Si querés, decime «quitá el silencio»."
    fun unmuted(percent: Int) = "Volví a activar el sonido, está al $percent por ciento."
    fun volumeFixed() = "Este dispositivo no permite cambiar el volumen."
    fun volumeRejected() = "El sistema no me dejó cambiar el volumen. Revisá el modo No molestar."
}
