package com.lucyremota.emisor

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.lucyremota.emisor.audio.AndroidActivationFeedback
import com.lucyremota.emisor.audio.AndroidVolumeControl
import com.lucyremota.emisor.audio.AudioFocusHelper
import com.lucyremota.emisor.audio.ChimePlayer
import com.lucyremota.emisor.audio.MicrophoneSource
import com.lucyremota.emisor.azure.AzureCredentials
import com.lucyremota.emisor.azure.AzureNeuralVoiceEngine
import com.lucyremota.emisor.azure.AzureSpeechRecognitionEngine
import com.lucyremota.emisor.data.ConnectivityMonitor
import com.lucyremota.emisor.data.DiagnosticsLog
import com.lucyremota.emisor.data.FileCommandHistory
import com.lucyremota.emisor.data.SecretStore
import com.lucyremota.emisor.media.AndroidMediaControl
import com.lucyremota.emisor.settings.OfflineRecognizerType
import com.lucyremota.emisor.settings.OnlineRecognizerType
import com.lucyremota.emisor.settings.OnlineVoiceType
import com.lucyremota.emisor.settings.SaraSettings
import com.lucyremota.emisor.settings.SettingsRepository
import com.lucyremota.emisor.settings.WakeEngineType
import com.lucyremota.emisor.speech.AndroidSpeechRecognitionEngine
import com.lucyremota.emisor.speech.vosk.VoskModelManager
import com.lucyremota.emisor.speech.vosk.VoskSpeechRecognitionEngine
import com.lucyremota.emisor.speech.vosk.VoskWakeWordEngine
import com.lucyremota.emisor.voice.AndroidTtsVoiceEngine
import com.lucyremota.emisor.voice.SharedTextToSpeech
import com.lucyremota.emisor.wakeword.PorcupineFiles
import com.lucyremota.emisor.wakeword.PorcupineWakeWordEngine
import com.lucyremota.nucleo.assistant.AssistantConfig
import com.lucyremota.nucleo.assistant.AssistantOrchestrator
import com.lucyremota.nucleo.engine.EngineProvider
import com.lucyremota.nucleo.engine.SpeechRecognitionEngine
import com.lucyremota.nucleo.engine.VoiceEngine
import com.lucyremota.nucleo.engine.VoiceParams
import com.lucyremota.nucleo.engine.WakeWordEngine
import com.lucyremota.nucleo.execution.DefaultCommandExecutor
import com.lucyremota.nucleo.interpreter.CompositeCommandInterpreter
import com.lucyremota.nucleo.interpreter.RuleBasedCommandInterpreter
import com.lucyremota.nucleo.permissions.PermissionSnapshot
import com.lucyremota.nucleo.util.AudioLevelMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Inyección de dependencias manual: un único grafo de objetos por proceso. Se eligió en lugar de
 * un framework porque el grafo es pequeño y así se evita procesamiento de anotaciones.
 */
private const val BLUETOOTH_VOICE_GAIN = 2f
private const val POCKET_VOICE_GAIN = 3f

class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** La app está abierta o en Recientes (para la escucha «con la app abierta»). */
    val appTaskAlive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val logger = DiagnosticsLog()
    val levels = AudioLevelMeter()
    val settings = SettingsRepository(app, scope)
    val secrets = SecretStore(app)
    val connectivity = ConnectivityMonitor(app)
    /** Micrófono de las órdenes con Bluetooth conectado (auto, auriculares). */
    val micRouter = com.lucyremota.emisor.audio.MicRouter(app, { settings.settings.value.micChoice }, logger)
    /** En el bolsillo la tela apaga la voz: ahí (y sólo ahí) el micrófono se refuerza al triple. */
    val pocket = com.lucyremota.emisor.audio.PocketDetector(app).also { it.start() }
    // Con Bluetooth (el auto) el teléfono queda lejos: la voz se refuerza al doble.
    val microphone = MicrophoneSource(app, levels, { settings.settings.value.echoCancellation }, { micRouter.captureDevice() }) {
        (if (micRouter.bluetoothAudioConnected()) BLUETOOTH_VOICE_GAIN else 1f) * (if (pocket.inPocket) POCKET_VOICE_GAIN else 1f)
    }
    private val phraseCache = com.lucyremota.emisor.voice.PhraseCache(app)
    val voskModels = VoskModelManager(app, logger)
    val porcupineFiles = PorcupineFiles(app)
    val history = FileCommandHistory(app) { settings.settings.value.saveHistory }
    val focus = AudioFocusHelper(app)
    private val tts = SharedTextToSpeech(app)
    val azure = AzureCredentials(secrets) { settings.settings.value.azureRegion }

    val activityStarter = com.lucyremota.emisor.apps.ActivityStarter(app, ::isAppInForeground)
    val appLauncher = com.lucyremota.emisor.apps.AndroidAppLauncher(app, activityStarter, logger)
    val musicUsage = com.lucyremota.emisor.media.MusicUsageTracker(app, logger) { taste -> memory.put(com.lucyremota.nucleo.memory.MemoryKeys.MUSIC_TASTE, taste) }
    val mediaControl = AndroidMediaControl(app, activityStarter, appLauncher, musicUsage, logger)
    private val messenger = com.lucyremota.emisor.apps.WhatsAppMessenger(app, activityStarter, logger)

    /** Preferencias y rutinas que el usuario le enseña a Sara. */
    val memory = com.lucyremota.emisor.data.PrefsMemoryStore(app)
    private val rules = RuleBasedCommandInterpreter()

    // ---------------------------------------------------------------- Habilidades y cerebro
    val scheduler = com.lucyremota.emisor.skills.Scheduler(app)
    private val location = com.lucyremota.emisor.skills.LocationProvider(app)
    private val lists = com.lucyremota.nucleo.skills.ListBook(com.lucyremota.emisor.skills.PrefsTextStore(app, "lucy_lists"))
    private val plannerSupport = com.lucyremota.nucleo.ai.PlannerSupport({ skills }, memory)
    private val claude = com.lucyremota.nucleo.ai.ClaudeAssistant({ secrets.get(SecretStore.ANTHROPIC_KEY) }, plannerSupport, logger)
    private val gemini = com.lucyremota.nucleo.ai.GeminiAssistant({ secrets.get(SecretStore.GEMINI_KEY)?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_DEFAULT_KEY.takeIf { it.isNotBlank() } }, memory, logger, plannerSupport)
    /** IA: Google para preguntas con datos actuales; Claude (si hay clave) para planificar. */
    val ai = com.lucyremota.nucleo.ai.AiRouter({ settings.settings.value.aiProvider }, gemini, claude)

    /** Hace [action] cuando Sara termina de hablar y se cierra la esfera (p. ej. bloquear la pantalla). */
    private suspend fun waitForIdleThen(action: suspend () -> Unit) {
        kotlinx.coroutines.delay(300)
        kotlinx.coroutines.withTimeoutOrNull(20_000) { while (orchestrator.isInteractionActive) kotlinx.coroutines.delay(150) }
        kotlinx.coroutines.delay(700)
        action()
    }

    /** Todas las habilidades que Sara (y la IA) pueden usar. */
    val skills: com.lucyremota.nucleo.skills.SkillRegistry by lazy {
        com.lucyremota.nucleo.skills.SkillRegistry(
            listOf(
                com.lucyremota.nucleo.skills.CommandSkills { executor },
                com.lucyremota.nucleo.skills.ListsSkill(lists),
                com.lucyremota.nucleo.skills.CalculatorSkill(),
                com.lucyremota.nucleo.skills.MusicTasteSkill(memory),
                com.lucyremota.emisor.skills.AlarmSkill(app, activityStarter),
                com.lucyremota.emisor.skills.ReminderSkill(scheduler),
                com.lucyremota.emisor.skills.RoutineSkill(scheduler),
                com.lucyremota.emisor.skills.WeatherSkill(location),
                com.lucyremota.emisor.skills.DeviceSkill(app, activityStarter) { action -> scope.launch { waitForIdleThen(action) } },
                com.lucyremota.emisor.skills.CallsSkill(app, activityStarter),
                com.lucyremota.emisor.skills.CalendarSkill(app, activityStarter),
                com.lucyremota.emisor.skills.MapsSkill(activityStarter, location),
                com.lucyremota.emisor.skills.NotificationsSkill(app),
                com.lucyremota.emisor.skills.WhatsAppReadSkill(app, activityStarter),
                com.lucyremota.emisor.skills.GoogleSkill(ai),
                com.lucyremota.emisor.skills.TranslatorSkill(ai),
                com.lucyremota.emisor.skills.SmartHomeSkill(app, activityStarter),
                com.lucyremota.emisor.skills.DiagnosticsSkill(
                    missing = { com.lucyremota.emisor.permissions.PermissionCenter.missing(app).filter { it in com.lucyremota.emisor.permissions.PermissionCenter.ESSENTIAL } },
                    describe = { com.lucyremota.emisor.permissions.PermissionCenter.info(it).title.lowercase() },
                    checks = { diagnosticChecks() },
                ),
            ),
            isGranted = { com.lucyremota.emisor.permissions.PermissionCenter.isGranted(app, it) },
        )
    }

    // ---------------------------------------------------------------- Computadoras (Lucy Remota)
    /** Las PC vinculadas (se elige una a la vez). */
    val phoneStore = com.lucyremota.emisor.pc.PrefsPhoneStore(app)
    val orders = com.lucyremota.remoto.OrderLog()
    val link: com.lucyremota.protocolo.PhoneLink by lazy {
        com.lucyremota.protocolo.PhoneLink(phoneStore, com.lucyremota.emisor.pc.KeystoreIdentity(), { logger.d("Enlace", it) })
    }
    @Volatile private var lastHeard = ""
    /** Música, volumen y apps a la PC conectada; todo lo demás (y sin PC) en el teléfono, como Sara. */
    private val remoteExecutor by lazy { com.lucyremota.remoto.RemoteExecutor(link, executor, orders, { lastHeard }, phoneFallback = true) }
    @Volatile private var linkStarted = false

    /** Abre la conexión con las PC (una sola vez) y la rehace al cambiar de red. */
    @Synchronized
    fun startLink() {
        if (linkStarted) return
        runCatching { link.start() }.onSuccess { linkStarted = true }.onFailure { logger.w("Enlace", "No pude abrir la conexión", it) }
        runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private var currentNetwork: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (currentNetwork != null && currentNetwork != network) link.networkChanged()
                    currentNetwork = network
                }
                override fun onLost(network: android.net.Network) { link.networkChanged() }
            })
        }.onFailure { logger.w("Enlace", "Sin aviso de cambios de red", it) }
    }

    private val executor: DefaultCommandExecutor by lazy {
        DefaultCommandExecutor(
            mediaControl, AndroidVolumeControl(app), hasMediaSessionAccess = mediaControl::hasNotificationAccess, apps = appLauncher,
            messenger = messenger, confirmMessages = { settings.settings.value.confirmMessages },
            memory = memory, assistant = com.lucyremota.nucleo.ai.FastKnowledgeAssistant(ai, com.lucyremota.nucleo.ai.Wikipedia(logger)), skills = skills, planner = ai,
        )
    }

    /** Lo que el diagnóstico revisa además de los permisos. */
    private suspend fun diagnosticChecks(): List<String> = buildList {
        if (!connectivity.isOnline()) add("no hay conexión a Internet")
        if (!ai.isAvailable()) add("no hay clave de IA cargada (sólo funciones locales)")
        if (com.lucyremota.emisor.apps.SaraAccessibilityService.isEnabled(app) && !com.lucyremota.emisor.apps.SaraAccessibilityService.isWorking(app)) {
            add("la accesibilidad está activada pero trabada: apagala y prendela")
        }
        val wake = engines.wakeWordEngine()
        val availability = runCatching { wake?.availability() }.getOrNull()
        if (availability is com.lucyremota.nucleo.engine.Availability.Unavailable) add("la detección de «Lucy» no está lista: ${availability.detail}")
        if (!scheduler.exactAlarmsAllowed()) add("las alarmas exactas están limitadas: los recordatorios pueden demorarse unos minutos")
    }
    val updates = com.lucyremota.emisor.update.UpdateManager(app, logger)

    // Motores (instancias reutilizables; la selección depende de los ajustes vigentes).
    val voskWake = VoskWakeWordEngine(voskModels, microphone, logger)
    private val porcupineWake = PorcupineWakeWordEngine(app, secrets, porcupineFiles, microphone)
    val voskRecognizer = VoskSpeechRecognitionEngine(voskModels, microphone) { settings.settings.value.voiceBoost }
    private val onDeviceRecognizer = AndroidSpeechRecognitionEngine(app, onDevice = true, levels)
    // El reconocedor de Google graba él mismo (pasarle el audio de Sara lo hacía fallar en algunos teléfonos).
    private val systemRecognizer = AndroidSpeechRecognitionEngine(app, onDevice = false, levels)
    private val azureRecognizer = AzureSpeechRecognitionEngine(azure, microphone)
    val offlineTts = AndroidTtsVoiceEngine(app, tts, focus, phraseCache, networkVoices = false)
    val systemNetworkTts = AndroidTtsVoiceEngine(app, tts, focus, phraseCache, networkVoices = true)
    /** Voz Daniela (Piper): se baja sola por Wi-Fi; mientras tanto habla la voz del teléfono. */
    val piperModels = com.lucyremota.emisor.voice.PiperModelManager(app, logger) { settings.settings.value.piperVoice }
    val piperVoice = com.lucyremota.emisor.voice.PiperVoiceEngine(piperModels, { settings.settings.value.piperVoice }, offlineTts, focus, levels, logger)

    /** Baja la voz Daniela si está elegida y falta (por Wi-Fi, o [force] desde el botón de Ajustes). */
    fun ensurePiper(force: Boolean = false) {
        piperModels.refresh()
        if (!settings.settings.value.usePiper || piperModels.isInstalled()) return
        if (piperModels.state.value is com.lucyremota.emisor.speech.vosk.ModelState.Downloading) return
        if (force || piperModels.onUnmeteredNetwork()) scope.launch { piperModels.download() }
    }

    val azureVoice = AzureNeuralVoiceEngine(azure, focus, levels, phraseCache)

    val engines = object : EngineProvider {
        private val s: SaraSettings get() = settings.settings.value

        override fun wakeWordEngine(): WakeWordEngine = when (s.wakeEngine) {
            WakeEngineType.VOSK -> voskWake
            WakeEngineType.PORCUPINE -> porcupineWake
        }

        override fun onlineRecognizer(): SpeechRecognitionEngine = when (s.onlineRecognizer) {
            OnlineRecognizerType.SYSTEM -> systemRecognizer
            OnlineRecognizerType.AZURE -> azureRecognizer
        }

        override fun offlineRecognizer(): SpeechRecognitionEngine = when (s.offlineRecognizer) {
            OfflineRecognizerType.VOSK -> voskRecognizer
            OfflineRecognizerType.ANDROID_ON_DEVICE -> onDeviceRecognizer
            OfflineRecognizerType.AUTO ->
                if (voskModels.isInstalled() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) voskRecognizer else onDeviceRecognizer
        }

        override fun onlineVoice(): VoiceEngine = when (s.onlineVoice) {
            OnlineVoiceType.AZURE -> azureVoice
            OnlineVoiceType.SYSTEM_NETWORK -> systemNetworkTts
        }

        override fun offlineVoice(): VoiceEngine = if (s.usePiper) piperVoice else offlineTts
    }

    val orchestrator = AssistantOrchestrator(
        scope = scope,
        engines = engines,
        interpreter = com.lucyremota.remoto.PcInterpreter(CompositeCommandInterpreter(rules, memory = memory, aiAvailable = ai::isAvailable, plannerAvailable = ai::isAvailable)),
        executor = remoteExecutor,
        connectivity = connectivity,
        configProvider = { settings.current().toAssistantConfig() },
        feedback = AndroidActivationFeedback(ChimePlayer(), focus, logger, micRouter) { settings.settings.value.musicWhileListening },
        history = history,
        logger = logger,
    )

    init {
        startLink()
        scope.launch {
            orchestrator.events.collect {
                if (it is com.lucyremota.nucleo.model.AssistantEvent.Heard) lastHeard = it.text
                if (it is com.lucyremota.nucleo.model.AssistantEvent.Responded) logger.d("Respuesta", it.text)
            }
        }
        scope.launch { history.load() }
        ensurePiper()
        if (mediaControl.hasNotificationAccess()) musicUsage.start()
        // Recordatorios y rutinas: al iniciar el proceso se vuelven a programar por si se perdieron.
        scope.launch { runCatching { scheduler.rescheduleAll() } }
        // Si una orden necesita un permiso y Sara está en segundo plano, deja una notificación con
        // el botón que lleva directo a activarlo (en primer plano lo muestra la pantalla de inicio).
        // Calidad de cada micrófono: cuánto se destaca la voz en cada orden (para el modo Automático).
        scope.launch {
            val samples = ArrayList<Float>()
            var listening = false
            var bluetooth = false
            launch { levels.level.collect { if (listening) synchronized(samples) { samples += it } } }
            orchestrator.state.collect { state ->
                val now = state is com.lucyremota.nucleo.model.AssistantState.Listening
                if (now && !listening) {
                    synchronized(samples) { samples.clear() }
                    bluetooth = micRouter.bluetoothActive
                } else if (!now && listening && micRouter.bluetoothMic() != null) {
                    micRouter.contrastOf(synchronized(samples) { samples.toList() })?.let { micRouter.record(bluetooth, it) }
                }
                listening = now
            }
        }
        scope.launch {
            orchestrator.events.collect { event ->
                if (event is com.lucyremota.nucleo.model.AssistantEvent.PermissionNeeded && !isAppInForeground()) {
                    com.lucyremota.emisor.permissions.PermissionCenter.notify(app, event.kind)
                }
                // La esfera aparece sobre lo que sea (inicio, otra app, bloqueo) al llamarla.
                if (event is com.lucyremota.nucleo.model.AssistantEvent.Activated && settings.settings.value.sphereOverlay &&
                    event.trigger != com.lucyremota.nucleo.model.Trigger.ASSIST_GESTURE
                ) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { com.lucyremota.emisor.ui.SphereActivity.show(app, isAppInForeground()) }
                }
            }
        }
    }

    fun SaraSettings.toAssistantConfig() = AssistantConfig(
        wakeWordSensitivity = sensitivity,
        acknowledgement = acknowledgement,
        // En el auto (Bluetooth conectado) se puede pedir Google aunque el modo sea Offline.
        recognitionMode = if (onlineInCar && recognitionMode == com.lucyremota.nucleo.engine.EngineMode.OFFLINE && micRouter.bluetoothAudioConnected()) com.lucyremota.nucleo.engine.EngineMode.AUTOMATIC else recognitionMode,
        allowRecognitionFallback = allowRecognitionFallback,
        // Daniela es local: con ella elegida no se usan voces online.
        voiceMode = if (usePiper) com.lucyremota.nucleo.engine.EngineMode.OFFLINE else voiceMode,
        languageTag = language,
        endSilenceMillis = speechPauseMillis.toLong(),
        continuousConversation = continuousConversation,
        quickResponse = quickResponse,
        chatFollowUp = chatFollowUp,
        repeatCommands = repeatCommands,
        wakeRequiresPrefix = heySara,
        onlineVoice = VoiceParams(onlineVoiceId, speechRate, voiceVolume, language),
        offlineVoice = VoiceParams(offlineVoiceId, speechRate, voiceVolume, language),
    )

    /**
     * «Comparar micrófonos»: graba 3 s con el del teléfono y 3 s con el del Bluetooth mientras
     * hablás, mide en cuál se destaca más tu voz y lo anota para el modo Automático.
     */
    suspend fun compareMicrophones(): String = orchestrator.withMicrophoneReleased {
        if (micRouter.bluetoothMic() == null) return@withMicrophoneReleased "No hay un Bluetooth con micrófono conectado."
        suspend fun measure(): Float? {
            val values = ArrayList<Float>()
            val start = System.currentTimeMillis()
            microphone.capture(echoCancellation = false) { buf, len ->
                values += com.lucyremota.nucleo.util.AudioLevelMeter.levelOfPcm16(buf, len)
                System.currentTimeMillis() - start < 3_000
            }
            return micRouter.contrastOf(values)
        }
        val phone = measure()
        micRouter.beginInteraction(force = true)
        val bt = try { if (micRouter.bluetoothActive) measure() else null } finally { micRouter.endInteraction() }
        phone?.let { micRouter.record(false, it) }
        bt?.let { micRouter.record(true, it) }
        fun pct(v: Float?) = v?.let { "${(it * 100).toInt()}/100" } ?: "no se pudo medir"
        val better = when {
            phone == null || bt == null -> null
            bt > phone + 0.03f -> "el del Bluetooth"
            else -> "el del teléfono"
        }
        "Teléfono: ${pct(phone)} · Bluetooth: ${pct(bt)}." + (better?.let { " Te escucha mejor $it; en Automático uso ese." } ?: "")
    }

    fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun permissionSnapshot(): PermissionSnapshot = PermissionSnapshot(
        sdkInt = Build.VERSION.SDK_INT,
        recordAudio = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        postNotifications = Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        notificationListener = mediaControl.hasNotificationAccess(),
    )

    fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roleManager = app.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
}

val Context.container: AppContainer get() = (applicationContext as SaraApp).container

class SaraApp : Application() {
    lateinit var container: AppContainer
        private set

    companion object {
        fun containerOrNull(context: Context): AppContainer? =
            (context.applicationContext as? SaraApp)?.takeIf { it::container.isInitialized }?.container
    }

    override fun onCreate() {
        super.onCreate()
        // Lucy: todo lo de Sara en el teléfono, y además maneja tus computadoras.
        com.lucyremota.nucleo.model.AssistantIdentity.name = "Lucy"
        com.lucyremota.nucleo.model.AssistantIdentity.capabilities =
            "En el teléfono hago de todo (música, WhatsApp, llamadas, alarmas, preguntas) y además manejo tu computadora: música, volumen, programas, ventanas y apagado."
        com.lucyremota.nucleo.util.WakeWordTuning.profile = com.lucyremota.nucleo.util.WakeWordProfile.LUCY
        container = AppContainer(this)
        com.lucyremota.emisor.services.SaraNotifications.createChannels(this)
    }
}
