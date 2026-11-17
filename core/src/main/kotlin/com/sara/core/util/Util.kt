package com.sara.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.sqrt

interface SaraLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)

    object None : SaraLogger {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, error: Throwable?) = Unit
    }
}

/** Nivel de audio normalizado (0..1) que alimenta el indicador visual de voz. */
class AudioLevelMeter {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun update(value: Float) {
        _level.value = value.coerceIn(0f, 1f)
    }

    fun reset() = update(0f)

    companion object {
        /** RMS de PCM 16 bits little-endian, mapeado a 0..1 en escala logarítmica (-60 dBFS .. 0 dBFS). */
        fun levelOfPcm16(buffer: ByteArray, length: Int): Float {
            val samples = length / 2
            if (samples == 0) return 0f
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                sum += s.toDouble() * s
                i += 2
            }
            val rms = sqrt(sum / samples)
            if (rms < 1.0) return 0f
            val db = 20 * log10(rms / Short.MAX_VALUE)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }

        /** RMS lineal en unidades de muestra (útil para detección de voz). */
        fun rmsOfPcm16(buffer: ByteArray, length: Int): Double {
            val samples = length / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                sum += s.toDouble() * s
                i += 2
            }
            return sqrt(sum / samples)
        }
    }
}

/** Ajuste de la palabra de activación: traduce sensibilidad (0..1) en confianza mínima exigida. */
object WakeWordTuning {
    const val DEFAULT_SENSITIVITY = 0.5f

    fun minimumConfidence(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(0f, 1f)
        return 0.95f - 0.5f * s
    }

    /**
     * Decide si un resultado de reconocimiento restringido a la gramática contiene la palabra de activación.
     * Para reducir falsas activaciones rechaza menciones dentro de frases largas ("le dije a Sara que...").
     */
    fun accept(words: List<Pair<String, Float>>, sensitivity: Float, keyword: String = "sara"): Float? {
        val index = words.indexOfFirst { it.first == keyword }
        if (index < 0) return null
        val confidence = words[index].second
        if (confidence < minimumConfidence(sensitivity)) return null
        if (words.size > 4 && index > 1) return null
        return confidence
    }

    /** Variantes con las que un reconocedor libre transcribe «Sara». */
    val KEYWORD_ALIASES = setOf("sara", "sarah", "zara", "zarah")

    /**
     * Segunda etapa: la ventana de audio (≈2,4 s) se vuelve a transcribir con vocabulario libre y sólo
     * se acepta si aparece «Sara». Elimina falsos positivos de la gramática restringida
     * (por ejemplo «subí el volumen» o «para la próxima»).
     */
    fun verifiedByTranscript(transcript: String): Boolean =
        transcript.lowercase().split(' ').any { it in KEYWORD_ALIASES }

    /**
     * Con música de fondo la transcripción libre falla seguido; entonces se usa además una gramática
     * con palabras parecidas que compiten con «Sara»: sólo gana si realmente se pronunció.
     */
    val COMPETITOR_GRAMMAR = listOf(
        "sara", "para", "paraba", "parar", "separar", "separa", "cara", "clara", "sala", "casa", "era", "ahora",
        "zapa", "sabe", "saca", "saga", "salta", "santa", "sana", "tara", "vara", "rara", "sarta", "farra",
        "jarra", "barra", "guitarra", "[unk]",
    )

    /** Cantidad de resultados parciales seguidos con «sara» para pasar a verificación. */
    fun requiredPartialHits(sensitivity: Float): Int = when {
        sensitivity >= 0.4f -> 1
        sensitivity >= 0.2f -> 2
        else -> 3
    }

    /**
     * Ganancia digital para voz baja o lejana: lleva el pico de la ventana a ~[target] sin pasar
     * de [maxGain] (para no inflar el ruido de una habitación en silencio).
     */
    fun gainFor(peak: Int, target: Int = 12_000, maxGain: Float = 6f): Float =
        if (peak <= 0) 1f else (target.toFloat() / peak).coerceIn(1f, maxGain)

    /** Aplica [gain] a PCM de 16 bits little-endian, recortando sin desbordar. */
    fun applyGain(pcm: ByteArray, length: Int, gain: Float): ByteArray {
        val out = pcm.copyOf(length)
        if (gain == 1f) return out
        var i = 0
        while (i + 1 < length) {
            val sample = (out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8)
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            out[i] = scaled.toByte()
            out[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
        return out
    }

    fun peakOf(pcm: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val abs = kotlin.math.abs(sample.toShort().toInt())
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }
}

/**
 * Compuerta de energía para ahorrar batería: en silencio el reconocedor no procesa audio.
 * Cuando vuelve a haber sonido se le entregan los bloques previos para no perder el comienzo.
 */
class EnergyGate(private val idleAfterChunks: Int = 15, private val minRms: Double = 45.0, private val ratio: Double = 1.7) {
    private var noiseFloor = -1.0
    private var quietChunks = 0
    var idle = false
        private set

    /** Devuelve `true` si este bloque debe procesarse; [wokeUp] indica que conviene entregar el pre-roll. */
    fun feed(rms: Double): Decision {
        noiseFloor = when {
            noiseFloor < 0 -> rms
            rms < noiseFloor -> rms
            else -> noiseFloor * 0.995 + rms * 0.005
        }
        // Umbral bajo: una voz suave o lejos del teléfono también tiene que despertar al detector.
        val loud = rms > maxOf(noiseFloor * ratio, minRms)
        if (loud) {
            quietChunks = 0
            if (idle) {
                idle = false
                return Decision(process = true, wokeUp = true)
            }
            return Decision(process = true, wokeUp = false)
        }
        quietChunks++
        if (quietChunks > idleAfterChunks) idle = true
        return Decision(process = !idle, wokeUp = false)
    }

    data class Decision(val process: Boolean, val wokeUp: Boolean)
}

/**
 * Primera etapa del detector, sin esperar a que termine la frase: reacciona a los resultados
 * parciales (unos 100-300 ms después de decir «Sara»), lo que además funciona con música de fondo,
 * donde nunca hay silencio para cerrar una frase.
 */
class WakeWordGate(private val sensitivity: Float) {
    private val requiredHits = WakeWordTuning.requiredPartialHits(sensitivity)
    private var hits = 0
    private var cooldown = 0

    fun onFinal(words: List<Pair<String, Float>>): Boolean {
        hits = 0
        if (cooldown > 0) {
            cooldown--
            return false
        }
        return WakeWordTuning.accept(words, sensitivity) != null
    }

    fun onPartial(tokens: List<String>): Boolean {
        if (cooldown > 0) {
            cooldown--
            hits = 0
            return false
        }
        hits = if ("sara" in tokens) hits + 1 else 0
        return hits >= requiredHits
    }

    /** El candidato no pasó la verificación: se ignoran unos bloques para no reverificar lo mismo. */
    fun rejected() {
        hits = 0
        cooldown = COOLDOWN_CHUNKS
    }

    private companion object {
        const val COOLDOWN_CHUNKS = 5
    }
}
