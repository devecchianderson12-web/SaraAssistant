package com.sara.lucy

import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.execution.MediaControl
import com.sara.core.execution.VolumeControl
import com.sara.core.execution.VolumeState
import com.sara.core.interpreter.CompositeCommandInterpreter
import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.memory.InMemoryStore
import com.sara.core.model.CommandIntent
import com.sara.core.model.AssistantIdentity
import com.sara.core.skills.CalculatorSkill
import com.sara.core.skills.SkillRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Lucy entiende y responde igual que Sara en el teléfono, pero con su nombre. */
class LucyBrainTest {
    private val interpreter = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), memory = InMemoryStore())
    private var played: Pair<String?, String?>? = null
    private val media = object : MediaControl {
        override suspend fun perform(action: MediaAction, query: String?, app: String?): MediaActionResult {
            if (action == MediaAction.PLAY) played = query to app
            return MediaActionResult.Done(app)
        }
    }
    private val volume = object : VolumeControl {
        var v = VolumeState(40, 0, 100)
        override fun state() = v
        override fun setIndex(index: Int): Boolean { v = v.copy(current = index); return true }
        override fun setMuted(muted: Boolean): Boolean { v = v.copy(isMuted = muted); return true }
    }
    private val executor = DefaultCommandExecutor(media, volume, skills = SkillRegistry(listOf(CalculatorSkill())))

    @Before fun identity() = LucyIdentity.apply()

    private fun intent(text: String) = runBlocking { interpreter.interpret(text).intent }

    @Test fun playMusicWithName() {
        assertTrue(intent("Lucy, reproducí música") is CommandIntent.PlayMusic)
        assertTrue(intent("lucy reproduci musica") is CommandIntent.PlayMusic)
        assertTrue(intent("luci poné música") is CommandIntent.PlayMusic)
        val q = intent("Lucy, poné música de queen en spotify") as CommandIntent.PlayMusic
        assertEquals("spotify", q.app?.lowercase())
    }

    @Test fun commandsAreSilentAcks() = runBlocking {
        val r = executor.execute(intent("Lucy, reproducí música"))
        assertTrue(r.success)
        // No informativa: Lucy sólo dice «Okey, voy.» (no repite la orden).
        assertFalse(r.informative)
        val v = executor.execute(intent("Lucy, subí el volumen"))
        assertTrue(v.success)
        assertFalse(v.informative)
        assertEquals(50, volume.v.current)
    }

    @Test fun smallTalkWithName() = runBlocking {
        val r = executor.execute(intent("¿cómo estás Lucy?"))
        assertTrue(r.informative)
        assertTrue(r.response, r.response.contains("bien"))
        val who = executor.execute(intent("Lucy, ¿cómo te llamás?"))
        assertTrue(who.response, who.response.contains("Lucy"))
        assertEquals("Música, volumen, apps, recordatorios y preguntas.", executor.execute(intent("Lucy, qué podés hacer")).response)
    }

    @Test fun calculatorAnswers() = runBlocking {
        val r = executor.execute(intent("Lucy, cuánto es 25 por 4"))
        assertTrue(r.response, r.response.contains("100"))
    }

    @Test fun identityIsLucy() = assertEquals("Lucy", AssistantIdentity.name)
}
