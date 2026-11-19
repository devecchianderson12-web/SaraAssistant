package com.lucyremota.nucleo.apps

import java.text.Normalizer
import java.util.Locale

data class InstalledApp(val label: String, val id: String)

/**
 * Elige la app instalada cuyo nombre coincide mejor con lo que dijo el usuario.
 * «youtube» encuentra «YouTube» y, si no existe, «Morphe YouTube»; tolera errores leves («whatsap»).
 */
object AppMatcher {
    private val marks = "\\p{Mn}+".toRegex()
    private val nonAlnum = "[^a-z0-9 ]".toRegex()
    private val spaces = "\\s+".toRegex()
    private val noise = setOf("app", "aplicacion", "la", "el", "de", "the")

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "")
            .replace(nonAlnum, " ").replace(spaces, " ").trim()

    fun score(query: String, label: String): Double {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return 0.0
        if (q == l) return 1.0
        val qCompact = q.replace(" ", "")
        val lCompact = l.replace(" ", "")
        if (qCompact == lCompact) return 0.98
        val qTokens = q.split(' ').filter { it !in noise }.ifEmpty { q.split(' ') }
        val lTokens = l.split(' ')
        var best = 0.0
        // Todas las palabras dichas están en el nombre: «youtube» -> «Morphe YouTube».
        if (qTokens.all { it in lTokens }) {
            // «Morphe YouTube» termina en lo pedido (es una variante); «YouTube Music» es otra app.
            val endsWithQuery = lTokens.takeLast(qTokens.size) == qTokens
            best = maxOf(best, 0.9 - 0.02 * (lTokens.size - qTokens.size).coerceAtLeast(0) + if (endsWithQuery) 0.03 else 0.0)
        }
        if (qCompact.length >= 3 && lCompact.contains(qCompact)) best = maxOf(best, 0.84 - 0.01 * (lCompact.length - qCompact.length).coerceAtMost(20))
        // Errores leves por palabra.
        val perToken = qTokens.map { qt -> lTokens.maxOf { similarity(qt, it) } }.average()
        best = maxOf(best, perToken * 0.85)
        best = maxOf(best, similarity(qCompact, lCompact) * 0.85)
        return best
    }

    /** Nombres en español de apps del sistema que en algunos teléfonos están en inglés. */
    private val synonyms = mapOf(
        "configuracion" to listOf("settings", "ajustes"), "ajustes" to listOf("settings", "configuracion"),
        "reloj" to listOf("clock", "alarma"), "alarma" to listOf("clock", "reloj"), "camara" to listOf("camera"),
        "galeria" to listOf("gallery", "photos", "fotos"), "fotos" to listOf("photos", "gallery", "galeria"),
        "contactos" to listOf("contacts"), "telefono" to listOf("phone", "dialer"), "mensajes" to listOf("messages", "messaging"),
        "calculadora" to listOf("calculator"), "calendario" to listOf("calendar"), "archivos" to listOf("files", "file manager"),
        "navegador" to listOf("chrome", "browser"), "correo" to listOf("gmail", "email", "mail"), "mapas" to listOf("maps"),
        "tienda" to listOf("play store", "galaxy store"), "musica" to listOf("music"),
    )

    fun best(query: String, apps: List<InstalledApp>, threshold: Double = 0.62): InstalledApp? {
        val variants = listOf(query) + synonyms[normalize(query)].orEmpty()
        return apps.map { app -> app to variants.maxOf { score(it, app.label) } }
            .filter { it.second >= threshold }
            .sortedWith(compareByDescending<Pair<InstalledApp, Double>> { it.second }.thenBy { it.first.label.length })
            .firstOrNull()?.first
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
