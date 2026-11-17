package com.sara.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Audio PCM 16 bits mono ya sintetizado. */
class PcmClip(val pcm: ByteArray, val sampleRate: Int)

/**
 * Guarda el audio de frases cortas y repetidas («Te escucho») para reproducirlas al instante,
 * sin esperar a la red ni al motor de voz. Sólo contiene la voz de Sara, nunca audio del usuario.
 */
class PhraseCache(context: Context) {
    private val dir = File(context.filesDir, "voice_cache").apply { mkdirs() }
    private val memory = HashMap<String, PcmClip>()

    fun key(engineId: String, voice: String?, rate: Float, text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$engineId|$voice|$rate|$text".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(key: String): PcmClip? {
        memory[key]?.let { return it }
        val file = File(dir, "$key.pcm")
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            val rate = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            PcmClip(bytes.copyOfRange(4, bytes.size), rate)
        }.getOrNull()?.also { memory[key] = it }
    }

    @Synchronized
    fun put(key: String, clip: PcmClip) {
        if (clip.pcm.isEmpty()) return
        memory[key] = clip
        // Límite pequeño: si crece de más se vacía (se vuelve a generar sola).
        if ((dir.listFiles()?.sumOf { it.length() } ?: 0L) > MAX_DISK_BYTES) dir.listFiles()?.forEach { it.delete() }
        runCatching {
            File(dir, "$key.pcm").outputStream().use {
                it.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(clip.sampleRate).array())
                it.write(clip.pcm)
            }
        }
    }

    private companion object {
        const val MAX_DISK_BYTES = 4L * 1024 * 1024
    }
}

/** Reproduce un clip PCM completo (modo estático: arranca sin búfer de red). */
object PcmPlayer {
    suspend fun play(clip: PcmClip, volume: Float) = withContext(Dispatchers.Default) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(clip.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(clip.pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(clip.pcm, 0, clip.pcm.size)
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            val frames = clip.pcm.size / 2
            while (track.playbackHeadPosition < frames && track.playState == AudioTrack.PLAYSTATE_PLAYING) delay(15)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Extrae el PCM de un WAV generado por el motor TTS (busca el bloque "data"). */
    fun fromWav(file: File): PcmClip? = runCatching {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate = 0
        var bits = 16
        var channels = 1
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "fmt ") {
                channels = buffer.getShort(offset + 10).toInt()
                sampleRate = buffer.getInt(offset + 12)
                bits = buffer.getShort(offset + 22).toInt()
            } else if (id == "data") {
                if (bits != 16 || channels != 1 || sampleRate <= 0) return@runCatching null
                val end = minOf(bytes.size, offset + 8 + size)
                return@runCatching PcmClip(bytes.copyOfRange(offset + 8, end), sampleRate)
            }
            offset += 8 + size + (size and 1)
        }
        null
    }.getOrNull()
}
