package com.sara.assistant.services

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sara.assistant.R
import com.sara.assistant.container
import com.sara.assistant.ui.MainActivity
import com.sara.core.model.AssistantState
import com.sara.core.model.Trigger
import com.sara.core.permissions.ServiceStartPolicy
import com.sara.core.permissions.StartContext
import com.sara.core.permissions.StartDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object SaraNotifications {
    const val CHANNEL_LISTENING = "sara_listening"
    const val CHANNEL_ALERTS = "sara_alerts"
    const val ID_LISTENING = 1
    const val ID_RESUME = 2
    const val ID_UPDATED = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, "Escucha de Sara", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Indica que Sara está esperando su nombre."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Avisos de Sara", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Pedidos para reactivar la escucha."
            },
        )
    }

    /** Aviso tras instalar una versión nueva (Android no deja reabrir la app sola desde segundo plano). */
    fun postUpdated(context: Context) {
        val open = PendingIntent.getActivity(
            context, 11, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_sara)
            .setContentTitle("SaraAssistant se actualizó")
            .setContentText("Versión ${com.sara.assistant.BuildConfig.VERSION_NAME} instalada. Tocá para abrir.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_UPDATED, notification) }
    }

    /** Notificación que permite reactivar la escucha con un toque (excepción oficial de Android). */
    fun postResumeRequest(context: Context, reason: String) {
        val pending = PendingIntent.getForegroundService(
            context, 10,
            Intent(context, SaraListeningService::class.java).setAction(SaraListeningService.ACTION_START_FROM_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_sara)
            .setContentTitle("Tocá para activar a Sara")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_RESUME, notification) }
    }
}

/**
 * Servicio en primer plano de tipo "microphone" que mantiene la detección de "Sara" con la
 * pantalla apagada, bloqueada o con otra app abierta. Muestra una notificación persistente
 * mientras el micrófono puede estar en uso y permite detenerlo desde ahí.
 */
class SaraListeningService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val c = container
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch { c.settings.update { it.copy(wakeWordEnabled = false) } }
                c.orchestrator.setWakeWordEnabled(false)
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_ACTIVATE -> {
                if (!enterForeground()) return START_NOT_STICKY
                c.orchestrator.activate(Trigger.NOTIFICATION)
            }
            else -> {
                // intent == null: el sistema relanzó el servicio tras cerrarlo (START_STICKY).
                if (intent == null && !c.settings.settings.value.wakeWordEnabled) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                if (!enterForeground()) return START_NOT_STICKY
                getSystemService(NotificationManager::class.java).cancel(SaraNotifications.ID_RESUME)
                c.orchestrator.setWakeWordEnabled(true)
            }
        }
        // Si el sistema o el fabricante mata el proceso, Android vuelve a crear el servicio.
        return START_STICKY
    }

    /** Cerrar Sara desde «Recientes» sólo cierra la ventana: la escucha sigue en este servicio. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        container.logger.d(TAG, "App quitada de recientes; la escucha sigue activa")
    }

    private var observing = false

    private fun enterForeground(): Boolean {
        val notification = buildNotification(container.orchestrator.state.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(this, SaraNotifications.ID_LISTENING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(SaraNotifications.ID_LISTENING, notification)
            }
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException; Android 14+: SecurityException
            // si el permiso de micrófono no está vigente en este contexto.
            val notAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException
            container.logger.w(TAG, "No se pudo iniciar en primer plano (${if (notAllowed) "segundo plano" else "permiso"})", e)
            SaraNotifications.postResumeRequest(this, "Android no permitió activar el micrófono en segundo plano.")
            _running.value = false
            stopSelf()
            return false
        }
        _running.value = true
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                container.orchestrator.state
                    .map { statusText(it) }
                    .distinctUntilChanged()
                    .collect {
                        runCatching {
                            getSystemService(NotificationManager::class.java)
                                .notify(SaraNotifications.ID_LISTENING, buildNotification(container.orchestrator.state.value))
                        }
                    }
            }
        }
        return true
    }

    private fun buildNotification(state: AssistantState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SaraListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val talk = PendingIntent.getService(
            this, 2, Intent(this, SaraListeningService::class.java).setAction(ACTION_ACTIVATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SaraNotifications.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_sara)
            .setContentTitle("SaraAssistant")
            .setContentText(statusText(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Hablar", talk)
            .addAction(0, "Detener", stop)
            .build()
    }

    private fun statusText(state: AssistantState) = when (state) {
        AssistantState.Inactive -> "Detección en pausa"
        AssistantState.WaitingForWakeWord -> "Esperando «Sara» · el audio se procesa en el teléfono"
        is AssistantState.Listening -> "Escuchando tu orden…"
        is AssistantState.Processing -> "Procesando…"
        is AssistantState.Executing -> "Ejecutando…"
        is AssistantState.Speaking -> "Hablando…"
        is AssistantState.Error -> "Atención: ${state.error.message}"
    }

    private fun stopSelfSafely() {
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        container.orchestrator.setWakeWordEnabled(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ListeningService"
        const val ACTION_START = "com.sara.assistant.START"
        const val ACTION_START_FROM_NOTIFICATION = "com.sara.assistant.START_FROM_NOTIFICATION"
        const val ACTION_STOP = "com.sara.assistant.STOP"
        const val ACTION_ACTIVATE = "com.sara.assistant.ACTIVATE"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Inicia la detección respetando las reglas de cada versión de Android. */
        fun start(context: Context, from: StartContext): StartDecision {
            val decision = ServiceStartPolicy.decide(Build.VERSION.SDK_INT, from, context.container.microphone.hasPermission())
            when (decision) {
                StartDecision.StartNow -> runCatching {
                    context.startForegroundService(Intent(context, SaraListeningService::class.java).setAction(ACTION_START))
                }.onFailure {
                    context.container.logger.w(TAG, "startForegroundService rechazado", it)
                    SaraNotifications.postResumeRequest(context, "Android no permitió activar la escucha automáticamente.")
                }
                is StartDecision.AskUserViaNotification -> SaraNotifications.postResumeRequest(context, decision.reason)
                is StartDecision.NotAllowed -> context.container.logger.w(TAG, decision.reason)
            }
            return decision
        }

        fun stop(context: Context) {
            context.container.orchestrator.setWakeWordEnabled(false)
            context.stopService(Intent(context, SaraListeningService::class.java))
        }
    }
}

/** Reactiva la escucha tras reiniciar o actualizar la app, si el usuario lo pidió. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) SaraNotifications.postUpdated(context)
                val settings = c.settings.current()
                if (settings.wakeWordEnabled && settings.autostart) {
                    SaraListeningService.start(context, StartContext.BOOT_COMPLETED)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
