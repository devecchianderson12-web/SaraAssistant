package com.lucyremota.emisor.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.lucyremota.emisor.audio.AudioFocusHelper
import com.lucyremota.nucleo.engine.Availability
import com.lucyremota.nucleo.engine.EngineError
import com.lucyremota.nucleo.engine.EngineErrorType
import com.lucyremota.nucleo.engine.SpeakResult
import com.lucyremota.nucleo.engine.UnavailableReason
import com.lucyremota.nucleo.engine.VoiceEngine
import com.lucyremota.nucleo.engine.VoiceOption
import com.lucyremota.nucleo.engine.VoiceParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Instancia única del motor TTS del sistema, inicializada a demanda. */
class SharedTextToSpeech(private val context: Context) {
    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun get(): TextToSpeech? = mutex.withLock {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val instance = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        }
        val ok = withTimeoutOrNull(8_000) { ready.await() } ?: false
        if (!ok) {
            instance.shutdown()
            return null
        }
        instance.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(true) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }
        })
        tts = instance
        instance
    }

    fun register(id: String) = CompletableDeferred<Boolean>().also { pending[id] = it }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}

/**
 * Voz del motor TTS instalado (Google, Samsung, etc.).
 * - [networkVoices] = false: sólo voces instaladas que funcionan sin Internet (modo offline).
 * - [networkVoices] = true: voces del sistema que requieren conexión (suelen ser de mejor calidad).
 * La calidad depende de las voces que el usuario tenga instaladas: no se promete calidad neuronal.
 */
class AndroidTtsVoiceEngine(
    private val context: Context,
    private val shared: SharedTextToSpeech,
    private val focus: AudioFocusHelper,
    private val cache: PhraseCache,
    private val networkVoices: Boolean,
) : VoiceEngine {
    override val id = if (networkVoices) "android-tts-network" else "android-tts-local"
    override val displayName = if (networkVoices) "Voces online del sistema" else "Voz del dispositivo (offline)"
    override val isOnline = networkVoices

    override suspend fun availability(): Availability {
        val tts = shared.get()
            ?: return Availability.Unavailable(UnavailableReason.NOT_SUPPORTED, "No hay un motor de texto a voz instalado.")
        val voices = spanishVoices(tts)
        return if (voices.isEmpty()) {
            Availability.Unavailable(
                UnavailableReason.MISSING_MODEL,
                if (networkVoices) "El motor de voz no ofrece voces online en español."
                else "No hay voces en español instaladas. Instalalas en Ajustes › Sistema › Idioma › Texto a voz.",
            )
        } else {
            Availability.Available
        }
    }

    override suspend fun voices(languageTag: String): List<VoiceOption> {
        val tts = shared.get() ?: return emptyList()
        val preferred = Locale.forLanguageTag(languageTag)
        return spanishVoices(tts)
            .sortedWith(compareBy({ rank(it.locale, preferred) }, { -it.quality }, { it.name }))
            .map {
                VoiceOption(
                    id = it.name,
                    name = it.name,
                    locale = it.locale.toLanguageTag(),
                    requiresNetwork = it.isNetworkConnectionRequired,
                    description = "${it.locale.getDisplayCountry(Locale("es"))} · calidad ${qualityLabel(it.quality)}",
                )
            }
    }

    override suspend fun prepare(text: String, params: VoiceParams) {
        val tts = shared.get() ?: return
        val voice = chooseVoice(tts, params) ?: return
        val key = cache.key(id, voice.name, params.rate, text)
        if (cache.get(key) == null) synthesizeClip(tts, voice, text, params)?.let { cache.put(key, it) }
    }

    override suspend fun speak(text: String, params: VoiceParams): SpeakResult {
        val tts = shared.get()
            ?: return SpeakResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, "No hay motor de texto a voz."), id)
        val voice = chooseVoice(tts, params)
            ?: return SpeakResult.Failure(EngineError(EngineErrorType.NOT_AVAILABLE, "No hay voces en español disponibles."), id)
        if (params.cacheable) {
            val clip = cache.get(cache.key(id, voice.name, params.rate, text))
            if (clip != null) {
                val request = focus.requestSpeech()
                try {
                    PcmPlayer.play(clip, params.volume)
                    return SpeakResult.Completed(id)
                } finally {
                    focus.abandon(request)
                }
            }
        }
        val utteranceId = UUID.randomUUID().toString()
        val done = shared.register(utteranceId)
        val request = focus.requestSpeech()
        try {
            tts.voice = voice
            tts.setSpeechRate(params.rate.coerceIn(0.5f, 2f))
            tts.setPitch(1.0f)
            val bundle = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, params.volume.coerceIn(0f, 1f)) }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId) != TextToSpeech.SUCCESS) {
                return SpeakResult.Failure(EngineError(EngineErrorType.UNKNOWN, "El motor de voz rechazó el texto."), id)
            }
            val ok = withTimeoutOrNull(30_000) { done.await() } ?: false
            return if (ok) SpeakResult.Completed(id)
            else SpeakResult.Failure(EngineError(if (networkVoices) EngineErrorType.NETWORK else EngineErrorType.UNKNOWN, "La voz no pudo completar la frase."), id)
        } finally {
            if (!done.isCompleted) tts.stop()
            focus.abandon(request)
        }
    }

    /** Sintetiza a un WAV temporal y lo convierte en PCM (para la caché de frases). */
    private suspend fun synthesizeClip(tts: TextToSpeech, voice: Voice, text: String, params: VoiceParams): PcmClip? {
        val file = File(context.cacheDir, "tts-${UUID.randomUUID()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        val done = shared.register(utteranceId)
        return try {
            tts.voice = voice
            tts.setSpeechRate(params.rate.coerceIn(0.5f, 2f))
            if (tts.synthesizeToFile(text, Bundle(), file, utteranceId) != TextToSpeech.SUCCESS) return null
            val ok = withTimeoutOrNull(15_000) { done.await() } ?: false
            if (ok) PcmPlayer.fromWav(file) else null
        } finally {
            file.delete()
        }
    }

    private fun spanishVoices(tts: TextToSpeech): List<Voice> = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        .filter { it.locale.language == "es" }
        .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        .filter { it.isNetworkConnectionRequired == networkVoices }

    private fun chooseVoice(tts: TextToSpeech, params: VoiceParams): Voice? {
        val voices = spanishVoices(tts)
        params.voiceId?.let { wanted -> voices.firstOrNull { it.name == wanted }?.let { return it } }
        val preferred = Locale.forLanguageTag(params.languageTag)
        return voices.sortedWith(compareBy({ rank(it.locale, preferred) }, { -it.quality })).firstOrNull()
    }

    /** Preferencia: idioma elegido > rioplatense > latinoamericano > resto del español. */
    private fun rank(locale: Locale, preferred: Locale): Int = when {
        locale.country.equals(preferred.country, true) -> 0
        locale.country == "AR" || locale.country == "UY" -> 1
        locale.country in LATAM -> 2
        else -> 3
    }

    private fun qualityLabel(quality: Int) = when {
        quality >= Voice.QUALITY_VERY_HIGH -> "muy alta"
        quality >= Voice.QUALITY_HIGH -> "alta"
        quality >= Voice.QUALITY_NORMAL -> "normal"
        else -> "baja"
    }

    private companion object {
        val LATAM = setOf("US", "MX", "CO", "CL", "PE", "VE", "419", "EC", "BO", "PY", "CR", "PA", "DO", "GT", "HN", "NI", "SV", "PR", "CU")
    }
}
