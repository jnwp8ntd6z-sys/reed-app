package org.olcbox.app.ui.reed2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reed 2.0 — каркас приложения с тремя разделами (Главная / Семья / Профиль).
 * Контент — между статус-баром и плашкой навбара; плашка навбара доходит до самого низа
 * (под системной навигацией та же подложка surface-100 — без полос при edge-to-edge).
 */
@Composable
fun ReedAppShell(
    selectedTab: ReedTab,
    onSelectTab: (ReedTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Reed2.ground000)) {
        Box(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
        ) { content() }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            ReedNavBar(selectedTab, onSelectTab)
            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(Reed2.surface100))
        }
    }
}
