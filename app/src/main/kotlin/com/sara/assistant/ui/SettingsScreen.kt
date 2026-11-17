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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.sara.assistant.BuildConfig
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
    val toggleRoutine: (com.sara.assistant.skills.ScheduledRoutine) -> Unit = {},
    val deleteRoutine: (com.sara.assistant.skills.ScheduledRoutine) -> Unit = {},
    val deleteReminder: (com.sara.assistant.skills.Reminder) -> Unit = {},
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

        // ---------------------------------------------------------------- Activación
        SectionCard("Activación") {
            SwitchRow("Detectar «${if (settings.heySara) "Ey Sara" else "Sara"}»", "Escucha continua procesada en el teléfono", settings.wakeWordEnabled, onChange = actions.onWakeToggle)
            OptionChips(
                "Palabra de activación",
                listOf(true to "«Ey Sara» (recomendado)", false to "«Sara»"),
                settings.heySara,
                subtitle = "«Ey Sara» se activa sólo con el llamado completo: menos activaciones por error.",
            ) { v -> actions.update { it.copy(heySara = v) } }
            SwitchRow("Activación manual", "Tocar el círculo central para hablar", settings.manualActivationEnabled) { v ->
                actions.update { it.copy(manualActivationEnabled = v) }
            }
            SwitchRow(
                "Conversación continua",
                "Después de responder sigue escuchando unos segundos sin que la llames (apagado: hace la orden y se calla)",
                settings.continuousConversation,
            ) { v -> actions.update { it.copy(continuousConversation = v) } }
            OptionChips(
                "Motor de activación",
                listOf(WakeEngineType.VOSK to "Vosk (sin cuenta)", WakeEngineType.PORCUPINE to "Porcupine"),
                settings.wakeEngine,
            ) { v -> actions.update { it.copy(wakeEngine = v) } }
            SliderRow("Sensibilidad", settings.sensitivity, 0f..1f, { "${(it * 100).roundToInt()} %" }) { v ->
                actions.update { it.copy(sensitivity = (v * 20).roundToInt() / 20f) }
            }
            Text("Más sensibilidad = responde más fácil, pero puede activarse por error.", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
            OptionChips(
                "Al detectar «Sara»",
                listOf(Acknowledgement.VOICE to "Dice «Decime» / «¿Sí?»", Acknowledgement.CHIME to "Señal sonora", Acknowledgement.VOICE_AND_CHIME to "Ambas", Acknowledgement.NONE to "Nada"),
                settings.acknowledgement,
            ) { v -> actions.update { it.copy(acknowledgement = v) } }
            SwitchRow("Escuchar con música de fondo", "Cancela el eco del parlante mientras suena música (si el teléfono lo admite)", settings.echoCancellation) { v ->
                actions.update { it.copy(echoCancellation = v) }
            }
            SwitchRow("Reactivar al encender el teléfono", "En Android 11+ requiere tocar una notificación, salvo que Sara sea el asistente predeterminado", settings.autostart) { v ->
                actions.update { it.copy(autostart = v) }
            }
            HorizontalDivider(color = SaraColors.Outline)
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

        // ---------------------------------------------------------------- Voz
        SectionCard("Voz") {
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
            VoiceRow("Voz online", settings.onlineVoiceId ?: if (settings.onlineVoice == OnlineVoiceType.AZURE) "es-AR-ElenaNeural (predeterminada)" else "Automática") {
                actions.loadVoices(true); voicePicker = true
            }
            VoiceRow("Voz offline", settings.offlineVoiceId ?: "Automática (preferencia rioplatense)") {
                actions.loadVoices(false); voicePicker = false
            }
            Text("La voz offline usa las voces instaladas en el teléfono: su naturalidad depende del motor de texto a voz del dispositivo.", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
            SliderRow("Velocidad", settings.speechRate, 0.6f..1.6f, { "%.1fx".format(it) }) { v ->
                actions.update { it.copy(speechRate = (v * 10).roundToInt() / 10f) }
            }
            SliderRow("Volumen de Sara", settings.voiceVolume, 0.2f..1f, { "${(it * 100).roundToInt()} %" }) { v ->
                actions.update { it.copy(voiceVolume = v) }
            }
            OutlinedButton(onClick = actions.testVoice, shape = RoundedCornerShape(50)) { Text("Probar voz") }
        }

        // ---------------------------------------------------------------- Reconocimiento
        SectionCard("Reconocimiento") {
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
            Text("Si Sara te corta cuando hacés una pausa, subí este valor. Si no entiende, te pregunta de nuevo una vez.", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
            SwitchRow("Respaldo offline en modo Online", "Si se corta la conexión, usar el motor local", settings.allowRecognitionFallback) { v ->
                actions.update { it.copy(allowRecognitionFallback = v) }
            }
            OptionChips("Idioma", languages, settings.language) { v -> actions.update { it.copy(language = v) } }
        }

        // ---------------------------------------------------------------- Inteligencia y memoria
        SectionCard("Inteligencia y memoria") {
            StatusLine(
                if (settings.aiProvider == com.sara.assistant.settings.AiProvider.GOOGLE) "IA: Google (Gemini + búsqueda)" else "IA: Claude",
                if (system.aiConfigured) "Activa: responde preguntas, el clima y charla" else "Sin clave: sólo órdenes y charla básica",
                system.aiConfigured, "Configurar",
            ) { aiDialog = true }
            Text(
                "Enseñale cosas: «cada vez que te pida música abrí BitChord», «cuando te diga buenas noches, hacé sacá la música».",
                style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted,
            )
            if (memories.isEmpty()) {
                Text("Todavía no recuerda nada.", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
            }
            memories.forEach { (key, value) ->
                StatusLine(
                    key.removePrefix(com.sara.core.memory.MemoryKeys.NOTE_PREFIX),
                    com.sara.core.memory.MemoryKeys.describe(key, value).replaceFirstChar { it.uppercase() },
                    true, "Olvidar",
                ) { actions.forget(key) }
            }
        }

        // ---------------------------------------------------------------- Mensajes
        SectionCard("Mensajes") {
            SwitchRow(
                "Confirmar antes de enviar",
                if (settings.confirmMessages) "Sara te lee el mensaje y espera un «sí»" else "Sara manda el WhatsApp sin preguntar",
                settings.confirmMessages,
            ) { v -> actions.update { it.copy(confirmMessages = v) } }
        }

        // ---------------------------------------------------------------- Privacidad
        SectionCard("Privacidad") {
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
                    EngineMode.OFFLINE -> "Nunca se envía audio"
                    else -> "Sólo la orden después de «Sara», al proveedor online elegido"
                },
                settings.recognitionMode == EngineMode.OFFLINE,
            )
            SwitchRow("Guardar historial", "Sólo texto, sin audio, máximo 100 órdenes", settings.saveHistory) { v ->
                actions.update { it.copy(saveHistory = v) }
            }
            OutlinedButton(onClick = actions.clearHistory, shape = RoundedCornerShape(50)) { Text("Borrar historial") }
            Text("Las claves de proveedores se guardan cifradas con el Keystore de Android y se excluyen de las copias de seguridad.", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
        }

        // ---------------------------------------------------------------- Sistema
        // ---------------------------------------------------------------- Rutinas y recordatorios
        SectionCard("Rutinas y recordatorios") {
            Text(
                "Decí por ejemplo «todos los días a las 8 decime el clima» o «recordame mañana a las 9 llamar al médico».",
                style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted,
            )
            if (routines.isEmpty() && reminders.isEmpty()) {
                Text("No hay rutinas ni recordatorios.", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
            }
            routines.forEach { r ->
                StatusLine(if (r.enabled) "Rutina" else "Rutina (pausada)", r.describe(), r.enabled, if (r.enabled) "Pausar" else "Activar") { actions.toggleRoutine(r) }
                TextButton(onClick = { actions.deleteRoutine(r) }) { Text("Borrar rutina", color = SaraColors.Red) }
            }
            reminders.forEach { r ->
                StatusLine("Recordatorio", "${r.text} · ${com.sara.assistant.skills.Scheduler.describeTime(r.at)}", true, "Borrar") { actions.deleteReminder(r) }
            }
        }

        // ---------------------------------------------------------------- Permisos
        SectionCard("Permisos") {
            com.sara.core.model.PermissionKind.entries.forEach { kind ->
                val info = com.sara.assistant.permissions.PermissionCenter.info(kind)
                val ok = kind !in system.missing
                StatusLine(info.title, if (ok) "Activado" else info.why, ok, if (ok) null else "Activar", if (ok) null else ({ actions.grant(kind) }))
            }
        }

        SectionCard("Sistema") {
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
            StatusLine(
                "Actualizaciones",
                when (updateState) {
                    UpdateState.Checking -> "Buscando…"
                    UpdateState.UpToDate -> "Tenés la última versión (v${BuildConfig.VERSION_NAME})"
                    is UpdateState.Available -> "Disponible ${updateState.release.tag}: actualizá desde Inicio"
                    is UpdateState.Failed -> updateState.message
                    else -> "Versión instalada v${BuildConfig.VERSION_NAME}"
                },
                updateState !is UpdateState.Available && updateState !is UpdateState.Failed,
                "Buscar", actions.checkUpdates,
            )
            HorizontalDivider(color = SaraColors.Outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnóstico", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.refresh) { Text("Actualizar", color = SaraColors.Cyan) }
            }
            system.engines.forEach { StatusLine(it.name, it.detail, it.ok) }
            Text("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Registro", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.clearDiagnostics) { Text("Limpiar", color = SaraColors.Muted) }
            }
            Text(
                diagnostics.takeLast(40).joinToString("\n").ifBlank { "Sin eventos" },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SaraColors.Muted,
            )
        }
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
                    TextButton(onClick = { if (key.isNotBlank()) actions.savePorcupineKey(key); key = "" }) { Text("Guardar AccessKey") }
                    TextButton(onClick = actions.importPorcupineKeyword) { Text("Importar .ppn") }
                    TextButton(onClick = actions.importPorcupineModel) { Text("Importar .pv") }
                }
            },
            confirmButton = { TextButton(onClick = { porcupineDialog = false }) { Text("Listo") } },
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
            TextButton(onClick = actions.deleteModel) { Text("Eliminar", color = SaraColors.Red) }
        } else if (state !is ModelState.Downloading && state != ModelState.Installing) {
            TextButton(onClick = actions.downloadModel) { Text("Descargar", color = SaraColors.Cyan) }
            TextButton(onClick = actions.importModel) { Text("Importar .zip", color = SaraColors.Muted) }
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
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
                if (hasKey) TextButton(onClick = { onClear(provider) }) { Text("Eliminar clave guardada", color = SaraColors.Red) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(provider, key.ifBlank { null }) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
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
                if (hasKey) TextButton(onClick = onClear) { Text("Eliminar clave guardada", color = SaraColors.Red) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key.ifBlank { null }, regionText) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
