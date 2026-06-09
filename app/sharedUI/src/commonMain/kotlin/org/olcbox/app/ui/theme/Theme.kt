package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

internal val LocalThemeIsDark = compositionLocalOf { mutableStateOf(true) }

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    // Reed VPN — фирменная ТЁМНАЯ тема (лайм/оранж на тёмном). Динамические цвета
    // Android (Material You) выключены, чтобы приложение не подхватывало палитру
    // обоев и не светлело в светлой системной теме.
    AppTheme(useDynamicColor = false, content = content)
}

@Composable
expect fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
)
