package com.sara.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sara.assistant.ui.theme.SaraColors
import com.sara.assistant.update.ReleaseInfo
import com.sara.assistant.update.UpdateState

/** Cartel discreto: sólo aparece si hay una versión realmente más nueva en GitHub Releases. */
@Composable
fun UpdateBanner(state: UpdateState, onUpdate: (ReleaseInfo) -> Unit, onDismiss: () -> Unit, onGrantPermission: () -> Unit) {
    val visible = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Installing ||
        state is UpdateState.NeedsPermission || (state is UpdateState.Failed && state.release != null)
    val actionColors = ButtonDefaults.buttonColors(containerColor = SaraColors.Cyan, contentColor = SaraColors.Background)
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(SaraColors.Cyan.copy(alpha = 0.10f))
                .border(1.dp, SaraColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is UpdateState.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nueva versión disponible", style = MaterialTheme.typography.titleMedium)
                        Text(state.release.tag, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
                    }
                    TextButton(onClick = onDismiss) { Text("Luego", color = SaraColors.Muted) }
                    Button(onClick = { onUpdate(state.release) }, shape = RoundedCornerShape(50), colors = actionColors) { Text("Actualizar") }
                }
                is UpdateState.Downloading -> {
                    Text("Descargando ${state.release.tag}… ${(state.progress * 100).toInt()} %", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = SaraColors.Cyan, trackColor = SaraColors.SurfaceHigh)
                }
                is UpdateState.Installing -> {
                    Text("Instalando ${state.release.tag}… confirmá en la ventana del sistema.", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SaraColors.Cyan, trackColor = SaraColors.SurfaceHigh)
                }
                is UpdateState.NeedsPermission -> {
                    Text("Para instalar la actualización, permití que SaraAssistant instale apps.", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onGrantPermission, shape = RoundedCornerShape(50), colors = actionColors) { Text("Permitir") }
                        TextButton(onClick = onDismiss) { Text("Luego", color = SaraColors.Muted) }
                    }
                }
                is UpdateState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Amber, modifier = Modifier.weight(1f))
                    state.release?.let { release -> TextButton(onClick = { onUpdate(release) }) { Text("Reintentar", color = SaraColors.Cyan) } }
                    TextButton(onClick = onDismiss) { Text("Cerrar", color = SaraColors.Muted) }
                }
                else -> Unit
            }
        }
    }
}
