package com.lucyremota.remoto

import com.lucyremota.nucleo.execution.DefaultCommandExecutor
import com.lucyremota.nucleo.execution.MediaAction
import com.lucyremota.nucleo.execution.MediaActionResult
import com.lucyremota.nucleo.execution.MediaControl
import com.lucyremota.nucleo.execution.VolumeControl
import com.lucyremota.nucleo.execution.VolumeState
import com.lucyremota.nucleo.interpreter.CompositeCommandInterpreter
import com.lucyremota.nucleo.interpreter.RuleBasedCommandInterpreter
import com.lucyremota.nucleo.memory.InMemoryStore
import com.lucyremota.nucleo.model.AssistantIdentity
import com.lucyremota.nucleo.model.CommandIntent
import com.lucyremota.protocolo.LinkState
import com.lucyremota.protocolo.PairedPc
import com.lucyremota.protocolo.PairingQr
import com.lucyremota.protocolo.PhoneLink
import com.lucyremota.protocolo.PhoneStore
import com.lucyremota.protocolo.SoftwareIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RemotoTest {
    init { AssistantIdentity.name = "Lucy" }

    private val interpreter = PcInterpreter(CompositeCommandInterpreter(RuleBasedCommandInterpreter(), aiAvailable = { true }))

    private fun intentOf(text: String) = runBlocking { interpreter.interpret(text).intent }

    private fun pc(text: String): Pair<String, Map<String, String>>? = (intentOf(text) as? CommandIntent.Skill)?.takeIf { it.skill == "pc" }?.let { it.action to it.args }

    @Test
    fun `entiende las ordenes de la computadora`() {
        assertEquals("TAKE_SCREENSHOT", pc("Lucy, sacá una captura de pantalla")?.first)
        assertEquals("MINIMIZE_WINDOWS", pc("Minimizá todas las ventanas")?.first)
        assertEquals("MINIMIZE_WINDOWS", pc("mostrame el escritorio")?.first)
        assertEquals("SCREEN_OFF", pc("Apagá la pantalla")?.first)
        assertEquals("LOCK_SESSION", pc("bloqueá la computadora")?.first)
        assertEquals("SHUTDOWN", pc("Lucy, apagá la compu")?.first)
        assertEquals("RESTART", pc("reiniciá la pc")?.first)
        assertEquals("CANCEL_SHUTDOWN", pc("cancelá el apagado")?.first)
        assertEquals("OPEN_SYSTEM_PAGE" to mapOf("page" to "settings"), pc("Abrí la configuración de Windows"))
        assertEquals("OPEN_SYSTEM_PAGE" to mapOf("page" to "bluetooth"), pc("abrí la configuración de bluetooth"))
        assertEquals("OPEN_SYSTEM_PAGE" to mapOf("page" to "explorer"), pc("Abrí el explorador de archivos"))
        assertEquals("OPEN_SYSTEM_PAGE" to mapOf("page" to "downloads"), pc("abrí descargas"))
        assertEquals("OPEN_SYSTEM_PAGE" to mapOf("page" to "task_manager"), pc("abrí el administrador de tareas"))
        assertEquals("GET_TIME", pc("Decime qué hora es")?.first)
        assertEquals("WEB_SEARCH" to mapOf("query" to "recetas de pizza"), pc("buscá en google recetas de pizza"))
        assertEquals("CLOSE_APPLICATION" to mapOf("name" to "Chrome"), pc("cerrá Chrome"))
        assertEquals("FOCUS_APPLICATION" to mapOf("name" to "Spotify"), pc("cambiá a Spotify"))
    }

    @Test
    fun `lo comun lo entiende el cerebro de Sara`() {
        assertEquals(CommandIntent.PlayMusic(null, null), intentOf("Lucy, poné música"))
        assertEquals(CommandIntent.PlayMusic(null, null), intentOf("quiero escuchar música"))
        assertTrue(intentOf("Subí el volumen") is CommandIntent.AdjustVolume)
        assertTrue(intentOf("Silenciá la computadora") is CommandIntent.AdjustVolume)
        assertEquals(CommandIntent.OpenApp("google chrome"), intentOf("Abrí Google Chrome"))
        assertEquals(CommandIntent.OpenApp("whatsapp"), intentOf("abrí WhatsApp"))
        assertEquals(CommandIntent.NowPlaying, intentOf("¿qué está sonando?"))
        assertTrue(intentOf("¿qué es la fotosíntesis?") is CommandIntent.Chat)
    }

    private class MemStore : PhoneStore {
        override val deviceId = "tel-interop-2"
        override val deviceName = "Teléfono (prueba completa)"
        override var pairedPc: PairedPc? = null
    }

    private object NoMedia : MediaControl {
        override suspend fun perform(action: MediaAction, query: String?, app: String?) = MediaActionResult.NothingPlaying
    }

    private object NoVolume : VolumeControl {
        override fun state() = VolumeState(0, 0, 0)
        override fun setIndex(index: Int) = false
        override fun setMuted(muted: Boolean) = false
    }

    /** Frase → intérprete → ejecutor → protocolo → Receptor de Windows real (.NET, PC simulada). */
    @Test
    fun `de la frase a la computadora y vuelta`() = runBlocking {
        val qrFile = System.getenv("LUCY_INTEROP_QR")
        assumeTrue("sin PC simulada", qrFile != null)
        val file = File(qrFile!!)
        withTimeout(30_000) { while (!file.exists()) delay(200) }
        val link = PhoneLink(MemStore(), SoftwareIdentity(), { println("[tel] $it") }, relayUrl = null)
        link.start()
        link.pairWithQr(PairingQr.parse(file.readText().trim())!!)
        withTimeout(15_000) { while (link.state.value !is LinkState.Connected || link.catalog.value.isEmpty()) delay(100) }
        val orders = OrderLog()
        val local = DefaultCommandExecutor(NoMedia, NoVolume, memory = InMemoryStore())
        val exec = RemoteExecutor(link, local, orders) { "prueba" }
        suspend fun say(text: String) = exec.execute(interpreter.interpret(text).intent)

        val up = say("Lucy, poné el volumen al 30")
        assertTrue(up.response, up.success)
        assertEquals("Volumen al 30 %", up.response)
        val more = say("subí el volumen")
        assertEquals("Volumen al 40 %", more.response)
        // Sensible: pide confirmación y sólo con «sí» la hace.
        val off = say("apagá la computadora")
        assertNotNull(off.confirmation)
        assertTrue(off.confirmation!!.onConfirm().success)
        // La PC simulada no sabe abrir programas: se avisa sin mandar.
        val app = say("abrí Photoshop")
        assertTrue(!app.success && app.response.contains("todavía no sabe"))
        assertTrue(orders.orders.value.any { it.intent == "SET_VOLUME" && it.status == "success" })
        link.unpair()
        link.close()
    }
}
