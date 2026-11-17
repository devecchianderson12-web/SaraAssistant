package com.sara.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Esfera de Sara para la capa que aparece al llamarla: una cáscara de partículas conectadas que gira
 * en 3D (como una red de puntos luminosos) con cintas de luz que fluyen adentro (como seda dentro
 * de un cristal). Los colores recorren el espectro (RGB) y todo late con el volumen de la voz.
 * Dibujada con Canvas: no usa videos ni archivos externos.
 */
class SphereView(context: Context) : View(context) {
    /** Volumen de la voz (0..1), suavizado. */
    @Volatile var level: Float = 0f

    /** 0 = esperando, 1 = escuchando o hablando a pleno. */
    @Volatile var energy: Float = 1f

    private var smooth = 0f
    private val start = SystemClock.uptimeMillis()

    // Puntos de la cáscara (distribución de Fibonacci) y sus vecinos para las conexiones.
    private val count = 360
    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val pz = FloatArray(count)
    private val links: List<Pair<Int, Int>>

    // Proyección por cuadro (reutilizada para no crear objetos al dibujar).
    private val sx = FloatArray(count)
    private val sy = FloatArray(count)
    private val sd = FloatArray(count)

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val hsv = FloatArray(3)

    init {
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1 - (i / (count - 1f)) * 2
            val r = sqrt(1 - y * y)
            val theta = golden * i
            px[i] = (cos(theta) * r).toFloat()
            py[i] = y
            pz[i] = (sin(theta) * r).toFloat()
        }
        // Cada punto se une con sus 2 vecinos más cercanos (red luminosa).
        val pairs = mutableSetOf<Pair<Int, Int>>()
        for (i in 0 until count) {
            val nearest = (0 until count).filter { it != i }
                .sortedBy { j -> (px[i] - px[j]).let { it * it } + (py[i] - py[j]).let { it * it } + (pz[i] - pz[j]).let { it * it } }
                .take(2)
            nearest.forEach { j -> pairs += if (i < j) i to j else j to i }
        }
        links = pairs.toList()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun hue(h: Float, s: Float, v: Float, alpha: Int): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f
        hsv[1] = s
        hsv[2] = v
        return Color.HSVToColor(alpha.coerceIn(0, 255), hsv)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        smooth += (level.coerceIn(0f, 1f) - smooth) * 0.25f
        val pulse = (smooth * energy).coerceIn(0f, 1f)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.30f * (1f + pulse * 0.18f + 0.015f * sin(t * 2f))
        val baseHue = t * 45f // ciclo RGB completo cada 8 s

        // Resplandor detrás de la esfera.
        glow.shader = RadialGradient(
            cx, cy, radius * 1.9f,
            intArrayOf(hue(baseHue, 0.9f, 1f, (70 + 110 * pulse).toInt()), hue(baseHue + 60, 0.9f, 0.6f, 40), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius * 1.9f, glow)

        // Rotación 3D de la cáscara.
        val ay = t * (0.35f + 0.6f * pulse)
        val ax = 0.35f * sin(t * 0.4f)
        val cosY = cos(ay); val sinY = sin(ay); val cosX = cos(ax); val sinX = sin(ax)
        for (i in 0 until count) {
            // Las partículas se separan un poco con la voz.
            val wobble = 1f + pulse * 0.10f * sin(t * 6f + i * 0.37f)
            val x1 = px[i] * cosY + pz[i] * sinY
            val z1 = -px[i] * sinY + pz[i] * cosY
            val y2 = py[i] * cosX - z1 * sinX
            val z2 = py[i] * sinX + z1 * cosX
            val persp = 1f / (1.9f - z2 * 0.6f)
            sx[i] = cx + x1 * radius * wobble * persp * 1.3f
            sy[i] = cy + y2 * radius * wobble * persp * 1.3f
            sd[i] = (z2 + 1f) / 2f // 0 = atrás, 1 = adelante
        }
        for ((a, b) in links) {
            val depth = (sd[a] + sd[b]) / 2f
            line.color = hue(baseHue + 180f * depth, 0.75f, 1f, (20 + 90 * depth * (0.6f + 0.4f * energy)).toInt())
            canvas.drawLine(sx[a], sy[a], sx[b], sy[b], line)
        }
        for (i in 0 until count) {
            val depth = sd[i]
            dot.color = hue(baseHue + 180f * depth + i * 0.3f, 0.6f, 1f, (60 + 195 * depth).toInt())
            canvas.drawCircle(sx[i], sy[i], (0.8f + 2.2f * depth) * (1f + pulse * 0.5f) * resources.displayMetrics.density * 0.6f, dot)
        }

        // Cintas de luz que fluyen adentro.
        val inner = radius * 0.78f
        for (r in 0 until RIBBONS) {
            val phase = t * (0.6f + r * 0.17f) + r * 2.1f
            val tilt = r * 1.05f + t * 0.25f
            val cosT = cos(tilt); val sinT = sin(tilt)
            val amplitude = 0.28f + 0.22f * pulse + 0.06f * sin(t + r)
            for (strand in 0 until STRANDS) {
                val offset = (strand - STRANDS / 2f) * (0.022f + 0.01f * pulse)
                path.reset()
                for (k in 0..SEGMENTS) {
                    val th = (k / SEGMENTS.toFloat()) * 2f * PI.toFloat()
                    var x = cos(th)
                    var y = sin(th * 2f + phase) * amplitude + offset * cos(th * 3f + phase)
                    var z = sin(th)
                    // Inclinación propia de cada cinta.
                    val y1 = y * cosT - z * sinT
                    val z1 = y * sinT + z * cosT
                    y = y1; z = z1
                    val x2 = x * cosY - z * sinY
                    x = x2
                    val px2 = cx + x * inner
                    val py2 = cy + y * inner
                    if (k == 0) path.moveTo(px2, py2) else path.lineTo(px2, py2)
                }
                val edge = 1f - abs(strand - STRANDS / 2f) / (STRANDS / 2f)
                ribbon.color = hue(baseHue + 90f + r * 70f + strand * 3f, 0.7f, 1f, (25 + 110 * edge * (0.5f + 0.5f * energy)).toInt())
                canvas.drawPath(path, ribbon)
            }
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        const val RIBBONS = 3
        const val STRANDS = 12
        const val SEGMENTS = 56
    }
}
