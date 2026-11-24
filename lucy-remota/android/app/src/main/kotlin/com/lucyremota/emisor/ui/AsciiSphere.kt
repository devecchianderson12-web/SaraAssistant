package com.lucyremota.emisor.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * La burbuja de Lucy: un ANILLO (dona) en arte ASCII que gira en 3D, en tonos fucsia → dorado, con
 * «L U C Y» escrito debajo. A propósito no se parece en nada a la esfera de Sara (redonda, maciza y
 * con todos los colores del arcoíris): de un vistazo se sabe cuál de las dos te está escuchando.
 * Late con la voz: crece, gira más rápido y se ilumina cuando hablás o cuando Lucy habla.
 */
class AsciiSphere(
    /** Filas de la grilla: más filas = caracteres más chicos y juntos. */
    private val rows: Int = 40,
    /** Escribir «L U C Y» debajo del anillo. */
    private val showName: Boolean = true,
) {
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depth = FloatArray(rows * rows)
    private val light = FloatArray(rows * rows)
    private val tone = FloatArray(rows * rows)

    /** Color de Lucy en este instante: va y viene entre fucsia y dorado (nunca el arcoíris de Sara). */
    fun colorAt(time: Float, alpha: Int = 255, shift: Float = 0f): Int {
        val k = (0.5f + 0.5f * sin(time * 0.9f + shift * 3f)).coerceIn(0f, 1f)
        return android.graphics.Color.argb(alpha.coerceIn(0, 255), mix(FUCHSIA_R, GOLD_R, k), mix(FUCHSIA_G, GOLD_G, k), mix(FUCHSIA_B, GOLD_B, k))
    }

    private fun mix(a: Int, b: Int, k: Float) = (a + (b - a) * k).toInt()

    /**
     * @param pulse voz (0..1) ya multiplicada por la energía del estado.
     * @param energy 0 = en reposo (tenue), 1 = escuchando o hablando.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, time: Float, pulse: Float, energy: Float) {
        if (radius <= 0f) return
        val r = radius * (1f + pulse * 0.16f + 0.015f * sin(time * 2.2f))
        val ringCy = if (showName) cy - r * 0.12f else cy

        glow.shader = RadialGradient(cx, ringCy, r * 1.5f, colorAt(time, (50 + 120 * pulse + 40 * energy).toInt()), 0, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, ringCy, r * 1.5f, glow)

        // Anillo 3D (como el clásico «donut» ASCII): se proyecta a una grilla con profundidad.
        java.util.Arrays.fill(depth, 0f)
        val a = time * (0.55f + 1.1f * energy + 1.4f * pulse)
        val b = time * (0.3f + 0.6f * energy)
        val cosA = cos(a); val sinA = sin(a); val cosB = cos(b); val sinB = sin(b)
        val k2 = 5f
        val k1 = rows * k2 * 3f / (8f * (TUBE + RING))
        var theta = 0f
        while (theta < TWO_PI) {
            val ct = cos(theta); val st = sin(theta)
            var phi = 0f
            while (phi < TWO_PI) {
                val cp = cos(phi); val sp = sin(phi)
                val circleX = RING + TUBE * ct
                val circleY = TUBE * st
                val x = circleX * (cosB * cp + sinA * sinB * sp) - circleY * cosA * sinB
                val y = circleX * (sinB * cp - sinA * cosB * sp) + circleY * cosA * cosB
                val z = k2 + cosA * circleX * sp + circleY * sinA
                val ooz = 1f / z
                val col = (rows / 2f + k1 * ooz * x).toInt()
                val row = (rows / 2f - k1 * ooz * y).toInt()
                if (col in 0 until rows && row in 0 until rows) {
                    val i = row * rows + col
                    if (ooz > depth[i]) {
                        depth[i] = ooz
                        light[i] = cp * ct * sinB - cosA * ct * sp - sinA * st + cosB * (cosA * st - ct * sinA * sp)
                        tone[i] = phi / TWO_PI
                    }
                }
                phi += 0.045f
            }
            theta += 0.11f
        }

        val cell = 2f * r / rows
        glyph.textSize = cell * 1.25f
        val baseline = glyph.textSize * 0.36f
        val dim = 0.5f + 0.5f * energy
        for (row in 0 until rows) {
            for (col in 0 until rows) {
                val i = row * rows + col
                if (depth[i] == 0f) continue
                val l = light[i]
                // Ondas que recorren el anillo con la voz.
                val wave = pulse * (0.5f + 0.5f * sin(tone[i] * 18f - time * 10f))
                val value = ((l.coerceAtLeast(0f) / 1.42f) * 0.8f + 0.2f + wave * 0.45f).coerceIn(0f, 1f) * dim
                val index = (value * (RAMP.length - 1)).toInt().coerceIn(1, RAMP.length - 1)
                glyph.color = colorAt(time, (90 + 165 * value).toInt(), tone[i])
                val x = cx + ((col + 0.5f) / rows * 2f - 1f) * r
                val y = ringCy + ((row + 0.5f) / rows * 2f - 1f) * r
                canvas.drawText(RAMP, index, index + 1, x, y + baseline, glyph)
            }
        }

        if (showName) {
            name.textSize = r * 0.26f
            name.color = colorAt(time + 1.5f, (170 + 85 * energy).toInt())
            name.setShadowLayer(r * 0.08f, 0f, 0f, colorAt(time, 200))
            canvas.drawText("L U C Y", cx, ringCy + r * 1.12f, name)
        }
    }

    private companion object {
        /** De tenue a brillante (la rampa del «donut» ASCII clásico). */
        const val RAMP = " .,-~:;=!*#$@"
        const val TWO_PI = 6.2831855f
        const val TUBE = 1f
        const val RING = 2f
        const val FUCHSIA_R = 0xFF; const val FUCHSIA_G = 0x2E; const val FUCHSIA_B = 0xA6
        const val GOLD_R = 0xFF; const val GOLD_G = 0xB4; const val GOLD_B = 0x2E
    }
}
