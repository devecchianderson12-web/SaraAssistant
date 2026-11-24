package com.lucyremota.emisor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucyremota.emisor.container
import com.lucyremota.protocolo.LinkState
import com.lucyremota.protocolo.PairingQr
import com.lucyremota.remoto.SentOrder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Back = Color(0xFF0A0B10)
private val Card = Color(0xFF1C1A28)
private val TextMain = Color(0xFFEEECF8)
private val Muted = Color(0xFF9A96B8)
private val Sky = Color(0xFF0EA5E9)
private val Good = Color(0xFF34D399)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)

/** Ícono de la pestaña: un monitor. */
val MonitorIcon: ImageVector = ImageVector.Builder("monitor", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
    moveTo(2f, 4f); lineTo(22f, 4f); lineTo(22f, 17f); lineTo(2f, 17f); close()
    moveTo(8f, 19f); lineTo(16f, 19f); lineTo(16f, 21f); lineTo(8f, 21f); close()
}.build()

/**
 * Pestaña «Computadoras»: las PC vinculadas como tarjetas (una conexión a la vez), agregar otra con
 * su QR o código, y las órdenes que se mandaron.
 */
@Composable
fun PcScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val link by c.link.state.collectAsState()
    val pcs by c.phoneStore.pcs.collectAsState()
    val activePc by c.phoneStore.active.collectAsState()
    val seen by c.link.seen.collectAsState()
    val orders by c.orders.orders.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var typingCode by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }

    if (scanning) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            QrScanner(Modifier.fillMaxSize()) { text ->
                scanning = false
                val qr = PairingQr.parse(text)
                if (qr == null) answer = "Ese QR no es de Lucy Remota. Abrí «Vincular» en la PC."
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
        Modifier.fillMaxSize().background(Back),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Computadoras", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("Decí «Lucy, poné música» y suena en la PC conectada. Sin PC, lo hago en el teléfono.", color = Muted)
            if (answer.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(answer, color = Sky) }
        }
        item {
            ConnectionPanel(
                link, pcs, activePc, seen,
                onScan = {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                    else askCamera.launch(Manifest.permission.CAMERA)
                },
                onCode = { typingCode = true }, onCancel = { c.link.cancelPairing() },
                onConnect = { id -> c.link.selectPc(id) }, onDisconnect = { c.link.disconnect() },
                onForget = { id -> if (id == activePc) scope.launch { c.link.unpair() } else c.link.forget(id) },
            )
        }
        if (typingCode) item { CodePanel(onDone = { code -> typingCode = false; c.link.pairWithCode(code); answer = "Conectando…" }, onCancel = { typingCode = false }) }
        if (orders.isNotEmpty()) {
            item { Text("Órdenes a la computadora", color = Muted, fontWeight = FontWeight.SemiBold) }
            items(orders.take(30), key = { it.id }) { OrderRow(it) }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(18.dp)) { content() }
}

@Composable
private fun ConnectionPanel(
    link: LinkState, pcs: List<com.lucyremota.protocolo.PairedPc>, activePc: String?, seen: Map<String, Long>,
    onScan: () -> Unit, onCode: () -> Unit, onCancel: () -> Unit,
    onConnect: (String) -> Unit, onDisconnect: () -> Unit, onForget: (String) -> Unit,
) {
    // Vinculando o con error: el aviso arriba de la lista.
    when (link) {
        is LinkState.Pairing -> Panel {
            StatusLine(Sky, "Vinculando…", "Esperando a ${link.pcName ?: "la computadora"}. Dejá abierta la ventana del QR en la PC.")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        }
        is LinkState.PairingFailed -> Panel { StatusLine(Bad, "No se pudo vincular", link.reason) }
        else -> Unit
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tus computadoras", color = Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCode) { Text("Código") }
            Button(onClick = onScan) { Text(if (pcs.isEmpty()) "Escanear QR" else "+ Agregar") }
        }
        if (pcs.isEmpty()) {
            Panel { StatusLine(Muted, "Ninguna computadora vinculada", "En la PC abrí Lucy Remota y tocá «Vincular». Cada PC se vincula una sola vez; después elegís a cuál conectarte.") }
        }
        val now = System.currentTimeMillis()
        pcs.forEachIndexed { i, pc ->
            val active = pc.id == activePc
            val connected = active && link is LinkState.Connected
            val nearby = (seen[pc.id] ?: 0L) > now - 12_000
            val (dot, detail) = when {
                connected -> Good to ((link as LinkState.Connected).let { (if (it.address == "Internet") "Conectada por Internet" else "Conectada · ${it.address}") + if (it.latencyMs > 0) " · ${it.latencyMs} ms" else "" })
                active -> Warn to "Conectando…"
                nearby -> Good to "Disponible en tu red"
                else -> Muted to "Tocá para conectarte"
            }
            DeviceCard(
                name = pc.name, detail = detail, dot = dot, color = PcColors[i % PcColors.size], highlighted = active,
                onClick = { if (!active) onConnect(pc.id) },
                menu = buildList {
                    if (active) add("Desconectar" to onDisconnect) else add("Conectar" to { onConnect(pc.id) })
                    add("Desvincular" to { onForget(pc.id) })
                },
            )
        }
        if (pcs.size > 1) Text("Te conectás a una computadora a la vez: al elegir otra, se desconecta de la anterior.", color = Muted, fontSize = 13.sp)
    }
}

private val PcColors = listOf(Color(0xFF1E2A8A), Color(0xFF6B21A8), Color(0xFF0F766E), Color(0xFF9A3412), Color(0xFF1D4ED8))

@Composable
private fun StatusLine(dot: Color, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(6.dp))
    Text(detail, color = Muted)
}

/** Una computadora vinculada, como en la lista de dispositivos de una app de escritorio remoto. */
@Composable
private fun DeviceCard(name: String, detail: String, dot: Color, color: Color, highlighted: Boolean, onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (highlighted) Color(0xFF26233A) else Card)
            .then(if (highlighted) Modifier.border(1.dp, Sky.copy(alpha = 0.5f), RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowsTile(color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(name, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Text(detail, color = Muted, fontSize = 14.sp)
        }
        Box {
            TextButton(onClick = { open = true }) { Text("⋮", color = TextMain, fontSize = 22.sp) }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                }
            }
        }
    }
}

/** Ícono de PC con Windows: un cuadro de color con la ventana de cuatro partes. */
@Composable
private fun WindowsTile(color: Color) {
    Box(Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(color), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
            val gap = size.width * 0.08f
            val half = (size.width - gap) / 2
            for (x in 0..1) for (y in 0..1) {
                drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(x * (half + gap), y * (half + gap)), size = androidx.compose.ui.geometry.Size(half, half))
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
