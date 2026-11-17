package com.sara.assistant.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La esfera de Sara dibujada como arte ASCII: una grilla apretada de caracteres (« .:-=+*#%@») que
 * forman una esfera iluminada que gira. Toda la esfera es de un solo color que va recorriendo el
 * espectro (rojo → amarillo → verde → cian → azul → violeta…) y late con la voz: crece, se ilumina y
 * le corren ondas cuando hablás o cuando Sara habla.
 */
class AsciiSphere(
    /** Filas de la grilla: más filas = más «cerrada» (caracteres más chicos y juntos). */
    private val rows: Int = 40,
) {
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)

    /** Color de toda la esfera en este instante (un solo tono que va cambiando). */
    fun colorAt(time: Float, alpha: Int = 255): Int {
        hsv[0] = (time * HUE_SPEED) % 360f
        hsv[1] = 0.85f
        hsv[2] = 1f
        return android.graphics.Color.HSVToColor(alpha.coerceIn(0, 255), hsv)
    }

    /**
     * @param pulse voz (0..1) ya multiplicada por la energía del estado.
     * @param energy 0 = en reposo (tenue), 1 = escuchando o hablando.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, time: Float, pulse: Float, energy: Float) {
        if (radius <= 0f) return
        val r = radius * (1f + pulse * 0.14f + 0.012f * sin(time * 2f))

        // Resplandor del mismo color.
        glow.shader = RadialGradient(cx, cy, r * 1.45f, colorAt(time, (40 + 120 * pulse + 40 * energy).toInt()), 0, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.45f, glow)

        val cell = 2f * r / rows
        glyph.textSize = cell * 1.18f
        val baseline = glyph.textSize * 0.36f
        // Luz que gira alrededor de la esfera.
        val lightAngle = time * (0.5f + 0.8f * energy)
        val lx = cos(lightAngle) * 0.65f
        val ly = -0.45f
        val lz = sin(lightAngle) * 0.35f + 0.65f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val spin = time * (0.35f + 0.9f * pulse)
        val cosS = cos(spin)
        val sinS = sin(spin)

        for (row in 0 until rows) {
            val y = (row + 0.5f) / rows * 2f - 1f
            for (col in 0 until rows) {
                val x = (col + 0.5f) / rows * 2f - 1f
                val d2 = x * x + y * y
                if (d2 > 1f) continue
                val z = sqrt(1f - d2)
                // Luz difusa + un poco de ambiente.
                val light = max(0f, (x * lx + y * ly + z * lz) / ll) * 0.75f + 0.18f
                // Textura que gira con la esfera (bandas de «latitud/longitud»).
                val tx = x * cosS + z * sinS
                val tz = -x * sinS + z * cosS
                val bands = 0.5f + 0.5f * sin(tx * 7f + y * 3f + time * 0.8f) * cos(y * 6f - tz * 4f)
                // Ondas que salen del centro con la voz.
                val dist = sqrt(d2)
                val wave = pulse * (0.5f + 0.5f * sin(dist * 14f - time * 9f))
                val value = (light * (0.6f + 0.4f * bands) + wave * 0.55f) * (0.45f + 0.55f * energy)
                val index = (value * (RAMP.length - 1)).toInt().coerceIn(0, RAMP.length - 1)
                if (index == 0) continue
                glyph.color = colorAt(time, (70 + 185 * value.coerceIn(0f, 1f)).toInt())
                canvas.drawText(RAMP, index, index + 1, cx + x * r, cy + y * r + baseline, glyph)
            }
        }
    }

    private companion object {
        /** De vacío a lleno. */
        const val RAMP = " .:-=+*#%@"
        /** Grados de tono por segundo: una vuelta completa al espectro cada 10 s. */
        const val HUE_SPEED = 36f
    }
}
