package com.sara.lucy.ui

import com.sara.core.ai.AiProvider
import com.sara.core.assistant.Acknowledgement
import com.sara.core.engine.VoiceOption
import com.sara.lucy.LucyApp
import com.sara.lucy.data.LucySettings
import com.sara.lucy.data.SecretStore
import com.sara.lucy.win.Autostart
import com.sara.lucy.win.LucyPaths
import com.sara.lucy.win.shellOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import kotlin.math.roundToInt

/** Ajustes de Lucy (los mismos del teléfono que aplican a la computadora). Se guardan al aceptar. */
class SettingsDialog(owner: JFrame, private val app: LucyApp, tab: Int, private val onSaved: () -> Unit) : JDialog(owner, "Ajustes de Lucy", true) {
    private val s = app.settings.current

    // General
    private val wake = JCheckBox("Escuchar siempre la palabra «Lucy»", s.wakeWordEnabled)
    private val hey = JCheckBox("Pedir «Ey Lucy» en lugar de «Lucy» sola (menos activaciones por error)", s.heyPrefix)
    private val sensitivity = JSlider(0, 100, (s.sensitivity * 100).roundToInt())
    private val ack = JComboBox(arrayOf("Responder con voz («¿Sí?»)", "Sólo un sonido", "Voz y sonido", "Nada"))
    private val continuous = JCheckBox("Conversación continua (sigue escuchando unos segundos después de responder)", s.continuousConversation)
    private val autostart = JCheckBox("Iniciar con Windows", Autostart.isEnabled())
    private val hotkey = JCheckBox("Atajo Ctrl+Alt+L para hablarle (se aplica al reiniciar Lucy)", s.hotkey)
    private val mic = JComboBox((listOf(AUTO_MIC) + com.sara.lucy.audio.Microphone.available()).toTypedArray()).apply {
        selectedItem = s.microphone.ifBlank { AUTO_MIC }
        renderer = javax.swing.DefaultListCellRenderer().let { base ->
            javax.swing.ListCellRenderer<String?> { list, value, index, sel, focus ->
                val text = if (value != null && value != AUTO_MIC && com.sara.lucy.audio.Microphone.looksBluetooth(value)) "$value  ·  Bluetooth (modo llamada)" else value
                base.getListCellRendererComponent(list, text, index, sel, focus)
            }
        }
    }
    private val boost = JComboBox(BOOSTS.map { it.second }.toTypedArray()).apply {
        selectedIndex = BOOSTS.indexOfFirst { it.first == s.micBoost }.coerceAtLeast(0)
    }
    private val asrStatus = JLabel(" ")
    private val quick = JCheckBox("Respuesta rápida (hace la orden apenas la entiende, sin esperar la pausa)", s.quickResponse)
    private val chat = JCheckBox("Charlar después de una pregunta (sigue escuchando sin decir «Lucy»; después de una orden se calla)", s.chatFollowUp)
    private val duck = JCheckBox("Bajar la música de las otras apps mientras te escucho y hablo", s.duckMusic)
    private val echo = JCheckBox("Cancelación de eco (que la música de la compu no me active ni tape tu voz)", s.echoCancellation)

    // Voz
    /** Voces de Piper (gratis, sin Internet, las mismas de Android) y la de Windows. */
    private val voiceChoice = JComboBox(
        (com.sara.core.voice.PiperVoice.entries.map { "${it.label} · ${it.country} (gratis, sin Internet)" } + "Voz de Windows").toTypedArray(),
    )
    private val piperStatus = JLabel(" ")
    private val voices = JComboBox<VoiceOption>()
    private val rate = JSlider(50, 200, (s.speechRate * 100).roundToInt())
    private val volume = JSlider(10, 100, (s.voiceVolume * 100).roundToInt())
    private val pause = JSlider(800, 3_000, s.speechPauseMillis)

    // IA
    private val provider = JComboBox(arrayOf("Google (Gemini)", "Claude (Anthropic)"))
    private val geminiKey = JPasswordField(30)
    private val claudeKey = JPasswordField(30)
    private val precise = JCheckBox("Mientras se baja el reconocimiento preciso, entender las órdenes con Google (manda el audio de cada orden)", s.preciseRecognition)

    // Esfera
    private val sphereVisible = JCheckBox("Mostrar la esfera flotante al llamarme", s.sphereVisible)
    private val sphereAlways = JCheckBox("Dejarla siempre visible", s.sphereAlways)
    private val sphereSize = JSlider(90, 320, s.sphereSize)

    // Otros
    private val city = JTextField(s.city, 20)
    private val saveHistory = JCheckBox("Guardar el historial de órdenes", s.saveHistory)
    private val updates = JCheckBox("Avisarme cuando haya una versión nueva", s.checkUpdates)
    private val minimized = JCheckBox("Abrir minimizada en la bandeja", s.startMinimized)

    init {
        ack.selectedIndex = Acknowledgement.entries.indexOf(s.acknowledgement)
        voiceChoice.selectedIndex = if (s.usePiper) s.piperVoice.ordinal else com.sara.core.voice.PiperVoice.entries.size
        showPiperStatus()
        voiceChoice.addActionListener { showPiperStatus() }
        app.scope.launch(Dispatchers.Swing) {
            app.parakeet.state.collect { st ->
                asrStatus.text = when (st) {
                    is com.sara.lucy.speech.PiperState.Downloading -> "Bajándose: ${(st.progress * 100).roundToInt()} % (490 MB, una sola vez)…"
                    com.sara.lucy.speech.PiperState.Installing -> "Instalando…"
                    com.sara.lucy.speech.PiperState.Ready -> "Listo."
                    is com.sara.lucy.speech.PiperState.Failed -> st.message
                    com.sara.lucy.speech.PiperState.NotInstalled -> "Se baja solo (490 MB, una vez)."
                }
            }
        }
        provider.selectedIndex = if (s.aiProvider == AiProvider.CLAUDE) 1 else 0
        geminiKey.text = if (app.secrets.get(SecretStore.GEMINI_KEY) != null) KEEP else ""
        claudeKey.text = if (app.secrets.get(SecretStore.ANTHROPIC_KEY) != null) KEEP else ""
        listOf(sensitivity, rate, volume, pause, sphereSize).forEach { it.preferredSize = Dimension(260, it.preferredSize.height) }
        voices.renderer = javax.swing.DefaultListCellRenderer().let { base ->
            javax.swing.ListCellRenderer<VoiceOption?> { list, value, index, sel, focus ->
                base.getListCellRendererComponent(list, value?.let { "${it.name} (${it.locale})" } ?: "Automática (español)", index, sel, focus)
            }
        }
        loadVoices()

        val tabs = JTabbedPane()
        tabs.addTab("General", form(
            row(wake), row(hey),
            labeled("Sensibilidad (más = te escucha desde más lejos, pero se activa más por error)", sensitivity),
            labeled("Cuando la llamás", ack), row(quick), row(chat), row(continuous), row(duck), row(echo),
            labeled("Micrófono", mic),
            note("En automático Lucy usa el micrófono de la compu y nunca el de un parlante o auricular Bluetooth: abrir ese micrófono pone todo el audio en «modo llamada» (la música se escucha mal). Si elegís uno Bluetooth, pasa eso mientras Lucy escucha."),
            labeled("Potenciar el micrófono (para hablarle normal desde lejos)", boost),
            labeled("Reconocimiento preciso (entiende frases libres, sin Internet)", asrStatus),
            row(JButton("Probar micrófono").apply { addActionListener { testMicrophone() } }), row(autostart), row(hotkey), row(minimized),
        ))
        tabs.addTab("Voz", form(
            labeled("Voz de Lucy", voiceChoice), row(piperStatus),
            row(JButton("Bajar la voz ahora").apply { addActionListener { downloadVoice() } }),
            note("Daniela (Argentina), Claude (México) y Sharvard (España): las mismas voces de Sara en Android. Se bajan una vez y después hablan sin Internet."),
            labeled("Voz de Windows (si elegís «Voz de Windows» o mientras se baja la otra)", voices), labeled("Velocidad", rate), labeled("Volumen de la voz", volume),
            labeled("Pausa que te espera al hablar (ms)", pause),
            row(JButton("Probar voz").apply { addActionListener { testVoice() } }),
            note("Para más voces en español: Configuración de Windows › Hora e idioma › Voz › Agregar voces."),
        ))
        tabs.addTab("IA", form(
            note("Con una clave de IA Lucy contesta preguntas, busca en Google y resuelve pedidos complejos. Sin clave funciona todo lo demás."),
            labeled("Para preguntas y pedidos", provider),
            labeled("Clave de Google (Gemini)", geminiKey),
            row(JButton("Conseguir clave gratis de Google").apply { addActionListener { shellOpen(app.system, "https://aistudio.google.com/apikey") } }),
            labeled("Clave de Claude (opcional)", claudeKey),
            row(precise),
            note("Las claves se guardan cifradas con tu usuario de Windows."),
        ))
        tabs.addTab("Esfera", form(
            row(sphereVisible), row(sphereAlways), labeled("Tamaño", sphereSize),
            row(JButton("Volver a la esquina").apply { addActionListener { (owner as? MainWindow)?.sphereWindow?.resetPosition() } }),
            note("Arrastrala para moverla. Doble clic: vuelve a la esquina superior derecha."),
        ))
        tabs.addTab("Otros", form(
            labeled("Ciudad para el clima (vacía = se estima por tu conexión)", city), row(saveHistory), row(updates),
            row(JButton("Diagnóstico").apply { addActionListener { diagnostics() } }),
            row(JButton("Abrir la carpeta de Lucy").apply { addActionListener { shellOpen(app.system, LucyPaths.home.absolutePath) } }),
            row(JButton("Borrar lo que recuerdo de vos").apply { addActionListener { forgetAll() } }),
        ))
        tabs.selectedIndex = tab.coerceIn(0, tabs.tabCount - 1)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("Cancelar").apply { addActionListener { dispose() } })
            add(JButton("Guardar").apply { addActionListener { save() } }.also { this@SettingsDialog.rootPane.defaultButton = it })
        }
        contentPane = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(tabs, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        size = Dimension(680, 600)
        setLocationRelativeTo(owner)
    }

    private fun form(vararg rows: JComponent): JComponent {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply { gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; anchor = GridBagConstraints.NORTHWEST; insets = Insets(4, 8, 4, 8) }
        rows.forEach { panel.add(it, c) }
        c.weighty = 1.0
        panel.add(JPanel(), c)
        return JScrollPane(panel).apply { border = null }
    }

    /** Una fila; las casillas con texto largo se parten en varias líneas (no se cortan). */
    private fun row(component: JComponent): JComponent {
        if (component !is JCheckBox || component.text.isNullOrEmpty()) return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(component) }
        // La casilla sola y el texto al lado en una etiqueta que se parte en renglones (tocarla también marca).
        val label = wrapped(component.text).apply {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { if (component.isEnabled) component.doClick() }
            })
        }
        component.text = ""
        return JPanel(BorderLayout(6, 0)).apply {
            add(component, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }
    }

    private fun labeled(text: String, component: JComponent) = JPanel(BorderLayout(0, 2)).apply {
        add(JLabel(text), BorderLayout.NORTH)
        add(row(component), BorderLayout.CENTER)
    }

    private fun note(text: String) = wrapped(text, java.awt.Color(0x9A, 0x96, 0xB8))

    /** Texto que se parte en renglones según el ancho (las etiquetas HTML no lo hacían bien). */
    private fun wrapped(text: String, color: java.awt.Color? = null) = javax.swing.JTextArea(text, 0, 56).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isFocusable = false
        isOpaque = false
        border = null
        font = UIManagerFont
        color?.let { foreground = it }
    }

    private fun loadVoices() {
        app.scope.launch(Dispatchers.IO) {
            val list = app.windowsVoices.voices("es-AR")
            launch(Dispatchers.Swing) {
                voices.addItem(null)
                list.forEach { voices.addItem(it) }
                voices.selectedItem = list.firstOrNull { it.id == s.voiceId }
            }
        }
    }

    private fun collect(): LucySettings = s.copy(
        wakeWordEnabled = wake.isSelected,
        heyPrefix = hey.isSelected,
        sensitivity = sensitivity.value / 100f,
        acknowledgement = Acknowledgement.entries[ack.selectedIndex.coerceAtLeast(0)],
        continuousConversation = continuous.isSelected,
        quickResponse = quick.isSelected,
        microphone = (mic.selectedItem as? String)?.takeIf { mic.selectedIndex > 0 } ?: "",
        micBoost = BOOSTS.getOrElse(boost.selectedIndex) { BOOSTS[0] }.first,
        chatFollowUp = chat.isSelected,
        usePiper = voiceChoice.selectedIndex < com.sara.core.voice.PiperVoice.entries.size,
        piperVoice = com.sara.core.voice.PiperVoice.entries.getOrElse(voiceChoice.selectedIndex) { s.piperVoice },
        duckMusic = duck.isSelected,
        echoCancellation = echo.isSelected,
        hotkey = hotkey.isSelected,
        startMinimized = minimized.isSelected,
        voiceId = (voices.selectedItem as? VoiceOption)?.id,
        speechRate = rate.value / 100f,
        voiceVolume = volume.value / 100f,
        speechPauseMillis = pause.value,
        aiProvider = if (provider.selectedIndex == 1) AiProvider.CLAUDE else AiProvider.GOOGLE,
        preciseRecognition = precise.isSelected,
        sphereVisible = sphereVisible.isSelected,
        sphereAlways = sphereAlways.isSelected,
        sphereSize = sphereSize.value,
        city = city.text.trim(),
        saveHistory = saveHistory.isSelected,
        checkUpdates = updates.isSelected,
    )

    private fun showPiperStatus() {
        val voice = com.sara.core.voice.PiperVoice.entries.getOrNull(voiceChoice.selectedIndex)
        piperStatus.text = when {
            voice == null -> "Usa la voz de Windows."
            app.piperModels.isInstalled(voice) -> "${voice.label} está lista."
            else -> "${voice.label} se baja sola al guardar (${voice.megabytes} MB, una vez)."
        }
    }

    private fun downloadVoice() {
        app.settings.update { collect() }
        app.ensurePiper()
        app.scope.launch(Dispatchers.Swing) {
            app.piperModels.state.collect { st ->
                piperStatus.text = when (st) {
                    is com.sara.lucy.speech.PiperState.Downloading -> "Bajando ${(st.progress * 100).roundToInt()} %…"
                    com.sara.lucy.speech.PiperState.Installing -> "Instalando…"
                    com.sara.lucy.speech.PiperState.Ready -> "Lista."
                    is com.sara.lucy.speech.PiperState.Failed -> st.message
                    com.sara.lucy.speech.PiperState.NotInstalled -> piperStatus.text
                }
            }
        }
    }

    private fun testVoice() {
        val preview = collect()
        app.settings.update { preview }
        app.scope.launch { app.orchestrator.speakPreview("Hola, soy Lucy. Así sueno.") }
    }

    private fun save() {
        val next = collect()
        val wakeChanged = next.wakeWordEnabled != s.wakeWordEnabled || next.heyPrefix != s.heyPrefix || next.sensitivity != s.sensitivity
        app.settings.update { next }
        app.ensurePiper()
        runCatching {
            String(geminiKey.password).let { if (it != KEEP) app.secrets.put(SecretStore.GEMINI_KEY, it) }
            String(claudeKey.password).let { if (it != KEEP) app.secrets.put(SecretStore.ANTHROPIC_KEY, it) }
        }.onFailure { JOptionPane.showMessageDialog(this, "No pude guardar las claves: ${it.message}", "Lucy", JOptionPane.WARNING_MESSAGE) }
        if (autostart.isSelected != Autostart.isEnabled() && !Autostart.set(autostart.isSelected)) {
            JOptionPane.showMessageDialog(this, "Windows no dejó cambiar el inicio automático.", "Lucy", JOptionPane.WARNING_MESSAGE)
        }
        if (wakeChanged) {
            // Reinicia la escucha para que tome la sensibilidad y la palabra nuevas.
            app.orchestrator.setWakeWordEnabled(false)
            app.orchestrator.setWakeWordEnabled(next.wakeWordEnabled)
        }
        onSaved()
        dispose()
    }

    /** Graba 4 segundos: muestra el nivel, qué micrófono usa y qué entendió, y lo reproduce. */
    private fun testMicrophone() {
        val wasListening = app.orchestrator.isWakeWordEnabled
        app.orchestrator.setWakeWordEnabled(false)
        val dialog = JOptionPane("Hablá ahora: «Lucy, poné música»… (4 segundos)", JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, arrayOf<Any>())
        val window = dialog.createDialog(this, "Probar micrófono").apply { isModal = false; isVisible = true }
        app.scope.launch(Dispatchers.IO) {
            val result = runCatching { app.microphoneTest() }.getOrElse { "No pude probar: ${it.message}" }
            launch(Dispatchers.Swing) {
                window.dispose()
                JOptionPane.showMessageDialog(this@SettingsDialog, result, "Probar micrófono", JOptionPane.INFORMATION_MESSAGE)
                if (wasListening) app.orchestrator.setWakeWordEnabled(true)
            }
        }
    }

    private fun diagnostics() {
        val problems = runBlocking { app.diagnosticChecks() }
        val text = (if (problems.isEmpty()) "Todo en orden.\n\n" else "Encontré esto:\n- " + problems.joinToString("\n- ") + "\n\n") +
            "Últimos registros:\n" + app.logger.recent().takeLast(60).joinToString("\n")
        val area = JTextArea(text, 24, 70).apply { isEditable = false; lineWrap = true }
        JOptionPane.showMessageDialog(this, JScrollPane(area), "Diagnóstico de Lucy", JOptionPane.INFORMATION_MESSAGE)
    }

    private fun forgetAll() {
        val ok = JOptionPane.showConfirmDialog(this, "¿Borro todo lo que recuerdo (preferencias, rutinas por frase y datos)?", "Lucy", JOptionPane.YES_NO_OPTION)
        if (ok == JOptionPane.YES_OPTION) app.memory.all().keys.forEach { app.memory.remove(it) }
    }

    private companion object {
        val UIManagerFont: java.awt.Font get() = javax.swing.UIManager.getFont("Label.font")
        /** Marca que muestra que ya hay una clave guardada (no se muestra la clave). */
        const val KEEP = "••••••••••••"
        const val AUTO_MIC = "Automático (el de la compu, nunca el de un parlante Bluetooth)"
        val BOOSTS = listOf(
            0 to "Automático (sube la voz baja sin subir el ruido)", 100 to "Normal", 200 to "×2", 300 to "×3", 500 to "×5", 800 to "×8 (máximo)",
        )
    }
}

/** Historial de órdenes: qué escuchó, qué hizo y qué respondió. */
class HistoryDialog(owner: JFrame, private val app: LucyApp) : JDialog(owner, "Historial", false) {
    init {
        val time = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        val model = DefaultListModel<String>()
        app.history.entries.value.forEach {
            val at = Instant.ofEpochMilli(it.timestampMillis).atZone(ZoneId.systemDefault()).format(time)
            model.addElement("<html><b>$at</b> ${if (it.success) "✔" else "✖"} «${esc(it.heard)}»<br><span style='color:#666'>${esc(it.response)}</span></html>")
        }
        if (model.isEmpty) model.addElement("Todavía no hay órdenes.")
        contentPane = JPanel(BorderLayout()).apply {
            add(JScrollPane(JList(model)), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(JButton("Borrar historial").apply { addActionListener { app.history.clear(); model.clear(); model.addElement("Todavía no hay órdenes.") } })
                add(JButton("Cerrar").apply { addActionListener { dispose() } })
            }, BorderLayout.SOUTH)
        }
        size = Dimension(560, 480)
        setLocationRelativeTo(owner)
    }

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
