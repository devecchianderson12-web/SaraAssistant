package com.sara.lucy.ui

import com.sara.core.model.AssistantEvent
import com.sara.core.model.AssistantState
import com.sara.core.model.PermissionKind
import com.sara.core.model.StateKind
import com.sara.core.model.kind
import com.sara.lucy.LucyApp
import com.sara.lucy.win.LucyLocation
import com.sara.lucy.win.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

private val BG_TOP = Color(14, 10, 30)
private val BG_BOTTOM = Color(4, 4, 10)
private val TEXT = Color(236, 232, 255)
private val MUTED = Color(160, 150, 200)
private val ACCENT = Color(150, 95, 255)

/** Botón píldora violeta (bordes totalmente redondeados con FlatLaf). */
internal fun darkButton(text: String, action: () -> Unit) = JButton(text).apply {
    putClientProperty("JButton.buttonType", "roundRect")
    isFocusPainted = false
    background = Color(46, 34, 88)
    foreground = TEXT
    font = Font("Segoe UI", Font.PLAIN, 13)
    margin = java.awt.Insets(6, 16, 6, 16)
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addActionListener { action() }
}

/** Ventana principal: la esfera (nebulosa) que reacciona a la voz, lo que escuchó y lo que respondió. */
class MainWindow(private val app: LucyApp) : JFrame("Lucy") {
    private val sphere = SphereComponent()
    private val status = label("", 15f, TEXT)
    private val heard = label("", 13f, MUTED)
    private val answer = label("", 14f, TEXT)
    private val banner = JPanel(FlowLayout(FlowLayout.CENTER, 8, 4)).apply { isOpaque = false; isVisible = false }
    private val input = JTextField().apply {
        font = Font("Segoe UI", Font.PLAIN, 14)
        background = Color(24, 18, 44)
        foreground = TEXT
        caretColor = TEXT
        putClientProperty("JTextField.placeholderText", "Escribile a Lucy…")
        putClientProperty("JComponent.roundRect", true)
        margin = java.awt.Insets(6, 12, 6, 12)
        toolTipText = "Escribile a Lucy y apretá Enter"
        addActionListener { send() }
    }
    private val wakeToggle = darkButton("") { toggleWake() }
    val sphereWindow = SphereWindow(app.settings) { app.orchestrator.activate() }
    private var tray: TrayIcon? = null
    private var hideTimer: Timer? = null
    private var told = false
    private var trayWakeItem: JCheckBoxMenuItem? = null

    init {
        title = if (LucyLocation.version > 0) "Lucy · V${LucyLocation.version}" else "Lucy"
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        iconImages = listOf(16, 32, 48, 64, 128, 256).map { SpherePainter.icon(it) }
        minimumSize = Dimension(420, 560)
        size = Dimension(460, 640)
        setLocationRelativeTo(null)
        contentPane = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).paint = GradientPaint(0f, 0f, BG_TOP, 0f, height.toFloat(), BG_BOTTOM)
                g.fillRect(0, 0, width, height)
            }
        }.apply { border = BorderFactory.createEmptyBorder(12, 16, 14, 16) }

        sphere.preferredSize = Dimension(320, 320)
        sphere.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        sphere.toolTipText = "Tocá para hablar"
        sphere.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = talk()
        })
        val center = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(banner)
            add(sphere)
            add(Box.createVerticalStrut(6))
            add(status)
            add(Box.createVerticalStrut(10))
            // Lo que escuchó y lo que respondió, en una burbuja redondeada.
            add(object : JPanel() {
                init {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = BorderFactory.createEmptyBorder(10, 14, 12, 14)
                    alignmentX = JComponent.CENTER_ALIGNMENT
                    add(heard)
                    add(Box.createVerticalStrut(4))
                    add(answer)
                }
                override fun paintComponent(g: Graphics) {
                    if (heard.text.isNullOrBlank() && answer.text.isNullOrBlank()) return
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(28, 22, 52, 220)
                    g2.fillRoundRect(0, 0, width - 1, height - 1, 26, 26)
                    g2.color = Color(110, 90, 200, 120)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 26, 26)
                    g2.dispose()
                }
            })
        }
        add(center, BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout(8, 8)).apply {
            isOpaque = false
            add(JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(input, BorderLayout.CENTER)
                add(darkButton("Enviar") { send() }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
                isOpaque = false
                add(darkButton("Hablar") { talk() })
                add(wakeToggle)
                add(darkButton("Historial") { HistoryDialog(this@MainWindow, app).isVisible = true })
                add(darkButton("Ajustes") { openSettings() })
            }, BorderLayout.SOUTH)
        }
        add(bottom, BorderLayout.SOUTH)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                isVisible = false
                if (!told) {
                    told = true
                    tray?.displayMessage("Lucy", "Sigo acá, en la bandeja. Llamame diciendo «Lucy».", TrayIcon.MessageType.INFO)
                }
            }
        })
        setupTray()
        observe()
        refreshWake()
        app.notify = { title, text -> tray?.displayMessage(title, text, TrayIcon.MessageType.INFO) }
        if (app.settings.current.sphereVisible && app.settings.current.sphereAlways) sphereWindow.isVisible = true
    }

    private fun label(text: String, size: Float, color: Color) = JLabel(text, SwingConstants.CENTER).apply {
        foreground = color
        font = Font("Segoe UI", Font.PLAIN, 13).deriveFont(size)
        alignmentX = JComponent.CENTER_ALIGNMENT
    }

    /** Texto largo en varias líneas, centrado. */
    private fun JLabel.setWrapped(text: String) {
        val clean = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        this.text = if (clean.isBlank()) "" else if (clean.length < 48) clean else "<html><body style='width:380px'><center>$clean</center></body></html>"
    }

    fun showWindow() = SwingUtilities.invokeLater {
        isVisible = true
        extendedState = NORMAL
        toFront()
        requestFocus()
    }

    fun talk() {
        if (!app.orchestrator.activate()) app.orchestrator.cancelInteraction()
    }

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        if (app.orchestrator.submitText(text)) input.text = ""
    }

    private fun toggleWake() {
        val on = !app.orchestrator.isWakeWordEnabled
        app.settings.update { it.copy(wakeWordEnabled = on) }
        app.orchestrator.setWakeWordEnabled(on)
        refreshWake()
    }

    private fun refreshWake() {
        val on = app.orchestrator.isWakeWordEnabled
        wakeToggle.text = if (on) "«Lucy»: activada" else "«Lucy»: apagada"
        wakeToggle.foreground = if (on) Color(150, 255, 190) else Color(255, 170, 170)
        trayWakeItem?.state = on
    }

    fun openSettings(tab: Int = 0) {
        SettingsDialog(this, app, tab) {
            refreshWake()
            sphereWindow.applySize()
            if (!app.settings.current.sphereVisible || (!app.settings.current.sphereAlways && !app.orchestrator.isInteractionActive)) sphereWindow.isVisible = false
            else if (app.settings.current.sphereAlways) sphereWindow.isVisible = true
        }.isVisible = true
    }

    private fun showBanner(text: String, button: String?, action: (() -> Unit)?) {
        banner.removeAll()
        banner.add(label(text, 12.5f, Color(255, 220, 150)))
        if (button != null && action != null) banner.add(darkButton(button) { banner.isVisible = false; action() })
        banner.add(darkButton("✕") { banner.isVisible = false })
        banner.isVisible = true
        banner.revalidate()
        banner.repaint()
    }

    // ---------------------------------------------------------------- Estado de Lucy → pantalla
    private fun observe() {
        // Sin clave de IA Lucy sólo hace órdenes: para charlar y preguntar como Sara en el teléfono hace falta.
        if (!app.ai.isAvailable()) {
            showBanner("Para charlar y hacerle preguntas como a Sara, cargá la clave gratis de Google.", "Cargar clave") { openSettings(2) }
        }
        app.scope.launch(Dispatchers.Swing) {
            app.orchestrator.state.collect { state -> render(state) }
        }
        app.scope.launch(Dispatchers.Swing) {
            app.levels.level.collect { sphere.painter.level = it; sphereWindow.sphere.painter.level = it }
        }
        app.scope.launch(Dispatchers.Swing) {
            app.orchestrator.events.collect { event ->
                when (event) {
                    is AssistantEvent.Heard -> { heard.setWrapped("Vos: «${event.text}»"); answer.setWrapped("") }
                    is AssistantEvent.Responded -> if (event.text.isNotBlank()) answer.setWrapped(event.text)
                    is AssistantEvent.Notice -> answer.setWrapped(event.text)
                    is AssistantEvent.Failed -> answer.setWrapped(event.error.message)
                    is AssistantEvent.PermissionNeeded -> if (event.kind == PermissionKind.AI_KEY) {
                        showBanner("Para preguntas cargá la clave de Google.", "Cargar clave") { openSettings(2) }
                    }
                    is AssistantEvent.Activated -> Unit
                }
            }
        }
    }

    private fun render(state: AssistantState) {
        val (energy, text) = when (state) {
            AssistantState.Inactive -> 0.3f to "Tocá la esfera o apretá Ctrl+Alt+L para hablar"
            AssistantState.WaitingForWakeWord -> 0.35f to if (app.settings.current.heyPrefix) "Decí «Ey Lucy»" else "Decí «Lucy»"
            is AssistantState.Listening -> 1f to (state.partialText.ifBlank { "Te escucho…" })
            is AssistantState.Processing -> 0.7f to "Pensando…"
            is AssistantState.Executing -> 0.7f to "Haciéndolo…"
            is AssistantState.Speaking -> 0.85f to state.text
            is AssistantState.Error -> 0.3f to state.error.message
        }
        for (p in listOf(sphere.painter, sphereWindow.sphere.painter)) {
            p.energy = energy
            p.speaking = state is AssistantState.Speaking
        }
        status.setWrapped(text)
        val caption = when (state) {
            is AssistantState.Listening -> state.partialText.ifBlank { "Te escucho…" }
            is AssistantState.Speaking -> state.text
            is AssistantState.Processing -> "Pensando…"
            else -> ""
        }
        sphereWindow.setCaption(caption)
        refreshWake()
        // La esfera flotante aparece al llamarla (sobre cualquier ventana) y se va al terminar.
        val active = state.kind in setOf(StateKind.LISTENING, StateKind.PROCESSING, StateKind.EXECUTING, StateKind.SPEAKING)
        val s = app.settings.current
        if (s.sphereVisible && active) {
            hideTimer?.stop()
            if (!sphereWindow.isVisible) sphereWindow.isVisible = true
        } else if (!s.sphereAlways && sphereWindow.isVisible) {
            hideTimer?.stop()
            hideTimer = Timer(700) { if (!app.orchestrator.isInteractionActive) sphereWindow.isVisible = false }.apply { isRepeats = false; start() }
        }
    }

    // ---------------------------------------------------------------- Bandeja del sistema
    private fun setupTray() {
        if (!SystemTray.isSupported()) return
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit) = javax.swing.JMenuItem(text).apply { addActionListener { action() } }
        menu.add(item("Abrir Lucy") { showWindow() })
        menu.add(item("Hablar ahora (Ctrl+Alt+L)") { talk() })
        trayWakeItem = JCheckBoxMenuItem("Escuchar «Lucy»").apply { addActionListener { toggleWake() } }
        menu.add(trayWakeItem)
        menu.add(item("Mostrar la esfera para moverla") { sphereWindow.isVisible = true; sphereWindow.setCaption("Arrastrame") })
        menu.add(item("Ajustes") { showWindow(); openSettings() })
        menu.add(item("Buscar actualizaciones") { checkUpdatesNow(manual = true) })
        menu.addSeparator()
        menu.add(item("Salir") { exit() })
        val icon = TrayIcon(SpherePainter.icon(32), "Lucy").apply { isImageAutoSize = true }
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    menu.setLocation(e.xOnScreen, e.yOnScreen - menu.preferredSize.height)
                    menu.invoker = menu
                    menu.isVisible = true
                } else if (e.button == MouseEvent.BUTTON1 && e.clickCount >= 2) {
                    showWindow()
                }
            }
        })
        runCatching { SystemTray.getSystemTray().add(icon) }.onSuccess { tray = icon }
    }

    fun exit() {
        tray?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        app.shutdown()
        Runtime.getRuntime().halt(0)
    }

    // ---------------------------------------------------------------- Actualizaciones
    private var updater: Updater? = null

    /** Al abrir y después cada 6 horas (si está activado avisar de versiones nuevas). */
    fun checkUpdates(updater: Updater) {
        this.updater = updater
        app.scope.launch(Dispatchers.IO) {
            while (true) {
                if (app.settings.current.checkUpdates) checkUpdatesNow(manual = false)
                kotlinx.coroutines.delay(6 * 60 * 60_000L)
            }
        }
    }

    /** «Buscar actualizaciones»: [manual] = lo pidió el usuario (avisa también si ya está al día). */
    fun checkUpdatesNow(manual: Boolean) {
        val updater = updater ?: Updater(app.logger).also { this.updater = it }
        app.scope.launch(Dispatchers.IO) {
            val release = updater.available()
            launch(Dispatchers.Swing) {
                if (release == null) {
                    if (manual) JOptionPane.showMessageDialog(
                        this@MainWindow,
                        if (updater.latest() == null) "No pude buscar actualizaciones. Revisá la conexión." else "Lucy está al día (V${com.sara.lucy.win.LucyLocation.version}).",
                        "Lucy", JOptionPane.INFORMATION_MESSAGE,
                    )
                    return@launch
                }
                if (manual) showWindow()
                showBanner("Hay una versión nueva de Lucy (V${release.version}).", "Actualizar") {
                    status.setWrapped("Bajando la actualización…")
                    app.scope.launch(Dispatchers.IO) {
                        val error = updater.install(release) { p -> SwingUtilities.invokeLater { status.setWrapped("Bajando la actualización… $p%") } }
                        SwingUtilities.invokeLater {
                            if (error == null) exit() else JOptionPane.showMessageDialog(this@MainWindow, error, "Lucy", JOptionPane.WARNING_MESSAGE)
                        }
                    }
                }
            }
        }
    }
}
