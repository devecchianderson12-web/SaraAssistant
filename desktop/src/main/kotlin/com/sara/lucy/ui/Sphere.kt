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
 * La burbuja de Lucy (la misma que en el teléfono): un ANILLO en arte ASCII que gira en 3D, en tonos
 * fucsia → dorado, con «L U C Y» escrito debajo. Late con la voz: crece, gira más rápido y se
 * ilumina cuando hablás o cuando Lucy habla.
 */
class SpherePainter(private val showName: Boolean = true) {
    @Volatile var level = 0f
    @Volatile var energy = 0.35f
    @Volatile var speaking = false

    private val start = System.nanoTime()
    private var smooth = 0.0
    private val rows = 40
    private val depth = DoubleArray(rows * rows)
    private val light = DoubleArray(rows * rows)
    private val tone = DoubleArray(rows * rows)

    /** Va y viene entre fucsia y dorado (nunca el arcoíris de Sara). */
    private fun color(t: Double, alpha: Double, shift: Double = 0.0): Color {
        val k = (0.5 + 0.5 * sin(t * 0.9 + shift * 3)).coerceIn(0.0, 1.0)
        fun mix(a: Int, b: Int) = (a + (b - a) * k).toInt()
        return Color(mix(0xFF, 0xFF), mix(0x2E, 0xB4), mix(0xA6, 0x2E), (alpha.coerceIn(0.0, 1.0) * 255).toInt())
    }

    fun paint(g: Graphics2D, w: Int, h: Int, time: Double = (System.nanoTime() - start) / 1e9, tilt: Double? = null, turn: Double? = null) {
        if (w <= 0 || h <= 0) return
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val t = time
        val target = if (speaking) max(level.toDouble(), 0.35 + 0.25 * sin(t * 9) * sin(t * 3.3)) else level.toDouble()
        smooth += (target.coerceIn(0.0, 1.0) - smooth) * 0.25
        val pulse = (smooth * energy).coerceIn(0.0, 1.0)
        val cx = w / 2.0
        val r = min(w, h) * 0.34 * (1 + pulse * 0.16 + 0.015 * sin(t * 2.2))
        val cy = if (showName) h / 2.0 - r * 0.14 else h / 2.0

        val glowR = (r * 1.5).toFloat()
        g.paint = RadialGradientPaint(
            Point2D.Double(cx, cy), glowR, floatArrayOf(0f, 1f),
            arrayOf(color(t, (50 + 120 * pulse + 40 * energy) / 255), Color(0, 0, 0, 0)), MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fill(Ellipse2D.Double(cx - glowR, cy - glowR, glowR * 2.0, glowR * 2.0))

        java.util.Arrays.fill(depth, 0.0)
        val a = tilt ?: (t * (0.55 + 1.1 * energy + 1.4 * pulse))
        val b = turn ?: (t * (0.3 + 0.6 * energy))
        val cosA = cos(a); val sinA = sin(a); val cosB = cos(b); val sinB = sin(b)
        val k2 = 5.0
        val k1 = rows * k2 * 3 / (8 * (TUBE + RING))
        var theta = 0.0
        while (theta < TWO_PI) {
            val ct = cos(theta); val st = sin(theta)
            var phi = 0.0
            while (phi < TWO_PI) {
                val cp = cos(phi); val sp = sin(phi)
                val circleX = RING + TUBE * ct
                val circleY = TUBE * st
                val x = circleX * (cosB * cp + sinA * sinB * sp) - circleY * cosA * sinB
                val y = circleX * (sinB * cp - sinA * cosB * sp) + circleY * cosA * cosB
                val z = k2 + cosA * circleX * sp + circleY * sinA
                val ooz = 1 / z
                val col = (rows / 2.0 + k1 * ooz * x).toInt()
                val row = (rows / 2.0 - k1 * ooz * y).toInt()
                if (col in 0 until rows && row in 0 until rows) {
                    val i = row * rows + col
                    if (ooz > depth[i]) {
                        depth[i] = ooz
                        light[i] = cp * ct * sinB - cosA * ct * sp - sinA * st + cosB * (cosA * st - ct * sinA * sp)
                        tone[i] = phi / TWO_PI
                    }
                }
                phi += 0.045
            }
            theta += 0.11
        }

        val cell = 2 * r / rows
        g.font = Font(Font.MONOSPACED, Font.BOLD, max(4, (cell * 1.25).toInt()))
        val fm = g.fontMetrics
        val dim = 0.5 + 0.5 * energy.toDouble()
        for (row in 0 until rows) {
            for (col in 0 until rows) {
                val i = row * rows + col
                if (depth[i] == 0.0) continue
                val wave = pulse * (0.5 + 0.5 * sin(tone[i] * 18 - t * 10))
                val value = ((max(0.0, light[i]) / 1.42) * 0.8 + 0.2 + wave * 0.45).coerceIn(0.0, 1.0) * dim
                val index = (value * (RAMP.length - 1)).toInt().coerceIn(1, RAMP.length - 1)
                g.color = color(t, (90 + 165 * value) / 255, tone[i])
                val ch = RAMP[index].toString()
                val x = cx + ((col + 0.5) / rows * 2 - 1) * r
                val y = cy + ((row + 0.5) / rows * 2 - 1) * r
                g.drawString(ch, (x - fm.stringWidth(ch) / 2.0).toFloat(), (y + fm.ascent * 0.36).toFloat())
            }
        }
        if (showName) {
            g.font = Font(Font.MONOSPACED, Font.BOLD, max(6, (r * 0.26).toInt()))
            val name = "L U C Y"
            val nfm = g.fontMetrics
            g.color = color(t + 1.5, (170 + 85 * energy.toDouble()) / 255)
            g.drawString(name, (cx - nfm.stringWidth(name) / 2.0).toFloat(), (cy + r * 1.12 + nfm.ascent * 0.35).toFloat())
        }
    }

    companion object {
        /** De tenue a brillante (la rampa del «donut» ASCII clásico). */
        private const val RAMP = " .,-~:;=!*#$@"
        private const val TWO_PI = 6.283185307179586
        private const val TUBE = 1.0
        private const val RING = 2.0

        /**
         * Ícono de Lucy (ventana, bandeja y .exe): el anillo ASCII con «L U C Y» sobre fondo oscuro.
         * A tamaño chico (donde las letras no se leen) se dibuja el anillo fucsia-dorado con una «L».
         */
        fun icon(size: Int): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = Color(18, 8, 22)
            g.fillOval(0, 0, size, size)
            if (size < 48) {
                val s = size / 108.0
                g.stroke = java.awt.BasicStroke((9 * s).toFloat())
                g.paint = java.awt.GradientPaint((32 * s).toFloat(), (32 * s).toFloat(), Color(0xFF, 0x2E, 0xA6), (76 * s).toFloat(), (76 * s).toFloat(), Color(0xFF, 0xB4, 0x2E))
                g.draw(Ellipse2D.Double(25 * s, 25 * s, 58 * s, 58 * s))
                g.stroke = java.awt.BasicStroke((7 * s).toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.color = Color.WHITE
                val l = java.awt.geom.Path2D.Double().apply { moveTo(47 * s, 40 * s); lineTo(47 * s, 66 * s); lineTo(64 * s, 66 * s) }
                g.draw(l)
            } else {
                // De frente y apenas inclinado: se ve el agujero del anillo.
                SpherePainter().apply { energy = 1f; level = 0.5f }.paint(g, size, size, 1.3, tilt = 1.2, turn = 0.15)
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
