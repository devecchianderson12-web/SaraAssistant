package com.sara.core.apps

import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.model.CommandIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        InstalledApp("Morphe YouTube", "app.morphe.youtube"),
        InstalledApp("WhatsApp", "com.whatsapp"),
        InstalledApp("YouTube Music", "com.google.android.apps.youtube.music"),
        InstalledApp("Configuración", "com.android.settings"),
        InstalledApp("Spotify", "com.spotify.music"),
    )

    @Test
    fun `encuentra la app por nombre parecido`() {
        assertEquals("app.morphe.youtube", AppMatcher.best("youtube", apps)?.id)
        assertEquals("com.whatsapp", AppMatcher.best("whatsap", apps)?.id)
        assertEquals("com.whatsapp", AppMatcher.best("whats app", apps)?.id)
        assertEquals("com.google.android.apps.youtube.music", AppMatcher.best("youtube music", apps)?.id)
        assertEquals("com.android.settings", AppMatcher.best("configuracion", apps)?.id)
        assertNull(AppMatcher.best("calculadora", apps))
        assertEquals("clock", AppMatcher.best("reloj", listOf(InstalledApp("Clock", "clock")))?.id)
    }

    @Test
    fun `prefiere la coincidencia exacta`() {
        val withOfficial = apps + InstalledApp("YouTube", "com.google.android.youtube")
        assertEquals("com.google.android.youtube", AppMatcher.best("YouTube", withOfficial)?.id)
    }
}

class SearchAndOpenInterpreterTest {
    private val interpreter = RuleBasedCommandInterpreter()
    private fun intentOf(text: String) = interpreter.interpretNow(text).intent

    @Test
    fun `abrir apps`() {
        assertEquals(CommandIntent.OpenApp("youtube"), intentOf("Sara, abrí YouTube"))
        assertEquals(CommandIntent.OpenApp("whatsapp"), intentOf("abrime la app de WhatsApp"))
        assertEquals(CommandIntent.OpenApp("configuracion"), intentOf("abrí la configuración"))
    }

    @Test
    fun `buscar musica con y sin app`() {
        assertEquals(CommandIntent.PlayMusic("despacito", "youtube"), intentOf("buscá Despacito en YouTube"))
        assertEquals(CommandIntent.PlayMusic("soda stereo", "spotify"), intentOf("poné música de Soda Stereo en Spotify"))
        assertEquals(CommandIntent.PlayMusic("bachata de manuel turizo"), intentOf("reproducí la bachata de Manuel Turizo"))
        assertEquals(CommandIntent.PlayMusic("los redondos"), intentOf("poné los redondos"))
        assertEquals(CommandIntent.PlayMusic(null, "spotify"), intentOf("poné música en Spotify"))
        assertEquals(CommandIntent.PlayMusic(null, null), intentOf("poné algo de música"))
    }

    @Test
    fun `cambiar y sacar la musica`() {
        assertEquals(CommandIntent.Next, intentOf("cambiá la música"))
        assertEquals(CommandIntent.Next, intentOf("cambiá de música"))
        assertEquals(CommandIntent.StopMusic, intentOf("sacá la música por completo"))
        assertEquals(CommandIntent.StopMusic, intentOf("cerrá la música"))
    }
}
