package com.sara.assistant.ui

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
import com.sara.assistant.media.SaraNotificationListener
import com.sara.assistant.ui.theme.SaraColors
import com.sara.assistant.ui.theme.SaraTheme
import com.sara.core.model.AssistantState

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Acción a ejecutar cuando el usuario responde el diálogo de permisos. */
    private var afterPermissions: ((micGranted: Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (!mic) Toast.makeText(this, "Sin micrófono Sara no puede escucharte.", Toast.LENGTH_LONG).show()
        afterPermissions?.invoke(mic)
        afterPermissions = null
        viewModel.refreshSystem()
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importModel) }
    private val keywordPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importPorcupine(it, keyword = true) } }
    private val pvPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importPorcupine(it, keyword = false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(SaraColors.Background.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(SaraColors.Background.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent { SaraTheme { SaraApp() } }
        handleAssistIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssistIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
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
        HOME("Sara", Icons.Filled.Home),
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
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
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
                        Tab.HISTORY -> HistoryScreen(history, settings.saveHistory, viewModel::clearHistory)
                        Tab.SETTINGS -> SettingsScreen(
                            settings = settings, system = system, modelState = modelState, serviceRunning = running,
                            micActive = micActive, voices = voices, diagnostics = diagnostics,
                            actions = SettingsActions(
                                update = { viewModel.update(it) },
                                onWakeToggle = wakeToggle,
                                testVoice = { viewModel.testVoice() },
                                loadVoices = { viewModel.loadVoices(it) },
                                saveAzure = { k, r -> viewModel.saveAzure(k, r) },
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
                            ),
                            updateState = updateState,
                        )
                    }
                }
            }
        }
    }
}
