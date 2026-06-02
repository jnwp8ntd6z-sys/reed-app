package org.olcbox.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Фирменная палитра Reed VPN: лайм (155,210,0) + оранж (255,140,80) на near-black ──
internal val OlcboxDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9BD200),            // Reed lime
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF3F5500),
    onPrimaryContainer = Color(0xFFD7F26B),
    inversePrimary = Color(0xFF5E7F00),
    secondary = Color(0xFFFF8C50),          // Reed orange
    onSecondary = Color(0xFF2A1100),
    secondaryContainer = Color(0xFF5A2E10),
    onSecondaryContainer = Color(0xFFFFD8C2),
    tertiary = Color(0xFFFFB088),
    onTertiary = Color(0xFF3A1A00),
    tertiaryContainer = Color(0xFF5A2E10),
    onTertiaryContainer = Color(0xFFFFDDC9),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFC8C8C8),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2C2C2C),
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF2A2A2A),
    outline = Color(0xFF555555),
    outlineVariant = Color(0xFF333333),
    error = Color(0xFFFF5A5A),
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF7A1010),
    onErrorContainer = Color(0xFFFFD6D6)
)

internal val OlcboxLightColorScheme = lightColorScheme(
    primary = Color(0xFF4C6A00),            // Reed lime (darkened for light bg contrast)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDF26B),
    onPrimaryContainer = Color(0xFF161F00),
    inversePrimary = Color(0xFF9BD200),
    secondary = Color(0xFFA8430F),          // Reed orange (darkened)
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC8),
    onSecondaryContainer = Color(0xFF351000),
    tertiary = Color(0xFF8A4B25),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBC8),
    onTertiaryContainer = Color(0xFF311300),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFFE9E7),
    surfaceContainerHigh = Color(0xFFF9DEDB),
    surfaceContainerHighest = Color(0xFFF1D6D3),
    inverseSurface = Color(0xFF392E2D),
    inverseOnSurface = Color(0xFFFFEDEA),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
