package com.sara.assistant.apps

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.sara.assistant.R
import com.sara.core.apps.AppMatcher
import com.sara.core.apps.InstalledApp
import com.sara.core.execution.AppLaunchResult
import com.sara.core.execution.AppLauncher
import com.sara.core.execution.CloseAppsResult
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface StartOutcome {
    data class Started(val deviceLocked: Boolean) : StartOutcome
    data object NeedsTap : StartOutcome
    data class Failed(val message: String) : StartOutcome
}

/**
 * Abre pantallas de otras apps respetando las reglas de Android: desde segundo plano sólo se
 * permite si Sara está visible o tiene «Mostrar sobre otras apps». Si no, deja una notificación
 * que el usuario toca para abrir (sin saltear la pantalla de bloqueo).
 */
class ActivityStarter(private val context: Context, private val isAppInForeground: () -> Boolean) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Abrir apps", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cuando Android no permite abrir una app directamente."
            },
        )
    }

    fun canStartDirectly(): Boolean = isAppInForeground() || Settings.canDrawOverlays(context)

    fun start(intent: Intent, label: String): StartOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!canStartDirectly()) {
            postTapToOpen(intent, label)
            return StartOutcome.NeedsTap
        }
        return try {
            context.startActivity(intent)
            StartOutcome.Started(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
        } catch (e: Exception) {
            StartOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun postTapToOpen(intent: Intent, label: String) {
        val pending = PendingIntent.getActivity(
            context, label.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sara)
            .setContentTitle("Tocá para abrir $label")
            .setContentText("Pedido a Sara")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL = "sara_open_apps"
        const val NOTIFICATION_ID = 4
    }
}

/** Lista las apps con ícono en el lanzador y abre la que mejor coincide con el nombre dicho. */
class AndroidAppLauncher(
    private val context: Context,
    private val starter: ActivityStarter,
    private val logger: SaraLogger,
) : AppLauncher {
    private var cache: List<InstalledApp> = emptyList()
    private var cachedAt = 0L

    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < CACHE_MS) return@withContext cache
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cache = pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.id }
        cachedAt = now
        cache
    }

    suspend fun find(name: String): InstalledApp? = AppMatcher.best(name, installedApps())

    override suspend fun open(name: String): AppLaunchResult {
        val app = find(name) ?: return AppLaunchResult.NotFound(name)
        val intent = context.packageManager.getLaunchIntentForPackage(app.id)
            ?: return AppLaunchResult.Failed("${app.label} no se puede abrir.")
        logger.d(TAG, "«$name» -> ${app.label}")
        return when (val outcome = withContext(Dispatchers.Main) { starter.start(intent, app.label) }) {
            is StartOutcome.Started -> AppLaunchResult.Opened(app.label, outcome.deviceLocked)
            StartOutcome.NeedsTap -> AppLaunchResult.NeedsTap(app.label)
            is StartOutcome.Failed -> AppLaunchResult.Failed(outcome.message)
        }
    }

    override suspend fun closeBackgroundApps(): CloseAppsResult = SaraAccessibilityService.closeRecentApps(context)

    private companion object {
        const val TAG = "Apps"
        const val CACHE_MS = 60_000L
    }
}
