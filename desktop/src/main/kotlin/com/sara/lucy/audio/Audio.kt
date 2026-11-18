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

/** Fuente de audio PCM 16 bits mono 16 kHz en bloques de 100 ms (el micrófono, o un archivo en pruebas). */
interface AudioSource {
    suspend fun capture(onChunk: (ByteArray, Int) -> Boolean): CaptureOutcome
}

/**
 * Micrófono: PCM 16 bits mono a 16 kHz en bloques de 100 ms. Se abre sólo durante [capture] y se
 * libera siempre al terminar o cancelar; nunca se guarda audio. Si la placa no da 16 kHz, graba a
 * 48 kHz y convierte.
 */
class Microphone(
    private val levels: AudioLevelMeter,
    /** Micrófono elegido en Ajustes (nombre); vacío = el predeterminado de Windows. */
    private val preferred: () -> String = { "" },
) : AudioSource {
    private val busy = AtomicBoolean(false)

    /** `true` mientras Lucy tiene el micrófono abierto. */
    val active: Boolean get() = busy.get()

    override suspend fun capture(onChunk: (ByteArray, Int) -> Boolean): CaptureOutcome = withContext(Dispatchers.IO) {
        val (line, rate) = open() ?: return@withContext CaptureOutcome.Failed(
            EngineError(EngineErrorType.AUDIO_INPUT, "No encontré un micrófono. Revisá que esté conectado."),
        )
        busy.set(true)
        history = DoubleArray(0)
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

    /** Nombre del micrófono que se usa (para el diagnóstico). */
    fun describe(): String = preferred().trim().ifEmpty { automatic() ?: "micrófono predeterminado" }

    private fun open(): Pair<TargetDataLine, Int>? {
        // Automático: el predeterminado, salvo que haya un parlante/auricular Bluetooth (su micrófono
        // pone todo el audio en «modo llamada»): entonces el primero que no sea Bluetooth.
        val wanted = preferred().trim().ifEmpty { automatic().orEmpty() }
        val mixer = if (wanted.isEmpty()) null else AudioSystem.getMixerInfo().firstOrNull { it.name == wanted }?.let { AudioSystem.getMixer(it) }
        for (rate in listOf(SAMPLE_RATE, 48_000)) {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (if (mixer != null) !mixer.isLineSupported(info) else !AudioSystem.isLineSupported(info)) continue
            try {
                val line = (if (mixer != null) mixer.getLine(info) else AudioSystem.getLine(info)) as TargetDataLine
                line.open(format, rate / 10 * 2 * 4)
                return line to rate
            } catch (_: LineUnavailableException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return null
    }

    /** Muestras anteriores del bloque previo (para que el filtro no corte entre bloques). */
    private var history = DoubleArray(0)

    /**
     * 48 kHz → 16 kHz con un filtro pasa bajos de verdad (sinc con ventana, corte en 7 kHz): sin él,
     * los agudos se «doblan» sobre la voz y Vosk entiende cualquier cosa.
     */
    internal fun downsample(src: ByteArray, length: Int, factor: Int, out: ByteArray): ByteArray {
        val n = length / 2
        val taps = FILTER.size
        if (history.size != taps - 1) history = DoubleArray(taps - 1)
        val x = DoubleArray(taps - 1 + n)
        System.arraycopy(history, 0, x, 0, taps - 1)
        for (i in 0 until n) x[taps - 1 + i] = ((src[i * 2 + 1].toInt() shl 8) or (src[i * 2].toInt() and 0xFF)).toShort().toDouble()
        System.arraycopy(x, n, history, 0, taps - 1)
        val samples = n / factor
        for (i in 0 until samples) {
            var acc = 0.0
            val end = i * factor + taps - 1
            for (k in 0 until taps) acc += FILTER[k] * x[end - k]
            val s = acc.toInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        /** Micrófonos conectados (para elegir en Ajustes). */
        fun available(): List<String> = AudioSystem.getMixerInfo().filter { info ->
            !info.name.startsWith("Port ") && !info.name.contains("Primary Sound Capture", ignoreCase = true) &&
                runCatching { AudioSystem.getMixer(info).targetLineInfo.any { it.lineClass == TargetDataLine::class.java } }.getOrDefault(false)
        }.map { it.name }.distinct()

        /** Nombres típicos del micrófono de un parlante o auricular Bluetooth («manos libres»). */
        fun looksBluetooth(name: String): Boolean {
            val n = name.lowercase()
            return "hands-free" in n || "handsfree" in n || "manos libres" in n || "bluetooth" in n || "ag audio" in n
        }

        /** Si hay micrófonos Bluetooth y otro común, el común; si no, null (= el predeterminado). */
        fun automatic(): String? {
            val all = available()
            if (all.none(::looksBluetooth)) return null
            return all.firstOrNull { !looksBluetooth(it) }
        }

        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2

        /** Pasa bajos de 63 coeficientes (ventana de Hamming) para 48 kHz con corte en 7 kHz. */
        private val FILTER: DoubleArray = run {
            val taps = 63
            val fc = 7_000.0 / 48_000.0
            val h = DoubleArray(taps) { i ->
                val m = i - (taps - 1) / 2.0
                val sinc = if (m == 0.0) 2 * fc else kotlin.math.sin(2 * PI * fc * m) / (PI * m)
                sinc * (0.54 - 0.46 * kotlin.math.cos(2 * PI * i / (taps - 1)))
            }
            val sum = h.sum()
            DoubleArray(taps) { h[it] / sum }
        }
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

/**
 * Potenciador del micrófono: para hablarle normal desde lejos (o con un micrófono flojo, como el de
 * un parlante). [mode] 0 = automático (sube la voz baja sin subir el ruido de fondo), 100 = normal,
 * 200…800 = fijo (×2…×8). Un limitador suave evita que se sature.
 */
class BoostedSource(private val inner: AudioSource, private val mode: () -> Int) : AudioSource {
    override suspend fun capture(onChunk: (ByteArray, Int) -> Boolean): CaptureOutcome {
        val agc = AutoGain()
        return inner.capture { buf, len ->
            val m = mode()
            val gain = if (m <= 0) agc.next(buf, len) else m / 100f
            if (gain <= 1.01f) onChunk(buf, len) else {
                val out = ByteArray(len)
                boost(buf, len, gain, out)
                onChunk(out, len)
            }
        }
    }

    /** Ganancia automática: lleva los picos de voz a ~40 % sin pasar ×6 ni subir el ruido de fondo de ~1 %. */
    internal class AutoGain {
        private var noise = 150.0
        private var peak = 0.0
        fun next(pcm: ByteArray, len: Int): Float {
            val n = len / 2
            if (n == 0) return 1f
            var sum = 0.0
            var max = 0
            for (i in 0 until n) {
                val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
                sum += v.toDouble() * v
                if (kotlin.math.abs(v) > max) max = kotlin.math.abs(v)
            }
            val rms = kotlin.math.sqrt(sum / n)
            // Piso de ruido: baja rápido, sube muy despacio (la voz no lo mueve).
            noise = if (rms < noise) rms.coerceAtLeast(20.0) else noise + (rms - noise) * 0.003
            // Pico de la voz: sube al instante, baja en unos segundos.
            peak = if (max > peak) max.toDouble() else peak * 0.985
            val forVoice = TARGET_PEAK / peak.coerceAtLeast(200.0)
            val forNoise = NOISE_CEILING / noise
            return minOf(MAX_AUTO, forVoice, forNoise).coerceAtLeast(1.0).toFloat()
        }
    }

    companion object {
        private const val TARGET_PEAK = 13_000.0
        private const val NOISE_CEILING = 330.0
        private const val MAX_AUTO = 6.0

        /** Sube el volumen con un limitador suave (por encima de ~70 % comprime en vez de recortar). */
        fun boost(src: ByteArray, len: Int, gain: Float, out: ByteArray) {
            val knee = 0.7f
            for (i in 0 until len / 2) {
                val v = ((src[2 * i + 1].toInt() shl 8) or (src[2 * i].toInt() and 0xFF)).toShort() / 32768f * gain
                val a = kotlin.math.abs(v)
                val limited = if (a <= knee) v else kotlin.math.sign(v) * (knee + (1 - knee) * kotlin.math.tanh((a - knee) / (1 - knee)))
                val s = (limited * 32767f).toInt().coerceIn(-32768, 32767)
                out[2 * i] = (s and 0xFF).toByte()
                out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
            }
        }
    }
}
