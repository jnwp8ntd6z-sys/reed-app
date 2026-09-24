package org.olcbox.app.ui.reed2

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reed 2.0 — Главная без кода (ТЗ 4.3). Видит человек после «Продолжить без кода» и ревьюер Apple.
 * Кнопка выключена/приглушена; карточка «Подключи подписку»; замок «Серверы появятся после входа».
 */
@Composable
fun ReedHomeNoCodeScreen(
    cardText: String,            // iOS/Android разный текст (ТЗ 4.3)
    onEnterCode: () -> Unit,
    onScanQr: () -> Unit,
    onBell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Reed2.ground000).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("REED", color = Reed2.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Reed2.lime))
            Spacer(Modifier.width(6.dp))
            Text("CLIENT", color = Reed2.chrome400, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Reed2.surface200)
                .clickable(onClick = onBell), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Notifications, "Уведомления", tint = Reed2.ink, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedConnectButton(state = ReedConnState.Off, size = 150) // приглушённая, неактивная
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedStatusDot("Нет подписки", Reed2.chrome600)
        }
        Spacer(Modifier.height(6.dp))
        Text("Добавь код, чтобы подключиться", color = Reed2.inkMuted, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)

        Spacer(Modifier.height(22.dp))
        ReedConnectSubscriptionCard(cardText = cardText, onEnterCode = onEnterCode, onScanQr = onScanQr)

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = Reed2.chrome600, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Серверы появятся после входа", color = Reed2.chrome600, fontSize = 13.sp)
        }
    }
}

/** Карточка «Подключи подписку»: «Ввести код» + круглая QR (ТЗ 4.3). */
@Composable
fun ReedConnectSubscriptionCard(
    cardText: String,
    onEnterCode: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Подключи подписку", color = Reed2.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(cardText, color = Reed2.inkMuted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                        .background(Reed2.ink).clickable(onClick = onEnterCode),
                    contentAlignment = Alignment.Center,
                ) { Text("Ввести код", color = Reed2.onInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(Reed2.surface300)
                        .clickable(onClick = onScanQr),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.QrCodeScanner, "QR", tint = Reed2.ink, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}
