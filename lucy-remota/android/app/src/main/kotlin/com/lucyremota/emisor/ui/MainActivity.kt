package com.lucyremota.emisor.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucyremota.emisor.media.SaraNotificationListener
import com.lucyremota.emisor.ui.theme.SaraColors
import com.lucyremota.emisor.ui.theme.SaraTheme
import com.lucyremota.nucleo.model.AssistantState

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Acción a ejecutar cuando el usuario responde el diálogo de permisos. */
    private var afterPermissions: ((micGranted: Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (!mic) Toast.makeText(this, "Sin micrófono Lucy no puede escucharte.", Toast.LENGTH_LONG).show()
        afterPermissions?.invoke(mic)
        afterPermissions = null
        viewModel.refreshSystem()
    }
    private val grantLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // «No volver a preguntar»: el único camino es la pantalla de permisos de la app.
            Toast.makeText(this, "Activalo en Permisos.", Toast.LENGTH_LONG).show()
            openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        viewModel.refreshSystem()
    }

    /** Lleva directo a conceder [kind]: diálogo del sistema o la pantalla exacta de Ajustes. */
    /** «Activar» de la clave de IA: abre Ajustes con el cuadro para cargarla. */
    private val aiDialogRequested = androidx.compose.runtime.mutableStateOf(false)

    private fun grant(kind: com.lucyremota.nucleo.model.PermissionKind) {
        val runtime = com.lucyremota.emisor.permissions.PermissionCenter.runtimePermission(kind)
        if (kind == com.lucyremota.nucleo.model.PermissionKind.AI_KEY) {
            aiDialogRequested.value = true
        } else if (runtime != null) {
            grantLauncher.launch(runtime)
        } else if (com.lucyremota.emisor.permissions.PermissionCenter.isRestricted(kind) && com.lucyremota.emisor.permissions.PermissionCenter.restrictedSettingsBlocked(this)) {
            // Android lo tiene bloqueado (app instalada desde un archivo): primero hay que destrabarlo.
            android.app.AlertDialog.Builder(this)
                .setTitle("Android bloqueó este permiso")
                .setMessage(
                    "Pasa con las apps instaladas desde un archivo (no desde Play Store).\n\n" +
                        "1. En la pantalla que se abre, tocá ⋮ (arriba a la derecha).\n" +
                        "2. Elegí «Permitir ajustes restringidos» y confirmá.\n" +
                        "3. Volvé a Lucy y tocá «Activar» otra vez: ahora sí se puede prender.",
                )
                .setPositiveButton("Abrir") { _, _ -> openAppSettings() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            openSafely(*com.lucyremota.emisor.permissions.PermissionCenter.settingsIntents(this, kind).toTypedArray())
        }
    }

    private fun handleGrantIntent(intent: Intent?) {
        val name = intent?.getStringExtra(com.lucyremota.emisor.permissions.PermissionCenter.EXTRA_GRANT) ?: return
        intent.removeExtra(com.lucyremota.emisor.permissions.PermissionCenter.EXTRA_GRANT)
        com.lucyremota.nucleo.model.PermissionKind.entries.firstOrNull { it.name == name }?.let(::grant)
    }

    // ---------------------------------------------------------------- «Darle todos los permisos»
    // Android exige que el usuario apruebe cada permiso; Sara lo hace lo más corto posible: un solo
    // diálogo para todos los permisos comunes y después cada pantalla especial, una tras otra.

    private var wizardActive = false
    private val wizardTried = mutableSetOf<com.lucyremota.nucleo.model.PermissionKind>()

    private val wizardRuntimeLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshSystem()
        continueWizard()
    }

    private fun startGrantAll() {
        wizardActive = true
        wizardTried.clear()
        val runtime = com.lucyremota.emisor.permissions.PermissionCenter.missing(this)
            .mapNotNull { com.lucyremota.emisor.permissions.PermissionCenter.runtimePermission(it) }
            .plus(if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList())
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            .distinct()
        if (runtime.isNotEmpty()) wizardRuntimeLauncher.launch(runtime.toTypedArray()) else continueWizard()
    }

    /** Abre la siguiente pantalla de permiso que falta (cada una se intenta una sola vez por ronda). */
    private fun continueWizard() {
        if (!wizardActive) return
        val next = com.lucyremota.emisor.permissions.PermissionCenter.missing(this).firstOrNull { kind ->
            kind !in wizardTried && com.lucyremota.emisor.permissions.PermissionCenter.runtimePermission(kind) == null &&
                kind != com.lucyremota.nucleo.model.PermissionKind.AI_KEY
        }
        if (next == null) {
            wizardActive = false
            viewModel.refreshSystem()
            val left = com.lucyremota.emisor.permissions.PermissionCenter.missing(this).filter { it != com.lucyremota.nucleo.model.PermissionKind.AI_KEY }
            Toast.makeText(
                this,
                if (left.isEmpty()) "Listo: Lucy tiene todos los permisos." else "Faltan ${left.size}: están en Ajustes › Permisos.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        wizardTried += next
        val info = com.lucyremota.emisor.permissions.PermissionCenter.info(next)
        Toast.makeText(this, "Activá «${info.title}» y volvé con Atrás.", Toast.LENGTH_LONG).show()
        grant(next)
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importModel) }
    private val keywordPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importPorcupine(it, keyword = true) } }
    private val pvPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importPorcupine(it, keyword = false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.lucyremota.emisor.SaraApp.containerOrNull(this)?.appTaskAlive?.value = true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(SaraColors.Background.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(SaraColors.Background.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent { SaraTheme { SaraApp() } }
        handleAssistIntent(intent)
        handleGrantIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssistIntent(intent)
        handleGrantIntent(intent)
    }

    override fun onResume() {
        com.lucyremota.emisor.permissions.PermissionCenter.rebindNotificationListener(this)
        super.onResume()
        viewModel.onResume()
        // Al volver de una pantalla de Ajustes, sigue con el próximo permiso.
        if (wizardActive) window.decorView.postDelayed({ continueWizard() }, 400)
    }

    /** Botón de asistente de auriculares / ACTION_ASSIST: empieza a escuchar directamente. */
    private fun handleAssistIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND) {
            withMicPermission { viewModel.activate() }
        }
    }

    private fun withMicPermission(includeNotifications: Boolean = false, block: () -> Unit) {
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (includeNotifications && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            block()
            return
        }
        afterPermissions = { granted -> if (granted) block() }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun openSafely(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, "No se pudo abrir la configuración.", Toast.LENGTH_SHORT).show()
    }

    private fun openNotificationAccess() {
        val component = ComponentName(this, SaraNotificationListener::class.java)
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            null
        }
        openSafely(*listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).toTypedArray())
    }

    private fun openAppSettings() = openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun openAssistantSettings() = openSafely(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    private fun openBatterySettings() = openSafely(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    private enum class Tab(val label: String, val icon: ImageVector) {
        HOME("Lucy", Icons.Filled.Home),
        PCS("PC", MonitorIcon),
        HISTORY("Historial", Icons.AutoMirrored.Filled.List),
        SETTINGS("Ajustes", Icons.Filled.Settings),
    }

    @Composable
    private fun SaraApp() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val level by viewModel.level.collectAsStateWithLifecycle()
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        val conversation by viewModel.conversation.collectAsStateWithLifecycle()
        val system by viewModel.system.collectAsStateWithLifecycle()
        val modelState by viewModel.modelState.collectAsStateWithLifecycle()
        val running by viewModel.serviceRunning.collectAsStateWithLifecycle()
        val micActive by viewModel.micActive.collectAsStateWithLifecycle()
        val micSilenced by viewModel.micSilenced.collectAsStateWithLifecycle()
        val history by viewModel.history.collectAsStateWithLifecycle()
        val voices by viewModel.voices.collectAsStateWithLifecycle()
        val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
        val message by viewModel.message.collectAsStateWithLifecycle()
        val updateState by viewModel.updateState.collectAsStateWithLifecycle()
        val memories by viewModel.memories.collectAsStateWithLifecycle()
        val routines by viewModel.routines.collectAsStateWithLifecycle()
        val reminders by viewModel.reminders.collectAsStateWithLifecycle()
        val micCompare by viewModel.micCompare.collectAsStateWithLifecycle()
        val piperState by viewModel.piperState.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        val aiRequested by aiDialogRequested
        LaunchedEffect(aiRequested) { if (aiRequested) tab = Tab.SETTINGS }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                viewModel.consumeMessage()
            }
        }

        val wakeToggle: (Boolean) -> Unit = { enabled ->
            if (enabled) withMicPermission(includeNotifications = true) { viewModel.setWakeWord(true) } else viewModel.setWakeWord(false)
        }

        Scaffold(
            containerColor = SaraColors.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = SaraColors.Surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item; if (item == Tab.SETTINGS) viewModel.refreshSystem() },
                            icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = SaraColors.Text,
                                selectedTextColor = SaraColors.Text,
                                indicatorColor = SaraColors.Violet.copy(alpha = 0.28f),
                                unselectedIconColor = SaraColors.Muted,
                                unselectedTextColor = SaraColors.Muted,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                    when (current) {
                        Tab.HOME -> HomeScreen(
                            state = state, level = level, settings = settings, conversation = conversation, system = system,
                            modelState = modelState, serviceRunning = running, micActive = micActive, micSilenced = micSilenced,
                            onOrbClick = {
                                if (state is AssistantState.Listening || state is AssistantState.Speaking || state is AssistantState.Processing) {
                                    viewModel.cancel()
                                } else {
                                    withMicPermission { viewModel.activate() }
                                }
                            },
                            onWakeToggle = wakeToggle,
                            onRequestMic = { withMicPermission(includeNotifications = true) { viewModel.refreshSystem() } },
                            onDownloadModel = viewModel::downloadModel,
                            onNotificationAccess = ::openNotificationAccess,
                            onGrant = ::grant,
                            onGrantAll = ::startGrantAll,
                            updateBanner = {
                                UpdateBanner(
                                    state = updateState,
                                    onUpdate = { viewModel.installUpdate(it) },
                                    onDismiss = viewModel::dismissUpdate,
                                    onGrantPermission = {
                                        openSafely(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                                    },
                                )
                            },
                        )
                        Tab.PCS -> PcScreen()
                        Tab.HISTORY -> HistoryScreen(history, settings.saveHistory, viewModel::clearHistory)
                        Tab.SETTINGS -> SettingsScreen(
                            settings = settings, system = system, modelState = modelState, serviceRunning = running,
                            micActive = micActive, voices = voices, diagnostics = diagnostics, memories = memories,
                            aiDialogRequested = aiRequested, onAiDialogShown = { aiDialogRequested.value = false },
                            routines = routines, reminders = reminders,
                            actions = SettingsActions(
                                update = { viewModel.update(it) },
                                onWakeToggle = wakeToggle,
                                testVoice = { viewModel.testVoice() },
                                loadVoices = { viewModel.loadVoices(it) },
                                saveAzure = { k, r -> viewModel.saveAzure(k, r) },
                                saveAiKey = { p, k -> viewModel.saveAiKey(p, k) },
                                clearAiKey = viewModel::clearAiKey,
                                forget = viewModel::forget,
                                grant = ::grant,
                                grantAll = ::startGrantAll,
                                toggleRoutine = viewModel::toggleRoutine,
                                deleteRoutine = viewModel::deleteRoutine,
                                deleteReminder = viewModel::deleteReminder,
                                clearAzure = viewModel::clearAzure,
                                savePorcupineKey = viewModel::savePorcupineKey,
                                importPorcupineKeyword = { keywordPicker.launch(arrayOf("*/*")) },
                                importPorcupineModel = { pvPicker.launch(arrayOf("*/*")) },
                                downloadModel = { viewModel.downloadModel() },
                                importModel = { modelPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                                deleteModel = { viewModel.deleteModel() },
                                requestMic = { withMicPermission(includeNotifications = true) { viewModel.refreshSystem() } },
                                openNotificationAccess = ::openNotificationAccess,
                                openAppSettings = ::openAppSettings,
                                openAssistantSettings = ::openAssistantSettings,
                                openBatterySettings = ::openBatterySettings,
                                clearHistory = { viewModel.clearHistory() },
                                clearDiagnostics = viewModel::clearDiagnostics,
                                refresh = { viewModel.refreshSystem() },
                                checkUpdates = { viewModel.checkUpdates() },
                                openOverlaySettings = {
                                    openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                                },
                                openAccessibilitySettings = { openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                compareMicrophones = { withMicPermission { viewModel.compareMicrophones() } },
                                dismissMicCompare = viewModel::dismissMicCompare,
                                installUpdate = { viewModel.installUpdate(it) },
                                downloadPiper = { viewModel.downloadPiper(it) },
                            ),
                            updateState = updateState,
                            micCompare = micCompare,
                            piperState = piperState,
                        )
                    }
                }
            }
        }
    }
}
