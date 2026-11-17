package com.sara.lucy.ui

import com.sara.lucy.data.SettingsRepository
import java.awt.AlphaComposite
import java.awt.BasicStroke
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
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.abs
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

    private val count = 260
    private val px = DoubleArray(count)
    private val py = DoubleArray(count)
    private val pz = DoubleArray(count)
    private val links: List<Pair<Int, Int>>
    private val start = System.nanoTime()
    private var smooth = 0.0

    init {
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1 - i / (count - 1.0) * 2
            val r = sqrt(1 - y * y)
            px[i] = cos(golden * i) * r
            py[i] = y
            pz[i] = sin(golden * i) * r
        }
        val pairs = HashSet<Pair<Int, Int>>()
        for (i in 0 until count) {
            (0 until count).filter { it != i }
                .sortedBy { j -> sq(px[i] - px[j]) + sq(py[i] - py[j]) + sq(pz[i] - pz[j]) }.take(2)
                .forEach { j -> pairs += if (i < j) i to j else j to i }
        }
        links = pairs.toList()
    }

    private fun sq(v: Double) = v * v

    private fun hsv(h: Double, s: Double, v: Double, a: Double): Color {
        val c = Color.getHSBColor((((h % 360) + 360) % 360 / 360).toFloat(), s.toFloat(), v.toFloat())
        return Color(c.red, c.green, c.blue, (a.coerceIn(0.0, 1.0) * 255).toInt())
    }

    fun paint(g: Graphics2D, w: Int, h: Int, time: Double = (System.nanoTime() - start) / 1e9) {
        if (w <= 0 || h <= 0) return
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val t = time
        val target = if (speaking) max(level.toDouble(), 0.35 + 0.25 * sin(t * 9) * sin(t * 3.3)) else level.toDouble()
        smooth += (target.coerceIn(0.0, 1.0) - smooth) * 0.25
        val pulse = (smooth * energy).coerceIn(0.0, 1.0)
        val cx = w / 2.0
        val cy = h / 2.0
        val radius = min(w, h) * 0.30 * (1 + pulse * 0.18 + 0.015 * sin(t * 2))
        val hue = t * 45

        // Resplandor.
        val glowR = (radius * 1.6).toFloat()
        g.paint = RadialGradientPaint(
            Point2D.Double(cx, cy), glowR, floatArrayOf(0f, 1f),
            arrayOf(hsv(hue, 0.9, 1.0, 0.25 + 0.4 * pulse), Color(0, 0, 0, 0)), MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fill(Ellipse2D.Double(cx - glowR, cy - glowR, glowR * 2.0, glowR * 2.0))

        // Cáscara de partículas que gira.
        val ay = t * (0.35 + 0.6 * pulse)
        val ax = 0.35 * sin(t * 0.4)
        val cosY = cos(ay); val sinY = sin(ay); val cosX = cos(ax); val sinX = sin(ax)
        val sx = DoubleArray(count); val sy = DoubleArray(count); val sd = DoubleArray(count)
        for (i in 0 until count) {
            val wob = 1 + pulse * 0.10 * sin(t * 6 + i * 0.37)
            val x1 = px[i] * cosY + pz[i] * sinY
            val z1 = -px[i] * sinY + pz[i] * cosY
            val y2 = py[i] * cosX - z1 * sinX
            val z2 = py[i] * sinX + z1 * cosX
            val persp = 1 / (1.9 - z2 * 0.6)
            sx[i] = cx + x1 * radius * wob * persp * 1.3
            sy[i] = cy + y2 * radius * wob * persp * 1.3
            sd[i] = (z2 + 1) / 2
        }
        g.stroke = BasicStroke(0.8f)
        val line = Line2D.Double()
        for ((a, b) in links) {
            val depth = (sd[a] + sd[b]) / 2
            g.color = hsv(hue + 180 * depth, 0.75, 1.0, (0.08 + 0.35 * depth) * (0.6 + 0.4 * energy))
            line.setLine(sx[a], sy[a], sx[b], sy[b])
            g.draw(line)
        }
        val dot = Ellipse2D.Double()
        for (i in 0 until count) {
            val depth = sd[i]
            g.color = hsv(hue + 180 * depth + i * 0.3, 0.6, 1.0, 0.25 + 0.75 * depth)
            val size = (0.5 + 1.4 * depth) * (1 + pulse * 0.5) * max(1.0, min(w, h) / 170.0)
            dot.setFrame(sx[i] - size, sy[i] - size, size * 2, size * 2)
            g.fill(dot)
        }

        // Cintas de luz adentro.
        val inner = radius * 0.78
        g.stroke = BasicStroke(1.1f * max(1f, min(w, h) / 170f))
        for (r in 0 until 3) {
            val phase = t * (0.6 + r * 0.17) + r * 2.1
            val tilt = r * 1.05 + t * 0.25
            val cosT = cos(tilt); val sinT = sin(tilt)
            val amp = 0.28 + 0.22 * pulse + 0.06 * sin(t + r)
            for (strand in 0 until 8) {
                val offset = (strand - 4) * (0.025 + 0.01 * pulse)
                val path = Path2D.Double()
                for (k in 0..40) {
                    val th = k / 40.0 * 2 * PI
                    val x = cos(th)
                    val y = sin(th * 2 + phase) * amp + offset * cos(th * 3 + phase)
                    val z = sin(th)
                    val y1 = y * cosT - z * sinT
                    val z1 = y * sinT + z * cosT
                    val x2 = x * cosY - z1 * sinY
                    if (k == 0) path.moveTo(cx + x2 * inner, cy + y1 * inner) else path.lineTo(cx + x2 * inner, cy + y1 * inner)
                }
                val edge = 1 - abs(strand - 4) / 4.0
                g.color = hsv(hue + 90 + r * 70 + strand * 3, 0.7, 1.0, (0.1 + 0.45 * edge) * (0.5 + 0.5 * energy))
                g.draw(path)
            }
        }
    }

    companion object {
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
