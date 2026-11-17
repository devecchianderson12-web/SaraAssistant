package com.sara.lucy

import com.sara.core.util.AudioLevelMeter
import com.sara.lucy.audio.Microphone
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 48 → 16 kHz: la voz pasa entera y los agudos no se «doblan» sobre ella. */
class ResampleTest {
    private fun rmsAfter(freq: Double): Double {
        val mic = Microphone(AudioLevelMeter())
        val n = 4_800
        var last = 0.0
        repeat(3) { block ->
            val src = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = (sin(2 * PI * freq * (block * n + i) / 48_000.0) * 10_000).toInt()
                src[i * 2] = v.toByte(); src[i * 2 + 1] = (v shr 8).toByte()
            }
            val out = mic.downsample(src, src.size, 3, ByteArray(Microphone.CHUNK_BYTES))
            last = AudioLevelMeter.rmsOfPcm16(out, out.size)
        }
        return last
    }

    @Test fun keepsVoiceAndRemovesHighs() {
        val voice = rmsAfter(1_000.0)
        val high = rmsAfter(15_000.0)
        assertTrue("voz $voice", voice > 6_500)
        assertTrue("agudos $high", high < 300)
    }
}
