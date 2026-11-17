package com.sara.core.interpreter

import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.execution.MediaControl
import com.sara.core.execution.MessageContact
import com.sara.core.execution.MessagePrepareResult
import com.sara.core.execution.MessageSendResult
import com.sara.core.execution.Messenger
import com.sara.core.execution.VolumeControl
import com.sara.core.execution.VolumeState
import com.sara.core.model.CommandIntent
import com.sara.core.model.PermissionKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {
    private fun intentOf(text: String) = RuleBasedCommandInterpreter().interpretNow(text).intent

    @Test
    fun `entiende pedidos de whatsapp`() {
        assertEquals(
            CommandIntent.SendMessage("miamor", "Tal, tal, tal cosa"),
            intentOf("envía un mensaje por WhatsApp a miamor que diga \"tal, tal, tal cosa\""),
        )
        assertEquals(
            CommandIntent.SendMessage("mi amor", "Llego en diez minutos"),
            intentOf("Sara, mandale un WhatsApp a mi amor que diga llego en diez minutos"),
        )
        assertEquals(
            CommandIntent.SendMessage("juan", "Hola, ¿cómo estás?"),
            intentOf("enviá un mensaje a Juan por WhatsApp diciendo hola, ¿cómo estás?"),
        )
        assertEquals(
            CommandIntent.SendMessage("mamá", "Ya salgo"),
            intentOf("decile a mamá por whatsapp que ya salgo"),
        )
        assertEquals(CommandIntent.SendMessage("pedro", "Abrí la puerta"), intentOf("escribile a Pedro que abrí la puerta"))
    }

    @Test
    fun `grupo dicho de varias formas`() {
        assertEquals(
            CommandIntent.SendMessage("notas", "Hola", group = true),
            intentOf("Envía un mensaje a WhatsApp en el grupo de notas que diga hola"),
        )
        assertEquals(
            CommandIntent.SendMessage("notas", "Hola", group = true),
            intentOf("mandá un mensaje al grupo notas que está en archivados que diga hola"),
        )
    }

    @Test
    fun `contacto al final`() {
        assertEquals(
            CommandIntent.SendMessage("juan pérez", "Hola"),
            intentOf("enviame un mensaje a wpp que diga hola a Juan Pérez"),
        )
        assertEquals(
            CommandIntent.SendMessage("mi amor", "Te extraño mucho"),
            intentOf("mandá un mensaje por WhatsApp diciendo te extraño mucho a mi amor"),
        )
    }

    @Test
    fun `no confunde otras ordenes`() {
        assertNull(MessageParser.parse("abrí WhatsApp"))
        assertEquals(CommandIntent.OpenApp("whatsapp"), intentOf("abrí WhatsApp"))
        assertNull(MessageParser.parse("poné música de Soda Stereo"))
    }

    @Test
    fun `respuestas si o no`() {
        assertEquals(true, ConfirmationParser.parse("sí"))
        assertEquals(true, ConfirmationParser.parse("dale, mandalo"))
        assertEquals(true, ConfirmationParser.parse("Sí, envíalo"))
        assertEquals(false, ConfirmationParser.parse("no"))
        assertEquals(false, ConfirmationParser.parse("no, cancelá"))
        assertNull(ConfirmationParser.parse("qué hora es"))
    }

    private class FakeMessenger(private val prepare: MessagePrepareResult) : Messenger {
        var sent: Pair<MessageContact, String>? = null
        override suspend fun prepare(contact: String, group: Boolean) = prepare
        override suspend fun send(contact: MessageContact, message: String): MessageSendResult {
            sent = contact to message
            return MessageSendResult.Sent
        }
    }

    private object NoMedia : MediaControl {
        override suspend fun perform(action: MediaAction, query: String?, app: String?) = MediaActionResult.NothingPlaying
    }

    private object NoVolume : VolumeControl {
        override fun state() = VolumeState(1, 0, 15)
        override fun setIndex(index: Int) = true
        override fun setMuted(muted: Boolean) = true
    }

    @Test
    fun `pide confirmacion y despues envia`() = runBlocking {
        val m = FakeMessenger(MessagePrepareResult.Found(MessageContact("Mi Amor", "5491100000000")))
        val result = DefaultCommandExecutor(NoMedia, NoVolume, messenger = m, confirmMessages = { true })
            .execute(CommandIntent.SendMessage("miamor", "Hola"))
        assertNotNull(result.confirmation)
        val confirmation = result.confirmation!!
        assertEquals("¿Le mando «Hola» a Mi Amor?", confirmation.question)
        assertNull(m.sent)
        assertEquals("Listo, se lo mandé a Mi Amor.", confirmation.onConfirm().response)
        assertEquals("Hola", m.sent?.second)
    }

    @Test
    fun `sin confirmacion envia directo y avisa permisos`() = runBlocking {
        val m = FakeMessenger(MessagePrepareResult.Found(MessageContact("Mi Amor", "549")))
        val direct = DefaultCommandExecutor(NoMedia, NoVolume, messenger = m, confirmMessages = { false })
            .execute(CommandIntent.SendMessage("miamor", "Hola"))
        assertTrue(direct.success)
        assertNull(direct.confirmation)
        assertEquals("Hola", m.sent?.second)

        val noPerm = DefaultCommandExecutor(NoMedia, NoVolume, messenger = FakeMessenger(MessagePrepareResult.NeedsPermission(PermissionKind.CONTACTS)))
            .execute(CommandIntent.SendMessage("miamor", "Hola"))
        assertFalse(noPerm.success)
        assertEquals(PermissionKind.CONTACTS, noPerm.needsPermission)
    }
}
