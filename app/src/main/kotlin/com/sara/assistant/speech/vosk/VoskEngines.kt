package com.sara.assistant.speech.vosk

import com.sara.assistant.audio.CaptureOutcome
import com.sara.assistant.audio.MicrophoneSource
import com.sara.core.engine.Availability
import com.sara.core.engine.EngineError
import com.sara.core.engine.EngineErrorType
import com.sara.core.engine.RecognitionRequest
import com.sara.core.engine.RecognitionResult
import com.sara.core.engine.SpeechRecognitionEngine
import com.sara.core.engine.UnavailableReason
import com.sara.core.engine.WakeWordConfig
import com.sara.core.engine.WakeWordEngine
import com.sara.core.engine.WakeWordResult
import com.sara.core.util.SaraLogger
import com.sara.core.util.WakeWordTuning
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

private const val MISSING_MODEL =
    "Falta el modelo de voz en español. Descargalo en Ajustes › Activación (unos 40 MB, una sola vez)."

/**
 * Palabra de activación con Vosk, en dos etapas y 100 % local:
 * 1. Reconocimiento continuo con gramática restringida a ["sara", "[unk]"] (liviano); la confianza
 *    por palabra se compara con la sensibilidad elegida.
 * 2. Ante un candidato, el fragmento se transcribe con vocabulario libre y sólo se acepta si
 *    «Sara» aparece al principio. Así se descartan frases parecidas («para», «subí el volumen»).
 */
class VoskWakeWordEngine(
    private val models: VoskModelManager,
    private val microphone: MicrophoneSource,
    private val logger: SaraLogger,
) : WakeWordEngine {
    override val id = "vosk-wake"
    override val displayName = "Vosk (local, sin cuenta)"

    override suspend fun availability(): Availability = when {
        !microphone.hasPermission() -> Availability.Unavailable(UnavailableReason.PERMISSION_DENIED, "Sara necesita permiso de micrófono.")
        !models.isInstalled() -> Availability.Unavailable(UnavailableReason.MISSING_MODEL, MISSING_MODEL)
        else -> Availability.Available
    }

    override suspend fun awaitWakeWord(config: WakeWordConfig): WakeWordResult {
        val model = models.model()
            ?: return WakeWordResult.Failed(EngineError(EngineErrorType.NOT_AVAILABLE, MISSING_MODEL))
        val recognizer = Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat(), GRAMMAR).apply { setWords(true) }
        // Audio de la frase en curso (sólo en memoria, se descarta en cada fin de frase).
        val utterance = ByteArrayOutputStream()
        var detected: Float? = null
        try {
            val outcome = microphone.capture { buffer, length ->
                if (utterance.size() < MAX_UTTERANCE_BYTES) utterance.write(buffer, 0, length)
                if (recognizer.acceptWaveForm(buffer, length)) {
                    val json = recognizer.result
                    val candidate = WakeWordTuning.accept(parseWords(json), config.sensitivity)
                    if (candidate != null) {
                        val transcript = transcribe(model, utterance.toByteArray())
                        if (WakeWordTuning.verifiedByTranscript(transcript)) {
                            detected = candidate
                        } else {
                            logger.d(TAG, "Candidato rechazado en la verificación")
                        }
                    }
                    utterance.reset()
                }
                detected == null
            }
            detected?.let { return WakeWordResult.Detected(it) }
            return when (outcome) {
                is CaptureOutcome.Failed -> WakeWordResult.Failed(outcome.error)
                is CaptureOutcome.Completed -> WakeWordResult.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono se cerró."))
            }
        } finally {
            recognizer.close()
        }
    }

    private fun parseWords(json: String): List<Pair<String, Float>> {
        val obj = JSONObject(json)
        val result = obj.optJSONArray("result")
        if (result != null) {
            return (0 until result.length()).map { i ->
                val w = result.getJSONObject(i)
                w.optString("word") to w.optDouble("conf", 0.0).toFloat()
            }
        }
        return obj.optString("text").split(' ').filter { it.isNotBlank() }.map { it to 1f }
    }

    /** Segunda etapa: transcripción libre del fragmento candidato (sólo se ejecuta ante un posible «Sara»). */
    private fun transcribe(model: Model, audio: ByteArray): String {
        val free = Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat())
        return try {
            free.acceptWaveForm(audio, audio.size)
            JSONObject(free.finalResult).optString("text")
        } finally {
            free.close()
        }
    }

    private companion object {
        const val TAG = "VoskWake"
        const val GRAMMAR = "[\"sara\", \"[unk]\"]"
        const val MAX_UTTERANCE_BYTES = MicrophoneSource.SAMPLE_RATE * 2 * 8 // 8 s
    }
}

/** Reconocimiento offline de órdenes con Vosk (vocabulario libre en español). */
class VoskSpeechRecognitionEngine(
    private val models: VoskModelManager,
    private val microphone: MicrophoneSource,
) : SpeechRecognitionEngine {
    override val id = "vosk-offline"
    override val displayName = "Vosk (offline)"
    override val isOnline = false

    override suspend fun availability(): Availability = when {
        !microphone.hasPermission() -> Availability.Unavailable(UnavailableReason.PERMISSION_DENIED, "Sara necesita permiso de micrófono.")
        !models.isInstalled() -> Availability.Unavailable(UnavailableReason.MISSING_MODEL, MISSING_MODEL)
        else -> Availability.Available
    }

    override suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult {
        val model = models.model()
            ?: return RecognitionResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, MISSING_MODEL), id)
        val recognizer = Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat())
        val start = System.currentTimeMillis()
        var text: String? = null
        var heardSpeech = false
        var lastPartial = ""
        try {
            val outcome = microphone.capture { buffer, length ->
                val elapsed = System.currentTimeMillis() - start
                if (recognizer.acceptWaveForm(buffer, length)) {
                    val t = JSONObject(recognizer.result).optString("text").trim()
                    if (t.isNotEmpty()) {
                        text = t
                        return@capture false
                    }
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        heardSpeech = true
                        lastPartial = partial
                        onPartial(partial)
                    }
                }
                when {
                    !heardSpeech && elapsed > request.noSpeechTimeoutMillis -> false
                    elapsed > request.maxSpeechMillis -> {
                        text = JSONObject(recognizer.finalResult).optString("text").trim()
                        false
                    }
                    else -> true
                }
            }
            val recognized = text
            return when {
                !recognized.isNullOrBlank() -> RecognitionResult.Success(recognized, null, id)
                outcome is CaptureOutcome.Failed -> RecognitionResult.Failure(outcome.error, id)
                outcome is CaptureOutcome.Completed && outcome.allSilent -> RecognitionResult.Failure(
                    EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono no recibe audio. Revisá que no esté bloqueado en los ajustes rápidos."), id,
                )
                else -> RecognitionResult.Failure(EngineError(EngineErrorType.NO_SPEECH, "No se detectó voz."), id)
            }
        } finally {
            recognizer.close()
        }
    }
}
