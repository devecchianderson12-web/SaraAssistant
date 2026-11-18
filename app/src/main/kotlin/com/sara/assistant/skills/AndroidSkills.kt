package com.sara.assistant.skills

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.sara.assistant.apps.ActivityStarter
import com.sara.assistant.apps.StartOutcome
import com.sara.assistant.media.SaraNotificationListener
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Números y letras dichos en palabras («ge tres» → «g3»), para nombres de dispositivos. */
private val SPOKEN = mapOf(
    "cero" to "0", "uno" to "1", "una" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5", "seis" to "6",
    "siete" to "7", "ocho" to "8", "nueve" to "9", "diez" to "10",
    "be" to "b", "ce" to "c", "de" to "d", "efe" to "f", "ge" to "g", "je" to "g", "hache" to "h", "jota" to "j",
    "ka" to "k", "ele" to "l", "eme" to "m", "ene" to "n", "pe" to "p", "cu" to "q", "erre" to "r", "ese" to "s", "te" to "t",
    "uve" to "v", "ve" to "v", "doble ve" to "w", "equis" to "x", "ye" to "y", "zeta" to "z",
)

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** Abre una pantalla respetando las reglas de Android (directo o con notificación para tocar). */
private suspend fun open(starter: ActivityStarter, intent: Intent, label: String, done: String, tap: String): SkillResult =
    when (val outcome = withContext(Dispatchers.Main) { starter.start(intent, label) }) {
        is StartOutcome.Started -> SkillResult.Partial(done)
        StartOutcome.NeedsTap -> SkillResult.Partial(tap)
        is StartOutcome.Failed -> SkillResult.Failed("No pude abrir $label.")
    }

// =============================================================================================
// Alarmas y temporizadores (app de reloj del teléfono)
// =============================================================================================

class AlarmSkill(private val context: Context, private val starter: ActivityStarter) : Skill {
    override val id = "alarma"
    override val description = "Alarmas y temporizadores en el reloj del teléfono."
    override val actions = listOf(
        SkillAction(
            "alarma", "Crea una alarma a una hora.",
            listOf(Param("hora", ParamType.INTEGER, "Hora 0-23."), Param("minuto", ParamType.INTEGER, "Minuto 0-59."), Param("etiqueta", ParamType.STRING, "Nombre.", required = false)),
            Risk.CHANGE, verification = "Consulta la próxima alarma del sistema.",
        ),
        SkillAction("temporizador", "Inicia un temporizador.", listOf(Param("segundos", ParamType.INTEGER, "Duración en segundos.")), Risk.CHANGE),
        SkillAction("ver", "Dice cuál es la próxima alarma."),
        SkillAction("ver_temporizadores", "Abre los temporizadores del reloj."),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = when (action) {
        "alarma" -> {
            val hour = args.getValue("hora").toInt().coerceIn(0, 23)
            val minute = args.getValue("minuto").toInt().coerceIn(0, 59)
            val time = "$hour:${minute.toString().padStart(2, '0')}"
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { args["etiqueta"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            when (val r = open(starter, intent, "el reloj", "", "Tocá la notificación para crear la alarma de las $time.")) {
                is SkillResult.Partial -> if (r.message.isNotEmpty()) r else {
                    // Verificación: la próxima alarma del sistema coincide con la pedida.
                    delay(1_500)
                    val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
                    val nextTime = next?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
                    if (nextTime != null && nextTime.hour == hour && nextTime.minute == minute) SkillResult.Done("Listo, alarma a las $time.")
                    else SkillResult.Done("Le pedí al reloj la alarma de las $time.", verified = false)
                }
                else -> r
            }
        }
        "temporizador" -> {
            val seconds = args.getValue("segundos").toInt().coerceIn(1, 86_399)
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            val label = TimeParser.describeDuration(seconds.toLong())
            open(starter, intent, "el reloj", "Temporizador de $label en marcha.", "Tocá la notificación para iniciar el temporizador.")
        }
        "ver" -> {
            val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
            if (next == null) SkillResult.Done("No tenés alarmas activas.")
            else SkillResult.Done("Tu próxima alarma es ${Scheduler.describeTime(next)}.")
        }
        else -> open(starter, Intent(AlarmClock.ACTION_SHOW_TIMERS), "el reloj", "Te abrí los temporizadores.", "Tocá la notificación para ver los temporizadores.")
    }
}

// =============================================================================================
// Recordatorios y rutinas programadas
// =============================================================================================

class ReminderSkill(private val scheduler: Scheduler) : Skill {
    override val id = "recordatorios"
    override val description = "Recordatorios con aviso y voz a una hora."
    override val actions = listOf(
        SkillAction(
            "crear", "Crea un recordatorio.",
            listOf(Param("texto", ParamType.STRING, "Qué recordar."), Param("cuando", ParamType.STRING, "Fecha y hora ISO, por ejemplo 2026-11-18T09:00.")),
            Risk.CHANGE, verification = "Queda guardado y programado en el sistema.",
        ),
        SkillAction("ver", "Lista los recordatorios pendientes."),
        SkillAction("borrar", "Borra un recordatorio (o el próximo).", listOf(Param("texto", ParamType.STRING, "Cuál.", required = false)), Risk.CHANGE),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        return when (action) {
            "crear" -> {
                val at = runCatching { LocalDateTime.parse(args.getValue("cuando")) }.getOrNull()
                    ?: return SkillResult.Failed("No entendí cuándo.")
                if (!at.isAfter(LocalDateTime.now())) return SkillResult.Failed("Esa hora ya pasó.")
                val r = scheduler.addReminder(args.getValue("texto"), at)
                if (scheduler.reminders().any { it.id == r.id }) {
                    SkillResult.Done("Listo, te lo recuerdo ${TimeParser.describe(at, LocalDateTime.now())}." +
                        if (scheduler.exactAlarmsAllowed()) "" else " Puede demorar unos minutos.")
                } else {
                    SkillResult.Failed("No pude guardarlo.")
                }
            }
            "ver" -> {
                val list = scheduler.reminders().sortedBy { it.at }
                if (list.isEmpty()) SkillResult.Done("No tenés recordatorios.")
                else SkillResult.Done(list.take(5).joinToString("; ", "Tenés: ", ".") { "${it.text}, ${Scheduler.describeTime(it.at)}" })
            }
            else -> {
                val list = scheduler.reminders().sortedBy { it.at }
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
    override val description = "Rutinas programadas: ejecutan una orden de Sara a una hora fija."
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
// Ubicación (compartida por clima, mapas y «dónde estoy»)
// =============================================================================================

class LocationProvider(private val context: Context) {
    fun hasPermission() = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    @Suppress("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val recent = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 30 * 60_000) return recent
        if (Build.VERSION.SDK_INT >= 30 && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val fresh = withTimeoutOrNull(6_000) {
                suspendCancellableCoroutine { cont ->
                    val executor = Executors.newSingleThreadExecutor()
                    manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, executor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                        executor.shutdown()
                    }
                }
            }
            if (fresh != null) return fresh
        }
        return recent
    }

    @Suppress("DEPRECATION")
    suspend fun placeName(location: Location): String? = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.forLanguageTag("es-AR")).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
        }.getOrNull()?.let { a -> listOfNotNull(a.thoroughfare?.let { s -> a.subThoroughfare?.let { "$s $it" } ?: s }, a.locality ?: a.subAdminArea).joinToString(", ").ifBlank { null } }
    }

    @Suppress("DEPRECATION")
    suspend fun cityName(location: Location): String? = withContext(Dispatchers.IO) {
        runCatching { Geocoder(context, Locale.forLanguageTag("es-AR")).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }
            .getOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }
}

// =============================================================================================
// Clima (Open-Meteo: gratis, sin clave, respuesta en ~1 segundo)
// =============================================================================================

class WeatherSkill(private val location: LocationProvider) : Skill {
    override val id = "clima"
    override val description = "Clima actual y pronóstico (hoy, mañana, pasado mañana) de tu ubicación o de una ciudad."
    override val actions = listOf(
        SkillAction(
            "consultar", "Clima de un lugar y día.",
            listOf(
                Param("lugar", ParamType.STRING, "Ciudad (vacío = ubicación actual).", required = false),
                Param("dia", ParamType.STRING, "Día.", required = false, options = listOf("hoy", "manana", "pasado_manana")),
            ),
        ),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = withContext(Dispatchers.IO) {
        val place = args["lugar"]?.trim().orEmpty()
        val (lat, lon, name) = if (place.isNotEmpty()) {
            val geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name=" + URLEncoder.encode(place, "UTF-8"))
            val r = geo.optJSONArray("results")?.optJSONObject(0) ?: return@withContext SkillResult.Failed("No encontré $place.")
            Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", place))
        } else {
            if (!location.hasPermission()) {
                return@withContext SkillResult.NeedsPermission(PermissionKind.LOCATION, "Decime la ciudad o activá la ubicación. Te dejé el botón.")
            }
            val loc = location.current() ?: return@withContext SkillResult.Failed("No sé dónde estás. Decime la ciudad.")
            Triple(loc.latitude, loc.longitude, location.cityName(loc))
        }
        val data = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=3" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
        )
        val where = name?.let { " en $it" } ?: ""
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
            val sky = describe(current.getInt("weather_code"))
            SkillResult.Done("Ahora$where hace $temp grados, $sky. Máxima de $max y mínima de $min.$rainText")
        } else {
            val sky = describe(daily.getJSONArray("weather_code").getInt(dayIndex))
            val day = if (dayIndex == 1) "Mañana" else "Pasado mañana"
            SkillResult.Done("$day$where: $sky, entre $min y $max grados.$rainText")
        }
    }

    private fun describe(code: Int) = when (code) {
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

internal fun get(url: String): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return JSONObject(body)
    } finally {
        conn.disconnect()
    }
}

// =============================================================================================
// Ajustes del teléfono
// =============================================================================================

class DeviceSkill(private val context: Context, private val starter: ActivityStarter) : Skill {
    override val id = "dispositivo"
    override val description = "Linterna, brillo, Wi-Fi, Bluetooth, No molestar, batería e información del teléfono."
    private val state = Param("estado", ParamType.STRING, "Qué hacer.", options = listOf("prender", "apagar", "consultar"))
    override val actions = listOf(
        SkillAction("linterna", "Prende o apaga la linterna.", listOf(state), Risk.CHANGE, verification = "Callback de la cámara."),
        SkillAction(
            "brillo", "Cambia o consulta el brillo de la pantalla.",
            listOf(
                Param("accion", ParamType.STRING, "Qué hacer.", options = listOf("subir", "bajar", "fijar", "consultar")),
                Param("porcentaje", ParamType.INTEGER, "0 a 100.", required = false),
            ),
            Risk.CHANGE, verification = "Relee el brillo del sistema.",
        ),
        SkillAction("wifi", "Prende, apaga o consulta el Wi-Fi (con Accesibilidad lo hace sola).", listOf(state), Risk.CHANGE, verification = "Relee el estado del Wi-Fi."),
        SkillAction(
            "bluetooth", "Prende, apaga o consulta el Bluetooth, o se conecta a un dispositivo vinculado (auriculares, parlante, auto).",
            listOf(
                Param("estado", ParamType.STRING, "Qué hacer.", options = listOf("prender", "apagar", "consultar", "conectar")),
                Param("dispositivo", ParamType.STRING, "Nombre del dispositivo para «conectar».", required = false),
            ),
            Risk.CHANGE, verification = "Relee el estado del Bluetooth y si hay un dispositivo de audio conectado.",
        ),
        SkillAction("no_molestar", "Modo No molestar.", listOf(state), Risk.CHANGE, setOf(), "Relee el filtro de interrupciones."),
        SkillAction("bateria", "Nivel de batería y si está cargando."),
        SkillAction("bloquear", "Bloquea y apaga la pantalla.", risk = Risk.CHANGE, permissions = setOf(PermissionKind.ACCESSIBILITY), verification = "Relee si la pantalla quedó apagada."),
        SkillAction("desbloquear", "Prende la pantalla y la desbloquea (si no tiene contraseña).", risk = Risk.CHANGE, verification = "Relee si sigue bloqueada."),
        SkillAction("info", "Modelo, versión de Android, almacenamiento y memoria libres."),
    )

    @Volatile private var torchOn = false
    private val camera = context.getSystemService(CameraManager::class.java)

    init {
        runCatching {
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = when (action) {
        "linterna" -> torch(args["estado"] != "apagar")
        "brillo" -> brightness(args.getValue("accion"), args["porcentaje"]?.toIntOrNull())
        "wifi" -> {
            val wifi = context.getSystemService(WifiManager::class.java)
            val enabled = wifi?.isWifiEnabled == true
            when (args["estado"]) {
                "consultar" -> SkillResult.Done(if (enabled) "El Wi-Fi está prendido." else "El Wi-Fi está apagado.")
                "prender" -> if (enabled) SkillResult.Done("El Wi-Fi ya está prendido.") else autoToggle(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi", true) { wifi?.isWifiEnabled == true }
                else -> if (!enabled) SkillResult.Done("El Wi-Fi ya está apagado.") else autoToggle(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi", false) { wifi?.isWifiEnabled == true }
            }
        }
        "bluetooth" -> bluetooth(args.getValue("estado"), args["dispositivo"])
        "no_molestar" -> dnd(args.getValue("estado"))
        "bloquear" -> lockScreen()
        "desbloquear" -> unlockScreen()
        "bateria" -> {
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            SkillResult.Done("Tenés $level por ciento de batería" + if (bm.isCharging) ", cargando." else ".")
        }
        else -> {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = stat.availableBytes / 1_073_741_824.0
            val totalGb = stat.totalBytes / 1_073_741_824.0
            val memory = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
            SkillResult.Done(
                "Es un ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} con Android ${Build.VERSION.RELEASE}. " +
                    "Libres: ${"%.1f".format(freeGb)} de ${"%.0f".format(totalGb)} gigas de almacenamiento y ${memory.availMem / 1_048_576} megas de memoria.",
            )
        }
    }

    private suspend fun lockScreen(): SkillResult {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        if (!power.isInteractive) return SkillResult.Done("La pantalla ya está bloqueada.")
        val done = com.sara.assistant.apps.SaraAccessibilityService.lockScreen(context)
            ?: return SkillResult.NeedsPermission(PermissionKind.ACCESSIBILITY, "Para bloquear la pantalla necesito Accesibilidad. Te dejé el botón.")
        if (!done) return SkillResult.Failed("El teléfono no me dejó bloquear la pantalla.")
        repeat(10) { if (!power.isInteractive) return SkillResult.Done("") ; delay(150) }
        return SkillResult.Done("", verified = false)
    }

    private suspend fun unlockScreen(): SkillResult {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        if (!keyguard.isKeyguardLocked && power.isInteractive) return SkillResult.Done("El teléfono ya está desbloqueado.")
        // Prende la pantalla aunque Sara esté en segundo plano.
        @Suppress("DEPRECATION")
        val wake = power.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "sara:desbloquear",
        )
        runCatching { wake.acquire(3_000) }
        val intent = Intent(context, com.sara.assistant.ui.UnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        starter.start(intent, "Desbloquear")
        repeat(20) {
            if (!keyguard.isKeyguardLocked) return SkillResult.Done("Listo, desbloqueado.")
            delay(150)
        }
        return if (keyguard.isDeviceSecure) SkillResult.Partial("Prendí la pantalla; como tiene contraseña, desbloqueala vos.")
        else SkillResult.Done("Prendí la pantalla.", verified = false)
    }

    private suspend fun torch(on: Boolean): SkillResult {
        val id = runCatching {
            camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return SkillResult.Failed("Este teléfono no tiene linterna.")
        return try {
            camera.setTorchMode(id, on)
            delay(300)
            if (torchOn == on) SkillResult.Done(if (on) "Linterna prendida." else "Linterna apagada.")
            else SkillResult.Done(if (on) "Prendí la linterna." else "Apagué la linterna.", verified = false)
        } catch (e: Exception) {
            SkillResult.Failed("La cámara está en uso, no pude usar la linterna.")
        }
    }

    private fun brightness(action: String, percent: Int?): SkillResult {
        val resolver = context.contentResolver
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val currentPct = (current * 100 / 255.0).roundToInt()
        if (action == "consultar") return SkillResult.Done("El brillo está al $currentPct por ciento.")
        if (!Settings.System.canWrite(context)) {
            return SkillResult.NeedsPermission(PermissionKind.WRITE_SETTINGS, "Para cambiar el brillo necesito un permiso. Te dejé el botón.")
        }
        val target = when (action) {
            "subir" -> currentPct + (percent ?: 20)
            "bajar" -> currentPct - (percent ?: 20)
            else -> percent ?: currentPct
        }.coerceIn(1, 100)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (target * 255 / 100.0).roundToInt().coerceIn(1, 255))
        val after = (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 0) * 100 / 255.0).roundToInt()
        return if (kotlin.math.abs(after - target) <= 2) SkillResult.Done("Brillo al $target por ciento.")
        else SkillResult.Failed("El sistema no aceptó el cambio de brillo.")
    }

    private fun dnd(state: String): SkillResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        val active = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (state == "consultar") return SkillResult.Done(if (active) "No molestar está activado." else "No molestar está desactivado.")
        if (!nm.isNotificationPolicyAccessGranted) {
            return SkillResult.NeedsPermission(PermissionKind.DND_ACCESS, "Para eso necesito acceso a No molestar. Te dejé el botón.")
        }
        val on = state == "prender"
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        val now = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        return if (now == on) SkillResult.Done(if (on) "No molestar activado." else "No molestar desactivado.")
        else SkillResult.Failed("El sistema no aceptó el cambio.")
    }

    private val btAdapter get() = runCatching { context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter }.getOrNull()
    private fun btEnabled(): Boolean? = runCatching { btAdapter?.isEnabled }.getOrNull()

    /** Prendido o prendiéndose (mientras se prende, el interruptor aparece apagado: no hay que tocarlo). */
    private fun btOnOrTurningOn(): Boolean = runCatching {
        btAdapter?.state in setOf(android.bluetooth.BluetoothAdapter.STATE_ON, android.bluetooth.BluetoothAdapter.STATE_TURNING_ON)
    }.getOrDefault(false)

    /** Nombres de los dispositivos de audio conectados ahora (null si falta el permiso). */
    @Suppress("MissingPermission")
    private suspend fun connectedAudioNames(): Set<String>? {
        if (!btPermission()) return null
        val adapter = btAdapter ?: return null
        val names = mutableSetOf<String>()
        for (profile in listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)) {
            val proxy = withTimeoutOrNull(1_500) {
                suspendCancellableCoroutine<android.bluetooth.BluetoothProfile?> { cont ->
                    val ok = runCatching {
                        adapter.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
                            override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) { if (cont.isActive) cont.resume(proxy) }
                            override fun onServiceDisconnected(p: Int) = Unit
                        }, profile)
                    }.getOrDefault(false)
                    if (!ok && cont.isActive) cont.resume(null)
                }
            } ?: continue
            runCatching {
                proxy.connectedDevices.forEach { d ->
                    if (Build.VERSION.SDK_INT >= 30) runCatching { d.alias }.getOrNull()?.ifBlank { null }?.let(names::add)
                    runCatching { d.name }.getOrNull()?.ifBlank { null }?.let(names::add)
                }
            }
            runCatching { adapter.closeProfileProxy(profile, proxy) }
        }
        return names
    }

    private suspend fun isConnectedTo(name: String): Boolean? =
        connectedAudioNames()?.let { names -> AppMatcher.best(name, names.map { InstalledApp(it, it) }) != null }
    private fun btPermission() = Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    /** ¿Hay un dispositivo de audio Bluetooth conectado? (null si no se puede saber). */
    @Suppress("MissingPermission")
    private fun audioConnected(): Boolean? {
        if (!btPermission()) return null
        val adapter = btAdapter ?: return null
        return runCatching {
            listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)
                .any { adapter.getProfileConnectionState(it) == android.bluetooth.BluetoothProfile.STATE_CONNECTED }
        }.getOrNull()
    }

    /** Un dispositivo vinculado con el nombre que le pusiste en Ajustes › Bluetooth y el de fábrica. */
    private data class Paired(val shown: String, val factory: String?, val deviceClass: Int?)

    /**
     * Android deja renombrar los dispositivos vinculados («KP-1234-ABCD» → «Gol G3»): ese nombre
     * (alias) es el que se ve en Ajustes y el que usás vos; el de fábrica sirve de respaldo.
     */
    @Suppress("MissingPermission")
    private fun bonded(): List<Paired> {
        if (!btPermission()) return emptyList()
        return runCatching { btAdapter?.bondedDevices?.toList() }.getOrNull().orEmpty().mapNotNull { d ->
            val factory = runCatching { d.name }.getOrNull()?.ifBlank { null }
            val alias = if (Build.VERSION.SDK_INT >= 30) runCatching { d.alias }.getOrNull()?.ifBlank { null } else null
            val shown = alias ?: factory ?: return@mapNotNull null
            Paired(shown, factory, runCatching { d.bluetoothClass?.deviceClass }.getOrNull())
        }
    }

    private fun bondedNames(): List<String> = bonded().map { it.shown }

    /**
     * El dispositivo vinculado que nombraste, con el nombre que le pusiste o el de fábrica, aunque lo
     * digas distinto: «gol g3» / «gol ge tres» / «golg3» → «Gol G3»; «el auto» / «la radio» → el que
     * Android marca como equipo de auto; «los auriculares» / «el parlante» → el de ese tipo (si hay
     * uno solo). Devuelve el nombre que se ve en Ajustes › Bluetooth (el que hay que tocar).
     */
    private fun bondedName(query: String): String? {
        val devices = bonded()
        if (devices.isEmpty()) return null
        val wanted = spokenCompact(query)
        fun names(p: Paired) = listOfNotNull(p.shown, p.factory)
        devices.firstOrNull { p -> names(p).any { spokenCompact(it) == wanted } }?.let { return it.shown }
        if (wanted.length >= 3) devices.firstOrNull { p ->
            names(p).any { val have = spokenCompact(it); have.length >= 3 && (have.contains(wanted) || wanted.contains(have)) }
        }?.let { return it.shown }
        AppMatcher.best(query, devices.flatMap { p -> names(p).map { InstalledApp(it, p.shown) } })?.let { return it.id }
        val n = AppMatcher.normalize(query)
        val classes = when {
            Regex("\\b(auto|coche|carro|radio|estereo|stereo|camioneta)\\b").containsMatchIn(n) ->
                setOf(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
            Regex("\\b(auriculares|audifonos|cascos|headset)\\b").containsMatchIn(n) ->
                setOf(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET)
            Regex("\\b(parlante|bocina|altavoz|parlantito)\\b").containsMatchIn(n) ->
                setOf(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO)
            else -> return null
        }
        return devices.filter { it.deviceClass in classes }.singleOrNull()?.shown
    }

    /** «Gol ge tres» → «golg3»: números y letras dichas en palabras, sin espacios ni acentos. */
    private fun spokenCompact(text: String): String = AppMatcher.normalize(text).split(' ').joinToString("") { w -> SPOKEN[w] ?: w }

    private fun devicesList(): String = bondedNames().take(6).joinToString(", ").ifBlank { "ninguno" }

    private suspend fun bluetooth(state: String, device: String?): SkillResult {
        val enabled = btEnabled()
        return when (state) {
            "consultar" -> when {
                enabled == null -> SkillResult.Failed("No pude leer el Bluetooth.")
                !enabled -> SkillResult.Done("El Bluetooth está apagado.")
                audioConnected() == true -> SkillResult.Done("El Bluetooth está prendido y hay un dispositivo conectado.")
                else -> SkillResult.Done("El Bluetooth está prendido.")
            }
            "prender" -> when {
                enabled == true -> SkillResult.Done("El Bluetooth ya está prendido.")
                btOnOrTurningOn() -> if (waitUntil(8_000) { btEnabled() == true }) SkillResult.Done("Bluetooth prendido.") else SkillResult.Failed("No terminó de prenderse.")
                else -> autoToggle(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth", true) { btEnabled() == true }
            }
            "apagar" -> if (enabled == false) SkillResult.Done("El Bluetooth ya está apagado.") else autoToggle(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth", false) { btEnabled() == true }
            else -> device?.takeIf { it.isNotBlank() }?.let { connect(it) } ?: connectAny()
        }
    }

    /**
     * Conectarse a un dispositivo vinculado:
     * 1. Si ya está conectado, no toca nada.
     * 2. Si el Bluetooth está apagado, toca el interruptor UNA vez y espera a que termine de prenderse.
     * 3. Muchos auriculares se reconectan solos: se espera unos segundos antes de tocar.
     * 4. Recién si no se conectó, toca su nombre (nunca el interruptor ni otras filas).
     */
    /**
     * «Conectate a mi Bluetooth» (sin nombre): si ya hay algo conectado, listo; si no, prende el
     * Bluetooth y espera a que se reconecte solo el de siempre. Nunca toca un nombre adivinado.
     */
    private suspend fun connectAny(): SkillResult {
        connectedAudioNames()?.firstOrNull()?.let { return SkillResult.Done("Ya estás conectado a $it.") }
        if (btEnabled() != true) {
            val on = bluetooth("prender", null)
            if (on is SkillResult.Failed || on is SkillResult.NeedsPermission) return on
        }
        if (waitUntil(8_000) { audioConnected() == true }) {
            return SkillResult.Done("Conectado${connectedAudioNames()?.firstOrNull()?.let { " a $it" } ?: ""}.")
        }
        return SkillResult.Failed("Prendí el Bluetooth pero no se conectó solo. Decime a cuál: ${devicesList()}.")
    }

    private suspend fun connect(device: String): SkillResult {
        if (!com.sara.assistant.apps.SaraAccessibilityService.isEnabled(context)) {
            return SkillResult.NeedsPermission(PermissionKind.ACCESSIBILITY, "Para conectarme sola necesito Accesibilidad. Te dejé el botón.")
        }
        val clean = device.replace(Regex("(?i)\\b(bluetooth|blutu|los|las|el|la|mis|mi|de|al|a)\\b"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { device }
        // Sólo se toca un dispositivo que existe: si no lo reconozco, digo cuáles hay (no adivino).
        val name = bondedName(clean) ?: bondedName(device)
            ?: return if (btPermission()) SkillResult.Failed("No encontré «$clean» entre tus dispositivos. Tenés: ${devicesList()}.")
            else SkillResult.NeedsPermission(PermissionKind.BLUETOOTH, "Para ver tus dispositivos necesito el permiso de Bluetooth. Te dejé el botón.")
        if (btEnabled() == true && isConnectedTo(name) == true) return SkillResult.Done("Ya estás conectado a $name.")
        val wasConnected = audioConnected()
        val opened = open(starter, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth", "", "Tocá la notificación para abrir el Bluetooth.")
        if (opened !is SkillResult.Partial || opened.message.isNotEmpty()) return opened
        fun connectedNow(): Boolean = audioConnected() == true && wasConnected != true
        if (!btOnOrTurningOn()) {
            com.sara.assistant.apps.SaraAccessibilityService.setScreenSwitch(context, true)
        }
        if (!waitUntil(8_000) { btEnabled() == true }) {
            com.sara.assistant.apps.SaraAccessibilityService.back(context)
            return SkillResult.Failed("No pude prender el Bluetooth.")
        }
        // Reconexión automática (sin tocar nada).
        if (waitUntil(6_000) { connectedNow() } || isConnectedTo(name) == true) {
            com.sara.assistant.apps.SaraAccessibilityService.back(context)
            return SkillResult.Done("Conectado a $name.")
        }
        val tapped = com.sara.assistant.apps.SaraAccessibilityService.tapOnScreen(context, name)
            ?: return SkillResult.Failed("No encontré «$name» entre tus dispositivos vinculados.").also {
                com.sara.assistant.apps.SaraAccessibilityService.back(context)
            }
        waitUntil(10_000) { connectedNow() }
        val connected = isConnectedTo(name) ?: audioConnected()
        com.sara.assistant.apps.SaraAccessibilityService.back(context)
        return when {
            connected == true -> SkillResult.Done("Conectado a $tapped.")
            connected == null -> SkillResult.Done("Toqué $tapped en Bluetooth para conectarlo.", verified = false)
            else -> SkillResult.Failed("Toqué $tapped pero no se conectó. ¿Está prendido y cerca?")
        }
    }

    /** Prende/apaga desde Ajustes tocando el interruptor (con Accesibilidad); si no, abre la pantalla. */
    private suspend fun autoToggle(action: String, label: String, on: Boolean, isOn: () -> Boolean): SkillResult {
        if (!com.sara.assistant.apps.SaraAccessibilityService.isEnabled(context)) return panel(if (label == "Wi-Fi") Settings.Panel.ACTION_WIFI else action, label)
        val opened = open(starter, Intent(action), label, "", "Tocá la notificación para abrir el $label.")
        if (opened !is SkillResult.Partial || opened.message.isNotEmpty()) return opened
        val switched = com.sara.assistant.apps.SaraAccessibilityService.setScreenSwitch(context, on)
        waitUntil(5_000) { isOn() == on }
        com.sara.assistant.apps.SaraAccessibilityService.back(context)
        val word = if (on) "prendido" else "apagado"
        return when {
            isOn() == on -> SkillResult.Done("$label $word.")
            switched == true -> SkillResult.Done("Toqué el $label, no pude confirmar que quedó $word.", verified = false)
            else -> SkillResult.Failed("No pude cambiar el $label.")
        }
    }

    private suspend fun waitUntil(millis: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            delay(250)
        }
        return condition()
    }

    private suspend fun panel(action: String, label: String): SkillResult =
        open(starter, Intent(action), label, "Android no deja cambiar el $label solo: te abrí el panel.", "Tocá la notificación para abrir el $label.")
}

// =============================================================================================
// Llamadas y SMS
// =============================================================================================

class CallsSkill(private val context: Context, private val starter: ActivityStarter) : Skill {
    override val id = "llamadas"
    override val description = "Llamar a un contacto o número y preparar SMS."
    override val actions = listOf(
        SkillAction(
            "llamar", "Llama a un contacto o número.", listOf(Param("contacto", ParamType.STRING, "Nombre o número.")),
            Risk.SENSITIVE, setOf(PermissionKind.CONTACTS), "Abre la llamada en el marcador.",
            confirm = { "¿Llamo a ${it["contacto"]}?" },
        ),
        SkillAction(
            "sms", "Deja un SMS escrito para enviar.", listOf(Param("contacto", ParamType.STRING, "Nombre o número."), Param("mensaje", ParamType.STRING, "Texto.")),
            Risk.CHANGE, setOf(PermissionKind.CONTACTS),
        ),
    )

    private fun lookup(name: String): Pair<String, String>? {
        val digits = name.filter { it.isDigit() || it == '+' }
        if (digits.count(Char::isDigit) >= 6) return name to digits
        val phones = mutableListOf<InstalledApp>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) { val n = c.getString(0) ?: continue; val p = c.getString(1) ?: continue; phones += InstalledApp(n, p) } }
        return AppMatcher.best(name, phones.distinctBy { it.label })?.let { it.label to it.id }
    }

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val (name, number) = withContext(Dispatchers.IO) { lookup(args.getValue("contacto")) }
            ?: return SkillResult.Failed("No encontré a ${args.getValue("contacto")} en tus contactos.")
        return if (action == "llamar") {
            if (granted(context, Manifest.permission.CALL_PHONE)) {
                open(starter, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))), "la llamada", "Llamando a $name.", "Tocá la notificación para llamar a $name.")
            } else {
                open(starter, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), "el teléfono", "Te dejé marcado a $name, tocá llamar.", "Tocá la notificación para llamar a $name.")
            }
        } else {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", args.getValue("mensaje"))
            open(starter, intent, "los mensajes", "Te dejé el SMS a $name listo, tocá enviar.", "Tocá la notificación para mandar el SMS a $name.")
        }
    }
}

// =============================================================================================
// Calendario
// =============================================================================================

class CalendarSkill(private val context: Context, private val starter: ActivityStarter) : Skill {
    override val id = "calendario"
    override val description = "Eventos del calendario del teléfono (consultar y crear)."
    override val actions = listOf(
        SkillAction("ver", "Eventos de hoy o mañana.", listOf(Param("dia", ParamType.STRING, "Día.", required = false, options = listOf("hoy", "manana"))), permissions = setOf(PermissionKind.CALENDAR)),
        SkillAction(
            "crear", "Crea un evento (abre el calendario con los datos para guardarlo).",
            listOf(Param("titulo", ParamType.STRING, "Título."), Param("cuando", ParamType.STRING, "Fecha y hora ISO.")), Risk.CHANGE,
        ),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        return if (action == "ver") withContext(Dispatchers.IO) {
            val day = if (args["dia"] == "manana") LocalDate.now().plusDays(1) else LocalDate.now()
            val zone = ZoneId.systemDefault()
            val begin = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val events = mutableListOf<Pair<Long, String>>()
            CalendarContract.Instances.query(
                context.contentResolver, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), begin, end,
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: continue
                    val start = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    events += start to if (allDay) "$title (todo el día)" else "$title a las ${java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalTime().let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" }}"
                }
            }
            val label = if (args["dia"] == "manana") "Mañana" else "Hoy"
            if (events.isEmpty()) SkillResult.Done("$label no tenés eventos.")
            else SkillResult.Done("$label tenés ${events.size}: " + events.sortedBy { it.first }.take(6).joinToString("; ") { it.second } + ".")
        } else {
            val at = runCatching { LocalDateTime.parse(args.getValue("cuando")) }.getOrNull() ?: return SkillResult.Failed("No entendí cuándo.")
            val start = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, args.getValue("titulo"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000)
            open(starter, intent, "el calendario", "Te abrí el calendario con «${args.getValue("titulo")}» ${TimeParser.describe(at, LocalDateTime.now())}: tocá guardar.", "Tocá la notificación para guardar el evento.")
        }
    }
}

// =============================================================================================
// Mapas, lugares y ubicación
// =============================================================================================

class MapsSkill(private val starter: ActivityStarter, private val location: LocationProvider) : Skill {
    override val id = "mapas"
    override val description = "Navegación, lugares cercanos y dónde estoy."
    override val actions = listOf(
        SkillAction("navegar", "Inicia la navegación hacia un destino.", listOf(Param("destino", ParamType.STRING, "Dirección o lugar.")), Risk.CHANGE),
        SkillAction("cerca", "Busca lugares cercanos (farmacias, cafés…).", listOf(Param("lugar", ParamType.STRING, "Qué buscar.")), Risk.CHANGE),
        SkillAction("ubicacion", "Dice dónde estás.", permissions = setOf(PermissionKind.LOCATION)),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        return when (action) {
            "navegar" -> {
                val dest = args.getValue("destino")
                open(starter, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))), "el mapa", "Vamos a $dest.", "Tocá la notificación para ir a $dest.")
                    .let { if (it is SkillResult.Failed) open(starter, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "el mapa", "Te abrí $dest en el mapa.", "Tocá la notificación para ver $dest.") else it }
            }
            "cerca" -> {
                val what = args.getValue("lugar")
                open(starter, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(what))), "el mapa", "Te muestro $what cerca.", "Tocá la notificación para ver $what cerca.")
            }
            else -> {
                val loc = location.current() ?: return SkillResult.Failed("No pude obtener tu ubicación. Revisá que esté activada.")
                val name = location.placeName(loc)
                if (name != null) SkillResult.Done("Estás en $name.") else SkillResult.Done("Estás en ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}.")
            }
        }
    }
}

// =============================================================================================
// Notificaciones (sólo cuando se piden; no se guardan)
// =============================================================================================

class NotificationsSkill(private val context: Context) : Skill {
    override val id = "notificaciones"
    override val description = "Lee las notificaciones pendientes (todas o de una app) y las borra."
    private val appParam = Param("app", ParamType.STRING, "App (WhatsApp, Gmail…); vacío = todas.", required = false)
    override val actions = listOf(
        SkillAction("leer", "Lee las notificaciones: app, quién y qué dice.", listOf(appParam), permissions = setOf(PermissionKind.NOTIFICATION_ACCESS)),
        SkillAction("borrar", "Borra las notificaciones (todas o de una app).", listOf(appParam), risk = Risk.CHANGE, permissions = setOf(PermissionKind.NOTIFICATION_ACCESS)),
    )

    /** Una notificación ya lista para decir. */
    data class Item(val key: String, val packageName: String, val app: String, val title: String, val lines: List<String>, val clearable: Boolean)

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val listener = SaraNotificationListener.instance
            ?: return SkillResult.NeedsPermission(PermissionKind.NOTIFICATION_ACCESS, "Necesito el acceso a notificaciones.")
        val wanted = args["app"]?.takeIf { it.isNotBlank() }
        val all = current(context, listener)
        val items = if (wanted == null) all else all.filter { matchesApp(it, wanted) }
        val where = wanted?.let { " de ${items.firstOrNull()?.app ?: it.replaceFirstChar { c -> c.uppercase() }}" }.orEmpty()
        return when (action) {
            "borrar" -> {
                if (wanted == null) runCatching { listener.cancelAllNotifications() }
                else items.forEach { runCatching { listener.cancelNotification(it.key) } }
                // Se comprueba que se fueron (las fijas, como la música, no se pueden borrar).
                kotlinx.coroutines.delay(300)
                val left = current(context, listener).count { it.clearable && (wanted == null || matchesApp(it, wanted)) }
                when {
                    items.isEmpty() -> SkillResult.Done("No tenías notificaciones$where.")
                    left == 0 -> SkillResult.Done("Listo, borré las notificaciones$where.")
                    else -> SkillResult.Done("Borré algunas; quedan $left que no se pueden borrar.", verified = false)
                }
            }
            else -> {
                if (items.isEmpty()) return SkillResult.Done("No tenés notificaciones$where.")
                val spoken = items.take(MAX_READ).joinToString(" ") { describe(it, showApp = wanted == null) }
                val more = if (items.size > MAX_READ) " Y ${items.size - MAX_READ} más." else ""
                val count = if (items.size == 1) "Tenés una notificación$where." else "Tenés ${items.size} notificaciones$where."
                SkillResult.Done("$count $spoken$more")
            }
        }
    }

    companion object {
        private const val MAX_READ = 6
        private const val MAX_LINES = 4

        fun matchesApp(item: Item, wanted: String): Boolean {
            val w = com.sara.core.interpreter.TextNormalizer.normalize(wanted).replace(" ", "")
            val app = com.sara.core.interpreter.TextNormalizer.normalize(item.app).replace(" ", "")
            return app.contains(w) || w.contains(app) || item.packageName.contains(w)
        }

        private fun describe(item: Item, showApp: Boolean): String {
            val who = if (showApp) "${item.app}, ${item.title}" else item.title
            val body = item.lines.takeLast(MAX_LINES).joinToString(". ") { it.trim().trimEnd('.') }
            return if (body.isBlank()) "$who." else "$who: $body."
        }

        /** Notificaciones visibles, sin resúmenes de grupo ni las fijas, con el texto completo de cada una. */
        fun current(context: Context, listener: android.service.notification.NotificationListenerService): List<Item> {
            val pm = context.packageManager
            val raw = runCatching { listener.activeNotifications.toList() }.getOrDefault(emptyList())
                .filter { it.packageName != context.packageName && !it.isOngoing }
            // Si una app tiene notificaciones sueltas, su «resumen» (p. ej. «5 mensajes de 2 chats») sobra.
            val withChildren = raw.filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }.map { it.packageName }.toSet()
            return raw.filter { sbn -> sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 || sbn.packageName !in withChildren }
                .sortedByDescending { it.postTime }
                .mapNotNull { sbn ->
                    val n = sbn.notification
                    val extras = n.extras
                    val title = (extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)
                        ?: extras.getCharSequence(android.app.Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
                    val lines = mutableListOf<String>()
                    // Mensajería (WhatsApp, Telegram…): cada mensaje con quién lo mandó.
                    val messages = runCatching {
                        android.app.Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                            extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES),
                        )
                    }.getOrNull().orEmpty()
                    val isGroup = extras.getBoolean(android.app.Notification.EXTRA_IS_GROUP_CONVERSATION)
                    for (m in messages) {
                        val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                        val sender = m.senderPerson?.name?.toString()
                        lines += if (isGroup && sender != null) "$sender dice $text" else text
                    }
                    if (lines.isEmpty()) {
                        extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.mapNotNullTo(lines) { it?.toString() }
                    }
                    if (lines.isEmpty()) {
                        (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
                            ?.toString()?.takeIf { it.isNotBlank() }?.let { lines += it }
                    }
                    if (title.isBlank() && lines.isEmpty()) return@mapNotNull null
                    val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                    Item(sbn.key, sbn.packageName, app, title.ifBlank { app }, lines.map { it.replace(Regex("\\s+"), " ").take(300) }, sbn.isClearable)
                }
                .distinctBy { it.app + it.title + it.lines.joinToString() }
        }
    }
}

// =============================================================================================
// IA: búsqueda en Google y traducción
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

// =============================================================================================
// Hogar inteligente (no hay API pública para controlar Google Home desde otra app)
// =============================================================================================

class SmartHomeSkill(private val context: Context, private val starter: ActivityStarter) : Skill {
    override val id = "hogar"
    override val description = "Hogar inteligente: abre la app de Google Home (no hay control directo disponible)."
    override val actions = listOf(SkillAction("controlar", "Abre la app del hogar para hacer el pedido.", listOf(Param("pedido", ParamType.STRING, "Qué querés hacer.")), Risk.CHANGE))

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val launch = HOME_APPS.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
            ?: return SkillResult.Failed("Todavía no puedo controlar tu casa: Google no permite que otras apps manejen los dispositivos. Instalá Google Home y lo abro para que lo hagas ahí.")
        return open(starter, launch, "Google Home", "No puedo manejar tus dispositivos directamente: te abrí Google Home.", "Tocá la notificación para abrir Google Home.")
    }

    private companion object {
        val HOME_APPS = listOf("com.google.android.apps.chromecast.app", "io.homeassistant.companion.android", "com.xiaomi.smarthome", "com.tuya.smartlife")
    }
}

// =============================================================================================
// Diagnóstico
// =============================================================================================

class DiagnosticsSkill(
    private val missing: () -> List<PermissionKind>,
    private val describe: (PermissionKind) -> String,
    private val checks: suspend () -> List<String>,
) : Skill {
    override val id = "diagnostico"
    override val description = "Revisa permisos, conexión, IA y servicios de Sara."
    override val actions = listOf(SkillAction("revisar", "Informa qué falta o qué está fallando."))

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val problems = missing().map { "falta ${describe(it)}" } + checks()
        return if (problems.isEmpty()) SkillResult.Done("Todo en orden.") else SkillResult.Done("Encontré esto: " + problems.joinToString("; ") + ".")
    }
}
