package com.sara.core.engine

import com.sara.core.util.SaraLogger

/** Aviso de cambio de motor durante un reconocimiento. */
data class EngineSwitchNotice(
    val fromEngineId: String?,
    val toEngineId: String,
    val message: String,
    /** `true` si el usuario ya había hablado y tiene que repetir la orden. */
    val requiresRepeat: Boolean,
)

/**
 * Política de selección de reconocimiento:
 * 1. ONLINE/AUTOMÁTICO con conexión -> motor online.
 * 2. Si falla la conexión -> motor offline (si está configurado y el usuario lo permite).
 * 3. OFFLINE -> jamás se invoca al motor online.
 * 4. Si ningún motor está disponible se informa con un error claro.
 */
class RecognitionRouter(
    private val connectivity: ConnectivityStatus,
    private val logger: SaraLogger = SaraLogger.None,
) {
    suspend fun recognize(
        mode: EngineMode,
        online: SpeechRecognitionEngine?,
        offline: SpeechRecognitionEngine?,
        request: RecognitionRequest,
        allowOfflineFallback: Boolean,
        onPartial: (String) -> Unit = {},
        onSwitch: suspend (EngineSwitchNotice) -> Unit = {},
    ): RecognitionResult {
        if (mode == EngineMode.OFFLINE) {
            val engine = offline
                ?: return RecognitionResult.Failure(
                    EngineError(EngineErrorType.NOT_AVAILABLE, "El reconocimiento offline no está configurado."), "none",
                )
            val availability = engine.availability()
            if (availability is Availability.Unavailable) {
                return RecognitionResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, availability.detail), engine.id)
            }
            return engine.recognize(request, onPartial)
        }

        val fallbackAllowed = mode == EngineMode.AUTOMATIC || allowOfflineFallback
        val connected = connectivity.isOnline()
        val onlineAvailability = when {
            online == null -> Availability.Unavailable(UnavailableReason.NOT_CONFIGURED, "No hay reconocimiento online configurado.")
            !connected -> Availability.Unavailable(UnavailableReason.NO_NETWORK, "No hay conexión a Internet.")
            else -> online.availability()
        }

        if (online == null || onlineAvailability is Availability.Unavailable) {
            val detail = (onlineAvailability as Availability.Unavailable).detail
            logger.d(TAG, "Online no disponible: $detail")
            val offlineAvailability = if (fallbackAllowed) offline?.availability() else null
            if (offline != null && offlineAvailability is Availability.Available) {
                onSwitch(EngineSwitchNotice(online?.id, offline.id, "$detail Uso el reconocimiento local.", requiresRepeat = false))
                return offline.recognize(request, onPartial)
            }
            val type = if (!connected) EngineErrorType.NETWORK else EngineErrorType.NOT_AVAILABLE
            val message = if (offlineAvailability is Availability.Unavailable) {
                "$detail Y el modo offline tampoco está listo: ${offlineAvailability.detail}"
            } else {
                detail
            }
            return RecognitionResult.Failure(EngineError(type, message), online?.id ?: "none")
        }

        val result = online.recognize(request, onPartial)
        if (result is RecognitionResult.Failure && result.error.allowsFallback && fallbackAllowed && offline != null) {
            if (offline.availability() is Availability.Available) {
                logger.d(TAG, "Fallback a ${offline.id} por ${result.error.type}")
                onSwitch(
                    EngineSwitchNotice(
                        online.id, offline.id,
                        "Se cortó el reconocimiento online. Repetímelo, te escucho en modo local.",
                        requiresRepeat = true,
                    ),
                )
                return offline.recognize(request, onPartial)
            }
        }
        return result
    }

    private companion object {
        const val TAG = "RecognitionRouter"
    }
}

/**
 * Política de voz:
 * - AUTOMÁTICO: online con respaldo offline.
 * - ONLINE: sólo online (si falla, se informa el error; no hay respaldo).
 * - OFFLINE: sólo el motor local.
 */
class VoiceRouter(
    private val connectivity: ConnectivityStatus,
    private val logger: SaraLogger = SaraLogger.None,
) {
    suspend fun speak(
        mode: EngineMode,
        online: VoiceEngine?,
        offline: VoiceEngine?,
        text: String,
        onlineParams: VoiceParams,
        offlineParams: VoiceParams,
    ): SpeakResult {
        return when (mode) {
            EngineMode.OFFLINE -> speakWith(offline, text, offlineParams, "La voz offline no está disponible.")
            EngineMode.ONLINE -> {
                val unavailable = onlineProblem(online)
                if (unavailable != null) {
                    SpeakResult.Failure(unavailable, online?.id ?: "none")
                } else {
                    online!!.speak(text, onlineParams)
                }
            }
            EngineMode.AUTOMATIC -> {
                val problem = onlineProblem(online)
                if (problem == null) {
                    val result = online!!.speak(text, onlineParams)
                    if (result is SpeakResult.Failure) {
                        logger.d(TAG, "Voz online falló (${result.error.message}); uso offline")
                        speakWith(offline, text, offlineParams, result.error.message)
                    } else {
                        result
                    }
                } else {
                    speakWith(offline, text, offlineParams, problem.message)
                }
            }
        }
    }

    private suspend fun onlineProblem(online: VoiceEngine?): EngineError? {
        if (online == null) return EngineError(EngineErrorType.NOT_AVAILABLE, "No hay voz online configurada.")
        if (!connectivity.isOnline()) return EngineError(EngineErrorType.NETWORK, "No hay conexión para la voz online.")
        val availability = online.availability()
        if (availability is Availability.Unavailable) {
            val type = if (availability.reason == UnavailableReason.MISSING_CREDENTIALS) EngineErrorType.CREDENTIALS else EngineErrorType.NOT_AVAILABLE
            return EngineError(type, availability.detail)
        }
        return null
    }

    private suspend fun speakWith(engine: VoiceEngine?, text: String, params: VoiceParams, reason: String): SpeakResult {
        if (engine == null) return SpeakResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, reason), "none")
        val availability = engine.availability()
        if (availability is Availability.Unavailable) {
            return SpeakResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, availability.detail), engine.id)
        }
        return engine.speak(text, params)
    }

    private companion object {
        const val TAG = "VoiceRouter"
    }
}
