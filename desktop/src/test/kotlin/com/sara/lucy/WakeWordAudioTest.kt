package com.sara.lucy

import com.sara.core.engine.WakeWordConfig
import com.sara.core.engine.WakeWordResult
import com.sara.core.util.SaraLogger
import com.sara.core.util.WakeWordTuning
import com.sara.lucy.audio.AudioSource
import com.sara.lucy.audio.CaptureOutcome
import com.sara.lucy.audio.Microphone
import com.sara.lucy.speech.VoskModels
import com.sara.lucy.speech.VoskWakeWordEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * La palabra «Lucy» con audio de verdad y el modelo de voz real (sólo corre si se indica dónde está
 * el modelo con -PlucyModel=…; en CI, en Windows): se activa al llamarla, también con música, y no
 * se activa con charla ni con una canción.
 */
class WakeWordAudioTest {
    private val modelDir = System.getProperty("lucy.model")?.takeIf { it.isNotBlank() && File(it).isDirectory }

    @Before fun setUp() {
        assumeTrue("sin modelo de voz", modelDir != null)
        System.setProperty("jna.encoding", "UTF-8")
        LucyIdentity.apply()
    }

    private class FileSource(private val pcm: ByteArray) : AudioSource {
        var pos = 0
        override suspend fun capture(onChunk: (ByteArray, Int) -> Boolean): CaptureOutcome {
            while (pos + Microphone.CHUNK_BYTES <= pcm.size) {
                val chunk = pcm.copyOfRange(pos, pos + Microphone.CHUNK_BYTES)
                pos += Microphone.CHUNK_BYTES
                if (!onChunk(chunk, chunk.size)) break
            }
            return CaptureOutcome.Completed(false)
        }
    }

    private fun activations(name: String): Int {
        val wav = javaClass.getResourceAsStream("/voz/$name")!!.readBytes()
        val source = FileSource(wav.copyOfRange(44, wav.size))
        val engine = VoskWakeWordEngine(VoskModels(SaraLogger.None).also { System.setProperty("lucy.model", modelDir!!) }, source, SaraLogger.None)
        var count = 0
        runBlocking {
            while (source.pos + Microphone.CHUNK_BYTES <= wav.size - 44) {
                if (engine.awaitWakeWord(WakeWordConfig(WakeWordTuning.DEFAULT_SENSITIVITY, requirePrefix = false)) is WakeWordResult.Detected) {
                    count++
                    // Como en la app: después de activarse atiende la orden y recién después vuelve a esperar.
                    source.pos += Microphone.CHUNK_BYTES * 20
                } else break
            }
        }
        return count
    }

    @Test fun wakesWhenCalled() {
        for (f in listOf("lucy-1.wav", "lucy-2.wav", "lucy-pone-musica.wav", "lucy-con-musica.wav")) assertEquals(f, 1, activations(f))
    }

    @Test fun ignoresTalkAndSongs() {
        assertEquals("charla", 0, activations("charla.wav"))
        assertEquals("canción", 0, activations("cancion.wav"))
    }
}
