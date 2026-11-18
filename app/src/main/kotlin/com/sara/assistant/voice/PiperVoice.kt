package com.sara.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.sara.assistant.audio.AudioFocusHelper
import com.sara.assistant.speech.vosk.ModelState
import com.sara.core.engine.Availability
import com.sara.core.engine.SpeakResult
import com.sara.core.engine.VoiceEngine
import com.sara.core.engine.VoiceOption
import com.sara.core.engine.VoiceParams
import com.sara.core.util.AudioLevelMeter
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * La voz «Daniela» (Piper, español de Argentina): libre, gratis y sin Internet. Se descarga sola una
 * vez (unos 115 MB, por Wi-Fi) y después habla desde el teléfono con sherpa-onnx.
 */
class PiperModelManager(private val context: Context, private val logger: SaraLogger) {
    private val dir = File(context.filesDir, "piper")
    private val modelDir = File(dir, MODEL)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelState>(if (isInstalled()) ModelState.Ready else ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun isInstalled() = File(modelDir, "$ONNX").isFile && File(modelDir, "tokens.txt").isFile && File(modelDir, "espeak-ng-data").isDirectory

    fun modelDir(): File = modelDir

    /** Wi-Fi u otra red sin costo: ahí se baja sola. Con datos móviles espera (o el botón de Ajustes). */
    fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInstalled()) return@withLock true.also { _state.value = ModelState.Ready }
            val archive = File(context.cacheDir, "piper-daniela.tar.bz2")
            try {
                _state.value = ModelState.Downloading(0f)
                val conn = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    archive.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = ModelState.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                _state.value = ModelState.Installing
                val tmp = File(dir, "tmp").apply { deleteRecursively(); mkdirs() }
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val target = File(tmp, entry.name).canonicalFile
                        if (!target.path.startsWith(tmp.canonicalPath)) continue
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { tar.copyTo(it) }
                        }
                    }
                }
                modelDir.deleteRecursively()
                if (!File(tmp, MODEL).renameTo(modelDir)) error("no se pudo instalar")
                tmp.deleteRecursively()
                _state.value = ModelState.Ready
                logger.d(TAG, "Voz Daniela instalada")
                true
            } catch (e: Exception) {
                logger.w(TAG, "No pude bajar la voz Daniela", e)
                _state.value = ModelState.Failed("No pude bajar la voz: ${e.message ?: "error"}. Probá de nuevo.")
                false
            } finally {
                archive.delete()
            }
        }
    }

    fun delete() {
        modelDir.deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    companion object {
        private const val TAG = "Piper"
        const val MODEL = "vits-piper-es_AR-daniela-high"
        const val ONNX = "es_AR-daniela-high.onnx"
        const val URL_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL.tar.bz2"
    }
}

/**
 * Habla con Daniela. Mientras la voz no está descargada (o si falla) usa la voz del teléfono, así
 * Sara nunca se queda muda. Las frases cortas de siempre («Okey, voy.», «¿Sí?») quedan preparadas en
 * memoria para que suenen al instante.
 */
class PiperVoiceEngine(
    private val models: PiperModelManager,
    private val fallback: VoiceEngine,
    private val focus: AudioFocusHelper,
    private val levels: AudioLevelMeter,
    private val logger: SaraLogger,
) : VoiceEngine {
    override val id = "piper-daniela"
    override val displayName = "Daniela (Argentina, sin Internet)"
    override val isOnline = false

    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private val cache = HashMap<String, FloatArray>()
    /** Muestras ya entregadas al parlante en la frase actual. */
    @Volatile private var written = 0
    @Volatile private var firstSound = 0L

    override suspend fun availability(): Availability =
        if (models.isInstalled()) Availability.Available else fallback.availability()

    override suspend fun voices(languageTag: String): List<VoiceOption> =
        listOf(VoiceOption(id, "Daniela", "es-AR", requiresNetwork = false, description = "Argentina · femenina · sin Internet"))

    private suspend fun engine(): OfflineTts? = lock.withLock {
        tts?.let { return it }
        if (!models.isInstalled()) return null
        val dir = models.modelDir()
        runCatching {
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, PiperModelManager.ONNX).absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = THREADS,
                    ),
                ),
            )
        }.onFailure { logger.w(TAG, "No pude cargar la voz Daniela", it) }.getOrNull()?.also { tts = it }
    }

    /** Velocidad de Sara (1.0 normal) → velocidad de Piper. */
    private fun speedOf(params: VoiceParams) = params.rate.coerceIn(0.5f, 2f)

    override suspend fun prepare(text: String, params: VoiceParams) {
        val key = "${speedOf(params)}|$text"
        if (synchronized(cache) { key in cache }) return
        val engine = engine() ?: return
        val audio = withContext(Dispatchers.Default) { engine.generate(text, sid = 0, speed = speedOf(params)) }
        synchronized(cache) { cache[key] = audio.samples }
    }

    override suspend fun speak(text: String, params: VoiceParams): SpeakResult {
        val engine = engine() ?: return fallback.speak(text, params)
        val request = focus.requestSpeech()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(engine.sampleRate()).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(engine.sampleRate() * 4 / 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        written = 0
        val started = System.currentTimeMillis()
        firstSound = 0L
        return try {
            track.setVolume(params.volume.coerceIn(0f, 1f))
            track.play()
            val cached = synchronized(cache) { cache["${speedOf(params)}|$text"] }
            withContext(Dispatchers.Default) {
                if (cached != null) {
                    write(track, cached)
                } else {
                    // Va sonando a medida que se genera (frase por frase): empieza a hablar enseguida.
                    val job = coroutineContext
                    engine.generateWithCallback(
                        text, sid = 0, speed = speedOf(params),
                        callback = PiperChunkCallback { samples ->
                            if (job.isActive) {
                                write(track, samples)
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
            }
            // Esperar a que termine de sonar lo que quedó en el búfer (máximo 3 s).
            val end = System.currentTimeMillis() + 3_000
            while (track.playbackHeadPosition < written && System.currentTimeMillis() < end) kotlinx.coroutines.delay(20)
            logger.d(TAG, "Daniela habló: ${firstSound - started} ms hasta el primer sonido, ${written / engine.sampleRate().toFloat()} s de audio")
            SpeakResult.Completed(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Daniela no pudo hablar; uso la voz del teléfono", e)
            fallback.speak(text, params)
        } finally {
            levels.reset()
            runCatching { track.stop() }
            track.release()
            focus.abandon(request)
        }
    }

    private fun write(track: AudioTrack, samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val n = minOf(CHUNK, samples.size - offset)
            var sum = 0.0
            for (i in offset until offset + n) sum += samples[i] * samples[i]
            levels.update((sqrt(sum / n) * 4).toFloat())
            if (firstSound == 0L) firstSound = System.currentTimeMillis()
            val count = track.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) return
            offset += count
            written += count
        }
    }

    private companion object {
        const val TAG = "Piper"
        const val THREADS = 2
        const val CHUNK = 2_048
    }
}
