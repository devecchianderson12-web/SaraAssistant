package com.lucyremota.emisor.wakeword

import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineActivationLimitException
import ai.picovoice.porcupine.PorcupineActivationRefusedException
import ai.picovoice.porcupine.PorcupineActivationThrottledException
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.net.Uri
import com.lucyremota.emisor.audio.MicrophoneSource
import com.lucyremota.emisor.data.SecretStore
import com.lucyremota.nucleo.engine.Availability
import com.lucyremota.nucleo.engine.EngineError
import com.lucyremota.nucleo.engine.EngineErrorType
import com.lucyremota.nucleo.engine.UnavailableReason
import com.lucyremota.nucleo.engine.WakeWordConfig
import com.lucyremota.nucleo.engine.WakeWordEngine
import com.lucyremota.nucleo.engine.WakeWordResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Archivos que el usuario importa desde la consola de Picovoice. */
class PorcupineFiles(private val context: Context) {
    private val dir = File(context.filesDir, "porcupine").apply { mkdirs() }
    val keyword = File(dir, "lucy_es.ppn")
    val model = File(dir, "porcupine_params_es.pv")

    suspend fun import(uri: Uri, target: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            target.length() > 0
        }.getOrDefault(false)
    }
}

/**
 * Adaptador opcional para Picovoice Porcupine (detector dedicado de muy bajo consumo).
 * Requiere AccessKey gratuita, un archivo .ppn entrenado para "Lucy" en español y el modelo
 * porcupine_params_es.pv. Ver README › Porcupine.
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val secrets: SecretStore,
    private val files: PorcupineFiles,
    private val microphone: MicrophoneSource,
) : WakeWordEngine {
    override val id = "porcupine"
    override val displayName = "Picovoice Porcupine"

    override suspend fun availability(): Availability = when {
        !microphone.hasPermission() -> Availability.Unavailable(UnavailableReason.PERMISSION_DENIED, "Lucy necesita permiso de micrófono.")
        !secrets.has(SecretStore.PORCUPINE_ACCESS_KEY) -> Availability.Unavailable(UnavailableReason.MISSING_CREDENTIALS, "Falta la AccessKey de Picovoice.")
        !files.keyword.isFile -> Availability.Unavailable(UnavailableReason.MISSING_MODEL, "Falta importar el archivo .ppn de \"Lucy\".")
        !files.model.isFile -> Availability.Unavailable(UnavailableReason.MISSING_MODEL, "Falta importar porcupine_params_es.pv.")
        else -> Availability.Available
    }

    override suspend fun awaitWakeWord(config: WakeWordConfig): WakeWordResult {
        val accessKey = secrets.get(SecretStore.PORCUPINE_ACCESS_KEY)
            ?: return WakeWordResult.Failed(EngineError(EngineErrorType.CREDENTIALS, "Falta la AccessKey de Picovoice."))
        val result = CompletableDeferred<WakeWordResult>()
        val manager = try {
            PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf(files.keyword.absolutePath))
                .setModelPath(files.model.absolutePath)
                .setSensitivities(floatArrayOf(config.sensitivity.coerceIn(0f, 1f)))
                .setErrorCallback { e -> result.complete(WakeWordResult.Failed(map(e))) }
                .build(context.applicationContext) { _ -> result.complete(WakeWordResult.Detected(null)) }
        } catch (e: PorcupineException) {
            return WakeWordResult.Failed(map(e))
        }
        try {
            manager.start()
            return result.await()
        } catch (e: PorcupineException) {
            return WakeWordResult.Failed(map(e))
        } finally {
            // Se libera el micrófono antes de devolver el control (fuera del hilo de audio).
            withContext(NonCancellable + Dispatchers.Default) {
                runCatching { manager.stop() }
                runCatching { manager.delete() }
            }
        }
    }

    private fun map(e: PorcupineException): EngineError {
        val type = when (e) {
            is PorcupineActivationException, is PorcupineActivationLimitException,
            is PorcupineActivationRefusedException, is PorcupineActivationThrottledException -> EngineErrorType.CREDENTIALS
            else -> EngineErrorType.NOT_AVAILABLE
        }
        return EngineError(type, "Porcupine: ${e.message ?: e.javaClass.simpleName}", e)
    }
}
