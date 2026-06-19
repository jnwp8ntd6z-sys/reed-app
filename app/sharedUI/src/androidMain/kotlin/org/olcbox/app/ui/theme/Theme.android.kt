package org.olcbox.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Composable
actual fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    // Reed VPN всегда тёмная (фирменный дизайн) — не зависим от системной темы,
    // иначе при светлой теме телефона приложение становилось белым.
    val isDarkState = remember { mutableStateOf(true) }
    val typography = getAppTypography()

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState
    ) {
        val isDark by isDarkState
        val colorScheme = when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            isDark -> OlcboxDarkColorScheme
            else -> OlcboxLightColorScheme
        }

        // Ограничиваем системный масштаб шрифта. На Honor/Xiaomi с увеличенным размером
        // шрифта/экрана (fontScale > 1.1) текст «съезжал», наезжал и ломал вёрстку — теперь
        // он одинаковый на всех телефонах. Лёгкое увеличение (до 1.1) оставляем для
        // доступности, экстремальные значения зажимаем.
        val baseDensity = LocalDensity.current
        val clampedDensity = Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale.coerceIn(0.9f, 1.1f)
        )

        CompositionLocalProvider(LocalDensity provides clampedDensity) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = typography
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
            }
        }
    }
}
