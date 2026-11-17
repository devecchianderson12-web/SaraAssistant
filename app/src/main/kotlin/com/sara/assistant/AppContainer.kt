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
    val logger = DiagnosticsLog()
    val levels = AudioLevelMeter()
    val settings = SettingsRepository(app, scope)
    val secrets = SecretStore(app)
    val connectivity = ConnectivityMonitor(app)
    val microphone = MicrophoneSource(app, levels) { settings.settings.value.echoCancellation }
    private val phraseCache = com.sara.assistant.voice.PhraseCache(app)
    val voskModels = VoskModelManager(app, logger)
    val porcupineFiles = PorcupineFiles(app)
    val history = FileCommandHistory(app) { settings.settings.value.saveHistory }
    val focus = AudioFocusHelper(app)
    private val tts = SharedTextToSpeech(app)
    private val azure = AzureCredentials(secrets) { settings.settings.value.azureRegion }

    val activityStarter = com.sara.assistant.apps.ActivityStarter(app, ::isAppInForeground)
    val appLauncher = com.sara.assistant.apps.AndroidAppLauncher(app, activityStarter, logger)
    val musicUsage = com.sara.assistant.media.MusicUsageTracker(app, logger)
    val mediaControl = AndroidMediaControl(app, activityStarter, appLauncher, musicUsage, logger)
    private val messenger = com.sara.assistant.apps.WhatsAppMessenger(app, activityStarter, logger)
    val updates = com.sara.assistant.update.UpdateManager(app, logger)

    // Motores (instancias reutilizables; la selección depende de los ajustes vigentes).
    val voskWake = VoskWakeWordEngine(voskModels, microphone, logger)
    private val porcupineWake = PorcupineWakeWordEngine(app, secrets, porcupineFiles, microphone)
    val voskRecognizer = VoskSpeechRecognitionEngine(voskModels, microphone)
    private val onDeviceRecognizer = AndroidSpeechRecognitionEngine(app, onDevice = true, levels)
    private val systemRecognizer = AndroidSpeechRecognitionEngine(app, onDevice = false, levels)
    private val azureRecognizer = AzureSpeechRecognitionEngine(azure, microphone)
    val offlineTts = AndroidTtsVoiceEngine(app, tts, focus, phraseCache, networkVoices = false)
    val systemNetworkTts = AndroidTtsVoiceEngine(app, tts, focus, phraseCache, networkVoices = true)
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

        override fun offlineVoice(): VoiceEngine = offlineTts
    }

    val orchestrator = AssistantOrchestrator(
        scope = scope,
        engines = engines,
        interpreter = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), conversational = null),
        executor = DefaultCommandExecutor(
            mediaControl, AndroidVolumeControl(app), hasMediaSessionAccess = mediaControl::hasNotificationAccess, apps = appLauncher,
            messenger = messenger, confirmMessages = { settings.settings.value.confirmMessages },
        ),
        connectivity = connectivity,
        configProvider = { settings.current().toAssistantConfig() },
        feedback = AndroidActivationFeedback(ChimePlayer(), focus, logger),
        history = history,
        logger = logger,
    )

    init {
        scope.launch { history.load() }
        if (mediaControl.hasNotificationAccess()) musicUsage.start()
        // Si una orden necesita un permiso y Sara está en segundo plano, deja una notificación con
        // el botón que lleva directo a activarlo (en primer plano lo muestra la pantalla de inicio).
        scope.launch {
            orchestrator.events.collect { event ->
                if (event is com.sara.core.model.AssistantEvent.PermissionNeeded && !isAppInForeground()) {
                    com.sara.assistant.permissions.PermissionCenter.notify(app, event.kind)
                }
            }
        }
    }

    fun SaraSettings.toAssistantConfig() = AssistantConfig(
        wakeWordSensitivity = sensitivity,
        acknowledgement = acknowledgement,
        recognitionMode = recognitionMode,
        allowRecognitionFallback = allowRecognitionFallback,
        voiceMode = voiceMode,
        languageTag = language,
        endSilenceMillis = speechPauseMillis.toLong(),
        onlineVoice = VoiceParams(onlineVoiceId, speechRate, voiceVolume, language),
        offlineVoice = VoiceParams(offlineVoiceId, speechRate, voiceVolume, language),
    )

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
