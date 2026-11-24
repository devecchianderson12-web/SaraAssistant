package com.lucyremota.emisor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lucyremota.emisor.ui.theme.SaraColors
import com.lucyremota.nucleo.model.HistoryEntry
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(entries: List<HistoryEntry>, saveHistory: Boolean, onClear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Historial", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (saveHistory) "Sólo texto, últimas 100 órdenes, guardado en tu teléfono" else "El historial está desactivado",
                    style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted,
                )
            }
            if (entries.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("Borrar", color = SaraColors.Red) }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Todavía no hay órdenes.\nProbá decir «Lucy, poné música».", color = SaraColors.Muted, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.timestampMillis.toString() + it.heard }) { entry -> HistoryItem(entry) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar historial?") },
            text = { Text("Se eliminan todas las órdenes guardadas en este teléfono.") },
            confirmButton = { TextButton(onClick = { confirm = false; onClear() }) { Text("Borrar", color = SaraColors.Red) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
            containerColor = SaraColors.SurfaceHigh,
        )
    }
}

@Composable
private fun HistoryItem(entry: HistoryEntry) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SaraColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (entry.success) SaraColors.Green else SaraColors.Amber))
            Spacer(Modifier.width(8.dp))
            Text(entry.intentLabel, style = MaterialTheme.typography.labelLarge, color = SaraColors.Violet, modifier = Modifier.weight(1f))
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestampMillis)), style = MaterialTheme.typography.labelMedium, color = SaraColors.Muted)
        }
        Text("«${entry.heard}»", style = MaterialTheme.typography.bodyLarge)
        Text(entry.response, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
    }
}
