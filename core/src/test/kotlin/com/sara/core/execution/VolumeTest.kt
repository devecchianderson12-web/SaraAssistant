package com.sara.core.execution

import com.sara.core.model.CommandIntent
import com.sara.core.model.VolumeChange
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VolumeCalculatorTest {
    private val range15 = VolumeState(current = 6, min = 0, max = 15) // 40 %

    @Test
    fun `porcentaje actual sobre el rango`() {
        assertEquals(40, VolumeCalculator.percentOf(range15))
        assertEquals(0, VolumeCalculator.percentOf(VolumeState(1, 1, 25)))
        assertEquals(100, VolumeCalculator.percentOf(VolumeState(25, 1, 25)))
    }

    @Test
    fun `aumento relativo suma puntos porcentuales`() {
        val target = VolumeCalculator.target(range15, VolumeChange.Increase(30)) as VolumeTarget.Index
        assertEquals(11, target.index) // 70 % de 15 = 10.5 -> 11
        assertEquals(73, target.percent)
    }

    @Test
    fun `valor absoluto establece el porcentaje del rango`() {
        val target = VolumeCalculator.target(range15, VolumeChange.SetTo(30)) as VolumeTarget.Index
        assertEquals(5, target.index) // 4.5 -> 5 (redondeo)
        val withMin = VolumeCalculator.target(VolumeState(10, 1, 25), VolumeChange.SetTo(50)) as VolumeTarget.Index
        assertEquals(13, withMin.index)
    }

    @Test
    fun `cambios pequenos siempre mueven al menos un paso`() {
        val coarse = VolumeState(current = 3, min = 0, max = 7)
        val up = VolumeCalculator.target(coarse, VolumeChange.Increase(5)) as VolumeTarget.Index
        assertEquals(4, up.index)
        val down = VolumeCalculator.target(coarse, VolumeChange.Decrease(5)) as VolumeTarget.Index
        assertEquals(2, down.index)
    }

    @Test
    fun `limites del rango`() {
        val top = VolumeCalculator.target(VolumeState(14, 0, 15), VolumeChange.Increase(50)) as VolumeTarget.Index
        assertEquals(15, top.index)
        val bottom = VolumeCalculator.target(VolumeState(1, 0, 15), VolumeChange.Decrease(50)) as VolumeTarget.Index
        assertEquals(0, bottom.index)
        assertEquals(VolumeTarget.Index(15, 100), VolumeCalculator.target(range15, VolumeChange.Max))
    }
}

class FakeVolume(var state: VolumeState, private val limitTo: Int? = null, private val reject: Boolean = false) : VolumeControl {
    var lastSet: Int? = null
    override fun state() = state
    override fun setIndex(index: Int): Boolean {
        if (reject) return false
        lastSet = index
        state = state.copy(current = limitTo?.let { minOf(it, index) } ?: index, isMuted = false)
        return true
    }
    override fun setMuted(muted: Boolean): Boolean {
        if (reject) return false
        state = state.copy(isMuted = muted)
        return true
    }
}

class FakeMedia(private val result: MediaActionResult) : MediaControl {
    val calls = mutableListOf<Pair<MediaAction, String?>>()
    override suspend fun perform(action: MediaAction, query: String?): MediaActionResult {
        calls += action to query
        return result
    }
}

class DefaultCommandExecutorTest {
    private val responses = ResponseCatalog(Random(1))

    @Test
    fun `ejecuta volumen absoluto y responde con el porcentaje real`() = runTest {
        val volume = FakeVolume(VolumeState(6, 0, 15))
        val executor = DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), volume, responses)
        val result = executor.execute(CommandIntent.AdjustVolume(VolumeChange.SetTo(50)))
        assertTrue(result.success)
        assertEquals(8, volume.lastSet)
        assertEquals("Puse el volumen al 53 por ciento.", result.response)
    }

    @Test
    fun `informa cuando el sistema limita el volumen`() = runTest {
        val volume = FakeVolume(VolumeState(6, 0, 15), limitTo = 10)
        val executor = DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), volume, responses)
        val result = executor.execute(CommandIntent.AdjustVolume(VolumeChange.Max))
        assertTrue(result.response.contains("limitó"))
    }

    @Test
    fun `errores de volumen y dispositivos de volumen fijo`() = runTest {
        val rejected = DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), FakeVolume(VolumeState(6, 0, 15), reject = true), responses)
        assertFalse(rejected.execute(CommandIntent.AdjustVolume(VolumeChange.Increase(null))).success)
        val fixed = DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), FakeVolume(VolumeState(6, 0, 15, isFixed = true)), responses)
        assertFalse(fixed.execute(CommandIntent.AdjustVolume(VolumeChange.Mute)).success)
    }

    @Test
    fun `ya al maximo no cambia nada`() = runTest {
        val volume = FakeVolume(VolumeState(15, 0, 15))
        val result = DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), volume, responses)
            .execute(CommandIntent.AdjustVolume(VolumeChange.Increase(null)))
        assertEquals("El volumen ya está al máximo.", result.response)
        assertEquals(null, volume.lastSet)
    }

    @Test
    fun `bajar con la musica silenciada no cambia nada y lo explica`() = runTest {
        val volume = FakeVolume(VolumeState(0, 0, 15, isMuted = true))
        val result = DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), volume, responses)
            .execute(CommandIntent.AdjustVolume(VolumeChange.Decrease(null)))
        assertTrue(result.response.contains("silenciada"))
        assertEquals(null, volume.lastSet)
    }

    @Test
    fun `subir con la musica silenciada quita el silencio`() = runTest {
        val volume = FakeVolume(VolumeState(0, 0, 15, isMuted = true))
        DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), volume, responses)
            .execute(CommandIntent.AdjustVolume(VolumeChange.SetTo(40)))
        assertFalse(volume.state.isMuted)
        assertEquals(6, volume.lastSet)
    }

    @Test
    fun `respuestas multimedia honestas`() = runTest {
        val media = FakeMedia(MediaActionResult.Unsupported("Radio X"))
        val result = DefaultCommandExecutor(media, FakeVolume(VolumeState(1, 0, 15)), responses).execute(CommandIntent.Next)
        assertFalse(result.success)
        assertEquals("Radio X no permite pasar a la siguiente canción.", result.response)

        val play = DefaultCommandExecutor(FakeMedia(MediaActionResult.Done(null)), FakeVolume(VolumeState(1, 0, 15)), responses)
            .execute(CommandIntent.PlayMusic())
        assertEquals("Perfecto, voy a reproducir música.", play.response)

        val noApp = DefaultCommandExecutor(FakeMedia(MediaActionResult.NoMediaApp), FakeVolume(VolumeState(1, 0, 15)), responses) { false }
            .execute(CommandIntent.PlayMusic())
        assertTrue(noApp.needsNotificationAccess)
    }

    @Test
    fun `excepciones del reproductor se convierten en error`() = runTest {
        val media = object : MediaControl {
            override suspend fun perform(action: MediaAction, query: String?): MediaActionResult = throw IllegalStateException("sesión caída")
        }
        val result = DefaultCommandExecutor(media, FakeVolume(VolumeState(1, 0, 15)), responses).execute(CommandIntent.Pause)
        assertFalse(result.success)
        assertTrue(result.response.contains("sesión caída"))
    }
}
