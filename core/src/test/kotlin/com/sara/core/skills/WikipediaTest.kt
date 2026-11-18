package com.sara.core.skills

import com.sara.core.ai.FastKnowledgeAssistant
import com.sara.core.ai.Wikipedia
import com.sara.core.memory.ConversationalAssistant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WikipediaTest {
    @Test
    fun `temas de saber general`() {
        assertEquals("fotosíntesis", Wikipedia.topicOf("Sara, ¿qué es la fotosíntesis?"))
        assertEquals("calculadora", Wikipedia.topicOf("cómo se creó la calculadora"))
        assertEquals("San Martín", Wikipedia.topicOf("quién fue San Martín"))
        assertNull(Wikipedia.topicOf("qué hora es"))
        assertNull(Wikipedia.topicOf("cómo está el clima hoy"))
        assertNull(Wikipedia.topicOf("poné música"))
        assertNull(Wikipedia.topicOf("qué es lo que me dijiste"))
    }

    @Test
    fun `limpia el resumen`() {
        assertEquals("La fotosíntesis es la conversión de materia inorgánica en materia orgánica.",
            Wikipedia.clean("La fotosíntesis (del griego antiguo φῶς) es la conversión de materia inorgánica en materia orgánica."))
        assertNull(Wikipedia.clean("Mercurio puede referirse a: el planeta, el elemento."))
    }

    private class FakeAi(private val wait: Long, private val answer: String) : ConversationalAssistant {
        override fun isAvailable() = true
        override suspend fun reply(text: String): String { delay(wait); return answer }
    }
    private class FakeWiki(private val answer: String?) : Wikipedia() {
        override fun answer(question: String) = answer
    }

    @Test
    fun `la IA lenta o con error deja paso a Wikipedia`() = runBlocking {
        assertEquals("IA", FastKnowledgeAssistant(FakeAi(10, "IA"), FakeWiki("Wiki"), 500).reply("qué es la luna"))
        assertEquals("Wiki", FastKnowledgeAssistant(FakeAi(5_000, "IA"), FakeWiki("Wiki"), 100).reply("qué es la luna"))
        assertEquals("Wiki", FastKnowledgeAssistant(FakeAi(10, "No pude responder ahora."), FakeWiki("Wiki"), 500).reply("qué es la luna"))
        assertEquals("IA", FastKnowledgeAssistant(FakeAi(300, "IA"), FakeWiki(null), 100).reply("qué es la luna"))
        assertEquals("Hola", FastKnowledgeAssistant(FakeAi(10, "Hola"), FakeWiki("Wiki"), 100).reply("contame un chiste"))
    }
}
