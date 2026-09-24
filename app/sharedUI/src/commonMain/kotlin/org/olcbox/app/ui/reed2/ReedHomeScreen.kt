package org.olcbox.app.ui.reed2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reed 2.0 — Главная (ТЗ 4.4). Stateless: всё состояние приходит параметрами, навбар
 * добавляет каркас. Подписка: [subscription] (данные) ЛИБО [subscriptionMessage]
 * (загрузка / закончилась) с необязательной кнопкой действия.
 */
@Composable
fun ReedHomeScreen(
    connState: ReedConnState,
    statusWord: String,
    timer: String,
    locationSubtitle: String,
    subscription: SubscriptionUi?,
    servers: List<ServerUi>,
    selectedKey: String?,
    network: String,
    hasUnread: Boolean,
    onToggleConnect: () -> Unit,
    onSelectServer: (ServerUi) -> Unit,
    onSelectNetwork: (String) -> Unit,
    onBell: () -> Unit,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
    errorText: String? = null,
    hintText: String? = null,
    subscriptionMessage: String? = null,
    subscriptionActionLabel: String? = null,
    onSubscriptionAction: () -> Unit = {},
    onSubscriptionClick: (() -> Unit)? = null,   // несколько подписок → выбор
    refreshing: Boolean = false,
    pinging: Boolean = false,
    emptyServersText: String = "Серверы загружаются…",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(Reed2.ground000)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
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
                val dotAlpha by androidx.compose.animation.core.animateFloatAsState(
                    if (hasUnread) 1f else 0f, tween(220), label = "unreadDot")
                Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(9.dp)
                    .alpha(dotAlpha).clip(RoundedCornerShape(50)).background(Reed2.statusWarn))
            }
        }

        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedConnectButton(state = connState, onClick = onToggleConnect, size = 170)
        }

        Spacer(Modifier.height(14.dp))
        val dotColor = when (connState) {
            ReedConnState.On -> Reed2.lime
            ReedConnState.Connecting -> Reed2.statusWarn
            ReedConnState.Off -> Reed2.chrome600
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedStatusDot(statusWord, dotColor)
        }
        AnimatedVisibility(timer.isNotBlank(), enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            Column {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ReedMonoCapsule(timer) }
            }
        }
        if (locationSubtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(locationSubtitle, color = Reed2.inkMuted, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        if (errorText != null) {
            Spacer(Modifier.height(6.dp))
            Text(errorText, color = Reed2.statusDanger, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = TextAlign.Center)
        }
        if (hintText != null) {
            Spacer(Modifier.height(6.dp))
            Text(hintText, color = Reed2.inkMuted, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = TextAlign.Center)
        }

        // Карточка подписки / сообщение о подписке.
        if (subscription != null) {
            Spacer(Modifier.height(18.dp))
            ReedCard(Modifier.fillMaxWidth()
                .then(if (onSubscriptionClick != null) Modifier.clickable(onClick = onSubscriptionClick) else Modifier)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Подписка ${subscription.untilLabel}", color = Reed2.ink,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (subscription.totalGb > 0) {
                            Text(subscription.usageLabel, color = Reed2.inkMuted,
                                fontFamily = LocalReedFonts.current.mono, fontSize = 13.sp,
                                maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (onSubscriptionClick != null) {
                            Icon(Icons.Rounded.UnfoldMore, "Сменить подписку", tint = Reed2.inkMuted,
                                modifier = Modifier.padding(start = 6.dp).size(18.dp))
                        }
                    }
                    if (subscription.totalGb > 0) {
                        Spacer(Modifier.height(12.dp))
                        ReedWavyProgress(subscription.progress)
                    }
                    if (subscription.footnote.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(subscription.footnote, color = Reed2.inkMuted, fontSize = 12.sp)
                    }
                }
            }
        } else if (subscriptionMessage != null) {
            Spacer(Modifier.height(18.dp))
            ReedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(subscriptionMessage, color = Reed2.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (subscriptionActionLabel != null) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                                .background(Reed2.surface300).clickable(onClick = onSubscriptionAction),
                            contentAlignment = Alignment.Center,
                        ) { Text(subscriptionActionLabel, color = Reed2.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedSegmented(
                options = listOf("Wi-Fi", "Мобильный"),
                selectedIndex = if (network == "cell") 1 else 0,
                onSelect = { onSelectNetwork(if (it == 1) "cell" else "wifi") },
                modifier = Modifier.width(260.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ReedSectionHeader("СЕРВЕРЫ", Modifier.weight(1f))
            SmallPillButton("Пинг", Icons.Rounded.Speed, spinning = false, pulsing = pinging, onClick = onPing)
            Spacer(Modifier.width(8.dp))
            SmallPillButton("Обновить", Icons.Rounded.Refresh, spinning = refreshing, pulsing = false, onClick = onRefresh)
        }

        Spacer(Modifier.height(10.dp))
        val shown = servers.filter { it.network == network }
        Column(Modifier.animateContentSize(tween(260))) {
            if (shown.isEmpty()) {
                Text(
                    if (servers.isEmpty()) emptyServersText
                    else if (network == "cell") "Мобильных серверов в подписке нет" else "Серверов Wi-Fi в подписке нет",
                    color = Reed2.inkMuted, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center,
                )
            } else {
                shown.forEach { s ->
                    ReedServerRow(
                        country = s.country, countryName = s.countryName, city = s.city,
                        pingMs = s.pingMs, selected = s.key == selectedKey,
                        onClick = { onSelectServer(s) },
                        connState = if (s.key == selectedKey) connState else ReedConnState.Off,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeLogo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("REED", color = Reed2.ink, fontSize = 18.sp, fontFamily = LocalReedFonts.current.headline, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Reed2.lime))
        Spacer(Modifier.width(6.dp))
        Text("CLIENT", fontSize = 18.sp, fontFamily = LocalReedFonts.current.headline, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            style = chromeTextStyle(androidx.compose.material3.MaterialTheme.typography.titleMedium)
                .copy(fontSize = 18.sp, fontFamily = LocalReedFonts.current.headline, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
    }
}

/** Маленькая кнопка с подложкой. spinning — иконка вращается (загрузка), pulsing — мягко мигает (замер). */
@Composable
private fun SmallPillButton(
    text: String,
    icon: ImageVector,
    spinning: Boolean,
    pulsing: Boolean,
    onClick: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "pill")
    val angle by infinite.animateFloat(0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val pulse by infinite.animateFloat(1f, 0.35f,
        infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    Row(
        Modifier.clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface200)
            .clickable(enabled = !spinning, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Reed2.inkMuted.copy(alpha = if (pulsing) pulse else 1f),
            modifier = Modifier.size(16.dp).rotate(if (spinning) angle else 0f))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Reed2.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
