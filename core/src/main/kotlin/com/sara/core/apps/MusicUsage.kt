package com.sara.core.apps

import kotlin.math.pow

/**
 * Aprende qué app de música usa más el usuario a partir del tiempo que cada app estuvo
 * reproduciendo. El puntaje decae con el tiempo (vida media [halfLifeMillis]) para que, si el
 * usuario cambia de Spotify a YouTube, Sara lo note en pocos días.
 */
class MusicUsage(
    private val halfLifeMillis: Long = 10L * DAY,
) {
    private data class Entry(var score: Double, var updatedAt: Long)

    private val entries = mutableMapOf<String, Entry>()

    /** Suma [playedMillis] de reproducción de [packageName] al momento [now]. */
    @Synchronized
    fun record(packageName: String, playedMillis: Long, now: Long) {
        if (playedMillis <= 0) return
        val entry = entries.getOrPut(packageName) { Entry(0.0, now) }
        entry.score = decayed(entry, now) + playedMillis
        entry.updatedAt = now
    }

    /** Paquetes ordenados de más a menos usado, ignorando los que ya casi no cuentan. */
    @Synchronized
    fun ranking(now: Long): List<String> =
        entries.map { (pkg, e) -> pkg to decayed(e, now) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }

    /** La app más usada entre las instaladas, o null si todavía no hay datos. */
    fun favorite(now: Long, installed: (String) -> Boolean = { true }): String? =
        ranking(now).firstOrNull(installed)

    @Synchronized
    fun serialize(): String = entries.entries.joinToString("\n") { (pkg, e) -> "$pkg\t${e.score}\t${e.updatedAt}" }

    @Synchronized
    fun load(data: String?) {
        entries.clear()
        data?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@forEach
            val score = parts[1].toDoubleOrNull() ?: return@forEach
            val at = parts[2].toLongOrNull() ?: return@forEach
            if (parts[0].isNotBlank()) entries[parts[0]] = Entry(score, at)
        }
    }

    private fun decayed(e: Entry, now: Long): Double {
        val elapsed = (now - e.updatedAt).coerceAtLeast(0)
        return e.score * 0.5.pow(elapsed.toDouble() / halfLifeMillis)
    }

    companion object {
        const val DAY = 24L * 60 * 60 * 1000
        /** Menos de 30 s de música acumulada no alcanza para elegir una app. */
        const val MIN_SCORE = 30_000.0

        /** Si todavía no aprendió nada: apps de música conocidas, en orden de preferencia. */
        val KNOWN_MUSIC_APPS = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "app.morphe.android.apps.youtube.music",
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "app.morphe.android.youtube",
            "deezer.android.app",
            "com.apple.android.music",
            "com.amazon.mp3",
            "com.aspiro.tidal",
            "com.soundcloud.android",
        )
    }
}
