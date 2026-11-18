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
 * Voces femeninas de Piper (libres, gratis y sin Internet). Son todas las voces femeninas en español
 * que publica Piper: no hay de Chile, Uruguay ni Colombia.
 * [speed] corrige el ritmo propio de cada voz (Daniela habla rápido y suena cortante: se la frena).
 * En estos modelos la velocidad no es lineal: 0,65 la hace hablar alrededor de un 15 % más lento.
 */
enum class PiperVoice(val label: String, val country: String, val folder: String, val onnx: String, val sid: Int, val speed: Float, val megabytes: Int) {
    DANIELA("Daniela", "Argentina", "vits-piper-es_AR-daniela-high", "es_AR-daniela-high.onnx", 0, 0.65f, 115),
    CLAUDE("Claude", "México", "vits-piper-es_MX-claude-high", "es_MX-claude-high.onnx", 0, 1.0f, 67),
    SHARVARD("Sharvard", "España", "vits-piper-es_ES-sharvard-medium", "es_ES-sharvard-medium.onnx", 1, 1.0f, 80),
    ;

    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$folder.tar.bz2"
}

/** Descarga e instala la voz elegida (una vez, por Wi-Fi) y dice si está lista. */
class PiperModelManager(private val context: Context, private val logger: SaraLogger, private val selected: () -> PiperVoice) {
    private val dir = File(context.filesDir, "piper")
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    /** Estado de la voz elegida. */
    val state: StateFlow<ModelState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun modelDir(voice: PiperVoice = selected()): File = File(dir, voice.folder)

    fun isInstalled(voice: PiperVoice = selected()) = modelDir(voice).let {
        File(it, voice.onnx).isFile && File(it, "tokens.txt").isFile && File(it, "espeak-ng-data").isDirectory
    }

    /** Al cambiar de voz: muestra si la nueva ya está bajada. */
    fun refresh() {
        if (_state.value !is ModelState.Downloading && _state.value != ModelState.Installing) {
            _state.value = if (isInstalled()) ModelState.Ready else ModelState.NotInstalled
        }
    }

    /** Wi-Fi u otra red sin costo: ahí se baja sola. Con datos móviles espera (o el botón de Ajustes). */
    fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val voice = selected()
        mutex.withLock {
            if (isInstalled(voice)) return@withLock true.also { _state.value = ModelState.Ready }
            val modelDir = modelDir(voice)
            val archive = File(context.cacheDir, "piper-${voice.name.lowercase()}.tar.bz2")
            try {
                _state.value = ModelState.Downloading(0f)
                val conn = (URL(voice.url).openConnection() as HttpURLConnection).apply {
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
                if (!File(tmp, voice.folder).renameTo(modelDir)) error("no se pudo instalar")
                tmp.deleteRecursively()
                _state.value = if (selected() == voice) ModelState.Ready else _state.value
                logger.d(TAG, "Voz ${voice.label} instalada")
                true
            } catch (e: Exception) {
                logger.w(TAG, "No pude bajar la voz ${voice.label}", e)
                _state.value = ModelState.Failed("No pude bajar la voz: ${e.message ?: "error"}. Probá de nuevo.")
                false
            } finally {
                archive.delete()
            }
        }
    }

    private companion object {
        const val TAG = "Piper"
    }
}

/**
 * Habla con la voz de Piper elegida. Mientras la voz no está descargada (o si falla) usa la voz del teléfono, así
 * Sara nunca se queda muda. Las frases cortas de siempre («Okey, voy.», «¿Sí?») quedan preparadas en
 * memoria para que suenen al instante.
 */
class PiperVoiceEngine(
    private val models: PiperModelManager,
    private val selected: () -> PiperVoice,
    private val fallback: VoiceEngine,
    private val focus: AudioFocusHelper,
    private val levels: AudioLevelMeter,
    private val logger: SaraLogger,
) : VoiceEngine {
    override val id = "piper"
    override val displayName = "Voces de Piper (sin Internet)"
    override val isOnline = false

    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private var loaded: PiperVoice? = null
    private val cache = HashMap<String, FloatArray>()
    /** Muestras ya entregadas al parlante en la frase actual. */
    @Volatile private var written = 0
    @Volatile private var firstSound = 0L

    override suspend fun availability(): Availability =
        if (models.isInstalled()) Availability.Available else fallback.availability()

    override suspend fun voices(languageTag: String): List<VoiceOption> = PiperVoice.entries.map {
        VoiceOption(it.name, it.label, it.country, requiresNetwork = false, description = "${it.country} · femenina · sin Internet")
    }

    private suspend fun engine(): OfflineTts? = lock.withLock {
        val voice = selected()
        if (loaded == voice) tts?.let { return it }
        // Cambió la voz: se libera la anterior (y lo que tenía preparado).
        tts?.release()
        tts = null
        loaded = null
        synchronized(cache) { cache.clear() }
        if (!models.isInstalled(voice)) return null
        val dir = models.modelDir(voice)
        runCatching {
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, voice.onnx).absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = THREADS,
                    ),
                ),
            )
        }.onFailure { logger.w(TAG, "No pude cargar la voz ${voice.label}", it) }.getOrNull()?.also {
            tts = it
            loaded = voice
        }
    }

    /** Velocidad de Sara (1.0 normal) → velocidad de Piper. */
    private fun speedOf(params: VoiceParams) = (params.rate * selected().speed).coerceIn(0.5f, 2f)

    override suspend fun prepare(text: String, params: VoiceParams) {
        val key = "${selected()}|${speedOf(params)}|$text"
        if (synchronized(cache) { key in cache }) return
        val engine = engine() ?: return
        val audio = withContext(Dispatchers.Default) { engine.generate(text, sid = selected().sid, speed = speedOf(params)) }
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
            val cached = synchronized(cache) { cache["${selected()}|${speedOf(params)}|$text"] }
            withContext(Dispatchers.Default) {
                if (cached != null) {
                    write(track, cached)
                } else {
                    // Frase por frase: empieza a sonar apenas está lista la primera (la generación con
                    // callback de la librería ignora la velocidad, por eso no se usa).
                    for (sentence in text.split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }) {
                        if (!coroutineContext.isActive) break
                        write(track, engine.generate(sentence, sid = selected().sid, speed = speedOf(params)).samples)
                    }
                }
            }
            // Que suene lo que quedó en el búfer: con frases cortas («¿Sí?») el búfer no se llena y el
            // parlante no arrancaba hasta el tiempo límite (eso demoraba 3 s en abrir el micrófono).
            // stop() en modo stream reproduce lo pendiente y termina; se espera sólo lo que dura el audio.
            track.stop()
            val end = System.currentTimeMillis() + (written * 1000L / engine.sampleRate()) + 300
            while (track.playbackHeadPosition < written && System.currentTimeMillis() < end) kotlinx.coroutines.delay(10)
            logger.d(TAG, "${selected().label} habló: ${firstSound - started} ms hasta el primer sonido, ${written / engine.sampleRate().toFloat()} s de audio")
            SpeakResult.Completed(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "${selected().label} no pudo hablar; uso la voz del teléfono", e)
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
        val SENTENCE = Regex("(?<=[.!?;:])\\s+")
    }
}
