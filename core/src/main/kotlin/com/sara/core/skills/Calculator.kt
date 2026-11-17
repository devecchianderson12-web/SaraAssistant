package com.sara.core.skills

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Cálculos dichos en voz alta, sin Internet: «cuánto es 25 por 4», «raíz de 81», «15 por ciento de
 * 200», «2 elevado a 10», «(3 + 4) por 2», «mil quinientos dividido 3».
 */
object Calculator {
    private val marks = "\\p{Mn}+".toRegex()

    /** Pasa palabras a símbolos: «25 por 4» -> «25*4». Devuelve null si no parece una cuenta. */
    fun toExpression(text: String): String? {
        var t = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "")
        t = t.replace(Regex("^.*?\\b(cuanto es|cuanto da|cuanto son|calcula(me)?|calcul[aá]|resolve(me)?|cuanto)\\b"), " ")
        // «por ciento» antes de convertir palabras (si no, «ciento» se leería como 100).
        t = t.replace(Regex("\\bpor\\s*ciento\\b"), " % ")
        t = numberWords(t)
        // «15 % de 200» -> 15%200 (operador de porcentaje)
        t = t.replace(Regex("(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:de|del)\\s*"), "$1%")
        t = t.replace(Regex("\\braiz cuadrada de\\b|\\braiz de\\b"), " r ")
        val replacements = listOf(
            "elevado al cuadrado" to "^2", "al cuadrado" to "^2", "al cubo" to "^3", "elevado a la" to "^", "elevado a" to "^",
            "multiplicado por" to "*", "dividido por" to "/", "dividido entre" to "/", "dividido" to "/", "entre" to "/",
            "sobre" to "/", "mas" to "+", "menos" to "-", "por" to "*", "x" to "*", "×" to "*", "÷" to "/",
        )
        for ((word, symbol) in replacements) t = t.replace(Regex("(?<![a-z])${Regex.escape(word)}(?![a-z])"), " $symbol ")
        t = t.replace(Regex("(\\d),(\\d)"), "$1.$2").replace("?", " ").replace("=", " ")
        val expr = t.replace(Regex("\\s+"), "")
        if (expr.isEmpty() || !expr.all { it.isDigit() || it in "+-*/^().%r" }) return null
        if (expr.none { it in "+-*/^%r" } || expr.none { it.isDigit() }) return null
        return expr
    }

    fun evaluate(expression: String): Double? = runCatching { Parser(expression).parse() }.getOrNull()?.takeIf { it.isFinite() }

    fun answer(text: String): String? {
        val expr = toExpression(text) ?: return null
        val value = evaluate(expr) ?: return null
        return "Da ${format(value)}."
    }

    fun format(value: Double): String {
        if (abs(value - value.roundToLong()) < 1e-9 && abs(value) < 1e15) return value.roundToLong().toString()
        return String.format(Locale.forLanguageTag("es-AR"), "%.4f", value).trimEnd('0').trimEnd(',')
    }

    private val small = mapOf(
        "cero" to 0, "uno" to 1, "una" to 1, "un" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14,
        "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20,
        "veintiuno" to 21, "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25,
        "veintiseis" to 26, "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30,
        "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90,
        "cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300, "cuatrocientos" to 400,
        "quinientos" to 500, "seiscientos" to 600, "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900,
    )

    /** «mil quinientos treinta y dos» -> «1532». */
    private fun numberWords(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            var total = 0L
            var current = 0L
            var used = 0
            var j = i
            while (j < words.size) {
                val w = words[j]
                when {
                    w in small -> { current += small.getValue(w); used++ }
                    w == "mil" -> { current = (if (current == 0L) 1 else current) * 1_000; total += current; current = 0; used++ }
                    w == "millon" || w == "millones" -> { current = (if (current == 0L) 1 else current) * 1_000_000; total += current; current = 0; used++ }
                    w == "y" && used > 0 && words.getOrNull(j + 1) in small && (small[words.getOrNull(j + 1)] ?: 10) < 10 -> {}
                    else -> break
                }
                j++
            }
            if (used > 0) {
                out += (total + current).toString()
                i = j
            } else {
                out += words[i]
                i++
            }
        }
        return out.joinToString(" ")
    }

    /** Analizador recursivo: + - * / ^ %, raíz (r) y paréntesis. */
    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expression()
            require(pos == s.length) { "sobra texto" }
            return v
        }

        private fun peek() = s.getOrNull(pos)

        private fun expression(): Double {
            var v = term()
            while (true) {
                v = when (peek()) {
                    '+' -> { pos++; v + term() }
                    '-' -> { pos++; v - term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                v = when (peek()) {
                    '*' -> { pos++; v * power() }
                    '/' -> { pos++; val d = power(); require(d != 0.0); v / d }
                    '%' -> { pos++; v / 100.0 * power() }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                pos++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double = when (peek()) {
            '-' -> { pos++; -unary() }
            '+' -> { pos++; unary() }
            'r' -> { pos++; val v = unary(); require(v >= 0); sqrt(v) }
            else -> primary()
        }

        private fun primary(): Double {
            if (peek() == '(') {
                pos++
                val v = expression()
                require(peek() == ')')
                pos++
                return v
            }
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            require(pos > start)
            return s.substring(start, pos).toDouble()
        }
    }
}

/** Conversiones de unidades comunes, sin Internet. */
object UnitConverter {
    private data class Unit(val names: List<String>, val kind: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double, val label: String)

    private fun linear(kind: String, factor: Double, label: String, vararg names: String) =
        Unit(names.toList(), kind, { it * factor }, { it / factor }, label)

    private val units = listOf(
        linear("len", 1.0, "metros", "metro", "metros", "m"),
        linear("len", 1_000.0, "kilómetros", "kilometro", "kilometros", "km"),
        linear("len", 0.01, "centímetros", "centimetro", "centimetros", "cm"),
        linear("len", 0.001, "milímetros", "milimetro", "milimetros", "mm"),
        linear("len", 0.0254, "pulgadas", "pulgada", "pulgadas"),
        linear("len", 0.3048, "pies", "pie", "pies"),
        linear("len", 1_609.344, "millas", "milla", "millas"),
        linear("len", 0.9144, "yardas", "yarda", "yardas"),
        linear("mass", 1.0, "kilos", "kilo", "kilos", "kilogramo", "kilogramos", "kg"),
        linear("mass", 0.001, "gramos", "gramo", "gramos", "g"),
        linear("mass", 0.45359237, "libras", "libra", "libras"),
        linear("mass", 0.028349523, "onzas", "onza", "onzas"),
        linear("mass", 1_000.0, "toneladas", "tonelada", "toneladas"),
        linear("vol", 1.0, "litros", "litro", "litros", "l"),
        linear("vol", 0.001, "mililitros", "mililitro", "mililitros", "ml"),
        linear("vol", 3.785411784, "galones", "galon", "galones"),
        linear("vol", 0.25, "tazas", "taza", "tazas"),
        linear("time", 1.0, "segundos", "segundo", "segundos"),
        linear("time", 60.0, "minutos", "minuto", "minutos"),
        linear("time", 3_600.0, "horas", "hora", "horas"),
        linear("time", 86_400.0, "días", "dia", "dias"),
        linear("speed", 1.0, "kilómetros por hora", "kilometros por hora", "km por hora", "kmh"),
        linear("speed", 1.609344, "millas por hora", "millas por hora", "mph"),
        linear("data", 1.0, "megas", "mega", "megas", "megabyte", "megabytes", "mb"),
        linear("data", 1_024.0, "gigas", "giga", "gigas", "gigabyte", "gigabytes", "gb"),
        linear("data", 1_048_576.0, "teras", "tera", "teras", "terabyte", "terabytes", "tb"),
        Unit(listOf("celsius", "centigrados", "grados celsius", "grados centigrados", "grados"), "temp", { it }, { it }, "grados Celsius"),
        Unit(listOf("fahrenheit", "grados fahrenheit"), "temp", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }, "grados Fahrenheit"),
        Unit(listOf("kelvin", "grados kelvin"), "temp", { it - 273.15 }, { it + 273.15 }, "kelvin"),
    )

    private val marks = "\\p{Mn}+".toRegex()

    private fun find(name: String): Unit? {
        val n = name.trim()
        return units.firstOrNull { u -> u.names.any { it == n } }
            ?: units.sortedByDescending { u -> u.names.maxOf { it.length } }
                .firstOrNull { u -> u.names.any { it.length >= 3 && (n.endsWith(" $it") || n == it) } }
    }

    /** «cuántos centímetros son 3 pulgadas», «pasá 30 grados a fahrenheit», «10 millas en kilómetros». */
    fun answer(text: String): String? {
        val t = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "").replace(Regex("[¿?¡!]"), "").trim()
        val num = "(-?\\d+(?:[.,]\\d+)?)"
        // «cuántos X son N Y» / «cuántos X hay en N Y»
        Regex("cuant[oa]s\\s+(.+?)\\s+(?:son|hay en|tiene|tienen|equivalen a)\\s+$num\\s+(.+)$").find(t)?.let { m ->
            return convert(m.groupValues[2], m.groupValues[3], m.groupValues[1])
        }
        // «N Y a/en X»
        Regex("$num\\s+(.+?)\\s+(?:a|en|son cuantos|son cuantas|cuantos son en)\\s+(.+)$").find(t)?.let { m ->
            return convert(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        return null
    }

    private fun convert(amount: String, fromName: String, toName: String): String? {
        val value = amount.replace(',', '.').toDoubleOrNull() ?: return null
        val from = find(fromName) ?: return null
        val to = find(toName.removeSuffix(" hay")) ?: return null
        if (from.kind != to.kind || from === to) return null
        val result = to.fromBase(from.toBase(value))
        return "${Calculator.format(value)} ${from.label} son ${Calculator.format(result)} ${to.label}."
    }
}
