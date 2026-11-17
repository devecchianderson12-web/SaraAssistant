package com.sara.core.state

import com.sara.core.model.AssistantError
import com.sara.core.model.AssistantState
import com.sara.core.model.CommandIntent
import com.sara.core.model.ErrorKind
import com.sara.core.permissions.Feature
import com.sara.core.permissions.PermissionPlanner
import com.sara.core.permissions.PermissionSnapshot
import com.sara.core.permissions.SaraPermission
import com.sara.core.permissions.ServiceStartPolicy
import com.sara.core.permissions.StartContext
import com.sara.core.permissions.StartDecision
import com.sara.core.util.AudioLevelMeter
import com.sara.core.util.WakeWordTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStateMachineTest {
    @Test
    fun `secuencia valida completa`() {
        val sm = AssistantStateMachine()
        assertTrue(sm.moveTo(AssistantState.WaitingForWakeWord))
        assertTrue(sm.moveTo(AssistantState.Listening()))
        assertTrue(sm.moveTo(AssistantState.Listening("poné")))
        assertTrue(sm.moveTo(AssistantState.Processing("poné música")))
        assertTrue(sm.moveTo(AssistantState.Executing(CommandIntent.PlayMusic())))
        assertTrue(sm.moveTo(AssistantState.Speaking("Perfecto")))
        assertTrue(sm.moveTo(AssistantState.WaitingForWakeWord))
    }

    @Test
    fun `transiciones invalidas se rechazan sin cambiar el estado`() {
        val sm = AssistantStateMachine()
        assertFalse(sm.moveTo(AssistantState.Processing("x")))
        assertFalse(sm.moveTo(AssistantState.Executing(CommandIntent.Pause)))
        assertEquals(AssistantState.Inactive, sm.state.value)
        sm.moveTo(AssistantState.WaitingForWakeWord)
        assertFalse(sm.moveTo(AssistantState.Executing(CommandIntent.Pause)))
    }

    @Test
    fun `cualquier estado puede detenerse y el error se recupera`() {
        val sm = AssistantStateMachine()
        sm.moveTo(AssistantState.Listening())
        assertTrue(sm.moveTo(AssistantState.Error(AssistantError(ErrorKind.NETWORK, "x"))))
        assertTrue(sm.moveTo(AssistantState.WaitingForWakeWord))
        assertTrue(sm.moveTo(AssistantState.Inactive))
    }
}

class PermissionPolicyTest {
    private fun snapshot(sdk: Int, audio: Boolean = true, notif: Boolean = true, listener: Boolean = true) =
        PermissionSnapshot(sdk, audio, notif, listener)

    @Test
    fun `notificaciones solo se piden desde Android 13`() {
        assertEquals(listOf(SaraPermission.RECORD_AUDIO), PermissionPlanner.runtimePermissionsFor(Feature.BACKGROUND_WAKE_WORD, 32))
        assertEquals(
            listOf(SaraPermission.RECORD_AUDIO, SaraPermission.POST_NOTIFICATIONS),
            PermissionPlanner.runtimePermissionsFor(Feature.BACKGROUND_WAKE_WORD, 33),
        )
        assertTrue(PermissionPlanner.missingFor(Feature.BACKGROUND_WAKE_WORD, snapshot(29, notif = false)).isEmpty())
        assertEquals(listOf(SaraPermission.POST_NOTIFICATIONS), PermissionPlanner.missingFor(Feature.BACKGROUND_WAKE_WORD, snapshot(34, notif = false)))
    }

    @Test
    fun `control multimedia requiere acceso a notificaciones`() {
        assertEquals(listOf(SaraPermission.NOTIFICATION_LISTENER), PermissionPlanner.missingFor(Feature.MEDIA_SESSION_CONTROL, snapshot(30, listener = false)))
        assertEquals(listOf(SaraPermission.RECORD_AUDIO), PermissionPlanner.missingFor(Feature.MANUAL_LISTENING, snapshot(35, audio = false)))
    }

    @Test
    fun `inicio del servicio de microfono segun version y contexto`() {
        assertTrue(ServiceStartPolicy.decide(35, StartContext.VISIBLE_ACTIVITY, true) is StartDecision.StartNow)
        assertTrue(ServiceStartPolicy.decide(35, StartContext.NOTIFICATION_ACTION, true) is StartDecision.StartNow)
        assertTrue(ServiceStartPolicy.decide(34, StartContext.VOICE_INTERACTION_SERVICE, true) is StartDecision.StartNow)
        assertTrue(ServiceStartPolicy.decide(29, StartContext.BOOT_COMPLETED, true) is StartDecision.StartNow)
        assertTrue(ServiceStartPolicy.decide(30, StartContext.BOOT_COMPLETED, true) is StartDecision.AskUserViaNotification)
        assertTrue(ServiceStartPolicy.decide(35, StartContext.BOOT_COMPLETED, true) is StartDecision.AskUserViaNotification)
        assertTrue(ServiceStartPolicy.decide(31, StartContext.BACKGROUND, true) is StartDecision.AskUserViaNotification)
        assertTrue(ServiceStartPolicy.decide(35, StartContext.VISIBLE_ACTIVITY, false) is StartDecision.NotAllowed)
    }
}

class WakeWordAndAudioTest {
    @Test
    fun `sensibilidad mas alta exige menos confianza`() {
        assertTrue(WakeWordTuning.minimumConfidence(1f) < WakeWordTuning.minimumConfidence(0f))
        assertEquals(0.70f, WakeWordTuning.minimumConfidence(0.5f), 0.001f)
    }

    @Test
    fun `aceptacion de la palabra de activacion`() {
        assertEquals(0.9f, WakeWordTuning.accept(listOf("sara" to 0.9f), 0.5f))
        assertNull(WakeWordTuning.accept(listOf("sara" to 0.5f), 0.5f))
        assertEquals(0.8f, WakeWordTuning.accept(listOf("[unk]" to 1f, "sara" to 0.8f), 0.5f))
        // mención dentro de una frase larga: se ignora
        assertNull(WakeWordTuning.accept(listOf("[unk]", "[unk]", "[unk]", "sara", "[unk]").map { it to 1f }, 0.5f))
        assertNull(WakeWordTuning.accept(listOf("[unk]" to 1f), 1f))
    }

    @Test
    fun `verificacion por transcripcion libre`() {
        assertTrue(WakeWordTuning.verifiedByTranscript("sara"))
        assertTrue(WakeWordTuning.verifiedByTranscript("eh sara pausa"))
        assertTrue(WakeWordTuning.verifiedByTranscript("Zara"))
        assertFalse(WakeWordTuning.verifiedByTranscript("subí el volumen"))
        assertFalse(WakeWordTuning.verifiedByTranscript("para la próxima"))
        assertFalse(WakeWordTuning.verifiedByTranscript(""))
    }

    @Test
    fun `deteccion por parciales con estabilidad y enfriamiento`() {
        val gate = com.sara.core.util.WakeWordGate(0.3f)
        assertFalse(gate.onPartial(listOf("sara")))
        assertTrue(gate.onPartial(listOf("sara")))
        gate.rejected()
        repeat(5) { assertFalse(gate.onPartial(listOf("sara"))) }
        assertFalse(gate.onPartial(listOf("sara")))
        assertTrue(gate.onPartial(listOf("sara")))
        assertFalse(gate.onPartial(listOf("[unk]")))
        assertTrue(gate.onFinal(listOf("sara" to 0.9f)))
        assertEquals(1, WakeWordTuning.requiredPartialHits(0.9f))
        assertEquals(3, WakeWordTuning.requiredPartialHits(0.1f))
        assertEquals(2, WakeWordTuning.requiredPartialHits(0.5f))
        assertEquals(1, WakeWordTuning.requiredPartialHits(0.9f))
    }

    @Test
    fun `ey sara exige el llamado antes del nombre`() {
        assertTrue(WakeWordTuning.verifiedWithPrefix("ey sara"))
        assertTrue(WakeWordTuning.verifiedWithPrefix("y sara poné música"))
        assertTrue(WakeWordTuning.verifiedWithPrefix("bueno oye sarah"))
        assertTrue(WakeWordTuning.verifiedWithPrefix("hey zara"))
        assertFalse(WakeWordTuning.verifiedWithPrefix("sara"))
        assertFalse(WakeWordTuning.verifiedWithPrefix("le dije a sara que venga"))
        assertFalse(WakeWordTuning.verifiedWithPrefix("para la próxima"))
    }

    @Test
    fun `ganancia para voz baja`() {
        assertEquals(6f, WakeWordTuning.gainFor(500), 0.001f)
        assertEquals(1f, WakeWordTuning.gainFor(20_000), 0.001f)
        // 1000 y -1000 en PCM 16 bits little-endian.
        val pcm = byteArrayOf(0xE8.toByte(), 0x03, 0x18, 0xFC.toByte())
        assertEquals(1000, WakeWordTuning.peakOf(pcm, 4))
        val louder = WakeWordTuning.applyGain(pcm, 4, 4f)
        assertEquals(4000, WakeWordTuning.peakOf(louder, 4))
        assertTrue(WakeWordTuning.peakOf(WakeWordTuning.applyGain(pcm, 4, 100f), 4) >= 32767)
    }

    @Test
    fun `compuerta de energia duerme en silencio y despierta con voz`() {
        val gate = com.sara.core.util.EnergyGate(idleAfterChunks = 3)
        repeat(3) { assertTrue(gate.feed(50.0).process) }
        assertFalse(gate.feed(50.0).process)
        assertTrue(gate.idle)
        val wake = gate.feed(3000.0)
        assertTrue(wake.process && wake.wokeUp)
        assertFalse(gate.idle)
    }

    @Test
    fun `nivel de audio`() {
        assertEquals(0f, AudioLevelMeter.levelOfPcm16(ByteArray(320), 320))
        val loud = ByteArray(320) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F }
        assertEquals(1f, AudioLevelMeter.levelOfPcm16(loud, loud.size), 0.01f)
    }
}
