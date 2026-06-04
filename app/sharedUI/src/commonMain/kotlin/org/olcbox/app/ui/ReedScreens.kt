package org.olcbox.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.rounded.CardGiftcard
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import org.olcbox.app.data.reed.DeviceInfo
import org.olcbox.app.data.reed.DevicesResponse
import org.olcbox.app.data.reed.ReedApi
import org.olcbox.app.data.reed.ReedLinks
import org.olcbox.app.data.reed.ReedSession
import org.olcbox.app.data.reed.SubscriptionResponse
import org.olcbox.app.data.reed.decodeImageBitmap
import org.olcbox.app.ui.features.home.HomeScreenViewModel
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
fun ReedOnboardingScreen(onDone: () -> Unit) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var statusMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        // Логотип-герой: значок щита в круге + название
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("REED VPN", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black)
                MutedText("Свободный интернет без границ")
            }
        }
        Spacer(Modifier.height(32.dp))

        OnboardFeature(Icons.Rounded.Shield, "Обходит блокировки",
            "Работает там, где другие VPN не справляются")
        OnboardFeature(Icons.Rounded.Bolt, "Быстро и без рекламы",
            "Свои серверы, высокая скорость, никакой рекламы")
        OnboardFeature(Icons.Rounded.Public, "Серверы по всему миру",
            "Нидерланды, Германия, Финляндия, Польша и другие")

        Spacer(Modifier.height(20.dp))

        ReedPrimaryButton(if (busy) "Подтвердите в Telegram…" else "Войти через Telegram") {
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
        Spacer(Modifier.height(12.dp))
        ReedSecondaryButton("Пропустить — у меня нет аккаунта") {
            ReedSession.onboardingDone = true
            onDone()
        }
        if (statusMsg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            MutedText(statusMsg)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Вход нужен, чтобы подтянуть вашу подписку и серверы. " +
                "Без входа можно осмотреться, но подключение будет недоступно.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
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

// Круглая кнопка подключения: кольцо таймера сессии + значок питания + время сессии.
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
    val glyphColor = if (isConnected) onPrimary else primary
    val ringProgress = (sessionSeconds % 60L).toFloat() / 60f

    Box(
        modifier = Modifier
            .size(196.dp)
            .clip(CircleShape)
            .background(if (isConnected) primary else MaterialTheme.colorScheme.surfaceContainer)
            .border(if (isConnected) 0.dp else 6.dp, outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isConnected) {
            Canvas(Modifier.size(196.dp)) {
                val stroke = 5.dp.toPx()
                val pad = 10.dp.toPx()
                val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
                val topLeft = Offset(pad, pad)
                drawArc(color = onPrimary.copy(alpha = 0.18f), startAngle = -90f,
                    sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
                drawArc(color = onPrimary, startAngle = -90f, sweepAngle = 360f * ringProgress,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }
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
    var consent by remember { mutableStateOf(ReedSession.consentAccepted) }

    var data by remember { mutableStateOf<SubscriptionResponse?>(null) }
    suspend fun reloadSubscription() {
        val t = ReedSession.token ?: return
        data = try { ReedApi.subscription(t) } catch (e: Throwable) { data }
    }
    LaunchedEffect(ReedSession.token) { reloadSubscription() }

    // Авто-импорт серверов Reed после входа: один раз на токен, если серверов ещё нет.
    // Тянем olcRTC-конфиги (родной транспорт движка) — кнопкой можно подключиться.
    LaunchedEffect(ReedSession.token, locations.size) {
        val t = ReedSession.token
        if (t != null && locations.isEmpty() && ReedSession.importedForToken != t) {
            ReedSession.importedForToken = t
            // olcRTC-локации (родной транспорт). После — VLESS-локации из /app/locations.
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

        // Политики и согласие пользователя (ссылки ведут на документы из бота).
        if (!consent) {
            ReedCard {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = consent,
                        onCheckedChange = { consent = it; ReedSession.consentAccepted = it },
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("Я принимаю условия:", fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Политика конфиденциальности",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { uri.openUri(ReedLinks.PRIVACY_POLICY) })
                        Spacer(Modifier.height(4.dp))
                        Text("Условия использования",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { uri.openUri(ReedLinks.TERMS_OF_SERVICE) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Политика конфиденциальности",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { uri.openUri(ReedLinks.PRIVACY_POLICY) })
                Spacer(Modifier.width(12.dp))
                Text("Условия использования",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { uri.openUri(ReedLinks.TERMS_OF_SERVICE) })
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!hasSubscription) {
            // Нейтральный блок без призыва к оплате (требование Apple App Store):
            // всё управление подпиской — в Telegram-боте.
            ReedCard {
                Text("Подписка не активна", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("Полное управление подпиской осуществляется в Telegram-Боте.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                ReedPrimaryButton("Telegram бот") { uri.openUri(BOT_URL) }
            }
            Spacer(Modifier.height(20.dp))
        } else {
            ReedCard {
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
                    Text("ОКОНЧАНИЕ ПОДПИСКИ", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                scope.launch {
                    reloadSubscription()
                    homeViewModel.refreshSubscriptions { locationViewModel.loadLocations { } }
                }
            }
            Spacer(Modifier.width(6.dp))
            IconAction(Icons.Rounded.Bolt, "Тест") {
                locationViewModel.refreshPings(
                    targetLocationIds = null,
                    performPing = { config -> homeViewModel.performPingFor(config) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // Описания серверов из /app/subscription (имя → описание): "Быстрый. Без рекламы." и т.п.
        val serverDescByName: Map<String, String> =
            data?.servers?.associate { it.name to it.desc }.orEmpty()

        if (locations.isEmpty()) {
            ReedCard {
                MutedText("Серверы появятся автоматически после входа и оформления подписки.")
            }
        } else {
            locations.forEach { loc ->
                val isSel = loc.storageId == selectedId
                val ping = pingFor(pingsState, loc.storageId)
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                        .border(
                            if (isSel) 2.dp else 1.dp,
                            if (isSel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            locationViewModel.selectLocation(loc.storageId) {
                                homeViewModel.loadCurrentConfig()
                                homeViewModel.restartVpnIfRunning()
                            }
                        }
                        .padding(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(loc.fullName,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black,
                                color = if (isSel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                            val desc = serverDescByName[loc.fullName]
                            if (!desc.isNullOrBlank()) {
                                Text(desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(if (ping != null) "$ping мс" else "—",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.isVpnConnected && isSel) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(10.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
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
            MutedText("Делитесь кодом: получаете ${d?.referral?.percent ?: 30}% от оплат друзей.")
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
            Text("${d?.referral?.bonus_balance ?: 0} ₽", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            MutedText("Вывод доступен от ${d?.referral?.withdraw_min ?: 3000} бонусов. Обращаться в поддержку.")
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
        Spacer(Modifier.height(28.dp))
    }
}
