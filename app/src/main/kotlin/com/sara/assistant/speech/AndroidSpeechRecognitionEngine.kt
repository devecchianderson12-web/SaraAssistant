package com.sara.assistant.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.sara.core.engine.Availability
import com.sara.core.engine.EngineError
import com.sara.core.engine.EngineErrorType
import com.sara.core.engine.RecognitionRequest
import com.sara.core.engine.RecognitionResult
import com.sara.core.engine.SpeechRecognitionEngine
import com.sara.core.engine.UnavailableReason
import com.sara.core.util.AudioLevelMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reconocedor del sistema Android.
 * - [onDevice] = false: servicio de reconocimiento instalado (normalmente Google), alta calidad,
 *   puede enviar audio a sus servidores -> se considera ONLINE.
 * - [onDevice] = true: `createOnDeviceSpeechRecognizer` (Android 12+), garantiza procesamiento local.
 */
class AndroidSpeechRecognitionEngine(
    private val context: Context,
    private val onDevice: Boolean,
    private val levels: AudioLevelMeter,
) : SpeechRecognitionEngine {
    override val id = if (onDevice) "android-on-device" else "android-system"
    override val displayName = if (onDevice) "Android en el dispositivo (offline)" else "Servicio de voz del sistema (online)"
    override val isOnline = !onDevice

    override suspend fun availability(): Availability = withContext(Dispatchers.Main) {
        if (onDevice) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return@withContext Availability.Unavailable(UnavailableReason.NOT_SUPPORTED, "El reconocimiento en el dispositivo requiere Android 12 o superior.")
            }
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                return@withContext Availability.Unavailable(UnavailableReason.NOT_SUPPORTED, "Este teléfono no ofrece reconocimiento en el dispositivo.")
            }
            Availability.Available
        } else {
            if (systemRecognizerComponent() == null) {
                Availability.Unavailable(UnavailableReason.NOT_SUPPORTED, "No hay un servicio de reconocimiento de voz instalado (por ejemplo, la app de Google).")
            } else {
                Availability.Available
            }
        }
    }

    /**
     * Elige explícitamente un servicio que no sea el de Sara: cuando Sara es el asistente
     * predeterminado, el sistema puede marcar su propio RecognitionService como el de por defecto.
     */
    private fun systemRecognizerComponent(): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != context.packageName }
        val preferred = services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName == "com.google.android.tts" }
            ?: services.firstOrNull()
        return preferred?.let { ComponentName(it.packageName, it.name) }
    }

    override suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult {
        val hardLimit = request.noSpeechTimeoutMillis + request.maxSpeechMillis + 5_000
        return withTimeoutOrNull(hardLimit) { recognizeOnMain(request, onPartial) }
            ?: RecognitionResult.Failure(EngineError(EngineErrorType.SERVER, "El reconocimiento tardó demasiado."), id)
    }

    private suspend fun recognizeOnMain(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult =
        withContext(Dispatchers.Main) {
            val requestedAt = System.currentTimeMillis()
            suspendCancellableCoroutine { cont ->
                val recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    val component = systemRecognizerComponent()
                    if (component != null) SpeechRecognizer.createSpeechRecognizer(context, component)
                    else SpeechRecognizer.createSpeechRecognizer(context)
                }

                fun finish(result: RecognitionResult) {
                    if (!cont.isActive) return
                    levels.reset()
                    recognizer.destroy()
                    cont.resume(result)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        android.util.Log.d("Sara/Recognizer", "Listo para escuchar en ${System.currentTimeMillis() - requestedAt} ms")
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = levels.update((rmsdB + 2f) / 12f)
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }?.let(onPartial)
                    }

                    override fun onResults(results: Bundle?) {
                        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val best = texts.firstOrNull { it.isNotBlank() }
                        finish(
                            if (best != null) RecognitionResult.Success(best, scores?.firstOrNull()?.takeIf { it >= 0f }, id)
                            else RecognitionResult.Failure(EngineError(EngineErrorType.NO_MATCH, "No entendí lo que dijiste."), id),
                        )
                    }

                    override fun onError(error: Int) {
                        finish(RecognitionResult.Failure(mapError(error), id))
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, request.languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, request.endSilenceMillis)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, request.endSilenceMillis)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
                    if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                cont.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { recognizer.cancel() }
                        runCatching { recognizer.destroy() }
                        levels.reset()
                    }
                }
                try {
                    recognizer.startListening(intent)
                } catch (e: Exception) {
                    finish(RecognitionResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, "No se pudo iniciar el reconocimiento: ${e.message}", e), id))
                }
            }
        }

    private fun mapError(code: Int): EngineError = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            EngineError(EngineErrorType.NETWORK, "Problema de conexión con el servicio de voz.")
        SpeechRecognizer.ERROR_SERVER, ERROR_TOO_MANY_REQUESTS, ERROR_SERVER_DISCONNECTED ->
            EngineError(EngineErrorType.SERVER, "El servicio de voz no respondió.")
        SpeechRecognizer.ERROR_AUDIO -> EngineError(EngineErrorType.AUDIO_INPUT, "No se pudo grabar audio.")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> EngineError(EngineErrorType.NO_SPEECH, "No se detectó voz.")
        SpeechRecognizer.ERROR_NO_MATCH -> EngineError(EngineErrorType.NO_MATCH, "No entendí lo que dijiste.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> EngineError(EngineErrorType.BUSY, "El reconocedor está ocupado.")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> EngineError(EngineErrorType.PERMISSION, "El servicio de voz no tiene permiso de micrófono.")
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
            EngineError(EngineErrorType.NOT_AVAILABLE, "El idioma no está disponible en este reconocedor. Descargá el paquete de español en los ajustes de voz del sistema.")
        else -> EngineError(EngineErrorType.UNKNOWN, "Error del reconocedor ($code).")
    }

    private companion object {
        // Constantes de API 31+ usadas por valor para mantener compatibilidad con Android 10.
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
