package com.sara.core.memory

import com.sara.core.execution.AppLaunchResult
import com.sara.core.execution.AppLauncher
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MemoryTest {
    private fun intentOf(text: String) = RuleBasedCommandInterpreter().interpretNow(text).intent

    @Test
    fun `aprende la app de musica`() {
        val bitchord = CommandIntent.Remember(MemoryKeys.MUSIC_APP, "Bitchord")
        assertEquals(bitchord, intentOf("Sara, cada que te pida que pongas música quiero que ejecutes la aplicación de bitchord"))
        assertEquals(bitchord, intentOf("cada vez que te pida música abrí BitChord"))
        assertEquals(CommandIntent.Remember(MemoryKeys.MUSIC_APP, "Spotify"), intentOf("de ahora en más usá Spotify para la música"))
        assertEquals(CommandIntent.Remember(MemoryKeys.MUSIC_APP, "Youtube Music"), intentOf("cambiá la app de música a YouTube Music"))
        assertEquals(CommandIntent.Forget(MemoryKeys.MUSIC_APP), intentOf("olvidate de la app de música"))
        assertEquals(CommandIntent.Forget(MemoryKeys.MUSIC_APP), intentOf("volvé a la app de música por defecto"))
    }

    @Test
    fun `rutinas y datos`() {
        assertEquals(
            CommandIntent.Remember(MemoryKeys.routineKey("buenas noches"), "sacá la música y cerrá las apps"),
            intentOf("cuando te diga buenas noches, hacé sacá la música y cerrá las apps"),
        )
        assertTrue(intentOf("acordate que mi color favorito es el azul") is CommandIntent.Remember)
        assertEquals(CommandIntent.RecallMemory, intentOf("¿qué recordás?"))
        // Lo de siempre sigue funcionando.
        assertEquals(CommandIntent.PlayMusic(null, null), intentOf("poné algo de música"))
        assertEquals(CommandIntent.CloseBackgroundApps, intentOf("borrá todo"))
    }

    @Test
    fun `charla local`() {
        val now = LocalDateTime.of(2026, 11, 17, 9, 5)
        assertEquals("Son las 9:05.", SmallTalk.answer("¿qué hora es?", now))
        assertEquals("Hoy es martes 17 de noviembre.", SmallTalk.answer("qué día es hoy", now))
        assertEquals("Muy bien, ¿y vos?", SmallTalk.answer("¿Cómo estás?"))
        assertNull(SmallTalk.answer("¿qué tiempo hace en Córdoba?"))
        assertEquals(CommandIntent.Chat("¿Cómo estás?"), intentOf("¿Cómo estás?"))
    }

    @Test
    fun `whatsapp a un grupo`() {
        assertEquals(
            CommandIntent.SendMessage("notas", "Hola", group = true),
            intentOf("sara, envia un mensaje al grupo que dice \"notas\" en mi whatsapp en el cual solo estoy yo que diga \"hola\""),
        )
        assertEquals(
            CommandIntent.SendMessage("familia", "Ya llego", group = true),
            intentOf("mandá un mensaje al grupo familia que diga ya llego"),
        )
    }

    private class FakeMedia : MediaControl {
        var lastApp: String? = null
        override suspend fun perform(action: MediaAction, query: String?, app: String?): MediaActionResult {
            lastApp = app
            return MediaActionResult.Done(app)
        }
    }

    private object NoVolume : VolumeControl {
        override fun state() = VolumeState(1, 0, 15)
        override fun setIndex(index: Int) = true
        override fun setMuted(muted: Boolean) = true
    }

    private object Apps : AppLauncher {
        override suspend fun open(name: String) = AppLaunchResult.Opened(name, false)
        override suspend fun findLabel(name: String) = if (name.lowercase() == "bitchord") "BitChord" else null
    }

    private class FakeAi(private val available: Boolean) : ConversationalAssistant {
        override fun isAvailable() = available
        override suspend fun reply(text: String) = "Hace 20 grados."
    }

    @Test
    fun `usa la app guardada y la puede cambiar`() = runBlocking {
        val memory = InMemoryStore()
        val media = FakeMedia()
        val executor = DefaultCommandExecutor(media, NoVolume, apps = Apps, memory = memory)

        assertEquals("Listo, la música en BitChord.", executor.execute(CommandIntent.Remember(MemoryKeys.MUSIC_APP, "Bitchord")).response)
        executor.execute(CommandIntent.PlayMusic())
        assertEquals("BitChord", media.lastApp)
        // Una app pedida explícitamente gana.
        executor.execute(CommandIntent.PlayMusic(null, "spotify"))
        assertEquals("spotify", media.lastApp)

        assertFalse(executor.execute(CommandIntent.Remember(MemoryKeys.MUSIC_APP, "Inexistente")).success)
        assertEquals("BitChord", memory.get(MemoryKeys.MUSIC_APP))

        executor.execute(CommandIntent.Forget(MemoryKeys.MUSIC_APP))
        executor.execute(CommandIntent.PlayMusic())
        assertNull(media.lastApp)
    }

    @Test
    fun `rutina guardada se ejecuta y la ia responde preguntas`() = runBlocking {
        val memory = InMemoryStore()
        memory.put(MemoryKeys.routineKey("buenas noches"), "sacá la música")
        val interpreter = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), memory = memory, aiAvailable = { true })
        assertEquals(CommandIntent.StopMusic, interpreter.interpret("Buenas noches").intent)
        assertEquals(CommandIntent.Chat("¿qué tiempo hace?"), interpreter.interpret("¿qué tiempo hace?").intent)

        val withAi = DefaultCommandExecutor(FakeMedia(), NoVolume, assistant = FakeAi(true))
        assertEquals("Hace 20 grados.", withAi.execute(CommandIntent.Chat("¿qué tiempo hace?")).response)
        val noAi = DefaultCommandExecutor(FakeMedia(), NoVolume, assistant = FakeAi(false))
        assertFalse(noAi.execute(CommandIntent.Chat("¿qué tiempo hace?")).success)
        assertEquals("Muy bien, ¿y vos?", noAi.execute(CommandIntent.Chat("¿cómo estás?")).response)
    }
}
