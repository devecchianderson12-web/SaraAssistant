package com.lucyremota.emisor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucyremota.emisor.LucyRemotaService
import com.lucyremota.emisor.container
import com.lucyremota.emisor.data.SecretStore
import com.lucyremota.remoto.SentOrder
import com.lucyremota.nucleo.model.AssistantEvent
import com.lucyremota.nucleo.model.AssistantState
import com.lucyremota.protocolo.LinkState
import com.lucyremota.protocolo.PairingQr
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Back = Color(0xFF12111A)
private val Card = Color(0xFF1C1A28)
private val TextMain = Color(0xFFEEECF8)
private val Muted = Color(0xFF9A96B8)
private val Accent = Color(0xFF8B5CF6)
private val Sky = Color(0xFF0EA5E9)
private val Good = Color(0xFF34D399)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LucyRemotaService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Back, surface = Card)) {
                Screen()
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val link by c.link.state.collectAsState()
    val state by c.orchestrator.state.collectAsState()
    val orders by c.orders.orders.collectAsState()
    val settings by c.settings.settings.collectAsState()
    val update by c.updates.available.collectAsState()
    val progress by c.updates.progress.collectAsState()
    var heard by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var typingCode by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        c.refreshWake(granted(Manifest.permission.RECORD_AUDIO))
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> scanning = ok }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(::granted)
        if (needed.isNotEmpty()) askPermissions.launch(needed.toTypedArray())
        c.updates.check()
    }
    LaunchedEffect(Unit) {
        c.orchestrator.events.collect { e ->
            when (e) {
                is AssistantEvent.Heard -> { heard = e.text; answer = "" }
                is AssistantEvent.Responded -> if (e.text.isNotBlank()) answer = e.text
                is AssistantEvent.Failed -> answer = e.error.message
                is AssistantEvent.Notice -> answer = e.text
                else -> Unit
            }
        }
    }

    if (scanning) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            QrScanner(Modifier.fillMaxSize()) { text ->
                scanning = false
                val qr = PairingQr.parse(text)
                if (qr == null) answer = "Ese QR no es de Lucy Remota. Abrí «Vincular teléfono» en la PC."
                else { c.link.pairWithQr(qr); answer = "Escaneado. Conectando con ${qr.pcName}…" }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(24.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Apuntá al QR que muestra Lucy Remota en la computadora", color = Color.White)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { scanning = false }) { Text("Cancelar", color = Color.White) }
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Back).statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Lucy Remota", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Emisor · controlá tu computadora con la voz", color = Muted)
        }
        update?.let { u ->
            item {
                Panel {
                    Text("Hay una versión nueva (V${u.version}).", color = TextMain)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { scope.launch { c.updates.install(u)?.let { answer = it } } }) {
                        Text(progress?.let { "Bajando… $it %" } ?: "Actualizar")
                    }
                }
            }
        }
        item { ConnectionPanel(link, onScan = { if (granted(Manifest.permission.CAMERA)) scanning = true else askCamera.launch(Manifest.permission.CAMERA) },
            onCode = { typingCode = true }, onCancel = { c.link.cancelPairing() }, onUnpair = { scope.launch { c.link.unpair() } }) }
        if (typingCode) item { CodePanel(onDone = { code -> typingCode = false; c.link.pairWithCode(code); answer = "Conectando…" }, onCancel = { typingCode = false }) }
        item {
            TalkPanel(state, heard, answer, settings.wakeWord, onTalk = {
                if (!granted(Manifest.permission.RECORD_AUDIO)) askPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                else c.orchestrator.activate()
            })
        }
        item {
            Panel {
                SwitchRow("Escuchar «Lucy»", "Con la pantalla apagada también (notificación fija).", settings.wakeWord) { on ->
                    c.settings.update { it.copy(wakeWord = on) }
                    c.refreshWake(granted(Manifest.permission.RECORD_AUDIO))
                }
            }
        }
        if (orders.isNotEmpty()) {
            item { Text("Órdenes enviadas", color = Muted, fontWeight = FontWeight.SemiBold) }
            items(orders.take(30), key = { it.id }) { OrderRow(it) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Ocultar ajustes" else "Ajustes") }
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Ocultar diagnóstico" else "Diagnóstico") }
            }
        }
        if (showSettings) item { SettingsPanel() }
        if (showLog) item { LogPanel() }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(18.dp)) { content() }
}

@Composable
private fun ConnectionPanel(link: LinkState, onScan: () -> Unit, onCode: () -> Unit, onCancel: () -> Unit, onUnpair: () -> Unit) {
    Panel {
        val (dot, title, detail) = when (link) {
            is LinkState.Connected -> Triple(Good, "Conectada con ${link.pcName}", "${link.address}" + if (link.latencyMs > 0) " · ${link.latencyMs} ms" else "")
            is LinkState.Searching -> Triple(Warn, "Buscando ${link.pcName}…", "Abrí Lucy Remota en la computadora. Tienen que estar en la misma red Wi-Fi (o la PC conectada al hotspot del teléfono).")
            is LinkState.Pairing -> Triple(Sky, "Vinculando…", "Esperando a ${link.pcName ?: "la computadora"}. Dejá abierta la ventana del QR en la PC.")
            is LinkState.PairingFailed -> Triple(Bad, "No se pudo vincular", link.reason)
            LinkState.Unpaired -> Triple(Muted, "Ninguna computadora vinculada", "En la PC abrí Lucy Remota (Receptor) y tocá «Vincular teléfono».")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(detail, color = Muted)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (link) {
                is LinkState.Connected, is LinkState.Searching -> OutlinedButton(onClick = onUnpair) { Text("Desvincular") }
                is LinkState.Pairing -> OutlinedButton(onClick = onCancel) { Text("Cancelar") }
                else -> {
                    Button(onClick = onScan) { Text("Escanear QR") }
                    OutlinedButton(onClick = onCode) { Text("Escribir código") }
                }
            }
        }
    }
}

@Composable
private fun CodePanel(onDone: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Panel {
        Text("Escribí el código que muestra la computadora", color = TextMain)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.uppercase().take(9) }, singleLine = true, label = { Text("Ej.: K7M2-9XQ4") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onDone(code) }, enabled = code.count { it.isLetterOrDigit() } == 8) { Text("Vincular") }
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        }
    }
}

@Composable
private fun TalkPanel(state: AssistantState, heard: String, answer: String, wakeOn: Boolean, onTalk: () -> Unit) {
    val active = state is AssistantState.Listening || state is AssistantState.Processing || state is AssistantState.Speaking
    val pulse = rememberInfiniteTransition(label = "pulso")
    val scale by pulse.animateFloat(1f, if (active) 1.08f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "escala")
    Panel {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(132.dp).scale(scale).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent, Sky)))
                    .border(2.dp, Color.White.copy(alpha = if (active) 0.7f else 0.15f), CircleShape)
                    .clickable(onClick = onTalk),
                contentAlignment = Alignment.Center,
            ) { Text(if (active) "…" else "Hablar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(12.dp))
            Text(
                when (state) {
                    is AssistantState.Listening -> state.partialText?.takeIf { it.isNotBlank() } ?: "Te escucho…"
                    is AssistantState.Processing -> "Mandando a la computadora…"
                    is AssistantState.Speaking -> "Hablando…"
                    else -> if (wakeOn) "Decí «Lucy» o tocá el círculo" else "Tocá el círculo para hablar"
                },
                color = Muted,
            )
            if (heard.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text("Vos: «$heard»", color = TextMain) }
            if (answer.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(answer, color = Accent) }
        }
    }
}

@Composable
private fun OrderRow(o: SentOrder) {
    val (label, color) = when (o.status) {
        "received" -> "Recibida" to Sky
        "processing" -> "En curso" to Sky
        "success" -> "Hecho" to Good
        "needs_confirmation" -> "Confirmar" to Warn
        "not_connected" -> "Sin conexión" to Bad
        "no_answer" -> "Sin respuesta" to Bad
        "expired" -> "Llegó tarde" to Bad
        "unsupported" -> "No disponible" to Bad
        else -> "Error" to Bad
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(o.heard.ifBlank { o.intent }, color = TextMain)
            Text(o.message.ifBlank { o.intent }, color = Muted, fontSize = 13.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(label, color = color, fontWeight = FontWeight.SemiBold)
            Text(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(o.at)), color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain)
            Text(detail, color = Muted, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsPanel() {
    val c = androidx.compose.ui.platform.LocalContext.current.container
    val s by c.settings.settings.collectAsState()
    var key by remember { mutableStateOf(if (c.secrets.get(SecretStore.GEMINI_KEY) != null) "••••••••" else "") }
    Panel {
        SwitchRow("Voz Daniela", "Natural y sin Internet (se baja una vez). Apagado: la voz del teléfono.", s.usePiper) { on ->
            c.settings.update { it.copy(usePiper = on) }
            c.ensureModels()
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow("Respuesta rápida", "Hace las órdenes cortas apenas las entiende.", s.quickResponse) { on -> c.settings.update { it.copy(quickResponse = on) } }
        Spacer(Modifier.height(10.dp))
        SwitchRow("Charlar", "Después de una pregunta sigue escuchando sin decir «Lucy».", s.chatFollowUp) { on -> c.settings.update { it.copy(chatFollowUp = on) } }
        Spacer(Modifier.height(14.dp))
        Text("Clave gratis de Google (Gemini) para preguntas", color = TextMain)
        Text("Sin clave, Lucy igual controla la PC y contesta lo básico.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Clave") })
        TextButton(onClick = { if (!key.startsWith("•")) c.secrets.put(SecretStore.GEMINI_KEY, key.trim()) }) { Text("Guardar clave") }
    }
}

@Composable
private fun LogPanel() {
    val c = androidx.compose.ui.platform.LocalContext.current.container
    val lines by c.logger.lines.collectAsState()
    Panel {
        lines.takeLast(60).reversed().forEach { Text(it, color = Muted, fontSize = 12.sp) }
        if (lines.isEmpty()) Text("Sin registros todavía.", color = Muted)
    }
}
