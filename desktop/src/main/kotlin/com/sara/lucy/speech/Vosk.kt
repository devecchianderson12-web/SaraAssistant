package com.sara.lucy.speech

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
import com.sara.core.util.AudioLevelMeter
import com.sara.core.util.EnergyGate
import com.sara.core.util.SaraLogger
import com.sara.core.util.WakeWordGate
import com.sara.core.util.WakeWordTuning
import com.sara.lucy.audio.AudioSource
import com.sara.lucy.audio.CaptureOutcome
import com.sara.lucy.audio.Microphone
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File

/** El modelo de voz en español viene dentro de Lucy (carpeta `model` junto al programa). */
class VoskModels(private val logger: SaraLogger) {
    @Volatile private var model: Model? = null
    @Volatile var error: String? = null
        private set

    fun folder(): File? {
        val candidates = buildList {
            System.getProperty("lucy.model")?.let { add(File(it)) }
            System.getProperty("jpackage.app-path")?.let { File(it).parentFile }?.let {
                add(File(it, "model"))
                add(File(it, "app/model"))
            }
            runCatching { File(VoskModels::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()?.let {
                add(File(it, "model"))
                add(File(it.parentFile, "model"))
            }
            add(File("model"))
        }
        return candidates.firstOrNull { File(it, "am").isDirectory || File(it, "conf").isDirectory }
    }

    fun isInstalled() = folder() != null

    @Synchronized fun model(): Model? {
        model?.let { return it }
        val dir = folder() ?: return null
        return runCatching {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath)
        }.onFailure {
            error = it.message
            logger.w(TAG, "No pude cargar el modelo de voz", it)
        }.getOrNull()?.also { model = it }
    }

    private companion object { const val TAG = "VoskModels" }
}

private const val MISSING_MODEL = "Falta el modelo de voz. Volvé a descargar Lucy."

private fun microphoneError() = EngineError(
    EngineErrorType.AUDIO_INPUT,
    "El micrófono no recibe audio. Revisá en Configuración de Windows › Privacidad › Micrófono que las apps de escritorio puedan usarlo.",
)

/**
 * Palabra de activación con Vosk, en dos etapas y 100 % local (la misma lógica que en el teléfono):
 * 1. Reconocimiento continuo con gramática restringida al nombre y «[unk]», reaccionando a los
 *    resultados parciales (sin esperar silencio): responde rápido y funciona con música.
 * 2. Ante un candidato, los últimos 2,4 s se transcriben con vocabulario libre y sólo se acepta si
 *    aparece el nombre; con sensibilidad media/alta también vale ganarle a palabras parecidas.
 */
class VoskWakeWordEngine(
    private val models: VoskModels,
    private val microphone: AudioSource,
    private val logger: SaraLogger,
) : WakeWordEngine {
    override val id = "vosk-wake"
    override val displayName = "Vosk (local)"

    override suspend fun availability(): Availability =
        if (!models.isInstalled()) Availability.Unavailable(UnavailableReason.MISSING_MODEL, MISSING_MODEL) else Availability.Available

    override suspend fun awaitWakeWord(config: WakeWordConfig): WakeWordResult {
        val model = models.model() ?: return WakeWordResult.Failed(EngineError(EngineErrorType.NOT_AVAILABLE, models.error ?: MISSING_MODEL))
        val recognizer = Recognizer(model, Microphone.SAMPLE_RATE.toFloat(), if (config.requirePrefix) prefixedGrammar() else grammar()).apply { setWords(true) }
        val gate = WakeWordGate(config.sensitivity)
        val energy = EnergyGate()
        val window = ArrayDeque<ByteArray>()
        val peaks = ArrayDeque<Int>()
        var detected = false
        try {
            val outcome = microphone.capture { buffer, length ->
                val raw = buffer.copyOf(length)
                window.addLast(raw)
                peaks.addLast(WakeWordTuning.peakOf(raw, length))
                while (window.size > WINDOW_CHUNKS) window.removeFirst()
                while (peaks.size > WINDOW_CHUNKS) peaks.removeFirst()
                // Voz baja o lejana: se amplifica según el pico de los últimos 2,4 s.
                val gain = WakeWordTuning.gainFor(peaks.max())
                val decision = energy.feed(AudioLevelMeter.rmsOfPcm16(buffer, length))
                if (!decision.process) return@capture true
                if (decision.wokeUp) {
                    // Pre-roll: los bloques anteriores para no perder el comienzo del nombre.
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
                    if (verify(model, window, config.sensitivity, config.requirePrefix)) {
                        detected = true
                    } else {
                        gate.rejected()
                        logger.d(TAG, "Candidato rechazado en la verificación")
                    }
                }
                !detected
            }
            if (detected) {
                logger.d(TAG, "Activación")
                return WakeWordResult.Detected(null)
            }
            return when (outcome) {
                is CaptureOutcome.Failed -> WakeWordResult.Failed(outcome.error)
                is CaptureOutcome.Completed -> WakeWordResult.Failed(
                    if (outcome.allSilent) microphoneError() else EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono se cerró."),
                )
            }
        } finally {
            recognizer.close()
        }
    }

    /** Segunda etapa sobre la ventana de audio (ver la explicación de la clase). */
    internal fun verify(model: Model, window: Collection<ByteArray>, sensitivity: Float, requirePrefix: Boolean): Boolean {
        val raw = ByteArrayOutputStream(window.sumOf { it.size }).apply { window.forEach { write(it) } }.toByteArray()
        val audio = WakeWordTuning.applyGain(raw, raw.size, WakeWordTuning.gainFor(WakeWordTuning.peakOf(raw, raw.size)))
        val free = transcribe(model, audio, null)
        if (requirePrefix) {
            if (WakeWordTuning.verifiedWithPrefix(free)) return true
            if (sensitivity < 0.3f) return false
            return WakeWordTuning.verifiedWithPrefix(transcribe(model, audio, list(WakeWordTuning.PREFIXED_PHRASES + WakeWordTuning.COMPETITOR_GRAMMAR)))
        }
        // El nombre tiene que estar al final de lo escuchado: el detector reacciona apenas se dice.
        if (nearEnd(free)) return true.also { logger.d(TAG, "Verificado: «$free»") }
        if (sensitivity < 0.3f) return false
        // Charla o canción seguida (muchas palabras y ninguna es el nombre): no es un llamado.
        if (free.split(' ').count { it.isNotBlank() && it != "[unk]" } >= MAX_FREE_WORDS) {
            logger.d(TAG, "Libre: «$free» · parece charla, no un llamado")
            return false
        }
        val contest = transcribe(model, audio, list(WakeWordTuning.COMPETITOR_GRAMMAR))
        logger.d(TAG, "Libre: «$free» · contra parecidas: «$contest»")
        return nearEnd(contest)
    }

    private fun nearEnd(text: String): Boolean =
        text.split(' ').filter { it.isNotBlank() && it != "[unk]" }.takeLast(2).any { it in WakeWordTuning.KEYWORD_ALIASES }

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

    private fun transcribe(model: Model, audio: ByteArray, grammar: String?): String {
        val free = if (grammar == null) Recognizer(model, Microphone.SAMPLE_RATE.toFloat()) else Recognizer(model, Microphone.SAMPLE_RATE.toFloat(), grammar)
        return try {
            free.acceptWaveForm(audio, audio.size)
            JSONObject(free.finalResult).optString("text")
        } finally {
            free.close()
        }
    }

    private companion object {
        const val TAG = "VoskWake"
        const val WINDOW_CHUNKS = 24 // 2,4 s
        const val PREROLL_CHUNKS = 5
        const val MAX_FREE_WORDS = 5
        fun list(words: List<String>) = words.joinToString(",", "[", "]") { "\"$it\"" }
        fun grammar() = list(listOf(WakeWordTuning.profile.keyword, "[unk]"))
        fun prefixedGrammar() = list(WakeWordTuning.PREFIXED_PHRASES + listOf(WakeWordTuning.profile.keyword, "[unk]"))
    }
}

/**
 * Reconocimiento de órdenes con Vosk (vocabulario libre en español). Si hay clave de Google y
 * conexión, el audio de la orden se manda además a Gemini para una transcripción más precisa; si
 * Google tarda o falla, queda lo que entendió Vosk.
 */
class VoskSpeechRecognitionEngine(
    private val models: VoskModels,
    private val microphone: AudioSource,
    private val refine: (suspend (ByteArray, String) -> String?)? = null,
) : SpeechRecognitionEngine {
    override val id = "vosk-offline"
    override val displayName = "Vosk"
    override val isOnline = false

    override suspend fun availability(): Availability =
        if (!models.isInstalled()) Availability.Unavailable(UnavailableReason.MISSING_MODEL, MISSING_MODEL) else Availability.Available

    override suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult {
        val model = models.model() ?: return RecognitionResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, models.error ?: MISSING_MODEL), id)
        val recognizer = Recognizer(model, Microphone.SAMPLE_RATE.toFloat())
        val start = System.currentTimeMillis()
        val audio = ByteArrayOutputStream()
        var text: String? = null
        var heardSpeech = false
        var lastPartial = ""
        var lastChange = start
        val gain = RunningGain()
        try {
            var settle = SETTLE_CHUNKS
            val outcome = microphone.capture { buffer, length ->
                val elapsed = System.currentTimeMillis() - start
                // Un instante de espera: el eco del «¿Sí?» de Lucy y el final de «Lucy» no son la orden.
                if (settle > 0) {
                    settle--
                    return@capture true
                }
                if (refine != null && audio.size() < MAX_AUDIO_BYTES) audio.write(buffer, 0, length)
                // Micrófonos bajos (muy común en notebooks): se lleva la voz a un nivel que Vosk entienda.
                val chunk = WakeWordTuning.applyGain(buffer, length, gain.next(buffer, length))
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    val t = JSONObject(recognizer.result).optString("text").trim()
                    if (t.isNotEmpty() && !onlyEcho(t)) {
                        text = t
                        return@capture false
                    }
                    // Era sólo el nombre o el eco del saludo: se sigue esperando la orden.
                    if (t.isNotEmpty()) {
                        heardSpeech = false
                        lastPartial = ""
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
                !recognized.isNullOrBlank() && !onlyEcho(recognized) -> {
                    val better = refine?.let { r -> runCatching { r(audio.toByteArray(), recognized) }.getOrNull() }?.trim()?.ifBlank { null }
                    RecognitionResult.Success(better ?: recognized, null, if (better != null) "$id+gemini" else id)
                }
                outcome is CaptureOutcome.Failed -> RecognitionResult.Failure(outcome.error, id)
                outcome is CaptureOutcome.Completed && outcome.allSilent -> RecognitionResult.Failure(microphoneError(), id)
                else -> RecognitionResult.Failure(EngineError(EngineErrorType.NO_SPEECH, "No se detectó voz."), id)
            }
        } finally {
            recognizer.close()
        }
    }

    /** Ganancia según el pico más alto de la orden hasta ahora (no sube y baja a cada rato). */
    private class RunningGain {
        private var peak = 2_500
        fun next(pcm: ByteArray, length: Int): Float {
            peak = maxOf(peak, WakeWordTuning.peakOf(pcm, length))
            return WakeWordTuning.gainFor(peak, maxGain = 4f)
        }
    }

    private companion object {
        const val STABLE_MARGIN_MS = 300L
        const val SETTLE_CHUNKS = 3 // 0,3 s
        /** Lo que no es una orden: el nombre o el eco del saludo de Lucy. */
        val ECHO_WORDS = setOf("si", "sí", "decime", "dime", "luci", "lucy", "lusi", "lucie", "luchi", "luz", "y", "[unk]")
        fun onlyEcho(text: String) = text.lowercase().split(' ').filter { it.isNotBlank() }.all { it in ECHO_WORDS }
        const val MAX_AUDIO_BYTES = 16_000 * 2 * 25
    }
}
