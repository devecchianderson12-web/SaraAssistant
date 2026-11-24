package com.lucyremota.emisor.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View

/**
 * Esfera de Sara para la capa que aparece al llamarla: arte ASCII de un solo color que recorre el
 * espectro (ver [AsciiSphere]) y late con el volumen de la voz. Dibujada con Canvas, sin archivos.
 */
class SphereView(context: Context) : View(context) {
    /** Volumen de la voz (0..1), suavizado. */
    @Volatile var level: Float = 0f

    /** 0 = esperando, 1 = escuchando o hablando a pleno. */
    @Volatile var energy: Float = 1f

    private var smooth = 0f
    private val start = SystemClock.uptimeMillis()
    private val ascii = AsciiSphere(rows = 44)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        smooth += (level.coerceIn(0f, 1f) - smooth) * 0.25f
        val pulse = (smooth * energy).coerceIn(0f, 1f)
        ascii.draw(canvas, width / 2f, height / 2f, minOf(width, height) * 0.36f, t, pulse, energy)
        postInvalidateOnAnimation()
    }
}
