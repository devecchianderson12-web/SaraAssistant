package com.sara.assistant.skills

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sara.assistant.R
import com.sara.assistant.SaraApp
import com.sara.assistant.ui.MainActivity
import com.sara.core.skills.TextStore
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Guarda texto en SharedPreferences (notas, listas, recordatorios, rutinas). */
class PrefsTextStore(context: Context, name: String) : TextStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        if (value == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, value).apply()
    }
}

data class Reminder(val id: Int, val text: String, val at: Long)

/** Rutina programada: a una hora, ciertos días, ejecuta una orden de Sara («decime el clima»). */
data class ScheduledRoutine(val id: Int, val hour: Int, val minute: Int, val days: String, val order: String, val enabled: Boolean = true) {
    fun runsOn(day: DayOfWeek) = when (days) {
        "lun-vie" -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
        "sab-dom" -> day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        else -> true
    }

    fun describe(): String {
        val when_ = when (days) { "lun-vie" -> "de lunes a viernes"; "sab-dom" -> "los fines de semana"; else -> "todos los días" }
        return "$when_ a las $hour:${minute.toString().padStart(2, '0')}: $order" + if (enabled) "" else " (pausada)"
    }
}

/**
 * Recordatorios y rutinas con AlarmManager (funciona con el teléfono bloqueado y sin que Sara esté
 * abierta). Si Android no permite alarmas exactas, se usan alarmas que pueden demorarse unos minutos.
 */
class Scheduler(private val context: Context) {
    private val store = PrefsTextStore(context, "sara_schedule")
    private val alarms = context.getSystemService(AlarmManager::class.java)

    @Synchronized fun reminders(): List<Reminder> = JSONArray(store.get("reminders") ?: "[]").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Reminder(it.getInt("id"), it.getString("text"), it.getLong("at")) }
    }

    @Synchronized fun routines(): List<ScheduledRoutine> = JSONArray(store.get("routines") ?: "[]").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            ScheduledRoutine(it.getInt("id"), it.getInt("hour"), it.getInt("minute"), it.getString("days"), it.getString("order"), it.optBoolean("enabled", true))
        }
    }

    private fun saveReminders(list: List<Reminder>) = store.put("reminders", JSONArray(list.map {
        JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)
    }).toString())

    private fun saveRoutines(list: List<ScheduledRoutine>) = store.put("routines", JSONArray(list.map {
        JSONObject().put("id", it.id).put("hour", it.hour).put("minute", it.minute).put("days", it.days).put("order", it.order).put("enabled", it.enabled)
    }).toString())

    private fun nextId(): Int = ((reminders().map { it.id } + routines().map { it.id }).maxOrNull() ?: 0) + 1

    @Synchronized fun addReminder(text: String, at: LocalDateTime): Reminder {
        val reminder = Reminder(nextId(), text, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        saveReminders(reminders() + reminder)
        schedule(reminder.id, reminder.at, ReminderReceiver.ACTION_REMINDER)
        return reminder
    }

    @Synchronized fun removeReminder(id: Int) {
        saveReminders(reminders().filterNot { it.id == id })
        cancel(id, ReminderReceiver.ACTION_REMINDER)
    }

    @Synchronized fun addRoutine(routine: ScheduledRoutine): ScheduledRoutine {
        val saved = routine.copy(id = nextId())
        saveRoutines(routines() + saved)
        scheduleRoutine(saved)
        return saved
    }

    @Synchronized fun updateRoutine(routine: ScheduledRoutine) {
        saveRoutines(routines().map { if (it.id == routine.id) routine else it })
        if (routine.enabled) scheduleRoutine(routine) else cancel(routine.id, ReminderReceiver.ACTION_ROUTINE)
    }

    @Synchronized fun removeRoutine(id: Int) {
        saveRoutines(routines().filterNot { it.id == id })
        cancel(id, ReminderReceiver.ACTION_ROUTINE)
    }

    /** Tras reiniciar el teléfono o actualizar Sara, las alarmas del sistema se pierden: se vuelven a crear. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        reminders().forEach { if (it.at > now) schedule(it.id, it.at, ReminderReceiver.ACTION_REMINDER) }
        routines().filter { it.enabled }.forEach { scheduleRoutine(it) }
    }

    fun scheduleRoutine(routine: ScheduledRoutine) {
        nextRun(routine, LocalDateTime.now())?.let {
            schedule(routine.id, it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), ReminderReceiver.ACTION_ROUTINE)
        }
    }

    fun nextRun(routine: ScheduledRoutine, now: LocalDateTime): LocalDateTime? {
        var day = now.toLocalDate()
        repeat(8) {
            val at = day.atTime(routine.hour, routine.minute)
            if (at.isAfter(now) && routine.runsOn(day.dayOfWeek)) return at
            day = day.plusDays(1)
        }
        return null
    }

    private fun pending(id: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, id * 10 + if (action == ReminderReceiver.ACTION_ROUTINE) 1 else 0,
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(id: Int, at: Long, action: String) {
        val intent = pending(id, action)
        val exact = android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }.onFailure { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
    }

    private fun cancel(id: Int, action: String) = alarms.cancel(pending(id, action))

    fun exactAlarmsAllowed(): Boolean = android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    companion object {
        fun describeTime(millis: Long): String {
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            return com.sara.core.skills.TimeParser.describe(at, LocalDateTime.now())
        }
    }
}

/** Dispara recordatorios (notificación + voz) y rutinas programadas (ejecuta la orden). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = SaraApp.containerOrNull(context) ?: return
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val pending = goAsync()
        container.scope.launch {
            try {
                when (intent.action) {
                    ACTION_REMINDER -> {
                        val reminder = container.scheduler.reminders().firstOrNull { it.id == id } ?: return@launch
                        container.scheduler.removeReminder(id)
                        notify(context, id, "Recordatorio", reminder.text)
                        runCatching { container.orchestrator.speakPreview("Te recuerdo: ${reminder.text}") }
                    }
                    ACTION_ROUTINE -> {
                        val routine = container.scheduler.routines().firstOrNull { it.id == id && it.enabled } ?: return@launch
                        container.scheduler.scheduleRoutine(routine) // la próxima vez
                        val order = routine.order
                        val reminderText = Regex("^(?:record[aá]me|av[ií]same|decime que)\\s+(.+)$", RegexOption.IGNORE_CASE).find(order)?.groupValues?.get(1)
                        if (reminderText != null) {
                            notify(context, 100_000 + id, "Rutina", reminderText)
                            runCatching { container.orchestrator.speakPreview(reminderText) }
                        } else if (!container.orchestrator.submitText(order)) {
                            // Sara estaba ocupada: queda el aviso para ejecutarla a mano.
                            notify(context, 100_000 + id, "Rutina pendiente", order)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply { description = "Recordatorios y rutinas de Sara." },
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sara)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(5_000 + id, notification) }
    }

    companion object {
        const val ACTION_REMINDER = "com.sara.assistant.REMINDER"
        const val ACTION_ROUTINE = "com.sara.assistant.ROUTINE"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "sara_reminders"
    }
}
