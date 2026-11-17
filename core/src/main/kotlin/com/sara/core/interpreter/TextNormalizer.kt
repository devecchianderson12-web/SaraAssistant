package com.sara.core.interpreter

import java.text.Normalizer
import java.util.Locale

/** Normaliza frases reconocidas: minúsculas, sin tildes ni signos, sin la palabra de activación ni muletillas. */
object TextNormalizer {
    private val combiningMarks = "\\p{Mn}+".toRegex()
    private val digitLetter = "(\\d)([a-z])".toRegex()
    private val letterDigit = "([a-z])(\\d)".toRegex()
    private val nonAlphanumeric = "[^a-z0-9 ]".toRegex()
    private val spaces = "\\s+".toRegex()
    private val wakePrefix = "^(?:(?:oye|oi|hey|ey|che|hola)\\s+)?(?:${com.sara.core.model.AssistantIdentity.NAMES})\\b\\s*".toRegex()
    private val porciento = "\\bporciento\\b".toRegex()
    private val fillers = listOf("por favor", "porfavor", "porfa", "porfis", "che", "eh", "em", "mmm", "bueno", "ehh")

    fun normalize(input: String): String {
        var text = input.lowercase(Locale.ROOT)
        text = text.replace("%", " por ciento ")
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "")
        text = text.replace(nonAlphanumeric, " ")
        text = text.replace(digitLetter, "$1 $2").replace(letterDigit, "$1 $2")
        text = text.replace(spaces, " ").trim()
        text = text.replace(wakePrefix, "")
        text = text.replace(porciento, "por ciento")
        var padded = " $text "
        for (filler in fillers) {
            while (padded.contains(" $filler ")) padded = padded.replace(" $filler ", " ")
        }
        return padded.replace(spaces, " ").trim()
    }

    fun tokens(normalized: String): List<String> =
        if (normalized.isBlank()) emptyList() else normalized.split(' ')
}

/** Números en español (dígitos o palabras) del 0 al 100. */
object SpanishNumbers {
    data class Match(val value: Int, val start: Int, val endExclusive: Int)

    private val units = mapOf(
        "cero" to 0, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
        "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
    )
    private val specials = mapOf(
        "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
        "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19,
        "veinte" to 20, "veintiuno" to 21, "veintiun" to 21, "veintidos" to 22, "veintitres" to 23,
        "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26, "veintisiete" to 27,
        "veintiocho" to 28, "veintinueve" to 29, "cien" to 100, "ciento" to 100,
    )
    private val tens = mapOf(
        "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60,
        "setenta" to 70, "ochenta" to 80, "noventa" to 90,
    )

    fun parseAt(tokens: List<String>, index: Int): Match? {
        val token = tokens.getOrNull(index) ?: return null
        token.toIntOrNull()?.let { return Match(it, index, index + 1) }
        units[token]?.let { return Match(it, index, index + 1) }
        specials[token]?.let { return Match(it, index, index + 1) }
        tens[token]?.let { tenValue ->
            val unit = if (tokens.getOrNull(index + 1) == "y") units[tokens.getOrNull(index + 2)] else null
            return if (unit != null && unit > 0) Match(tenValue + unit, index, index + 3) else Match(tenValue, index, index + 1)
        }
        return null
    }

    fun findAll(tokens: List<String>): List<Match> {
        val result = mutableListOf<Match>()
        var i = 0
        while (i < tokens.size) {
            val match = parseAt(tokens, i)
            if (match != null) {
                result += match
                i = match.endExclusive
            } else {
                i++
            }
        }
        return result
    }
}
