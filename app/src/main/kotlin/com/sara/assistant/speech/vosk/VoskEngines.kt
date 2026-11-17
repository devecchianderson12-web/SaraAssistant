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
import com.sara.core.util.WakeWordGate
import com.sara.core.util.WakeWordTuning
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

private const val MISSING_MODEL =
    "Falta el modelo de voz en español. Descargalo en Ajustes › Activación (unos 40 MB, una sola vez)."

/**
 * Palabra de activación con Vosk, en dos etapas y 100 % local:
 * 1. Reconocimiento continuo con gramática restringida a ["sara", "[unk]"]. Reacciona a los
 *    resultados parciales (sin esperar silencio), así responde rápido y funciona con música.
 * 2. Ante un candidato, los últimos 2,4 s se transcriben con vocabulario libre y sólo se acepta si
 *    aparece «Sara»; con música también vale ganarle a palabras parecidas («para», «cara»…).
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
        while (true) {
            // Si empieza o termina la música se reabre el micrófono con la fuente adecuada.
            val echoCancellation = microphone.echoCancellationWanted()
            val recognizer = Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat(), GRAMMAR).apply { setWords(true) }
            val gate = WakeWordGate(config.sensitivity)
            // Últimos ~2,4 s de audio, sólo en memoria.
            val window = ArrayDeque<ByteArray>()
            var detected = false
            var restart = false
            var chunks = 0
            try {
                val outcome = microphone.capture(echoCancellation) { buffer, length ->
                    window.addLast(buffer.copyOf(length))
                    while (window.size > WINDOW_CHUNKS) window.removeFirst()
                    val candidate = if (recognizer.acceptWaveForm(buffer, length)) {
                        gate.onFinal(parseWords(recognizer.result))
                    } else {
                        gate.onPartial(JSONObject(recognizer.partialResult).optString("partial").split(' '))
                    }
                    if (candidate) {
                        if (verify(model, window, echoCancellation, config.sensitivity)) {
                            detected = true
                        } else {
                            gate.rejected()
                            logger.d(TAG, "Candidato rechazado en la verificación")
                        }
                    }
                    chunks++
                    if (!detected && chunks % MUSIC_CHECK_CHUNKS == 0 && microphone.echoCancellationWanted() != echoCancellation) {
                        restart = true
                    }
                    !detected && !restart
                }
                if (detected) return WakeWordResult.Detected(null)
                if (restart) continue
                return when (outcome) {
                    is CaptureOutcome.Failed -> WakeWordResult.Failed(outcome.error)
                    is CaptureOutcome.Completed -> WakeWordResult.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono se cerró."))
                }
            } finally {
                recognizer.close()
            }
        }
    }

    /**
     * Segunda etapa sobre la ventana de audio: transcripción libre (estricta). Con música, como la
     * transcripción libre se confunde, también vale ganarle a palabras parecidas («para», «cara»…).
     */
    private fun verify(model: Model, window: ArrayDeque<ByteArray>, musicPlaying: Boolean, sensitivity: Float): Boolean {
        val audio = ByteArrayOutputStream(window.sumOf { it.size }).apply { window.forEach { write(it) } }.toByteArray()
        if (WakeWordTuning.verifiedByTranscript(transcribe(model, audio, null))) return true
        if (!musicPlaying || sensitivity < 0.3f) return false
        return "sara" in transcribe(model, audio, COMPETITORS).split(' ')
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
    private fun transcribe(model: Model, audio: ByteArray, grammar: String?): String {
        val free = if (grammar == null) Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat())
        else Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat(), grammar)
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
        const val WINDOW_CHUNKS = 24 // 2,4 s
        const val MUSIC_CHECK_CHUNKS = 20 // cada 2 s
        val COMPETITORS: String = WakeWordTuning.COMPETITOR_GRAMMAR.joinToString(",", "[", "]") { "\"$it\"" }
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
        var lastChange = start
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
                        lastChange = System.currentTimeMillis()
                        onPartial(partial)
                    }
                }
                when {
                    !heardSpeech && elapsed > request.noSpeechTimeoutMillis -> false
                    // Con música de fondo nunca hay silencio: si la transcripción dejó de cambiar, la orden terminó.
                    heardSpeech && System.currentTimeMillis() - lastChange > STABLE_PARTIAL_MS -> {
                        text = JSONObject(recognizer.finalResult).optString("text").trim()
                        false
                    }
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

    private companion object {
        const val STABLE_PARTIAL_MS = 1_100L
    }
}
