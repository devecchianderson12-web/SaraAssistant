package com.lucyremota.emisor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import com.lucyremota.emisor.ui.MainActivity
import com.lucyremota.protocolo.LinkState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lucy Remota activa: mantiene la conexión con la PC y la escucha de «Lucy» aunque la pantalla esté
 * apagada. Se ve una notificación fija con el estado; «Salir» la apaga.
 */
class LucyRemotaService : Service() {
    private var multicast: WifiManager.MulticastLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lucy Remota activa", NotificationManager.IMPORTANCE_LOW))
        val c = container
        // Sin permiso de micrófono Android no deja este servicio: se cierra solo en vez de cerrar la app.
        val started = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            runCatching { startForeground(ID, notification("Buscando la computadora…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
                .onFailure { c.logger.w("Servicio", "No pude quedar en primer plano", it) }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        // Sin esto muchos teléfonos descartan los anuncios de la PC (broadcast) con la pantalla apagada.
        multicast = getSystemService(WifiManager::class.java)?.createMulticastLock("lucyremota")?.apply { setReferenceCounted(false); acquire() }
        c.startLink()
        c.ensureModels()
        c.refreshWake(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        watcher = c.scope.launch {
            c.link.state.collect { st ->
                nm.notify(ID, notification(when (st) {
                    is LinkState.Connected -> "Conectada con ${st.pcName}. Decí «Lucy»."
                    is LinkState.Searching -> "Buscando ${st.pcName} en la red…"
                    is LinkState.Pairing -> "Esperando a la computadora para vincular…"
                    is LinkState.PairingFailed -> st.reason
                    LinkState.Unpaired -> "Vinculá una computadora."
                }))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.orchestrator.setWakeWordEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        container.refreshWake(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        return START_STICKY
    }

    override fun onDestroy() {
        if (watcher == null) { super.onDestroy(); return }
        watcher?.cancel()
        runCatching { multicast?.release() }
        container.orchestrator.setWakeWordEnabled(false)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LucyRemotaService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Lucy Remota")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Salir", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "activa"
        private const val ID = 7
        const val ACTION_STOP = "com.lucyremota.emisor.SALIR"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, LucyRemotaService::class.java)) }
        }
    }
}
