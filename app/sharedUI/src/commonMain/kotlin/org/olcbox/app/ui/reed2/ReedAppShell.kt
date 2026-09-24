package org.olcbox.app.ui.reed2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reed 2.0 — каркас приложения с тремя разделами (ТЗ: Главная / Семья / Профиль).
 * Плашка навбара внизу; контент рисуется над плашкой. Шарик навбара выступает в нижние
 * ~24dp контента — поэтому контент экранов уже имеет нижний отступ в скролле.
 *
 * Stateless: раздел и обработчик выбора приходят снаружи; [content] рисует нужный экран.
 */
@Composable
fun ReedAppShell(
    selectedTab: ReedTab,
    onSelectTab: (ReedTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Reed2.ground000)) {
        // Контент над плашкой (80dp). Верхние 24dp навбар-контейнера прозрачны (там шарик).
        Box(Modifier.fillMaxSize().padding(bottom = 80.dp)) {
            content()
        }
        ReedNavBar(selectedTab, onSelectTab, Modifier.align(Alignment.BottomCenter))
    }
}
