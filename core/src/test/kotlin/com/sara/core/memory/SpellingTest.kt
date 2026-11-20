package com.sara.core.memory

import com.sara.core.apps.AppMatcher
import com.sara.core.apps.InstalledApp
import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.execution.MediaControl
import com.sara.core.execution.VolumeControl
import com.sara.core.execution.VolumeState
import com.sara.core.interpreter.CompositeCommandInterpreter
import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.model.CommandIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingTest {
    private object NoMedia : MediaControl {
        override suspend fun perform(action: MediaAction, query: String?, app: String?) = MediaActionResult.Done("Música")
    }
    private object NoVolume : VolumeControl {
        override fun state() = VolumeState(5, 0, 15)
        override fun setIndex(index: Int) = true
        override fun setMuted(muted: Boolean) = true
    }

    private fun intentOf(text: String) = RuleBasedCommandInterpreter().interpretNow(text).intent

    @Test
    fun `ensenar como se escribe un nombre`() = runBlocking {
        val memory = InMemoryStore()
        val executor = DefaultCommandExecutor(NoMedia, NoVolume, memory = memory)
        val interpreter = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), memory = memory)
        suspend fun say(text: String) = executor.execute(interpreter.interpret(text).intent)

        val taught = say("Sara, yesica se escribe con y griega no con jota")
        assertTrue(taught.response, taught.success)
        assertEquals("Listo, de ahora en más escribo «Yesica».", taught.response)
        // Lo que el reconocimiento escribe mal se corrige antes de entender la orden.
        assertEquals("llamá a Yesica", Spelling.apply("llamá a jesica", memory))
        assertEquals(
            CommandIntent.Skill("llamadas", "llamar", mapOf("contacto" to "yesica", "via" to "telefono")),
            interpreter.interpret("llamá a Jésica").intent,
        )

        // «Ceci» no se parece a «Yessi» por cómo se escribe: se enseña con «cuando digo … me refiero a …».
        assertTrue(say("cuando digo ceci me refiero a Yessi").success)
        assertTrue(say("no es jessi, es Yessi").success)
        assertEquals("mandale saludos a Yessi y a Yessi", Spelling.apply("mandale saludos a ceci y a jessi", memory))
        val stored = memory.get(Spelling.PREFIX + "yessi")
        assertNotNull(stored)
        assertTrue(stored!!, stored.contains("ceci") && stored.contains("jessi"))
        assertTrue(executor.execute(CommandIntent.RecallMemory).response.contains("«Yessi» se escribe así"))
    }

    @Test
    fun `formas de decirlo`() {
        assertEquals("Yessi", Spelling.word((intentOf("el nombre Jessi se escribe con y griega") as CommandIntent.Remember).value))
        assertEquals("Vanesa", Spelling.word((intentOf("Banesa se escribe con ve corta, no con be larga") as CommandIntent.Remember).value))
        // Las rutinas siguen funcionando igual.
        val routine = intentOf("cuando te diga buenas noches quiero que pauses la música") as CommandIntent.Remember
        assertTrue(routine.key.startsWith(MemoryKeys.ROUTINE_PREFIX))
    }

    @Test
    fun `llamadas por whatsapp`() {
        assertEquals(
            CommandIntent.Skill("llamadas", "llamar", mapOf("contacto" to "tatiana", "via" to "whatsapp")),
            intentOf("ey sara, llama por whatsapp a tatiana"),
        )
        assertEquals(
            CommandIntent.Skill("llamadas", "llamar", mapOf("contacto" to "tatiana", "via" to "whatsapp")),
            intentOf("llamá a Tatiana por WhatsApp"),
        )
        assertEquals(
            CommandIntent.Skill("llamadas", "llamar", mapOf("contacto" to "mama", "via" to "whatsapp_video")),
            intentOf("haceme una videollamada por whatsapp a mi mamá"),
        )
    }

    @Test
    fun `contactos que suenan igual`() {
        val contacts = listOf(InstalledApp("Yessi", "1"), InstalledApp("Tatiana", "2"), InstalledApp("Juan", "3"))
        assertEquals("Yessi", AppMatcher.best("jessi", contacts)?.label)
        assertEquals("Tatiana", AppMatcher.best("tatiana", contacts)?.label)
    }
}
