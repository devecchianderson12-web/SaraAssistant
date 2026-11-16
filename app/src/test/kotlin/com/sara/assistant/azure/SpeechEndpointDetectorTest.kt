package com.sara.assistant.azure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechEndpointDetectorTest {
    private fun chunk(amplitude: Int): ByteArray {
        val out = ByteArray(3200)
        for (i in 0 until 1600) {
            val s = (sin(2 * PI * 220 * i / 16000.0) * amplitude).toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `corta tras silencio posterior a la voz`() {
        val d = SpeechEndpointDetector(noSpeechTimeoutMillis = 5000, maxSpeechMillis = 10000)
        repeat(3) { assertTrue(d.feed(chunk(50), 3200)) }
        repeat(8) { assertTrue(d.feed(chunk(8000), 3200)) }
        assertTrue(d.speechDetected)
        var continues = true
        var chunks = 0
        while (continues && chunks < 30) {
            continues = d.feed(chunk(50), 3200)
            chunks++
        }
        assertFalse(continues)
        assertTrue(chunks in 8..10) // ~900 ms de silencio
    }

    @Test
    fun `sin voz termina por tiempo de espera`() {
        val d = SpeechEndpointDetector(noSpeechTimeoutMillis = 1000, maxSpeechMillis = 10000)
        var count = 0
        while (d.feed(chunk(40), 3200)) count++
        assertFalse(d.speechDetected)
        assertTrue(count in 8..10)
    }

    @Test
    fun `limite maximo de grabacion`() {
        val d = SpeechEndpointDetector(noSpeechTimeoutMillis = 1000, maxSpeechMillis = 2000)
        var count = 0
        while (d.feed(chunk(9000), 3200)) count++
        assertTrue(d.speechDetected) // voz desde el primer bloque
        assertTrue(count <= 30)
    }
}
