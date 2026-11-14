package com.milolab.btctv.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF06070B)
    val BgTop = Color(0xFF0B0E15)
    val Panel = Color(0xFF10131B)
    val PanelHigh = Color(0xFF161A24)
    val Text = Color(0xFFF4F6FB)
    val Dim = Color(0xFF7C8698)
    val Dimmer = Color(0xFF4B5464)
    val Up = Color(0xFF2BD9A6)
    val Down = Color(0xFFFF5470)
    val Grid = Color(0xFF1C2230)
}

val CardShape = RoundedCornerShape(14.dp)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.Bg,
            surface = C.Panel,
            primary = Color(0xFFF7931A),
            onBackground = C.Text,
            onSurface = C.Text
        ),
        typography = AppTypography,
        content = content
    )
}

val Mono = FontFamily.Monospace
