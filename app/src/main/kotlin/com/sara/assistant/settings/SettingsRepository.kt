package com.sara.assistant.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sara.core.assistant.Acknowledgement
import com.sara.core.engine.EngineMode
import com.sara.core.util.WakeWordTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class WakeEngineType { VOSK, PORCUPINE }
enum class OnlineRecognizerType { SYSTEM, AZURE }
enum class OfflineRecognizerType { AUTO, VOSK, ANDROID_ON_DEVICE }
enum class OnlineVoiceType { AZURE, SYSTEM_NETWORK }
/** IA para preguntas: Google (Gemini con búsqueda de Google) o Claude. */
enum class AiProvider { GOOGLE, CLAUDE }

data class SaraSettings(
    // Activación
    val wakeWordEnabled: Boolean = false,
    val manualActivationEnabled: Boolean = true,
    val wakeEngine: WakeEngineType = WakeEngineType.VOSK,
    val sensitivity: Float = WakeWordTuning.DEFAULT_SENSITIVITY,
    val acknowledgement: Acknowledgement = Acknowledgement.VOICE,
    val autostart: Boolean = false,
    val echoCancellation: Boolean = true,
    /** Silencio tolerado dentro de una orden (pausas al hablar). */
    val speechPauseMillis: Int = 1_700,
    // Voz
    val voiceMode: EngineMode = EngineMode.AUTOMATIC,
    val onlineVoice: OnlineVoiceType = OnlineVoiceType.AZURE,
    val onlineVoiceId: String? = null,
    val offlineVoiceId: String? = null,
    val speechRate: Float = 1.0f,
    val voiceVolume: Float = 1.0f,
    // Reconocimiento
    val recognitionMode: EngineMode = EngineMode.AUTOMATIC,
    val onlineRecognizer: OnlineRecognizerType = OnlineRecognizerType.SYSTEM,
    val offlineRecognizer: OfflineRecognizerType = OfflineRecognizerType.AUTO,
    val allowRecognitionFallback: Boolean = true,
    val language: String = "es-AR",
    // Privacidad / proveedores
    val saveHistory: Boolean = true,
    val azureRegion: String = "",
    // Mensajes
    /** Leer el mensaje y esperar un «sí» antes de mandarlo. */
    val confirmMessages: Boolean = true,
    val aiProvider: AiProvider = AiProvider.GOOGLE,
    /** Seguir escuchando unos segundos después de responder (charla sin repetir «Sara»). */
    val continuousConversation: Boolean = true,
    /** Activación con «Ey Sara» (true, menos falsas activaciones) o con «Sara» sola. */
    val heySara: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "sara_settings")

/** Ajustes persistentes (DataStore). No guarda credenciales: ver [SecretStore]. */
class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore
    private val _settings = MutableStateFlow(SaraSettings())
    val settings: StateFlow<SaraSettings> = _settings.asStateFlow()

    init {
        scope.launch { store.data.map { it.toSettings() }.collect { _settings.value = it } }
    }

    suspend fun current(): SaraSettings = store.data.first().toSettings().also { _settings.value = it }

    suspend fun update(transform: (SaraSettings) -> SaraSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
            _settings.value = updated
        }
    }

    private object Keys {
        val wakeWordEnabled = booleanPreferencesKey("wake_enabled")
        val manualActivation = booleanPreferencesKey("manual_enabled")
        val wakeEngine = stringPreferencesKey("wake_engine")
        val sensitivity = floatPreferencesKey("sensitivity")
        val acknowledgement = stringPreferencesKey("ack")
        val autostart = booleanPreferencesKey("autostart")
        val echoCancellation = booleanPreferencesKey("echo_cancellation")
        val speechPause = androidx.datastore.preferences.core.intPreferencesKey("speech_pause_ms")
        val voiceMode = stringPreferencesKey("voice_mode")
        val onlineVoice = stringPreferencesKey("online_voice")
        val onlineVoiceId = stringPreferencesKey("online_voice_id")
        val offlineVoiceId = stringPreferencesKey("offline_voice_id")
        val speechRate = floatPreferencesKey("speech_rate")
        val voiceVolume = floatPreferencesKey("voice_volume")
        val recognitionMode = stringPreferencesKey("recognition_mode")
        val onlineRecognizer = stringPreferencesKey("online_recognizer")
        val offlineRecognizer = stringPreferencesKey("offline_recognizer")
        val allowFallback = booleanPreferencesKey("allow_fallback")
        val language = stringPreferencesKey("language")
        val saveHistory = booleanPreferencesKey("save_history")
        val azureRegion = stringPreferencesKey("azure_region")
        val confirmMessages = booleanPreferencesKey("confirm_messages")
        val aiProvider = stringPreferencesKey("ai_provider")
        val continuousConversation = booleanPreferencesKey("continuous_conversation")
        val heySara = booleanPreferencesKey("hey_sara")
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private fun Preferences.toSettings(): SaraSettings {
        val d = SaraSettings()
        return SaraSettings(
            wakeWordEnabled = this[Keys.wakeWordEnabled] ?: d.wakeWordEnabled,
            manualActivationEnabled = this[Keys.manualActivation] ?: d.manualActivationEnabled,
            wakeEngine = this[Keys.wakeEngine].toEnum(d.wakeEngine),
            sensitivity = this[Keys.sensitivity] ?: d.sensitivity,
            acknowledgement = this[Keys.acknowledgement].toEnum(d.acknowledgement),
            autostart = this[Keys.autostart] ?: d.autostart,
            echoCancellation = this[Keys.echoCancellation] ?: d.echoCancellation,
            speechPauseMillis = this[Keys.speechPause] ?: d.speechPauseMillis,
            voiceMode = this[Keys.voiceMode].toEnum(d.voiceMode),
            onlineVoice = this[Keys.onlineVoice].toEnum(d.onlineVoice),
            onlineVoiceId = this[Keys.onlineVoiceId],
            offlineVoiceId = this[Keys.offlineVoiceId],
            speechRate = this[Keys.speechRate] ?: d.speechRate,
            voiceVolume = this[Keys.voiceVolume] ?: d.voiceVolume,
            recognitionMode = this[Keys.recognitionMode].toEnum(d.recognitionMode),
            onlineRecognizer = this[Keys.onlineRecognizer].toEnum(d.onlineRecognizer),
            offlineRecognizer = this[Keys.offlineRecognizer].toEnum(d.offlineRecognizer),
            allowRecognitionFallback = this[Keys.allowFallback] ?: d.allowRecognitionFallback,
            language = this[Keys.language] ?: d.language,
            saveHistory = this[Keys.saveHistory] ?: d.saveHistory,
            azureRegion = this[Keys.azureRegion] ?: d.azureRegion,
            confirmMessages = this[Keys.confirmMessages] ?: d.confirmMessages,
            aiProvider = this[Keys.aiProvider].toEnum(d.aiProvider),
            continuousConversation = this[Keys.continuousConversation] ?: d.continuousConversation,
            heySara = this[Keys.heySara] ?: d.heySara,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: SaraSettings) {
        this[Keys.wakeWordEnabled] = s.wakeWordEnabled
        this[Keys.manualActivation] = s.manualActivationEnabled
        this[Keys.wakeEngine] = s.wakeEngine.name
        this[Keys.sensitivity] = s.sensitivity
        this[Keys.acknowledgement] = s.acknowledgement.name
        this[Keys.autostart] = s.autostart
        this[Keys.echoCancellation] = s.echoCancellation
        this[Keys.speechPause] = s.speechPauseMillis
        this[Keys.voiceMode] = s.voiceMode.name
        this[Keys.onlineVoice] = s.onlineVoice.name
        if (s.onlineVoiceId != null) this[Keys.onlineVoiceId] = s.onlineVoiceId else remove(Keys.onlineVoiceId)
        if (s.offlineVoiceId != null) this[Keys.offlineVoiceId] = s.offlineVoiceId else remove(Keys.offlineVoiceId)
        this[Keys.speechRate] = s.speechRate
        this[Keys.voiceVolume] = s.voiceVolume
        this[Keys.recognitionMode] = s.recognitionMode.name
        this[Keys.onlineRecognizer] = s.onlineRecognizer.name
        this[Keys.offlineRecognizer] = s.offlineRecognizer.name
        this[Keys.allowFallback] = s.allowRecognitionFallback
        this[Keys.language] = s.language
        this[Keys.saveHistory] = s.saveHistory
        this[Keys.azureRegion] = s.azureRegion
        this[Keys.confirmMessages] = s.confirmMessages
        this[Keys.aiProvider] = s.aiProvider.name
        this[Keys.continuousConversation] = s.continuousConversation
        this[Keys.heySara] = s.heySara
    }
}
