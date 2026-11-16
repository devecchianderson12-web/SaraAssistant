package com.sara.assistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
@Composable
fun SaraOrb(state: AssistantState, level: Float, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val color by animateColorAsState(state.color(), tween(450), label = "orbColor")
    val smoothLevel by animateFloatAsState(level, spring(stiffness = 300f), label = "level")
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    val breath by transition.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "rotation")
    val active = state !is AssistantState.Inactive
    val pressed = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(interactionSource = pressed, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Activar a Sara" },
    ) {
        Canvas(Modifier.size(size)) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val base = this.size.minDimension * 0.28f
            val energetic = state is AssistantState.Listening || state is AssistantState.Speaking

            // Halo
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = if (active) 0.35f else 0.12f), Color.Transparent), center, base * (2.0f + smoothLevel * 0.6f)),
                radius = base * (2.0f + smoothLevel * 0.6f), center = center,
            )
            // Anillos que se expanden
            if (active) {
                val rings = if (energetic) 3 else 2
                for (i in 0 until rings) {
                    val p = (phase + i.toFloat() / rings) % 1f
                    val radius = base * (1.05f + p * (0.75f + smoothLevel * 0.5f))
                    drawCircle(color.copy(alpha = (1f - p) * if (energetic) 0.55f else 0.3f), radius, center, style = Stroke(width = 2.dp.toPx()))
                }
            }
            // Núcleo con gradiente giratorio
            val coreRadius = base * (if (active) breath else 1f) * (1f + smoothLevel * 0.18f)
            rotate(rotation, center) {
                drawCircle(
                    brush = Brush.sweepGradient(listOf(SaraColors.Violet, SaraColors.Cyan, SaraColors.Pink, SaraColors.Violet), center),
                    radius = coreRadius, center = center,
                )
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f), color.copy(alpha = 0.55f)), center, coreRadius),
                radius = coreRadius * 0.97f, center = center,
            )
            if (!active) drawCircle(SaraColors.Background.copy(alpha = 0.45f), coreRadius, center)
        }
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.16f).scale(1f + smoothLevel * 0.1f),
        )
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SaraColors.Surface)
            .border(1.dp, SaraColors.Outline.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = SaraColors.Violet)
        content()
    }
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
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = SaraColors.Cyan) }
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
