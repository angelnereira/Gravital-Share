package io.gravital.share.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GravitalColors {
    val Orange           = Color(0xFFFF6600)
    val OrangeLight      = Color(0xFFFF8533)

    val StatusGreen      = Color(0xFF22C55E)
    val StatusAmber      = Color(0xFFF59E0B)
    val StatusOrange     = Color(0xFFEA580C)
    val StatusRed        = Color(0xFFEF4444)
    val StatusGray       = Color(0xFF6B7280)
}

private val Dark = darkColorScheme(
    primary              = Color(0xFFFF8533),
    onPrimary            = Color(0xFF1A0000),
    primaryContainer     = Color(0xFF4A1A00),
    onPrimaryContainer   = Color(0xFFFFD5B0),
    secondary            = Color(0xFF22C55E),
    onSecondary          = Color(0xFF003311),
    secondaryContainer   = Color(0xFF003311),
    onSecondaryContainer = Color(0xFF88E5A0),
    tertiary             = Color(0xFF60A5FA),
    onTertiary           = Color(0xFF00214D),
    background           = Color(0xFF0D0D0D),
    onBackground         = Color(0xFFE8E8E8),
    surface              = Color(0xFF141414),
    surfaceVariant       = Color(0xFF1F1F1F),
    onSurface            = Color(0xFFE8E8E8),
    onSurfaceVariant     = Color(0xFFAAAAAA),
    outline              = Color(0xFF444444),
    error                = Color(0xFFEF4444),
    onError              = Color.White,
)

private val Light = lightColorScheme(
    primary              = Color(0xFFDD4F00),
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFFFDDCC),
    onPrimaryContainer   = Color(0xFF2A1000),
    secondary            = Color(0xFF16A34A),
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF003311),
    tertiary             = Color(0xFF2563EB),
    onTertiary           = Color.White,
    background           = Color(0xFFF8F8F8),
    onBackground         = Color(0xFF1A1A1A),
    surface              = Color(0xFFFFFFFF),
    surfaceVariant       = Color(0xFFF2F2F2),
    onSurface            = Color(0xFF1A1A1A),
    onSurfaceVariant     = Color(0xFF555555),
    outline              = Color(0xFFCCCCCC),
    error                = Color(0xFFDC2626),
    onError              = Color.White,
)

@Composable
fun GravitalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography  = GravitalTypography,
        content     = content,
    )
}
