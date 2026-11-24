package com.sara.core.memory

import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.model.CommandIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class ReprogramTest {
    private fun intentOf(text: String) = RuleBasedCommandInterpreter().interpretNow(text).intent

    @Test
    fun `reprogramar el auto`() {
        assertEquals(
            CommandIntent.Remember(MemoryKeys.routineKey("conectate al auto"), "conectate al fiat cronos"),
            intentOf("Sara, cada vez que te diga conectate al auto quiero que te conectes al fiat cronos"),
        )
        assertEquals(
            CommandIntent.Remember(MemoryKeys.routineKey("desconectate"), "desconectate del fiat cronos"),
            intentOf("cada vez que te diga \"desconectate\" quiero que te desconectes del fiat cronos"),
        )
    }
}
