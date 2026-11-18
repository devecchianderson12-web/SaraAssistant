package com.sara.lucy

import com.sara.lucy.audio.BoostedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MicBoostTest {
    private fun tone(amplitude: Int, n: Int = 1600) = ByteArray(n * 2).also { b ->
        for (i in 0 until n) {
            val v = (sin(2 * PI * 300 * i / 16000.0) * amplitude).toInt()
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
    }

    @Test
    fun `sube la voz baja sin saturar ni subir el ruido`() {
        val agc = BoostedSource.AutoGain()
        // Ruido de fondo bajo un rato: no lo sube más que hasta ~1 %.
        repeat(30) { agc.next(tone(60), 3200) }
        val forNoise = agc.next(tone(60), 3200)
        assertTrue("ruido ×$forNoise", forNoise * 60 * 0.707 <= 400)
        // Voz baja (pico 1500): la sube varias veces.
        val forVoice = agc.next(tone(1500), 3200)
        assertTrue("voz ×$forVoice", forVoice >= 1f)
        // Voz fuerte: no la toca.
        repeat(5) { agc.next(tone(20000), 3200) }
        assertEquals(1f, agc.next(tone(20000), 3200), 0.01f)
    }

    @Test
    fun `el limitador no recorta`() {
        val src = tone(20000)
        val out = ByteArray(src.size)
        BoostedSource.boost(src, src.size, 8f, out)
        var max = 0
        for (i in 0 until out.size / 2) max = maxOf(max, kotlin.math.abs(((out[2 * i + 1].toInt() shl 8) or (out[2 * i].toInt() and 0xFF)).toShort().toInt()))
        assertTrue(max in 25_000..32_767)
    }
}
