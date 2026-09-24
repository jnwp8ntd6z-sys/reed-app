package org.olcbox.app.ui.reed2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reed 2.0 — Семья (ТЗ 4.5). Места, оповещение о новом устройстве, участники,
 * плашки-коды приглашения (RDI-) и устройства (RDX-) с плавным раскрытием.
 */
@Composable
fun ReedFamilyScreen(
    placesUsed: Int,
    placesTotal: Int,
    alert: NewDeviceAlertUi?,
    members: List<MemberUi>,
    devices: List<DeviceUi>,
    inviteCode: String?,          // код RDI- когда запрошен
    deviceCode: String?,          // код RDX- когда запрошен
    canManage: Boolean = true,    // владелец; участник — только свои устройства
    onAlertItsMe: (Int) -> Unit = {},
    onAlertBlock: (Int) -> Unit = {},
    onMemberClick: (MemberUi) -> Unit = {},
    onDeviceClick: (DeviceUi) -> Unit = {},
    onRequestInvite: () -> Unit = {},
    onRequestDevice: () -> Unit = {},
    onCopy: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Reed2.ground000).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("СЕМЬЯ", color = Reed2.ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)

        if (!canManage) {
            Spacer(Modifier.height(18.dp))
            ReedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Подпиской управляет владелец", color = Reed2.ink, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Ты в семье как участник. Места, приглашения и устройства настраивает владелец подписки.",
                        color = Reed2.inkMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            return@Column
        }

        if (placesTotal > 0) {
            Spacer(Modifier.height(10.dp))
            Text("$placesUsed из $placesTotal мест занято", color = Reed2.inkMuted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(placesTotal.coerceAtMost(12)) { i ->
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50))
                        .background(if (i < placesUsed) Reed2.chrome050 else Reed2.chrome800))
                }
            }
        }

        // Оповещение о новом устройстве.
        if (alert != null) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Reed2.cardRadius))
                .background(Color(0x1FE6B45C)).border(1.dp, Color(0x40E6B45C), RoundedCornerShape(Reed2.cardRadius))
                .padding(16.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Reed2.statusWarn),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Shield, null, tint = Reed2.onInk, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(alert.title, color = Reed2.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(alert.subtitle, color = Reed2.inkMuted, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                            .border(1.dp, Reed2.hairline, RoundedCornerShape(Reed2.pillRadius))
                            .clickable { onAlertItsMe(alert.deviceId) }, contentAlignment = Alignment.Center) {
                            Text("Это я", color = Reed2.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                            .background(Reed2.ink).clickable { onAlertBlock(alert.deviceId) },
                            contentAlignment = Alignment.Center) {
                            Text("Заблокировать", color = Reed2.onInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (canManage) {
            Spacer(Modifier.height(22.dp))
            ReedSectionHeader("УЧАСТНИКИ")
            Spacer(Modifier.height(8.dp))
            members.forEach { m -> MemberRow(m) { onMemberClick(m) } }
            Spacer(Modifier.height(12.dp))
            CodePlank(
                title = "Пригласить участника", subtitle = "Код для близкого человека",
                code = inviteCode, note = "Код действует 1 час. Участник войдёт в твою подписку.",
                onExpand = onRequestInvite, onCopy = onCopy,
            )
        }

        Spacer(Modifier.height(22.dp))
        ReedSectionHeader("ТВОИ УСТРОЙСТВА")
        Spacer(Modifier.height(8.dp))
        devices.forEach { d -> DeviceRow(d) { onDeviceClick(d) } }
        Spacer(Modifier.height(12.dp))
        CodePlank(
            title = "Добавить устройство", subtitle = "Код для твоего планшета или ноутбука",
            code = deviceCode, note = "Код действует 1 час. Устройство войдёт в твой аккаунт.",
            onExpand = onRequestDevice, onCopy = onCopy,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MemberRow(m: MemberUi, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Reed2.tileRadius)).clickable(onClick = onClick)
        .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(50))
            .border(1.5.dp, Reed2.chrome400, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Text(m.name.take(1).uppercase(), color = Reed2.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(m.name, color = Reed2.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(m.subtitle, color = Reed2.inkMuted, fontSize = 13.sp)
        }
        if (m.statusText != null) {
            val c = when (m.statusKind) {
                "ok" -> Reed2.statusOk; "warn" -> Reed2.statusWarn; "danger" -> Reed2.statusDanger
                else -> Reed2.inkMuted
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(c))
                Spacer(Modifier.width(6.dp))
                Text(m.statusText, color = c, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceUi, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Reed2.tileRadius))
        .background(Reed2.surface200).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (d.kind == "laptop") Icons.Rounded.Laptop else Icons.Rounded.Smartphone, null,
            tint = Reed2.inkMuted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(d.name, color = if (d.blocked) Reed2.inkMuted else Reed2.ink, fontSize = 16.sp)
            Text(d.subtitle, color = if (d.blocked) Reed2.statusDanger else Reed2.inkMuted, fontSize = 13.sp)
        }
        Text("›", color = Reed2.chrome600, fontSize = 20.sp)
    }
}

@Composable
private fun CodePlank(
    title: String, subtitle: String, code: String?, note: String,
    onExpand: () -> Unit, onCopy: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (expanded) 90f else 0f, tween(320), label = "arrow")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Reed2.cardRadius)).background(Reed2.surface300)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded; if (!expanded) Unit else onExpand() }
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Reed2.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Reed2.inkMuted, fontSize = 13.sp)
            }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Reed2.ink),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowOutward, null, tint = Reed2.onInk,
                    modifier = Modifier.size(20.dp).rotate(rot))
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Reed2.ground000)
                    .clickable { code?.let(onCopy) }.padding(14.dp)) {
                    Text(code ?: "Создаём код…", color = if (code != null) Reed2.ink else Reed2.chrome600,
                        fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                Text(if (code != null) "$note Нажми на код, чтобы скопировать." else note,
                    color = Reed2.inkMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}
