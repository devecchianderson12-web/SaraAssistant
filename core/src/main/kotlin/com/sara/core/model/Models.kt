package com.sara.core.model

/** Cambio de volumen pedido por el usuario. Los porcentajes se expresan sobre el rango disponible (0-100). */
sealed interface VolumeChange {
    /** Aumenta [points] puntos porcentuales respecto del nivel actual. `null` = paso por defecto. */
    data class Increase(val points: Int?) : VolumeChange

    /** Disminuye [points] puntos porcentuales respecto del nivel actual. `null` = paso por defecto. */
    data class Decrease(val points: Int?) : VolumeChange

    /** Establece un porcentaje absoluto del rango disponible. */
    data class SetTo(val percent: Int) : VolumeChange

    data object Max : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
}

/** Intención reconocida en una orden de voz. */
sealed interface CommandIntent {
    /** Reproducir música. [query] es una búsqueda opcional ("música de Soda Stereo" -> "soda stereo"). */
    data class PlayMusic(val query: String? = null, val app: String? = null) : CommandIntent

    /** Abrir una app instalada por su nombre (o uno muy parecido). */
    data class OpenApp(val name: String) : CommandIntent
    data object Pause : CommandIntent
    data object Resume : CommandIntent
    data object Next : CommandIntent
    data object Previous : CommandIntent
    data object StopMusic : CommandIntent

    /** «Cerrá las apps en segundo plano»: saca la música y limpia las apps recientes. */
    data object CloseBackgroundApps : CommandIntent

    /** «Mandale un WhatsApp a Mi Amor que diga …». [message] conserva mayúsculas y acentos. */
    data class SendMessage(val contact: String, val message: String, val group: Boolean = false) : CommandIntent

    /** «Cada vez que te pida música abrí BitChord»: guarda una preferencia o rutina. */
    data class Remember(val key: String, val value: String) : CommandIntent
    /** «Olvidate de …». [key] puede ser la clave exacta o lo que dijo el usuario. */
    data class Forget(val key: String) : CommandIntent
    data object RecallMemory : CommandIntent

    /** Preguntas y charla («¿cómo estás?», «¿qué tiempo hace?»): local o con IA. */
    data class Chat(val text: String) : CommandIntent
    data class AdjustVolume(val change: VolumeChange) : CommandIntent
    data object Cancel : CommandIntent
    data class Unknown(val text: String) : CommandIntent
}

fun CommandIntent.label(): String = when (this) {
    is CommandIntent.PlayMusic -> (if (query.isNullOrBlank()) "Reproducir música" else "Reproducir: $query") + (app?.let { " en $it" } ?: "")
    is CommandIntent.OpenApp -> "Abrir $name"
    CommandIntent.Pause -> "Pausar"
    CommandIntent.Resume -> "Continuar"
    CommandIntent.Next -> "Siguiente canción"
    CommandIntent.Previous -> "Canción anterior"
    CommandIntent.StopMusic -> "Detener música"
    is CommandIntent.AdjustVolume -> when (val c = change) {
        is VolumeChange.Increase -> "Subir volumen" + (c.points?.let { " $it%" } ?: "")
        is VolumeChange.Decrease -> "Bajar volumen" + (c.points?.let { " $it%" } ?: "")
        is VolumeChange.SetTo -> "Volumen al ${c.percent}%"
        VolumeChange.Max -> "Volumen máximo"
        VolumeChange.Mute -> "Silenciar"
        VolumeChange.Unmute -> "Quitar silencio"
    }
    CommandIntent.CloseBackgroundApps -> "Cerrar apps en segundo plano"
    is CommandIntent.SendMessage -> "WhatsApp a $contact: $message"
    is CommandIntent.Remember -> "Recordar: $key = $value"
    is CommandIntent.Forget -> "Olvidar: $key"
    CommandIntent.RecallMemory -> "Qué recuerdo"
    is CommandIntent.Chat -> "Pregunta: $text"
    CommandIntent.Cancel -> "Cancelar"
    is CommandIntent.Unknown -> "No reconocida"
}

/** Resultado del intérprete: intención + confianza y la regla que la produjo (útil para diagnóstico). */
data class InterpretationResult(
    val intent: CommandIntent,
    val confidence: Float,
    val normalizedText: String,
    val ruleId: String,
)

enum class ErrorKind {
    PERMISSION_MISSING,
    MICROPHONE_UNAVAILABLE,
    ENGINE_UNAVAILABLE,
    NETWORK,
    CREDENTIALS,
    RECOGNITION_FAILED,
    NO_SPEECH,
    EXECUTION_FAILED,
    VOICE_FAILED,
    UNKNOWN,
}

data class AssistantError(val kind: ErrorKind, val message: String)

/** Estados visuales y lógicos de Sara. */
sealed interface AssistantState {
    data object Inactive : AssistantState
    data object WaitingForWakeWord : AssistantState
    data class Listening(val partialText: String = "") : AssistantState
    data class Processing(val text: String) : AssistantState
    data class Executing(val intent: CommandIntent) : AssistantState
    data class Speaking(val text: String) : AssistantState
    data class Error(val error: AssistantError) : AssistantState
}

enum class StateKind { INACTIVE, WAITING, LISTENING, PROCESSING, EXECUTING, SPEAKING, ERROR }

val AssistantState.kind: StateKind
    get() = when (this) {
        AssistantState.Inactive -> StateKind.INACTIVE
        AssistantState.WaitingForWakeWord -> StateKind.WAITING
        is AssistantState.Listening -> StateKind.LISTENING
        is AssistantState.Processing -> StateKind.PROCESSING
        is AssistantState.Executing -> StateKind.EXECUTING
        is AssistantState.Speaking -> StateKind.SPEAKING
        is AssistantState.Error -> StateKind.ERROR
    }

/** Cómo se activó una interacción. */
enum class Trigger { WAKE_WORD, MANUAL, ASSIST_GESTURE, NOTIFICATION }

/** Eventos puntuales para la interfaz (no son estado). */
sealed interface AssistantEvent {
    data class Activated(val trigger: Trigger) : AssistantEvent
    data class Heard(val text: String, val engineId: String) : AssistantEvent
    data class Responded(val text: String, val success: Boolean, val intent: CommandIntent?) : AssistantEvent
    data class Notice(val text: String) : AssistantEvent
    data class Failed(val error: AssistantError) : AssistantEvent
    /** Una orden necesita un permiso: la app muestra un botón que lleva directo a activarlo. */
    data class PermissionNeeded(val kind: PermissionKind) : AssistantEvent
}

/** Permisos o ajustes que alguna función de Sara necesita. */
enum class PermissionKind {
    MICROPHONE, NOTIFICATIONS, NOTIFICATION_ACCESS, OVERLAY, ACCESSIBILITY, CONTACTS, BATTERY, DEFAULT_ASSISTANT,
    /** Clave de la IA (Google o Claude) para responder preguntas. */
    AI_KEY,
}

data class HistoryEntry(
    val timestampMillis: Long,
    val heard: String,
    val intentLabel: String,
    val response: String,
    val success: Boolean,
)
