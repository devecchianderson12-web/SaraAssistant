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
    /** Canal mínimo: la notificación obligatoria del servicio queda plegada y sin ícono arriba. */
    const val CHANNEL_LISTENING = "sara_listening_min"
    const val CHANNEL_ALERTS = "sara_alerts"
    const val ID_LISTENING = 1
    const val ID_RESUME = 2
    const val ID_UPDATED = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, "Escucha de Sara", NotificationManager.IMPORTANCE_MIN).apply {
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

    /**
     * Escucha inteligente: el servicio queda vivo (sin micrófono) y abre el micrófono sólo cuando
     * corresponde: conectado al auto o a un Bluetooth, cargando, con la pantalla encendida o en el
     * horario elegido. Se vuelve a evaluar ante cada cambio y una vez por minuto (horario).
     */
    private val conditionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = evaluate()
    }
    private var receiverRegistered = false
    private var watching = false
    @Volatile private var listeningNow: Boolean? = null

    private val audioDevices = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = evaluate()
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = evaluate()
    }

    private fun screenOn() = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false

    private fun charging(): Boolean =
        getSystemService(android.os.BatteryManager::class.java)?.isCharging == true

    private fun conditions() = com.sara.core.assistant.ListenConditions(
        bluetoothAudio = container.micRouter.bluetoothAudioConnected(),
        charging = charging(),
        screenOn = screenOn(),
        hour = java.time.LocalTime.now().hour,
    )

    /** Abre o cierra el micrófono de la espera según el modo y lo que pasa ahora. */
    private fun evaluate() {
        val c = container
        val s = c.settings.settings.value
        if (!s.wakeWordEnabled) return
        val listen = com.sara.core.assistant.ListenPolicy.shouldListen(s.listenMode, s.smartListening, conditions())
        if (listen == listeningNow) return
        // Si está en plena charla u orden, no se corta: se vuelve a evaluar al terminar.
        if (!listen && c.orchestrator.isInteractionActive) return
        listeningNow = listen
        c.logger.d(TAG, com.sara.core.assistant.ListenPolicy.describe(s.listenMode, s.smartListening, conditions()))
        c.orchestrator.setWakeWordEnabled(listen)
    }

    private fun startWatching() {
        if (watching) return
        watching = true
        registerReceiver(conditionsReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        receiverRegistered = true
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(audioDevices, null)
        // Cambios de ajustes, fin de cada interacción y el horario (cada minuto).
        lifecycleScope.launch { container.settings.settings.collect { listeningNow = null; evaluate() } }
        lifecycleScope.launch { container.orchestrator.state.collect { if (!container.orchestrator.isInteractionActive) evaluate() } }
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                evaluate()
            }
        }
    }

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
                // intent == null: el sistema relanzó el servicio tras cerrarlo (START_STICKY). En el modo
                // «sólo con la app abierta» no vuelve solo.
                if (intent == null && (!c.settings.settings.value.wakeWordEnabled || c.settings.settings.value.listenMode == com.sara.core.assistant.ListenMode.APP_ONLY)) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                if (!enterForeground()) return START_NOT_STICKY
                getSystemService(NotificationManager::class.java).cancel(SaraNotifications.ID_RESUME)
                listeningNow = null
                startWatching()
                evaluate()
            }
        }
        // Si el sistema o el fabricante mata el proceso, Android vuelve a crear el servicio.
        return START_STICKY
    }

    /**
     * Cerrar Sara desde «Recientes»: en el modo «sólo con la app abierta» se apaga del todo; en los
     * otros modos sólo se cierra la ventana y la escucha sigue en este servicio.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val c = container
        if (c.settings.settings.value.listenMode == com.sara.core.assistant.ListenMode.APP_ONLY) {
            c.logger.d(TAG, "App quitada de recientes: se apaga la escucha (modo sólo con la app abierta)")
            c.orchestrator.setWakeWordEnabled(false)
            stopSelfSafely()
        } else {
            c.logger.d(TAG, "App quitada de recientes; la escucha sigue activa")
        }
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
        AssistantState.Inactive -> com.sara.core.assistant.ListenPolicy.describe(
            container.settings.settings.value.listenMode, container.settings.settings.value.smartListening, conditions(),
        ) + " · tocá «Hablar» o usá el botón de asistente"
        AssistantState.WaitingForWakeWord -> "Esperando tu llamado · el audio se procesa en el teléfono"
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
        if (receiverRegistered) runCatching { unregisterReceiver(conditionsReceiver) }
        runCatching { getSystemService(android.media.AudioManager::class.java).unregisterAudioDeviceCallback(audioDevices) }
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
                if (settings.wakeWordEnabled && settings.autostart && settings.listenMode != com.sara.core.assistant.ListenMode.APP_ONLY) {
                    SaraListeningService.start(context, StartContext.BOOT_COMPLETED)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
