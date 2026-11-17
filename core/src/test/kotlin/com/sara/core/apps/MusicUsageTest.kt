package com.sara.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicUsageTest {
    private val min = 60_000L
    private val day = MusicUsage.DAY

    @Test
    fun `sin datos no hay favorita`() {
        assertNull(MusicUsage().favorite(0))
    }

    @Test
    fun `elige la que mas reprodujo`() {
        val u = MusicUsage()
        u.record("spotify", 30 * min, 0)
        u.record("youtube", 5 * min, 0)
        assertEquals("spotify", u.favorite(0))
    }

    @Test
    fun `poco tiempo no alcanza`() {
        val u = MusicUsage()
        u.record("spotify", 10_000, 0)
        assertNull(u.favorite(0))
    }

    @Test
    fun `cambio de habito se nota con el tiempo`() {
        val u = MusicUsage()
        u.record("spotify", 300 * min, 0)
        // Tres semanas después usa YouTube todos los días una hora.
        for (d in 21..27) u.record("youtube", 60 * min, d * day)
        assertEquals("youtube", u.favorite(27 * day))
    }

    @Test
    fun `ignora apps desinstaladas`() {
        val u = MusicUsage()
        u.record("spotify", 30 * min, 0)
        u.record("youtube", 5 * min, 0)
        assertEquals("youtube", u.favorite(0) { it != "spotify" })
    }

    @Test
    fun `se guarda y se recupera`() {
        val u = MusicUsage()
        u.record("spotify", 30 * min, 0)
        u.record("youtube", 50 * min, 0)
        val copy = MusicUsage().apply { load(u.serialize()) }
        assertEquals(listOf("youtube", "spotify"), copy.ranking(0))
        MusicUsage().load("basura\nmal\tdato")
    }
}
