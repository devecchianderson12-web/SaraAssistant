package com.sara.lucy.speech

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.sara.core.util.SaraLogger
import com.sara.lucy.win.LucyPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reconocimiento preciso sin Internet: Parakeet (NVIDIA, multilingüe con español) con sherpa-onnx.
 * Entiende frases libres como el reconocedor de Google del teléfono; Vosk sigue marcando cuándo
 * terminaste de hablar y Parakeet transcribe la orden entera en ~0,2–0,5 s. Se baja una vez (~490 MB).
 */
class ParakeetAsr(private val logger: SaraLogger, private val baseDir: File = File(LucyPaths.home, "asr")) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<PiperState>(PiperState.NotInstalled)
    /** Mismo estado que las voces (no instalado, bajando, listo, error). */
    val state: StateFlow<PiperState> = _state.asStateFlow()
    @Volatile private var recognizer: OfflineRecognizer? = null

    private val dir get() = File(baseDir, FOLDER)

    init { refresh() }

    fun isInstalled() = FILES.all { File(dir, it).isFile }

    fun refresh() {
        if (_state.value !is PiperState.Downloading && _state.value != PiperState.Installing) {
            _state.value = if (isInstalled()) PiperState.Ready else PiperState.NotInstalled
        }
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInstalled()) return@withLock true.also { _state.value = PiperState.Ready }
            baseDir.mkdirs()
            val archive = File(baseDir, "$FOLDER.tar.bz2.part")
            try {
                _state.value = PiperState.Downloading(0f)
                val conn = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
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
                val tmp = File(baseDir, "tmp").apply { deleteRecursively(); mkdirs() }
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        // Sólo lo necesario (no los audios de ejemplo).
                        if (!entry.isDirectory && entry.name.substringAfterLast('/') !in FILES) continue
                        val target = File(tmp, entry.name).canonicalFile
                        if (!target.path.startsWith(tmp.canonicalPath)) continue
                        if (entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { tar.copyTo(it) }
                        }
                    }
                }
                dir.deleteRecursively()
                if (!File(tmp, FOLDER).renameTo(dir)) error("no se pudo instalar")
                tmp.deleteRecursively()
                _state.value = PiperState.Ready
                logger.d(TAG, "Reconocimiento preciso instalado")
                true
            } catch (e: Exception) {
                logger.w(TAG, "No pude bajar el reconocimiento preciso", e)
                _state.value = PiperState.Failed("No pude bajarlo: ${e.message ?: "error"}. Probá de nuevo.")
                false
            } finally {
                archive.delete()
            }
        }
    }

    /** Carga el modelo (≈2 s la primera vez); después queda en memoria. */
    @Synchronized
    fun load(): OfflineRecognizer? {
        recognizer?.let { return it }
        if (!isInstalled()) return null
        return runCatching {
            sherpaNativeDir()?.let { System.setProperty("sherpa_onnx.native.path", it.absolutePath) }
            val t = OfflineTransducerModelConfig.builder()
                .setEncoder(File(dir, "encoder.int8.onnx").absolutePath)
                .setDecoder(File(dir, "decoder.int8.onnx").absolutePath)
                .setJoiner(File(dir, "joiner.int8.onnx").absolutePath)
                .build()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val model = OfflineModelConfig.builder().setTransducer(t).setTokens(File(dir, "tokens.txt").absolutePath)
                .setNumThreads(threads).setModelType("nemo_transducer").setDebug(false).build()
            OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model).setDecodingMethod("greedy_search").build())
        }.onFailure { logger.w(TAG, "No pude cargar el reconocimiento preciso", it) }.getOrNull()?.also { recognizer = it }
    }

    /** Texto de la orden (PCM 16 bits mono 16 kHz), o null si no está o no entendió nada. */
    suspend fun transcribe(pcm: ByteArray): String? = withContext(Dispatchers.Default) {
        val r = load() ?: return@withContext null
        if (pcm.size < 3_200) return@withContext null
        val samples = FloatArray(pcm.size / 2) { i ->
            ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort() / 32768f
        }
        val t0 = System.currentTimeMillis()
        val text = synchronized(this@ParakeetAsr) {
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, 16_000)
                r.decode(stream)
                r.getResult(stream).text
            } finally {
                stream.release()
            }
        }.trim()
        logger.d(TAG, "Parakeet (${System.currentTimeMillis() - t0} ms): «$text»")
        text.ifBlank { null }
    }

    private companion object {
        const val TAG = "Parakeet"
        const val FOLDER = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
        const val URL_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$FOLDER.tar.bz2"
        val FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    }
}
