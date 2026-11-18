package com.sara.core.memory

import org.json.JSONObject
import kotlin.math.pow

/**
 * Gustos musicales aprendidos solos: cuánto se escuchó cada artista en cualquier app (Spotify,
 * YouTube Music, BitChord…). Lo viejo pesa menos (se va olvidando a la mitad cada 30 días), así
 * sigue los gustos actuales. Sólo guarda nombres de artistas y minutos.
 */
class MusicTaste {
    private class Entry(var name: String, var seconds: Double, var at: Long)

    private val artists = linkedMapOf<String, Entry>()

    @Synchronized
    fun record(artist: String, millis: Long, now: Long) {
        val name = cleanArtist(artist) ?: return
        if (millis < 5_000) return
        val key = name.lowercase()
        val e = artists.getOrPut(key) { Entry(name, 0.0, now) }
        e.seconds = decayed(e, now) + millis / 1000.0
        e.at = now
        e.name = name
        if (artists.size > MAX_ARTISTS) {
            artists.entries.minByOrNull { decayed(it.value, now) }?.key?.let { artists.remove(it) }
        }
    }

    /** Artistas más escuchados (al menos [minMinutes] minutos), de más a menos. */
    @Synchronized
    fun top(now: Long, count: Int = 5, minMinutes: Double = 3.0): List<String> =
        artists.values.map { it.name to decayed(it, now) }
            .filter { it.second >= minMinutes * 60 }
            .sortedByDescending { it.second }
            .take(count).map { it.first }

    /** «Duki, Bizarrap y Tini», o null si todavía no aprendió nada. */
    fun summary(now: Long): String? {
        val top = top(now)
        if (top.isEmpty()) return null
        return if (top.size == 1) top[0] else top.dropLast(1).joinToString(", ") + " y " + top.last()
    }

    @Synchronized
    fun serialize(): String = JSONObject().apply {
        artists.values.forEach { put(it.name, JSONObject().put("s", it.seconds).put("t", it.at)) }
    }.toString()

    @Synchronized
    fun load(json: String?) {
        artists.clear()
        val obj = runCatching { JSONObject(json ?: return) }.getOrNull() ?: return
        for (name in obj.keys()) {
            val e = obj.optJSONObject(name) ?: continue
            artists[name.lowercase()] = Entry(name, e.optDouble("s", 0.0), e.optLong("t"))
        }
    }

    private fun decayed(e: Entry, now: Long): Double =
        e.seconds * 0.5.pow(((now - e.at).coerceAtLeast(0) / HALF_LIFE_MS.toDouble()))

    companion object {
        private const val HALF_LIFE_MS = 30L * 24 * 3600 * 1000
        private const val MAX_ARTISTS = 200
        private val NOT_ARTISTS = setOf("unknown", "desconocido", "<unknown>", "various artists", "varios artistas", "youtube", "spotify")

        /** «Duki - Topic» → «Duki»; «Bizarrap, Quevedo» → «Bizarrap»; descarta «Desconocido». */
        fun cleanArtist(raw: String?): String? {
            val name = raw.orEmpty().replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*VEVO$", RegexOption.IGNORE_CASE), "")
                .split(Regex("\\s*(?:,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)).first()
                .trim()
            if (name.length < 2 || name.lowercase() in NOT_ARTISTS) return null
            return name
        }
    }
}
