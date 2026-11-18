package com.sara.assistant.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sara.core.apps.MusicUsage
import com.sara.core.util.SaraLogger

/**
 * Mide cuánto tiempo reproduce cada app (Spotify, YouTube, etc.) usando las sesiones multimedia,
 * para saber cuál usa más el usuario, y qué artistas escucha más (gustos musicales, sin que haga
 * falta decírselo). Sólo guarda paquetes, artistas y minutos. Necesita el «Acceso a notificaciones»
 * que Sara ya pide para controlar la música.
 */
class MusicUsageTracker(
    private val context: Context,
    private val logger: SaraLogger,
    /** Resumen de gustos actualizado («Duki, Bizarrap y Tini»). */
    private val onTaste: (String) -> Unit = {},
) {
    private val taste = com.sara.core.memory.MusicTaste().apply { load(context.getSharedPreferences("music_usage", Context.MODE_PRIVATE).getString(TASTE_KEY, null)) }
    /** Paquete -> artista que está sonando. */
    private val artistOf = mutableMapOf<String, String>()
    private var lastTaste: String? = null
    private val prefs = context.getSharedPreferences("music_usage", Context.MODE_PRIVATE)
    private val usage = MusicUsage().apply { load(prefs.getString(KEY, null)) }
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Paquete -> momento (elapsedRealtime) en que empezó a sonar. */
    private val playingSince = mutableMapOf<String, Long>()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var started = false
    private var lastStopped: Pair<String, Long>? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessionsChanged(list.orEmpty())
    }

    /** Idempotente; se llama al iniciar la app y cuando se concede el acceso a notificaciones. */
    fun start() = main.post {
        if (started) return@post
        try {
            val component = ComponentName(context, SaraNotificationListener::class.java)
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            started = true
            onSessionsChanged(sessionManager.getActiveSessions(component))
        } catch (e: SecurityException) {
            logger.d(TAG, "Sin acceso a sesiones todavía")
        }
    }

    /** App de música más usada (paquete), o null si todavía no aprendió. */
    fun favorite(isInstalled: (String) -> Boolean): String? {
        flushPlaying()
        return usage.favorite(System.currentTimeMillis(), isInstalled)
    }

    /**
     * App que está sonando o que dejó de sonar hace poco. Sirve cuando la app se pausó sola al
     * hablarle a Sara: «sacá la música» tiene que actuar sobre ella igual.
     */
    @Synchronized
    fun recentlyPlaying(windowMillis: Long = RECENT_MS): String? =
        playingSince.keys.firstOrNull()
            ?: lastStopped?.takeIf { SystemClock.elapsedRealtime() - it.second < windowMillis }?.first

    private fun onSessionsChanged(list: List<MediaController>) {
        callbacks.forEach { (controller, cb) -> controller.unregisterCallback(cb) }
        callbacks.clear()
        val present = list.map { it.packageName }.toSet()
        playingSince.keys.filter { it !in present }.forEach { stopped(it) }
        list.filter { it.packageName != context.packageName }.forEach { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = update(controller.packageName, state)
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = metadataChanged(controller.packageName, metadata)
                override fun onSessionDestroyed() = stopped(controller.packageName)
            }
            controller.registerCallback(cb, main)
            callbacks[controller] = cb
            metadataChanged(controller.packageName, controller.metadata)
            update(controller.packageName, controller.playbackState)
        }
    }

    @Synchronized
    private fun update(pkg: String, state: PlaybackState?) {
        if (state?.state == PlaybackState.STATE_PLAYING) {
            playingSince.getOrPut(pkg) { SystemClock.elapsedRealtime() }
        } else {
            stopped(pkg)
        }
    }

    @Synchronized
    private fun stopped(pkg: String) {
        val since = playingSince.remove(pkg) ?: return
        lastStopped = pkg to SystemClock.elapsedRealtime()
        record(pkg, SystemClock.elapsedRealtime() - since)
        save()
    }

    /** Suma lo que lleva sonando sin cortar la medición (por si el proceso muere). */
    @Synchronized
    private fun flushPlaying() {
        val now = SystemClock.elapsedRealtime()
        playingSince.entries.toList().forEach { (pkg, since) ->
            record(pkg, now - since)
            playingSince[pkg] = now
        }
        if (playingSince.isNotEmpty()) save()
    }

    /** Cambió la canción: lo escuchado hasta ahora va al artista anterior. */
    @Synchronized
    private fun metadataChanged(pkg: String, metadata: android.media.MediaMetadata?) {
        val artist = metadata?.let {
            it.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                ?: it.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: it.getString(android.media.MediaMetadata.METADATA_KEY_AUTHOR)
        }?.takeIf { it.isNotBlank() }
        if (artist == artistOf[pkg]) return
        playingSince[pkg]?.let { since ->
            val now = SystemClock.elapsedRealtime()
            record(pkg, now - since)
            playingSince[pkg] = now
            save()
        }
        if (artist == null) artistOf.remove(pkg) else artistOf[pkg] = artist
    }

    private fun record(pkg: String, millis: Long) {
        val now = System.currentTimeMillis()
        usage.record(pkg, millis, now)
        // Videos sueltos o podcasts en apps que no son de música no cuentan como gusto musical.
        if (pkg !in NOT_MUSIC) artistOf[pkg]?.let { taste.record(it, millis, now) }
    }

    private fun save() {
        prefs.edit().putString(KEY, usage.serialize()).putString(TASTE_KEY, taste.serialize()).apply()
        val summary = taste.summary(System.currentTimeMillis()) ?: return
        if (summary != lastTaste) {
            lastTaste = summary
            runCatching { onTaste(summary) }
        }
    }

    private companion object {
        const val TAG = "MusicUsage"
        const val KEY = "usage"
        const val TASTE_KEY = "taste"
        /** Apps donde lo que suena no suele ser música (videos, podcasts, audiolibros, navegadores). */
        val NOT_MUSIC = setOf(
            "com.google.android.youtube", "com.android.chrome", "com.google.android.apps.podcasts",
            "com.audible.application", "com.netflix.mediaclient", "com.instagram.android", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.whatsapp", "com.whatsapp.w4b",
        )
        const val RECENT_MS = 45_000L
    }
}
