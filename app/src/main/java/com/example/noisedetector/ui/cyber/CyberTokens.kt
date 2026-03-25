package com.example.noisedetector.ui.cyber

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object CyberColors {
    val VoidBlack = Color(0xFF030508)
    val DeepNavy = Color(0xFF0A1628)
    val PanelNavy = Color(0xFF0D1F35)
    val GlassLight = Color(0x18FFFFFF)
    val GlassBorder = Color(0x35FFFFFF)
    val NeonCyan = Color(0xFF00F5FF)
    val NeonCyanDim = Color(0x6600F5FF)
    val NeonGreen = Color(0xFF39FF14)
    val NeonYellow = Color(0xFFFFEA00)
    val NeonRed = Color(0xFFFF3131)
    val NeonMagenta = Color(0xFFFF00AA)
    val TextPrimary = Color(0xFFE8F4FF)
    val TextMuted = Color(0xFF7A9BB8)
    val TextDim = Color(0xFF4A6578)
    val AlertGlow = Color(0x66FF3131)

    val ScreenGradient: Brush
        get() = Brush.verticalGradient(
            colors = listOf(VoidBlack, DeepNavy, Color(0xFF061018), DeepNavy),
        )

    val CyanGlowBrush: Brush
        get() = Brush.radialGradient(
            colors = listOf(NeonCyan.copy(alpha = 0.45f), Color.Transparent),
        )
}
