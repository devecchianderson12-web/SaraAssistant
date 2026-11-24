package com.sara.assistant.permissions

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sara.assistant.R
import com.sara.assistant.apps.SaraAccessibilityService
import com.sara.assistant.media.SaraNotificationListener
import com.sara.assistant.services.SaraNotifications
import com.sara.assistant.ui.MainActivity
import com.sara.core.model.PermissionKind

/**
 * Un solo lugar para cada permiso que usa Sara: para qué sirve, si está concedido y el atajo más
 * directo para activarlo (el diálogo del sistema cuando existe; si no, la pantalla exacta de Ajustes).
 */
object PermissionCenter {
    const val EXTRA_GRANT = "com.sara.assistant.GRANT_PERMISSION"

    data class Info(val title: String, val why: String)

    /** Los que Sara necesita para lo básico: se muestran siempre en Inicio. El resto, en Ajustes › Permisos. */
    val ESSENTIAL = setOf(PermissionKind.MICROPHONE, PermissionKind.NOTIFICATIONS, PermissionKind.NOTIFICATION_ACCESS, PermissionKind.BATTERY)

    fun info(kind: PermissionKind): Info = when (kind) {
        PermissionKind.MICROPHONE -> Info("Micrófono", "Para escucharte. El audio nunca se guarda.")
        PermissionKind.NOTIFICATIONS -> Info("Notificaciones", "Para avisarte y dejarte botones cuando hace falta algo.")
        PermissionKind.NOTIFICATION_ACCESS -> Info(
            "Control de música",
            "Para pausar, pasar y sacar la música de cualquier app. Si aparece gris: Info de la app › ⋮ › Permitir ajustes restringidos.",
        )
        PermissionKind.OVERLAY -> Info("Abrir apps", "Para abrir apps y WhatsApp aunque estés en otra app o con la pantalla apagada.")
        PermissionKind.ACCESSIBILITY -> Info(
            "Accesibilidad",
            "Para cerrar apps y mandar WhatsApp sola. Si ya está activa y no anda: apagala y prendela. Si aparece gris: Info de la app › ⋮ › Permitir ajustes restringidos. En Xiaomi activá también «Inicio automático».",
        )
        PermissionKind.CONTACTS -> Info("Contactos", "Para mandar WhatsApp a tus contactos por nombre.")
        PermissionKind.BATTERY -> Info("Batería sin restricciones", "Para que Sara siga escuchando con la pantalla bloqueada.")
        PermissionKind.DEFAULT_ASSISTANT -> Info("Asistente predeterminado", "Para que Sara arranque sola al reiniciar el teléfono.")
        PermissionKind.AI_KEY -> Info(
            "Preguntas con Google",
            "Para responder noticias, búsquedas y pedidos complejos. La clave de Google es gratis: tocá Activar y seguí los pasos.",
        )
        PermissionKind.LOCATION -> Info("Ubicación", "Para el clima de donde estás, «dónde estoy» y lugares cercanos. Sólo cuando lo pedís.")
        PermissionKind.PHONE -> Info("Llamadas", "Para llamar directamente cuando lo pedís (sin este permiso, Sara deja el número marcado).")
        PermissionKind.CALENDAR -> Info("Calendario", "Para decirte tus eventos de hoy y mañana.")
        PermissionKind.WRITE_SETTINGS -> Info("Modificar ajustes", "Para cambiar el brillo de la pantalla.")
        PermissionKind.DND_ACCESS -> Info("No molestar", "Para activar y desactivar el modo No molestar.")
        PermissionKind.BLUETOOTH -> Info("Dispositivos cercanos", "Para saber si se conectó tu parlante o auricular Bluetooth.")
    }

    /** Permisos que se piden con el diálogo del sistema (un toque en «Permitir»). */
    fun runtimePermission(kind: PermissionKind): String? = when (kind) {
        PermissionKind.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        PermissionKind.CONTACTS -> Manifest.permission.READ_CONTACTS
        PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        PermissionKind.LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
        PermissionKind.PHONE -> Manifest.permission.CALL_PHONE
        PermissionKind.CALENDAR -> Manifest.permission.READ_CALENDAR
        PermissionKind.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null
        else -> null
    }

    fun isGranted(context: Context, kind: PermissionKind): Boolean = when (kind) {
        PermissionKind.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        PermissionKind.MICROPHONE, PermissionKind.CONTACTS, PermissionKind.PHONE, PermissionKind.CALENDAR -> granted(context, runtimePermission(kind)!!)
        PermissionKind.LOCATION -> granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionKind.WRITE_SETTINGS -> Settings.System.canWrite(context)
        PermissionKind.BLUETOOTH -> Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        PermissionKind.DND_ACCESS -> context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
        PermissionKind.NOTIFICATION_ACCESS -> NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        PermissionKind.OVERLAY -> Settings.canDrawOverlays(context)
        // Activada en Ajustes alcanza: si el sistema la frena (Xiaomi), se reconecta sola al usarla.
        PermissionKind.ACCESSIBILITY -> SaraAccessibilityService.isEnabled(context)
        PermissionKind.BATTERY -> context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        PermissionKind.AI_KEY -> com.sara.assistant.SaraApp.containerOrNull(context)?.ai?.isAvailable() == true
        // Antes de Android 10 no hay «rol» de asistente que pedir: se da por resuelto.
        PermissionKind.DEFAULT_ASSISTANT -> Build.VERSION.SDK_INT < 29 || runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
    }

    /**
     * Android 13+ bloquea «Acceso a notificaciones» y «Accesibilidad» en apps instaladas desde un
     * archivo (no desde Play Store): el interruptor aparece gris o no queda prendido. Se habilita en
     * Info de la app › ⋮ › «Permitir ajustes restringidos».
     */
    fun restrictedSettingsBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), context.packageName)
        }.getOrNull() ?: return false
        return mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED
    }

    /** Estos dos permisos son los que Android bloquea en apps instaladas desde un archivo. */
    fun isRestricted(kind: PermissionKind) = kind == PermissionKind.NOTIFICATION_ACCESS || kind == PermissionKind.ACCESSIBILITY

    /** Pide al sistema que vuelva a conectar el lector de notificaciones (a veces queda activado pero desconectado). */
    fun rebindNotificationListener(context: Context) {
        if (!isGranted(context, PermissionKind.NOTIFICATION_ACCESS)) return
        runCatching { android.service.notification.NotificationListenerService.requestRebind(ComponentName(context, SaraNotificationListener::class.java)) }
    }

    fun missing(context: Context): List<PermissionKind> = PermissionKind.entries.filterNot { isGranted(context, it) }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Pantallas de Ajustes, de la más directa a la más general. */
    fun settingsIntents(context: Context, kind: PermissionKind): List<Intent> {
        // La clave de la IA se carga en Sara (no en Ajustes del sistema).
        if (kind == PermissionKind.AI_KEY) return emptyList()
        val pkg = Uri.parse("package:" + context.packageName)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        // Bloqueado por Android: primero Info de la app (⋮ › Permitir ajustes restringidos).
        if (isRestricted(kind) && restrictedSettingsBlocked(context)) return listOf(appDetails)
        return when (kind) {
            PermissionKind.NOTIFICATION_ACCESS -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, SaraNotificationListener::class.java).flattenToString(),
                    )
                } else null,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            PermissionKind.OVERLAY -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            PermissionKind.ACCESSIBILITY -> listOf(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(
                    Intent.EXTRA_COMPONENT_NAME,
                    ComponentName(context, SaraAccessibilityService::class.java).flattenToString(),
                ),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
            // Diálogo directo «¿Permitir que Sara se ejecute en segundo plano?».
            PermissionKind.BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            PermissionKind.DEFAULT_ASSISTANT -> listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            )
            PermissionKind.WRITE_SETTINGS -> listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg))
            PermissionKind.DND_ACCESS -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            PermissionKind.MICROPHONE, PermissionKind.CONTACTS, PermissionKind.NOTIFICATIONS, PermissionKind.AI_KEY,
            PermissionKind.LOCATION, PermissionKind.PHONE, PermissionKind.CALENDAR, PermissionKind.BLUETOOTH -> emptyList()
        } + appDetails
    }

    /**
     * Notificación con un botón que lleva directo a activar [kind]. Se usa cuando Sara está en
     * segundo plano y una orden no se pudo cumplir por falta de ese permiso.
     */
    fun notify(context: Context, kind: PermissionKind) {
        if (isGranted(context, kind)) return
        val pm = context.packageManager
        val target = if (runtimePermission(kind) != null) null
        else settingsIntents(context, kind).firstOrNull { it.resolveActivity(pm) != null }
        val intent = (target ?: Intent(context, MainActivity::class.java).putExtra(EXTRA_GRANT, kind.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, 100 + kind.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val info = info(kind)
        val notification = NotificationCompat.Builder(context, SaraNotifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_sara)
            .setContentTitle("Activá «${info.title}»")
            .setContentText(info.why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.why))
            .setContentIntent(pending)
            .addAction(0, "Activar", pending)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(200 + kind.ordinal, notification) }
    }
}
