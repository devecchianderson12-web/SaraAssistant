package com.sara.core.assistant

import com.sara.core.engine.Availability
import com.sara.core.engine.EngineErrorType
import com.sara.core.engine.EngineMode
import com.sara.core.engine.FakeConnectivity
import com.sara.core.engine.FakeEngineProvider
import com.sara.core.engine.FakeRecognizer
import com.sara.core.engine.FakeVoice
import com.sara.core.engine.FakeWakeWord
import com.sara.core.engine.NeverEndingRecognizer
import com.sara.core.engine.UnavailableReason
import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.execution.ResponseCatalog
import com.sara.core.execution.VolumeState
import com.sara.core.execution.FakeMedia
import com.sara.core.execution.FakeVolume
import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.model.AssistantEvent
import com.sara.core.model.AssistantState
import com.sara.core.model.ErrorKind
import com.sara.core.model.HistoryEntry
import com.sara.core.model.StateKind
import com.sara.core.model.Trigger
import com.sara.core.model.kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import com.sara.core.assistant.Acknowledgement
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantOrchestratorTest {

    private class Harness(
        scope: TestScope,
        val engines: FakeEngineProvider,
        config: AssistantConfig = AssistantConfig(),
        assistant: com.sara.core.memory.ConversationalAssistant? = null,
        interpreter: com.sara.core.interpreter.CommandInterpreter = RuleBasedCommandInterpreter(),
        feedback: ActivationFeedback = ActivationFeedback.None,
    ) {
        val media = FakeMedia(MediaActionResult.Done("Música"))
        val volume = FakeVolume(VolumeState(6, 0, 15))
        val history = mutableListOf<HistoryEntry>()
        val visited = mutableListOf<StateKind>()
        val orchestrator = AssistantOrchestrator(
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)),
            engines = engines,
            interpreter = interpreter,
            executor = DefaultCommandExecutor(media, volume, ResponseCatalog(Random(0)), assistant = assistant),
            connectivity = FakeConnectivity(true),
            configProvider = { config },
            feedback = feedback,
            history = object : CommandHistory {
                override suspend fun record(entry: HistoryEntry) {
                    history += entry
                }
            },
            responses = ResponseCatalog(Random(0)),
            clock = { 1_000L },
            errorDisplayMillis = 10,
        )

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                orchestrator.state.collect { s -> if (visited.lastOrNull() != s.kind) visited += s.kind }
            }
        }
    }

    @Test
    fun `flujo completo por palabra de activacion vuelve a esperar`() = runTest {
        val wake = FakeWakeWord()
        val offline = FakeRecognizer("vosk", false).willReturn("Poné música")
        val voice = FakeVoice("tts", false)
        val h = Harness(this, FakeEngineProvider(wake = wake, offline = offline, offlineVoiceEngine = voice), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE))

        h.orchestrator.setWakeWordEnabled(true)
        runCurrent()
        assertTrue(wake.listening)
        assertEquals(AssistantState.WaitingForWakeWord, h.orchestrator.state.value)

        wake.trigger()
        advanceUntilIdle()

        assertEquals(listOf(MediaAction.PLAY to null), h.media.calls)
        assertEquals(listOf("Decime.", "Okey, voy."), voice.spoken)
        assertEquals(
            listOf(StateKind.INACTIVE, StateKind.WAITING, StateKind.SPEAKING, StateKind.LISTENING, StateKind.PROCESSING, StateKind.EXECUTING, StateKind.SPEAKING, StateKind.WAITING),
            h.visited,
        )
        assertEquals(2, wake.starts) // volvió a escuchar "Sara"
        assertEquals("Poné música", h.history.single().heard)
    }

    @Test
    fun `respuesta rapida ejecuta la orden completa sin esperar el fin de frase`() = runTest {
        // Reconocedor que entiende «pasá la canción» y después se queda esperando la pausa larga.
        val slow = object : com.sara.core.engine.SpeechRecognitionEngine {
            override val id = "lento"
            override val displayName = "lento"
            override val isOnline = false
            var cancelled = false
            override suspend fun availability() = com.sara.core.engine.Availability.Available
            override suspend fun recognize(request: com.sara.core.engine.RecognitionRequest, onPartial: (String) -> Unit): com.sara.core.engine.RecognitionResult {
                onPartial("pasá la canción")
                try {
                    kotlinx.coroutines.delay(10_000)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled = true
                    throw e
                }
                return com.sara.core.engine.RecognitionResult.Success("pasá la canción", null, id)
            }
        }
        val voice = FakeVoice("tts", false)
        val media = FakeMedia(MediaActionResult.Done("Música"))
        val orchestrator = AssistantOrchestrator(
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            engines = FakeEngineProvider(offline = slow, offlineVoiceEngine = voice),
            interpreter = RuleBasedCommandInterpreter(),
            executor = DefaultCommandExecutor(media, FakeVolume(VolumeState(6, 0, 15)), ResponseCatalog(Random(0))),
            connectivity = FakeConnectivity(true),
            configProvider = { AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE, quickResponse = true, acknowledgement = Acknowledgement.NONE) },
            responses = ResponseCatalog(Random(0)),
            clock = { testScheduler.currentTime },
        )
        orchestrator.activate()
        advanceTimeBy(1_500)
        runCurrent()
        // Ya pasó de canción antes de los 10 s que tardaba en dar la frase por terminada.
        assertEquals(listOf(MediaAction.NEXT to null), media.calls)
        assertTrue(slow.cancelled)
    }

    @Test
    fun `charla sigue escuchando despues de una pregunta y se calla despues de una orden`() = runTest {
        val offline = FakeRecognizer("vosk", false)
            .willReturn("¿cómo estás?").willReturn("¿qué hora es?").willReturn("poné música").willReturn("subí el volumen")
        val h = Harness(this, FakeEngineProvider(offline = offline, offlineVoiceEngine = FakeVoice("tts", false)),
            AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE, chatFollowUp = true))

        h.orchestrator.activate()
        advanceUntilIdle()

        assertEquals(listOf(MediaAction.PLAY to null), h.media.calls)
        assertEquals(3, offline.calls) // no siguió escuchando después de «poné música»
    }

    @Test
    fun `sin repetir la orden suena un tono y las preguntas se contestan igual`() = runTest {
        val offline = FakeRecognizer("vosk", false).willReturn("poné música").willReturn("¿qué hora es?")
        val voice = FakeVoice("tts", false)
        var tones = 0
        val feedback = object : ActivationFeedback {
            override suspend fun chime() = Unit
            override suspend fun done() { tones++ }
            override fun interactionStarted() = Unit
            override fun interactionEnded() = Unit
        }
        val h = Harness(
            this, FakeEngineProvider(offline = offline, offlineVoiceEngine = voice),
            AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE, acknowledgement = Acknowledgement.NONE, repeatCommands = false),
            feedback = feedback,
        )
        h.orchestrator.activate()
        advanceUntilIdle()
        assertEquals(1, tones)
        assertTrue(voice.spoken.isEmpty())
        h.orchestrator.activate()
        advanceUntilIdle()
        assertEquals(1, tones)
        assertTrue(voice.spoken.single().startsWith("Son las"))
    }

    @Test
    fun `si la respuesta termina en pregunta escucha la respuesta`() = runTest {
        val offline = FakeRecognizer("vosk", false)
            .willReturn("¿qué es la luna?").willReturn("sí").willReturn("subí el volumen")
        val asked = mutableListOf<String>()
        val ai = object : com.sara.core.memory.ConversationalAssistant {
            override fun isAvailable() = true
            override suspend fun reply(text: String): String {
                asked += text
                return if (asked.size == 1) "Es el satélite de la Tierra. ¿Querés que te cuente más?" else "Se formó hace 4500 millones de años."
            }
        }
        val voice = FakeVoice("tts", false)
        val h = Harness(
            this, FakeEngineProvider(offline = offline, offlineVoiceEngine = voice),
            AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE, chatFollowUp = false),
            assistant = ai,
            interpreter = com.sara.core.interpreter.CompositeCommandInterpreter(RuleBasedCommandInterpreter(), aiAvailable = { true }),
        )

        h.orchestrator.activate()
        advanceUntilIdle()

        assertEquals(listOf("¿qué es la luna?", "sí"), asked)
        // Después de una respuesta sin pregunta, se calla: no escuchó «subí el volumen».
        assertEquals(2, offline.calls)
        assertEquals(null, h.volume.lastSet)
    }

    @Test
    fun `orden pegada al nombre no pregunta y la ejecuta directo`() = runTest {
        val wake = FakeWakeWord()
        val offline = FakeRecognizer("vosk", false).willReturn("luci poné música")
        val voice = FakeVoice("tts", false)
        val h = Harness(this, FakeEngineProvider(wake = wake, offline = offline, offlineVoiceEngine = voice), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE))

        h.orchestrator.setWakeWordEnabled(true)
        runCurrent()
        wake.trigger(com.sara.core.engine.WakeWordResult.Detected(null, commandStarted = true))
        advanceUntilIdle()

        assertEquals(listOf(MediaAction.PLAY to null), h.media.calls)
        // Sin «Decime.»: sólo confirma.
        assertEquals(listOf("Okey, voy."), voice.spoken)
    }

    @Test
    fun `activacion manual comparte el mismo flujo y libera el microfono de la deteccion`() = runTest {
        val wake = FakeWakeWord()
        val offline = FakeRecognizer("vosk", false).willReturn("subí el volumen al 50%")
        val h = Harness(this, FakeEngineProvider(wake = wake, offline = offline), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE, acknowledgement = Acknowledgement.NONE))
        h.orchestrator.setWakeWordEnabled(true)
        runCurrent()
        assertTrue(wake.listening)

        assertTrue(h.orchestrator.activate(Trigger.MANUAL))
        runCurrent()
        advanceUntilIdle()
        assertEquals(8, h.volume.lastSet)
        assertEquals(AssistantState.WaitingForWakeWord, h.orchestrator.state.value)
        assertTrue(wake.listening)
    }

    @Test
    fun `sin deteccion continua la activacion manual termina en inactiva`() = runTest {
        val offline = FakeRecognizer("vosk", false).willReturn("pausá la música")
        val h = Harness(this, FakeEngineProvider(offline = offline), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE))
        h.orchestrator.activate()
        advanceUntilIdle()
        assertEquals(listOf(MediaAction.PAUSE to null), h.media.calls)
        assertEquals(AssistantState.Inactive, h.orchestrator.state.value)
    }

    @Test
    fun `no permite dos interacciones simultaneas y se puede cancelar`() = runTest {
        val h = Harness(this, FakeEngineProvider(offline = NeverEndingRecognizer()), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE))
        assertTrue(h.orchestrator.activate())
        runCurrent()
        assertEquals(StateKind.LISTENING, h.orchestrator.state.value.kind)
        assertFalse(h.orchestrator.activate())
        h.orchestrator.cancelInteraction()
        advanceUntilIdle()
        assertEquals(AssistantState.Inactive, h.orchestrator.state.value)
        assertFalse(h.orchestrator.isInteractionActive)
    }

    @Test
    fun `motor de activacion sin modelo muestra error y no reintenta en bucle`() = runTest {
        val wake = FakeWakeWord(Availability.Unavailable(UnavailableReason.MISSING_MODEL, "Falta descargar el modelo"))
        val h = Harness(this, FakeEngineProvider(wake = wake))
        val events = mutableListOf<AssistantEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.orchestrator.events.toList(events) }
        h.orchestrator.setWakeWordEnabled(true)
        advanceUntilIdle()
        val error = (h.orchestrator.state.value as AssistantState.Error).error
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, error.kind)
        assertEquals(0, wake.starts)
        assertTrue(events.any { it is AssistantEvent.Failed })
    }

    @Test
    fun `silencio del usuario se informa con voz sin quedar en error`() = runTest {
        val offline = FakeRecognizer("vosk", false).willFail(EngineErrorType.NO_SPEECH)
        val voice = FakeVoice("tts", false)
        val h = Harness(this, FakeEngineProvider(offline = offline, offlineVoiceEngine = voice), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE))
        h.orchestrator.activate()
        advanceUntilIdle()
        assertEquals(listOf("¿Qué dijiste?", "No te escuché bien. Probá de nuevo."), voice.spoken)
        assertFalse(h.visited.contains(StateKind.ERROR))
    }

    @Test
    fun `error de microfono pasa por el estado de error`() = runTest {
        val offline = FakeRecognizer("vosk", false).willFail(EngineErrorType.AUDIO_INPUT)
        val h = Harness(this, FakeEngineProvider(offline = offline), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE))
        h.orchestrator.activate()
        advanceUntilIdle()
        assertTrue(h.visited.contains(StateKind.ERROR))
        assertEquals(AssistantState.Inactive, h.orchestrator.state.value)
    }

    @Test
    fun `fallo de voz no impide ejecutar la orden`() = runTest {
        val offline = FakeRecognizer("vosk", false).willReturn("siguiente canción")
        val h = Harness(this, FakeEngineProvider(offline = offline, offlineVoiceEngine = FakeVoice("tts", false, fail = true)), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE))
        h.orchestrator.activate()
        advanceUntilIdle()
        assertEquals(listOf(MediaAction.NEXT to null), h.media.calls)
        assertTrue(h.visited.contains(StateKind.ERROR))
    }

    @Test
    fun `desactivar la deteccion libera el motor`() = runTest {
        val wake = FakeWakeWord()
        val h = Harness(this, FakeEngineProvider(wake = wake))
        h.orchestrator.setWakeWordEnabled(true)
        runCurrent()
        assertTrue(wake.listening)
        h.orchestrator.setWakeWordEnabled(false)
        runCurrent()
        assertFalse(wake.listening)
        assertEquals(AssistantState.Inactive, h.orchestrator.state.value)
    }

    @Test
    fun `orden escrita usa el mismo interprete y ejecutor`() = runTest {
        val voice = FakeVoice("tts", false)
        val h = Harness(this, FakeEngineProvider(offlineVoiceEngine = voice), AssistantConfig(voiceMode = EngineMode.OFFLINE))
        assertTrue(h.orchestrator.submitText("poné el volumen al 50%"))
        advanceUntilIdle()
        assertEquals(8, h.volume.lastSet)
        assertEquals(listOf("Okey, voy."), voice.spoken)
        assertEquals("poné el volumen al 50%", h.history.single().heard)
    }

    @Test
    fun `si no entiende pregunta de nuevo una vez y ejecuta la segunda orden`() = runTest {
        val offline = FakeRecognizer("vosk", false).willReturn("eh bueno este").willReturn("pausá la música")
        val voice = FakeVoice("tts", false)
        val h = Harness(this, FakeEngineProvider(offline = offline, offlineVoiceEngine = voice), AssistantConfig(recognitionMode = EngineMode.OFFLINE, voiceMode = EngineMode.OFFLINE, acknowledgement = Acknowledgement.NONE))
        h.orchestrator.activate()
        advanceUntilIdle()
        assertEquals("No te entendí, decímelo de nuevo.", voice.spoken.first())
        assertEquals(listOf(MediaAction.PAUSE to null), h.media.calls)
        assertEquals(2, offline.calls)
    }
}
