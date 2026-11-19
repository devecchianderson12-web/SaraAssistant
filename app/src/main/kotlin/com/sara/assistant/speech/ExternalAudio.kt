package com.sara.assistant.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import com.sara.assistant.audio.MicrophoneSource
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Le pasa al reconocedor de Google el audio grabado por Sara (ya reforzado) en lugar de que él abra
 * el micrófono: así el refuerzo de la voz también vale para las órdenes. Android 13+ (EXTRA_AUDIO_SOURCE).
 * Si el reconocedor no lo acepta (falla apenas empieza), se deja de usar y graba él como siempre.
 */
class ExternalAudio(
    context: Context,
    private val microphone: MicrophoneSource,
    private val logger: SaraLogger,
    private val wanted: () -> Boolean,
) {
    private val prefs = context.getSharedPreferences("sara_audio_externo", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** El reconocedor ya demostró que no acepta audio de afuera. */
    var unsupported: Boolean
        get() = prefs.getBoolean("no_soportado", false)
        set(value) { prefs.edit().putBoolean("no_soportado", value).apply() }

    /** Pico de la voz en la última orden (para saber si hablaste). */
    @Volatile var voicePeak = 0
        private set

    /** El reconocedor no entendió nada aunque se te escuchó: a la segunda vez se deja de usar. */
    fun strike() {
        val n = prefs.getInt("fallas", 0) + 1
        prefs.edit().putInt("fallas", n).apply()
        if (n >= 2) {
            unsupported = true
            logger.w(TAG, "El reconocedor parece ignorar el audio de Sara: graba él")
        }
    }

    fun worked() {
        if (prefs.getInt("fallas", 0) != 0) prefs.edit().putInt("fallas", 0).apply()
    }

    fun shouldUse(): Boolean = Build.VERSION.SDK_INT >= 33 && !unsupported && wanted()

    /** Empieza a grabar y agrega el audio al pedido; devuelve la grabación para cortarla al terminar. */
    fun attach(intent: Intent): Job? {
        if (Build.VERSION.SDK_INT < 33) return null
        val (read, write) = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()?.let { it[0] to it[1] } ?: return null
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicrophoneSource.SAMPLE_RATE)
        logger.d(TAG, "Orden con la voz reforzada (audio de Sara)")
        voicePeak = 0
        return scope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                microphone.capture { buffer, length ->
                    voicePeak = maxOf(voicePeak, com.sara.core.util.WakeWordTuning.peakOf(buffer, length))
                    runCatching { out.write(buffer, 0, length); true }.getOrDefault(false)
                }
            }
            runCatching { read.close() }
        }
    }

    private companion object { const val TAG = "AudioExterno" }
}
