package com.sara.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sara.core.model.AssistantState

object SaraColors {
    val Background = Color(0xFF0A0B10)
    val Surface = Color(0xFF14161F)
    val SurfaceHigh = Color(0xFF1C1F2B)
    val Outline = Color(0xFF2A2E3D)
    val Violet = Color(0xFF8B7BFF)
    val Cyan = Color(0xFF3DD9EB)
    val Pink = Color(0xFFFF6FB5)
    val Amber = Color(0xFFFFC56B)
    val Green = Color(0xFF4ADE9B)
    val Red = Color(0xFFFF5C7A)
    val Text = Color(0xFFEDEEF5)
    val Muted = Color(0xFF9097AE)
    val Idle = Color(0xFF4A5068)
}

fun AssistantState.color(): Color = when (this) {
    AssistantState.Inactive -> SaraColors.Idle
    AssistantState.WaitingForWakeWord -> SaraColors.Violet
    is AssistantState.Listening -> SaraColors.Cyan
    is AssistantState.Processing -> SaraColors.Violet
    is AssistantState.Executing -> SaraColors.Amber
    is AssistantState.Speaking -> SaraColors.Pink
    is AssistantState.Error -> SaraColors.Red
}

fun AssistantState.title(): String = when (this) {
    AssistantState.Inactive -> "Inactiva"
    AssistantState.WaitingForWakeWord -> "Esperando «Sara»"
    is AssistantState.Listening -> "Escuchando"
    is AssistantState.Processing -> "Procesando"
    is AssistantState.Executing -> "Ejecutando"
    is AssistantState.Speaking -> "Hablando"
    is AssistantState.Error -> "Error"
}

private val colors = darkColorScheme(
    primary = SaraColors.Violet,
    onPrimary = Color.White,
    secondary = SaraColors.Cyan,
    onSecondary = Color(0xFF00242A),
    tertiary = SaraColors.Pink,
    background = SaraColors.Background,
    onBackground = SaraColors.Text,
    surface = SaraColors.Surface,
    onSurface = SaraColors.Text,
    surfaceVariant = SaraColors.SurfaceHigh,
    onSurfaceVariant = SaraColors.Muted,
    surfaceContainer = SaraColors.Surface,
    surfaceContainerHigh = SaraColors.SurfaceHigh,
    surfaceContainerHighest = SaraColors.SurfaceHigh,
    outline = SaraColors.Outline,
    outlineVariant = SaraColors.Outline,
    error = SaraColors.Red,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
