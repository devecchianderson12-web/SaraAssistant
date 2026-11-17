package com.sara.lucy.audio

import com.sara.core.engine.EngineError
import com.sara.core.engine.EngineErrorType
import com.sara.core.util.AudioLevelMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

sealed interface CaptureOutcome {
    /** [allSilent] = el micrófono sólo entregó ceros (bloqueado en Privacidad de Windows o desconectado). */
    data class Completed(val allSilent: Boolean) : CaptureOutcome
    data class Failed(val error: EngineError) : CaptureOutcome
}

/**
 * Micrófono: PCM 16 bits mono a 16 kHz en bloques de 100 ms. Se abre sólo durante [capture] y se
 * libera siempre al terminar o cancelar; nunca se guarda audio. Si la placa no da 16 kHz, graba a
 * 48 kHz y convierte.
 */
class Microphone(private val levels: AudioLevelMeter) {
    private val busy = AtomicBoolean(false)

    /** `true` mientras Lucy tiene el micrófono abierto. */
    val active: Boolean get() = busy.get()

    suspend fun capture(onChunk: (ByteArray, Int) -> Boolean): CaptureOutcome = withContext(Dispatchers.IO) {
        val (line, rate) = open() ?: return@withContext CaptureOutcome.Failed(
            EngineError(EngineErrorType.AUDIO_INPUT, "No encontré un micrófono. Revisá que esté conectado."),
        )
        busy.set(true)
        var allSilent = true
        try {
            line.start()
            val factor = rate / SAMPLE_RATE
            val raw = ByteArray(CHUNK_BYTES * factor)
            val chunk = ByteArray(CHUNK_BYTES)
            while (isActive) {
                var read = 0
                while (read < raw.size && isActive) {
                    val n = line.read(raw, read, raw.size - read)
                    if (n < 0) return@withContext CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono dejó de responder."))
                    read += n
                }
                if (!isActive) break
                val data = if (factor == 1) raw else downsample(raw, read, factor, chunk)
                val length = if (factor == 1) read else read / factor
                if (allSilent && data.take(length).any { it.toInt() != 0 }) allSilent = false
                levels.update(AudioLevelMeter.levelOfPcm16(data, length))
                if (!onChunk(data, length)) break
            }
            CaptureOutcome.Completed(allSilent)
        } catch (e: Exception) {
            CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono dejó de responder.", e))
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
            busy.set(false)
            levels.reset()
        }
    }

    private fun open(): Pair<TargetDataLine, Int>? {
        for (rate in listOf(SAMPLE_RATE, 48_000)) {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) continue
            try {
                val line = AudioSystem.getLine(info) as TargetDataLine
                line.open(format, rate / 10 * 2 * 4)
                return line to rate
            } catch (_: LineUnavailableException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return null
    }

    /** 48 kHz → 16 kHz promediando (filtro pasa bajos simple). */
    private fun downsample(src: ByteArray, length: Int, factor: Int, out: ByteArray): ByteArray {
        val samples = length / 2 / factor
        for (i in 0 until samples) {
            var sum = 0
            for (k in 0 until factor) {
                val j = (i * factor + k) * 2
                sum += ((src[j + 1].toInt() shl 8) or (src[j].toInt() and 0xFF)).toShort().toInt()
            }
            val s = sum / factor
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2
    }
}

/** Sonido corto de «te escucho» (generado, sin archivos). */
object Chime {
    fun play() {
        runCatching {
            val rate = 44_100f
            val format = AudioFormat(rate, 16, 1, true, false)
            val samples = (rate * 0.22).toInt()
            val data = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val t = i / rate
                val freq = if (t < 0.09) 880.0 else 1320.0
                val env = min(1.0, t / 0.01) * exp(-((t % 0.11) * 18))
                val v = (sin(2 * PI * freq * t) * env * 0.25 * Short.MAX_VALUE).toInt()
                data[i * 2] = (v and 0xFF).toByte()
                data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
            line.open(format)
            line.start()
            line.write(data, 0, data.size)
            line.drain()
            line.close()
        }
    }
}
