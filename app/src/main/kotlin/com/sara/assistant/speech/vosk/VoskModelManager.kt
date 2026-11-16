package com.sara.assistant.speech.vosk

import android.content.Context
import android.net.Uri
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Installing : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Gestiona el modelo Vosk de español (vosk-model-small-es-0.42, ~40 MB, licencia Apache 2.0).
 * Se usa tanto para la palabra de activación (gramática restringida) como para el reconocimiento
 * offline. El modelo se descarga una sola vez y se procesa 100 % en el dispositivo.
 */
class VoskModelManager(private val context: Context, private val logger: SaraLogger) {
    private val baseDir = File(context.filesDir, "vosk")
    private val modelDir = File(baseDir, "es")
    private val mutex = Mutex()
    private var model: Model? = null

    private val _state = MutableStateFlow<ModelState>(if (isInstalled()) ModelState.Ready else ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun isInstalled(): Boolean = File(modelDir, "am/final.mdl").isFile && File(modelDir, "graph").isDirectory

    /** Carga perezosa (1-3 s la primera vez). El modelo se comparte entre reconocedores. */
    suspend fun model(): Model? = mutex.withLock {
        model?.let { return it }
        if (!isInstalled()) return null
        withContext(Dispatchers.IO) {
            runCatching {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(modelDir.absolutePath)
            }.onFailure {
                logger.w(TAG, "No se pudo cargar el modelo", it)
                _state.value = ModelState.Failed("El modelo está dañado. Volvé a descargarlo.")
            }.getOrNull()
        }.also { model = it }
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "vosk-model.zip")
        try {
            _state.value = ModelState.Downloading(0f)
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) error("El servidor respondió ${connection.responseCode}")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: MODEL_SIZE_BYTES
                connection.inputStream.use { input ->
                    zip.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReported = 0f
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val progress = (copied.toFloat() / total).coerceIn(0f, 1f)
                            if (progress - lastReported >= 0.01f) {
                                lastReported = progress
                                _state.value = ModelState.Downloading(progress)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            install(zip.inputStream())
        } catch (e: Exception) {
            logger.w(TAG, "Descarga fallida", e)
            _state.value = ModelState.Failed("No se pudo descargar el modelo: ${e.message}")
            false
        } finally {
            zip.delete()
        }
    }

    /** Instala un .zip de modelo Vosk elegido por el usuario (por ejemplo, descargado desde una PC). */
    suspend fun importZip(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo")
            install(stream)
        } catch (e: Exception) {
            _state.value = ModelState.Failed("No se pudo importar: ${e.message}")
            false
        }
    }

    suspend fun delete() = mutex.withLock {
        model?.close()
        model = null
        withContext(Dispatchers.IO) { modelDir.deleteRecursively() }
        _state.value = ModelState.NotInstalled
    }

    private suspend fun install(stream: InputStream): Boolean {
        _state.value = ModelState.Installing
        val staging = File(baseDir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingPath = staging.canonicalPath + File.separator
        ZipInputStream(stream.buffered()).use { zipStream ->
            while (true) {
                val entry = zipStream.nextEntry ?: break
                val target = File(staging, entry.name)
                // Protección contra "zip slip".
                if (!target.canonicalPath.startsWith(stagingPath)) error("Entrada inválida en el zip: ${entry.name}")
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zipStream.copyTo(it) }
                }
            }
        }
        // El zip oficial trae una carpeta raíz (vosk-model-small-es-0.42/); se aplana.
        val root = staging.listFiles()?.singleOrNull { it.isDirectory && !File(staging, "am").exists() } ?: staging
        if (!File(root, "am/final.mdl").isFile) {
            staging.deleteRecursively()
            _state.value = ModelState.Failed("El archivo no es un modelo Vosk válido.")
            return false
        }
        mutex.withLock {
            model?.close()
            model = null
            modelDir.deleteRecursively()
            if (!root.renameTo(modelDir)) root.copyRecursively(modelDir, overwrite = true)
        }
        staging.deleteRecursively()
        _state.value = ModelState.Ready
        logger.d(TAG, "Modelo instalado")
        return true
    }

    companion object {
        private const val TAG = "VoskModel"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        const val MODEL_SIZE_BYTES = 39_817_833L
    }
}
