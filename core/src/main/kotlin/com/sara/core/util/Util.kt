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
     * Segunda etapa: el fragmento candidato se vuelve a transcribir con vocabulario libre y sólo se
     * acepta si «Sara» aparece entre las primeras palabras. Elimina falsos positivos de la gramática
     * restringida (por ejemplo «subí el volumen» o «para la próxima»).
     */
    fun verifiedByTranscript(transcript: String): Boolean =
        transcript.lowercase().split(' ').filter { it.isNotBlank() }.take(3).any { it in KEYWORD_ALIASES }
}
