package com.sara.assistant.azure

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sara.assistant.audio.AudioFocusHelper
import com.sara.assistant.audio.CaptureOutcome
import com.sara.assistant.audio.MicrophoneSource
import com.sara.assistant.data.SecretStore
import com.sara.assistant.voice.PcmClip
import com.sara.assistant.voice.PcmPlayer
import com.sara.assistant.voice.PhraseCache
import com.sara.core.engine.Availability
import com.sara.core.engine.EngineError
import com.sara.core.engine.EngineErrorType
import com.sara.core.engine.RecognitionRequest
import com.sara.core.engine.RecognitionResult
import com.sara.core.engine.SpeakResult
import com.sara.core.engine.SpeechRecognitionEngine
import com.sara.core.engine.UnavailableReason
import com.sara.core.engine.VoiceEngine
import com.sara.core.engine.VoiceOption
import com.sara.core.engine.VoiceParams
import com.sara.core.util.AudioLevelMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Credenciales de Azure AI Speech: clave cifrada en [SecretStore] + región en ajustes. */
class AzureCredentials(private val secrets: SecretStore, private val regionSetting: () -> String) {
    fun key(): String? = secrets.get(SecretStore.AZURE_KEY)?.takeIf { it.isNotBlank() }
    fun region(): String? = regionSetting().trim().lowercase().takeIf { REGION.matches(it) }
    fun isConfigured() = key() != null && region() != null

    fun unavailable(): Availability.Unavailable? = when {
        key() == null -> Availability.Unavailable(UnavailableReason.MISSING_CREDENTIALS, "Falta la clave de Azure Speech (Ajustes › Voz › Proveedor online).")
        region() == null -> Availability.Unavailable(UnavailableReason.MISSING_CREDENTIALS, "Falta la región de Azure Speech (por ejemplo, eastus o brazilsouth).")
        else -> null
    }

    private companion object {
        val REGION = Regex("[a-z0-9]{3,30}")
    }
}

internal fun httpError(code: Int, body: String?): EngineError = when (code) {
    401, 403 -> EngineError(EngineErrorType.CREDENTIALS, "Azure rechazó la clave o la región ($code).")
    429 -> EngineError(EngineErrorType.SERVER, "Azure limitó las solicitudes (429). Probá en un momento.")
    in 500..599 -> EngineError(EngineErrorType.SERVER, "Azure no respondió ($code).")
    else -> EngineError(EngineErrorType.UNKNOWN, "Azure respondió $code ${body.orEmpty().take(120)}")
}

internal fun networkError(e: IOException): EngineError = when (e) {
    is UnknownHostException, is SocketTimeoutException -> EngineError(EngineErrorType.NETWORK, "Sin conexión con Azure.", e)
    else -> EngineError(EngineErrorType.NETWORK, "Error de red: ${e.message}", e)
}

/**
 * Voz neuronal online de Azure AI Speech. Por defecto usa es-AR-ElenaNeural (voz femenina
 * rioplatense). El audio PCM se reproduce a medida que llega para minimizar la latencia.
 */
class AzureNeuralVoiceEngine(
    private val credentials: AzureCredentials,
    private val focus: AudioFocusHelper,
    private val levels: AudioLevelMeter,
    private val cache: PhraseCache,
) : VoiceEngine {
    override val id = "azure-neural"
    override val displayName = "Azure neuronal (online)"
    override val isOnline = true

    override suspend fun availability(): Availability = credentials.unavailable() ?: Availability.Available

    override suspend fun voices(languageTag: String): List<VoiceOption> = withContext(Dispatchers.IO) {
        val key = credentials.key() ?: return@withContext emptyList()
        val region = credentials.region() ?: return@withContext emptyList()
        val connection = (URL("https://$region.tts.speech.microsoft.com/cognitiveservices/voices/list").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Ocp-Apim-Subscription-Key", key)
            connectTimeout = 8_000
            readTimeout = 10_000
        }
        try {
            if (connection.responseCode != 200) return@withContext emptyList()
            val array = JSONArray(connection.inputStream.bufferedReader().readText())
            (0 until array.length()).map { array.getJSONObject(it) }
                .filter { it.optString("Locale").startsWith("es-") }
                .map {
                    VoiceOption(
                        id = it.getString("ShortName"),
                        name = it.optString("LocalName", it.getString("ShortName")),
                        locale = it.optString("Locale"),
                        requiresNetwork = true,
                        description = "${it.optString("LocaleName")} · ${if (it.optString("Gender") == "Female") "femenina" else "masculina"}",
                    )
                }
                .sortedWith(compareBy({ it.locale != "es-AR" }, { !it.description.contains("femenina") }, { it.name }))
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun prepare(text: String, params: VoiceParams) {
        val cacheKey = cache.key(id, params.voiceId ?: DEFAULT_VOICE, params.rate, text)
        if (cache.get(cacheKey) == null) synthesizeClip(text, params)?.let { cache.put(cacheKey, it) }
    }

    /** Descarga la frase completa (para la caché de frases cortas). */
    private suspend fun synthesizeClip(text: String, params: VoiceParams): PcmClip? = withContext(Dispatchers.IO) {
        val key = credentials.key() ?: return@withContext null
        val region = credentials.region() ?: return@withContext null
        val connection = openSynthesis(region, key, text, params)
        try {
            if (connection.responseCode != 200) return@withContext null
            val pcm = connection.inputStream.use { it.readBytes() }
            PcmClip(if (pcm.size % 2 == 1) pcm.copyOf(pcm.size - 1) else pcm, SAMPLE_RATE)
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openSynthesis(region: String, key: String, text: String, params: VoiceParams): HttpURLConnection {
        val voice = params.voiceId ?: DEFAULT_VOICE
        val ratePercent = ((params.rate.coerceIn(0.5f, 2f) - 1f) * 100).toInt()
        val ssml = """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="${voice.take(5)}">""" +
            """<voice name="${voice.escapeXml()}"><prosody rate="${if (ratePercent >= 0) "+" else ""}$ratePercent%">${text.escapeXml()}</prosody></voice></speak>"""
        return (URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6_000
            readTimeout = 15_000
            setRequestProperty("Ocp-Apim-Subscription-Key", key)
            setRequestProperty("Content-Type", "application/ssml+xml")
            setRequestProperty("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
            setRequestProperty("User-Agent", "SaraAssistant")
            outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }
        }
    }

    override suspend fun speak(text: String, params: VoiceParams): SpeakResult = withContext(Dispatchers.IO) {
        if (params.cacheable) {
            val cacheKey = cache.key(id, params.voiceId ?: DEFAULT_VOICE, params.rate, text)
            val clip = cache.get(cacheKey) ?: synthesizeClip(text, params)?.also { cache.put(cacheKey, it) }
            if (clip != null) {
                val token = focus.requestSpeech()
                try {
                    PcmPlayer.play(clip, params.volume)
                    return@withContext SpeakResult.Completed(id)
                } finally {
                    focus.abandon(token)
                }
            }
        }
        val key = credentials.key() ?: return@withContext SpeakResult.Failure(EngineError(EngineErrorType.CREDENTIALS, "Falta la clave de Azure."), id)
        val region = credentials.region() ?: return@withContext SpeakResult.Failure(EngineError(EngineErrorType.CREDENTIALS, "Falta la región de Azure."), id)
        var track: AudioTrack? = null
        val focusToken = focus.requestSpeech()
        val connection = try {
            openSynthesis(region, key, text, params)
        } catch (e: IOException) {
            focus.abandon(focusToken)
            return@withContext SpeakResult.Failure(networkError(e), id)
        }
        try {
            val code = connection.responseCode
            if (code != 200) {
                return@withContext SpeakResult.Failure(httpError(code, connection.errorStream?.bufferedReader()?.readText()), id)
            }
            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE)) // ~0.5 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = audioTrack
            audioTrack.setVolume(params.volume.coerceIn(0f, 1f))
            audioTrack.play()
            var totalBytes = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(4_800)
                var carry = -1
                while (true) {
                    coroutineContext.ensureActive()
                    var read = input.read(buffer, if (carry >= 0) 1 else 0, buffer.size - 1)
                    if (read < 0) break
                    if (carry >= 0) {
                        buffer[0] = carry.toByte()
                        read += 1
                        carry = -1
                    }
                    // Las muestras son de 2 bytes: si llega un byte suelto, se guarda para el próximo bloque.
                    if (read % 2 == 1) {
                        carry = buffer[read - 1].toInt()
                        read -= 1
                    }
                    levels.update(AudioLevelMeter.levelOfPcm16(buffer, read))
                    audioTrack.write(buffer, 0, read)
                    totalBytes += read
                }
            }
            // Esperar a que termine de sonar lo que quedó en el buffer.
            val totalFrames = (totalBytes / 2).toInt()
            while (audioTrack.playbackHeadPosition < totalFrames) {
                coroutineContext.ensureActive()
                kotlinx.coroutines.delay(20)
                if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) break
            }
            SpeakResult.Completed(id)
        } catch (e: IOException) {
            SpeakResult.Failure(networkError(e), id)
        } finally {
            track?.let {
                runCatching { it.pause(); it.flush(); it.stop() }
                it.release()
            }
            connection.disconnect()
            focus.abandon(focusToken)
            levels.reset()
        }
    }

    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        const val DEFAULT_VOICE = "es-AR-ElenaNeural"
        private const val SAMPLE_RATE = 24_000
    }
}

/**
 * Reconocimiento online con Azure (API REST de audio corto). Sara graba localmente con detección
 * de voz por energía y envía sólo el fragmento de la orden, nunca audio continuo.
 */
class AzureSpeechRecognitionEngine(
    private val credentials: AzureCredentials,
    private val microphone: MicrophoneSource,
) : SpeechRecognitionEngine {
    override val id = "azure-stt"
    override val displayName = "Azure Speech (online)"
    override val isOnline = true

    override suspend fun availability(): Availability = when {
        !microphone.hasPermission() -> Availability.Unavailable(UnavailableReason.PERMISSION_DENIED, "Sara necesita permiso de micrófono.")
        else -> credentials.unavailable() ?: Availability.Available
    }

    override suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult {
        val key = credentials.key() ?: return RecognitionResult.Failure(EngineError(EngineErrorType.CREDENTIALS, "Falta la clave de Azure."), id)
        val region = credentials.region() ?: return RecognitionResult.Failure(EngineError(EngineErrorType.CREDENTIALS, "Falta la región de Azure."), id)

        val pcm = ByteArrayOutputStream()
        val detector = SpeechEndpointDetector(request.noSpeechTimeoutMillis, request.maxSpeechMillis)
        val outcome = microphone.capture { buffer, length ->
            pcm.write(buffer, 0, length)
            detector.feed(buffer, length)
        }
        if (outcome is CaptureOutcome.Failed) return RecognitionResult.Failure(outcome.error, id)
        if (outcome is CaptureOutcome.Completed && outcome.allSilent) {
            return RecognitionResult.Failure(EngineError(EngineErrorType.AUDIO_INPUT, "El micrófono no recibe audio."), id)
        }
        if (!detector.speechDetected) return RecognitionResult.Failure(EngineError(EngineErrorType.NO_SPEECH, "No se detectó voz."), id)

        return withContext(Dispatchers.IO) {
            val language = URLEncoder.encode(request.languageTag, "UTF-8")
            val url = URL("https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=$language&format=detailed")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6_000
                readTimeout = 12_000
                setRequestProperty("Ocp-Apim-Subscription-Key", key)
                setRequestProperty("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { out ->
                    val data = pcm.toByteArray()
                    out.write(wavHeader(data.size))
                    out.write(data)
                }
                val code = connection.responseCode
                if (code != 200) return@withContext RecognitionResult.Failure(httpError(code, connection.errorStream?.bufferedReader()?.readText()), id)
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                when (json.optString("RecognitionStatus")) {
                    "Success" -> {
                        val best = json.optJSONArray("NBest")?.optJSONObject(0)
                        val text = best?.optString("Display")?.takeIf { it.isNotBlank() } ?: json.optString("DisplayText")
                        if (text.isBlank()) RecognitionResult.Failure(EngineError(EngineErrorType.NO_MATCH, "No entendí lo que dijiste."), id)
                        else RecognitionResult.Success(text, best?.optDouble("Confidence")?.toFloat(), id)
                    }
                    "NoMatch", "InitialSilenceTimeout", "BabbleTimeout" ->
                        RecognitionResult.Failure(EngineError(EngineErrorType.NO_MATCH, "No entendí lo que dijiste."), id)
                    else -> RecognitionResult.Failure(EngineError(EngineErrorType.SERVER, "Azure no pudo procesar el audio."), id)
                }
            } catch (e: IOException) {
                RecognitionResult.Failure(networkError(e), id)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun wavHeader(dataSize: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(MicrophoneSource.SAMPLE_RATE); putInt(MicrophoneSource.SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataSize)
    }.array()
}

/**
 * Detección de fin de frase por energía con piso de ruido adaptativo. Devuelve `false` en [feed]
 * cuando hay que dejar de grabar.
 */
class SpeechEndpointDetector(
    private val noSpeechTimeoutMillis: Long,
    private val maxSpeechMillis: Long,
    private val chunkMillis: Long = 100,
    private val trailingSilenceMillis: Long = 900,
) {
    var speechDetected = false
        private set
    private var elapsed = 0L
    private var silenceAfterSpeech = 0L
    private var noiseFloor = -1.0

    fun feed(buffer: ByteArray, length: Int): Boolean {
        elapsed += chunkMillis
        val rms = AudioLevelMeter.rmsOfPcm16(buffer, length)
        // El piso de ruido arranca acotado: si el usuario habla desde el primer bloque, no se toma su voz como ruido.
        noiseFloor = if (noiseFloor < 0) minOf(rms, MIN_SPEECH_RMS / 2) else if (rms < noiseFloor) rms else noiseFloor * 0.98 + rms * 0.02
        val threshold = maxOf(noiseFloor * 3.0, MIN_SPEECH_RMS)
        if (rms > threshold) {
            speechDetected = true
            silenceAfterSpeech = 0
        } else if (speechDetected) {
            silenceAfterSpeech += chunkMillis
        }
        return when {
            !speechDetected && elapsed >= noSpeechTimeoutMillis -> false
            speechDetected && silenceAfterSpeech >= trailingSilenceMillis -> false
            elapsed >= noSpeechTimeoutMillis + maxSpeechMillis -> false
            else -> true
        }
    }

    private companion object {
        const val MIN_SPEECH_RMS = 600.0
    }
}
