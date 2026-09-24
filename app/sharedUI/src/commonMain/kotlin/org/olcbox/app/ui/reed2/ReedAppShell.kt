package org.olcbox.app.ui.reed2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reed 2.0 — каркас приложения с тремя разделами (Главная / Семья / Профиль).
 * Телефон: контент между статус-баром и плашкой навбара; плашка доходит до самого низа
 * (под системной навигацией та же подложка surface-100 — без полос при edge-to-edge).
 * Ширина ≥ 600 dp (ТЗ 7.6.5): навигация слева (рейл), контент по центру не шире 560 dp.
 */
@Composable
fun ReedAppShell(
    selectedTab: ReedTab,
    onSelectTab: (ReedTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Reed2.ground000)) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                ReedNavRail(selectedTab, onSelectTab)
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) { content() }
                }
            }
        } else {
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
}

/** Боковая навигация для планшетов: тот же «шарик» над активным разделом, едет по вертикали. */
@Composable
private fun ReedNavRail(selected: ReedTab, onSelect: (ReedTab) -> Unit) {
    val easing = remember { CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f) }
    val tabs = ReedTab.entries
    val itemHeight = 88.dp
    val index = tabs.indexOf(selected)
    val bubbleY by animateDpAsState(itemHeight * index + 8.dp, tween(380, easing = easing), label = "railBubble")
    Box(
        Modifier.width(96.dp).fillMaxHeight().background(Reed2.surface100)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Box(Modifier.padding(top = 24.dp)) {
            Box(
                Modifier.offset(x = 20.dp, y = bubbleY).size(56.dp).clip(CircleShape).background(Reed2.chrome050)
            )
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                tabs.forEach { tab ->
                    val active = tab == selected
                    val tint by animateColorAsState(if (active) Reed2.onInk else Reed2.inkMuted, tween(220), label = "railTint")
                    val label by animateColorAsState(if (active) Reed2.ink else Reed2.inkMuted, tween(220), label = "railLabel")
                    Column(
                        Modifier.width(96.dp).height(itemHeight)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.padding(top = 8.dp).size(56.dp), contentAlignment = Alignment.Center) {
                            Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(24.dp))
                        }
                        Text(tab.label, color = label, fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
