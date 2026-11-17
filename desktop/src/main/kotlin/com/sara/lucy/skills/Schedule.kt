package com.sara.lucy.skills

import com.sara.core.skills.TextStore
import com.sara.core.skills.TimeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Aviso a una hora: recordatorio («Te recuerdo…»), alarma o temporizador. */
data class Reminder(val id: Int, val text: String, val at: Long, val kind: String = KIND_REMINDER) {
    companion object {
        const val KIND_REMINDER = "recordatorio"
        const val KIND_ALARM = "alarma"
        const val KIND_TIMER = "temporizador"
    }
}

/** Rutina programada: a una hora, ciertos días, ejecuta una orden («decime el clima»). */
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
 * Recordatorios, alarmas, temporizadores y rutinas. Se revisan cada pocos segundos mientras Lucy
 * está abierta (arranca con Windows); lo que venció con la computadora apagada se avisa al abrir,
 * si pasó hace menos de 12 horas.
 */
class Scheduler(private val store: TextStore) {
    /** Qué hacer cuando vence algo (lo conecta la app: voz, aviso, ejecutar la orden). */
    var onReminder: suspend (Reminder, late: Boolean) -> Unit = { _, _ -> }
    var onRoutine: suspend (ScheduledRoutine) -> Unit = {}

    @Synchronized fun reminders(): List<Reminder> = JSONArray(store.get("reminders") ?: "[]").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Reminder(it.getInt("id"), it.getString("text"), it.getLong("at"), it.optString("kind", Reminder.KIND_REMINDER))
        }
    }

    @Synchronized fun routines(): List<ScheduledRoutine> = JSONArray(store.get("routines") ?: "[]").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            ScheduledRoutine(it.getInt("id"), it.getInt("hour"), it.getInt("minute"), it.getString("days"), it.getString("order"), it.optBoolean("enabled", true))
        }
    }

    private fun saveReminders(list: List<Reminder>) = store.put("reminders", JSONArray(list.map {
        JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("kind", it.kind)
    }).toString())

    private fun saveRoutines(list: List<ScheduledRoutine>) = store.put("routines", JSONArray(list.map {
        JSONObject().put("id", it.id).put("hour", it.hour).put("minute", it.minute).put("days", it.days).put("order", it.order).put("enabled", it.enabled)
    }).toString())

    private fun nextId(): Int = ((reminders().map { it.id } + routines().map { it.id }).maxOrNull() ?: 0) + 1

    @Synchronized fun addReminder(text: String, at: LocalDateTime, kind: String = Reminder.KIND_REMINDER): Reminder {
        val reminder = Reminder(nextId(), text, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), kind)
        saveReminders(reminders() + reminder)
        return reminder
    }

    @Synchronized fun removeReminder(id: Int) = saveReminders(reminders().filterNot { it.id == id })

    @Synchronized fun addRoutine(routine: ScheduledRoutine): ScheduledRoutine {
        val saved = routine.copy(id = nextId())
        saveRoutines(routines() + saved)
        return saved
    }

    @Synchronized fun updateRoutine(routine: ScheduledRoutine) = saveRoutines(routines().map { if (it.id == routine.id) routine else it })

    @Synchronized fun removeRoutine(id: Int) = saveRoutines(routines().filterNot { it.id == id })

    fun nextRun(routine: ScheduledRoutine, now: LocalDateTime): LocalDateTime? {
        var day = now.toLocalDate()
        repeat(8) {
            val at = day.atTime(routine.hour, routine.minute)
            if (at.isAfter(now) && routine.runsOn(day.dayOfWeek)) return at
            day = day.plusDays(1)
        }
        return null
    }

    fun start(scope: CoroutineScope) = scope.launch {
        var lastCheck = LocalDateTime.now()
        while (isActive) {
            val nowMillis = System.currentTimeMillis()
            for (r in reminders().filter { it.at <= nowMillis }) {
                removeReminder(r.id)
                val late = nowMillis - r.at > 90_000
                if (nowMillis - r.at < 12 * 3_600_000L) runCatching { onReminder(r, late) }
            }
            // Rutinas: las que tocaban entre la revisión anterior y ahora (las que tocaban con Lucy cerrada no se ejecutan).
            val now = LocalDateTime.now()
            for (routine in routines().filter { it.enabled }) {
                val at = nextRun(routine, lastCheck) ?: continue
                if (!at.isAfter(now)) runCatching { onRoutine(routine) }
            }
            lastCheck = now
            delay(5_000)
        }
    }

    companion object {
        fun describeTime(millis: Long): String =
            TimeParser.describe(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()), LocalDateTime.now())
    }
}
