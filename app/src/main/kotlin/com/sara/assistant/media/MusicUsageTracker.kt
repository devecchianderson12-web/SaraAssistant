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
 * para saber cuál usa más el usuario. Sólo guarda el nombre del paquete y los minutos; no guarda
 * qué se escuchó. Necesita el «Acceso a notificaciones» que Sara ya pide para controlar la música.
 */
class MusicUsageTracker(
    private val context: Context,
    private val logger: SaraLogger,
) {
    private val prefs = context.getSharedPreferences("music_usage", Context.MODE_PRIVATE)
    private val usage = MusicUsage().apply { load(prefs.getString(KEY, null)) }
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Paquete -> momento (elapsedRealtime) en que empezó a sonar. */
    private val playingSince = mutableMapOf<String, Long>()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var started = false

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

    private fun onSessionsChanged(list: List<MediaController>) {
        callbacks.forEach { (controller, cb) -> controller.unregisterCallback(cb) }
        callbacks.clear()
        val present = list.map { it.packageName }.toSet()
        playingSince.keys.filter { it !in present }.forEach { stopped(it) }
        list.filter { it.packageName != context.packageName }.forEach { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = update(controller.packageName, state)
                override fun onSessionDestroyed() = stopped(controller.packageName)
            }
            controller.registerCallback(cb, main)
            callbacks[controller] = cb
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

    private fun record(pkg: String, millis: Long) = usage.record(pkg, millis, System.currentTimeMillis())

    private fun save() {
        prefs.edit().putString(KEY, usage.serialize()).apply()
    }

    private companion object {
        const val TAG = "MusicUsage"
        const val KEY = "usage"
    }
}
