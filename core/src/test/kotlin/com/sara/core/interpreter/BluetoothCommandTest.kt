package com.sara.core.interpreter

import com.sara.core.memory.InMemoryStore
import com.sara.core.model.CommandIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** «Sara, conectate a mi Bluetooth y conectate al Gol G3» y variantes. */
class BluetoothCommandTest {
    private val interpreter = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), memory = InMemoryStore())
    private fun intent(text: String) = runBlocking { interpreter.interpret(text).intent }
    private fun connect(device: String?) = CommandIntent.Skill(
        "dispositivo", "bluetooth", if (device == null) mapOf("estado" to "conectar") else mapOf("estado" to "conectar", "dispositivo" to device),
    )

    @Test fun namedDeviceWithoutConnectorWords() {
        assertEquals(connect("gol g3"), intent("conectate a gol g3"))
        assertEquals(connect("gol g3"), intent("conectate al gol g3"))
        assertEquals(connect("Gol G3"), intent("Sara conectate al Gol G3"))
        assertEquals(connect("gol g3"), intent("conectá el bluetooth al gol g3"))
    }

    @Test fun genericBluetoothHasNoDevice() {
        assertEquals(connect(null), intent("conectate a mi bluetooth"))
    }

    @Test fun nameAtStartStillSplitsAndDropsTheRedundantStep() {
        assertEquals(connect("gol g3"), intent("Sara, conectate a mi bluetooth y conectate a gol g3"))
    }
}
