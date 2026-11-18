package com.sara.lucy.audio

import com.sara.core.engine.EngineError
import com.sara.core.engine.EngineErrorType
import com.sara.core.util.AudioLevelMeter
import com.sara.core.util.SaraLogger
import com.sara.lucy.win.WinBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Micrófono con cancelación de eco de Windows (el mismo procesamiento de las videollamadas): a lo
 * que graba le resta lo que suena por los parlantes, así la música de la computadora (y la voz de
 * Lucy) no la despierta ni tapa tu voz. Es lo que en el teléfono hace el modo «comunicación».
 *
 * Un solo proceso graba y reparte el audio a quien lo pida (la espera de «Lucy» y la orden); se
 * cierra si nadie lo usa un rato. Si Windows no lo permite, se usa el micrófono común.
 */
class AecMicrophone(
    private val levels: AudioLevelMeter,
    private val fallback: Microphone,
    private val logger: SaraLogger,
    /** Ajuste del usuario: usar la cancelación de eco. */
    private val enabled: () -> Boolean,
    /** Micrófono elegido (nombre); vacío = automático (nunca el de un parlante Bluetooth si hay otro). */
    private val device: () -> String = { "" },
) : AudioSource {
    @Volatile private var openedFor: String? = null
    private val listeners = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()
    private val lock = Any()
    private var process: Process? = null
    @Volatile private var failures = 0
    @Volatile private var lastUse = 0L

    /** Qué micrófono se está usando (para el diagnóstico). */
    @Volatile var mode: String = "sin usar"
        private set
    /** Nombre del micrófono que abrió la cancelación de eco. */
    @Volatile private var openedDevice: String? = null

    override suspend fun capture(onChunk: (ByteArray, Int) -> Boolean): CaptureOutcome {
        if (!enabled() || failures >= MAX_FAILURES) {
            mode = "${fallback.describe()} (sin cancelación de eco)"
            return fallback.capture(onChunk)
        }
        val queue = LinkedBlockingQueue<ByteArray>()
        if (!start()) {
            mode = "micrófono común"
            return fallback.capture(onChunk)
        }
        listeners += queue
        var allSilent = true
        var got = false
        try {
            return withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                while (isActive) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (process?.isAlive != true || (!got && System.currentTimeMillis() - started > FIRST_AUDIO_MS)) {
                            failures++
                            logger.w(TAG, "La cancelación de eco no entrega audio; uso el micrófono común")
                            stop()
                            mode = "micrófono común"
                            listeners -= queue
                            return@withContext fallback.capture(onChunk)
                        }
                        continue
                    }
                    got = true
                    failures = 0
                    mode = "${openedDevice ?: "predeterminado"} con cancelación de eco"
                    if (allSilent && chunk.any { it.toInt() != 0 }) allSilent = false
                    levels.update(AudioLevelMeter.levelOfPcm16(chunk, chunk.size))
                    if (!onChunk(chunk, chunk.size)) break
                }
                CaptureOutcome.Completed(allSilent)
            }
        } catch (e: Exception) {
            return CaptureOutcome.Failed(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono dejó de responder.", e))
        } finally {
            listeners -= queue
            lastUse = System.currentTimeMillis()
            levels.reset()
        }
    }

    private fun start(): Boolean = synchronized(lock) {
        val wanted = device().trim()
        // Cambió el micrófono elegido: se reabre con el nuevo.
        if (process?.isAlive == true && openedFor != wanted) stop()
        if (process?.isAlive == true) return true
        openedFor = wanted
        return runCatching {
            val args = mutableListOf(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", WinBridge.scriptFile().absolutePath, "-Mode", "mic",
            )
            if (wanted.isNotEmpty()) args += listOf("-Device", wanted)
            val p = ProcessBuilder(args).start()
            process = p
            thread(isDaemon = true, name = "lucy-aec-err") {
                runCatching { p.errorStream.bufferedReader().forEachLine { logger.w(TAG, "Cancelación de eco: $it") } }
            }
            thread(isDaemon = true, name = "lucy-aec") { pump(p, p.inputStream) }
            logger.d(TAG, "Micrófono con cancelación de eco iniciado")
            true
        }.getOrElse {
            logger.w(TAG, "No pude iniciar la cancelación de eco", it)
            failures++
            false
        }
    }

    /** Lee el audio del proceso y lo reparte en bloques de 100 ms. */
    private fun pump(p: Process, input: InputStream) {
        runCatching {
            // Primero la línea «READY <micrófono>».
            val header = java.io.ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return
                if (c == '\n'.code) break
                header.write(c)
            }
            openedDevice = header.toString(Charsets.UTF_8).removePrefix("READY").trim().ifEmpty { null }
            logger.d(TAG, "Grabando con: ${openedDevice ?: "micrófono predeterminado"}")
            val buf = ByteArray(Microphone.CHUNK_BYTES)
            while (p.isAlive) {
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) return
                    read += n
                }
                val chunk = buf.copyOf()
                listeners.forEach { it.offer(chunk) }
                // Nadie escucha hace rato: se apaga (vuelve a abrirse al llamarla o al activar la espera).
                if (listeners.isEmpty() && System.currentTimeMillis() - lastUse > IDLE_MS) {
                    stop()
                    return
                }
            }
        }
    }

    fun stop() = synchronized(lock) {
        process?.let { p -> runCatching { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() } }
        process = null
    }

    private companion object {
        const val TAG = "AecMic"
        const val FIRST_AUDIO_MS = 6_000L
        const val IDLE_MS = 15_000L
        const val MAX_FAILURES = 3
    }
}
