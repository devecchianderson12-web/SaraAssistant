package com.sara.core.skills

import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.memory.MusicTaste
import com.sara.core.model.CommandIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicTasteTest {
    @Test
    fun `aprende los artistas mas escuchados`() {
        val t = MusicTaste()
        val now = 1_000_000_000_000L
        t.record("Duki", 20 * 60_000L, now)
        t.record("Bizarrap, Quevedo", 10 * 60_000L, now)
        t.record("Tini - Topic", 5 * 60_000L, now)
        t.record("Unknown", 60 * 60_000L, now)
        t.record("Queen", 60_000L, now)
        assertEquals("Duki, Bizarrap y Tini", t.summary(now))
        val copy = MusicTaste().apply { load(t.serialize()) }
        assertEquals(t.summary(now), copy.summary(now))
        // Dos meses después, lo nuevo pesa más que lo viejo.
        val later = now + 60L * 24 * 3600 * 1000
        t.record("Queen", 8 * 60_000L, later)
        assertEquals("Queen", t.top(later).first())
        assertNull(MusicTaste().summary(now))
    }

    @Test
    fun `pregunta por los gustos`() {
        val intent = RuleBasedCommandInterpreter().interpretNow("Sara, ¿qué música me gusta?").intent
        assertEquals(CommandIntent.Skill("gustos", "musica", emptyMap()), intent)
    }
}
