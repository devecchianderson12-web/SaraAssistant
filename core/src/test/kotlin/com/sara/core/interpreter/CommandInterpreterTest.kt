package com.sara.core.interpreter

import com.sara.core.model.CommandIntent
import com.sara.core.model.CommandIntent.AdjustVolume
import com.sara.core.model.InterpretationResult
import com.sara.core.model.VolumeChange
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandInterpreterTest {
    private val interpreter = RuleBasedCommandInterpreter()

    private fun intentOf(text: String) = interpreter.interpretNow(text).intent

    private fun assertIntent(expected: CommandIntent, vararg phrases: String) {
        for (phrase in phrases) assertEquals("Frase: \"$phrase\"", expected, intentOf(phrase))
    }

    @Test
    fun `reproducir musica con variaciones naturales`() {
        assertIntent(
            CommandIntent.PlayMusic(),
            "Sara, reproduce música",
            "Sara, poné música",
            "Poné algo de música",
            "Quiero escuchar música",
            "Reproducí una canción",
            "Me gustaría escuchar música",
            "Che Sara, poné música por favor",
            "pon música",
        )
    }

    @Test
    fun `controles de reproduccion del enunciado`() {
        assertIntent(CommandIntent.Pause, "Sara, pausá la música", "pausa", "poné pausa", "frená la música")
        assertIntent(CommandIntent.Resume, "Sara, continuá reproduciendo", "seguí con la música", "dale play", "reanudá")
        assertIntent(CommandIntent.Next, "Sara, siguiente canción", "pasá de tema", "pasá a la siguiente", "otra canción", "saltá esta")
        assertIntent(CommandIntent.Previous, "Sara, canción anterior", "poné la de antes", "volvé atrás")
        assertIntent(CommandIntent.StopMusic, "Sara, detené la música", "pará la música", "apagá la música", "stop")
    }

    @Test
    fun `volumen relativo y absoluto`() {
        assertIntent(AdjustVolume(VolumeChange.Increase(null)), "Sara, subí el volumen", "más fuerte", "subilo")
        assertIntent(AdjustVolume(VolumeChange.Decrease(null)), "Sara, bajá el volumen", "más despacio", "bajale")
        assertIntent(AdjustVolume(VolumeChange.Increase(30)), "Sara, subí un 30% la música", "subí el volumen un 30 por ciento", "aumentá el volumen treinta por ciento")
        assertIntent(AdjustVolume(VolumeChange.SetTo(10)), "Sara, bajale al 10%", "bajá el volumen al diez por ciento")
        assertIntent(AdjustVolume(VolumeChange.SetTo(50)), "Sara, poné el volumen al 50%", "volumen 50", "poné el volumen en cincuenta", "dejá el volumen a la mitad")
        assertIntent(AdjustVolume(VolumeChange.SetTo(35)), "poné el volumen al treinta y cinco por ciento")
        assertIntent(AdjustVolume(VolumeChange.Decrease(20)), "bajá el volumen un 20%")
        assertIntent(AdjustVolume(VolumeChange.Increase(10)), "poné el volumen un 10% más")
        assertIntent(AdjustVolume(VolumeChange.Max), "Sara, volumen máximo", "poné la música a todo volumen", "subilo al máximo", "al palo")
        assertIntent(AdjustVolume(VolumeChange.SetTo(0)), "bajá el volumen al mínimo")
        assertIntent(AdjustVolume(VolumeChange.Mute), "Sara, silenciá la música", "muteá")
        assertIntent(AdjustVolume(VolumeChange.Unmute), "quitá el silencio", "activá el sonido")
        assertIntent(AdjustVolume(VolumeChange.Increase(5)), "subí un poco el volumen")
        assertIntent(AdjustVolume(VolumeChange.Increase(30)), "subí la música un 30%")
    }

    @Test
    fun `porcentajes fuera de rango se acotan`() {
        assertEquals(AdjustVolume(VolumeChange.SetTo(100)), intentOf("poné el volumen al 150%"))
    }

    @Test
    fun `busqueda de musica como entidad`() {
        assertEquals(CommandIntent.PlayMusic("soda stereo"), intentOf("Poné música de Soda Stereo"))
        assertEquals(CommandIntent.PlayMusic(null), intentOf("poné algo de música"))
    }

    @Test
    fun `cancelar y frases no reconocidas`() {
        assertIntent(CommandIntent.Cancel, "nada", "cancelá", "olvidalo")
        assertTrue(intentOf("¿qué hora es?") is CommandIntent.Chat)
        assertTrue(intentOf("¿quién ganó el partido?") is CommandIntent.Unknown)
        assertTrue(intentOf("Sara") is CommandIntent.Unknown)
        assertTrue(intentOf("") is CommandIntent.Unknown)
        assertTrue(intentOf("poné el volumen") is CommandIntent.Unknown)
    }

    @Test
    fun `interprete compuesto solo consulta la IA si las reglas fallan`() = runTest {
        var calls = 0
        val ai = object : CommandInterpreter {
            override suspend fun interpret(text: String): InterpretationResult {
                calls++
                return InterpretationResult(CommandIntent.Next, 0.7f, text, "ai")
            }
        }
        val composite = CompositeCommandInterpreter(interpreter, ai)
        assertEquals(AdjustVolume(VolumeChange.Increase(null)), composite.interpret("subí el volumen").intent)
        assertEquals(0, calls)
        assertEquals(CommandIntent.Next, composite.interpret("hacé lo tuyo").intent)
        assertEquals(1, calls)
    }
}
