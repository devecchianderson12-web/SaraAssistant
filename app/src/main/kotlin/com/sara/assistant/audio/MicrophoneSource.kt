package com.sara.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import com.sara.core.engine.EngineError
import com.sara.core.engine.EngineErrorType
import com.sara.core.util.AudioLevelMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

sealed interface CaptureOutcome {
    /** Captura terminada normalmente. [allSilent] = el micrófono sólo entregó ceros (bloqueado o en uso por una llamada). */
    data class Completed(val allSilent: Boolean) : CaptureOutcome
    data class Failed(val error: EngineError) : CaptureOutcome
}

/**
 * Captura PCM 16 bits mono a 16 kHz. El micrófono se abre sólo durante [capture] y se libera
 * siempre al terminar o cancelar; nunca se guarda audio.
 */
class MicrophoneSource(
    private val context: Context,
    private val levels: AudioLevelMeter,
    /** Ajuste del usuario: usar cancelación de eco cuando hay música sonando. */
    private val echoCancellationEnabled: () -> Boolean = { true },
    /** Micrófono a usar (del teléfono o del Bluetooth); null = el que elija Android. */
    private val preferredDevice: suspend () -> android.media.AudioDeviceInfo? = { null },
    /** Refuerzo de la voz (×2 con Bluetooth conectado: en el auto el teléfono queda lejos). */
    private val gain: () -> Float = { 1f },
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Con música sonando por el parlante el micrófono la capta y tapa la voz. En ese caso se graba con
     * la fuente VOICE_COMMUNICATION, que en la mayoría de los teléfonos aplica la cancelación de eco
     * del hardware (resta lo que sale por el parlante), más los efectos AEC/NS si existen.
     */
    fun echoCancellationWanted(): Boolean = echoCancellationEnabled() && audioManager.isMusicActive

    private val _active = MutableStateFlow(false)

    /** `true` mientras Sara tiene el micrófono abierto (indicador de privacidad). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _silenced = MutableStateFlow(false)

    /** `true` si el micrófono entrega sólo silencio digital (interruptor de privacidad o llamada en curso). */
    val silenced: StateFlow<Boolean> = _silenced.asStateFlow()

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Entrega bloques de ~100 ms a [onChunk] hasta que devuelva `false` o se cancele la corrutina. */
    @SuppressLint("MissingPermission")
    suspend fun capture(
        echoCancellation: Boolean = echoCancellationWanted(),
        onChunk: (ByteArray, Int) -> Boolean,
    ): CaptureOutcome = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.PERMISSION, "Sara no tiene permiso para usar el micrófono."))
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                if (echoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_BYTES * 4),
            )
        } catch (e: SecurityException) {
            return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.PERMISSION, "Permiso de micrófono revocado.", e))
        } catch (e: IllegalArgumentException) {
            return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "Formato de audio no soportado.", e))
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "No se pudo abrir el micrófono."))
        }
        preferredDevice()?.let { runCatching { record.setPreferredDevice(it) } }
        val effects = if (echoCancellation) attachEchoEffects(record.audioSessionId) else emptyList()
        var allSilent = true
        var silentChunks = 0
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.BUSY, "El micrófono está en uso por otra app."))
            }
            _active.value = true
            val buffer = ByteArray(CHUNK_BYTES)
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "Error leyendo el micrófono ($read)."))
                }
                if (read == 0) continue
                val zero = isDigitalSilence(buffer, read)
                if (zero) silentChunks++ else {
                    silentChunks = 0
                    allSilent = false
                }
                _silenced.value = silentChunks >= SILENCED_AFTER_CHUNKS
                val g = gain()
                if (g > 1.01f) boost(buffer, read, g)
                levels.update(AudioLevelMeter.levelOfPcm16(buffer, read))
                if (!onChunk(buffer, read)) break
            }
            CaptureOutcome.Completed(allSilent)
        } catch (e: IllegalStateException) {
            CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono dejó de responder.", e))
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            record.release()
            _active.value = false
            levels.reset()
        }
    }

    private fun attachEchoEffects(sessionId: Int): List<AudioEffect> = buildList {
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()?.let(::add)
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()?.let(::add)
        }
    }

    /** Sube el volumen en el lugar, con un limitador suave (comprime en vez de recortar). */
    private fun boost(pcm: ByteArray, length: Int, gain: Float) {
        val knee = 0.7f
        var i = 0
        while (i + 1 < length) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort() / 32768f * gain
            val a = kotlin.math.abs(v)
            val limited = if (a <= knee) v else kotlin.math.sign(v) * (knee + (1 - knee) * kotlin.math.tanh((a - knee) / (1 - knee)))
            val out = (limited * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[i] = out.toByte()
            pcm[i + 1] = (out shr 8).toByte()
            i += 2
        }
    }

    private fun isDigitalSilence(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (buffer[i].toInt() != 0) return false
        return true
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 3_200 // 100 ms
        private const val SILENCED_AFTER_CHUNKS = 30 // 3 s
    }
}
