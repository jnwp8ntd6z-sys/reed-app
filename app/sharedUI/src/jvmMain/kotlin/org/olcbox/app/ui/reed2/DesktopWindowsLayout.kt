package org.olcbox.app.ui.reed2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Reed Client для Windows 3.0 — раскладка под большой экран (макет согласован 16.10.2026):
 *   [боковая панель 220] [центр: кнопка, состояние, подписка, тумблеры] [серверы 340, во всю высоту]
 * Цвета/шрифты — те же токены Reed2, что на телефоне; меняется только расположение.
 */

internal val DesktopSidebarWidth = 220.dp
internal val DesktopServersWidth = 340.dp

/** Пункт боковой панели: раздел (вкладка) или действие (поддержка, уведомления). */
internal data class SidebarItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val badge: Boolean = false,
    val onClick: () -> Unit,
)

/** Каркас окна: боковая панель слева, центр, справа панель серверов (если есть что показать). */
@Composable
internal fun DesktopShell(
    items: List<SidebarItem>,
    footerLabel: String,       // «Подписка» (пусто, если заголовка нет)
    footerTitle: String,       // «до 23 июля 2029» / «Без аккаунта»
    footerVersion: String,     // «Reed Client 3.0.0»
    servers: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Reed2.ground000)) {
      val compact = maxWidth < 1000.dp // окно у минимума (900×600): боковые панели уже, центру остаётся ≥ 420 dp
      val sidebarW = if (compact) 190.dp else DesktopSidebarWidth
      val serversW = if (compact) 290.dp else DesktopServersWidth
      Row(Modifier.fillMaxSize()) {
        DesktopSidebar(items, footerLabel, footerTitle, footerVersion, sidebarW)
        Box(
            Modifier.weight(1f).fillMaxHeight().background(
                Brush.verticalGradient(0f to Reed2.ground100, 0.7f to Reed2.ground000)
            ),
        ) { content() }
        if (servers != null) {
            Box(Modifier.width(serversW).fillMaxHeight().background(Reed2.surface100)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Reed2.hairline))
                servers()
            }
        }
      }
    }
}

@Composable
private fun DesktopSidebar(items: List<SidebarItem>, footerLabel: String, footerTitle: String, footerVersion: String, width: androidx.compose.ui.unit.Dp) {
    Column(
        Modifier.width(width).fillMaxHeight().background(Reed2.ground000)
            .padding(horizontal = 14.dp, vertical = 22.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("REED", color = Reed2.ink, fontSize = 15.sp, fontFamily = LocalReedFonts.current.headline,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Reed2.lime))
            Spacer(Modifier.width(6.dp))
            Text("CLIENT", fontSize = 15.sp, fontFamily = LocalReedFonts.current.headline, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, color = Reed2.chrome400)
        }
        items.forEach { item -> SidebarRow(item) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Reed2.hairline))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (footerLabel.isNotBlank()) Text(footerLabel, color = Reed2.inkMuted, fontSize = 12.sp)
            Text(footerTitle, color = Reed2.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(footerVersion, color = Reed2.chrome600, fontSize = 11.sp, fontFamily = LocalReedFonts.current.mono)
        }
    }
}

@Composable
private fun SidebarRow(item: SidebarItem) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when { item.selected -> Reed2.surface200; hovered -> Reed2.surface100; else -> Color.Transparent },
        tween(160), label = "sideBg")
    val tint by animateColorAsState(if (item.selected || hovered) Reed2.ink else Reed2.inkMuted, tween(160), label = "sideTint")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .hoverable(hover).clickable(onClick = item.onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(18.dp))
            if (item.badge) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(RoundedCornerShape(50)).background(Reed2.statusWarn))
        }
        Spacer(Modifier.width(12.dp))
        Text(item.label, color = tint, fontSize = 14.sp,
            fontWeight = if (item.selected) FontWeight.Medium else FontWeight.Normal)
    }
}

/** Центр главного экрана: кнопка, состояние, таймер, подписка, два тумблера. */
@Composable
internal fun DesktopHomeCenter(
    connState: ReedConnState,
    statusWord: String,
    timer: String,
    locationSubtitle: String,
    topLine: String,
    hasUnread: Boolean,
    onBell: () -> Unit,
    onToggleConnect: () -> Unit,
    subscription: SubscriptionUi?,
    subscriptionMessage: String?,
    subscriptionActionLabel: String?,
    onSubscriptionAction: () -> Unit,
    onSubscriptionClick: (() -> Unit)?,
    errorText: String?,
    ruDirect: Boolean,
    onRuDirect: (Boolean) -> Unit,
    autoConnect: Boolean,
    onAutoConnect: (Boolean) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val narrow = maxWidth < 560.dp
    val sidePad = if (narrow) 20.dp else 36.dp
    Column(Modifier.fillMaxSize().padding(horizontal = sidePad, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(topLine, color = Reed2.inkMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(Reed2.surface200).clickable(onClick = onBell),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Notifications, "Уведомления", tint = Reed2.ink, modifier = Modifier.size(18.dp)) }
                if (hasUnread) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(8.dp)
                    .clip(RoundedCornerShape(50)).background(Reed2.statusWarn))
            }
        }

      // Большое окно: блок по центру оставшейся высоты; маленькое — прокрутка.
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
       Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        ReedConnectButton(state = connState, onClick = onToggleConnect, size = 210)
        Spacer(Modifier.height(18.dp))
        ReedStatusDot(statusWord, when (connState) {
            ReedConnState.On -> Reed2.lime
            ReedConnState.Connecting -> Reed2.statusWarn
            ReedConnState.Off -> Reed2.chrome600
        })
        AnimatedVisibility(
            timer.isNotBlank(),
            enter = expandVertically(tween(420, easing = FastOutSlowInEasing)) + fadeIn(tween(300, delayMillis = 120)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(380, easing = FastOutSlowInEasing)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                ReedMonoCapsule(timer)
            }
        }
        if (locationSubtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(locationSubtitle, color = Reed2.inkMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        if (errorText != null) {
            Spacer(Modifier.height(6.dp))
            Text(errorText, color = Reed2.statusDanger, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(22.dp))
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (subscription != null) {
                ReedCard(Modifier.fillMaxWidth()
                    .then(if (onSubscriptionClick != null) Modifier.clickable(onClick = onSubscriptionClick) else Modifier)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Подписка ${subscription.untilLabel}", color = Reed2.ink, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (subscription.totalGb > 0) {
                                Text(subscription.usageLabel, color = Reed2.inkMuted, fontFamily = LocalReedFonts.current.mono,
                                    fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                            }
                            if (onSubscriptionClick != null) {
                                Icon(Icons.Rounded.UnfoldMore, "Сменить подписку", tint = Reed2.inkMuted,
                                    modifier = Modifier.padding(start = 6.dp).size(18.dp))
                            }
                        }
                        if (subscription.totalGb > 0) { Spacer(Modifier.height(12.dp)); ReedWavyProgress(subscription.progress) }
                        if (subscription.footnote.isNotBlank()) {
                            Spacer(Modifier.height(10.dp)); Text(subscription.footnote, color = Reed2.inkMuted, fontSize = 12.sp)
                        }
                    }
                }
            } else if (subscriptionMessage != null) {
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
            val ru: @Composable (Modifier) -> Unit = { m -> DesktopToggleTile(m, "Российские сайты напрямую",
                "Банки, Госуслуги, Яндекс — мимо туннеля", ruDirect, onRuDirect) }
            val auto: @Composable (Modifier) -> Unit = { m -> DesktopToggleTile(m, "Подключаться при запуске",
                "Сразу после открытия приложения", autoConnect, onAutoConnect) }
            if (narrow) {
                ru(Modifier.fillMaxWidth()); auto(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ru(Modifier.weight(1f)); auto(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
       }
      }
    }
  }
}

@Composable
private fun DesktopToggleTile(modifier: Modifier, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Reed2.surface100).clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Reed2.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Reed2.inkMuted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Reed2.statusOk,
                uncheckedThumbColor = Reed2.chrome400, uncheckedTrackColor = Reed2.surface300,
                uncheckedBorderColor = Reed2.hairline,
            ),
        )
    }
}

/** Правая панель: поиск, «Пинг»/«Обновить», список серверов во всю высоту. */
@Composable
internal fun DesktopServersPanel(
    servers: List<ServerUi>,
    selectedKey: String?,
    connState: ReedConnState,
    onSelect: (ServerUi) -> Unit,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
    pinging: Boolean,
    refreshing: Boolean,
    emptyText: String,
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReedSectionHeader("СЕРВЕРЫ")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Reed2.surface200)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Reed2.chrome600, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Поиск по стране или городу", color = Reed2.chrome600, fontSize = 13.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Reed2.ink, fontSize = 13.sp, fontFamily = LocalReedFonts.current.body),
                        cursorBrush = SolidColor(Reed2.lime),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelPill("Пинг", Icons.Rounded.Speed, spinning = false, pulsing = pinging, onClick = onPing)
                PanelPill("Обновить", Icons.Rounded.Refresh, spinning = refreshing, pulsing = false, onClick = onRefresh)
            }
        }
        val q = query.trim().lowercase()
        val shown = if (q.isEmpty()) servers else servers.filter {
            it.countryName.lowercase().contains(q) || it.city.lowercase().contains(q)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (shown.isEmpty()) {
                Text(if (servers.isEmpty()) emptyText else "Ничего не найдено", color = Reed2.inkMuted, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center)
            } else {
                var lastGroup: String? = null
                shown.forEach { s ->
                    val g = serverGroup(s)
                    if (g != lastGroup && q.isEmpty()) {
                        if (g.isNotEmpty()) Text(g, color = Reed2.chrome600, fontSize = 11.sp, letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp, top = if (lastGroup == null) 6.dp else 14.dp, bottom = 6.dp))
                        lastGroup = g
                    }
                    ReedServerRow(
                        country = s.country, countryName = s.countryName, city = s.city, pingMs = s.pingMs,
                        selected = s.key == selectedKey, onClick = { onSelect(s) },
                        connState = if (s.key == selectedKey) connState else ReedConnState.Off,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Группы списка: Автовыбор без заголовка, затем Европа / Америка / Для поездок (Россия). */
private fun serverGroup(s: ServerUi): String = when (s.country.uppercase()) {
    "EU" -> ""
    "US", "CA", "BR", "MX" -> "АМЕРИКА"
    "RU" -> "ДЛЯ ПОЕЗДОК"
    "NL", "DE", "FI", "PL", "SE", "FR", "GB", "CH", "AT", "TR" -> "ЕВРОПА"
    else -> "ДРУГИЕ"
}

@Composable
private fun PanelPill(text: String, icon: ImageVector, spinning: Boolean, pulsing: Boolean, onClick: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "panelPill")
    val angle by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val pulse by infinite.animateFloat(1f, 0.35f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    Row(
        Modifier.clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface300)
            .clickable(enabled = !spinning, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Reed2.inkMuted.copy(alpha = if (pulsing) pulse else 1f),
            modifier = Modifier.size(15.dp).rotate(if (spinning) angle else 0f))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Reed2.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Иконки для пунктов панели, которых нет в ReedTab. */
internal object DesktopIcons {
    val support: ImageVector get() = Icons.Rounded.ChatBubble
}

@Suppress("unused")
private fun Modifier.dim(on: Boolean) = if (on) alpha(0.5f) else this
