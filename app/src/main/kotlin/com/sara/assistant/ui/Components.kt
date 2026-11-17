package com.sara.assistant.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PathEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
/**
 * Nebulosa al estilo «Jarvis»: nubes de color que giran y se mezclan, partículas, anillos
 * técnicos que rotan y barras alrededor que laten con el volumen de la voz. Se calma cuando Sara
 * espera y se enciende cuando escucha o habla.
 */
@Composable
fun SaraOrb(state: AssistantState, level: Float, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val color by animateColorAsState(state.color(), tween(450), label = "orbColor")
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
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r = this.size.minDimension / 2
            val voice = if (speaking) maxOf(smoothLevel, 0.35f + 0.25f * sin(time * 9f) * sin(time * 3.3f)) else smoothLevel
            val pulse = (voice * energy).coerceIn(0f, 1f)
            val palette = listOf(SaraColors.Violet, SaraColors.Cyan, SaraColors.Pink, color, SaraColors.Violet, SaraColors.Cyan, color)

            // Resplandor de fondo.
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.10f + 0.30f * energy), Color.Transparent), c, r),
                radius = r, center = c,
            )

            // Nubes de la nebulosa (se suman entre sí, como luz).
            palette.forEachIndexed { i, tint ->
                val dir = if (i % 2 == 0) 1f else -1f
                val angle = time * (0.18f + i * 0.05f) * dir * (0.5f + energy) + i * 0.9f
                val dist = r * (0.10f + 0.10f * sin(time * 0.7f + i)) * (1f + pulse * 0.9f)
                val p = Offset(c.x + cos(angle) * dist, c.y + sin(angle) * dist)
                val blob = r * (0.26f + 0.05f * sin(time * 1.3f + i * 1.7f)) * (1f + pulse * 0.55f)
                drawCircle(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.20f + 0.30f * energy), Color.Transparent), p, blob),
                    radius = blob, center = p, blendMode = BlendMode.Plus,
                )
            }

            // Núcleo brillante.
            val core = r * (0.12f + 0.10f * pulse + 0.015f * sin(time * 2f))
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.35f + 0.5f * pulse), color.copy(alpha = 0.35f), Color.Transparent), c, core * 2.2f,
                ),
                radius = core * 2.2f, center = c, blendMode = BlendMode.Plus,
            )

            // Partículas en órbita.
            for (j in 0 until PARTICLES) {
                val seedA = frac(j * 0.618034f)
                val seedB = frac(j * 0.414214f)
                val dir = if (j % 3 == 0) -1f else 1f
                val angle = j * 2.39996f + time * (0.08f + seedB * 0.35f) * dir * (0.4f + energy)
                val radius = r * (0.22f + seedA * 0.55f) * (1f + pulse * 0.30f * sin(time * 3f + j))
                val twinkle = 0.25f + 0.75f * abs(sin(time * (1.2f + seedA * 2f) + j))
                drawCircle(
                    palette[j % palette.size].copy(alpha = twinkle * (0.35f + 0.65f * energy)),
                    radius = (0.8f + seedB * 1.6f).dp.toPx() * (1f + pulse * 0.6f),
                    center = Offset(c.x + cos(angle) * radius, c.y + sin(angle) * radius),
                    blendMode = BlendMode.Plus,
                )
            }

            // Barras que laten con la voz, alrededor de la nebulosa.
            val ring = r * 0.80f
            val tick = 1.6.dp.toPx()
            for (k in 0 until BARS) {
                val a = k * (2f * PI.toFloat() / BARS) + time * 0.15f
                val wobble = 0.5f + 0.5f * sin(time * 6f + k * 0.7f) * cos(time * 2.3f + k * 0.31f)
                val length = r * (0.015f + 0.11f * pulse * wobble + 0.01f * energy)
                val dirV = Offset(cos(a), sin(a))
                drawLine(
                    color.copy(alpha = 0.25f + 0.6f * energy),
                    start = c + dirV * ring, end = c + dirV * (ring + length), strokeWidth = tick,
                )
            }

            // Anillos técnicos que giran (estilo «Jarvis»).
            val arcStroke = Stroke(width = 2.dp.toPx())
            rotate(time * 40f * (0.4f + energy), c) {
                for (q in 0 until 3) {
                    drawArc(
                        SaraColors.Cyan.copy(alpha = 0.25f + 0.45f * energy), startAngle = q * 120f, sweepAngle = 70f, useCenter = false,
                        topLeft = Offset(c.x - r * 0.70f, c.y - r * 0.70f), size = Size(r * 1.40f, r * 1.40f), style = arcStroke,
                    )
                }
            }
            rotate(-time * 22f, c) {
                drawCircle(
                    color.copy(alpha = 0.18f + 0.35f * energy), radius = r * 0.93f, center = c,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 9.dp.toPx()))),
                )
            }
            drawCircle(
                Color.White.copy(alpha = 0.10f + 0.35f * pulse), radius = r * (0.58f + 0.10f * pulse), center = c,
                style = Stroke(width = (1f + 3f * pulse).dp.toPx()),
            )
            if (state is AssistantState.Inactive) drawCircle(SaraColors.Background.copy(alpha = 0.35f), r, c)
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

private const val PARTICLES = 70
private const val BARS = 64
private fun frac(x: Float) = x - floor(x)

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
