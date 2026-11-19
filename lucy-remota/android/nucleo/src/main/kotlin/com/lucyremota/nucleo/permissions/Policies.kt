package com.lucyremota.nucleo.permissions

/** Instantánea de permisos y accesos relevantes para Sara. */
data class PermissionSnapshot(
    val sdkInt: Int,
    val recordAudio: Boolean,
    val postNotifications: Boolean,
    val notificationListener: Boolean,
)

enum class SaraPermission { RECORD_AUDIO, POST_NOTIFICATIONS, NOTIFICATION_LISTENER }

enum class Feature {
    /** Escuchar al tocar el botón con la app visible. */
    MANUAL_LISTENING,

    /** Detección de "Sara" en segundo plano (servicio en primer plano con notificación). */
    BACKGROUND_WAKE_WORD,

    /** Controlar sesiones multimedia de otras apps verificando su estado. */
    MEDIA_SESSION_CONTROL,
}

object PermissionPlanner {
    /** Permisos en tiempo de ejecución que se piden con el diálogo del sistema para [feature]. */
    fun runtimePermissionsFor(feature: Feature, sdkInt: Int): List<SaraPermission> = when (feature) {
        Feature.MANUAL_LISTENING -> listOf(SaraPermission.RECORD_AUDIO)
        Feature.BACKGROUND_WAKE_WORD -> buildList {
            add(SaraPermission.RECORD_AUDIO)
            if (sdkInt >= 33) add(SaraPermission.POST_NOTIFICATIONS)
        }
        Feature.MEDIA_SESSION_CONTROL -> emptyList()
    }

    fun missingFor(feature: Feature, snapshot: PermissionSnapshot): List<SaraPermission> {
        val required = runtimePermissionsFor(feature, snapshot.sdkInt) +
            if (feature == Feature.MEDIA_SESSION_CONTROL) listOf(SaraPermission.NOTIFICATION_LISTENER) else emptyList()
        return required.filter { !isGranted(it, snapshot) }
    }

    fun isGranted(permission: SaraPermission, snapshot: PermissionSnapshot): Boolean = when (permission) {
        SaraPermission.RECORD_AUDIO -> snapshot.recordAudio
        SaraPermission.POST_NOTIFICATIONS -> snapshot.sdkInt < 33 || snapshot.postNotifications
        SaraPermission.NOTIFICATION_LISTENER -> snapshot.notificationListener
    }
}

/** Desde dónde se intenta iniciar el servicio de escucha. */
enum class StartContext { VISIBLE_ACTIVITY, NOTIFICATION_ACTION, VOICE_INTERACTION_SERVICE, BOOT_COMPLETED, BACKGROUND }

sealed interface StartDecision {
    data object StartNow : StartDecision

    /** El sistema no permitiría usar el micrófono: se muestra una notificación para que el usuario lo active. */
    data class AskUserViaNotification(val reason: String) : StartDecision
    data class NotAllowed(val reason: String) : StartDecision
}

/**
 * Reglas oficiales para iniciar un servicio en primer plano de tipo micrófono:
 * - Android 11+ (API 30): un servicio iniciado desde segundo plano no recibe audio del micrófono,
 *   salvo excepciones (notificación, VoiceInteractionService activo, actividad visible...).
 * - Android 12+ (API 31): además no se pueden iniciar servicios en primer plano desde segundo plano.
 * - Android 14/15: el arranque desde BOOT_COMPLETED no puede iniciar servicios de micrófono.
 */
object ServiceStartPolicy {
    fun decide(sdkInt: Int, context: StartContext, hasRecordAudio: Boolean): StartDecision {
        if (!hasRecordAudio) return StartDecision.NotAllowed("Falta el permiso de micrófono.")
        return when (context) {
            StartContext.VISIBLE_ACTIVITY,
            StartContext.NOTIFICATION_ACTION,
            StartContext.VOICE_INTERACTION_SERVICE -> StartDecision.StartNow
            StartContext.BOOT_COMPLETED,
            StartContext.BACKGROUND ->
                if (sdkInt < 30) {
                    StartDecision.StartNow
                } else {
                    StartDecision.AskUserViaNotification(
                        "Android no permite abrir el micrófono desde segundo plano. Tocá la notificación para activar a Sara.",
                    )
                }
        }
    }
}
