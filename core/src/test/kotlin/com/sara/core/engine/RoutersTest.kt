package com.sara.core.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRouterTest {
    private val request = RecognitionRequest("es-AR")

    @Test
    fun `online con conexion usa el motor online`() = runTest {
        val online = FakeRecognizer("online", true).willReturn("poné música")
        val offline = FakeRecognizer("offline", false)
        val result = RecognitionRouter(FakeConnectivity(true)).recognize(EngineMode.ONLINE, online, offline, request, allowOfflineFallback = true)
        assertEquals(RecognitionResult.Success("poné música", 0.9f, "online"), result)
        assertEquals(0, offline.calls)
    }

    @Test
    fun `modo offline nunca invoca al motor online`() = runTest {
        val online = FakeRecognizer("online", true).willReturn("x")
        val offline = FakeRecognizer("offline", false).willReturn("pausá")
        val result = RecognitionRouter(FakeConnectivity(true)).recognize(EngineMode.OFFLINE, online, offline, request, allowOfflineFallback = true)
        assertEquals("offline", result.engineId)
        assertEquals(0, online.calls)
    }

    @Test
    fun `modo offline sin motor configurado informa el error`() = runTest {
        val online = FakeRecognizer("online", true)
        val offline = FakeRecognizer("offline", false, Availability.Unavailable(UnavailableReason.MISSING_MODEL, "Falta el modelo"))
        val result = RecognitionRouter(FakeConnectivity(true)).recognize(EngineMode.OFFLINE, online, offline, request, true)
        assertTrue(result is RecognitionResult.Failure)
        assertEquals("Falta el modelo", (result as RecognitionResult.Failure).error.message)
        assertEquals(0, online.calls)
    }

    @Test
    fun `sin conexion pasa a offline sin pedir que repita`() = runTest {
        val online = FakeRecognizer("online", true)
        val offline = FakeRecognizer("offline", false).willReturn("subí el volumen")
        val notices = mutableListOf<EngineSwitchNotice>()
        val result = RecognitionRouter(FakeConnectivity(false)).recognize(EngineMode.AUTOMATIC, online, offline, request, false, onSwitch = { notices += it })
        assertEquals("offline", result.engineId)
        assertEquals(0, online.calls)
        assertEquals(false, notices.single().requiresRepeat)
    }

    @Test
    fun `falla de red durante el reconocimiento cambia a offline y pide repetir`() = runTest {
        val online = FakeRecognizer("online", true).willFail(EngineErrorType.NETWORK)
        val offline = FakeRecognizer("offline", false).willReturn("siguiente")
        val notices = mutableListOf<EngineSwitchNotice>()
        val result = RecognitionRouter(FakeConnectivity(true)).recognize(EngineMode.AUTOMATIC, online, offline, request, false, onSwitch = { notices += it })
        assertEquals(RecognitionResult.Success("siguiente", 0.9f, "offline"), result)
        assertTrue(notices.single().requiresRepeat)
    }

    @Test
    fun `modo online sin respaldo permitido devuelve el error`() = runTest {
        val online = FakeRecognizer("online", true).willFail(EngineErrorType.NETWORK)
        val offline = FakeRecognizer("offline", false).willReturn("x")
        val result = RecognitionRouter(FakeConnectivity(true)).recognize(EngineMode.ONLINE, online, offline, request, allowOfflineFallback = false)
        assertTrue(result is RecognitionResult.Failure)
        assertEquals(0, offline.calls)
    }

    @Test
    fun `silencio del usuario no dispara el respaldo`() = runTest {
        val online = FakeRecognizer("online", true).willFail(EngineErrorType.NO_SPEECH)
        val offline = FakeRecognizer("offline", false).willReturn("x")
        val result = RecognitionRouter(FakeConnectivity(true)).recognize(EngineMode.AUTOMATIC, online, offline, request, true)
        assertEquals(EngineErrorType.NO_SPEECH, (result as RecognitionResult.Failure).error.type)
        assertEquals(0, offline.calls)
    }

    @Test
    fun `si ambos motores fallan el mensaje explica los dos motivos`() = runTest {
        val online = FakeRecognizer("online", true, Availability.Unavailable(UnavailableReason.NOT_SUPPORTED, "No hay servicio online."))
        val offline = FakeRecognizer("offline", false, Availability.Unavailable(UnavailableReason.MISSING_MODEL, "Falta el modelo."))
        val result = RecognitionRouter(FakeConnectivity(true)).recognize(EngineMode.AUTOMATIC, online, offline, request, true)
        val message = (result as RecognitionResult.Failure).error.message
        assertTrue(message.contains("No hay servicio online.") && message.contains("Falta el modelo."))
    }

    @Test
    fun `sin ningun motor disponible informa claramente`() = runTest {
        val result = RecognitionRouter(FakeConnectivity(false)).recognize(EngineMode.AUTOMATIC, FakeRecognizer("online", true), null, request, true)
        assertEquals(EngineErrorType.NETWORK, (result as RecognitionResult.Failure).error.type)
    }
}

class VoiceRouterTest {
    private val params = VoiceParams()

    @Test
    fun `automatico usa online y cae a offline si falla`() = runTest {
        val online = FakeVoice("neural", true, fail = true)
        val offline = FakeVoice("local", false)
        val result = VoiceRouter(FakeConnectivity(true)).speak(EngineMode.AUTOMATIC, online, offline, "Hola", params, params)
        assertEquals(SpeakResult.Completed("local"), result)
        assertEquals(listOf("Hola"), offline.spoken)
    }

    @Test
    fun `automatico sin conexion habla offline directamente`() = runTest {
        val online = FakeVoice("neural", true)
        val offline = FakeVoice("local", false)
        VoiceRouter(FakeConnectivity(false)).speak(EngineMode.AUTOMATIC, online, offline, "Hola", params, params)
        assertTrue(online.spoken.isEmpty())
        assertEquals(listOf("Hola"), offline.spoken)
    }

    @Test
    fun `siempre online no usa respaldo`() = runTest {
        val online = FakeVoice("neural", true, fail = true)
        val offline = FakeVoice("local", false)
        val result = VoiceRouter(FakeConnectivity(true)).speak(EngineMode.ONLINE, online, offline, "Hola", params, params)
        assertTrue(result is SpeakResult.Failure)
        assertTrue(offline.spoken.isEmpty())
    }

    @Test
    fun `siempre online sin credenciales informa el motivo`() = runTest {
        val online = FakeVoice("neural", true, Availability.Unavailable(UnavailableReason.MISSING_CREDENTIALS, "Falta la clave"))
        val result = VoiceRouter(FakeConnectivity(true)).speak(EngineMode.ONLINE, online, FakeVoice("local", false), "Hola", params, params)
        val error = (result as SpeakResult.Failure).error
        assertEquals(EngineErrorType.CREDENTIALS, error.type)
        assertEquals("Falta la clave", error.message)
    }

    @Test
    fun `siempre offline nunca usa la voz online`() = runTest {
        val online = FakeVoice("neural", true)
        val offline = FakeVoice("local", false)
        VoiceRouter(FakeConnectivity(true)).speak(EngineMode.OFFLINE, online, offline, "Hola", params, params)
        assertTrue(online.spoken.isEmpty())
        assertEquals(1, offline.spoken.size)
    }
}
