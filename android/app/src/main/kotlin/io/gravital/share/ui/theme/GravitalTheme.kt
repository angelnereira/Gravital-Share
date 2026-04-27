package io.gravital.share.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Gravital Design System palette ───────────────────────────────────────────
// Inspired by Cloudflare/Tailscale: serious, technical, trustworthy

object GravitalColors {
    val Orange       = Color(0xFFFF6600)
    val OrangeDim    = Color(0xFFCC5200)
    val Surface      = Color(0xFF0D0D0D)
    val SurfaceLight = Color(0xFFF5F5F5)
    val OnSurface    = Color(0xFFE8E8E8)
    val OnSurfaceLight = Color(0xFF1A1A1A)
    val StatusGreen  = Color(0xFF22C55E)
    val StatusAmber  = Color(0xFFF59E0B)
    val StatusOrange = Color(0xFFEA580C)
    val StatusRed    = Color(0xFFEF4444)
    val StatusGray   = Color(0xFF6B7280)
}

private val DarkColorScheme = darkColorScheme(
    primary          = GravitalColors.Orange,
    onPrimary        = Color.White,
    primaryContainer = GravitalColors.OrangeDim,
    secondary        = GravitalColors.StatusGreen,
    background       = GravitalColors.Surface,
    surface          = Color(0xFF1A1A1A),
    onBackground     = GravitalColors.OnSurface,
    onSurface        = GravitalColors.OnSurface,
    error            = GravitalColors.StatusRed,
)

private val LightColorScheme = lightColorScheme(
    primary          = GravitalColors.Orange,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFFFE4CC),
    secondary        = Color(0xFF16A34A),
    background       = GravitalColors.SurfaceLight,
    surface          = Color.White,
    onBackground     = GravitalColors.OnSurfaceLight,
    onSurface        = GravitalColors.OnSurfaceLight,
    error            = GravitalColors.StatusRed,
)

@Composable
fun GravitalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography  = GravitalTypography,
        content     = content
    )
}
