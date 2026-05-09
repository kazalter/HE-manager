package com.hemanager.mobile.player.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Color palette for the player. Mirrors the values declared in [com.hemanager.mobile.MainActivity.scheme]
 * so the player feels visually continuous with the rest of the app.
 *
 * Why duplicated: MainActivity's scheme is private to the activity. Re-declaring here as
 * top-level constants keeps the player module self-contained without making MainActivity's
 * theme public surface. If these ever drift, both should be moved to a shared theme module.
 */
object PlayerColors {
    val Primary = Color(0xFF8EA2FF)
    val Secondary = Color(0xFF58E6C2)
    val Tertiary = Color(0xFFF6C46B)
    val Background = Color(0xFF070A12)
    val Surface = Color(0xFF111620)
    val SurfaceVariant = Color(0xFF202838)
    val PrimaryContainer = Color(0xFF28336D)
    val OnSurface = Color(0xFFF4F7FF)
    val OnSurfaceVariant = Color(0xFFC6CFDD)
    val OnSurfaceMuted = Color(0xB3F4F7FF)
    val Danger = Color(0xFFFF8FA3)

    // Translucent overlays for control surfaces atop the video.
    val ScrimTop = Brush.verticalGradient(
        listOf(Color(0xCC000000), Color(0x00000000)),
    )
    val ScrimBottom = Brush.verticalGradient(
        listOf(Color(0x00000000), Color(0xDD000000)),
    )

    val ButtonGlass = Color(0x66000000)
    val ButtonGlassPressed = Color(0xAA000000)
}

val PlayerColorScheme = darkColorScheme(
    primary = PlayerColors.Primary,
    secondary = PlayerColors.Secondary,
    tertiary = PlayerColors.Tertiary,
    background = PlayerColors.Background,
    surface = PlayerColors.Surface,
    surfaceVariant = PlayerColors.SurfaceVariant,
    primaryContainer = PlayerColors.PrimaryContainer,
    onPrimary = Color(0xFF080B12),
    onSecondary = Color(0xFF0E1715),
    onTertiary = Color(0xFF15100A),
    onBackground = PlayerColors.OnSurface,
    onSurface = PlayerColors.OnSurface,
    onSurfaceVariant = PlayerColors.OnSurfaceVariant,
)
