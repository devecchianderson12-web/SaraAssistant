package com.sara.core.memory

import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.model.CommandIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutinesCarTest {
    private fun intentOf(text: String) = RuleBasedCommandInterpreter().interpretNow(text).intent

    @Test
    fun `rutina del auto dicha larga`() {
        val r = intentOf("ey sara, guarda en tu memoria, cada vez que te pida \"conectate al auto\" quiero que si el bluetooth esta apagado lo enciendas y te conectes al gol g3 y si esta encendido solo conectate al gol g3")
        assertEquals(CommandIntent.Remember(MemoryKeys.routineKey("conectate al auto"), "conectate a gol g3"), r)
    }

    @Test
    fun `desconectar y prender bluetooth`() {
        assertEquals(CommandIntent.Skill("dispositivo", "bluetooth", mapOf("estado" to "desconectar", "dispositivo" to "gol g 3")), intentOf("desconectate del gol g3"))
        assertEquals(CommandIntent.Skill("dispositivo", "bluetooth", mapOf("estado" to "desconectar")), intentOf("desconectate"))
        assertEquals(CommandIntent.Skill("dispositivo", "bluetooth", mapOf("estado" to "prender")), intentOf("ey sara, prende el bluetooth"))
        assertEquals("conectar", (intentOf("conectate al gol g3") as CommandIntent.Skill).args["estado"])
    }
}
