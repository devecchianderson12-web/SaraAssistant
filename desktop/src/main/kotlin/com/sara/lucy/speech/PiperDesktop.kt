package com.sara.lucy.speech

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.sara.core.engine.Availability
import com.sara.core.engine.SpeakResult
import com.sara.core.engine.VoiceEngine
import com.sara.core.engine.VoiceOption
import com.sara.core.engine.VoiceParams
import com.sara.core.util.AudioLevelMeter
import com.sara.core.util.SaraLogger
import com.sara.core.voice.PiperVoice
import com.sara.lucy.win.LucyPaths
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
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** Carpeta con las DLL de sherpa-onnx que vienen con Lucy (voces de Piper y reconocimiento Parakeet). */
internal fun sherpaNativeDir(): File? {
    val candidates = buildList {
        System.getProperty("jpackage.app-path")?.let { File(it).parentFile }?.let { add(File(it, "sherpa")); add(File(it, "app/sherpa")) }
        runCatching { File(PiperVoiceEngine::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()?.let {
            add(File(it, "sherpa")); add(File(it.parentFile, "sherpa"))
        }
    }
    return candidates.firstOrNull { File(it, "sherpa-onnx-jni.dll").isFile }
}

/** Estado de la voz elegida (bajándose, lista…). */
sealed interface PiperState {
    data object NotInstalled : PiperState
    data class Downloading(val progress: Float) : PiperState
    data object Installing : PiperState
    data object Ready : PiperState
    data class Failed(val message: String) : PiperState
}

/** Descarga e instala la voz de Piper elegida en %APPDATA%\Lucy\piper (una vez). */
class PiperModels(private val logger: SaraLogger, private val selected: () -> PiperVoice) {
    private val dir = File(LucyPaths.home, "piper")
    private val mutex = Mutex()
    private val _state = MutableStateFlow<PiperState>(PiperState.NotInstalled)
    val state: StateFlow<PiperState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun modelDir(voice: PiperVoice = selected()) = File(dir, voice.folder)

    fun isInstalled(voice: PiperVoice = selected()) = modelDir(voice).let {
        File(it, voice.onnx).isFile && File(it, "tokens.txt").isFile && File(it, "espeak-ng-data").isDirectory
    }

    fun refresh() {
        if (_state.value !is PiperState.Downloading && _state.value != PiperState.Installing) {
            _state.value = if (isInstalled()) PiperState.Ready else PiperState.NotInstalled
        }
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val voice = selected()
        mutex.withLock {
            if (isInstalled(voice)) return@withLock true.also { _state.value = PiperState.Ready }
            val archive = File(dir, "${voice.name.lowercase()}.tar.bz2.part")
            try {
                dir.mkdirs()
                _state.value = PiperState.Downloading(0f)
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
                            if (total > 0) _state.value = PiperState.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                _state.value = PiperState.Installing
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
                modelDir(voice).deleteRecursively()
                if (!File(tmp, voice.folder).renameTo(modelDir(voice))) error("no se pudo instalar")
                tmp.deleteRecursively()
                if (selected() == voice) _state.value = PiperState.Ready
                logger.d(TAG, "Voz ${voice.label} instalada")
                true
            } catch (e: Exception) {
                logger.w(TAG, "No pude bajar la voz ${voice.label}", e)
                _state.value = PiperState.Failed("No pude bajar la voz: ${e.message ?: "error"}. Probá de nuevo.")
                false
            } finally {
                archive.delete()
            }
        }
    }

    private companion object { const val TAG = "Piper" }
}

/**
 * Habla con la voz de Piper elegida (la misma de Android). Mientras no está bajada, o si falla, habla
 * la voz de Windows. Frase por frase: empieza a sonar apenas está lista la primera.
 */
class PiperVoiceEngine(
    private val models: PiperModels,
    private val selected: () -> PiperVoice,
    private val fallback: VoiceEngine,
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

    override suspend fun availability(): Availability = if (models.isInstalled()) Availability.Available else fallback.availability()

    override suspend fun voices(languageTag: String): List<VoiceOption> = PiperVoice.entries.map {
        VoiceOption(it.name, it.label, it.country, requiresNetwork = false, description = "${it.country} · femenina · sin Internet")
    }

    private suspend fun engine(): OfflineTts? = lock.withLock {
        val voice = selected()
        if (loaded == voice) tts?.let { return it }
        tts?.release()
        tts = null
        loaded = null
        synchronized(cache) { cache.clear() }
        if (!models.isInstalled(voice)) return null
        val dir = models.modelDir(voice)
        runCatching {
            nativeLibraries()?.let { System.setProperty("sherpa_onnx.native.path", it.absolutePath) }
            val vits = OfflineTtsVitsModelConfig.builder()
                .setModel(File(dir, voice.onnx).absolutePath)
                .setTokens(File(dir, "tokens.txt").absolutePath)
                .setDataDir(File(dir, "espeak-ng-data").absolutePath)
                .build()
            OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(2).setDebug(false).build()).build())
        }.onFailure { logger.w(TAG, "No pude cargar la voz ${voice.label}", it) }.getOrNull()?.also {
            tts = it
            loaded = voice
        }
    }

    /** Las DLL de sherpa-onnx van en la carpeta «sherpa» junto al programa. */
    private fun nativeLibraries(): File? = sherpaNativeDir()

    /** Para la autoprueba: genera una frase sin reproducirla (cuántas muestras salieron). */
    suspend fun synthesizeForTest(text: String): Int = engine()?.let { withContext(Dispatchers.Default) { it.generate(text, selected().sid, selected().speed).samples.size } } ?: 0

    private fun speedOf(params: VoiceParams) = (params.rate * selected().speed).coerceIn(0.5f, 2f)

    override suspend fun prepare(text: String, params: VoiceParams) {
        val key = "${selected()}|${speedOf(params)}|$text"
        if (synchronized(cache) { key in cache }) return
        val engine = engine() ?: return
        val audio = withContext(Dispatchers.Default) { engine.generate(text, selected().sid, speedOf(params)) }
        synchronized(cache) { cache[key] = audio.samples }
    }

    override suspend fun speak(text: String, params: VoiceParams): SpeakResult {
        val engine = engine() ?: return fallback.speak(text, params)
        val rate = engine.sampleRate.toFloat()
        val line = runCatching {
            val format = AudioFormat(rate, 16, 1, true, false)
            (AudioSystem.getLine(javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine).apply {
                open(format, (rate / 4).toInt() * 2)
                start()
            }
        }.getOrElse { return fallback.speak(text, params) }
        return try {
            val volume = params.volume.coerceIn(0f, 1f)
            val cached = synchronized(cache) { cache["${selected()}|${speedOf(params)}|$text"] }
            withContext(Dispatchers.IO) {
                if (cached != null) write(line, cached, volume)
                else for (sentence in text.split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }) {
                    if (!coroutineContext.isActive) break
                    write(line, engine.generate(sentence, selected().sid, speedOf(params)).samples, volume)
                }
                if (coroutineContext.isActive) line.drain()
            }
            SpeakResult.Completed(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "${selected().label} no pudo hablar; uso la voz de Windows", e)
            fallback.speak(text, params)
        } finally {
            levels.reset()
            runCatching { line.stop(); line.close() }
        }
    }

    private fun write(line: SourceDataLine, samples: FloatArray, volume: Float) {
        var offset = 0
        val bytes = ByteArray(CHUNK * 2)
        while (offset < samples.size) {
            val n = minOf(CHUNK, samples.size - offset)
            var sum = 0.0
            for (i in 0 until n) {
                val v = samples[offset + i] * volume
                sum += v * v
                val s = (v.coerceIn(-1f, 1f) * 32767).toInt()
                bytes[i * 2] = (s and 0xFF).toByte()
                bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            levels.update((sqrt(sum / n) * 4).toFloat())
            line.write(bytes, 0, n * 2)
            offset += n
        }
    }

    private companion object {
        const val TAG = "Piper"
        const val CHUNK = 2_048
        val SENTENCE = Regex("(?<=[.!?;:])\\s+")
    }
}
