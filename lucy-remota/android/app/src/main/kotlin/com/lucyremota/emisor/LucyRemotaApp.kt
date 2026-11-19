package com.lucyremota.emisor

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lucyremota.emisor.audio.AndroidActivationFeedback
import com.lucyremota.emisor.audio.AudioFocusHelper
import com.lucyremota.emisor.audio.ChimePlayer
import com.lucyremota.emisor.audio.MicrophoneSource
import com.lucyremota.emisor.data.DiagnosticsLog
import com.lucyremota.emisor.data.KeystoreIdentity
import com.lucyremota.remoto.OrderLog
import com.lucyremota.emisor.data.PrefsMemoryStore
import com.lucyremota.emisor.data.PrefsPhoneStore
import com.lucyremota.emisor.data.SecretStore
import com.lucyremota.emisor.data.SettingsStore
import com.lucyremota.remoto.PcInterpreter
import com.lucyremota.remoto.RemoteExecutor
import com.lucyremota.emisor.speech.AndroidSpeechRecognitionEngine
import com.lucyremota.emisor.speech.vosk.VoskModelManager
import com.lucyremota.emisor.speech.vosk.VoskSpeechRecognitionEngine
import com.lucyremota.emisor.speech.vosk.VoskWakeWordEngine
import com.lucyremota.emisor.voice.AndroidTtsVoiceEngine
import com.lucyremota.emisor.voice.PhraseCache
import com.lucyremota.emisor.voice.PiperModelManager
import com.lucyremota.emisor.voice.PiperVoiceEngine
import com.lucyremota.emisor.voice.SharedTextToSpeech
import com.lucyremota.nucleo.ai.FastKnowledgeAssistant
import com.lucyremota.nucleo.ai.GeminiAssistant
import com.lucyremota.nucleo.ai.PlannerSupport
import com.lucyremota.nucleo.ai.Wikipedia
import com.lucyremota.nucleo.assistant.AssistantConfig
import com.lucyremota.nucleo.assistant.AssistantOrchestrator
import com.lucyremota.nucleo.engine.ConnectivityStatus
import com.lucyremota.nucleo.engine.EngineMode
import com.lucyremota.nucleo.engine.EngineProvider
import com.lucyremota.nucleo.engine.SpeechRecognitionEngine
import com.lucyremota.nucleo.engine.VoiceEngine
import com.lucyremota.nucleo.engine.VoiceParams
import com.lucyremota.nucleo.engine.WakeWordEngine
import com.lucyremota.nucleo.execution.DefaultCommandExecutor
import com.lucyremota.nucleo.execution.MediaAction
import com.lucyremota.nucleo.execution.MediaActionResult
import com.lucyremota.nucleo.execution.MediaControl
import com.lucyremota.nucleo.execution.VolumeControl
import com.lucyremota.nucleo.execution.VolumeState
import com.lucyremota.nucleo.interpreter.CompositeCommandInterpreter
import com.lucyremota.nucleo.interpreter.RuleBasedCommandInterpreter
import com.lucyremota.nucleo.model.AssistantEvent
import com.lucyremota.nucleo.model.AssistantIdentity
import com.lucyremota.nucleo.skills.CalculatorSkill
import com.lucyremota.nucleo.skills.SkillRegistry
import com.lucyremota.nucleo.util.AudioLevelMeter
import com.lucyremota.nucleo.util.WakeWordProfile
import com.lucyremota.nucleo.util.WakeWordTuning
import com.lucyremota.protocolo.PhoneLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LucyRemotaApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        // Se llama Lucy: «Lucy, poné música». Lo que hace es en la computadora.
        AssistantIdentity.name = "Lucy"
        AssistantIdentity.device = "computadora"
        AssistantIdentity.platform = "un teléfono que controla una computadora con Windows"
        AssistantIdentity.capabilities = "Controlo tu computadora: música, volumen, programas, ventanas, capturas, apagado y preguntas."
        WakeWordTuning.profile = WakeWordProfile.LUCY
        container = Container(this)
    }
}

val Context.container: Container get() = (applicationContext as LucyRemotaApp).container

/** Todo lo que usa Lucy Remota (equivalente al AppContainer de Sara, pero las órdenes van a la PC). */
class Container(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logger = DiagnosticsLog()
    val levels = AudioLevelMeter()
    val settings = SettingsStore(app)
    val secrets = SecretStore(app)
    val memory = PrefsMemoryStore(app)
    val phoneStore = PrefsPhoneStore(app)
    val orders = OrderLog()
    val link: PhoneLink by lazy { PhoneLink(phoneStore, KeystoreIdentity(), { logger.d("Enlace", it) }) }
    val updates = com.lucyremota.emisor.update.Updater(app, logger)

    // ---------------------------------------------------------------- Voz (los mismos motores de Sara)
    val microphone = MicrophoneSource(app, levels)
    val voskModels = VoskModelManager(app, logger)
    private val phraseCache = PhraseCache(app)
    private val focus = AudioFocusHelper(app)
    private val tts = SharedTextToSpeech(app)
    private val wake = VoskWakeWordEngine(voskModels, microphone, logger)
    private val voskRecognizer = VoskSpeechRecognitionEngine(voskModels, microphone)
    private val systemRecognizer = AndroidSpeechRecognitionEngine(app, onDevice = false, levels)
    private val phoneVoice = AndroidTtsVoiceEngine(app, tts, focus, phraseCache, networkVoices = false)
    val piperModels = PiperModelManager(app, logger) { settings.current.piperVoice }
    private val piper = PiperVoiceEngine(piperModels, { settings.current.piperVoice }, phoneVoice, focus, levels, logger)

    private val engines = object : EngineProvider {
        override fun wakeWordEngine(): WakeWordEngine = wake
        override fun onlineRecognizer(): SpeechRecognitionEngine = systemRecognizer
        override fun offlineRecognizer(): SpeechRecognitionEngine = voskRecognizer
        override fun onlineVoice(): VoiceEngine? = null
        override fun offlineVoice(): VoiceEngine = if (settings.current.usePiper) piper else phoneVoice
    }

    // ---------------------------------------------------------------- Cerebro
    private val gemini = GeminiAssistant({ secrets.get(SecretStore.GEMINI_KEY) }, memory, logger, PlannerSupport({ SkillRegistry(emptyList()) }, memory))
    val aiAvailable: Boolean get() = gemini.isAvailable()

    /** Lo que se resuelve en el teléfono: charla, preguntas (IA o Wikipedia), cuentas y lo que le enseñás. */
    private val local = DefaultCommandExecutor(
        NoMedia, NoVolume, memory = memory, assistant = FastKnowledgeAssistant(gemini, Wikipedia(logger)),
        skills = SkillRegistry(listOf(CalculatorSkill())),
    )
    @Volatile private var lastHeard = ""
    private val executor = RemoteExecutor(link, local, orders) { lastHeard }

    private val connectivity = object : ConnectivityStatus {
        override fun isOnline(): Boolean {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
            return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
    }

    val orchestrator = AssistantOrchestrator(
        scope = scope,
        engines = engines,
        interpreter = PcInterpreter(CompositeCommandInterpreter(RuleBasedCommandInterpreter(), memory = memory, aiAvailable = { gemini.isAvailable() })),
        executor = executor,
        connectivity = connectivity,
        configProvider = { config() },
        feedback = AndroidActivationFeedback(ChimePlayer(), focus),
        logger = logger,
    )

    init {
        scope.launch {
            orchestrator.events.collect {
                if (it is AssistantEvent.Heard) lastHeard = it.text
                if (it is AssistantEvent.Responded) logger.d("Respuesta", it.text)
            }
        }
    }

    private fun config(): AssistantConfig {
        val s = settings.current
        return AssistantConfig(
            wakeWordSensitivity = s.sensitivity,
            // Google (el mejor) y, sin Internet, Vosk en el teléfono.
            recognitionMode = EngineMode.AUTOMATIC,
            voiceMode = EngineMode.OFFLINE,
            languageTag = "es-AR",
            wakeRequiresPrefix = s.heyPrefix,
            quickResponse = s.quickResponse,
            chatFollowUp = s.chatFollowUp,
            repeatCommands = s.repeatCommands,
            offlineVoice = VoiceParams(null, s.speechRate, 1f, "es-AR"),
        )
    }

    @Volatile private var linkStarted = false

    /** Abre la conexión con la PC (una sola vez; si el puerto falla queda en el diagnóstico). */
    @Synchronized
    fun startLink() {
        if (linkStarted) return
        runCatching { link.start() }.onSuccess { linkStarted = true }.onFailure { logger.w("Enlace", "No pude abrir la conexión", it) }
    }

    /** Baja lo que falta para escuchar «Lucy» y para la voz Daniela (una vez). */
    fun ensureModels() {
        scope.launch { if (!voskModels.isInstalled()) voskModels.download() }
        piperModels.refresh()
        if (settings.current.usePiper && !piperModels.isInstalled()) scope.launch { piperModels.download() }
    }

    /** La escucha de «Lucy» se prende si está activada, hay permiso y el modelo está. */
    fun refreshWake(micAllowed: Boolean) {
        orchestrator.setWakeWordEnabled(settings.current.wakeWord && micAllowed && voskModels.isInstalled())
    }

    private object NoMedia : MediaControl {
        override suspend fun perform(action: MediaAction, query: String?, app: String?) = MediaActionResult.NothingPlaying
    }

    private object NoVolume : VolumeControl {
        override fun state() = VolumeState(0, 0, 0)
        override fun setIndex(index: Int) = false
        override fun setMuted(muted: Boolean) = false
    }
}
