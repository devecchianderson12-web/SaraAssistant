package com.sara.lucy

import com.sara.core.ai.AiRouter
import com.sara.core.ai.ClaudeAssistant
import com.sara.core.ai.GeminiAssistant
import com.sara.core.ai.PlannerSupport
import com.sara.core.assistant.ActivationFeedback
import com.sara.core.assistant.AssistantConfig
import com.sara.core.assistant.AssistantOrchestrator
import com.sara.core.engine.Availability
import com.sara.core.engine.ConnectivityStatus
import com.sara.core.engine.EngineMode
import com.sara.core.engine.EngineProvider
import com.sara.core.engine.SpeechRecognitionEngine
import com.sara.core.engine.VoiceEngine
import com.sara.core.engine.VoiceParams
import com.sara.core.engine.WakeWordEngine
import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.interpreter.CompositeCommandInterpreter
import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.model.AssistantIdentity
import com.sara.core.model.PermissionKind
import com.sara.core.skills.CalculatorSkill
import com.sara.core.skills.CommandSkills
import com.sara.core.skills.ListBook
import com.sara.core.skills.ListsSkill
import com.sara.core.skills.SkillRegistry
import com.sara.core.util.AudioLevelMeter
import com.sara.core.util.WakeWordProfile
import com.sara.core.util.WakeWordTuning
import com.sara.lucy.audio.Chime
import com.sara.lucy.audio.AecMicrophone
import com.sara.lucy.audio.Microphone
import com.sara.lucy.data.DiagnosticsLog
import com.sara.lucy.data.FileCommandHistory
import com.sara.lucy.data.FileMemoryStore
import com.sara.lucy.data.FileTextStore
import com.sara.lucy.data.LucySettings
import com.sara.lucy.data.SecretStore
import com.sara.lucy.data.SettingsRepository
import com.sara.lucy.skills.AlarmSkill
import com.sara.lucy.skills.CalendarSkill
import com.sara.lucy.skills.DeviceSkill
import com.sara.lucy.skills.DiagnosticsSkill
import com.sara.lucy.skills.GoogleSkill
import com.sara.lucy.skills.LocationProvider
import com.sara.lucy.skills.MapsSkill
import com.sara.lucy.skills.Reminder
import com.sara.lucy.skills.ReminderSkill
import com.sara.lucy.skills.RoutineSkill
import com.sara.lucy.skills.Scheduler
import com.sara.lucy.skills.TranslatorSkill
import com.sara.lucy.skills.WeatherSkill
import com.sara.lucy.skills.phoneOnlySkills
import com.sara.lucy.speech.GeminiTranscriber
import com.sara.lucy.speech.SapiVoiceEngine
import com.sara.lucy.speech.VoskModels
import com.sara.lucy.speech.VoskSpeechRecognitionEngine
import com.sara.lucy.speech.VoskWakeWordEngine
import com.sara.lucy.win.LucyPaths
import com.sara.lucy.win.WinAppLauncher
import com.sara.lucy.win.WinBridge
import com.sara.lucy.win.WinMediaControl
import com.sara.lucy.win.WinVolumeControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** La misma Sara del teléfono, en Windows: se llama Lucy y comparte el mismo cerebro (módulo core). */
object LucyIdentity {
    fun apply() {
        AssistantIdentity.name = "Lucy"
        AssistantIdentity.device = "computadora"
        AssistantIdentity.platform = "una computadora con Windows"
        AssistantIdentity.capabilities = "Música, volumen, apps, recordatorios, alarmas, listas, clima, cuentas, charla y preguntas."
        WakeWordTuning.profile = WakeWordProfile.LUCY
    }
}

/** Grafo de objetos de Lucy (equivalente a AppContainer en Android). */
class LucyApp {
    init {
        LucyIdentity.apply()
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logger = DiagnosticsLog()
    val levels = AudioLevelMeter()
    val settings = SettingsRepository()
    val system = WinBridge("system", logger)
    val voiceBridge = WinBridge("voice", logger)
    val secrets = SecretStore(File(LucyPaths.home, "keys.json"), system, logger)
    /** Micrófono con cancelación de eco de Windows (si no anda, el común). */
    // La cancelación de eco usa el micrófono predeterminado de Windows; si elegís otro, se usa ese directo.
    // Automático: nunca el micrófono de un parlante Bluetooth si hay otro (pondría la música en «modo llamada»).
    val microphone = AecMicrophone(levels, Microphone(levels) { settings.current.microphone }, logger, { settings.current.echoCancellation }) {
        settings.current.microphone
    }
    /** Lo que escuchan la palabra «Lucy» y las órdenes: el micrófono con el potenciador. */
    private val listeningSource = com.sara.lucy.audio.BoostedSource(microphone, { settings.current.micBoost }) { bluetoothOutput() }

    @Volatile private var bluetoothCheck = 0L to false

    /** ¿El sonido sale por Bluetooth? (se consulta cada 20 s como mucho). */
    private fun bluetoothOutput(): Boolean {
        val (at, value) = bluetoothCheck
        if (System.currentTimeMillis() - at < 20_000) return value
        val now = runCatching { system.obj("btOutput", timeoutMillis = 3_000).optBoolean("bluetooth") }.getOrDefault(false)
        bluetoothCheck = System.currentTimeMillis() to now
        return now
    }
    val models = VoskModels(logger)
    val history = FileCommandHistory(File(LucyPaths.home, "history.json")) { settings.current.saveHistory }
    val memory = FileMemoryStore(File(LucyPaths.home, "memory.json"))
    private val data = FileTextStore(File(LucyPaths.home, "data.json"))

    val connectivity = object : ConnectivityStatus {
        @Volatile private var cached: Pair<Long, Boolean>? = null
        override fun isOnline(): Boolean {
            cached?.takeIf { System.currentTimeMillis() - it.first < 15_000 }?.let { return it.second }
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress("dns.google", 443), 1_500) } }.isSuccess ||
                runCatching { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1_500) } }.isSuccess
            cached = System.currentTimeMillis() to ok
            return ok
        }
    }

    val appLauncher = WinAppLauncher(system, logger) { listOf(ProcessHandle.current().pid(), system.pid, voiceBridge.pid) }
    val media = WinMediaControl(system, appLauncher, data, logger).apply {
        onTaste = { memory.put(com.sara.core.memory.MemoryKeys.MUSIC_TASTE, it) }
    }
    private val volume = WinVolumeControl(system)

    // ---------------------------------------------------------------- Habilidades y cerebro
    val scheduler = Scheduler(data)
    private val location = LocationProvider { settings.current.city }
    private val lists = ListBook(data)
    private val plannerSupport = PlannerSupport({ skills }, memory)
    private val claude = ClaudeAssistant({ secrets.get(SecretStore.ANTHROPIC_KEY) }, plannerSupport, logger)
    private val gemini = GeminiAssistant({ secrets.get(SecretStore.GEMINI_KEY) }, memory, logger, plannerSupport)
    val ai = AiRouter({ settings.current.aiProvider }, gemini, claude)

    val skills: SkillRegistry by lazy {
        SkillRegistry(
            listOf(
                CommandSkills { executor },
                ListsSkill(lists),
                CalculatorSkill(),
                com.sara.core.skills.MusicTasteSkill(memory),
                AlarmSkill(scheduler),
                ReminderSkill(scheduler),
                RoutineSkill(scheduler),
                WeatherSkill(location),
                DeviceSkill(system),
                CalendarSkill(system),
                MapsSkill(system, location),
                GoogleSkill(ai),
                TranslatorSkill(ai),
                DiagnosticsSkill { diagnosticChecks() },
            ) + phoneOnlySkills(),
            isGranted = { kind -> kind != PermissionKind.AI_KEY || ai.isAvailable() },
        )
    }

    private val executor: DefaultCommandExecutor by lazy {
        DefaultCommandExecutor(
            media, volume, apps = appLauncher, memory = memory, assistant = com.sara.core.ai.FastKnowledgeAssistant(ai, com.sara.core.ai.Wikipedia(logger)), skills = skills, planner = ai,
        )
    }

    /** «Probar micrófono»: 4 segundos de audio → nivel, micrófono usado, lo que entiende; y lo reproduce. */
    suspend fun microphoneTest(): String {
        val pcm = java.io.ByteArrayOutputStream()
        var peak = 0
        val outcome = listeningSource.capture { buf, len ->
            pcm.write(buf, 0, len)
            peak = maxOf(peak, WakeWordTuning.peakOf(buf, len))
            pcm.size() < 16_000 * 2 * 4
        }
        if (outcome is com.sara.lucy.audio.CaptureOutcome.Failed) return outcome.error.message
        val audio = pcm.toByteArray()
        val model = models.model() ?: return "Falta el modelo de voz."
        val boosted = WakeWordTuning.applyGain(audio, audio.size, WakeWordTuning.gainFor(peak, maxGain = 4f))
        val heard = org.vosk.Recognizer(model, 16_000f).use { r ->
            r.acceptWaveForm(boosted, boosted.size)
            org.json.JSONObject(r.finalResult).optString("text")
        }
        runCatching {
            val format = javax.sound.sampled.AudioFormat(16_000f, 16, 1, true, false)
            val line = javax.sound.sampled.AudioSystem.getSourceDataLine(format)
            line.open(format); line.start(); line.write(audio, 0, audio.size); line.drain(); line.close()
        }
        val level = peak * 100 / 32_768
        val verdict = when {
            peak == 0 -> "No llega nada de audio: revisá Configuración de Windows › Privacidad › Micrófono."
            level < 5 -> "Llega muy bajo: subí el volumen del micrófono en Configuración › Sistema › Sonido."
            level > 95 -> "Llega saturado: bajá un poco el volumen del micrófono."
            else -> "El nivel está bien."
        }
        logger.d(TAG, "Prueba de micrófono: ${microphone.mode}, pico $level%, entendió «$heard»")
        return "Micrófono: ${microphone.mode}.\nNivel máximo: $level%. $verdict\nEntendí: «${heard.ifBlank { "(nada)" }}»\n\n(Te lo acabo de reproducir para que escuches cómo me llega.)"
    }

    suspend fun diagnosticChecks(): List<String> = buildList {
        if (!models.isInstalled()) add("falta el modelo de voz")
        else if (models.model() == null) add("el modelo de voz no carga: ${models.error}")
        if (!connectivity.isOnline()) add("no hay conexión a Internet")
        if (!ai.isAvailable()) add("no hay clave de IA cargada (sólo funciones locales)")
        if (runCatching { system.call("ping") }.isFailure) add("el puente con Windows no responde")
    }

    // ---------------------------------------------------------------- Motores
    private val transcriber = GeminiTranscriber({ secrets.get(SecretStore.GEMINI_KEY) }, connectivity::isOnline)
    val wake = VoskWakeWordEngine(models, listeningSource, logger)
    /** Reconocimiento preciso sin Internet (Parakeet): se baja solo la primera vez. */
    val parakeet = com.sara.lucy.speech.ParakeetAsr(logger)
    private val recognizer = VoskSpeechRecognitionEngine(models, listeningSource) { pcm, draft ->
        // Vosk marca cuándo terminaste; Parakeet (local) o, si todavía no está, Google transcriben bien la frase.
        parakeet.transcribe(pcm) ?: if (settings.current.preciseRecognition) transcriber.transcribe(pcm, draft) else null
    }

    /** Baja (una vez) y deja cargado el reconocimiento preciso. */
    fun ensureParakeet() {
        scope.launch(Dispatchers.IO) {
            if (!parakeet.isInstalled() && !parakeet.download()) return@launch
            parakeet.load()
        }
    }
    private val sapi = SapiVoiceEngine(voiceBridge)
    /** Voces de Piper (Daniela, Claude, Sharvard): se bajan una vez; mientras tanto habla la voz de Windows. */
    val piperModels = com.sara.lucy.speech.PiperModels(logger) { settings.current.piperVoice }
    val piper = com.sara.lucy.speech.PiperVoiceEngine(piperModels, { settings.current.piperVoice }, sapi, levels, logger)
    /** La voz que se usa ahora (para «Probar voz» y la lista de voces de Windows). */
    val voice: VoiceEngine get() = if (settings.current.usePiper) piper else sapi
    val windowsVoices: VoiceEngine get() = sapi

    /** Baja la voz de Piper elegida si falta. */
    fun ensurePiper() {
        piperModels.refresh()
        if (!settings.current.usePiper || piperModels.isInstalled() || piperModels.state.value is com.sara.lucy.speech.PiperState.Downloading) return
        scope.launch { piperModels.download() }
    }

    private val engines = object : EngineProvider {
        override fun wakeWordEngine(): WakeWordEngine = wake
        override fun onlineRecognizer(): SpeechRecognitionEngine? = null
        override fun offlineRecognizer(): SpeechRecognitionEngine = recognizer
        override fun onlineVoice(): VoiceEngine? = null
        override fun offlineVoice(): VoiceEngine = voice
    }

    /** Señal de «te escucho» y bajar la música de las otras apps mientras Lucy escucha y habla. */
    private val feedback = object : ActivationFeedback {
        override suspend fun chime() = Chime.play()
        override fun interactionStarted() = duck(true)
        override fun interactionEnded() = duck(false)
    }

    @Volatile private var ducked = false

    private fun duck(on: Boolean) {
        if (on && !settings.current.duckMusic) return
        if (!on && !ducked) return
        ducked = on
        scope.launch(Dispatchers.IO) {
            runCatching { system.call("duck", mapOf("on" to on, "exclude" to listOf(ProcessHandle.current().pid(), voiceBridge.pid))) }
                .onFailure { logger.w(TAG, "No pude bajar la música", it) }
        }
    }

    val orchestrator = AssistantOrchestrator(
        scope = scope,
        engines = engines,
        interpreter = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), memory = memory, aiAvailable = ai::isAvailable, plannerAvailable = ai::isAvailable),
        executor = executor,
        connectivity = connectivity,
        configProvider = { settings.current.toAssistantConfig() },
        feedback = feedback,
        history = history,
        logger = logger,
    )

    /** Avisos de recordatorios y rutinas (los muestra la bandeja del sistema). */
    var notify: (title: String, text: String) -> Unit = { _, _ -> }

    fun start() {
        ensurePiper()
        ensureParakeet()
        scheduler.onReminder = { r, late ->
            val said = when (r.kind) {
                Reminder.KIND_ALARM -> "¡Alarma! Son las ${java.time.LocalTime.now().let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" }}." + if (r.text != "Alarma") " ${r.text}." else ""
                Reminder.KIND_TIMER -> r.text
                else -> (if (late) "Mientras no estaba, tenías que acordarte: " else "Te recuerdo: ") + r.text
            }
            notify(if (r.kind == Reminder.KIND_REMINDER) "Recordatorio" else r.kind.replaceFirstChar(Char::uppercase), said)
            val times = if (r.kind == Reminder.KIND_REMINDER) 1 else 3
            repeat(times) {
                if (r.kind != Reminder.KIND_REMINDER) Chime.play()
                runCatching { orchestrator.speakPreview(said) }
            }
        }
        scheduler.onRoutine = { routine ->
            val order = routine.order
            val reminderText = Regex("^(?:record[aá]me|av[ií]same|decime que)\\s+(.+)$", RegexOption.IGNORE_CASE).find(order)?.groupValues?.get(1)
            if (reminderText != null) {
                notify("Rutina", reminderText)
                runCatching { orchestrator.speakPreview(reminderText) }
            } else if (!orchestrator.submitText(order)) {
                notify("Rutina pendiente", order)
            }
        }
        scheduler.start(scope)
        // Aprende qué app de música usás más (para «poné música»).
        scope.launch(Dispatchers.IO) {
            delay(20_000)
            while (isActive) {
                if (!orchestrator.isInteractionActive) media.sampleUsage()
                delay(60_000)
            }
        }
        // Prepara Windows de antemano para que la primera orden sea rápida.
        scope.launch(Dispatchers.IO) {
            runCatching { system.call("ping") }
            runCatching { voiceBridge.call("ping") }
            appLauncher.installed()
            models.model()
        }
        orchestrator.setWakeWordEnabled(settings.current.wakeWordEnabled && wakeAvailableBlocking())
    }

    private fun wakeAvailableBlocking() = kotlinx.coroutines.runBlocking { wake.availability() is Availability.Available }

    fun shutdown() {
        orchestrator.shutdown()
        microphone.stop()
        if (ducked) runCatching { system.call("duck", mapOf("on" to false, "exclude" to emptyList<Long>())) }
        system.stop()
        voiceBridge.stop()
    }

    private fun LucySettings.toAssistantConfig() = AssistantConfig(
        wakeWordSensitivity = sensitivity,
        acknowledgement = acknowledgement,
        recognitionMode = EngineMode.OFFLINE,
        voiceMode = EngineMode.OFFLINE,
        languageTag = "es-AR",
        endSilenceMillis = speechPauseMillis.toLong(),
        continuousConversation = continuousConversation,
        quickResponse = quickResponse,
        chatFollowUp = chatFollowUp,
        wakeRequiresPrefix = heyPrefix,
        offlineVoice = VoiceParams(voiceId, speechRate, voiceVolume, "es-AR"),
    )

    private companion object {
        const val TAG = "Lucy"
    }
}
