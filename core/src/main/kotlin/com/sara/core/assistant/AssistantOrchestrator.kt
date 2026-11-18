package com.sara.core.assistant

import com.sara.core.engine.EngineErrorType
import com.sara.core.engine.EngineMode
import com.sara.core.engine.EngineProvider
import com.sara.core.engine.EngineError
import com.sara.core.engine.RecognitionRequest
import com.sara.core.engine.RecognitionResult
import com.sara.core.engine.RecognitionRouter
import com.sara.core.engine.SpeakResult
import com.sara.core.engine.VoiceParams
import com.sara.core.engine.VoiceRouter
import com.sara.core.engine.WakeWordConfig
import com.sara.core.engine.WakeWordResult
import com.sara.core.engine.Availability
import com.sara.core.engine.ConnectivityStatus
import com.sara.core.engine.UnavailableReason
import com.sara.core.execution.CommandExecutor
import com.sara.core.execution.ExecutionResult
import com.sara.core.interpreter.ConfirmationParser
import com.sara.core.execution.ResponseCatalog
import com.sara.core.interpreter.CommandInterpreter
import com.sara.core.model.AssistantError
import com.sara.core.model.AssistantEvent
import com.sara.core.model.AssistantState
import com.sara.core.model.CommandIntent
import com.sara.core.model.ErrorKind
import com.sara.core.model.HistoryEntry
import com.sara.core.model.Trigger
import com.sara.core.model.label
import com.sara.core.state.AssistantStateMachine
import com.sara.core.util.SaraLogger
import com.sara.core.util.WakeWordTuning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

enum class Acknowledgement { VOICE, CHIME, VOICE_AND_CHIME, NONE }

/** Configuración efectiva de una interacción (instantánea de los ajustes). */
data class AssistantConfig(
    val wakeWordSensitivity: Float = WakeWordTuning.DEFAULT_SENSITIVITY,
    val acknowledgement: Acknowledgement = Acknowledgement.VOICE,
    val recognitionMode: EngineMode = EngineMode.AUTOMATIC,
    val allowRecognitionFallback: Boolean = true,
    val voiceMode: EngineMode = EngineMode.AUTOMATIC,
    val languageTag: String = "es-AR",
    val onlineVoice: VoiceParams = VoiceParams(),
    val offlineVoice: VoiceParams = VoiceParams(),
    val noSpeechTimeoutMillis: Long = 7_000,
    val maxSpeechMillis: Long = 12_000,
    /** Pausa permitida al hablar antes de cortar la escucha. */
    val endSilenceMillis: Long = 1_700,
    /** Si no entendió, vuelve a preguntar una vez en lugar de salir. */
    val retryOnce: Boolean = true,
    /** Después de responder sigue escuchando unos segundos para seguir la charla sin decir «Sara». */
    val continuousConversation: Boolean = false,
    /** Palabra de activación «Ey Sara» (true) o «Sara» sola (false). */
    val wakeRequiresPrefix: Boolean = true,
    val followUpSilenceMillis: Long = 5_000,
    /** Charla: después de responder una pregunta (no una orden) sigue escuchando para conversar. */
    val chatFollowUp: Boolean = false,
    /** Respuesta rápida: ejecuta las órdenes completas sin esperar la pausa larga de fin de frase. */
    val quickResponse: Boolean = false,
)

/** Señales no verbales y manejo de foco de audio, implementadas por la plataforma. */
interface ActivationFeedback {
    suspend fun chime()
    fun interactionStarted()
    fun interactionEnded()

    object None : ActivationFeedback {
        override suspend fun chime() = Unit
        override fun interactionStarted() = Unit
        override fun interactionEnded() = Unit
    }
}

interface CommandHistory {
    suspend fun record(entry: HistoryEntry)
}

/**
 * Coordina el ciclo completo: espera de la palabra de activación -> escucha -> interpretación ->
 * ejecución -> respuesta hablada -> regreso a la espera. La activación manual y la de voz comparten
 * exactamente el mismo flujo de reconocimiento e interpretación.
 */
class AssistantOrchestrator(
    private val scope: CoroutineScope,
    private val engines: EngineProvider,
    private val interpreter: CommandInterpreter,
    private val executor: CommandExecutor,
    private val connectivity: ConnectivityStatus,
    private val configProvider: suspend () -> AssistantConfig,
    private val feedback: ActivationFeedback = ActivationFeedback.None,
    private val history: CommandHistory? = null,
    private val responses: ResponseCatalog = ResponseCatalog(),
    private val logger: SaraLogger = SaraLogger.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val errorDisplayMillis: Long = 1_500,
) {
    private val machine = AssistantStateMachine()
    val state: StateFlow<AssistantState> = machine.state

    private val _events = MutableSharedFlow<AssistantEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AssistantEvent> = _events.asSharedFlow()

    private val recognitionRouter = RecognitionRouter(connectivity, logger)
    private val voiceRouter = VoiceRouter(connectivity, logger)

    private val lock = Any()
    @Volatile private var wakeWordEnabled = false
    private var wakeJob: Job? = null
    private var interactionJob: Job? = null
    /** La orden vino pegada al nombre: no se pregunta «¿Sí?». */
    @Volatile private var inlineCommand = false

    val isWakeWordEnabled: Boolean get() = wakeWordEnabled
    val isInteractionActive: Boolean get() = synchronized(lock) { interactionJob?.isActive == true }

    /** Activa o desactiva la escucha continua de "Sara". */
    fun setWakeWordEnabled(enabled: Boolean) = synchronized(lock) {
        wakeWordEnabled = enabled
        if (enabled) {
            if (interactionJob?.isActive != true && wakeJob?.isActive != true) startWakeLoopLocked()
        } else {
            wakeJob?.cancel()
            wakeJob = null
            if (interactionJob?.isActive != true) machine.reset(AssistantState.Inactive)
        }
    }

    /** Inicia una interacción (botón, gesto de asistente, notificación). Devuelve `false` si ya hay una en curso. */
    fun activate(trigger: Trigger = Trigger.MANUAL): Boolean = synchronized(lock) {
        if (interactionJob?.isActive == true) return false
        val toCancel = wakeJob
        wakeJob = null
        launchInteractionLocked(trigger, toCancel)
        true
    }

    /**
     * Procesa una orden escrita (sin reconocimiento de voz) con el mismo intérprete, ejecutor y
     * respuesta hablada. Útil para entrada de texto y para pruebas en emulador.
     */
    fun submitText(text: String): Boolean = synchronized(lock) {
        if (interactionJob?.isActive == true) return false
        val toCancel = wakeJob
        wakeJob = null
        launchInteractionLocked(Trigger.MANUAL, toCancel, presetText = text)
        true
    }

    /** Cancela la interacción actual; si la detección continua está activa, Sara vuelve a esperar. */
    fun cancelInteraction() {
        synchronized(lock) { interactionJob }?.cancel()
    }

    fun shutdown() {
        setWakeWordEnabled(false)
        cancelInteraction()
    }

    /**
     * Libera el micrófono (pausando la detección) mientras se ejecuta [block], por ejemplo para
     * atender un pedido del RecognitionService del sistema. Luego restaura la detección.
     */
    suspend fun <T> withMicrophoneReleased(block: suspend () -> T): T {
        val job = synchronized(lock) { wakeJob.also { wakeJob = null } }
        job?.cancelAndJoin()
        try {
            return block()
        } finally {
            synchronized(lock) {
                if (wakeWordEnabled && interactionJob?.isActive != true && wakeJob?.isActive != true) startWakeLoopLocked()
            }
        }
    }

    /** Hace hablar a Sara fuera de una interacción (por ejemplo, "Probar voz" en ajustes). */
    suspend fun speakPreview(text: String): SpeakResult {
        val cfg = configProvider()
        return voiceRouter.speak(cfg.voiceMode, engines.onlineVoice(), engines.offlineVoice(), text, cfg.onlineVoice, cfg.offlineVoice)
    }

    // -----------------------------------------------------------------------------------------

    /** Pregraba «Te escucho» con los motores que se pueden usar, para responder sin esperar a la red. */
    private suspend fun prepareAcknowledgements(cfg: AssistantConfig) {
        if (cfg.acknowledgement != Acknowledgement.VOICE && cfg.acknowledgement != Acknowledgement.VOICE_AND_CHIME) return
        val targets = buildList {
            if (cfg.voiceMode != EngineMode.OFFLINE) engines.onlineVoice()?.let { add(it to cfg.onlineVoice) }
            if (cfg.voiceMode != EngineMode.ONLINE) engines.offlineVoice()?.let { add(it to cfg.offlineVoice) }
        }
        for ((engine, params) in targets) {
            if (engine.availability() !is Availability.Available) continue
            for (phrase in responses.listeningPhrases + responses.retryPhrases + responses.acknowledgement) {
                runCatching { engine.prepare(phrase, params.copy(cacheable = true)) }
                    .onFailure { logger.w(TAG, "No se pudo pregrabar «$phrase»", it) }
            }
        }
    }

    private fun startWakeLoopLocked() {
        scope.launch { runCatching { prepareAcknowledgements(configProvider()) } }
        wakeJob = scope.launch {
            var failures = 0
            while (isActive) {
                val engine = engines.wakeWordEngine()
                if (engine == null) {
                    reportError(AssistantError(ErrorKind.ENGINE_UNAVAILABLE, "No hay motor de activación configurado."))
                    return@launch
                }
                val availability = engine.availability()
                if (availability is Availability.Unavailable) {
                    val kind = when (availability.reason) {
                        UnavailableReason.PERMISSION_DENIED -> ErrorKind.PERMISSION_MISSING
                        UnavailableReason.MISSING_CREDENTIALS -> ErrorKind.CREDENTIALS
                        else -> ErrorKind.ENGINE_UNAVAILABLE
                    }
                    reportError(AssistantError(kind, availability.detail))
                    return@launch
                }
                machine.moveTo(AssistantState.WaitingForWakeWord)
                val cfg = configProvider()
                when (val result = engine.awaitWakeWord(WakeWordConfig(cfg.wakeWordSensitivity, cfg.wakeRequiresPrefix))) {
                    is WakeWordResult.Detected -> {
                        logger.d(TAG, "Palabra de activación detectada (${result.confidence})")
                        val self = currentCoroutineContext()[Job]
                        synchronized(lock) {
                            if (wakeJob === self) wakeJob = null
                            if (interactionJob?.isActive != true) {
                                inlineCommand = result.commandStarted
                                launchInteractionLocked(Trigger.WAKE_WORD, null)
                            }
                        }
                        return@launch
                    }
                    is WakeWordResult.Failed -> {
                        failures++
                        val kind = errorKindOf(result.error)
                        reportError(AssistantError(kind, result.error.message))
                        if (result.error.type in FATAL_WAKE_ERRORS) return@launch
                        delay(min(30_000L, 1_000L shl min(failures, 5)))
                    }
                }
            }
        }
    }

    private fun launchInteractionLocked(trigger: Trigger, toCancel: Job?, presetText: String? = null) {
        interactionJob = scope.launch {
            try {
                toCancel?.cancelAndJoin()
                if (presetText != null) runTextInteraction(presetText) else runInteraction(trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.w(TAG, "Error inesperado en la interacción", t)
                reportError(AssistantError(ErrorKind.UNKNOWN, t.message ?: "Error inesperado"))
            } finally {
                withContext(NonCancellable) { runCatching { feedback.interactionEnded() } }
                val self = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (interactionJob === self) interactionJob = null
                    if (wakeWordEnabled) {
                        if (wakeJob?.isActive != true) startWakeLoopLocked()
                    } else {
                        machine.reset(AssistantState.Inactive)
                    }
                }
            }
        }
    }

    private suspend fun runInteraction(trigger: Trigger) {
        val started = clock()
        val cfg = configProvider()
        feedback.interactionStarted()
        _events.emit(AssistantEvent.Activated(trigger))

        val inline = trigger == Trigger.WAKE_WORD && inlineCommand
        inlineCommand = false
        when {
            inline -> Unit
            trigger == Trigger.WAKE_WORD && cfg.acknowledgement == Acknowledgement.VOICE -> speak(responses.listening(), cfg, cacheable = true)
            trigger == Trigger.WAKE_WORD && cfg.acknowledgement == Acknowledgement.VOICE_AND_CHIME -> {
                feedback.chime()
                speak(responses.listening(), cfg, cacheable = true)
            }
            cfg.acknowledgement != Acknowledgement.NONE -> feedback.chime()
        }

        var retried = false
        logger.d(TAG, "Abro el micrófono para la orden (${clock() - started} ms desde el llamado)")
        while (true) {
            machine.moveTo(AssistantState.Listening())
            val result = listen(cfg)
            when (result) {
                is RecognitionResult.Failure -> {
                    val userSilent = result.error.type == EngineErrorType.NO_SPEECH || result.error.type == EngineErrorType.NO_MATCH
                    if (userSilent && cfg.retryOnce && !retried) {
                        retried = true
                        speak(responses.askAgain(), cfg, cacheable = true)
                        continue
                    }
                    handleRecognitionFailure(result.error, cfg)
                }
                is RecognitionResult.Success -> {
                    val text = result.text.trim()
                    _events.emit(AssistantEvent.Heard(text, result.engineId))
                    machine.moveTo(AssistantState.Processing(text))
                    val interpretation = interpreter.interpret(text)
                    if (interpretation.intent is CommandIntent.Unknown && cfg.retryOnce && !retried) {
                        retried = true
                        logger.d(TAG, "\"$text\" no se entendió; pregunto de nuevo")
                        speak(responses.didNotUnderstandRetry(), cfg, cacheable = true)
                        continue
                    }
                    execute(text, interpretation, cfg)
                    when {
                        cfg.continuousConversation && interpretation.intent !is CommandIntent.Cancel -> followUpConversation(cfg)
                        // Charla: si le hiciste una pregunta (no una orden), sigue escuchando para conversar.
                        cfg.chatFollowUp && interpretation.intent is CommandIntent.Chat -> followUpConversation(cfg, chatOnly = true)
                    }
                }
            }
            return
        }
    }

    /**
     * Conversación continua: tras responder, escucha unos segundos más. Si no habla o no es una
     * orden/pregunta, termina en silencio (sin «no te entendí»), así no reacciona a ruidos.
     */
    private suspend fun followUpConversation(cfg: AssistantConfig, chatOnly: Boolean = false) {
        val quick = cfg.copy(noSpeechTimeoutMillis = if (chatOnly) CHAT_FOLLOW_UP_MS else cfg.followUpSilenceMillis)
        repeat(if (chatOnly) MAX_CHAT_TURNS else MAX_CONVERSATION_TURNS) {
            machine.moveTo(AssistantState.Listening())
            val heard = listen(quick) as? RecognitionResult.Success ?: return
            val text = heard.text.trim()
            if (text.isEmpty()) return
            _events.emit(AssistantEvent.Heard(text, heard.engineId))
            machine.moveTo(AssistantState.Processing(text))
            val interpretation = interpreter.interpret(text)
            when (interpretation.intent) {
                is CommandIntent.Unknown -> return
                is CommandIntent.Cancel -> {
                    speak(responses.cancelled(), cfg)
                    return
                }
                else -> {
                    execute(text, interpretation, cfg)
                    // En la charla, una orden («poné música») la termina: hace la orden y se calla.
                    if (chatOnly && interpretation.intent !is CommandIntent.Chat) return
                }
            }
        }
    }

    /**
     * Escucha la orden. En «respuesta rápida», si lo que va entendiendo ya es una orden completa y
     * dejaste de hablar un instante («pasá la canción», «subí el volumen», «cuánto es 35 % de 1000»),
     * no espera la pausa larga de fin de frase: corta y la ejecuta enseguida.
     */
    private suspend fun listen(cfg: AssistantConfig): RecognitionResult {
        if (!cfg.quickResponse) return listenFully(cfg) {}
        val latest = java.util.concurrent.atomic.AtomicReference("" to 0L)
        return coroutineScope {
            val full = async { listenFully(cfg) { partial -> latest.set(partial to clock()) } }
            val early = async {
                // Lo que ya se analizó y cuánto silencio necesita (se calcula una vez por texto).
                var checked = ""
                var wait = Long.MAX_VALUE
                while (isActive) {
                    delay(QUICK_POLL_MS)
                    val (partial, at) = latest.get()
                    if (partial.isBlank()) continue
                    val stable = clock() - at
                    if (stable < QUICK_MIN_STABLE_MS) continue
                    if (partial != checked) {
                        checked = partial
                        wait = quickWait(partial)
                    }
                    if (stable >= wait) {
                        logger.d(TAG, "Respuesta rápida: «$partial»")
                        return@async RecognitionResult.Success(partial, null, "rapida")
                    }
                }
                null
            }
            val result = select<RecognitionResult?> {
                full.onAwait { it }
                early.onAwait { it }
            } ?: full.await()
            full.cancel()
            early.cancel()
            result
        }
    }

    /** Cuánto silencio alcanza para dar por terminada esta orden (o «nunca» si no está completa). */
    private suspend fun quickWait(partial: String): Long {
        val intent = runCatching { interpreter.interpret(partial).intent }.getOrNull() ?: return Long.MAX_VALUE
        return when (intent) {
            CommandIntent.Pause, CommandIntent.Resume, CommandIntent.Next, CommandIntent.Previous,
            CommandIntent.StopMusic, CommandIntent.CloseBackgroundApps, CommandIntent.Cancel -> 350L
            is CommandIntent.AdjustVolume -> if (partial.trimEnd().last().isDigit()) 650L else 350L
            // Una cuenta: si termina en número puede que siga («de 10…» → «de 1000»).
            is CommandIntent.Skill -> when {
                intent.skill == "calculadora" -> if (partial.trimEnd().last().isDigit()) 650L else 400L
                intent.args.values.any { it.isBlank() } -> Long.MAX_VALUE
                else -> 700L
            }
            is CommandIntent.OpenApp -> 600L
            // «Poné música» puede seguir («…de Queen»); con tema, el tema puede ser largo.
            is CommandIntent.PlayMusic -> if (intent.query == null) 600L else 800L
            else -> Long.MAX_VALUE
        }
    }

    private suspend fun listenFully(cfg: AssistantConfig, onPartial: (String) -> Unit): RecognitionResult = recognitionRouter.recognize(
        mode = cfg.recognitionMode,
        online = engines.onlineRecognizer(),
        offline = engines.offlineRecognizer(),
        request = RecognitionRequest(cfg.languageTag, cfg.maxSpeechMillis, cfg.noSpeechTimeoutMillis, cfg.endSilenceMillis),
        allowOfflineFallback = cfg.allowRecognitionFallback,
        onPartial = { partial ->
            machine.moveTo(AssistantState.Listening(partial))
            onPartial(partial)
        },
        onSwitch = { notice ->
            _events.emit(AssistantEvent.Notice(notice.message))
            if (notice.requiresRepeat) {
                speak(notice.message, cfg)
                machine.moveTo(AssistantState.Listening())
                feedback.chime()
            }
        },
    )

    private suspend fun runTextInteraction(text: String) {
        val cfg = configProvider()
        feedback.interactionStarted()
        _events.emit(AssistantEvent.Activated(Trigger.MANUAL))
        machine.moveTo(AssistantState.Listening())
        processText(text.trim(), "texto", cfg)
    }

    /** Interpretación -> ejecución -> historial -> respuesta hablada. Compartido por voz y texto. */
    private suspend fun processText(text: String, engineId: String, cfg: AssistantConfig) {
        _events.emit(AssistantEvent.Heard(text, engineId))
        machine.moveTo(AssistantState.Processing(text))
        execute(text, interpreter.interpret(text), cfg)
    }

    private suspend fun execute(text: String, interpretation: com.sara.core.model.InterpretationResult, cfg: AssistantConfig) {
        val intent = interpretation.intent
        logger.d(TAG, "\"$text\" -> ${intent.label()} (${interpretation.ruleId}, ${interpretation.confidence})")
        if (intent !is CommandIntent.Unknown && intent !is CommandIntent.Cancel) {
            machine.moveTo(AssistantState.Executing(intent))
        }
        var result = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) { executor.execute(intent) }
            ?: ExecutionResult(false, responses.tookTooLong(), ErrorKind.EXECUTION_FAILED)
        // Preguntas de seguimiento: «¿Qué le digo?» y «¿Le mando a Mi Amor: …?».
        repeat(MAX_FOLLOW_UPS) {
            val followUp = result.followUp
            val confirmation = result.confirmation
            result = when {
                followUp != null -> {
                    val reply = ask(followUp.question, intent, cfg)
                    if (reply.isNullOrBlank()) ExecutionResult(false, responses.didNotHear(), ErrorKind.NO_SPEECH)
                    else {
                        machine.moveTo(AssistantState.Executing(intent))
                        withTimeoutOrNull(EXECUTION_TIMEOUT_MS) { followUp.onAnswer(reply) }
                            ?: ExecutionResult(false, responses.tookTooLong(), ErrorKind.EXECUTION_FAILED)
                    }
                }
                confirmation != null -> {
                    val accepted = ask(confirmation.question, intent, cfg)?.let(ConfirmationParser::parse)
                    logger.d(TAG, "Confirmación: $accepted")
                    if (accepted == true) {
                        machine.moveTo(AssistantState.Executing(intent))
                        withTimeoutOrNull(EXECUTION_TIMEOUT_MS) { confirmation.onConfirm() }
                            ?: ExecutionResult(false, responses.tookTooLong(), ErrorKind.EXECUTION_FAILED)
                    } else {
                        ExecutionResult(true, confirmation.declined)
                    }
                }
                else -> return finish(text, intent, result, cfg)
            }
        }
        finish(text, intent, result, cfg)
    }

    /** Sara pregunta algo y escucha la respuesta (null si no escuchó nada). */
    private suspend fun ask(question: String, intent: CommandIntent, cfg: AssistantConfig): String? {
        _events.emit(AssistantEvent.Responded(question, true, intent))
        speak(question, cfg)
        machine.moveTo(AssistantState.Listening())
        val answer = listen(cfg) as? RecognitionResult.Success ?: return null
        val reply = answer.text.trim()
        _events.emit(AssistantEvent.Heard(reply, answer.engineId))
        return reply
    }

    private suspend fun finish(text: String, intent: CommandIntent, execution: ExecutionResult, cfg: AssistantConfig) {
        runCatching {
            history?.record(HistoryEntry(clock(), text, intent.label(), execution.response, execution.success))
        }.onFailure { logger.w(TAG, "No se pudo guardar el historial", it) }
        _events.emit(AssistantEvent.Responded(execution.response, execution.success, intent))
        execution.needsPermission?.let { _events.emit(AssistantEvent.PermissionNeeded(it)) }
        // Una orden que salió bien: «Okey, voy.» y nada más (el detalle queda escrito en pantalla).
        val brief = execution.success && !execution.informative && execution.needsPermission == null && intent !is CommandIntent.Cancel
        when {
            brief -> speak(responses.acknowledgement, cfg, cacheable = true)
            execution.response.isNotBlank() -> speak(execution.response, cfg)
        }
    }

    private suspend fun handleRecognitionFailure(error: EngineError, cfg: AssistantConfig) {
        when (error.type) {
            EngineErrorType.NO_SPEECH, EngineErrorType.NO_MATCH -> {
                _events.emit(AssistantEvent.Notice(responses.didNotHear()))
                speak(responses.didNotHear(), cfg)
            }
            else -> {
                val assistantError = AssistantError(errorKindOf(error), error.message)
                reportError(assistantError)
                speak(error.message, cfg, showErrors = false)
            }
        }
    }

    private suspend fun speak(text: String, cfg: AssistantConfig, showErrors: Boolean = true, cacheable: Boolean = false) {
        machine.moveTo(AssistantState.Speaking(text))
        val result = voiceRouter.speak(
            cfg.voiceMode, engines.onlineVoice(), engines.offlineVoice(), text,
            cfg.onlineVoice.copy(cacheable = cacheable), cfg.offlineVoice.copy(cacheable = cacheable),
        )
        if (result is SpeakResult.Failure && showErrors) {
            reportError(AssistantError(ErrorKind.VOICE_FAILED, "No pude hablar: ${result.error.message}"))
        }
    }

    private suspend fun reportError(error: AssistantError) {
        logger.w(TAG, "${error.kind}: ${error.message}")
        machine.moveTo(AssistantState.Error(error))
        _events.emit(AssistantEvent.Failed(error))
        delay(errorDisplayMillis)
    }

    private fun errorKindOf(error: EngineError): ErrorKind = when (error.type) {
        EngineErrorType.NETWORK, EngineErrorType.SERVER -> ErrorKind.NETWORK
        EngineErrorType.CREDENTIALS -> ErrorKind.CREDENTIALS
        EngineErrorType.PERMISSION -> ErrorKind.PERMISSION_MISSING
        EngineErrorType.AUDIO_INPUT, EngineErrorType.BUSY -> ErrorKind.MICROPHONE_UNAVAILABLE
        EngineErrorType.NOT_AVAILABLE -> ErrorKind.ENGINE_UNAVAILABLE
        EngineErrorType.NO_SPEECH, EngineErrorType.NO_MATCH -> ErrorKind.NO_SPEECH
        EngineErrorType.UNKNOWN -> ErrorKind.RECOGNITION_FAILED
    }

    private companion object {
        const val TAG = "Orchestrator"
        const val QUICK_POLL_MS = 100L
        /** Silencio mínimo antes de mirar si la orden está completa. */
        const val QUICK_MIN_STABLE_MS = 300L
        const val MAX_FOLLOW_UPS = 3
        const val MAX_CONVERSATION_TURNS = 6
        /** Charla: hasta 20 idas y vueltas sin decir «Sara»; si no hablás en 7 s, vuelve a esperar. */
        const val MAX_CHAT_TURNS = 20
        const val CHAT_FOLLOW_UP_MS = 7_000L
        /** Ninguna orden puede dejar a Sara trabada: si tarda más, se corta y vuelve a escuchar. */
        const val EXECUTION_TIMEOUT_MS = 60_000L
        val FATAL_WAKE_ERRORS = setOf(EngineErrorType.PERMISSION, EngineErrorType.NOT_AVAILABLE, EngineErrorType.CREDENTIALS)
    }
}
