package org.olcbox.app.ui.reed2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Group
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ReedTab(val label: String, val icon: ImageVector) {
    Home("Главная", Icons.Rounded.Home),
    Family("Семья", Icons.Rounded.Group),
    Profile("Профиль", Icons.Rounded.Person),
}

/**
 * Reed 2.0 — навбар Android с «шариком» (ТЗ 7.2).
 *
 * Плашка 80dp (surface-100, сверху линия hairline). Над активным разделом — круг 56dp
 * (chrome-050, иконка on-ink) с обводкой 6dp цвета фона экрана (выглядит как вырез), круг
 * наполовину выходит за верх плашки. Движение шарика — CubicBezier(0.2,0.8,0.2,1), 380мс;
 * смена цвета — 220мс. Подписи видны всегда.
 *
 * Ширина берётся из BoxWithConstraints (известна при композиции) → шарик рисуется уже на
 * первом кадре, без мигания при первом показе.
 */
private val BubbleEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
fun ReedNavBar(
    selected: ReedTab,
    onSelect: (ReedTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = ReedTab.entries
    val selectedIndex = tabs.indexOf(selected)

    // Контейнер 104dp: 24dp над плашкой (для выступающего круга) + 80dp плашка.
    BoxWithConstraints(modifier.fillMaxWidth().height(104.dp)) {
        val itemWidth = maxWidth / tabs.size
        // Левый край круга = центр(index*itemWidth + itemWidth/2) − 28(радиус) − 6(обводка).
        val bubbleX by animateDpAsState(
            targetValue = itemWidth * selectedIndex + itemWidth / 2 - 34.dp,
            animationSpec = tween(380, easing = BubbleEasing),
            label = "bubbleX",
        )

        // Плашка внизу.
        Box(Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter).background(Reed2.surface100)) {
            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter).background(Reed2.hairline))
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    NavItem(tab, tab == selected, Modifier.weight(1f).fillMaxSize()) { onSelect(tab) }
                }
            }
        }

        // Шарик — круг 56dp с «обводкой» 6dp цвета фона. Центр по Y = 38dp от верха контейнера.
        Box(
            modifier = Modifier
                .offset(x = bubbleX, y = 38.dp - 34.dp)
                .size(68.dp)
                .clip(CircleShape)
                .background(Reed2.ground000)
                .padding(6.dp)
                .clip(CircleShape)
                .background(Reed2.chrome050),
            contentAlignment = Alignment.Center,
        ) {
            Icon(selected.icon, null, tint = Reed2.onInk, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun NavItem(tab: ReedTab, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val lift by animateDpAsState(
        targetValue = if (active) (-8).dp else 0.dp,
        animationSpec = tween(380, easing = BubbleEasing), label = "lift",
    )
    val tint by animateColorAsState(if (active) Reed2.onInk else Reed2.inkMuted, tween(220), label = "tint")
    val labelColor by animateColorAsState(if (active) Reed2.ink else Reed2.inkMuted, tween(220), label = "labelColor")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.clickable(interaction, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Активную иконку скрываем (её показывает шарик сверху), место держим — подпись не прыгает.
            if (!active) {
                Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(24.dp).offset(y = lift))
            }
        }
        Text(
            tab.label, color = labelColor, fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
    }
}
