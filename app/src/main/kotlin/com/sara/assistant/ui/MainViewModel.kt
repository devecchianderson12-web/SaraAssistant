package com.sara.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sara.assistant.container
import com.sara.assistant.data.SecretStore
import com.sara.assistant.settings.AiProvider
import com.sara.assistant.services.SaraListeningService
import com.sara.assistant.settings.SaraSettings
import com.sara.assistant.settings.WakeEngineType
import com.sara.core.engine.Availability
import com.sara.core.engine.SpeakResult
import com.sara.core.engine.VoiceOption
import com.sara.core.model.AssistantEvent
import com.sara.core.model.Trigger
import com.sara.core.permissions.PermissionSnapshot
import com.sara.core.permissions.StartContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Conversation(
    val heard: String = "",
    val response: String = "",
    val notice: String = "",
    /** Permiso que pidió la última orden (se muestra con su botón arriba de todo). */
    val permission: com.sara.core.model.PermissionKind? = null,
)

data class EngineStatus(val name: String, val detail: String, val ok: Boolean)

data class SystemStatus(
    val permissions: PermissionSnapshot? = null,
    val defaultAssistant: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val engines: List<EngineStatus> = emptyList(),
    val azureConfigured: Boolean = false,
    val porcupineKey: Boolean = false,
    val porcupineKeyword: Boolean = false,
    val porcupineModel: Boolean = false,
    val canOpenAppsFromBackground: Boolean = false,
    /** App de música que Sara usa por defecto (la que más usa el usuario). */
    val musicApp: String? = null,
    val canCloseApps: Boolean = false,
    /** Permisos que faltan, cada uno con su botón en Inicio. */
    val missing: List<com.sara.core.model.PermissionKind> = emptyList(),
    val aiConfigured: Boolean = false,
    val googleKey: Boolean = false,
    val claudeKey: Boolean = false,
    /** Micrófono de las órdenes ahora y cómo escucha cada uno. */
    val micCurrent: String = "",
    val micQuality: String = "",
    val bluetoothMic: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    val state = c.orchestrator.state
    val level = c.levels.level
    val settings: StateFlow<SaraSettings> = c.settings.settings
    val history = c.history.entries
    val modelState = c.voskModels.state
    val piperState = c.piperModels.state
    val serviceRunning = SaraListeningService.running
    val micActive = c.microphone.active
    val micSilenced = c.microphone.silenced
    val diagnostics = c.logger.lines
    val updateState = c.updates.state

    private val _conversation = MutableStateFlow(Conversation())
    val conversation: StateFlow<Conversation> = _conversation.asStateFlow()

    private val _system = MutableStateFlow(SystemStatus())
    val system: StateFlow<SystemStatus> = _system.asStateFlow()

    private val _voices = MutableStateFlow<Map<Boolean, List<VoiceOption>>>(emptyMap())
    val voices: StateFlow<Map<Boolean, List<VoiceOption>>> = _voices.asStateFlow()

    // Rutinas programadas y recordatorios (se editan desde Ajustes).
    private val _routines = MutableStateFlow(c.scheduler.routines())
    val routines: StateFlow<List<com.sara.assistant.skills.ScheduledRoutine>> = _routines.asStateFlow()
    private val _reminders = MutableStateFlow(c.scheduler.reminders())
    val reminders: StateFlow<List<com.sara.assistant.skills.Reminder>> = _reminders.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            c.orchestrator.events.collect { event ->
                when (event) {
                    is AssistantEvent.Activated -> _conversation.value = Conversation()
                    is AssistantEvent.Heard -> _conversation.update { it.copy(heard = event.text) }
                    is AssistantEvent.Responded -> _conversation.update { it.copy(response = event.text) }
                    is AssistantEvent.Notice -> _conversation.update { it.copy(notice = event.text) }
                    is AssistantEvent.Failed -> _conversation.update { it.copy(notice = event.error.message) }
                    is AssistantEvent.PermissionNeeded -> {
                        _conversation.update { it.copy(permission = event.kind) }
                        refreshSystem()
                    }
                }
            }
        }
        viewModelScope.launch { c.secrets.version.collect { refreshSystem() } }
    }

    fun activate() = c.orchestrator.activate(Trigger.MANUAL)
    fun cancel() = c.orchestrator.cancelInteraction()
    fun consumeMessage() { _message.value = null }

    /** Se llama al volver a la app: refresca permisos y retoma la escucha si estaba activada. */
    fun onResume() {
        refreshSystem()
        viewModelScope.launch {
            val pending = c.updates.state.value
            // Si volvió de habilitar "instalar apps desconocidas", sigue con la actualización.
            if (pending is com.sara.assistant.update.UpdateState.NeedsPermission && c.updates.canInstall()) {
                c.updates.downloadAndInstall(pending.release)
            } else {
                c.updates.check()
            }
        }
        viewModelScope.launch {
            val s = c.settings.current()
            if (s.wakeWordEnabled && !SaraListeningService.running.value && c.microphone.hasPermission()) {
                SaraListeningService.start(getApplication(), StartContext.VISIBLE_ACTIVITY)
            }
        }
    }

    fun setWakeWord(enabled: Boolean) = viewModelScope.launch {
        c.settings.update { it.copy(wakeWordEnabled = enabled) }
        if (enabled) {
            if (c.settings.current().wakeEngine == WakeEngineType.VOSK && !c.voskModels.isInstalled()) {
                _message.value = "Primero descargá el modelo de voz para que Sara reconozca su nombre."
            }
            SaraListeningService.start(getApplication(), StartContext.VISIBLE_ACTIVITY)
        } else {
            SaraListeningService.stop(getApplication())
        }
    }

    fun update(transform: (SaraSettings) -> SaraSettings) = viewModelScope.launch {
        val before = c.settings.current()
        c.settings.update(transform)
        val after = c.settings.current()
        // Reinicia la detección si cambió el motor o la sensibilidad.
        if (after.wakeWordEnabled && (before.wakeEngine != after.wakeEngine || before.sensitivity != after.sensitivity || before.heySara != after.heySara || before.listenOnlyWithScreenOn != after.listenOnlyWithScreenOn) && SaraListeningService.running.value) {
            c.orchestrator.setWakeWordEnabled(false)
            c.orchestrator.setWakeWordEnabled(true)
        }
        refreshSystem()
    }

    fun downloadModel() = viewModelScope.launch {
        if (c.voskModels.download()) restartDetectionIfEnabled()
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        if (c.voskModels.importZip(uri)) restartDetectionIfEnabled()
    }

    fun deleteModel() = viewModelScope.launch { c.voskModels.delete() }

    private fun restartDetectionIfEnabled() {
        if (c.settings.settings.value.wakeWordEnabled) {
            if (SaraListeningService.running.value) {
                c.orchestrator.setWakeWordEnabled(false)
                c.orchestrator.setWakeWordEnabled(true)
            } else {
                SaraListeningService.start(getApplication(), StartContext.VISIBLE_ACTIVITY)
            }
        }
        refreshSystem()
    }

    fun checkUpdates() = viewModelScope.launch { c.updates.check(force = true) }

    private val _micCompare = MutableStateFlow<String?>(null)
    /** Resultado de «Comparar micrófonos» (null = no se está mostrando). */
    val micCompare: StateFlow<String?> = _micCompare.asStateFlow()

    fun compareMicrophones() = viewModelScope.launch {
        _micCompare.value = "Hablá sin parar unos 6 segundos (por ejemplo, contá del 1 al 20)…"
        _micCompare.value = runCatching { c.compareMicrophones() }.getOrElse { "No pude comparar: ${it.message}" }
        refreshSystem()
    }

    fun dismissMicCompare() { _micCompare.value = null }

    fun downloadPiper(force: Boolean) = c.ensurePiper(force)
    fun dismissUpdate() = c.updates.dismiss()
    fun installUpdate(release: com.sara.assistant.update.ReleaseInfo) = viewModelScope.launch { c.updates.downloadAndInstall(release) }

    fun clearHistory() = viewModelScope.launch { c.history.clear() }

    val memories = c.memory.entries
    fun forget(key: String) { c.memory.remove(key) }


    fun refreshSchedule() {
        _routines.value = c.scheduler.routines()
        _reminders.value = c.scheduler.reminders().sortedBy { it.at }
    }

    fun toggleRoutine(r: com.sara.assistant.skills.ScheduledRoutine) {
        c.scheduler.updateRoutine(r.copy(enabled = !r.enabled))
        refreshSchedule()
    }

    fun deleteRoutine(r: com.sara.assistant.skills.ScheduledRoutine) {
        c.scheduler.removeRoutine(r.id)
        refreshSchedule()
    }

    fun deleteReminder(r: com.sara.assistant.skills.Reminder) {
        c.scheduler.removeReminder(r.id)
        refreshSchedule()
    }

    private fun keyName(provider: AiProvider) =
        if (provider == AiProvider.GOOGLE) SecretStore.GEMINI_KEY else SecretStore.ANTHROPIC_KEY

    /** Guarda la clave (si se escribió una) y deja elegida esa IA. */
    fun saveAiKey(provider: AiProvider, key: String?) = viewModelScope.launch {
        if (!key.isNullOrBlank()) c.secrets.put(keyName(provider), key.trim())
        c.settings.update { it.copy(aiProvider = provider) }
        c.ai.reset()
        refreshSystem()
    }

    fun clearAiKey(provider: AiProvider) {
        c.secrets.put(keyName(provider), null)
        c.ai.reset()
        refreshSystem()
    }
    fun clearDiagnostics() = c.logger.clear()

    fun testVoice() = viewModelScope.launch {
        val result = c.orchestrator.speakPreview("Hola, soy Sara. Así suena mi voz con esta configuración.")
        if (result is SpeakResult.Failure) _message.value = "No pude hablar: ${result.error.message}"
    }

    fun loadVoices(online: Boolean) = viewModelScope.launch {
        val engine = if (online) c.engines.onlineVoice() else c.engines.offlineVoice()
        val list = runCatching { engine?.voices(c.settings.settings.value.language).orEmpty() }.getOrDefault(emptyList())
        _voices.update { it + (online to list) }
        if (list.isEmpty()) _message.value = "No encontré voces ${if (online) "online (revisá credenciales y conexión)" else "instaladas en español"}."
    }

    fun saveAzure(key: String?, region: String) = viewModelScope.launch {
        if (key != null) c.secrets.put(SecretStore.AZURE_KEY, key)
        c.settings.update { it.copy(azureRegion = region.trim().lowercase()) }
        _voices.update { it - true }
        refreshSystem()
    }

    fun clearAzure() {
        c.secrets.put(SecretStore.AZURE_KEY, null)
        refreshSystem()
    }

    fun savePorcupineKey(key: String?) {
        c.secrets.put(SecretStore.PORCUPINE_ACCESS_KEY, key)
        refreshSystem()
    }

    fun importPorcupine(uri: Uri, keyword: Boolean) = viewModelScope.launch {
        val target = if (keyword) c.porcupineFiles.keyword else c.porcupineFiles.model
        _message.value = if (c.porcupineFiles.import(uri, target)) "Archivo importado." else "No se pudo importar el archivo."
        refreshSystem()
    }

    fun refreshSystem() = viewModelScope.launch {
        refreshSchedule()
        val engines = c.engines
        suspend fun status(name: String, availability: suspend () -> Availability): EngineStatus {
            val a = runCatching { availability() }.getOrElse { Availability.Unavailable(com.sara.core.engine.UnavailableReason.NOT_SUPPORTED, it.message ?: "error") }
            return if (a is Availability.Unavailable) EngineStatus(name, a.detail, false) else EngineStatus(name, "Disponible", true)
        }
        val wake = engines.wakeWordEngine()
        val online = engines.onlineRecognizer()
        val offline = engines.offlineRecognizer()
        val onlineVoice = engines.onlineVoice()
        val offlineVoice = engines.offlineVoice()
        val list = listOf(
            status("Activación · ${wake?.displayName}") { wake!!.availability() },
            status("Reconocimiento online · ${online?.displayName}") { online!!.availability() },
            status("Reconocimiento offline · ${offline?.displayName}") { offline!!.availability() },
            status("Voz online · ${onlineVoice?.displayName}") { onlineVoice!!.availability() },
            status("Voz offline · ${offlineVoice?.displayName}") { offlineVoice!!.availability() },
        )
        _system.value = SystemStatus(
            permissions = c.permissionSnapshot(),
            defaultAssistant = c.isDefaultAssistant(),
            batteryUnrestricted = c.isIgnoringBatteryOptimizations(),
            engines = list + EngineStatus("Conexión a Internet", if (c.connectivity.isOnline()) "Conectado" else "Sin conexión", c.connectivity.isOnline()),
            azureConfigured = c.azure.isConfigured(),
            porcupineKey = c.secrets.has(SecretStore.PORCUPINE_ACCESS_KEY),
            porcupineKeyword = c.porcupineFiles.keyword.isFile,
            porcupineModel = c.porcupineFiles.model.isFile,
            canOpenAppsFromBackground = android.provider.Settings.canDrawOverlays(getApplication()),
            canCloseApps = com.sara.assistant.apps.SaraAccessibilityService.isEnabled(getApplication()),
            missing = com.sara.assistant.permissions.PermissionCenter.missing(getApplication()),
            aiConfigured = c.ai.isAvailable(),
            googleKey = c.secrets.has(SecretStore.GEMINI_KEY),
            claudeKey = c.secrets.has(SecretStore.ANTHROPIC_KEY),
            musicApp = runCatching { c.mediaControl.preferredMusicApp()?.label }.getOrNull(),
            micCurrent = c.micRouter.currentLabel(),
            micQuality = c.micRouter.describeQuality(),
            bluetoothMic = c.micRouter.bluetoothMic() != null,
        )
    }
}
