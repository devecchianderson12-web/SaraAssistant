package com.sara.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

class FakeConnectivity(var online: Boolean = true) : ConnectivityStatus {
    override fun isOnline() = online
}

class FakeRecognizer(
    override val id: String,
    override val isOnline: Boolean,
    var availability: Availability = Availability.Available,
    val results: ArrayDeque<RecognitionResult> = ArrayDeque(),
) : SpeechRecognitionEngine {
    override val displayName = id
    var calls = 0
    override suspend fun availability() = availability
    override suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult {
        calls++
        return results.removeFirstOrNull() ?: RecognitionResult.Failure(EngineError(EngineErrorType.NO_SPEECH, "silencio"), id)
    }

    fun willReturn(text: String) = apply { results.addLast(RecognitionResult.Success(text, 0.9f, id)) }
    fun willFail(type: EngineErrorType) = apply { results.addLast(RecognitionResult.Failure(EngineError(type, "falla $type"), id)) }
}

class FakeVoice(
    override val id: String,
    override val isOnline: Boolean,
    var availability: Availability = Availability.Available,
    var fail: Boolean = false,
) : VoiceEngine {
    override val displayName = id
    val spoken = mutableListOf<String>()
    override suspend fun availability() = availability
    override suspend fun voices(languageTag: String) = emptyList<VoiceOption>()
    override suspend fun speak(text: String, params: VoiceParams): SpeakResult {
        if (fail) return SpeakResult.Failure(EngineError(EngineErrorType.NETWORK, "sin red"), id)
        spoken += text
        return SpeakResult.Completed(id)
    }
}

/** Motor de activación controlado por el test: espera hasta que se llame a [trigger]. */
class FakeWakeWord(var availability: Availability = Availability.Available) : WakeWordEngine {
    override val id = "fake-wake"
    override val displayName = id
    var listening = false
        private set
    var starts = 0
        private set
    private var pending: CompletableDeferred<WakeWordResult>? = null

    override suspend fun availability() = availability
    override suspend fun awaitWakeWord(config: WakeWordConfig): WakeWordResult {
        starts++
        listening = true
        val deferred = CompletableDeferred<WakeWordResult>()
        pending = deferred
        try {
            return deferred.await()
        } finally {
            listening = false
        }
    }

    fun trigger(result: WakeWordResult = WakeWordResult.Detected(0.9f)) {
        pending?.complete(result)
    }
}

class NeverEndingRecognizer(override val id: String = "slow") : SpeechRecognitionEngine {
    override val displayName = id
    override val isOnline = false
    override suspend fun availability() = Availability.Available
    override suspend fun recognize(request: RecognitionRequest, onPartial: (String) -> Unit): RecognitionResult = awaitCancellation()
}

class FakeEngineProvider(
    var wake: WakeWordEngine? = FakeWakeWord(),
    var online: SpeechRecognitionEngine? = null,
    var offline: SpeechRecognitionEngine? = null,
    var onlineVoiceEngine: VoiceEngine? = null,
    var offlineVoiceEngine: VoiceEngine? = FakeVoice("tts-local", false),
) : EngineProvider {
    override fun wakeWordEngine() = wake
    override fun onlineRecognizer() = online
    override fun offlineRecognizer() = offline
    override fun onlineVoice() = onlineVoiceEngine
    override fun offlineVoice() = offlineVoiceEngine
}
