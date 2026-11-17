package com.sara.assistant.media

import android.app.SearchManager
import android.content.ComponentName
import com.sara.assistant.apps.ActivityStarter
import com.sara.assistant.apps.AndroidAppLauncher
import com.sara.assistant.apps.StartOutcome
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.execution.MediaControl
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Servicio vacío que sólo existe para que el usuario pueda otorgar "Acceso a notificaciones":
 * es el mecanismo oficial que habilita [MediaSessionManager.getActiveSessions] para leer y
 * controlar las sesiones multimedia de otras apps. Sara no lee ni guarda notificaciones.
 */
class SaraNotificationListener : NotificationListenerService()

/**
 * Control de reproducción mediante MediaSession/MediaController.
 * Con acceso a notificaciones: elige la sesión activa, verifica las acciones admitidas y confirma
 * el cambio de estado. Sin acceso: envía teclas multimedia al reproductor más reciente sin poder
 * verificar el resultado (y lo informa).
 */
class AndroidMediaControl(
    private val context: Context,
    private val starter: ActivityStarter,
    private val apps: AndroidAppLauncher,
    private val logger: SaraLogger,
) : MediaControl {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override suspend fun perform(action: MediaAction, query: String?, app: String?): MediaActionResult {
        if ((action == MediaAction.PLAY || action == MediaAction.RESUME) && (query != null || app != null)) {
            return playSearch(query, app)
        }
        return performOnSessions(action)
    }

    /**
     * «Buscá X», «poné X en Spotify»: primero por la sesión multimedia (funciona en segundo plano);
     * si no, abriendo la búsqueda de la app pedida.
     */
    private suspend fun playSearch(query: String?, appName: String?): MediaActionResult {
        val target = appName?.let { apps.find(it) }
        if (appName != null && target == null) return MediaActionResult.Failed("No encontré la app $appName.")
        val result = withContext(Dispatchers.Main) {
            val session = activeControllers()?.firstOrNull { target == null || it.packageName == target.id }
            if (session != null && query != null && session.supports(PlaybackState.ACTION_PLAY_FROM_SEARCH)) {
                session.transportControls.playFromSearch(query, Bundle())
                return@withContext confirm(session, appName(session)) { it.isPlaying() }
            }
            if (session != null && query == null) {
                session.transportControls.play()
                return@withContext confirm(session, appName(session)) { it.isPlaying() }
            }
            null
        }
        if (result != null) return result
        val pm = context.packageManager
        val candidates = buildList {
            if (query != null) {
                add(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"))
                add(Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, query))
            }
        }.map { intent -> target?.let { intent.setPackage(it.id) } ?: intent }
        val intent = candidates.firstOrNull { it.resolveActivity(pm) != null }
            ?: target?.let { pm.getLaunchIntentForPackage(it.id) }
            ?: return MediaActionResult.NoMediaApp
        val label = target?.label ?: appName
        return when (withContext(Dispatchers.Main) { starter.start(intent, label ?: "la música") }) {
            is StartOutcome.Started -> MediaActionResult.Done(label)
            StartOutcome.NeedsTap -> MediaActionResult.NeedsTap(label)
            is StartOutcome.Failed -> MediaActionResult.Failed("No pude abrir ${label ?: "la app de música"}.")
        }
    }

    private suspend fun performOnSessions(action: MediaAction): MediaActionResult = withContext(Dispatchers.Main) {
        val controllers = activeControllers()
        if (controllers == null) {
            return@withContext performWithoutAccess(action)
        }
        val playing = controllers.firstOrNull { it.isPlaying() }
        val target = playing ?: controllers.firstOrNull()

        when (action) {
            MediaAction.PLAY, MediaAction.RESUME -> {
                if (target == null) return@withContext startFromNothing()
                val app = appName(target)
                if (target.isPlaying()) return@withContext MediaActionResult.Done(app)
                if (!target.supports(PlaybackState.ACTION_PLAY) && !target.supports(PlaybackState.ACTION_PLAY_PAUSE)) {
                    return@withContext MediaActionResult.Unsupported(app)
                }
                target.transportControls.play()
                confirm(target, app) { it.isPlaying() }
            }
            MediaAction.PAUSE -> {
                val session = playing ?: return@withContext MediaActionResult.NothingPlaying
                val app = appName(session)
                if (!session.supports(PlaybackState.ACTION_PAUSE) && !session.supports(PlaybackState.ACTION_PLAY_PAUSE)) {
                    return@withContext MediaActionResult.Unsupported(app)
                }
                session.transportControls.pause()
                confirm(session, app) { !it.isPlaying() }
            }
            MediaAction.STOP -> {
                val session = playing ?: return@withContext MediaActionResult.NothingPlaying
                val app = appName(session)
                when {
                    session.supports(PlaybackState.ACTION_STOP) -> {
                        session.transportControls.stop()
                        confirm(session, app) { !it.isPlaying() }
                    }
                    session.supports(PlaybackState.ACTION_PAUSE) -> {
                        session.transportControls.pause()
                        if (confirm(session, app) { !it.isPlaying() } is MediaActionResult.Done) MediaActionResult.PausedInstead(app)
                        else MediaActionResult.NotConfirmed(app)
                    }
                    else -> MediaActionResult.Unsupported(app)
                }
            }
            MediaAction.NEXT, MediaAction.PREVIOUS -> {
                val session = target ?: return@withContext MediaActionResult.NothingPlaying
                val app = appName(session)
                val flag = if (action == MediaAction.NEXT) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
                if (!session.supports(flag)) return@withContext MediaActionResult.Unsupported(app)
                val before = session.metadata?.description?.mediaId ?: session.metadata?.description?.title
                if (action == MediaAction.NEXT) session.transportControls.skipToNext() else session.transportControls.skipToPrevious()
                confirm(session, app) {
                    val now = it.metadata?.description?.mediaId ?: it.metadata?.description?.title
                    now != before || it.playbackState?.state in SKIPPING_STATES
                }
            }
        }
    }

    private fun activeControllers(): List<MediaController>? {
        if (!hasNotificationAccess()) return null
        return try {
            sessionManager.getActiveSessions(ComponentName(context, SaraNotificationListener::class.java))
        } catch (e: SecurityException) {
            logger.w(TAG, "Sin acceso a sesiones multimedia", e)
            null
        }
    }

    /** No hay sesiones: se intenta reanudar el último reproductor y, si Sara está visible, abrir una app de música. */
    private suspend fun startFromNothing(): MediaActionResult {
        dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        repeat(10) {
            delay(150)
            activeControllers()?.firstOrNull { it.isPlaying() }?.let { return MediaActionResult.Done(appName(it)) }
        }
        if (openMusicApp()) return MediaActionResult.Done(null)
        return MediaActionResult.NoMediaApp
    }

    private fun performWithoutAccess(action: MediaAction): MediaActionResult {
                val key = when (action) {
            MediaAction.PLAY, MediaAction.RESUME -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        if (action != MediaAction.PLAY && action != MediaAction.RESUME && !audioManager.isMusicActive) {
            return MediaActionResult.NothingPlaying
        }
        dispatchKey(key)
        return MediaActionResult.SentWithoutVerification
    }

    private fun dispatchKey(code: Int) {
        val time = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    /** Abre la app de música predeterminada si Android lo permite en este momento. */
    private fun openMusicApp(): Boolean {
        if (!starter.canStartDirectly()) return false
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        return starter.start(intent, "la música") is StartOutcome.Started
    }

    private suspend fun confirm(controller: MediaController, app: String?, condition: (MediaController) -> Boolean): MediaActionResult {
        repeat(12) {
            delay(125)
            if (condition(controller)) return MediaActionResult.Done(app)
        }
        return MediaActionResult.NotConfirmed(app)
    }

    private fun MediaController.isPlaying() = playbackState?.state in PLAYING_STATES

    /** Si la app no informa acciones (estado nulo) se intenta igual. */
    private fun MediaController.supports(flag: Long): Boolean {
        val state = playbackState ?: return true
        return state.actions and flag != 0L
    }

    private fun appName(controller: MediaController): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(controller.packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    private companion object {
        const val TAG = "Media"
        val PLAYING_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
        val SKIPPING_STATES = setOf(PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_BUFFERING)
    }
}
