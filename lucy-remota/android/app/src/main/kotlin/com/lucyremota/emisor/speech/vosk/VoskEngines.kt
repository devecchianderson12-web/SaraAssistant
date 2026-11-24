package com.lucyremota.emisor.speech.vosk

import com.lucyremota.emisor.audio.CaptureOutcome
import com.lucyremota.emisor.audio.MicrophoneSource
import com.lucyremota.nucleo.engine.Availability
import com.lucyremota.nucleo.engine.EngineError
import com.lucyremota.nucleo.engine.EngineErrorType
import com.lucyremota.nucleo.engine.RecognitionRequest
import com.lucyremota.nucleo.engine.RecognitionResult
import com.lucyremota.nucleo.engine.SpeechRecognitionEngine
import com.lucyremota.nucleo.engine.UnavailableReason
import com.lucyremota.nucleo.engine.WakeWordConfig
import com.lucyremota.nucleo.engine.WakeWordEngine
import com.lucyremota.nucleo.engine.WakeWordResult
import com.lucyremota.nucleo.util.SaraLogger
import com.lucyremota.nucleo.util.AudioLevelMeter
import com.lucyremota.nucleo.util.EnergyGate
import com.lucyremota.nucleo.util.WakeWordGate
import com.lucyremota.nucleo.util.WakeWordTuning
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

private const val MISSING_MODEL =
    "Falta el modelo de voz en español. Descargalo en Ajustes › Activación (unos 40 MB, una sola vez)."

/**
 * Palabra de activación con Vosk, en dos etapas y 100 % local:
 * 1. Reconocimiento continuo con gramática restringida a ["lucy", "[unk]"]. Reacciona a los
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
        !microphone.hasPermission() -> Availability.Unavailable(UnavailableReason.PERMISSION_DENIED, "Lucy necesita permiso de micrófono.")
        !models.isInstalled() -> Availability.Unavailable(UnavailableReason.MISSING_MODEL, MISSING_MODEL)
        else -> Availability.Available
    }

    override suspend fun awaitWakeWord(config: WakeWordConfig): WakeWordResult {
        val model = models.model()
            ?: return WakeWordResult.Failed(EngineError(EngineErrorType.NOT_AVAILABLE, MISSING_MODEL))
        while (true) {
            // Si empieza o termina la música se reabre el micrófono con la fuente adecuada.
            val echoCancellation = microphone.echoCancellationWanted()
            val recognizer = Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat(), if (config.requirePrefix) PREFIXED_GRAMMAR else GRAMMAR).apply { setWords(true) }
            // Verificador rápido, creado una vez (no por cada candidato): gramática chica de palabras parecidas.
            val quick = Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat(), if (config.requirePrefix) PREFIXED_COMPETITORS else COMPETITORS)
            val gate = WakeWordGate(config.sensitivity)
            val energy = EnergyGate()
            // Últimos ~2,4 s de audio, sólo en memoria.
            val window = ArrayDeque<ByteArray>()
            val peaks = ArrayDeque<Int>()
            var detected = false
            var restart = false
            var chunks = 0
            try {
                val outcome = microphone.capture(echoCancellation) { buffer, length ->
                    val raw = buffer.copyOf(length)
                    window.addLast(raw)
                    peaks.addLast(WakeWordTuning.peakOf(raw, length))
                    while (window.size > WINDOW_CHUNKS) window.removeFirst()
                    while (peaks.size > WINDOW_CHUNKS) peaks.removeFirst()
                    // Voz baja o lejana: se amplifica según el pico de los últimos 2,4 s.
                    val gain = WakeWordTuning.gainFor(peaks.max())
                    val decision = energy.feed(AudioLevelMeter.rmsOfPcm16(buffer, length))
                    if (!decision.process) {
                        chunks++
                        if (chunks % MUSIC_CHECK_CHUNKS == 0 && microphone.echoCancellationWanted() != echoCancellation) restart = true
                        return@capture !restart
                    }
                    if (decision.wokeUp) {
                        // Pre-roll: los bloques anteriores para no perder el comienzo de «Sara».
                        window.toList().dropLast(1).takeLast(PREROLL_CHUNKS).forEach {
                            val boosted = WakeWordTuning.applyGain(it, it.size, gain)
                            recognizer.acceptWaveForm(boosted, boosted.size)
                        }
                    }
                    val chunk = WakeWordTuning.applyGain(raw, length, gain)
                    val candidate = if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        gate.onFinal(parseWords(recognizer.result))
                    } else {
                        gate.onPartial(JSONObject(recognizer.partialResult).optString("partial").split(' '))
                    }
                    if (candidate) {
                        val t0 = System.currentTimeMillis()
                        val ok = verify(model, quick, window, config.sensitivity, config.requirePrefix)
                        logger.d(TAG, "Verificación ${if (ok) "aceptada" else "rechazada"} en ${System.currentTimeMillis() - t0} ms")
                        if (ok) {
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
                quick.close()
            }
        }
    }

    /**
     * Segunda etapa, pensada para que Lucy conteste al toque aun en teléfonos lentos: sólo el último
     * segundo y medio de audio, y primero la gramática chica de palabras parecidas («luz», «lucas»,
     * «luego»…), que se decodifica en milisegundos. Sólo si ahí no gana «Lucy» se prueba la
     * transcripción libre (más lenta, pero rescata los «Lucy» dichos raro).
     */
    private fun verify(model: Model, quick: Recognizer, window: ArrayDeque<ByteArray>, sensitivity: Float, requirePrefix: Boolean): Boolean {
        val recent = window.toList().takeLast(VERIFY_CHUNKS)
        val raw = ByteArrayOutputStream(recent.sumOf { it.size }).apply { recent.forEach { write(it) } }.toByteArray()
        val audio = WakeWordTuning.applyGain(raw, raw.size, WakeWordTuning.gainFor(WakeWordTuning.peakOf(raw, raw.size)))
        val fast = runCatching {
            quick.reset()
            quick.acceptWaveForm(audio, audio.size)
            JSONObject(quick.finalResult).optString("text")
        }.getOrDefault("")
        if (requirePrefix) {
            if (WakeWordTuning.verifiedWithPrefix(fast)) return true
        } else if (WakeWordTuning.profile.keyword in fast.split(' ')) {
            return true
        }
        if (sensitivity < 0.3f) return false
        val free = transcribe(model, audio, null)
        return if (requirePrefix) WakeWordTuning.verifiedWithPrefix(free) else WakeWordTuning.verifiedByTranscript(free)
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
        /** Cómo escribe el modelo en español el nombre («luci» para Lucy). */
        val GRAMMAR: String get() = "[\"${WakeWordTuning.profile.keyword}\", \"[unk]\"]"
        const val WINDOW_CHUNKS = 24 // 2,4 s
        const val PREROLL_CHUNKS = 5
        /** Lo que se vuelve a mirar para confirmar el nombre: el último segundo y medio. */
        const val VERIFY_CHUNKS = 15
        const val MUSIC_CHECK_CHUNKS = 20 // cada 2 s
        val COMPETITORS: String get() = WakeWordTuning.COMPETITOR_GRAMMAR.joinToString(",", "[", "]") { "\"$it\"" }
        /** Primera etapa de «Ey Sara»: el nombre solo o con el llamado delante. */
        val PREFIXED_GRAMMAR: String get() = (WakeWordTuning.PREFIXED_PHRASES + listOf(WakeWordTuning.profile.keyword, "[unk]")).joinToString(",", "[", "]") { "\"$it\"" }
        val PREFIXED_COMPETITORS: String get() = (WakeWordTuning.PREFIXED_PHRASES + WakeWordTuning.COMPETITOR_GRAMMAR).joinToString(",", "[", "]") { "\"$it\"" }
    }
}

/** Reconocimiento offline de órdenes con Vosk (vocabulario libre en español). */
class VoskSpeechRecognitionEngine(
    private val models: VoskModelManager,
    private val microphone: MicrophoneSource,
    /** Ajuste «Subir el volumen de tu voz»: amplifica la voz baja (micrófonos malos o lejanos). */
    private val boostVoice: () -> Boolean = { false },
) : SpeechRecognitionEngine {
    override val id = "vosk-offline"
    override val displayName = "Vosk (offline)"
    override val isOnline = false

    override suspend fun availability(): Availability = when {
        !microphone.hasPermission() -> Availability.Unavailable(UnavailableReason.PERMISSION_DENIED, "Lucy necesita permiso de micrófono.")
        !models.isInstalled() -> Availability.Unavailable(UnavailableReason.MISSING_MODEL, MISSING_MODEL)
        else -> Availability.Available
    }

    override suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult {
        val model = models.model()
            ?: return RecognitionResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, MISSING_MODEL), id)
        val recognizer = Recognizer(model, MicrophoneSource.SAMPLE_RATE.toFloat())
        // Respeta las pausas al hablar: no da la orden por terminada hasta este silencio.
        runCatching {
            recognizer.setEndpointerDelays(
                request.noSpeechTimeoutMillis / 1000f,
                request.endSilenceMillis / 1000f,
                (request.noSpeechTimeoutMillis + request.maxSpeechMillis) / 1000f,
            )
        }
        val start = System.currentTimeMillis()
        var text: String? = null
        var heardSpeech = false
        var lastPartial = ""
        var lastChange = start
        val boost = boostVoice()
        // Pico de la orden hasta ahora (la ganancia no sube y baja a cada rato); arranca en un nivel medio.
        var peak = 2_500
        try {
            val outcome = microphone.capture { buffer, length ->
                val elapsed = System.currentTimeMillis() - start
                val chunk = if (boost) {
                    peak = maxOf(peak, WakeWordTuning.peakOf(buffer, length))
                    WakeWordTuning.applyGain(buffer, length, WakeWordTuning.gainFor(peak, maxGain = 4f))
                } else buffer
                if (recognizer.acceptWaveForm(chunk, if (boost) chunk.size else length)) {
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
                    heardSpeech && System.currentTimeMillis() - lastChange > request.endSilenceMillis + STABLE_MARGIN_MS -> {
                        text = JSONObject(recognizer.finalResult).optString("text").trim()
                        false
                    }
                    elapsed > request.noSpeechTimeoutMillis + request.maxSpeechMillis -> {
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
        const val STABLE_MARGIN_MS = 300L
    }
}
