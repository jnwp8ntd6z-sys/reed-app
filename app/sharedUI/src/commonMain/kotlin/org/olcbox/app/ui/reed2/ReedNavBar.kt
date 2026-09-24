package org.olcbox.app.ui.reed2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Group
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
 * (chrome-050, иконка on-ink) с обводкой 6dp цвета фона экрана (выглядит как вырез).
 * Круг наполовину выходит за верх плашки; активная иконка приподнята.
 *
 * Движение шарика — CubicBezier(0.2,0.8,0.2,1), 380мс (мягкий разгон/торможение, без рывка).
 * Смена цвета иконки — tween 220мс. Подписи видны всегда.
 */
private val BubbleEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
fun ReedNavBar(
    selected: ReedTab,
    onSelect: (ReedTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = ReedTab.entries
    val density = LocalDensity.current
    var widthPx by remember { androidx.compose.runtime.mutableStateOf(0) }
    val itemWidthDp = if (widthPx > 0) with(density) { (widthPx / tabs.size).toDp() } else 0.dp

    val selectedIndex = tabs.indexOf(selected)
    // Центр круга по X = index*itemWidth + itemWidth/2; левый край круга = центр − 28 − 6(border).
    val bubbleX by animateDpAsState(
        targetValue = itemWidthDp * selectedIndex + itemWidthDp / 2 - 34.dp,
        animationSpec = tween(380, easing = BubbleEasing),
        label = "bubbleX",
    )

    // Контейнер 104dp: 24dp над плашкой (для выступающего круга) + 80dp плашка.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp)
            .onSizeChanged { widthPx = it.width },
    ) {
        // Плашка внизу.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(Reed2.surface100),
        ) {
            // Тонкая линия сверху плашки.
            Box(
                Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter)
                    .background(Reed2.hairline)
            )
            // Строка разделов.
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    NavItem(
                        tab = tab,
                        active = tab == selected,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }

        // Шарик (круг 56dp) с обводкой цвета фона — «вырез». Центр по Y = 38dp от верха.
        if (widthPx > 0) {
            Box(
                modifier = Modifier
                    .offset(x = bubbleX, y = 38.dp - 34.dp) // центр 38 − радиус-с-обводкой 34
                    .size(68.dp)                              // 56 + 2*6 обводка
                    .clip(CircleShape)
                    .background(Reed2.ground000)              // «обводка» = фон экрана
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Reed2.chrome050),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = selected.icon,
                    contentDescription = null,
                    tint = Reed2.onInk,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: ReedTab,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // Активная иконка приподнята на 8dp (в покое центр 46dp, центр круга 38dp → −8dp).
    val lift by animateDpAsState(
        targetValue = if (active) (-8).dp else 0.dp,
        animationSpec = tween(380, easing = BubbleEasing),
        label = "lift",
    )
    val tint by animateColorAsState(
        targetValue = if (active) Reed2.onInk else Reed2.inkMuted,
        animationSpec = tween(220),
        label = "tint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (active) Reed2.ink else Reed2.inkMuted,
        animationSpec = tween(220),
        label = "labelColor",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.clickable(
            interactionSource = interaction, indication = null, onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Иконка в покое: центр на 46dp от верха контейнера. Внутри плашки (80dp) — вверху.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Когда активна — саму иконку скрываем (её заменяет шарик сверху), но держим место,
            // чтобы подпись не прыгала. Показываем «призрак» только неактивной.
            if (!active) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier.size(24.dp).offset(y = lift),
                )
            }
        }
        Text(
            text = tab.label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
    }
}
