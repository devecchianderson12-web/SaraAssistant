package com.sara.lucy.skills

import com.sara.core.apps.AppMatcher
import com.sara.core.apps.InstalledApp
import com.sara.core.memory.ConversationalAssistant
import com.sara.core.model.PermissionKind
import com.sara.core.skills.Param
import com.sara.core.skills.ParamType
import com.sara.core.skills.Risk
import com.sara.core.skills.Skill
import com.sara.core.skills.SkillAction
import com.sara.core.skills.SkillResult
import com.sara.core.skills.TimeParser
import com.sara.lucy.win.WinBridge
import com.sara.lucy.win.shellOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

internal fun getJson(url: String): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "Lucy")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IOException("HTTP $code")
        return JSONObject(body)
    } finally {
        conn.disconnect()
    }
}

// =============================================================================================
// Alarmas y temporizadores (en la computadora los hace Lucy: suena y lo dice)
// =============================================================================================

class AlarmSkill(private val scheduler: Scheduler) : Skill {
    override val id = "alarma"
    override val description = "Alarmas y temporizadores: suenan en la computadora y Lucy avisa."
    override val actions = listOf(
        SkillAction(
            "alarma", "Crea una alarma a una hora.",
            listOf(Param("hora", ParamType.INTEGER, "Hora 0-23."), Param("minuto", ParamType.INTEGER, "Minuto 0-59."), Param("etiqueta", ParamType.STRING, "Nombre.", required = false)),
            Risk.CHANGE, verification = "Queda guardada en la lista de alarmas.",
        ),
        SkillAction("temporizador", "Inicia un temporizador.", listOf(Param("segundos", ParamType.INTEGER, "Duración en segundos.")), Risk.CHANGE),
        SkillAction("ver", "Dice cuál es la próxima alarma."),
        SkillAction("ver_temporizadores", "Dice cuánto le falta a los temporizadores."),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = when (action) {
        "alarma" -> {
            val hour = args.getValue("hora").toInt().coerceIn(0, 23)
            val minute = args.getValue("minuto").toInt().coerceIn(0, 59)
            val now = LocalDateTime.now()
            var at = now.toLocalDate().atTime(hour, minute)
            if (!at.isAfter(now)) at = at.plusDays(1)
            val r = scheduler.addReminder(args["etiqueta"]?.ifBlank { null } ?: "Alarma", at, Reminder.KIND_ALARM)
            if (scheduler.reminders().any { it.id == r.id }) SkillResult.Done("Listo, alarma ${TimeParser.describe(at, now)}.")
            else SkillResult.Failed("No pude guardarla.")
        }
        "temporizador" -> {
            val seconds = args.getValue("segundos").toLong().coerceIn(1, 86_399)
            val label = TimeParser.describeDuration(seconds)
            scheduler.addReminder("Terminó el temporizador de $label.", LocalDateTime.now().plusSeconds(seconds), Reminder.KIND_TIMER)
            SkillResult.Done("Temporizador de $label en marcha.")
        }
        "ver" -> scheduler.reminders().filter { it.kind == Reminder.KIND_ALARM }.minByOrNull { it.at }
            ?.let { SkillResult.Done("Tu próxima alarma es ${Scheduler.describeTime(it.at)}.") }
            ?: SkillResult.Done("No tenés alarmas activas.")
        else -> {
            val timers = scheduler.reminders().filter { it.kind == Reminder.KIND_TIMER }.sortedBy { it.at }
            if (timers.isEmpty()) SkillResult.Done("No hay temporizadores en marcha.")
            else SkillResult.Done(timers.joinToString("; ", "Falta ", ".") { TimeParser.describeDuration(((it.at - System.currentTimeMillis()) / 1000).coerceAtLeast(1)) })
        }
    }
}

// =============================================================================================
// Recordatorios y rutinas
// =============================================================================================

class ReminderSkill(private val scheduler: Scheduler) : Skill {
    override val id = "recordatorios"
    override val description = "Recordatorios con aviso y voz a una hora."
    override val actions = listOf(
        SkillAction(
            "crear", "Crea un recordatorio.",
            listOf(Param("texto", ParamType.STRING, "Qué recordar."), Param("cuando", ParamType.STRING, "Fecha y hora ISO, por ejemplo 2026-11-18T09:00.")),
            Risk.CHANGE, verification = "Queda guardado y programado.",
        ),
        SkillAction("ver", "Lista los recordatorios pendientes."),
        SkillAction("borrar", "Borra un recordatorio (o el próximo).", listOf(Param("texto", ParamType.STRING, "Cuál.", required = false)), Risk.CHANGE),
    )

    private fun pending() = scheduler.reminders().filter { it.kind == Reminder.KIND_REMINDER }.sortedBy { it.at }

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        return when (action) {
            "crear" -> {
                val at = runCatching { LocalDateTime.parse(args.getValue("cuando")) }.getOrNull()
                    ?: return SkillResult.Failed("No entendí cuándo.")
                if (!at.isAfter(LocalDateTime.now())) return SkillResult.Failed("Esa hora ya pasó.")
                val r = scheduler.addReminder(args.getValue("texto"), at)
                if (scheduler.reminders().any { it.id == r.id }) SkillResult.Done("Listo, te lo recuerdo ${TimeParser.describe(at, LocalDateTime.now())}.")
                else SkillResult.Failed("No pude guardarlo.")
            }
            "ver" -> pending().let { list ->
                if (list.isEmpty()) SkillResult.Done("No tenés recordatorios.")
                else SkillResult.Done(list.take(5).joinToString("; ", "Tenés: ", ".") { "${it.text}, ${Scheduler.describeTime(it.at)}" })
            }
            else -> {
                val list = pending()
                val query = args["texto"].orEmpty()
                val target = if (query.isBlank()) list.firstOrNull()
                else AppMatcher.best(query, list.map { InstalledApp(it.text, it.id.toString()) }, threshold = 0.5)?.let { m -> list.first { it.id.toString() == m.id } }
                if (target == null) SkillResult.Failed("No encontré ese recordatorio.")
                else {
                    scheduler.removeReminder(target.id)
                    SkillResult.Done("Borré «${target.text}».")
                }
            }
        }
    }
}

class RoutineSkill(private val scheduler: Scheduler) : Skill {
    override val id = "rutinas"
    override val description = "Rutinas programadas: ejecutan una orden a una hora fija."
    override val actions = listOf(
        SkillAction(
            "programar", "Programa una orden a una hora, todos los días, de lunes a viernes o fines de semana.",
            listOf(
                Param("hora", ParamType.INTEGER, "Hora 0-23."), Param("minuto", ParamType.INTEGER, "Minuto 0-59."),
                Param("dias", ParamType.STRING, "Días.", options = listOf("todos", "lun-vie", "sab-dom")),
                Param("orden", ParamType.STRING, "La orden, como la diría el usuario: «decime el clima»."),
            ),
            Risk.CHANGE,
        ),
        SkillAction("ver", "Lista las rutinas."),
        SkillAction("borrar", "Borra una rutina.", listOf(Param("texto", ParamType.STRING, "Cuál (hora u orden).", required = false)), Risk.SENSITIVE, confirm = { "¿Borro la rutina ${it["texto"].orEmpty()}?" }),
        SkillAction("pausar", "Pausa una rutina.", listOf(Param("texto", ParamType.STRING, "Cuál.", required = false)), Risk.CHANGE),
        SkillAction("reanudar", "Reactiva una rutina pausada.", listOf(Param("texto", ParamType.STRING, "Cuál.", required = false)), Risk.CHANGE),
    )

    private fun find(text: String?): ScheduledRoutine? {
        val all = scheduler.routines()
        if (text.isNullOrBlank()) return all.singleOrNull()
        TimeParser.clock(if (text.contains("las")) text else "a las $text")?.let { c ->
            all.firstOrNull { it.hour == c.hour && it.minute == c.minute }?.let { return it }
        }
        val m = AppMatcher.best(text, all.map { InstalledApp(it.order, it.id.toString()) }, threshold = 0.5) ?: return null
        return all.first { it.id.toString() == m.id }
    }

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = when (action) {
        "programar" -> {
            val r = scheduler.addRoutine(
                ScheduledRoutine(0, args.getValue("hora").toInt().coerceIn(0, 23), args.getValue("minuto").toInt().coerceIn(0, 59), args.getValue("dias"), args.getValue("orden")),
            )
            SkillResult.Done("Listo: ${r.describe()}.")
        }
        "ver" -> scheduler.routines().let { all ->
            if (all.isEmpty()) SkillResult.Done("No tenés rutinas programadas.") else SkillResult.Done(all.joinToString("; ", "Tenés: ", ".") { it.describe() })
        }
        "borrar" -> find(args["texto"])?.let { scheduler.removeRoutine(it.id); SkillResult.Done("Borré la rutina.") } ?: SkillResult.Failed("No encontré esa rutina.")
        "pausar" -> find(args["texto"])?.let { scheduler.updateRoutine(it.copy(enabled = false)); SkillResult.Done("Rutina pausada.") } ?: SkillResult.Failed("No encontré esa rutina.")
        else -> find(args["texto"])?.let { scheduler.updateRoutine(it.copy(enabled = true)); SkillResult.Done("Rutina activada.") } ?: SkillResult.Failed("No encontré esa rutina.")
    }
}

// =============================================================================================
// Ubicación aproximada (por la conexión a Internet; o la ciudad elegida en Ajustes)
// =============================================================================================

class LocationProvider(private val city: () -> String) {
    data class Place(val lat: Double, val lon: Double, val name: String?, val approximate: Boolean)

    @Volatile private var cached: Pair<Long, Place>? = null

    suspend fun current(): Place? = withContext(Dispatchers.IO) {
        city().trim().takeIf { it.isNotEmpty() }?.let { c -> geocode(c)?.let { return@withContext it } }
        cached?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return@withContext it.second }
        val place = runCatching {
            val j = getJson("https://ipwho.is/?lang=es")
            if (!j.optBoolean("success", true)) throw IOException(j.optString("message"))
            Place(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city").ifBlank { null }, true)
        }.recoverCatching {
            val j = getJson("https://ipapi.co/json/")
            Place(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city").ifBlank { null }, true)
        }.getOrNull()
        place?.also { cached = System.currentTimeMillis() to it }
    }

    fun geocode(name: String): Place? = runCatching {
        val r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name=" + enc(name)).optJSONArray("results")?.optJSONObject(0)
            ?: return null
        Place(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", name), false)
    }.getOrNull()
}

// =============================================================================================
// Clima (Open-Meteo: gratis, sin clave)
// =============================================================================================

class WeatherSkill(private val location: LocationProvider) : Skill {
    override val id = "clima"
    override val description = "Clima actual y pronóstico (hoy, mañana, pasado mañana) de tu zona o de una ciudad."
    override val actions = listOf(
        SkillAction(
            "consultar", "Clima de un lugar y día.",
            listOf(
                Param("lugar", ParamType.STRING, "Ciudad (vacío = tu zona).", required = false),
                Param("dia", ParamType.STRING, "Día.", required = false, options = listOf("hoy", "manana", "pasado_manana")),
            ),
        ),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = withContext(Dispatchers.IO) {
        val place = args["lugar"]?.trim().orEmpty()
        val where = if (place.isNotEmpty()) location.geocode(place) ?: return@withContext SkillResult.Failed("No encontré $place.")
        else location.current() ?: return@withContext SkillResult.Failed("No sé dónde estás. Decime la ciudad o cargala en Ajustes.")
        val data = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${where.lat}&longitude=${where.lon}&timezone=auto&forecast_days=3" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
        )
        val inPlace = where.name?.let { " en $it" } ?: ""
        val dayIndex = when (args["dia"]) { "manana" -> 1; "pasado_manana" -> 2; else -> 0 }
        val daily = data.getJSONObject("daily")
        val max = daily.getJSONArray("temperature_2m_max").getDouble(dayIndex).roundToInt()
        val min = daily.getJSONArray("temperature_2m_min").getDouble(dayIndex).roundToInt()
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(dayIndex, -1) ?: -1
        val rainText = when {
            rain >= 60 -> " Probabilidad de lluvia alta, $rain por ciento."
            rain >= 30 -> " Puede llover, $rain por ciento."
            else -> ""
        }
        if (dayIndex == 0) {
            val current = data.getJSONObject("current")
            val temp = current.getDouble("temperature_2m").roundToInt()
            SkillResult.Done("Ahora$inPlace hace $temp grados, ${sky(current.getInt("weather_code"))}. Máxima de $max y mínima de $min.$rainText")
        } else {
            val day = if (dayIndex == 1) "Mañana" else "Pasado mañana"
            SkillResult.Done("$day$inPlace: ${sky(daily.getJSONArray("weather_code").getInt(dayIndex))}, entre $min y $max grados.$rainText")
        }
    }

    private fun sky(code: Int) = when (code) {
        0 -> "despejado"
        1, 2 -> "algo nublado"
        3 -> "nublado"
        45, 48 -> "con niebla"
        in 51..57 -> "con llovizna"
        in 61..67, in 80..82 -> "con lluvia"
        in 71..77, 85, 86 -> "con nieve"
        in 95..99 -> "con tormenta"
        else -> "variable"
    }
}

// =============================================================================================
// La computadora: brillo, Wi-Fi, Bluetooth, batería, información
// =============================================================================================

class DeviceSkill(private val bridge: WinBridge) : Skill {
    override val id = "dispositivo"
    override val description = "Brillo, Wi-Fi, Bluetooth, No molestar, batería e información de la computadora."
    private val state = Param("estado", ParamType.STRING, "Qué hacer.", options = listOf("prender", "apagar", "consultar"))
    override val actions = listOf(
        SkillAction("linterna", "Prende o apaga la linterna.", listOf(state), Risk.CHANGE),
        SkillAction(
            "brillo", "Cambia o consulta el brillo de la pantalla.",
            listOf(
                Param("accion", ParamType.STRING, "Qué hacer.", options = listOf("subir", "bajar", "fijar", "consultar")),
                Param("porcentaje", ParamType.INTEGER, "0 a 100.", required = false),
            ),
            Risk.CHANGE, verification = "Relee el brillo de la pantalla.",
        ),
        SkillAction("wifi", "Prende, apaga o consulta el Wi-Fi.", listOf(state), Risk.CHANGE, verification = "Relee el estado de la radio Wi-Fi."),
        SkillAction(
            "bluetooth", "Prende, apaga o consulta el Bluetooth, o abre la lista para conectar un dispositivo.",
            listOf(
                Param("estado", ParamType.STRING, "Qué hacer.", options = listOf("prender", "apagar", "consultar", "conectar")),
                Param("dispositivo", ParamType.STRING, "Nombre del dispositivo para «conectar».", required = false),
            ),
            Risk.CHANGE, verification = "Relee el estado de la radio Bluetooth.",
        ),
        SkillAction("no_molestar", "Modo No molestar.", listOf(state), Risk.CHANGE),
        SkillAction("bateria", "Nivel de batería y si está cargando."),
        SkillAction("info", "Versión de Windows, espacio en disco y memoria libres."),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = withContext(Dispatchers.IO) {
        when (action) {
            "linterna" -> SkillResult.Failed("La computadora no tiene linterna.")
            "brillo" -> brightness(args["accion"] ?: "consultar", args["porcentaje"]?.toIntOrNull())
            "wifi" -> radio("WiFi", "el Wi-Fi", args["estado"] ?: "consultar")
            "bluetooth" -> if (args["estado"] == "conectar") {
                if (shellOpen(bridge, "ms-settings:bluetooth")) SkillResult.Partial("Te abrí Bluetooth: tocá ${args["dispositivo"]?.ifBlank { null } ?: "el dispositivo"} para conectarlo.")
                else SkillResult.Failed("No pude abrir Bluetooth.")
            } else radio("Bluetooth", "el Bluetooth", args["estado"] ?: "consultar")
            "no_molestar" -> if (shellOpen(bridge, "ms-settings:notifications")) SkillResult.Partial("Windows no deja cambiarlo desde acá: te abrí las notificaciones.")
            else SkillResult.Failed("No pude abrir las notificaciones.")
            "bateria" -> {
                val b = bridge.obj("battery")
                if (!b.optBoolean("present")) SkillResult.Done("Esta computadora no tiene batería: está enchufada.")
                else SkillResult.Done("Tenés ${b.optInt("percent")} por ciento de batería" + if (b.optBoolean("charging")) ", cargando." else ".")
            }
            else -> {
                val i = bridge.obj("info", timeoutMillis = 20_000)
                SkillResult.Done(
                    "${i.optString("os").removePrefix("Microsoft ")}. Te quedan ${i.optDouble("freeDiskGb").roundToInt()} GB libres de ${i.optInt("totalDiskGb")} " +
                        "y ${"%.1f".format(i.optInt("freeMemMb") / 1024.0)} GB de memoria libre de ${(i.optInt("totalMemMb") / 1024.0).roundToInt()}.",
                )
            }
        }
    }

    private fun brightness(action: String, percent: Int?): SkillResult {
        val now = bridge.obj("brightness")
        if (!now.optBoolean("supported")) return SkillResult.Failed("Esta pantalla no deja cambiar el brillo desde Windows.")
        val current = now.optInt("percent")
        val target = when (action) {
            "consultar" -> return SkillResult.Done("El brillo está al $current por ciento.")
            "subir" -> current + (percent ?: 20)
            "bajar" -> current - (percent ?: 20)
            else -> percent ?: return SkillResult.Failed("Decime a cuánto.")
        }.coerceIn(5, 100)
        val after = bridge.obj("brightness", mapOf("percent" to target)).optInt("percent", -1)
        return if (after >= 0 && kotlin.math.abs(after - target) <= 5) SkillResult.Done("Listo, brillo al $after por ciento.")
        else SkillResult.Failed("Windows no cambió el brillo.")
    }

    private fun radio(kind: String, name: String, wanted: String): SkillResult {
        val st = if (wanted == "prender") "on" else if (wanted == "apagar") "off" else "query"
        val r = runCatching { bridge.obj("radio", mapOf("kind" to kind, "state" to st), timeoutMillis = 15_000) }
            .getOrElse { return SkillResult.Failed("Windows no me deja manejar $name.") }
        if (!r.optBoolean("present")) return SkillResult.Failed("No encontré $name en esta computadora.")
        val on = r.optString("state") == "On"
        return when (st) {
            "query" -> SkillResult.Done(if (on) "${name.replaceFirstChar(Char::uppercase)} está prendido." else "${name.replaceFirstChar(Char::uppercase)} está apagado.")
            "on" -> if (on) SkillResult.Done("Listo, prendí $name.") else SkillResult.Failed("Windows no dejó prender $name.")
            else -> if (!on) SkillResult.Done("Listo, apagué $name.") else SkillResult.Failed("Windows no dejó apagar $name.")
        }
    }
}

// =============================================================================================
// Lo que en la computadora se hace en el navegador: calendario y mapas
// =============================================================================================

class CalendarSkill(private val bridge: WinBridge) : Skill {
    override val id = "calendario"
    override val description = "Abre tu Google Calendar (hoy, mañana o un evento nuevo listo para guardar)."
    override val actions = listOf(
        SkillAction("ver", "Abre el calendario en hoy o mañana.", listOf(Param("dia", ParamType.STRING, "Día.", required = false, options = listOf("hoy", "manana")))),
        SkillAction("crear", "Deja un evento listo para guardar.", listOf(Param("titulo", ParamType.STRING, "Título."), Param("cuando", ParamType.STRING, "Fecha y hora ISO.")), Risk.CHANGE),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        if (action == "ver") {
            val day = if (args["dia"] == "manana") LocalDate.now().plusDays(1) else LocalDate.now()
            val url = "https://calendar.google.com/calendar/r/day/${day.year}/${day.monthValue}/${day.dayOfMonth}"
            return if (shellOpen(bridge, url)) SkillResult.Partial("En la computadora no leo tu agenda: te abrí el calendario de ${if (args["dia"] == "manana") "mañana" else "hoy"}.")
            else SkillResult.Failed("No pude abrir el calendario.")
        }
        val at = runCatching { LocalDateTime.parse(args.getValue("cuando")) }.getOrNull() ?: return SkillResult.Failed("No entendí cuándo.")
        val f = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val url = "https://calendar.google.com/calendar/render?action=TEMPLATE&text=" + enc(args.getValue("titulo")) +
            "&dates=" + at.format(f) + "/" + at.plusHours(1).format(f)
        return if (shellOpen(bridge, url)) SkillResult.Partial("Te abrí el calendario con «${args.getValue("titulo")}» ${TimeParser.describe(at, LocalDateTime.now())}: tocá guardar.")
        else SkillResult.Failed("No pude abrir el calendario.")
    }
}

class MapsSkill(private val bridge: WinBridge, private val location: LocationProvider) : Skill {
    override val id = "mapas"
    override val description = "Cómo llegar, lugares cercanos y dónde estoy (aproximado)."
    override val actions = listOf(
        SkillAction("navegar", "Muestra cómo llegar a un destino.", listOf(Param("destino", ParamType.STRING, "Dirección o lugar.")), Risk.CHANGE),
        SkillAction("cerca", "Busca lugares cercanos (farmacias, cafés…).", listOf(Param("lugar", ParamType.STRING, "Qué buscar.")), Risk.CHANGE),
        SkillAction("ubicacion", "Dice dónde estás (aproximado, por la conexión)."),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = when (action) {
        "navegar" -> {
            val dest = args.getValue("destino")
            if (shellOpen(bridge, "https://www.google.com/maps/dir/?api=1&destination=" + enc(dest))) SkillResult.Partial("Te abrí cómo llegar a $dest.")
            else SkillResult.Failed("No pude abrir el mapa.")
        }
        "cerca" -> {
            val what = args.getValue("lugar")
            if (shellOpen(bridge, "https://www.google.com/maps/search/" + enc("$what cerca"))) SkillResult.Partial("Te muestro $what cerca.")
            else SkillResult.Failed("No pude abrir el mapa.")
        }
        else -> {
            val p = location.current() ?: return SkillResult.Failed("No pude saber dónde estás.")
            if (p.name != null) SkillResult.Done(if (p.approximate) "Estás por ${p.name}, más o menos." else "Estás en ${p.name}.")
            else SkillResult.Done("Estás cerca de ${"%.3f".format(p.lat)}, ${"%.3f".format(p.lon)}.")
        }
    }
}

// =============================================================================================
// Lo que sólo se puede en el teléfono (respuesta honesta, sin simular)
// =============================================================================================

class PhoneOnlySkill(override val id: String, private val what: String, override val actions: List<SkillAction>) : Skill {
    override val description = "$what: sólo desde el teléfono."
    override suspend fun run(action: String, args: Map<String, String>) =
        SkillResult.Failed("Desde la computadora no puedo $what. Pedíselo a Sara en el teléfono.")
}

fun phoneOnlySkills() = listOf(
    PhoneOnlySkill(
        "llamadas", "llamar ni mandar SMS",
        listOf(
            SkillAction("llamar", "Llama a un contacto o número.", listOf(Param("contacto", ParamType.STRING, "Nombre o número.")), Risk.SENSITIVE),
            SkillAction("sms", "Deja un SMS escrito.", listOf(Param("contacto", ParamType.STRING, "Nombre o número."), Param("mensaje", ParamType.STRING, "Texto.")), Risk.SENSITIVE),
        ),
    ),
    PhoneOnlySkill("notificaciones", "leer tus notificaciones", listOf(SkillAction("leer", "Resumen de notificaciones."))),
    PhoneOnlySkill("hogar", "manejar tu casa", listOf(SkillAction("controlar", "Hogar inteligente.", listOf(Param("pedido", ParamType.STRING, "Qué querés hacer.")), Risk.CHANGE))),
)

// =============================================================================================
// IA: búsqueda en Google y traducción (iguales que en el teléfono)
// =============================================================================================

class GoogleSkill(private val ai: ConversationalAssistant) : Skill {
    override val id = "google"
    override val description = "Busca información actual en Google (noticias, resultados, datos, precios)."
    override val actions = listOf(SkillAction("buscar", "Busca y responde en una o dos frases.", listOf(Param("consulta", ParamType.STRING, "Qué buscar.")), permissions = setOf(PermissionKind.AI_KEY)))
    override suspend fun run(action: String, args: Map<String, String>) = SkillResult.Done(ai.reply(args.getValue("consulta")))
}

class TranslatorSkill(private val ai: ConversationalAssistant) : Skill {
    override val id = "traductor"
    override val description = "Traduce frases a otro idioma."
    override val actions = listOf(
        SkillAction("traducir", "Traduce un texto.", listOf(Param("texto", ParamType.STRING, "Texto."), Param("idioma", ParamType.STRING, "Idioma de destino.")), permissions = setOf(PermissionKind.AI_KEY)),
    )
    override suspend fun run(action: String, args: Map<String, String>) =
        SkillResult.Done(ai.reply("Traducí al ${args.getValue("idioma")} y respondé sólo con la traducción, sin comillas: ${args.getValue("texto")}"))
}

class DiagnosticsSkill(private val checks: suspend () -> List<String>) : Skill {
    override val id = "diagnostico"
    override val description = "Revisa micrófono, modelo de voz, conexión e IA de Lucy."
    override val actions = listOf(SkillAction("revisar", "Informa qué falta o qué está fallando."))

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val problems = checks()
        return if (problems.isEmpty()) SkillResult.Done("Todo en orden.") else SkillResult.Done("Encontré esto: " + problems.joinToString("; ") + ".")
    }
}
