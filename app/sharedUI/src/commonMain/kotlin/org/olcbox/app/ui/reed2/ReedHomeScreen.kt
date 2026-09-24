package org.olcbox.app.ui.reed2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reed 2.0 — Главная (ТЗ 4.4). Stateless: получает состояние подключения, подписку, список
 * серверов, выбранный сервер, выбор сети. Навбар добавляет host-скаффолд.
 */
@Composable
fun ReedHomeScreen(
    connState: ReedConnState,
    statusWord: String,          // «Подключено» / «Подключаюсь…» / «Не подключено»
    timer: String,               // «01:24:07» (пусто → не показывать)
    locationSubtitle: String,    // «Германия · Франкфурт»
    subscription: SubscriptionUi?,
    servers: List<ServerUi>,
    selectedKey: String?,
    network: String,             // wifi | cell
    hasUnread: Boolean,
    onToggleConnect: () -> Unit,
    onSelectServer: (ServerUi) -> Unit,
    onSelectNetwork: (String) -> Unit,
    onBell: () -> Unit,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(Reed2.ground000)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        // Шапка: лого + колокольчик.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HomeLogo()
            Spacer(Modifier.weight(1f))
            Box {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Reed2.surface200)
                        .clickable(onClick = onBell),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Notifications, "Уведомления", tint = Reed2.ink,
                        modifier = Modifier.size(22.dp))
                }
                if (hasUnread) {
                    Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(9.dp)
                        .clip(RoundedCornerShape(50)).background(Reed2.statusWarn))
                }
            }
        }

        // Кнопка подключения.
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedConnectButton(state = connState, onClick = onToggleConnect, size = 170)
        }

        // Статус + таймер + локация.
        Spacer(Modifier.height(14.dp))
        val dotColor = when (connState) {
            ReedConnState.On -> Reed2.lime
            ReedConnState.Connecting -> Reed2.statusWarn
            ReedConnState.Off -> Reed2.chrome600
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedStatusDot(statusWord, dotColor)
        }
        if (timer.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ReedMonoCapsule(timer)
            }
        }
        if (locationSubtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(locationSubtitle, color = Reed2.inkMuted, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        // Карточка подписки.
        if (subscription != null) {
            Spacer(Modifier.height(18.dp))
            ReedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Подписка ${subscription.untilLabel}", color = Reed2.ink,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(subscription.usageLabel, color = Reed2.inkMuted,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp,
                            maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    ReedWavyProgress(subscription.progress)
                    Spacer(Modifier.height(10.dp))
                    Text(subscription.footnote, color = Reed2.inkMuted, fontSize = 12.sp)
                }
            }
        }

        // Переключатель Wi-Fi / Мобильный (по центру, ~260dp).
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedSegmented(
                options = listOf("Wi-Fi", "Мобильный"),
                selectedIndex = if (network == "cell") 1 else 0,
                onSelect = { onSelectNetwork(if (it == 1) "cell" else "wifi") },
                modifier = Modifier.width(260.dp),
            )
        }

        // Заголовок СЕРВЕРЫ + кнопки Пинг / Обновить.
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ReedSectionHeader("СЕРВЕРЫ", Modifier.weight(1f))
            SmallPillButton("Пинг", Icons.Rounded.Speed, onPing)
            Spacer(Modifier.width(8.dp))
            SmallPillButton("Обновить", Icons.Rounded.Refresh, onRefresh)
        }

        Spacer(Modifier.height(10.dp))
        val shown = servers.filter { it.network == network || (network == "cell" && it.network == "cell") || (network == "wifi" && it.network == "wifi") }
        val list = if (shown.isEmpty()) servers else shown
        Column {
            list.forEach { s ->
                ReedServerRow(
                    country = s.country, countryName = s.countryName, city = s.city,
                    pingMs = s.pingMs, selected = s.key == selectedKey,
                    onClick = { onSelectServer(s) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeLogo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("REED", color = Reed2.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Reed2.lime))
        Spacer(Modifier.width(6.dp))
        Text("CLIENT", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
            style = chromeTextStyle(androidx.compose.material3.MaterialTheme.typography.titleMedium)
                .copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp))
    }
}

@Composable
private fun SmallPillButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface200)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Reed2.inkMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Reed2.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
