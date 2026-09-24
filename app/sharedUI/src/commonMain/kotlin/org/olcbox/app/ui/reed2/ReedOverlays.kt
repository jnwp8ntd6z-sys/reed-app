package org.olcbox.app.ui.reed2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Действие в нижнем листе. danger — красное (удаление). */
data class ReedSheetAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** Содержимое нижнего листа. null — лист закрыт. */
data class ReedSheetSpec(
    val title: String,
    val subtitle: String? = null,
    val actions: List<ReedSheetAction>,
    val cancelLabel: String = "Отмена",
)

/**
 * Нижний лист действий Reed 2.0: затемнение + карточка снизу. Появление мягкое
 * (затемнение fade, карточка выезжает снизу с FastOutSlowIn), без рывков.
 * Рисовать ПОСЛЕДНИМ в корневом Box, чтобы лист был поверх всего.
 */
@Composable
fun ReedSheetHost(spec: ReedSheetSpec?, onDismiss: () -> Unit) {
    // Держим последний непустой spec, чтобы при закрытии контент не пропадал до конца анимации.
    val last = remember { arrayOfNulls<ReedSheetSpec>(1) }
    if (spec != null) last[0] = spec
    val shown = spec ?: last[0]
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = spec != null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(200)),
        ) {
            val i = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(i, null, onClick = onDismiss))
        }
        AnimatedVisibility(
            visible = spec != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
        ) {
            if (shown != null) {
                val i = remember { MutableInteractionSource() }
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(Reed2.surface200)
                        .clickable(i, null) {}   // клики по карточке не закрывают лист
                        .navigationBarsPadding()
                        .padding(20.dp),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50)).background(Reed2.chrome800))
                    Spacer(Modifier.height(14.dp))
                    Text(shown.title, color = Reed2.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (shown.subtitle != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(shown.subtitle, color = Reed2.inkMuted, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    shown.actions.forEach { a ->
                        Box(
                            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                                .background(Reed2.surface300)
                                .clickable { a.onClick() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(a.label, color = if (a.danger) Reed2.statusDanger else Reed2.ink,
                                fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Text(shown.cancelLabel, color = Reed2.inkMuted, fontSize = 15.sp) }
                }
            }
        }
    }
}

/** Уведомление для списка. isNewDevice — ведёт в «Семью». */
data class NotificationUi(
    val id: Int,
    val title: String,
    val body: String,
    val dateLabel: String,
    val isNewDevice: Boolean,
    val unread: Boolean,
)

/** Reed 2.0 — список уведомлений (открывается с колокольчика на Главной). */
@Composable
fun ReedNotificationsScreen(
    items: List<NotificationUi>,
    onBack: () -> Unit,
    onOpen: (NotificationUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Reed2.ground000)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Reed2.surface200)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ChevronLeft, "Назад", tint = Reed2.ink, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Text("Уведомления", color = Reed2.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(18.dp))
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.NotificationsNone, null, tint = Reed2.chrome600, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("Уведомлений пока нет", color = Reed2.inkMuted, fontSize = 15.sp)
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Reed2.tileRadius))
                            .background(Reed2.surface200)
                            .clickable(enabled = n.isNewDevice) { onOpen(n) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (n.unread) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50))
                                .background(if (n.isNewDevice) Reed2.statusWarn else Reed2.chrome200))
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(n.title, color = Reed2.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (n.body.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(n.body, color = Reed2.inkMuted, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            if (n.dateLabel.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(n.dateLabel, color = Reed2.chrome600, fontSize = 12.sp)
                            }
                        }
                        if (n.isNewDevice) {
                            Icon(Icons.Rounded.ChevronRight, null, tint = Reed2.chrome600, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Reed 2.0 — доступ участника приостановлен владельцем. */
@Composable
fun ReedBlockedScreen(onLogout: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Reed2.ground000)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Reed2.surface300),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, tint = Reed2.statusWarn, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Доступ приостановлен", color = Reed2.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Владелец подписки приостановил твой доступ. Когда он его вернёт, всё заработает само.",
            color = Reed2.inkMuted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                .background(Reed2.surface300).clickable(onClick = onLogout),
            contentAlignment = Alignment.Center,
        ) { Text("Выйти", color = Reed2.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
    }
}

/** Reed 2.0 — вкладка «Семья»/«Профиль» в режиме без кода: заголовок + карточка подключения. */
@Composable
fun ReedNoCodeTabScreen(
    title: String,
    cardText: String,
    onEnterCode: () -> Unit,
    onScanQr: () -> Unit,
    footer: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Reed2.ground000).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(title, color = Reed2.ink, fontSize = 30.sp, fontFamily = LocalReedFonts.current.headline, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(18.dp))
        ReedConnectSubscriptionCard(cardText = cardText, onEnterCode = onEnterCode, onScanQr = onScanQr)
        if (footer != null) {
            Spacer(Modifier.weight(1f))
            Text(footer, color = Reed2.chrome600, fontSize = 12.sp,
                fontFamily = LocalReedFonts.current.mono,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), textAlign = TextAlign.Center)
        }
    }
}
