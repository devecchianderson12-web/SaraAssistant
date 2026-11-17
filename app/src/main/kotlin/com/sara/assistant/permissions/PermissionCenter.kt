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
            "Para responder el clima, noticias y cualquier pregunta. La clave de Google es gratis: tocá Activar y seguí los pasos.",
        )
    }

    /** Permisos que se piden con el diálogo del sistema (un toque en «Permitir»). */
    fun runtimePermission(kind: PermissionKind): String? = when (kind) {
        PermissionKind.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        PermissionKind.CONTACTS -> Manifest.permission.READ_CONTACTS
        PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        else -> null
    }

    fun isGranted(context: Context, kind: PermissionKind): Boolean = when (kind) {
        PermissionKind.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        PermissionKind.MICROPHONE, PermissionKind.CONTACTS -> granted(context, runtimePermission(kind)!!)
        PermissionKind.NOTIFICATION_ACCESS -> NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        PermissionKind.OVERLAY -> Settings.canDrawOverlays(context)
        PermissionKind.ACCESSIBILITY -> SaraAccessibilityService.isWorking(context)
        PermissionKind.BATTERY -> context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        PermissionKind.AI_KEY -> com.sara.assistant.SaraApp.containerOrNull(context)?.ai?.isAvailable() == true
        PermissionKind.DEFAULT_ASSISTANT -> runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
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
            PermissionKind.MICROPHONE, PermissionKind.CONTACTS, PermissionKind.NOTIFICATIONS, PermissionKind.AI_KEY -> emptyList()
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
