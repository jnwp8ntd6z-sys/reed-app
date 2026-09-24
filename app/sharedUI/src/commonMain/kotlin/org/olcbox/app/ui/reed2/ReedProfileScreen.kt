package org.olcbox.app.ui.reed2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reed 2.0 — Профиль (ТЗ 4.6). Аккаунт, проверка сети, подключение (тумблеры), помощь, аккаунт.
 * Долгое нажатие на «ПРОФИЛЬ» = выгрузка логов (host обрабатывает через Modifier).
 */
@Composable
fun ReedProfileScreen(
    username: String,
    subLabel: String,               // «Подписка активна до 15 октября»
    lastCheckLabel: String,         // «2 мин назад»
    netRows: List<NetCheckRowUi>,
    autoConnect: Boolean,
    ruDirect: Boolean,
    version: String,                // «Reed 2.0 · сборка N»
    showBotButtons: Boolean = false, // Android + Россия
    onCheckNetwork: () -> Unit = {},
    onAutoConnect: (Boolean) -> Unit = {},
    onRuDirect: (Boolean) -> Unit = {},
    onServicesDirect: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onSupport: () -> Unit = {},
    onTerms: () -> Unit = {},
    onLoginOtherCode: () -> Unit = {},
    onLogout: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    onBotSubscription: () -> Unit = {},
    onReferrals: () -> Unit = {},
    servicesDirectLabel: String = "Приложения напрямую", // iOS: «Сервисы напрямую»
    servicesDirectSubtitle: String = "Банки, госуслуги и другие",
    autoConnectSubtitle: String = "При запуске и после перезагрузки",
    onTitleLongPress: () -> Unit = {},
    netChecking: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Reed2.ground000).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        // Долгое нажатие 1,5с на заголовок → выгрузка логов (ТЗ 4.6). Кнопки логов в интерфейсе нет.
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        Text("ПРОФИЛЬ", color = Reed2.ink, fontSize = 30.sp, fontFamily = LocalReedFonts.current.headline, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(1500L) { waitForUpOrCancellation() }
                    if (up == null) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onTitleLongPress()
                    }
                }
            })

        // Аккаунт.
        Spacer(Modifier.height(16.dp))
        ReedCard(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(50))
                        .border(1.5.dp, Reed2.chrome400, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text(username.replace("@", "").take(1).uppercase(), color = Reed2.ink,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(username, color = Reed2.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(subLabel, color = Reed2.inkMuted, fontSize = 13.sp)
                    }
                }
                if (showBotButtons) {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryPill("Подписка в боте", Modifier.weight(1f), onBotSubscription)
                        SecondaryPill("Рефералы", Modifier.weight(1f), onReferrals)
                    }
                }
            }
        }

        // Проверка сети.
        Spacer(Modifier.height(14.dp))
        ReedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Проверка сети", color = Reed2.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Text(lastCheckLabel, color = Reed2.inkMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Reed проверяет каждый тип подключения на твоей сети", color = Reed2.inkMuted,
                    fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                netRows.forEach { r ->
                    val icon = when (r.label) {
                        "Wi-Fi" -> Icons.Rounded.Wifi
                        "Мобильный" -> Icons.Rounded.SignalCellularAlt
                        else -> Icons.Rounded.Language
                    }
                    val statusColor = when (r.statusKind) {
                        "ok" -> Reed2.statusOk; "warn" -> Reed2.statusWarn; "muted" -> Reed2.inkMuted
                        else -> Reed2.statusDanger
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Reed2.inkMuted, modifier = Modifier.size(20.dp))
                        Text(r.label, color = Reed2.ink, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Text(Reed2.pingText(r.pingMs), color = Reed2.inkMuted, fontFamily = LocalReedFonts.current.mono,
                            fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(statusColor))
                        Text(r.statusWord, color = statusColor, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                    .background(Reed2.ink).clickable(enabled = !netChecking, onClick = onCheckNetwork),
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (netChecking) {
                            androidx.compose.material3.CircularProgressIndicator(color = Reed2.onInk,
                                strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Rounded.NetworkCheck, null, tint = Reed2.onInk, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (netChecking) "Проверяем…" else "Проверить сеть", color = Reed2.onInk,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ПОДКЛЮЧЕНИЕ.
        Spacer(Modifier.height(20.dp))
        ReedSectionHeader("ПОДКЛЮЧЕНИЕ")
        Spacer(Modifier.height(8.dp))
        ToggleRow(Icons.Rounded.Bolt, "Автоподключение", autoConnectSubtitle, autoConnect, onAutoConnect)
        ToggleRow(Icons.Rounded.Language, "Российские сайты напрямую", "Мимо туннеля", ruDirect, onRuDirect)
        NavRow(Icons.Rounded.Apps, servicesDirectLabel, servicesDirectSubtitle, onServicesDirect)
        NavRow(Icons.Rounded.Notifications, "Уведомления", null, onNotifications)

        // ПОМОЩЬ.
        Spacer(Modifier.height(20.dp))
        ReedSectionHeader("ПОМОЩЬ")
        Spacer(Modifier.height(8.dp))
        NavRow(Icons.Rounded.ChatBubbleOutline, "Написать в поддержку", null, onSupport)
        NavRow(Icons.Rounded.Description, "Условия и конфиденциальность", null, onTerms)

        // АККАУНТ.
        Spacer(Modifier.height(20.dp))
        ReedSectionHeader("АККАУНТ")
        Spacer(Modifier.height(8.dp))
        NavRow(Icons.Rounded.Key, "Войти по другому коду", null, onLoginOtherCode)
        NavRow(Icons.Rounded.Logout, "Выйти", null, onLogout)
        NavRow(Icons.Rounded.DeleteOutline, "Удалить аккаунт", null, onDeleteAccount, tint = Reed2.statusDanger)

        Spacer(Modifier.height(24.dp))
        Text(version, color = Reed2.chrome600, fontFamily = LocalReedFonts.current.mono, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SecondaryPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface300)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Reed2.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Reed2.tileRadius)).background(Reed2.surface200)
        .padding(14.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TileIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Reed2.ink, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Reed2.inkMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Reed2.statusOk,
                uncheckedThumbColor = Reed2.chrome400, uncheckedTrackColor = Reed2.surface300,
                uncheckedBorderColor = Reed2.hairline,
            ),
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, tint: Color = Reed2.ink) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Reed2.tileRadius)).background(Reed2.surface200)
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TileIcon(icon, tint)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = tint, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Reed2.inkMuted, fontSize = 12.sp)
        }
        Text("›", color = Reed2.chrome600, fontSize = 20.sp)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TileIcon(icon: ImageVector, tint: Color = Reed2.ink) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Reed2.surface300),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}
