package com.sara.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.sara.core.assistant.ActivationFeedback
import com.sara.core.execution.VolumeControl
import com.sara.core.execution.VolumeState
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Foco de audio "transitorio con atenuación": la música baja mientras Sara escucha o habla y
 * recupera su volumen al terminar (no se pausa, para no reanudar reproducciones que el usuario pausó).
 * El pedido de foco se marca como «sonido» y no como «voz»: con «voz» Android no atenúa solo y le
 * avisa a la app de música, que muchas veces se pausa (y Sara creía que no sonaba nada).
 */
class AudioFocusHelper(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * [pauseMusic] = la música se pausa (y sigue sola al terminar) en lugar de bajar: en el auto,
     * con el volumen alto, bajarla no alcanza para que se te escuche.
     */
    fun requestSpeech(pauseMusic: Boolean = false): AudioFocusRequest {
        val request = AudioFocusRequest.Builder(
            if (pauseMusic) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(request)
        return request
    }

    fun abandon(request: AudioFocusRequest?) {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
    }
}

/** Señal sonora breve (dos notas suaves) generada en memoria: sin archivos ni dependencias. */
class ChimePlayer {
    private val pcm: ShortArray by lazy { synthesize() }

    suspend fun play() = withContext(Dispatchers.Default) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.setVolume(0.6f)
            track.play()
            delay((pcm.size * 1000L / SAMPLE_RATE) + 40)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun synthesize(): ShortArray {
        val notes = listOf(880.0 to 0.09, 1318.5 to 0.14)
        val out = ArrayList<Short>()
        for ((frequency, seconds) in notes) {
            val count = (SAMPLE_RATE * seconds).toInt()
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = (i / (SAMPLE_RATE * 0.008)).coerceAtMost(1.0)
                val release = ((count - i) / (SAMPLE_RATE * 0.05)).coerceAtMost(1.0)
                val sample = sin(2 * PI * frequency * t) * 0.35 + sin(4 * PI * frequency * t) * 0.05
                out += (sample * attack * release * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
    }
}

class AndroidActivationFeedback(
    private val chime: ChimePlayer,
    private val focus: AudioFocusHelper,
    private val logger: SaraLogger,
    private val router: MicRouter? = null,
    /** Ajuste «Música mientras te escucho». */
    private val musicMode: () -> MusicWhileListening = { MusicWhileListening.DUCK },
) : ActivationFeedback {
    @Volatile private var request: AudioFocusRequest? = null

    override suspend fun chime() {
        runCatching { chime.play() }.onFailure { logger.w("Feedback", "No se pudo reproducir la señal", it) }
    }

    override fun interactionStarted() {
        val pause = when (musicMode()) {
            MusicWhileListening.DUCK -> false
            MusicWhileListening.PAUSE -> true
            MusicWhileListening.PAUSE_BLUETOOTH -> router?.bluetoothAudioConnected() == true
        }
        if (request == null) request = focus.requestSpeech(pause)
        router?.beginInteraction()
    }

    override fun interactionEnded() {
        router?.endInteraction()
        focus.abandon(request)
        request = null
    }
}

/** Volumen del canal multimedia (STREAM_MUSIC). No toca alarmas, llamadas ni notificaciones. */
class AndroidVolumeControl(context: Context) : VolumeControl {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun state(): VolumeState = VolumeState(
        current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
        min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC),
        max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        isFixed = audioManager.isVolumeFixed,
        isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC),
    )

    override fun setIndex(index: Int): Boolean = try {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        true
    } catch (e: SecurityException) {
        false
    }

    override fun setMuted(muted: Boolean): Boolean = try {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI,
        )
        true
    } catch (e: SecurityException) {
        false
    }
}
