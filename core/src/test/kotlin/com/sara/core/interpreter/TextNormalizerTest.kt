package com.sara.core.interpreter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun `quita tildes signos y palabra de activacion`() {
        assertEquals("pone musica", TextNormalizer.normalize("¡Sara, poné música!"))
        assertEquals("subi el volumen", TextNormalizer.normalize("Che Sara, subí el volumen por favor"))
        assertEquals("pausa la musica", TextNormalizer.normalize("SARAH pausá la MÚSICA"))
    }

    @Test
    fun `convierte simbolo de porcentaje`() {
        assertEquals("subi un 30 por ciento", TextNormalizer.normalize("Subí un 30%"))
        assertEquals("bajale al 10 por ciento", TextNormalizer.normalize("bajale al 10porciento"))
    }

    @Test
    fun `no confunde palabras que contienen el nombre`() {
        assertEquals("sarampion", TextNormalizer.normalize("sarampión"))
    }

    @Test
    fun `numeros en palabras`() {
        fun parse(text: String) = SpanishNumbers.findAll(TextNormalizer.tokens(TextNormalizer.normalize(text))).firstOrNull()?.value
        assertEquals(30, parse("treinta"))
        assertEquals(35, parse("treinta y cinco"))
        assertEquals(25, parse("veinticinco"))
        assertEquals(100, parse("cien"))
        assertEquals(42, parse("42"))
        assertNull(parse("una canción"))
    }
}
