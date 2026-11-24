package com.lucyremota.emisor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lucyremota.emisor.settings.SaraSettings
import com.lucyremota.emisor.settings.WakeEngineType
import com.lucyremota.emisor.speech.vosk.ModelState
import com.lucyremota.emisor.ui.theme.SaraColors
import com.lucyremota.emisor.ui.theme.color
import com.lucyremota.emisor.ui.theme.title
import com.lucyremota.nucleo.model.AssistantState

@Composable
fun HomeScreen(
    state: AssistantState,
    level: Float,
    settings: SaraSettings,
    conversation: Conversation,
    system: SystemStatus,
    modelState: ModelState,
    serviceRunning: Boolean,
    micActive: Boolean,
    micSilenced: Boolean,
    onOrbClick: () -> Unit,
    onWakeToggle: (Boolean) -> Unit,
    onRequestMic: () -> Unit,
    onDownloadModel: () -> Unit,
    onNotificationAccess: () -> Unit,
    onGrant: (com.lucyremota.nucleo.model.PermissionKind) -> Unit = {},
    onGrantAll: () -> Unit = {},
    updateBanner: @Composable () -> Unit = {},
) {
    val permissions = system.permissions
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lucy", style = MaterialTheme.typography.headlineMedium)
                Text("Tu asistente de voz", style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
            }
            // Con el reconocedor de Google el micrófono lo usa el sistema, no Sara: igual está escuchando.
            val micActive = micActive || state is AssistantState.Listening
            Pill(
                text = when {
                    micSilenced && micActive -> "Micrófono sin audio"
                    micActive -> "Micrófono activo"
                    else -> "Micrófono apagado"
                },
                color = when {
                    micSilenced && micActive -> SaraColors.Amber
                    micActive -> SaraColors.Green
                    else -> SaraColors.Idle
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        updateBanner()
        Spacer(Modifier.height(12.dp))
        SaraOrb(state, level, enabled = settings.manualActivationEnabled, size = 280.dp, onClick = onOrbClick)
        Spacer(Modifier.height(8.dp))

        AnimatedContent(state.title(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { title ->
            Text(title, style = MaterialTheme.typography.titleLarge, color = state.color())
        }
        Text(
            when (state) {
                AssistantState.Inactive -> if (settings.manualActivationEnabled) "Tocá el círculo para hablar" else "Activación manual desactivada"
                AssistantState.WaitingForWakeWord -> if (settings.heySara) "Decí «Ey Lucy» cuando quieras" else "Decí «Lucy» cuando quieras"
                is AssistantState.Listening -> state.partialText.ifBlank { "Decime qué necesitás" }
                is AssistantState.Processing -> "«${state.text}»"
                is AssistantState.Executing -> "En eso…"
                is AssistantState.Speaking -> state.text
                is AssistantState.Error -> state.error.message
            },
            style = MaterialTheme.typography.bodyLarge,
            color = SaraColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp).height(48.dp),
        )

        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedVisibility(conversation.heard.isNotBlank() || conversation.response.isNotBlank()) {
                ConversationCard(conversation)
            }

            // Cada permiso que falta, con su botón directo. El que pidió la última orden va primero.
            // Un solo botón para darle todo lo que necesita (cada permiso lo aprueba el usuario).
            if (system.missing.any { it != com.lucyremota.nucleo.model.PermissionKind.AI_KEY }) {
                SetupCard(
                    title = "Darle a Lucy todos los permisos",
                    text = "Faltan ${system.missing.count { it != com.lucyremota.nucleo.model.PermissionKind.AI_KEY }}. Te los pido todos seguidos: aceptá cada uno y volvé con Atrás.",
                    action = "Dar todos",
                    onAction = onGrantAll,
                )
            }
            // En Inicio sólo lo esencial y lo que pidió la última orden; el resto está en Ajustes › Permisos.
            val pending = system.missing
                .filter { it in com.lucyremota.emisor.permissions.PermissionCenter.ESSENTIAL || it == conversation.permission }
                .sortedByDescending { it == conversation.permission }
            pending.forEach { kind ->
                val info = com.lucyremota.emisor.permissions.PermissionCenter.info(kind)
                SetupCard(
                    title = if (kind == conversation.permission) "Falta: ${info.title}" else info.title,
                    text = info.why,
                    action = "Activar",
                    onAction = { onGrant(kind) },
                )
            }
            if (settings.wakeEngine == WakeEngineType.VOSK && modelState !is ModelState.Ready) {
                ModelCard(modelState, onDownloadModel)
            }

            SectionCard("Activación por voz") {
                SwitchRow(
                    title = if (settings.heySara) "Escuchar «Ey Lucy»" else "Escuchar «Lucy»",
                    subtitle = when {
                        settings.wakeWordEnabled && serviceRunning -> "Activa · también con la pantalla apagada o bloqueada"
                        settings.wakeWordEnabled -> "Activada, esperando permisos o modelo"
                        else -> "Detección desactivada"
                    },
                    checked = settings.wakeWordEnabled,
                    onChange = onWakeToggle,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConversationCard(conversation: Conversation) {
    SectionCard("Última interacción") {
        if (conversation.heard.isNotBlank()) {
            Text("Vos", style = MaterialTheme.typography.labelMedium, color = SaraColors.Muted)
            Text(conversation.heard, style = MaterialTheme.typography.bodyLarge)
        }
        if (conversation.response.isNotBlank()) {
            Text("Lucy", style = MaterialTheme.typography.labelMedium, color = SaraColors.Pink)
            Text(conversation.response, style = MaterialTheme.typography.bodyLarge)
        }
        if (conversation.notice.isNotBlank()) {
            Text(conversation.notice, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Amber)
        }
    }
}

@Composable
fun SetupCard(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(SaraColors.Violet.copy(alpha = 0.10f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
        Button(onClick = onAction, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = SaraColors.Violet)) {
            Text(action)
        }
    }
}

@Composable
private fun ModelCard(state: ModelState, onDownload: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(SaraColors.Cyan.copy(alpha = 0.08f)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Modelo de voz local", style = MaterialTheme.typography.titleMedium)
        when (state) {
            is ModelState.Downloading -> {
                Text("Descargando… ${(state.progress * 100).toInt()} %", color = SaraColors.Muted)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = SaraColors.Cyan, trackColor = SaraColors.SurfaceHigh)
            }
            ModelState.Installing -> {
                Text("Instalando…", color = SaraColors.Muted)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SaraColors.Cyan, trackColor = SaraColors.SurfaceHigh)
            }
            else -> {
                Text(
                    (if (state is ModelState.Failed) state.message + "\n" else "") +
                        "Para reconocer «Lucy» y entenderte sin Internet hace falta un modelo en español de unos 40 MB. Se descarga una sola vez y todo se procesa en tu teléfono.",
                    style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted,
                )
                OutlinedButton(onClick = onDownload, shape = RoundedCornerShape(50)) { Text("Descargar modelo (40 MB)") }
            }
        }
    }
}
