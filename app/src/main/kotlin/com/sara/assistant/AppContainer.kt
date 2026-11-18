package com.sara.assistant

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.sara.assistant.audio.AndroidActivationFeedback
import com.sara.assistant.audio.AndroidVolumeControl
import com.sara.assistant.audio.AudioFocusHelper
import com.sara.assistant.audio.ChimePlayer
import com.sara.assistant.audio.MicrophoneSource
import com.sara.assistant.azure.AzureCredentials
import com.sara.assistant.azure.AzureNeuralVoiceEngine
import com.sara.assistant.azure.AzureSpeechRecognitionEngine
import com.sara.assistant.data.ConnectivityMonitor
import com.sara.assistant.data.DiagnosticsLog
import com.sara.assistant.data.FileCommandHistory
import com.sara.assistant.data.SecretStore
import com.sara.assistant.media.AndroidMediaControl
import com.sara.assistant.settings.OfflineRecognizerType
import com.sara.assistant.settings.OnlineRecognizerType
import com.sara.assistant.settings.OnlineVoiceType
import com.sara.assistant.settings.SaraSettings
import com.sara.assistant.settings.SettingsRepository
import com.sara.assistant.settings.WakeEngineType
import com.sara.assistant.speech.AndroidSpeechRecognitionEngine
import com.sara.assistant.speech.vosk.VoskModelManager
import com.sara.assistant.speech.vosk.VoskSpeechRecognitionEngine
import com.sara.assistant.speech.vosk.VoskWakeWordEngine
import com.sara.assistant.voice.AndroidTtsVoiceEngine
import com.sara.assistant.voice.SharedTextToSpeech
import com.sara.assistant.wakeword.PorcupineFiles
import com.sara.assistant.wakeword.PorcupineWakeWordEngine
import com.sara.core.assistant.AssistantConfig
import com.sara.core.assistant.AssistantOrchestrator
import com.sara.core.engine.EngineProvider
import com.sara.core.engine.SpeechRecognitionEngine
import com.sara.core.engine.VoiceEngine
import com.sara.core.engine.VoiceParams
import com.sara.core.engine.WakeWordEngine
import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.interpreter.CompositeCommandInterpreter
import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.permissions.PermissionSnapshot
import com.sara.core.util.AudioLevelMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Inyección de dependencias manual: un único grafo de objetos por proceso. Se eligió en lugar de
 * un framework porque el grafo es pequeño y así se evita procesamiento de anotaciones.
 */
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
    val micRouter = com.sara.assistant.audio.MicRouter(app, { settings.settings.value.micChoice }, logger)
    val microphone = MicrophoneSource(app, levels, { settings.settings.value.echoCancellation }) { micRouter.captureDevice() }
    private val phraseCache = com.sara.assistant.voice.PhraseCache(app)
    val voskModels = VoskModelManager(app, logger)
    val porcupineFiles = PorcupineFiles(app)
    val history = FileCommandHistory(app) { settings.settings.value.saveHistory }
    val focus = AudioFocusHelper(app)
    private val tts = SharedTextToSpeech(app)
    val azure = AzureCredentials(secrets) { settings.settings.value.azureRegion }

    val activityStarter = com.sara.assistant.apps.ActivityStarter(app, ::isAppInForeground)
    val appLauncher = com.sara.assistant.apps.AndroidAppLauncher(app, activityStarter, logger)
    val musicUsage = com.sara.assistant.media.MusicUsageTracker(app, logger)
    val mediaControl = AndroidMediaControl(app, activityStarter, appLauncher, musicUsage, logger)
    private val messenger = com.sara.assistant.apps.WhatsAppMessenger(app, activityStarter, logger)

    /** Preferencias y rutinas que el usuario le enseña a Sara. */
    val memory = com.sara.assistant.data.PrefsMemoryStore(app)
    private val rules = RuleBasedCommandInterpreter()

    // ---------------------------------------------------------------- Habilidades y cerebro
    val scheduler = com.sara.assistant.skills.Scheduler(app)
    private val location = com.sara.assistant.skills.LocationProvider(app)
    private val lists = com.sara.core.skills.ListBook(com.sara.assistant.skills.PrefsTextStore(app, "sara_lists"))
    private val plannerSupport = com.sara.core.ai.PlannerSupport({ skills }, memory)
    private val claude = com.sara.core.ai.ClaudeAssistant({ secrets.get(SecretStore.ANTHROPIC_KEY) }, plannerSupport, logger)
    private val gemini = com.sara.core.ai.GeminiAssistant({ secrets.get(SecretStore.GEMINI_KEY) }, memory, logger, plannerSupport)
    /** IA: Google para preguntas con datos actuales; Claude (si hay clave) para planificar. */
    val ai = com.sara.core.ai.AiRouter({ settings.settings.value.aiProvider }, gemini, claude)

    /** Todas las habilidades que Sara (y la IA) pueden usar. */
    val skills: com.sara.core.skills.SkillRegistry by lazy {
        com.sara.core.skills.SkillRegistry(
            listOf(
                com.sara.core.skills.CommandSkills { executor },
                com.sara.core.skills.ListsSkill(lists),
                com.sara.core.skills.CalculatorSkill(),
                com.sara.assistant.skills.AlarmSkill(app, activityStarter),
                com.sara.assistant.skills.ReminderSkill(scheduler),
                com.sara.assistant.skills.RoutineSkill(scheduler),
                com.sara.assistant.skills.WeatherSkill(location),
                com.sara.assistant.skills.DeviceSkill(app, activityStarter),
                com.sara.assistant.skills.CallsSkill(app, activityStarter),
                com.sara.assistant.skills.CalendarSkill(app, activityStarter),
                com.sara.assistant.skills.MapsSkill(activityStarter, location),
                com.sara.assistant.skills.NotificationsSkill(app),
                com.sara.assistant.skills.GoogleSkill(ai),
                com.sara.assistant.skills.TranslatorSkill(ai),
                com.sara.assistant.skills.SmartHomeSkill(app, activityStarter),
                com.sara.assistant.skills.DiagnosticsSkill(
                    missing = { com.sara.assistant.permissions.PermissionCenter.missing(app).filter { it in com.sara.assistant.permissions.PermissionCenter.ESSENTIAL } },
                    describe = { com.sara.assistant.permissions.PermissionCenter.info(it).title.lowercase() },
                    checks = { diagnosticChecks() },
                ),
            ),
            isGranted = { com.sara.assistant.permissions.PermissionCenter.isGranted(app, it) },
        )
    }

    private val executor: DefaultCommandExecutor by lazy {
        DefaultCommandExecutor(
            mediaControl, AndroidVolumeControl(app), hasMediaSessionAccess = mediaControl::hasNotificationAccess, apps = appLauncher,
            messenger = messenger, confirmMessages = { settings.settings.value.confirmMessages },
            memory = memory, assistant = ai, skills = skills, planner = ai,
        )
    }

    /** Lo que el diagnóstico revisa además de los permisos. */
    private suspend fun diagnosticChecks(): List<String> = buildList {
        if (!connectivity.isOnline()) add("no hay conexión a Internet")
        if (!ai.isAvailable()) add("no hay clave de IA cargada (sólo funciones locales)")
        if (com.sara.assistant.apps.SaraAccessibilityService.isEnabled(app) && !com.sara.assistant.apps.SaraAccessibilityService.isWorking(app)) {
            add("la accesibilidad está activada pero trabada: apagala y prendela")
        }
        val wake = engines.wakeWordEngine()
        val availability = runCatching { wake?.availability() }.getOrNull()
        if (availability is com.sara.core.engine.Availability.Unavailable) add("la detección de «Sara» no está lista: ${availability.detail}")
        if (!scheduler.exactAlarmsAllowed()) add("las alarmas exactas están limitadas: los recordatorios pueden demorarse unos minutos")
    }
    val updates = com.sara.assistant.update.UpdateManager(app, logger)

    // Motores (instancias reutilizables; la selección depende de los ajustes vigentes).
    val voskWake = VoskWakeWordEngine(voskModels, microphone, logger)
    private val porcupineWake = PorcupineWakeWordEngine(app, secrets, porcupineFiles, microphone)
    val voskRecognizer = VoskSpeechRecognitionEngine(voskModels, microphone) { settings.settings.value.voiceBoost }
    private val onDeviceRecognizer = AndroidSpeechRecognitionEngine(app, onDevice = true, levels)
    private val systemRecognizer = AndroidSpeechRecognitionEngine(app, onDevice = false, levels)
    private val azureRecognizer = AzureSpeechRecognitionEngine(azure, microphone)
    val offlineTts = AndroidTtsVoiceEngine(app, tts, focus, phraseCache, networkVoices = false)
    val systemNetworkTts = AndroidTtsVoiceEngine(app, tts, focus, phraseCache, networkVoices = true)
    /** Voz Daniela (Piper): se baja sola por Wi-Fi; mientras tanto habla la voz del teléfono. */
    val piperModels = com.sara.assistant.voice.PiperModelManager(app, logger)
    val piperVoice = com.sara.assistant.voice.PiperVoiceEngine(piperModels, offlineTts, focus, levels, logger)

    /** Baja la voz Daniela si está elegida y falta (por Wi-Fi, o [force] desde el botón de Ajustes). */
    fun ensurePiper(force: Boolean = false) {
        if (!settings.settings.value.usePiper || piperModels.isInstalled()) return
        if (piperModels.state.value is com.sara.assistant.speech.vosk.ModelState.Downloading) return
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
        interpreter = CompositeCommandInterpreter(rules, memory = memory, aiAvailable = ai::isAvailable, plannerAvailable = ai::isAvailable),
        executor = executor,
        connectivity = connectivity,
        configProvider = { settings.current().toAssistantConfig() },
        feedback = AndroidActivationFeedback(ChimePlayer(), focus, logger, micRouter) { settings.settings.value.musicWhileListening },
        history = history,
        logger = logger,
    )

    init {
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
                val now = state is com.sara.core.model.AssistantState.Listening
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
                if (event is com.sara.core.model.AssistantEvent.PermissionNeeded && !isAppInForeground()) {
                    com.sara.assistant.permissions.PermissionCenter.notify(app, event.kind)
                }
                // La esfera aparece sobre lo que sea (inicio, otra app, bloqueo) al llamarla.
                if (event is com.sara.core.model.AssistantEvent.Activated && settings.settings.value.sphereOverlay &&
                    event.trigger != com.sara.core.model.Trigger.ASSIST_GESTURE
                ) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { com.sara.assistant.ui.SphereActivity.show(app, isAppInForeground()) }
                }
            }
        }
    }

    fun SaraSettings.toAssistantConfig() = AssistantConfig(
        wakeWordSensitivity = sensitivity,
        acknowledgement = acknowledgement,
        // En el auto (Bluetooth conectado) se puede pedir Google aunque el modo sea Offline.
        recognitionMode = if (onlineInCar && recognitionMode == com.sara.core.engine.EngineMode.OFFLINE && micRouter.bluetoothAudioConnected()) com.sara.core.engine.EngineMode.AUTOMATIC else recognitionMode,
        allowRecognitionFallback = allowRecognitionFallback,
        // Daniela es local: con ella elegida no se usan voces online.
        voiceMode = if (usePiper) com.sara.core.engine.EngineMode.OFFLINE else voiceMode,
        languageTag = language,
        endSilenceMillis = speechPauseMillis.toLong(),
        continuousConversation = continuousConversation,
        quickResponse = quickResponse,
        chatFollowUp = chatFollowUp,
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
                values += com.sara.core.util.AudioLevelMeter.levelOfPcm16(buf, len)
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
        container = AppContainer(this)
        com.sara.assistant.services.SaraNotifications.createChannels(this)
    }
}
