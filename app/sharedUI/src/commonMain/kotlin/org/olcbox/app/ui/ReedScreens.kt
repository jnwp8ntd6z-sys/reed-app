package org.olcbox.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.olcbox.app.data.reed.AppNotification
import org.olcbox.app.data.datasource.ReedTempServer
import org.olcbox.app.data.reed.DeviceInfo
import org.olcbox.app.data.reed.DevicesResponse
import org.olcbox.app.data.reed.ReedApi
import org.olcbox.app.data.reed.ReedLinks
import org.olcbox.app.data.reed.ReedSession
import org.olcbox.app.data.reed.SubscriptionItem
import org.olcbox.app.data.reed.SubscriptionResponse
import org.olcbox.app.data.reed.decodeImageBitmap
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.features.locations.PingsState

private const val BOT_URL = "https://t.me/ReedVPNbot"
private const val SUPPORT_URL = "https://t.me/reedvps"
// olcRTC-подписка Reed (формат olcbox, отдаётся text/plain). Это рабочий транспорт
// приложения. VLESS-серверы потребуют отдельного ядра (sing-box) — в работе.
private const val REED_OLCCONF_BASE = "https://reed-vpn.duckdns.org/app/olcconf?token="
private const val REED_LOCATIONS_BASE = "https://reed-vpn.duckdns.org/app/locations?token="

private val OPERATORS = listOf("МТС", "Мегафон", "Yota", "Билайн", "Т2", "Т-Мобайл", "Ростелеком")

@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── Онбординг (первый запуск) ────────────────────────────────────────────────

@Composable
private fun OnboardFeature(icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
            MutedText(subtitle)
        }
    }
}

@Composable
fun ReedOnboardingScreen(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    onToggleClick: () -> Unit,
    onDone: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var statusMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val state by homeViewModel.state.collectAsState()

    // Временный VPN подключается ПРЯМО на этом экране (для входа), не уводя на главную.
    val tempConnected = state.isVpnConnected
    val tempConnecting = state.isVpnLoading
    val limitReached = ReedSession.tempUsedBytes >= ReedTempServer.LIMIT_BYTES

    // Таймер сессии временного VPN.
    var sessionSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(tempConnected) {
        if (tempConnected) { sessionSeconds = 0L; while (true) { delay(1000); sessionSeconds += 1 } }
        else sessionSeconds = 0L
    }

    // Плавный переход цвета кнопки: белая → зелёная при подключении.
    val tempBtnBg by animateColorAsState(
        targetValue = if (tempConnected) MaterialTheme.colorScheme.primary else Color.White,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing), label = "tempBtnBg")
    val tempBtnFg = if (tempConnected) MaterialTheme.colorScheme.onPrimary else Color(0xFF0A0A0A)

    // Тёмный брендовый фон Reed — экран входа показывается раньше основного интерфейса,
    // поэтому фон задаём явно (иначе видно серое окно платформы).
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            // Только надпись слева сверху — без логотипа и слогана.
            Text("REED VPN", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black, color = Color.White)

            Spacer(Modifier.height(40.dp))

            // 1) Временный VPN — белая кнопка ВЫШЕ регистрации. Подключается здесь же,
            // чтобы через него дойти до Telegram и зарегистрироваться (вход → бот).
            Text("VPN для регистрации через Telegram. Работает только приложение и Telegram — этого достаточно, чтобы войти. Полное управление подпиской — в Telegram-боте.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (tempConnecting) return@Button
                    if (tempConnected) {
                        onToggleClick()  // отключить
                    } else if (!limitReached) {
                        // Выбрать временный сервер и подключиться, оставаясь на экране входа.
                        locationViewModel.selectLocation(ReedTempServer.STORAGE_ID) {
                            homeViewModel.loadCurrentConfig()
                            onToggleClick()
                        }
                    }
                },
                enabled = !limitReached || tempConnected,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tempBtnBg, contentColor = tempBtnFg),
            ) {
                Text(
                    when {
                        tempConnected -> "Временный VPN подключён · ${formatSession(sessionSeconds)}"
                        tempConnecting -> "Подключаюсь…"
                        limitReached -> "Лимит временного VPN исчерпан"
                        else -> "Подключить временный VPN"
                    },
                    fontWeight = FontWeight.Black)
            }
            if (tempConnected) {
                Spacer(Modifier.height(8.dp))
                Text("Готово — теперь зарегистрируйтесь через Telegram ниже.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(20.dp))

            // 2) Регистрация через Telegram (лаймовая основная кнопка).
            ReedPrimaryButton(
                text = if (busy) "Подтвердите в Telegram…" else "Зарегистрироваться через Telegram",
                enabled = !busy,
            ) {
                if (busy) return@ReedPrimaryButton
                busy = true
                statusMsg = ""
                scope.launch {
                    try {
                        val start = ReedApi.authStart()
                        uri.openUri(start.deeplink)
                        statusMsg = "Подтвердите вход в Telegram…"
                        repeat(60) {
                            delay(2000)
                            val poll = ReedApi.authPoll(start.nonce)
                            if (poll.status == "ok") {
                                ReedSession.token = poll.token
                                ReedSession.onboardingDone = true
                                onDone()
                                return@launch
                            }
                        }
                        statusMsg = "Вход не завершён, попробуйте снова"
                    } catch (e: Throwable) {
                        statusMsg = "Ошибка входа, попробуйте снова"
                    }
                    busy = false
                }
            }

            if (statusMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(statusMsg, style = MaterialTheme.typography.bodySmall, color = Color.White)
            }

            Spacer(Modifier.height(16.dp))
            // 3) Текстовая кнопка без подложки — пропустить временный VPN.
            TextButton(
                onClick = { ReedSession.onboardingDone = true; onDone() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Мне не нужен временный VPN", color = Color.White,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// Строка согласия: чекбокс (✅) + название документа + иконка ℹ️ для открытия текста.
@Composable
private fun ConsentRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            modifier = Modifier.weight(1f).clickable { onCheckedChange(!checked) },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            Icons.Rounded.Info,
            contentDescription = "Открыть текст",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onInfo() },
        )
    }
}

// Модальное окно с полным текстом документа (прокручивается). Внутри приложения.
@Composable
private fun PolicyDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

// Раскрывающаяся плашка: иконка + заголовок + подзаголовок + поворачивающийся шеврон.
@Composable
private fun ExpandablePlashka(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(ReedCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ReedCardShape)
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                if (subtitle != null) { Spacer(Modifier.height(2.dp)); MutedText(subtitle) }
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                ReedCard { content() }
            }
        }
    }
}

// Плашка-тумблер: иконка (лайм если вкл, оранжевая если выкл) + заголовок + Switch.
@Composable
private fun TogglePlashka(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(ReedCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ReedCardShape)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label.uppercase(), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// Маленькая кликабельная иконка-действие с подписью (для «Обновить» / «Тест»).
@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary)
    }
}

// ── Главная ──────────────────────────────────────────────────────────────────

private const val BYTES_IN_GB = 1024.0 * 1024.0 * 1024.0

private fun formatCountdown(secondsLeft: Long): String {
    if (secondsLeft <= 0) return "0Д 0Ч 0М"
    val d = secondsLeft / 86400
    val h = (secondsLeft % 86400) / 3600
    val m = (secondsLeft % 3600) / 60
    return "${d}Д ${h}Ч ${m}М"
}

private fun formatSession(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    fun p(v: Long) = v.toString().padStart(2, '0')
    return "${p(h)}:${p(m)}:${p(s)}"
}

// Размер трафика: ГБ, а от 1000 ГБ — в ТБ.
private fun formatSize(bytes: Long): String {
    val gb = bytes / BYTES_IN_GB
    return if (gb >= 1000) {
        val tb = (gb / 1024.0 * 10).toLong() / 10.0
        "$tb ТБ"
    } else {
        "${gb.toLong()} ГБ"
    }
}

private fun pingFor(state: PingsState, id: String): Int? = when (state) {
    is PingsState.Success -> state.pings[id]
    is PingsState.Loading -> state.currentPings[id] ?: state.lastPings?.get(id)
    is PingsState.Error -> state.lastPings?.get(id)
    PingsState.Idle -> null
}

// Идёт ли сейчас замер пинга для этой локации (для крутящегося индикатора у строки).
private fun pingLoadingFor(state: PingsState, id: String): Boolean =
    state is PingsState.Loading && state.pendingLocationIds.contains(id)

@Composable
private fun ratioColor(ratio: Double) = when {
    ratio < 0.7 -> MaterialTheme.colorScheme.primary
    ratio < 0.9 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun ReedTrafficBar(label: String, valueGb: Double, maxGb: Double) {
    val ratio = if (maxGb > 0) (valueGb / maxGb).coerceIn(0.0, 1.0) else 0.0
    val barColor = ratioColor(ratio)
    val shown = if (valueGb >= 100) valueGb.toLong().toString()
        else ((valueGb * 10).toLong() / 10.0).toString()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label.uppercase(), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shown, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(4.dp))
            Text("ГБ", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
        ) {
            Box(
                Modifier.fillMaxWidth(ratio.toFloat()).height(10.dp)
                    .background(barColor, RoundedCornerShape(50))
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("из ${maxGb.toLong()} ГБ", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Круглая кнопка подключения: плавно загорается зелёным при подключении + таймер сессии.
// Без кольца-прогресса (по просьбе владельца) — просто цвет + время.
@Composable
private fun ReedConnectButton(
    isConnected: Boolean,
    isLoading: Boolean,
    sessionSeconds: Long,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer

    // Плавный переход фона/значка: серый → зелёный (а не резкий скачок цвета).
    val bg by animateColorAsState(
        targetValue = if (isConnected) primary else surface,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "connectBg",
    )
    val glyphColor by animateColorAsState(
        targetValue = if (isConnected) onPrimary else primary,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "connectGlyph",
    )

    Box(
        modifier = Modifier
            .size(196.dp)
            .clip(CircleShape)
            .background(bg)
            .border(if (isConnected) 0.dp else 6.dp, outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Подключение",
                tint = glyphColor, modifier = Modifier.size(60.dp))
            if (isConnected) {
                Spacer(Modifier.height(6.dp))
                Text(formatSession(sessionSeconds), color = onPrimary,
                    fontWeight = FontWeight.Black, fontSize = 16.sp)
            } else if (isLoading) {
                Spacer(Modifier.height(6.dp))
                Text("Подключаюсь…", color = primary, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
    }
}

// Плашка одного сервера в списке. Временный сервер (для регистрации) — оранжевый акцент
// и трафик «x/5 ГБ» прямо в названии; остальные — лаймовый акцент.
@Composable
private fun ReedServerRow(
    loc: LocationItem,
    isSelected: Boolean,
    isConnectedHere: Boolean,
    ping: Int?,
    pingLoading: Boolean,
    serversRefreshing: Boolean,
    serversRefreshed: Boolean,
    desc: String?,
    onClick: () -> Unit,
) {
    val isTemp = ReedTempServer.isTemp(loc.storageId)
    val accent = if (isTemp) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val bg = when {
        isTemp -> MaterialTheme.colorScheme.secondary.copy(alpha = if (isSelected) 0.20f else 0.12f)
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Box(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(
                if (isSelected || isTemp) 2.dp else 1.dp,
                if (isTemp) accent
                else if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // У временного — трафик «x/5 ГБ» прямо в названии.
                val tempUsedGb = (ReedSession.tempUsedBytes.toDouble() /
                    (1024.0 * 1024 * 1024) * 10).toLong() / 10.0
                val title = if (isTemp) "${loc.fullName}  ·  $tempUsedGb/5 ГБ" else loc.fullName
                Text(title,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black,
                    color = if (isTemp || isSelected) accent else MaterialTheme.colorScheme.onSurface)
                // Индикатор активного транспорта: VLESS (обычный) или LTE (olcRTC, при белых
                // списках). Помогает понять, какой движок задействован на этом сервере.
                if (!isTemp) {
                    val transportLabel = if (loc.config?.isVless() == true) "VLESS" else "LTE"
                    Text(transportLabel,
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                }
                if (!desc.isNullOrBlank()) {
                    Text(desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Трейлинг: обновление серверов → спиннер; после успеха → галочка (гаснет);
            // замер пинга → спиннер; иначе — значение пинга.
            if (serversRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary)
            } else {
                AnimatedVisibility(visible = serversRefreshed, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Rounded.Check, contentDescription = "Обновлено",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                if (!serversRefreshed) {
                    if (pingLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(if (ping != null) "$ping мс" else "—",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isConnectedHere) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(10.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
fun ReedHomeScreen(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    onToggleClick: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val state by homeViewModel.state.collectAsState()
    val locations = locationViewModel.locations.toList()
    val pingsState = locationViewModel.pingsState
    val selectedId = locationViewModel.selectedLocationId

    // Локальное зеркало токена — чтобы перерисовать экран после входа на главном экране.
    var token by remember { mutableStateOf(ReedSession.token) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginMsg by remember { mutableStateOf("") }
    var logsMsg by remember { mutableStateOf("") }
    // Индикация кнопки «Обновить»: крутящийся спиннер у серверов → галочка → плавно гаснет.
    var serversRefreshing by remember { mutableStateOf(false) }
    var serversRefreshed by remember { mutableStateOf(false) }

    var data by remember { mutableStateOf<SubscriptionResponse?>(null) }
    // Первая загрузка подписки ещё не завершилась — чтобы не мигать «Подписка не
    // активна», пока идёт запрос (выглядело как «вылет из аккаунта» при перезаходе).
    var subLoaded by remember { mutableStateOf(false) }
    suspend fun reloadSubscription() {
        val t = ReedSession.token ?: return
        // Офлайн/сбой сети → НЕ выходим из аккаунта: показываем кэш последней активной
        // подписки (или то, что уже было загружено), а не «Подписка закончилась».
        // Кэш показываем СРАЗУ (до сетевого запроса), чтобы офлайн экран не залипал на
        // «Загрузка аккаунта» и сразу был виден аккаунт + кнопка подключения.
        val cached = ReedApi.cachedSubscription()
        if (data == null && cached != null) { data = cached; subLoaded = true }
        data = try { ReedApi.subscription(t) } catch (e: Throwable) { data ?: cached }
        subLoaded = true
    }
    LaunchedEffect(ReedSession.token) { reloadSubscription() }

    // Все подписки аккаунта (для переключателя «Сменить подписку» на карточке трафика).
    var subsList by remember { mutableStateOf<List<SubscriptionItem>>(emptyList()) }
    var showSubSwitcher by remember { mutableStateOf(false) }
    suspend fun reloadSubsList() {
        val t = ReedSession.token ?: return
        subsList = try { ReedApi.subscriptions(t).subscriptions } catch (e: Throwable) { subsList }
    }
    LaunchedEffect(ReedSession.token) { reloadSubsList() }

    // Нажал «Подключить временный VPN» на экране входа → авто-выбор и подключение
    // временного сервера (если не превышен лимит 5 ГБ на устройство).
    LaunchedEffect(Unit) {
        if (ReedSession.useTempVpnOnEntry) {
            ReedSession.useTempVpnOnEntry = false
            if (ReedSession.tempUsedBytes < ReedTempServer.LIMIT_BYTES) {
                locationViewModel.selectLocation(ReedTempServer.STORAGE_ID) {
                    homeViewModel.loadCurrentConfig()
                    onToggleClick()
                }
            }
        }
    }

    // Авто-импорт серверов Reed после входа: один раз на токен, если серверов ещё нет.
    // Тянем olcRTC-конфиги (родной транспорт движка) — кнопкой можно подключиться.
    LaunchedEffect(ReedSession.token, locations.size) {
        val t = ReedSession.token
        // «Серверов ещё нет» = нет НИ ОДНОЙ не-временной локации (временный сервер
        // присутствует всегда, поэтому isEmpty() тут не годится).
        val hasRealServers = locations.any { !ReedTempServer.isTemp(it.storageId) }
        if (t != null && !hasRealServers && ReedSession.importedForToken != t) {
            ReedSession.importedForToken = t
            // Сначала olcRTC-локации (родной транспорт движка), затем VLESS из
            // /app/locations. VLESS импортируется последним → именно быстрый VLESS
            // становится сервером по умолчанию (parseReedLocations выставляет активным
            // первый VLESS), а не запасной LTE-olcRTC.
            homeViewModel.onImportFullConfig(
                rawText = "$REED_OLCCONF_BASE$t",
                onComplete = {
                    homeViewModel.onImportFullConfig(
                        rawText = "$REED_LOCATIONS_BASE$t",
                        onComplete = { locationViewModel.loadLocations { } },
                        onError = { locationViewModel.loadLocations { } },
                    )
                },
                onError = { ReedSession.importedForToken = null },
            )
        }
    }

    // Авто-пинг и авто-подключение при запуске (фишки Happ).
    var autoPinged by remember { mutableStateOf(false) }
    var autoConnected by remember { mutableStateOf(false) }
    LaunchedEffect(locations.size, state.canStartVpn, state.isVpnConnected) {
        val hasReal = locations.any { !ReedTempServer.isTemp(it.storageId) }
        // Авто-пинг всех серверов один раз, как только появились реальные серверы —
        // чтобы сортировка по пингу сразу была актуальной.
        if (hasReal && !autoPinged) {
            autoPinged = true
            locationViewModel.refreshPings(
                targetLocationIds = null,
                performPing = { config -> homeViewModel.performPingFor(config) },
            )
        }
        // Авто-подключение к выбранному серверу один раз за запуск, если включено в
        // настройках и сейчас не подключено. Не вмешивается во временный VPN со входа.
        if (ReedSession.autoConnect && !autoConnected && hasReal &&
            !ReedSession.useTempVpnOnEntry &&
            !state.isVpnConnected && !state.isVpnLoading && state.canStartVpn
        ) {
            autoConnected = true
            onToggleClick()
        }
    }

    // Локальный таймер сессии: считаем секунды, пока VPN подключён.
    var sessionSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(state.isVpnConnected) {
        if (state.isVpnConnected) {
            sessionSeconds = 0L
            while (true) { delay(1000); sessionSeconds += 1 }
        } else {
            sessionSeconds = 0L
        }
    }

    val hasSubscription = data?.subscription?.status == "active"
    val sub = data?.subscription

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Text("REED VPN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))

        // Согласие/политики убраны с главного экрана — все документы теперь внизу
        // Личного кабинета (ReedAccountScreen).

        if (token == null) {
            // Не вошёл через Telegram — даём рабочую кнопку регистрации прямо на главном.
            ReedCard {
                Text("Войдите через Telegram", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("Чтобы подтянуть подписку и серверы.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                ReedPrimaryButton(
                    text = if (loginBusy) "Подтвердите в Telegram…" else "Зарегистрироваться через Telegram",
                    enabled = !loginBusy,
                ) {
                    if (loginBusy) return@ReedPrimaryButton
                    loginBusy = true
                    loginMsg = ""
                    scope.launch {
                        try {
                            val start = ReedApi.authStart()
                            uri.openUri(start.deeplink)
                            loginMsg = "Подтвердите вход в Telegram…"
                            repeat(60) {
                                delay(2000)
                                val poll = ReedApi.authPoll(start.nonce)
                                if (poll.status == "ok") {
                                    ReedSession.token = poll.token
                                    token = poll.token
                                    loginMsg = ""
                                    return@launch
                                }
                            }
                            loginMsg = "Вход не завершён, попробуйте снова"
                        } catch (e: Throwable) {
                            loginMsg = "Ошибка входа, попробуйте снова"
                        }
                        loginBusy = false
                    }
                }
                if (loginMsg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp)); MutedText(loginMsg)
                }
            }
            Spacer(Modifier.height(20.dp))
        } else if (ReedSession.token != null && !subLoaded && data == null) {
            // Идёт первичная загрузка данных аккаунта — показываем нейтральный
            // плейсхолдер, а не «Подписка не активна» (чтобы не выглядело как вылет).
            ReedCard {
                Text("Загрузка аккаунта…", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                MutedText("Подключаемся к серверу Reed VPN.")
            }
            Spacer(Modifier.height(20.dp))
        } else if (!hasSubscription) {
            // Нейтральный блок без призыва к оплате (требование Apple App Store):
            // всё управление подпиской — в Telegram-боте.
            ReedCard {
                Text("Подписка закончилась", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("Настройте в боте.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                ReedPrimaryButton("Telegram бот") { uri.openUri(BOT_URL) }
            }
            Spacer(Modifier.height(20.dp))
        } else {
            // Предупреждение об окончании подписки (за 3 дня) — фишка Happ. В приложении
            // НЕ предлагаем оплату: всё управление в Telegram-боте (правило Apple).
            val secLeft = sub?.seconds_left ?: 0L
            if (secLeft in 1..(3L * 86400)) {
                val daysLeft = (secLeft + 86399) / 86400  // округление вверх до дня
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f))
                        .border(1.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
                        .clickable { uri.openUri(BOT_URL) }
                        .padding(16.dp)
                ) {
                    Column {
                        Text("Подписка заканчивается через $daysLeft дн.",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(2.dp))
                        MutedText("Полное управление подпиской — в Telegram-боте.")
                    }
                }
            }

            // Карточка трафика. Если у аккаунта несколько подписок — она раскрывается
            // в список со сменой активной подписки (как плашки в настройках).
            val switchable = subsList.size > 1
            ReedCard {
                Column(
                    Modifier.fillMaxWidth().then(
                        if (switchable) Modifier.clickable { showSubSwitcher = !showSubSwitcher }
                        else Modifier
                    )
                ) {
                    val normalUsed = (data?.traffic?.used ?: 0L) / BYTES_IN_GB
                    val normalTotal = (data?.traffic?.total ?: 0L) / BYTES_IN_GB
                    ReedTrafficBar("Трафик", normalUsed, normalTotal)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatCountdown(sub?.seconds_left ?: 0L),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(2.dp))
                        Text((sub?.plan_label ?: "Окончание подписки").uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (switchable) {
                        Spacer(Modifier.height(10.dp))
                        // Текст сверху (мелкий, серый), стрелка ПОД текстом и всегда
                        // направлена вниз (ChevronRight повёрнут на 90°), серого цвета.
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Нажмите, чтобы сменить подписку",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(2.dp))
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(90f))
                        }
                    }
                }
                AnimatedVisibility(visible = showSubSwitcher && switchable,
                    enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        subsList.forEach { item ->
                            val isCur = item.current || item.sub_token == ReedSession.token
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isCur) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surfaceContainer)
                                    .border(1.dp,
                                        if (isCur) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(12.dp))
                                    .clickable(enabled = !isCur) {
                                        // Переключение активной подписки = смена токена.
                                        ReedSession.token = item.sub_token
                                        ReedSession.importedForToken = null  // переимпорт серверов
                                        token = item.sub_token
                                        showSubSwitcher = false
                                        scope.launch { reloadSubscription(); reloadSubsList() }
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.plan_label ?: "Подписка",
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black,
                                        color = if (isCur) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface)
                                    MutedText("ещё ${item.days_left} дн.")
                                }
                                if (isCur) {
                                    Icon(Icons.Rounded.Check, contentDescription = "Активна",
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        val canToggle = state.isVpnConnected || state.isVpnLoading || state.canStartVpn
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReedConnectButton(
                isConnected = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                sessionSeconds = sessionSeconds,
                onClick = { if (canToggle) onToggleClick() },
            )
        }
        Spacer(Modifier.height(28.dp))

        // Заголовок СЕРВЕРЫ + Обновить (подписку) + Тест (пинг)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Router, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("СЕРВЕРЫ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            IconAction(Icons.Rounded.Refresh, "Обновить") {
                if (serversRefreshing) return@IconAction
                serversRefreshing = true
                serversRefreshed = false
                // Предохранитель: если колбэк не придёт — снять спиннер через 10с.
                scope.launch { delay(10_000); if (serversRefreshing) serversRefreshing = false }
                scope.launch {
                    reloadSubscription()
                    homeViewModel.refreshSubscriptions {
                        locationViewModel.loadLocations {
                            serversRefreshing = false
                            serversRefreshed = true
                            // Галочка плавно гаснет сама через 2с.
                            scope.launch { delay(2000); serversRefreshed = false }
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            IconAction(Icons.Rounded.Bolt, "Тест") {
                serversRefreshed = false  // юзер сразу что-то нажал → галочку убираем резко
                locationViewModel.refreshPings(
                    targetLocationIds = null,
                    performPing = { config -> homeViewModel.performPingFor(config) },
                )
            }
            Spacer(Modifier.width(6.dp))
            // Экспорт логов подключения (для диагностики проблем с VPN) — открывает
            // системное «Поделиться»: можно отправить лог в Telegram-бот/поддержку.
            IconAction(Icons.Rounded.BugReport, "Логи") {
                // Android/iOS — системное «Поделиться»; Windows — копирование в буфер.
                // Показываем короткое подтверждение, чтобы на десктопе кнопка не «молчала».
                homeViewModel.onShareLogs(
                    onShared = { logsMsg = it.ifBlank { "Логи готовы" } },
                    onError = { logsMsg = "Не удалось выгрузить логи" },
                )
            }
        }
        if (logsMsg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MutedText(logsMsg)
        }
        Spacer(Modifier.height(14.dp))

        // Описания серверов из /app/subscription (имя → описание): "Быстрый. Без рекламы." и т.п.
        val serverDescByName: Map<String, String> =
            data?.servers?.associate { it.name to it.desc }.orEmpty()

        // Список серверов с учётом подписки (главная фишка — временный сервер):
        //  • нет активной подписки (не вошёл / кончилась) → в списке ТОЛЬКО временный;
        //  • подписка активна → реальные серверы, а временный уезжает в конец и прячется
        //    под сворачиваемую строку (оранжевый акцент, всегда доступен).
        // Серверы сортируются по пингу (быстрейший сверху, недоступные — в конце) — фишка Happ.
        val realServers = locations
            .filter { !ReedTempServer.isTemp(it.storageId) }
            .sortedBy { pingFor(pingsState, it.storageId) ?: Int.MAX_VALUE }
        val tempServer = locations.firstOrNull { ReedTempServer.isTemp(it.storageId) }

        // Общий onClick для выбора сервера.
        fun selectServer(loc: LocationItem) {
            serversRefreshed = false  // выбор сервера → галочку убираем резко
            locationViewModel.selectLocation(loc.storageId) {
                homeViewModel.loadCurrentConfig()
                homeViewModel.restartVpnIfRunning()
            }
        }

        if (locations.isEmpty()) {
            ReedCard {
                MutedText("Серверы появятся автоматически после входа. Полное управление подпиской — в Telegram-боте.")
            }
        } else if (hasSubscription && realServers.isNotEmpty()) {
            realServers.forEach { loc ->
                ReedServerRow(
                    loc = loc,
                    isSelected = loc.storageId == selectedId,
                    isConnectedHere = state.isVpnConnected && loc.storageId == selectedId,
                    ping = pingFor(pingsState, loc.storageId),
                    pingLoading = pingLoadingFor(pingsState, loc.storageId),
                    serversRefreshing = serversRefreshing,
                    serversRefreshed = serversRefreshed,
                    desc = serverDescByName[loc.fullName],
                    onClick = { selectServer(loc) },
                )
            }
            if (tempServer != null) {
                // Временный сервер спрятан в конце; раскрыт, если он сейчас выбран/подключён.
                var showTemp by remember {
                    mutableStateOf(state.isVpnConnected && selectedId == tempServer.storageId)
                }
                val rot by animateFloatAsState(if (showTemp) 90f else 0f, label = "tempChevron")
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { showTemp = !showTemp }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.rotate(rot))
                    Spacer(Modifier.width(6.dp))
                    Text("Временный сервер для регистрации",
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(visible = showTemp,
                    enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    ReedServerRow(
                        loc = tempServer,
                        isSelected = tempServer.storageId == selectedId,
                        isConnectedHere = state.isVpnConnected && tempServer.storageId == selectedId,
                        ping = pingFor(pingsState, tempServer.storageId),
                        pingLoading = pingLoadingFor(pingsState, tempServer.storageId),
                        serversRefreshing = serversRefreshing,
                        serversRefreshed = serversRefreshed,
                        desc = ReedTempServer.DESC,
                        onClick = { selectServer(tempServer) },
                    )
                }
            }
        } else {
            // Нет активной подписки → показываем ТОЛЬКО временный сервер (вместо всех).
            val onlyTemp = tempServer ?: realServers.firstOrNull()
            if (onlyTemp != null) {
                ReedServerRow(
                    loc = onlyTemp,
                    isSelected = onlyTemp.storageId == selectedId,
                    isConnectedHere = state.isVpnConnected && onlyTemp.storageId == selectedId,
                    ping = pingFor(pingsState, onlyTemp.storageId),
                    pingLoading = pingLoadingFor(pingsState, onlyTemp.storageId),
                    serversRefreshing = serversRefreshing,
                    serversRefreshed = serversRefreshed,
                    desc = if (ReedTempServer.isTemp(onlyTemp.storageId)) ReedTempServer.DESC
                        else serverDescByName[onlyTemp.fullName],
                    onClick = { selectServer(onlyTemp) },
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ── Настройки ────────────────────────────────────────────────────────────────

@Composable
private fun DeviceRow(
    dev: DeviceInfo,
    busy: Boolean,
    onToggleBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusLine = buildString {
        if (dev.os.isNotBlank()) append(dev.os)
        val seen = dev.last_seen.take(10)
        if (seen.isNotBlank()) {
            if (isNotEmpty()) append(" • ")
            append("был $seen")
        }
        if (dev.blocked) {
            if (isNotEmpty()) append(" • ")
            append("Заблокирован")
        } else if (isEmpty()) {
            append("Активен")
        }
    }
    Box(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(
                1.dp,
                if (dev.blocked) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Smartphone, contentDescription = null,
                    tint = if (dev.blocked) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(dev.name, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black)
                    MutedText(statusLine)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onToggleBlock, enabled = !busy,
                    modifier = Modifier.weight(1f)) {
                    Text(if (dev.blocked) "Разблокировать" else "Заблокировать",
                        style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Удалить", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
fun ReedSettingsScreen() {
    val scope = rememberCoroutineScope()
    var operator by remember { mutableStateOf("МТС") }
    var splitRouting by remember { mutableStateOf(ReedSession.splitRouting) }
    var autoConnect by remember { mutableStateOf(ReedSession.autoConnect) }
    var data by remember { mutableStateOf<SubscriptionResponse?>(null) }
    var devicesData by remember { mutableStateOf<DevicesResponse?>(null) }
    var devicesBusy by remember { mutableStateOf(false) }
    LaunchedEffect(ReedSession.token) {
        val t = ReedSession.token
        if (t != null) {
            val r = try { ReedApi.subscription(t) } catch (e: Throwable) { null }
            data = r
            r?.current_operator?.let { operator = it }
            devicesData = try { ReedApi.devices(t) } catch (e: Throwable) { null }
        }
    }
    val operators = data?.operators?.takeIf { it.isNotEmpty() } ?: OPERATORS

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        ScreenTitle("Настройки")
        Spacer(Modifier.height(20.dp))

        // Сменить оператора — раскрывающийся список
        ExpandablePlashka(Icons.Rounded.Router, "Сменить оператора", "Текущий: $operator") {
            operators.forEach { op ->
                val sel = op == operator
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .border(
                            if (sel) 2.dp else 1.dp,
                            if (sel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            operator = op
                            val t = ReedSession.token
                            if (t != null) {
                                scope.launch {
                                    try { ReedApi.setOperator(t, op) } catch (e: Throwable) {}
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(op, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black,
                        color = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Трафик — обычный (LTE-серверы olcRTC без лимита)
        ExpandablePlashka(Icons.Rounded.Storage, "Трафик") {
            val totalBytes = data?.traffic?.total ?: 0L
            val usedBytes = data?.traffic?.used ?: 0L
            Text("Трафик", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                if (totalBytes > 0) "${formatSize(usedBytes)} из ${formatSize(totalBytes)}" else "—",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                MutedText("LTE-серверы («когда глушат интернет») работают без ограничения трафика.")
            }
            Spacer(Modifier.height(6.dp))
            MutedText("Обычный трафик обновляется 1 числа каждого месяца.")
        }
        Spacer(Modifier.height(12.dp))

        // Устройства — реальные данные из /app/devices
        val dd = devicesData
        val devSubtitle = if (dd != null) "Занято ${dd.used} из ${dd.limit}" else "—"
        ExpandablePlashka(Icons.Rounded.Smartphone, "Устройства", devSubtitle) {
            // действие над устройством + перезагрузка списка
            fun act(deviceId: Int, action: String) {
                val t = ReedSession.token ?: return
                if (devicesBusy) return
                devicesBusy = true
                scope.launch {
                    try { ReedApi.deviceAction(t, deviceId, action) } catch (e: Throwable) {}
                    devicesData = try { ReedApi.devices(t) } catch (e: Throwable) { devicesData }
                    devicesBusy = false
                }
            }

            if (dd == null) {
                MutedText("Управление устройствами появится после входа в аккаунт.")
            } else {
                val all = dd.devices + dd.blocked
                if (all.isEmpty()) {
                    MutedText("Пока нет подключённых устройств.")
                } else {
                    Text("ПОДКЛЮЧЁННЫЕ УСТРОЙСТВА", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    all.forEach { dev ->
                        DeviceRow(dev, devicesBusy, onToggleBlock = { act(dev.id, "toggle_block") },
                            onDelete = { act(dev.id, "delete") })
                    }
                    MutedText("Заблокированное устройство не сможет подключиться. Удаление освобождает слот.")
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        TogglePlashka(Icons.Rounded.Wifi, "Split-routing", splitRouting) {
            splitRouting = it
            ReedSession.splitRouting = it
        }
        Spacer(Modifier.height(4.dp))
        MutedText("Российские сайты идут напрямую, мимо VPN. Применяется при следующем подключении.")
        Spacer(Modifier.height(12.dp))

        TogglePlashka(Icons.Rounded.Bolt, "Авто-подключение", autoConnect) {
            autoConnect = it
            ReedSession.autoConnect = it
        }
        Spacer(Modifier.height(4.dp))
        MutedText("Приложение само подключится к выбранному серверу при запуске и после перезагрузки телефона.")
        Spacer(Modifier.height(28.dp))
    }
}

// ── Поддержка ────────────────────────────────────────────────────────────────
@Composable
fun ReedSupportScreen() {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        ScreenTitle("Поддержка")
        Spacer(Modifier.height(20.dp))
        ReedCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("💬", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(12.dp))
                Text("Чат с поддержкой", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                MutedText("Напишите нам в Telegram — поможем быстро.")
                Spacer(Modifier.height(20.dp))
                ReedPrimaryButton("Написать в поддержку") { uri.openUri(SUPPORT_URL) }
            }
        }
    }
}

// ── Личный кабинет ───────────────────────────────────────────────────────────
@Composable
fun ReedAccountScreen() {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(ReedSession.token) }
    // Документ для модального окна (внизу ЛК): title -> body; null = закрыто.
    var policyDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    policyDialog?.let { (title, body) ->
        PolicyDialog(title = title, body = body, onDismiss = { policyDialog = null })
    }
    var data by remember { mutableStateOf<SubscriptionResponse?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var friendCode by remember { mutableStateOf("") }
    var friendSaved by remember { mutableStateOf(false) }
    var avatar by remember { mutableStateOf<ImageBitmap?>(null) }
    var notifications by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(token) {
        val t = token
        if (t != null) {
            statusMsg = "Загрузка…"
            data = try { ReedApi.subscription(t) } catch (e: Throwable) {
                statusMsg = "Не удалось загрузить данные"; null
            }
            if (data != null) statusMsg = ""
            notifications = try { ReedApi.notifications(t).notifications } catch (e: Throwable) { emptyList() }
            val bytes = ReedApi.avatarBytes(t)
            if (bytes != null) avatar = decodeImageBitmap(bytes)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        ScreenTitle("Личный кабинет")
        Spacer(Modifier.height(20.dp))

        if (token == null) {
            ReedCard {
                Text("Войдите через Telegram, чтобы увидеть подписку, трафик и реферальный код.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                ReedPrimaryButton("Зарегистрироваться через Telegram") {
                    scope.launch {
                        try {
                            val start = ReedApi.authStart()
                            uri.openUri(start.deeplink)
                            statusMsg = "Подтвердите вход в Telegram…"
                            repeat(60) {
                                delay(2000)
                                val poll = ReedApi.authPoll(start.nonce)
                                if (poll.status == "ok") {
                                    ReedSession.token = poll.token
                                    token = poll.token
                                    return@launch
                                }
                            }
                            statusMsg = "Вход не завершён, попробуйте снова"
                        } catch (e: Throwable) {
                            statusMsg = "Ошибка входа, попробуйте снова"
                        }
                    }
                }
                if (statusMsg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp)); MutedText(statusMsg)
                }
            }
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        val d = data
        val sub = d?.subscription

        // Профиль в стиле Telegram: круглый аватар + имя + @username
        ReedCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val av = avatar
                if (av != null) {
                    Image(
                        bitmap = av,
                        contentDescription = "Аватар",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Person, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(d?.profile?.name?.takeIf { it.isNotBlank() } ?: "Аккаунт",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    val uname = d?.profile?.username?.takeIf { it.isNotBlank() }
                    if (uname != null) { Spacer(Modifier.height(2.dp)); MutedText("@$uname") }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.height(14.dp))
            Text("ТЕКУЩАЯ ПОДПИСКА", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(sub?.plan_label ?: sub?.plan_id ?: statusMsg.ifEmpty { "—" },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text("заканчивается через ${sub.days_left} дн.",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Управление подпиской — только в Telegram-боте (требование Apple App Store:
        // в приложении не должно быть переходов на оплату).
        ReedCard {
            Text("Полное управление подпиской осуществляется в Telegram-Боте.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            ReedPrimaryButton("Telegram бот") { uri.openUri(BOT_URL) }
        }
        Spacer(Modifier.height(14.dp))

        // Реферальная программа
        ExpandablePlashka(Icons.Rounded.CardGiftcard, "Реферальная программа") {
            MutedText("Ваш реферальный код")
            Spacer(Modifier.height(2.dp))
            Text(d?.referral?.code ?: "—", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            MutedText("Делитесь кодом — вам начисляются бонусы за приглашённых друзей.")
            Spacer(Modifier.height(14.dp))
            MutedText("Реферальный код друга")
            Spacer(Modifier.height(6.dp))
            if (friendSaved) {
                Text(friendCode, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                MutedText("Изменить код нельзя")
            } else {
                OutlinedTextField(
                    value = friendCode,
                    onValueChange = { friendCode = it },
                    label = { Text("Введите код друга") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ReedPrimaryButton("Применить") {
                    if (friendCode.isNotBlank()) friendSaved = true
                }
            }
            Spacer(Modifier.height(14.dp))
            MutedText("Ваш бонусный баланс")
            Spacer(Modifier.height(2.dp))
            Text("${d?.referral?.bonus_balance ?: 0} бонусов", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            MutedText("Подробности — в Telegram-боте и поддержке.")
        }
        Spacer(Modifier.height(14.dp))

        // Уведомления — реальные, из /app/notifications
        ReedCard {
            ReedSectionTitle("Уведомления")
            Spacer(Modifier.height(10.dp))
            if (notifications.isEmpty()) {
                MutedText("Новых уведомлений нет.")
            } else {
                notifications.forEachIndexed { idx, n ->
                    if (idx > 0) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant))
                        Spacer(Modifier.height(10.dp))
                    }
                    if (n.title.isNotBlank()) {
                        Text(n.title, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(2.dp))
                    }
                    if (n.body.isNotBlank()) MutedText(n.body)
                    if (n.created_at.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(n.created_at.take(16),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Выйти
        Row(
            Modifier.fillMaxWidth().clip(ReedCardShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ReedCardShape)
                .clickable {
                    ReedSession.token = null
                    token = null
                    data = null
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Выйти из аккаунта", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))

        // Удалить аккаунт — полное удаление данных (требование App Store / Google Play)
        ReedCard {
            ReedSectionTitle("Удалить аккаунт")
            Spacer(Modifier.height(6.dp))
            MutedText("Безвозвратно удаляет ваш аккаунт и все данные: подписку, устройства, реферальный код — из приложения, бота и наших серверов.")
            Spacer(Modifier.height(12.dp))
            if (!confirmDelete) {
                Row(
                    Modifier.fillMaxWidth().clip(ReedCardShape)
                        .border(1.dp, MaterialTheme.colorScheme.error, ReedCardShape)
                        .clickable(enabled = token != null) { confirmDelete = true }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Удалить аккаунт", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("Точно удалить? Это действие необратимо.",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.weight(1f).clip(ReedCardShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ReedCardShape)
                            .clickable(enabled = !deleting) { confirmDelete = false }
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Отмена", fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f).clip(ReedCardShape)
                            .background(MaterialTheme.colorScheme.error)
                            .clickable(enabled = !deleting) {
                                val t = token ?: return@clickable
                                deleting = true
                                scope.launch {
                                    try { ReedApi.deleteAccount(t) } catch (e: Throwable) {}
                                    ReedSession.token = null
                                    ReedSession.onboardingDone = false
                                    token = null
                                    data = null
                                    deleting = false
                                }
                            }
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (deleting) "Удаление…" else "Удалить",
                            fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onError)
                    }
                }
            }
        }

        // Документы — открываются ВНУТРИ приложения модальным окном (требование Apple).
        Spacer(Modifier.height(24.dp))
        ReedSectionTitle("Документы")
        Spacer(Modifier.height(8.dp))
        PolicyLink(ReedPolicyTexts.TERMS_TITLE) {
            policyDialog = ReedPolicyTexts.TERMS_TITLE to ReedPolicyTexts.TERMS_BODY
        }
        PolicyLink(ReedPolicyTexts.PRIVACY_TITLE) {
            policyDialog = ReedPolicyTexts.PRIVACY_TITLE to ReedPolicyTexts.PRIVACY_BODY
        }
        PolicyLink(ReedPolicyTexts.CONSENT_TITLE) {
            policyDialog = ReedPolicyTexts.CONSENT_TITLE to ReedPolicyTexts.CONSENT_BODY
        }

        Spacer(Modifier.height(28.dp))
    }
}

// Строка-ссылка на документ внизу ЛК: название + иконка ℹ️, открывает текст модалкой.
@Composable
private fun PolicyLink(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Rounded.Info, contentDescription = "Открыть",
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}
