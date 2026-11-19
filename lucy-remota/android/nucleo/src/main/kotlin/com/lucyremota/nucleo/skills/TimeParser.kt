package com.lucyremota.nucleo.skills

import com.lucyremota.nucleo.interpreter.SpanishNumbers
import com.lucyremota.nucleo.interpreter.TextNormalizer
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Horas y duraciones dichas en castellano: «a las 7», «a las 7 y media», «a las 19:30»,
 * «mañana a las 9 de la mañana», «en 10 minutos», «dentro de una hora y media».
 */
object TimeParser {
    data class Clock(val hour: Int, val minute: Int)

    private fun numberTokens(normalized: String): List<String> =
        TextNormalizer.tokens(normalized).map { if (it == "un" || it == "una") "1" else it }

    /** Duración en segundos: «10 minutos», «una hora y media», «30 segundos», «hora y cuarto». */
    fun duration(text: String): Long? {
        val tokens = numberTokens(TextNormalizer.normalize(text))
        var total = 0L
        var found = false
        var i = 0
        while (i < tokens.size) {
            val n = SpanishNumbers.parseAt(tokens, i)
            if (n != null) {
                val unit = tokens.getOrNull(n.endExclusive)
                val seconds = unitSeconds(unit)
                if (seconds != null) {
                    total += n.value * seconds
                    found = true
                    // «una hora y media», «dos minutos y medio»
                    if (tokens.getOrNull(n.endExclusive + 1) == "y" && tokens.getOrNull(n.endExclusive + 2) in setOf("media", "medio")) {
                        total += seconds / 2
                        i = n.endExclusive + 3
                        continue
                    }
                    if (tokens.getOrNull(n.endExclusive + 1) == "y" && tokens.getOrNull(n.endExclusive + 2) == "cuarto") {
                        total += seconds / 4
                        i = n.endExclusive + 3
                        continue
                    }
                    i = n.endExclusive + 1
                    continue
                }
            } else if (tokens[i] in setOf("media", "medio") && unitSeconds(tokens.getOrNull(i + 1)) != null) {
                total += unitSeconds(tokens[i + 1])!! / 2
                found = true
                i += 2
                continue
            }
            i++
        }
        return if (found && total > 0) total else null
    }

    private fun unitSeconds(unit: String?): Long? = when (unit) {
        "segundo", "segundos", "seg" -> 1
        "minuto", "minutos", "min" -> 60
        "hora", "horas", "hs" -> 3_600
        "dia", "dias" -> 86_400
        else -> null
    }

    /** Hora del día: «7», «7 y media», «19:30», «7 de la tarde», «las 8 menos cuarto». */
    fun clock(text: String): Clock? {
        val original = text.lowercase()
        Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(original)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return Clock(adjust(h, original), min)
        }
        val tokens = numberTokens(TextNormalizer.normalize(text))
        // «… a las 7», «para las 8», «a la una»: la primera palabra de hora seguida de un número.
        val idx = tokens.indices.firstOrNull { i ->
            tokens[i] in setOf("las", "la", "a", "para") && SpanishNumbers.parseAt(tokens, i + 1) != null &&
                unitSeconds(tokens.getOrNull(SpanishNumbers.parseAt(tokens, i + 1)!!.endExclusive)) == null
        } ?: return null
        val hourMatch = SpanishNumbers.parseAt(tokens, idx + 1) ?: return null
        if (hourMatch.value !in 0..24) return null
        var hour = hourMatch.value % 24
        var minute = 0
        var j = hourMatch.endExclusive
        when {
            tokens.getOrNull(j) == "y" && tokens.getOrNull(j + 1) == "media" -> minute = 30
            tokens.getOrNull(j) == "y" && tokens.getOrNull(j + 1) == "cuarto" -> minute = 15
            tokens.getOrNull(j) == "menos" && tokens.getOrNull(j + 1) == "cuarto" -> { hour = (hour + 23) % 24; minute = 45 }
            tokens.getOrNull(j) == "y" -> SpanishNumbers.parseAt(tokens, j + 1)?.let { if (it.value in 0..59) minute = it.value }
            else -> SpanishNumbers.parseAt(tokens, j)?.let { if (it.value in 0..59 && tokens.getOrNull(it.endExclusive) != "minutos") minute = it.value }
        }
        return Clock(adjust(hour, original), minute)
    }

    /** «de la tarde/noche» suma 12 horas a horas de 1 a 11. */
    private fun adjust(hour: Int, text: String): Int {
        val pm = Regex("de la (tarde|noche)|\\bpm\\b|p\\.m\\.").containsMatchIn(text)
        val am = Regex("de la (mañana|manana|madrugada)|\\bam\\b").containsMatchIn(text)
        return when {
            pm && hour in 1..11 -> hour + 12
            am && hour == 12 -> 0
            else -> hour
        }
    }

    /**
     * Momento futuro: «en 10 minutos», «mañana a las 9», «a las 7» (hoy, o mañana si ya pasó),
     * «pasado mañana a las 8».
     */
    fun moment(text: String, now: LocalDateTime): LocalDateTime? {
        val normalized = TextNormalizer.normalize(text)
        val padded = " $normalized "
        if (padded.contains(" en ") || padded.contains(" dentro de ")) {
            duration(text)?.let { return now.plusSeconds(it) }
        }
        val c = clock(text) ?: return null
        var day = now.toLocalDate()
        when {
            padded.contains(" pasado manana ") -> day = day.plusDays(2)
            Regex(" manana (a|al|por|temprano|de) | manana $").containsMatchIn(padded) && !padded.contains(" de la manana") ->
                day = day.plusDays(1)
            padded.contains(" manana ") && padded.indexOf(" manana ") < padded.indexOf(" las ") -> day = day.plusDays(1)
        }
        var at = LocalDateTime.of(day, LocalTime.of(c.hour, c.minute))
        if (!at.isAfter(now)) {
            // «a las 7» cuando ya son las 8: si no dijo «de la mañana/tarde», probar 12 horas después.
            val halfDay = at.plusHours(12)
            at = if (c.hour < 12 && !Regex("de la (manana|tarde|noche)").containsMatchIn(normalized) && halfDay.isAfter(now)) halfDay
            else at.plusDays(1)
        }
        return at
    }

    fun describe(at: LocalDateTime, now: LocalDateTime): String {
        val time = "${at.hour}:${at.minute.toString().padStart(2, '0')}"
        return when (at.toLocalDate()) {
            now.toLocalDate() -> "hoy a las $time"
            now.toLocalDate().plusDays(1) -> "mañana a las $time"
            else -> "el ${at.dayOfMonth}/${at.monthValue} a las $time"
        }
    }

    fun describeDuration(seconds: Long): String {
        val h = seconds / 3_600
        val m = (seconds % 3_600) / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { if (it == 1L) "1 hora" else "$it horas" },
            m.takeIf { it > 0 }?.let { if (it == 1L) "1 minuto" else "$it minutos" },
            s.takeIf { it > 0 }?.let { if (it == 1L) "1 segundo" else "$it segundos" },
        ).joinToString(" y ")
    }
}
