package com.lucyremota.nucleo.engine

/** Disponibilidad de un motor en el momento de la consulta. */
sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: UnavailableReason, val detail: String) : Availability
}

enum class UnavailableReason { NOT_CONFIGURED, MISSING_CREDENTIALS, MISSING_MODEL, NO_NETWORK, NOT_SUPPORTED, PERMISSION_DENIED }

enum class EngineErrorType { NETWORK, SERVER, NOT_AVAILABLE, CREDENTIALS, PERMISSION, AUDIO_INPUT, BUSY, NO_SPEECH, NO_MATCH, UNKNOWN }

data class EngineError(val type: EngineErrorType, val message: String, val cause: Throwable? = null) {
    /** Errores técnicos del motor que justifican pasar al motor alternativo (no los del usuario o permisos). */
    val allowsFallback: Boolean
        get() = type in setOf(
            EngineErrorType.NETWORK,
            EngineErrorType.SERVER,
            EngineErrorType.NOT_AVAILABLE,
            EngineErrorType.CREDENTIALS,
            EngineErrorType.UNKNOWN,
        )
}

/** Modo elegido por el usuario para voz y reconocimiento. */
enum class EngineMode { AUTOMATIC, ONLINE, OFFLINE }

interface ConnectivityStatus {
    fun isOnline(): Boolean
}

// ---------------------------------------------------------------------------------------------
// Wake word
// ---------------------------------------------------------------------------------------------

data class WakeWordConfig(
    /** 0 = muy estricto (menos falsas activaciones), 1 = muy sensible. */
    val sensitivity: Float,
    /** «Ey Sara»: exige el llamado («ey», «hey», «oye») antes del nombre; «Sara» sola no activa. */
    val requirePrefix: Boolean = true,
)

sealed interface WakeWordResult {
    /**
     * [commandStarted]: la orden vino pegada al nombre («Lucy, poné música»); el motor ya guardó ese
     * audio para el reconocimiento, así que no se responde «¿Sí?» y se sigue escuchando directo.
     */
    data class Detected(val confidence: Float?, val commandStarted: Boolean = false) : WakeWordResult
    data class Failed(val error: EngineError) : WakeWordResult
}

/**
 * Detecta la palabra de activación de forma local.
 * La implementación abre el micrófono al llamar a [awaitWakeWord] y DEBE liberarlo antes de
 * devolver un resultado o al cancelarse la corrutina.
 */
interface WakeWordEngine {
    val id: String
    val displayName: String
    suspend fun availability(): Availability
    suspend fun awaitWakeWord(config: WakeWordConfig): WakeWordResult
}

// ---------------------------------------------------------------------------------------------
// Reconocimiento de voz
// ---------------------------------------------------------------------------------------------

data class RecognitionRequest(
    val languageTag: String,
    val maxSpeechMillis: Long = 10_000,
    val noSpeechTimeoutMillis: Long = 6_000,
    /** Silencio que se tolera dentro de la orden antes de darla por terminada (pausas al hablar). */
    val endSilenceMillis: Long = 1_700,
)

sealed interface RecognitionResult {
    val engineId: String

    data class Success(val text: String, val confidence: Float?, override val engineId: String) : RecognitionResult
    data class Failure(val error: EngineError, override val engineId: String) : RecognitionResult
}

interface SpeechRecognitionEngine {
    val id: String
    val displayName: String

    /** `true` si el audio sale del dispositivo. Nunca se usa en modo offline. */
    val isOnline: Boolean
    suspend fun availability(): Availability
    suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit = {}): RecognitionResult
}

// ---------------------------------------------------------------------------------------------
// Síntesis de voz
// ---------------------------------------------------------------------------------------------

data class VoiceOption(
    val id: String,
    val name: String,
    val locale: String,
    val requiresNetwork: Boolean,
    val description: String = "",
)

data class VoiceParams(
    val voiceId: String? = null,
    /** 1.0 = velocidad normal. Rango admitido 0.5 - 2.0. */
    val rate: Float = 1.0f,
    /** 0.0 - 1.0, relativo al volumen del sistema. */
    val volume: Float = 1.0f,
    val languageTag: String = "es-AR",
    /** Frase corta y repetida («Te escucho»): el motor puede guardar el audio y reproducirlo al instante. */
    val cacheable: Boolean = false,
)

sealed interface SpeakResult {
    val engineId: String

    data class Completed(override val engineId: String) : SpeakResult
    data class Failure(val error: EngineError, override val engineId: String) : SpeakResult
}

interface VoiceEngine {
    val id: String
    val displayName: String
    val isOnline: Boolean
    suspend fun availability(): Availability
    suspend fun voices(languageTag: String): List<VoiceOption>

    /** Suspende hasta terminar de hablar. Cancelar la corrutina corta el audio. */
    suspend fun speak(text: String, params: VoiceParams): SpeakResult

    /** Prepara de antemano el audio de una frase cacheable para que suene sin demora. */
    suspend fun prepare(text: String, params: VoiceParams) = Unit
}

/** Proveedor de los motores configurados actualmente (puede cambiar con los ajustes). */
interface EngineProvider {
    fun wakeWordEngine(): WakeWordEngine?
    fun onlineRecognizer(): SpeechRecognitionEngine?
    fun offlineRecognizer(): SpeechRecognitionEngine?
    fun onlineVoice(): VoiceEngine?
    fun offlineVoice(): VoiceEngine?
}
