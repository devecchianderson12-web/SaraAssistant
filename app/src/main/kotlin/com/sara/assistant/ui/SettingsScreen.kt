package com.sara.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.sara.assistant.BuildConfig
import com.sara.assistant.audio.MicChoice
import com.sara.assistant.audio.MusicWhileListening
import kotlinx.coroutines.launch
import com.sara.assistant.settings.OfflineRecognizerType
import com.sara.assistant.settings.OnlineRecognizerType
import com.sara.assistant.settings.OnlineVoiceType
import com.sara.assistant.settings.SaraSettings
import com.sara.assistant.settings.WakeEngineType
import com.sara.assistant.speech.vosk.ModelState
import com.sara.assistant.ui.theme.SaraColors
import com.sara.assistant.update.UpdateState
import com.sara.core.assistant.Acknowledgement
import com.sara.core.engine.EngineMode
import com.sara.core.engine.VoiceOption
import kotlin.math.roundToInt

/** Acciones del sistema que la pantalla delega en la actividad. */
class SettingsActions(
    val update: ((SaraSettings) -> SaraSettings) -> Unit,
    val onWakeToggle: (Boolean) -> Unit,
    val testVoice: () -> Unit,
    val loadVoices: (online: Boolean) -> Unit,
    val saveAzure: (key: String?, region: String) -> Unit,
    val clearAzure: () -> Unit,
    val savePorcupineKey: (String?) -> Unit,
    val importPorcupineKeyword: () -> Unit,
    val importPorcupineModel: () -> Unit,
    val downloadModel: () -> Unit,
    val importModel: () -> Unit,
    val deleteModel: () -> Unit,
    val requestMic: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val openAppSettings: () -> Unit,
    val openAssistantSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val clearHistory: () -> Unit,
    val clearDiagnostics: () -> Unit,
    val refresh: () -> Unit,
    val checkUpdates: () -> Unit,
    val openOverlaySettings: () -> Unit,
    val openAccessibilitySettings: () -> Unit = {},
    val saveAiKey: (com.sara.assistant.settings.AiProvider, String?) -> Unit = { _, _ -> },
    val clearAiKey: (com.sara.assistant.settings.AiProvider) -> Unit = {},
    val forget: (String) -> Unit = {},
    val grant: (com.sara.core.model.PermissionKind) -> Unit = {},
    val grantAll: () -> Unit = {},
    val toggleRoutine: (com.sara.assistant.skills.ScheduledRoutine) -> Unit = {},
    val deleteRoutine: (com.sara.assistant.skills.ScheduledRoutine) -> Unit = {},
    val deleteReminder: (com.sara.assistant.skills.Reminder) -> Unit = {},
    val compareMicrophones: () -> Unit = {},
    val dismissMicCompare: () -> Unit = {},
    val installUpdate: (com.sara.assistant.update.ReleaseInfo) -> Unit = {},
)

/** Apartados (para el índice de arriba) y su color. */
private val SECTIONS = listOf(
    "Activación" to SaraColors.Violet, "Micrófono" to SaraColors.Cyan, "Voz" to SaraColors.Pink, "Reconocimiento" to SaraColors.Amber,
    "IA" to SaraColors.Green, "Mensajes" to SaraColors.Cyan, "Rutinas" to SaraColors.Violet, "Permisos" to SaraColors.Amber,
    "Privacidad" to SaraColors.Green, "Sistema" to SaraColors.Muted,
)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)

/** Arriba de todo: la versión y el botón para buscar (y, si hay, instalar) la actualización. */
@Composable
private fun UpdateCard(state: UpdateState, actions: SettingsActions) {
    val available = state as? UpdateState.Available
    SectionCard(
        "Actualizaciones",
        subtitle = "Versión instalada v${BuildConfig.VERSION_NAME}",
        accent = if (available != null) SaraColors.Green else SaraColors.Cyan,
    ) {
        Text(
            when (state) {
                UpdateState.Checking -> "Buscando…"
                UpdateState.UpToDate -> "Tenés la última versión."
                is UpdateState.Available -> "¡Hay una versión nueva! ${state.release.tag}"
                is UpdateState.Downloading -> "Descargando ${(state.progress * 100).roundToInt()} %…"
                is UpdateState.Installing -> "Instalando…"
                is UpdateState.NeedsPermission -> "Falta permitir «instalar apps desconocidas»."
                is UpdateState.Failed -> state.message
                else -> "Tocá el botón para ver si hay una versión nueva."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                if (state == UpdateState.Checking) "Buscando…" else "Buscar actualización",
                enabled = state != UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Installing,
                onClick = actions.checkUpdates,
            )
            val release = available?.release ?: (state as? UpdateState.NeedsPermission)?.release ?: (state as? UpdateState.Failed)?.release
            if (release != null) ActionButton("Actualizar", color = SaraColors.Green) { actions.installUpdate(release) }
        }
    }
}

/** Voces neuronales de Azure de Argentina y Uruguay (las «Natural» de Edge). */
private val NATURAL_VOICES = listOf(
    "es-AR-ElenaNeural" to "Elena · Argentina", "es-AR-TomasNeural" to "Tomás · Argentina",
    "es-UY-ValentinaNeural" to "Valentina · Uruguay", "es-UY-MateoNeural" to "Mateo · Uruguay",
)

private val modeOptions = listOf(EngineMode.AUTOMATIC to "Automático", EngineMode.ONLINE to "Online", EngineMode.OFFLINE to "Offline")
private val languages = listOf("es-AR" to "Rioplatense", "es-US" to "Latino (EE. UU.)", "es-MX" to "México", "es-419" to "Latinoamérica", "es-ES" to "España")

@Composable
fun SettingsScreen(
    settings: SaraSettings,
    system: SystemStatus,
    modelState: ModelState,
    serviceRunning: Boolean,
    micActive: Boolean,
    voices: Map<Boolean, List<VoiceOption>>,
    diagnostics: List<String>,
    actions: SettingsActions,
    updateState: UpdateState = UpdateState.Idle,
    memories: Map<String, String> = emptyMap(),
    aiDialogRequested: Boolean = false,
    onAiDialogShown: () -> Unit = {},
    routines: List<com.sara.assistant.skills.ScheduledRoutine> = emptyList(),
    reminders: List<com.sara.assistant.skills.Reminder> = emptyList(),
    micCompare: String? = null,
) {
    var aiDialog by remember { mutableStateOf(false) }
    LaunchedEffect(aiDialogRequested) {
        if (aiDialogRequested) {
            aiDialog = true
            onAiDialogShown()
        }
    }
    var voicePicker by remember { mutableStateOf<Boolean?>(null) }
    var azureDialog by remember { mutableStateOf(false) }
    var porcupineDialog by remember { mutableStateOf(false) }
    val permissions = system.permissions

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Dónde empieza cada apartado, para el índice de arriba.
    val positions = remember { HashMap<String, Int>() }
    fun Modifier.anchor(name: String) = onGloballyPositioned { positions[name] = it.positionInParent().y.toInt() }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

        // ---------------------------------------------------------------- Actualizaciones (lo primero)
        UpdateCard(updateState, actions)

        // ---------------------------------------------------------------- Índice
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SECTIONS.forEach { (name, color) ->
                AssistChip(
                    onClick = { positions[name]?.let { y -> scope.launch { scroll.animateScrollTo(y) } } },
                    label = { Text(name) },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
                    colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.10f), labelColor = SaraColors.Text),
                )
            }
        }

        // ---------------------------------------------------------------- Activación
        SectionCard("Activación", Modifier.anchor("Activación"), "Cómo y cuándo te escucha", SaraColors.Violet) {
            SubHeader("Palabra de activación")
            SwitchRow("Detectar «${if (settings.heySara) "Ey Sara" else "Sara"}»", "Escucha continua procesada en el teléfono", settings.wakeWordEnabled, onChange = actions.onWakeToggle)
            OptionChips(
                "Palabra",
                listOf(false to "«Sara» (reacciona al toque)", true to "«Ey Sara» (más estricto)"),
                settings.heySara,
                subtitle = "«Ey Sara» se activa menos por error pero puede costar más que te escuche.",
            ) { v -> actions.update { it.copy(heySara = v) } }
            SliderRow("Sensibilidad", settings.sensitivity, 0f..1f, { "${(it * 100).roundToInt()} %" }) { v ->
                actions.update { it.copy(sensitivity = (v * 20).roundToInt() / 20f) }
            }
            Hint("Más sensibilidad = responde más fácil, pero puede activarse por error.")
            OptionChips(
                "Al detectar «Sara»",
                listOf(Acknowledgement.VOICE to "Dice «Decime» / «¿Sí?»", Acknowledgement.CHIME to "Señal sonora", Acknowledgement.VOICE_AND_CHIME to "Ambas", Acknowledgement.NONE to "Nada"),
                settings.acknowledgement,
            ) { v -> actions.update { it.copy(acknowledgement = v) } }

            SubHeader("Velocidad")
            SwitchRow(
                "Respuesta rápida",
                "Apenas entiende una orden completa («pasá la canción», «subí el volumen», «cuánto es 35 % de 1000») la hace, sin esperar a que termines la pausa",
                settings.quickResponse,
            ) { v -> actions.update { it.copy(quickResponse = v) } }
            Hint("Para que arranque a escucharte antes: en «Al detectar «Sara»» elegí «Señal sonora» (no dice «¿Sí?»). Y si igual tarda en cortar, bajá «Pausa permitida al hablar» en Reconocimiento.")

            SubHeader("Comportamiento")
            SwitchRow(
                "Charlar después de una pregunta",
                if (system.aiConfigured) "Cuando le preguntás algo (no una orden), sigue escuchando unos segundos para conversar sin decir «Sara». Después de una orden se calla."
                else "Necesita la clave de IA (Inteligencia y memoria). Sin clave sólo charla básica.",
                settings.chatFollowUp,
            ) { v -> actions.update { it.copy(chatFollowUp = v) } }
            SwitchRow(
                "Conversación continua",
                "Después de responder sigue escuchando unos segundos sin que la llames (apagado: hace la orden y se calla)",
                settings.continuousConversation,
            ) { v -> actions.update { it.copy(continuousConversation = v) } }
            SwitchRow(
                "Esfera al llamarla",
                if (system.canOpenAppsFromBackground) "Aparece en el centro sobre cualquier pantalla, también bloqueada"
                else "Necesita «Mostrar sobre otras apps» (Permisos › Abrir apps)",
                settings.sphereOverlay,
            ) { v -> actions.update { it.copy(sphereOverlay = v) } }
            SwitchRow("Activación manual", "Tocar el círculo central para hablar", settings.manualActivationEnabled) { v ->
                actions.update { it.copy(manualActivationEnabled = v) }
            }
            SwitchRow(
                "Ahorrar batería",
                "Apagado (recomendado): te escucha también con la pantalla apagada. Encendido: sólo con la pantalla encendida.",
                settings.listenOnlyWithScreenOn,
            ) { v -> actions.update { it.copy(listenOnlyWithScreenOn = v) } }
            SwitchRow("Reactivar al encender el teléfono", "En Android 11+ requiere tocar una notificación, salvo que Sara sea el asistente predeterminado", settings.autostart) { v ->
                actions.update { it.copy(autostart = v) }
            }
            Hint(
                "Sin gastar batería, como el asistente de Google: apagá «Detectar» y elegí a Sara como asistente predeterminado " +
                    "(Sistema). Se abre manteniendo apretado el botón de encendido o deslizando desde una esquina, según el teléfono.",
            )

            SubHeader("Motor")
            OptionChips(
                "Motor de activación",
                listOf(WakeEngineType.VOSK to "Vosk (sin cuenta)", WakeEngineType.PORCUPINE to "Porcupine"),
                settings.wakeEngine,
            ) { v -> actions.update { it.copy(wakeEngine = v) } }
            ModelRow(modelState, actions)
            if (settings.wakeEngine == WakeEngineType.PORCUPINE) {
                StatusLine(
                    "Porcupine",
                    listOf(
                        if (system.porcupineKey) "AccessKey ✓" else "Falta AccessKey",
                        if (system.porcupineKeyword) ".ppn ✓" else "Falta .ppn",
                        if (system.porcupineModel) ".pv ✓" else "Falta .pv",
                    ).joinToString(" · "),
                    system.porcupineKey && system.porcupineKeyword && system.porcupineModel,
                    "Configurar",
                ) { porcupineDialog = true }
            }
        }

        // ---------------------------------------------------------------- Micrófono y Bluetooth
        SectionCard("Micrófono y auto", Modifier.anchor("Micrófono"), "Para que te escuche bien con el auto o auriculares Bluetooth", SaraColors.Cyan) {
            StatusLine("Ahora usa", system.micCurrent.ifBlank { "Del teléfono" }, true)
            SubHeader("Micrófono para las órdenes")
            OptionChips(
                "Con un Bluetooth conectado",
                listOf(MicChoice.AUTO to "Automático", MicChoice.PHONE to "Del teléfono", MicChoice.BLUETOOTH to "Del Bluetooth"),
                settings.micChoice,
                subtitle = when (settings.micChoice) {
                    MicChoice.AUTO -> "Empieza con el del teléfono y, si te escucha mal, prueba el del Bluetooth y se queda con el que mejor te escucha."
                    MicChoice.PHONE -> "Siempre el del teléfono (suele grabar mejor que el del auto, si el celular está cerca tuyo)."
                    MicChoice.BLUETOOTH -> "Siempre el del auto o los auriculares. Mientras te escucha, el auto pasa un momento a «modo llamada»."
                },
            ) { v -> actions.update { it.copy(micChoice = v) } }
            Hint("«Sara» siempre se detecta con el micrófono del teléfono: usar el del Bluetooth todo el tiempo dejaría el auto en modo llamada.")
            StatusLine("Cómo te escucha cada uno", system.micQuality.ifBlank { "Todavía sin medir" }, true)
            ActionButton("Comparar micrófonos ahora", enabled = system.bluetoothMic, onClick = actions.compareMicrophones)
            if (!system.bluetoothMic) Hint("Conectá el auto o los auriculares para comparar.")

            SubHeader("Tu voz")
            SwitchRow(
                "Subir el volumen de tu voz",
                "Amplifica la voz baja al entender la orden (con el reconocimiento local; el de Google ajusta solo)",
                settings.voiceBoost,
            ) { v -> actions.update { it.copy(voiceBoost = v) } }
            SwitchRow("Escuchar con música de fondo", "Cancela el eco del parlante del teléfono mientras suena música (si el teléfono lo admite)", settings.echoCancellation) { v ->
                actions.update { it.copy(echoCancellation = v) }
            }

            SubHeader("Música mientras te escucho")
            OptionChips(
                "Qué hago con la música",
                listOf(
                    MusicWhileListening.DUCK to "Bajarla",
                    MusicWhileListening.PAUSE_BLUETOOTH to "Pausarla en el auto",
                    MusicWhileListening.PAUSE to "Pausarla siempre",
                ),
                settings.musicWhileListening,
                subtitle = "Pausada se reanuda sola cuando termino (salvo que me pidas pararla). «En el auto» = con audio Bluetooth conectado.",
            ) { v -> actions.update { it.copy(musicWhileListening = v) } }

            SubHeader("Reconocimiento en el auto")
            SwitchRow(
                "Entender con Google en el auto",
                "Con Bluetooth conectado usa el reconocimiento de Google (entiende mucho mejor con audio malo) aunque el modo sea Offline",
                settings.onlineInCar,
            ) { v -> actions.update { it.copy(onlineInCar = v) } }
        }

        // ---------------------------------------------------------------- Voz
        SectionCard("Voz", Modifier.anchor("Voz"), "Cómo te habla Sara", SaraColors.Pink) {
            SubHeader("Voz de Sara")
            val azureReady = system.azureConfigured && settings.azureRegion.isNotBlank()
            val current = if (settings.onlineVoice == OnlineVoiceType.AZURE) settings.onlineVoiceId ?: NATURAL_VOICES.first().first else null
            OptionChips(
                "Voces naturales (las mismas de «Leer en voz alta» de Edge)",
                NATURAL_VOICES,
                current,
                subtitle = if (azureReady) "Elegí la que más te guste y tocá «Probar voz»."
                else "Necesitan la clave de Azure Speech (gratis hasta 500.000 caracteres por mes, de sobra para Sara). Tocá «Configurar» acá abajo.",
            ) { id ->
                actions.update { it.copy(onlineVoice = OnlineVoiceType.AZURE, onlineVoiceId = id, voiceMode = if (it.voiceMode == EngineMode.OFFLINE) EngineMode.AUTOMATIC else it.voiceMode) }
                if (!azureReady) azureDialog = true
            }
            SubHeader("Avanzado")
            OptionChips("Modo de voz", modeOptions, settings.voiceMode, "Automático: neuronal online con respaldo offline") { v ->
                actions.update { it.copy(voiceMode = v) }
            }
            OptionChips(
                "Proveedor online",
                listOf(OnlineVoiceType.AZURE to "Azure neuronal", OnlineVoiceType.SYSTEM_NETWORK to "Voces online del sistema"),
                settings.onlineVoice,
            ) { v -> actions.update { it.copy(onlineVoice = v, onlineVoiceId = null) } }
            if (settings.onlineVoice == OnlineVoiceType.AZURE) {
                StatusLine(
                    "Azure Speech",
                    if (system.azureConfigured && settings.azureRegion.isNotBlank()) "Configurado · región ${settings.azureRegion}" else "Falta la clave y la región",
                    system.azureConfigured && settings.azureRegion.isNotBlank(),
                    "Configurar",
                ) { azureDialog = true }
            }
            SubHeader("Voces")
            VoiceRow("Voz online", settings.onlineVoiceId ?: if (settings.onlineVoice == OnlineVoiceType.AZURE) "es-AR-ElenaNeural (predeterminada)" else "Automática") {
                actions.loadVoices(true); voicePicker = true
            }
            VoiceRow("Voz offline", settings.offlineVoiceId ?: "Automática (preferencia rioplatense)") {
                actions.loadVoices(false); voicePicker = false
            }
            Hint("La voz offline usa las voces instaladas en el teléfono: su naturalidad depende del motor de texto a voz del dispositivo.")
            SliderRow("Velocidad", settings.speechRate, 0.6f..1.6f, { "%.1fx".format(it) }) { v ->
                actions.update { it.copy(speechRate = (v * 10).roundToInt() / 10f) }
            }
            SliderRow("Volumen de Sara", settings.voiceVolume, 0.2f..1f, { "${(it * 100).roundToInt()} %" }) { v ->
                actions.update { it.copy(voiceVolume = v) }
            }
            ActionButton("Probar voz", onClick = actions.testVoice)
        }

        // ---------------------------------------------------------------- Reconocimiento
        SectionCard("Reconocimiento", Modifier.anchor("Reconocimiento"), "Cómo entiende lo que le decís", SaraColors.Amber) {
            OptionChips("Modo", modeOptions, settings.recognitionMode, "En Offline nunca se envía audio a Internet") { v ->
                actions.update { it.copy(recognitionMode = v) }
            }
            OptionChips(
                "Motor online",
                listOf(OnlineRecognizerType.SYSTEM to "Servicio del sistema", OnlineRecognizerType.AZURE to "Azure Speech"),
                settings.onlineRecognizer,
            ) { v -> actions.update { it.copy(onlineRecognizer = v) } }
            OptionChips(
                "Motor offline",
                listOf(OfflineRecognizerType.AUTO to "Automático", OfflineRecognizerType.VOSK to "Vosk", OfflineRecognizerType.ANDROID_ON_DEVICE to "Android (12+)"),
                settings.offlineRecognizer,
            ) { v -> actions.update { it.copy(offlineRecognizer = v) } }
            SliderRow("Pausa permitida al hablar", settings.speechPauseMillis / 1000f, 0.8f..3.5f, { "%.1f s".format(it) }) { v ->
                actions.update { it.copy(speechPauseMillis = (v * 10).roundToInt() * 100) }
            }
            Hint("Si Sara te corta cuando hacés una pausa, subí este valor. Si no entiende, te pregunta de nuevo una vez.")
            SwitchRow("Respaldo offline en modo Online", "Si se corta la conexión, usar el motor local", settings.allowRecognitionFallback) { v ->
                actions.update { it.copy(allowRecognitionFallback = v) }
            }
            OptionChips("Idioma", languages, settings.language) { v -> actions.update { it.copy(language = v) } }
        }

        // ---------------------------------------------------------------- Inteligencia y memoria
        SectionCard("Inteligencia y memoria", Modifier.anchor("IA"), "Preguntas, búsquedas y lo que recuerda de vos", SaraColors.Green) {
            StatusLine(
                if (settings.aiProvider == com.sara.assistant.settings.AiProvider.GOOGLE) "IA: Google (Gemini + búsqueda)" else "IA: Claude",
                if (system.aiConfigured) "Activa: responde preguntas, el clima y charla" else "Sin clave: sólo órdenes y charla básica",
                system.aiConfigured, "Configurar",
            ) { aiDialog = true }
            Hint("Enseñale cosas: «cada vez que te pida música abrí BitChord», «cuando te diga buenas noches, sacá la música».")
            SubHeader("Lo que recuerda")
            if (memories.isEmpty()) Hint("Todavía no recuerda nada.")
            memories.forEach { (key, value) ->
                StatusLine(
                    key.removePrefix(com.sara.core.memory.MemoryKeys.NOTE_PREFIX),
                    com.sara.core.memory.MemoryKeys.describe(key, value).replaceFirstChar { it.uppercase() },
                    true, "Olvidar",
                ) { actions.forget(key) }
            }
        }

        // ---------------------------------------------------------------- Mensajes
        SectionCard("Mensajes", Modifier.anchor("Mensajes"), "WhatsApp", SaraColors.Cyan) {
            SwitchRow(
                "Confirmar antes de enviar",
                if (settings.confirmMessages) "Sara te lee el mensaje y espera un «sí»" else "Sara manda el WhatsApp sin preguntar",
                settings.confirmMessages,
            ) { v -> actions.update { it.copy(confirmMessages = v) } }
        }

        // ---------------------------------------------------------------- Rutinas y recordatorios
        SectionCard("Rutinas y recordatorios", Modifier.anchor("Rutinas"), "Lo que Sara hace sola a una hora", SaraColors.Violet) {
            Hint("Decí por ejemplo «todos los días a las 8 decime el clima» o «recordame mañana a las 9 llamar al médico».")
            if (routines.isEmpty() && reminders.isEmpty()) Hint("No hay rutinas ni recordatorios.")
            routines.forEach { r ->
                StatusLine(if (r.enabled) "Rutina" else "Rutina (pausada)", r.describe(), r.enabled, if (r.enabled) "Pausar" else "Activar") { actions.toggleRoutine(r) }
                ActionButton("Borrar rutina", color = SaraColors.Red) { actions.deleteRoutine(r) }
            }
            reminders.forEach { r ->
                StatusLine("Recordatorio", "${r.text} · ${com.sara.assistant.skills.Scheduler.describeTime(r.at)}", true, "Borrar") { actions.deleteReminder(r) }
            }
        }

        // ---------------------------------------------------------------- Permisos
        SectionCard("Permisos", Modifier.anchor("Permisos"), if (system.missing.isEmpty()) "Todo activado" else "Faltan ${system.missing.size}", SaraColors.Amber) {
            if (system.missing.isNotEmpty()) ActionButton("Dar todos los permisos", onClick = actions.grantAll)
            com.sara.core.model.PermissionKind.entries.forEach { kind ->
                val info = com.sara.assistant.permissions.PermissionCenter.info(kind)
                val ok = kind !in system.missing
                StatusLine(info.title, if (ok) "Activado" else info.why, ok, if (ok) null else "Activar", if (ok) null else ({ actions.grant(kind) }))
            }
        }

        // ---------------------------------------------------------------- Privacidad
        SectionCard("Privacidad", Modifier.anchor("Privacidad"), "Qué se guarda y qué sale del teléfono", SaraColors.Green) {
            StatusLine("Micrófono", if (micActive) "En uso por Sara ahora" else "Cerrado", !micActive)
            StatusLine(
                "Permiso de micrófono", if (permissions?.recordAudio == true) "Concedido" else "No concedido",
                permissions?.recordAudio == true, if (permissions?.recordAudio == true) "Gestionar" else "Permitir",
            ) { if (permissions?.recordAudio == true) actions.openAppSettings() else actions.requestMic() }
            StatusLine(
                "Acceso a notificaciones", if (permissions?.notificationListener == true) "Concedido (control multimedia)" else "Necesario para controlar apps de música",
                permissions?.notificationListener == true, "Abrir",
                actions.openNotificationAccess,
            )
            StatusLine(
                "App de música",
                system.musicApp?.let { "$it · la que más usás, se ajusta sola" } ?: "Todavía aprendiendo cuál usás más",
                system.musicApp != null,
            )
            StatusLine("Palabra de activación", "Se procesa 100 % en el teléfono", true)
            StatusLine(
                "Audio online",
                when (settings.recognitionMode) {
                    EngineMode.OFFLINE -> "Nunca se envía audio" + if (settings.onlineInCar) " (salvo en el auto, si lo activaste)" else ""
                    else -> "Sólo la orden después de «Sara», al proveedor online elegido"
                },
                settings.recognitionMode == EngineMode.OFFLINE,
            )
            SwitchRow("Guardar historial", "Sólo texto, sin audio, máximo 100 órdenes", settings.saveHistory) { v ->
                actions.update { it.copy(saveHistory = v) }
            }
            ActionButton("Borrar historial", color = SaraColors.Red, onClick = actions.clearHistory)
            Hint("Las claves de proveedores se guardan cifradas con el Keystore de Android y se excluyen de las copias de seguridad.")
        }

        // ---------------------------------------------------------------- Sistema
        SectionCard("Sistema", Modifier.anchor("Sistema"), "Servicio, batería y diagnóstico", SaraColors.Muted) {
            StatusLine("Servicio de escucha", if (serviceRunning) "En ejecución (notificación visible)" else "Detenido", serviceRunning)
            StatusLine(
                "Asistente predeterminado",
                if (system.defaultAssistant) "Sara es el asistente digital" else "Necesario para abrir Sara con el gesto de asistente y reactivarla sola tras reiniciar",
                system.defaultAssistant, "Cambiar", actions.openAssistantSettings,
            )
            StatusLine(
                "Abrir apps con la pantalla en otra app",
                if (system.canOpenAppsFromBackground) "Permitido: «Sara, abrí YouTube» abre la app directo"
                else "Sin permiso: Sara deja una notificación para tocar. Activá «Mostrar sobre otras apps»",
                system.canOpenAppsFromBackground, "Permitir", actions.openOverlaySettings,
            )
            StatusLine(
                "Cerrar apps en segundo plano",
                if (system.canCloseApps) "Activado: «Sara, cerrá las apps» limpia Recientes"
                else "Activá a Sara en Accesibilidad → Apps instaladas",
                system.canCloseApps, "Activar", actions.openAccessibilitySettings,
            )
            StatusLine(
                "Batería",
                if (system.batteryUnrestricted) "Sin restricciones" else "Optimizada: algunos fabricantes pueden cortar la escucha",
                system.batteryUnrestricted, "Ajustar", actions.openBatterySettings,
            )
            SubHeader("Diagnóstico")
            system.engines.forEach { StatusLine(it.name, it.detail, it.ok) }
            Hint("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · v${BuildConfig.VERSION_NAME}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Actualizar estado", onClick = actions.refresh)
                ActionButton("Limpiar registro", color = SaraColors.Muted, onClick = actions.clearDiagnostics)
            }
            Text(
                diagnostics.takeLast(40).joinToString("\n").ifBlank { "Sin eventos" },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SaraColors.Muted,
            )
        }
    }

    micCompare?.let { text ->
        AlertDialog(
            onDismissRequest = actions.dismissMicCompare,
            containerColor = SaraColors.SurfaceHigh,
            title = { Text("Comparar micrófonos") },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { ActionButton("Listo", onClick = actions.dismissMicCompare) },
        )
    }

    voicePicker?.let { online ->
        VoicePickerDialog(
            title = if (online) "Voz online" else "Voz offline",
            voices = voices[online],
            onDismiss = { voicePicker = null },
            onPick = { id ->
                voicePicker = null
                actions.update { if (online) it.copy(onlineVoiceId = id) else it.copy(offlineVoiceId = id) }
            },
        )
    }
    if (aiDialog) {
        AiKeyDialog(settings.aiProvider, system.googleKey, system.claudeKey, onDismiss = { aiDialog = false }, onSave = { provider, key ->
            aiDialog = false
            actions.saveAiKey(provider, key)
        }, onClear = { provider ->
            aiDialog = false
            actions.clearAiKey(provider)
        })
    }

    if (azureDialog) {
        AzureDialog(settings.azureRegion, system.azureConfigured, onDismiss = { azureDialog = false }, onSave = { key, region ->
            azureDialog = false
            actions.saveAzure(key, region)
        }, onClear = {
            azureDialog = false
            actions.clearAzure()
        })
    }
    if (porcupineDialog) {
        AlertDialog(
            onDismissRequest = { porcupineDialog = false },
            containerColor = SaraColors.SurfaceHigh,
            title = { Text("Picovoice Porcupine") },
            text = {
                var key by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1) Creá una AccessKey gratis en console.picovoice.ai.\n2) Entrená la palabra «Sara» en español y descargá el .ppn para Android.\n3) Descargá porcupine_params_es.pv del repositorio de Porcupine.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(key, { key = it }, label = { Text(if (system.porcupineKey) "AccessKey (guardada)" else "AccessKey") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    ActionButton("Guardar AccessKey", onClick = { if (key.isNotBlank()) actions.savePorcupineKey(key); key = "" })
                    ActionButton("Importar .ppn", onClick = actions.importPorcupineKeyword)
                    ActionButton("Importar .pv", onClick = actions.importPorcupineModel)
                }
            },
            confirmButton = { ActionButton("Listo", onClick = { porcupineDialog = false }) },
        )
    }
}

@Composable
private fun ModelRow(state: ModelState, actions: SettingsActions) {
    val (detail, ok) = when (state) {
        ModelState.Ready -> "Instalado · reconocimiento local listo" to true
        ModelState.NotInstalled -> "No instalado (40 MB)" to false
        is ModelState.Downloading -> "Descargando ${(state.progress * 100).roundToInt()} %" to false
        ModelState.Installing -> "Instalando…" to false
        is ModelState.Failed -> state.message to false
    }
    StatusLine("Modelo de voz en español (Vosk)", detail, ok)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state is ModelState.Ready) {
            ActionButton("Eliminar modelo", color = SaraColors.Red, onClick = actions.deleteModel)
        } else if (state !is ModelState.Downloading && state != ModelState.Installing) {
            ActionButton("Descargar", onClick = actions.downloadModel)
            ActionButton("Importar .zip", color = SaraColors.Muted, onClick = actions.importModel)
        }
    }
}

@Composable
private fun VoiceRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Cyan)
    }
}

@Composable
private fun VoicePickerDialog(title: String, voices: List<VoiceOption>?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SaraColors.SurfaceHigh,
        title = { Text(title) },
        text = {
            if (voices == null) {
                Text("Cargando voces…", color = SaraColors.Muted)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text("Automática", Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp), color = SaraColors.Cyan)
                    }
                    items(voices, key = { it.id }) { voice ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(voice.id) }.padding(vertical = 10.dp)) {
                            Text(voice.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${voice.locale} · ${voice.description}", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { ActionButton("Cerrar", onClick = onDismiss) },
    )
}

@Composable
private fun AiKeyDialog(
    current: com.sara.assistant.settings.AiProvider,
    googleKey: Boolean,
    claudeKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (com.sara.assistant.settings.AiProvider, String?) -> Unit,
    onClear: (com.sara.assistant.settings.AiProvider) -> Unit,
) {
    var provider by remember { mutableStateOf(current) }
    var key by remember { mutableStateOf("") }
    val google = provider == com.sara.assistant.settings.AiProvider.GOOGLE
    val hasKey = if (google) googleKey else claudeKey
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SaraColors.SurfaceHigh,
        title = { Text("Inteligencia de Sara") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionChips(
                    "Usar",
                    listOf(com.sara.assistant.settings.AiProvider.GOOGLE to "Google", com.sara.assistant.settings.AiProvider.CLAUDE to "Claude"),
                    provider,
                ) { provider = it; key = "" }
                Text(
                    if (google) "Busca todo en Google. Clave gratis: entrá a aistudio.google.com › «Get API key» › «Create API key», copiala y pegala acá."
                    else "Creá una clave en platform.claude.com › API Keys. Cada pregunta tiene un costo pequeño en tu cuenta de Anthropic.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    key, { key = it }, singleLine = true,
                    label = { Text(if (hasKey) "Clave (guardada, dejá vacío para conservarla)" else if (google) "Clave (AIza…)" else "Clave (sk-ant-…)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (hasKey) ActionButton("Eliminar clave guardada", color = SaraColors.Red, onClick = { onClear(provider) })
            }
        },
        confirmButton = { ActionButton("Guardar", onClick = { onSave(provider, key.ifBlank { null }) }) },
        dismissButton = { ActionButton("Cancelar", onClick = onDismiss) },
    )
}

@Composable
private fun AzureDialog(region: String, hasKey: Boolean, onDismiss: () -> Unit, onSave: (String?, String) -> Unit, onClear: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var regionText by remember { mutableStateOf(region) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SaraColors.SurfaceHigh,
        title = { Text("Azure AI Speech") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Creá un recurso «Speech» en portal.azure.com (tiene nivel gratuito) y copiá la clave y la región. Se usa para la voz neuronal es-AR-ElenaNeural y, si lo elegís, para el reconocimiento online.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    key, { key = it }, singleLine = true,
                    label = { Text(if (hasKey) "Clave (guardada, dejá vacío para conservarla)" else "Clave") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(regionText, { regionText = it }, singleLine = true, label = { Text("Región (ej.: eastus, brazilsouth)") })
                if (hasKey) ActionButton("Eliminar clave guardada", color = SaraColors.Red, onClick = onClear)
            }
        },
        confirmButton = { ActionButton("Guardar", onClick = { onSave(key.ifBlank { null }, regionText) }) },
        dismissButton = { ActionButton("Cancelar", onClick = onDismiss) },
    )
}
