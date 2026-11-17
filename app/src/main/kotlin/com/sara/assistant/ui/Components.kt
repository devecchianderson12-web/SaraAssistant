package com.sara.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import kotlin.math.sin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sara.assistant.R
import com.sara.assistant.ui.theme.SaraColors
import com.sara.assistant.ui.theme.color
import com.sara.core.model.AssistantState

/**
 * Indicador central: un orbe con anillos que respiran según el estado y reaccionan al nivel
 * real de audio (micrófono o voz de Sara). Se dibuja en un único Canvas para mantener 60 fps.
 */
/**
 * Nebulosa al estilo «Jarvis»: nubes de color que giran y se mezclan, partículas, anillos
 * técnicos que rotan y barras alrededor que laten con el volumen de la voz. Se calma cuando Sara
 * espera y se enciende cuando escucha o habla.
 */
@Composable
fun SaraOrb(state: AssistantState, level: Float, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val smoothLevel by animateFloatAsState(level.coerceIn(0f, 1f), spring(stiffness = 260f), label = "level")
    val energy by animateFloatAsState(
        when (state) {
            is AssistantState.Listening -> 1f
            is AssistantState.Speaking -> 0.9f
            is AssistantState.Processing, is AssistantState.Executing -> 0.75f
            is AssistantState.Error -> 0.5f
            AssistantState.WaitingForWakeWord -> 0.4f
            AssistantState.Inactive -> 0.18f
        },
        tween(700), label = "energy",
    )
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    // Mientras habla, aunque el motor de voz no informe volumen, la nebulosa «respira» como una voz.
    val speaking = state is AssistantState.Speaking
    val pressed = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(interactionSource = pressed, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Activar a Sara" },
    ) {
        val ascii = remember { AsciiSphere(rows = 38) }
        Canvas(Modifier.size(size)) {
            val voice = if (speaking) maxOf(smoothLevel, 0.35f + 0.25f * sin(time * 9f) * sin(time * 3.3f)) else smoothLevel
            val pulse = (voice * energy).coerceIn(0f, 1f)
            // Arte ASCII de un solo color que recorre el espectro (rojo → verde → azul…) y late con la voz.
            drawIntoCanvas { canvas ->
                ascii.draw(canvas.nativeCanvas, this.size.width / 2, this.size.height / 2, this.size.minDimension * 0.42f, time, pulse, energy)
            }
        }
        // El micrófono sólo cuando Sara no está escuchando ni hablando (para tocar y hablar).
        if (energy < 0.6f) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(size * 0.13f),
            )
        }
    }
}


/**
 * Bloque de ajustes: una «burbuja» con su título, una línea que explica qué hay adentro y una barra
 * de color a la izquierda, para saber siempre en qué apartado estás leyendo.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = SaraColors.Violet,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SaraColors.Surface)
            .drawBehind { drawRect(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.3f))), size = Size(5.dp.toPx(), size.height)) }
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .padding(start = 21.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = SaraColors.Text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = accent.copy(alpha = 0.9f)) }
        }
        HorizontalDivider(color = SaraColors.Outline)
        content()
    }
}

/** Subtítulo dentro de una burbuja, para separar grupos de opciones. */
@Composable
fun SubHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = SaraColors.Cyan, modifier = Modifier.padding(top = 4.dp))
}

/** Botón de verdad (con fondo y borde), no un texto suelto. */
@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, color: Color = SaraColors.Cyan, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, (if (enabled) color else SaraColors.Muted).copy(alpha = 0.55f)),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color.copy(alpha = 0.16f),
            contentColor = color,
            disabledContainerColor = SaraColors.SurfaceHigh,
            disabledContentColor = SaraColors.Muted,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = SaraColors.Violet, uncheckedTrackColor = SaraColors.SurfaceHigh),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OptionChips(title: String, options: List<Pair<T, String>>, selected: T, subtitle: String? = null, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SaraColors.Violet.copy(alpha = 0.22f),
                        selectedLabelColor = SaraColors.Text,
                        labelColor = SaraColors.Muted,
                    ),
                )
            }
        }
    }
}

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(local), style = MaterialTheme.typography.labelLarge, color = SaraColors.Cyan)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = SaraColors.Cyan, activeTrackColor = SaraColors.Violet, inactiveTrackColor = SaraColors.SurfaceHigh),
        )
    }
}

@Composable
fun StatusLine(title: String, detail: String, ok: Boolean, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) SaraColors.Green else SaraColors.Amber))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = SaraColors.Muted)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            ActionButton(action, onClick = onAction)
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = SaraColors.Text)
    }
}
