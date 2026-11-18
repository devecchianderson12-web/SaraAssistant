package com.sara.lucy.ui

import com.sara.lucy.data.SettingsRepository
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MultipleGradientPaint
import java.awt.Point
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La esfera de Sara/Lucy: cáscara de partículas conectadas que gira en 3D, cintas de luz adentro,
 * colores que recorren el espectro y que late con la voz.
 */
class SpherePainter {
    @Volatile var level = 0f
    @Volatile var energy = 0.35f
    @Volatile var speaking = false

    private val start = System.nanoTime()
    private var smooth = 0.0
    private val rows = 38

    /** Toda la esfera es de un solo color que recorre el espectro (una vuelta cada 10 s). */
    private fun hue(t: Double, alpha: Double): Color {
        val c = Color.getHSBColor(((t * 36) % 360 / 360).toFloat(), 0.85f, 1f)
        return Color(c.red, c.green, c.blue, (alpha.coerceIn(0.0, 1.0) * 255).toInt())
    }

    /**
     * La misma esfera de Sara en Android: arte ASCII apretado (« .:-=+*#%@») que forma una esfera
     * iluminada que gira, de un solo color RGB que va cambiando, y que late con la voz.
     */
    fun paint(g: Graphics2D, w: Int, h: Int, time: Double = (System.nanoTime() - start) / 1e9) {
        if (w <= 0 || h <= 0) return
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val t = time
        val target = if (speaking) max(level.toDouble(), 0.35 + 0.25 * sin(t * 9) * sin(t * 3.3)) else level.toDouble()
        smooth += (target.coerceIn(0.0, 1.0) - smooth) * 0.25
        val pulse = (smooth * energy).coerceIn(0.0, 1.0)
        val cx = w / 2.0
        val cy = h / 2.0
        val r = min(w, h) * 0.36 * (1 + pulse * 0.14 + 0.012 * sin(t * 2))

        val glowR = (r * 1.45).toFloat()
        g.paint = RadialGradientPaint(
            Point2D.Double(cx, cy), glowR, floatArrayOf(0f, 1f),
            arrayOf(hue(t, (40 + 120 * pulse + 40 * energy) / 255), Color(0, 0, 0, 0)), MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fill(Ellipse2D.Double(cx - glowR, cy - glowR, glowR * 2.0, glowR * 2.0))

        val cell = 2 * r / rows
        g.font = Font(Font.MONOSPACED, Font.BOLD, max(4, (cell * 1.18).toInt()))
        val fm = g.fontMetrics
        val lightAngle = t * (0.5 + 0.8 * energy)
        val lx = cos(lightAngle) * 0.65
        val ly = -0.45
        val lz = sin(lightAngle) * 0.35 + 0.65
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val spin = t * (0.35 + 0.9 * pulse)
        val cosS = cos(spin)
        val sinS = sin(spin)
        for (row in 0 until rows) {
            val y = (row + 0.5) / rows * 2 - 1
            for (col in 0 until rows) {
                val x = (col + 0.5) / rows * 2 - 1
                val d2 = x * x + y * y
                if (d2 > 1) continue
                val z = sqrt(1 - d2)
                val light = max(0.0, (x * lx + y * ly + z * lz) / ll) * 0.75 + 0.18
                val tx = x * cosS + z * sinS
                val tz = -x * sinS + z * cosS
                val bands = 0.5 + 0.5 * sin(tx * 7 + y * 3 + t * 0.8) * cos(y * 6 - tz * 4)
                val wave = pulse * (0.5 + 0.5 * sin(sqrt(d2) * 14 - t * 9))
                val value = (light * (0.6 + 0.4 * bands) + wave * 0.55) * (0.45 + 0.55 * energy)
                val index = (value * (RAMP.length - 1)).toInt().coerceIn(0, RAMP.length - 1)
                if (index == 0) continue
                g.color = hue(t, (70 + 185 * value.coerceIn(0.0, 1.0)) / 255)
                val ch = RAMP[index].toString()
                g.drawString(ch, (cx + x * r - fm.stringWidth(ch) / 2.0).toFloat(), (cy + y * r + fm.ascent * 0.36).toFloat())
            }
        }
    }

    companion object {
        /** De vacío a lleno. */
        private const val RAMP = " .:-=+*#%@"

        /** Ícono (ventana, bandeja, .exe): la esfera sobre un círculo oscuro. */
        fun icon(size: Int): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(8, 6, 20)
            g.fillOval(0, 0, size, size)
            if (size <= 32) {
                // A tamaño chico, una esfera de color se ve mejor que las partículas.
                val r = size / 2f
                g.paint = RadialGradientPaint(
                    Point2D.Float(r * 0.8f, r * 0.75f), r * 0.85f, floatArrayOf(0f, 0.55f, 1f),
                    arrayOf(Color(255, 255, 255), Color(170, 90, 255), Color(30, 140, 255, 0)),
                )
                g.fillOval((size * 0.12).toInt(), (size * 0.12).toInt(), (size * 0.76).toInt(), (size * 0.76).toInt())
            } else {
                // La esfera ocupa un tercio del área que se le da: se dibuja más grande y centrada.
                val big = (size * 1.45).toInt()
                g.translate((size - big) / 2, (size - big) / 2)
                SpherePainter().apply { energy = 1f; level = 0.6f }.paint(g, big, big, 1.3)
            }
            g.dispose()
            return img
        }
    }
}

/** Componente que anima la esfera mientras está visible. */
class SphereComponent(val painter: SpherePainter = SpherePainter()) : JComponent() {
    private val timer = Timer(16) { if (isShowing) repaint() }

    init {
        isOpaque = false
        timer.start()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            painter.paint(g2, width, height)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * La esfera flotante: ventana sin bordes, transparente, siempre arriba; por defecto en la esquina
 * superior derecha. Se arrastra para moverla y con doble clic vuelve a la esquina.
 */
class SphereWindow(private val settings: SettingsRepository, private val onClick: () -> Unit) : JWindow() {
    val sphere = SphereComponent()
    @Volatile private var caption = ""
    private var dragFrom: Point? = null
    private var dragged = false

    init {
        isAlwaysOnTop = true
        focusableWindowState = false
        type = Type.UTILITY
        val translucent = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }.getOrDefault(false)
        background = if (translucent) Color(0, 0, 0, 0) else Color(10, 8, 22)
        val panel = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.composite = AlphaComposite.SrcOver
                    val size = width
                    sphere.painter.paint(g2, size, size)
                    val text = caption
                    if (text.isNotEmpty()) {
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                        g2.font = Font("Segoe UI", Font.PLAIN, 12)
                        val shown = ellipsize(g2, text, width - 12)
                        val fm = g2.fontMetrics
                        val x = (width - fm.stringWidth(shown)) / 2
                        val y = size + 18
                        g2.color = Color(0, 0, 0, 150)
                        g2.fillRoundRect(x - 6, y - fm.ascent - 2, fm.stringWidth(shown) + 12, fm.height + 4, 10, 10)
                        g2.color = Color(255, 255, 255, 230)
                        g2.drawString(shown, x, y)
                    }
                } finally {
                    g2.dispose()
                }
            }
        }
        panel.isOpaque = false
        contentPane = panel
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragFrom = e.point
                dragged = false
            }
            override fun mouseDragged(e: MouseEvent) {
                val from = dragFrom ?: return
                dragged = true
                setLocation(e.xOnScreen - from.x, e.yOnScreen - from.y)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (dragged) settings.update { it.copy(sphereX = x, sphereY = y) }
                dragFrom = null
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) resetPosition() else if (!dragged) onClick()
            }
        }
        panel.addMouseListener(mouse)
        panel.addMouseMotionListener(mouse)
        Timer(16) { if (isVisible) panel.repaint() }.start()
        applySize()
    }

    private fun ellipsize(g: Graphics2D, text: String, max: Int): String {
        if (g.fontMetrics.stringWidth(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && g.fontMetrics.stringWidth("…$t") > max) t = t.drop(1)
        return "…$t"
    }

    fun applySize() {
        val s = settings.current.sphereSize
        setSize(s, s + 30)
        place()
    }

    private fun place() {
        val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = settings.current.sphereX
        val y = settings.current.sphereY
        if (x != null && y != null && x > area.x - width / 2 && x < area.x + area.width - width / 2 && y > area.y - 10 && y < area.y + area.height - height / 2) {
            setLocation(x, y)
        } else {
            setLocation(area.x + area.width - width - 16, area.y + 16)
        }
    }

    fun resetPosition() {
        settings.update { it.copy(sphereX = null, sphereY = null) }
        place()
    }

    fun setCaption(text: String) {
        caption = text
    }
}
