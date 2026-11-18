package com.sara.lucy.data

import com.sara.core.ai.AiProvider
import com.sara.core.assistant.Acknowledgement
import com.sara.core.assistant.CommandHistory
import com.sara.core.memory.MemoryStore
import com.sara.core.model.HistoryEntry
import com.sara.core.skills.TextStore
import com.sara.core.util.SaraLogger
import com.sara.core.util.WakeWordTuning
import com.sara.lucy.win.LucyPaths
import com.sara.lucy.win.WinBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Escribe primero en un temporal y después reemplaza: si se corta la luz no queda un archivo roto. */
private fun File.writeSafely(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(this)) {
        delete()
        tmp.renameTo(this)
    }
}

private fun File.readJson(): JSONObject? = runCatching { if (exists()) JSONObject(readText(Charsets.UTF_8)) else null }.getOrNull()

/** Texto clave → valor en un archivo JSON (listas, recordatorios, rutinas, uso de música). */
class FileTextStore(private val file: File) : TextStore {
    private val map: MutableMap<String, String> = file.readJson()?.let { j -> j.keySet().associateWith { j.getString(it) }.toMutableMap() } ?: mutableMapOf()

    @Synchronized override fun get(key: String): String? = map[key]

    @Synchronized override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
        file.writeSafely(JSONObject(map as Map<*, *>).toString(1))
    }
}

/** Lo que Lucy recuerda del usuario. */
class FileMemoryStore(private val file: File) : MemoryStore {
    private val map: LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        file.readJson()?.let { j -> j.keySet().forEach { put(it, j.getString(it)) } }
    }

    @Synchronized override fun all(): Map<String, String> = map.toMap()

    @Synchronized override fun put(key: String, value: String) {
        map[key] = value
        save()
    }

    @Synchronized override fun remove(key: String): Boolean = (map.remove(key) != null).also { if (it) save() }

    private fun save() = file.writeSafely(JSONObject(map as Map<*, *>).toString(1))
}

/** Historial de órdenes (últimas 200), para la pestaña Historial. */
class FileCommandHistory(private val file: File, private val enabled: () -> Boolean) : CommandHistory {
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        _entries.value = runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                HistoryEntry(it.getLong("t"), it.getString("heard"), it.getString("intent"), it.getString("response"), it.getBoolean("ok"))
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun record(entry: HistoryEntry) {
        if (!enabled()) return
        val list = (listOf(entry) + _entries.value).take(200)
        _entries.value = list
        runCatching {
            file.writeSafely(JSONArray(list.map {
                JSONObject().put("t", it.timestampMillis).put("heard", it.heard).put("intent", it.intentLabel).put("response", it.response).put("ok", it.success)
            }).toString())
        }
    }

    fun clear() {
        _entries.value = emptyList()
        file.delete()
    }
}

/** Claves de IA cifradas con DPAPI de Windows (sólo tu usuario de Windows las puede leer). */
class SecretStore(private val file: File, private val bridge: WinBridge, private val logger: SaraLogger) {
    private val cache = mutableMapOf<String, String?>()

    @Synchronized fun get(name: String): String? {
        if (cache.containsKey(name)) return cache[name]
        val sealed = file.readJson()?.optString(name)?.ifBlank { null }
        val value = sealed?.let { runCatching { bridge.call("unprotect", mapOf("text" to it)) as? String }.onFailure { e -> logger.w(TAG, "No pude descifrar $name", e) }.getOrNull() }
        cache[name] = value
        return value
    }

    @Synchronized fun put(name: String, value: String?) {
        val json = file.readJson() ?: JSONObject()
        if (value.isNullOrBlank()) json.remove(name)
        else json.put(name, bridge.call("protect", mapOf("text" to value.trim())) as String)
        file.writeSafely(json.toString(1))
        cache[name] = value?.trim()?.ifBlank { null }
    }

    companion object {
        private const val TAG = "Secrets"
        const val GEMINI_KEY = "gemini"
        const val ANTHROPIC_KEY = "anthropic"
    }
}

/** Ajustes de Lucy. Los mismos que en el teléfono que tienen sentido en la computadora. */
data class LucySettings(
    val wakeWordEnabled: Boolean = true,
    val sensitivity: Float = WakeWordTuning.DEFAULT_SENSITIVITY,
    /** «Ey Lucy» (true) o «Lucy» sola (false, como Sara por defecto). */
    val heyPrefix: Boolean = false,
    val acknowledgement: Acknowledgement = Acknowledgement.VOICE,
    val voiceId: String? = null,
    val speechRate: Float = 1.05f,
    val voiceVolume: Float = 1f,
    val speechPauseMillis: Int = 1_700,
    val continuousConversation: Boolean = false,
    val aiProvider: AiProvider = AiProvider.GOOGLE,
    /** Mandar el audio de cada orden a Google para entenderla mejor (si hay clave). */
    val preciseRecognition: Boolean = true,
    val duckMusic: Boolean = true,
    /** Cancelación de eco de Windows: la música de la compu no la despierta ni tapa tu voz. */
    val echoCancellation: Boolean = true,
    val sphereVisible: Boolean = true,
    val sphereAlways: Boolean = false,
    val sphereSize: Int = 170,
    val sphereX: Int? = null,
    val sphereY: Int? = null,
    val hotkey: Boolean = true,
    val saveHistory: Boolean = true,
    /** Ciudad para el clima si no se dice otra (vacía = se estima por Internet). */
    val city: String = "",
    val checkUpdates: Boolean = true,
    val startMinimized: Boolean = true,
    /** Voz de Piper (gratis, sin Internet, la misma de Android) o la voz de Windows. */
    val usePiper: Boolean = true,
    val piperVoice: com.sara.core.voice.PiperVoice = com.sara.core.voice.PiperVoice.DANIELA,
    /** Hace las órdenes completas sin esperar la pausa de fin de frase. */
    val quickResponse: Boolean = true,
    /** Después de una pregunta sigue escuchando para charlar (después de una orden se calla). */
    val chatFollowUp: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("wakeWordEnabled", wakeWordEnabled).put("sensitivity", sensitivity.toDouble()).put("heyPrefix", heyPrefix)
        .put("acknowledgement", acknowledgement.name).put("voiceId", voiceId ?: "").put("speechRate", speechRate.toDouble())
        .put("voiceVolume", voiceVolume.toDouble()).put("speechPauseMillis", speechPauseMillis).put("continuousConversation", continuousConversation)
        .put("aiProvider", aiProvider.name).put("preciseRecognition", preciseRecognition).put("duckMusic", duckMusic).put("echoCancellation", echoCancellation)
        .put("sphereVisible", sphereVisible).put("sphereAlways", sphereAlways).put("sphereSize", sphereSize)
        .put("sphereX", sphereX ?: JSONObject.NULL).put("sphereY", sphereY ?: JSONObject.NULL).put("hotkey", hotkey)
        .put("saveHistory", saveHistory).put("city", city).put("checkUpdates", checkUpdates).put("startMinimized", startMinimized)
        .put("usePiper", usePiper).put("piperVoice", piperVoice.name).put("quickResponse", quickResponse).put("chatFollowUp", chatFollowUp)

    companion object {
        fun fromJson(j: JSONObject): LucySettings {
            val d = LucySettings()
            return LucySettings(
                wakeWordEnabled = j.optBoolean("wakeWordEnabled", d.wakeWordEnabled),
                sensitivity = j.optDouble("sensitivity", d.sensitivity.toDouble()).toFloat(),
                heyPrefix = j.optBoolean("heyPrefix", d.heyPrefix),
                acknowledgement = runCatching { Acknowledgement.valueOf(j.optString("acknowledgement")) }.getOrDefault(d.acknowledgement),
                voiceId = j.optString("voiceId").ifBlank { null },
                speechRate = j.optDouble("speechRate", d.speechRate.toDouble()).toFloat(),
                voiceVolume = j.optDouble("voiceVolume", d.voiceVolume.toDouble()).toFloat(),
                speechPauseMillis = j.optInt("speechPauseMillis", d.speechPauseMillis),
                continuousConversation = j.optBoolean("continuousConversation", d.continuousConversation),
                aiProvider = runCatching { AiProvider.valueOf(j.optString("aiProvider")) }.getOrDefault(d.aiProvider),
                preciseRecognition = j.optBoolean("preciseRecognition", d.preciseRecognition),
                duckMusic = j.optBoolean("duckMusic", d.duckMusic),
                echoCancellation = j.optBoolean("echoCancellation", d.echoCancellation),
                sphereVisible = j.optBoolean("sphereVisible", d.sphereVisible),
                sphereAlways = j.optBoolean("sphereAlways", d.sphereAlways),
                sphereSize = j.optInt("sphereSize", d.sphereSize).coerceIn(90, 400),
                sphereX = if (j.isNull("sphereX") || !j.has("sphereX")) null else j.getInt("sphereX"),
                sphereY = if (j.isNull("sphereY") || !j.has("sphereY")) null else j.getInt("sphereY"),
                hotkey = j.optBoolean("hotkey", d.hotkey),
                saveHistory = j.optBoolean("saveHistory", d.saveHistory),
                city = j.optString("city", d.city),
                checkUpdates = j.optBoolean("checkUpdates", d.checkUpdates),
                startMinimized = j.optBoolean("startMinimized", d.startMinimized),
                usePiper = j.optBoolean("usePiper", d.usePiper),
                piperVoice = runCatching { com.sara.core.voice.PiperVoice.valueOf(j.optString("piperVoice")) }.getOrDefault(d.piperVoice),
                quickResponse = j.optBoolean("quickResponse", d.quickResponse),
                chatFollowUp = j.optBoolean("chatFollowUp", d.chatFollowUp),
            )
        }
    }
}

class SettingsRepository(private val file: File = File(LucyPaths.home, "settings.json")) {
    private val _settings = MutableStateFlow(file.readJson()?.let { LucySettings.fromJson(it) } ?: LucySettings())
    val settings: StateFlow<LucySettings> = _settings.asStateFlow()
    val current: LucySettings get() = _settings.value

    @Synchronized fun update(change: (LucySettings) -> LucySettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        runCatching { file.writeSafely(next.toJson().toString(1)) }
    }
}

/** Registro de diagnóstico (últimas 500 líneas en memoria y en %APPDATA%\Lucy\lucy.log). */
class DiagnosticsLog(private val file: File = File(LucyPaths.home, "lucy.log")) : SaraLogger {
    private val lines = ArrayDeque<String>()
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    init {
        runCatching { if (file.length() > 1_000_000) file.delete() }
    }

    override fun d(tag: String, message: String) = add("D", tag, message, null)
    override fun w(tag: String, message: String, error: Throwable?) = add("W", tag, message, error)

    @Synchronized private fun add(level: String, tag: String, message: String, error: Throwable?) {
        val line = "${LocalDateTime.now().format(time)} $level/$tag: $message" + (error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        lines.addLast(line)
        while (lines.size > 500) lines.removeFirst()
        runCatching { file.appendText(line + "\n", Charsets.UTF_8) }
        if (System.getProperty("lucy.verbose") != null) println(line)
    }

    @Synchronized fun recent(): List<String> = lines.toList()
}
