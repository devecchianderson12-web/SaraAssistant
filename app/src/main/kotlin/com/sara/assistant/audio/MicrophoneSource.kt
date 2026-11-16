package com.sara.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
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
class MicrophoneSource(private val context: Context, private val levels: AudioLevelMeter) {
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
    suspend fun capture(onChunk: (ByteArray, Int) -> Boolean): CaptureOutcome = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.PERMISSION, "Sara no tiene permiso para usar el micrófono."))
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
                levels.update(AudioLevelMeter.levelOfPcm16(buffer, read))
                if (!onChunk(buffer, read)) break
            }
            CaptureOutcome.Completed(allSilent)
        } catch (e: IllegalStateException) {
            CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono dejó de responder.", e))
        } finally {
            runCatching { record.stop() }
            record.release()
            _active.value = false
            levels.reset()
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
