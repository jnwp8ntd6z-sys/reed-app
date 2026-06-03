package org.olcbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.olcbox.app.data.reed.ReedApi
import org.olcbox.app.data.reed.ReedSession
import org.olcbox.app.data.reed.SubscriptionResponse
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.features.locations.PingsState

private const val BOT_URL = "https://t.me/ReedVPNbot"

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

@Composable
private fun ReedToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
        Switch(checked = checked, onCheckedChange = onChange)
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

private fun pingFor(state: PingsState, id: String): Int? = when (state) {
    is PingsState.Success -> state.pings[id]
    is PingsState.Loading -> state.currentPings[id] ?: state.lastPings?.get(id)
    is PingsState.Error -> state.lastPings?.get(id)
    PingsState.Idle -> null
}

// Цвет полоски/пинга по доле использования (зелёный → оранжевый → красный)
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

// Круглая кнопка подключения с кольцом таймера сессии и нарисованным значком питания
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
            .border(if (isConnected) 0.dp else 5.dp, outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(196.dp)) {
            val stroke = 5.dp.toPx()
            val pad = 10.dp.toPx()
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            val topLeft = Offset(pad, pad)
            if (isConnected) {
                drawArc(color = onPrimary.copy(alpha = 0.18f), startAngle = -90f,
                    sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
                drawArc(color = onPrimary, startAngle = -90f, sweepAngle = 360f * ringProgress,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            // значок питания: дуга с разрывом сверху + вертикальная чёрточка
            val r = size.minDimension * 0.20f
            val cx = size.width / 2f
            val cy = size.height / 2f + (if (isConnected) -8.dp.toPx() else 0f)
            val gstroke = 6.dp.toPx()
            drawArc(color = glyphColor, startAngle = -55f, sweepAngle = 290f, useCenter = false,
                topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                style = Stroke(width = gstroke, cap = StrokeCap.Round))
            drawLine(color = glyphColor, start = Offset(cx, cy - r - gstroke * 0.2f),
                end = Offset(cx, cy - r * 0.15f), strokeWidth = gstroke, cap = StrokeCap.Round)
        }
        if (isConnected) {
            Text(formatSession(sessionSeconds), color = onPrimary,
                fontWeight = FontWeight.Black, fontSize = 16.sp,
                modifier = Modifier.padding(top = 56.dp))
        } else if (isLoading) {
            Text("Подключаюсь…", color = primary, fontWeight = FontWeight.Black,
                fontSize = 13.sp, modifier = Modifier.padding(top = 52.dp))
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
    val state by homeViewModel.state.collectAsState()
    val locations = locationViewModel.locations.toList()
    val pingsState = locationViewModel.pingsState
    val selectedId = locationViewModel.selectedLocationId

    var data by remember { mutableStateOf<SubscriptionResponse?>(null) }
    LaunchedEffect(ReedSession.token) {
        val t = ReedSession.token
        if (t != null) data = try { ReedApi.subscription(t) } catch (e: Throwable) { null }
    }

    // Локальный таймер сессии: считаем секунды, пока VPN подключён
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

        if (!hasSubscription) {
            // Нет подписки — оранжевая кнопка покупки (как в дизайне)
            Button(
                onClick = { uri.openUri(BOT_URL) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) { Text("ПОЛУЧИТЬ 3 ДНЯ БЕСПЛАТНО", fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(8.dp))
            Text("от 129 ₽/мес", modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        } else {
            // Карточка трафика + обратный отсчёт
            ReedCard {
                val normalUsed = (data?.traffic?.used ?: 0L) / BYTES_IN_GB
                val normalTotal = (data?.traffic?.total ?: 0L) / BYTES_IN_GB
                ReedTrafficBar("Обычный трафик", normalUsed, normalTotal)
                Spacer(Modifier.height(14.dp))
                ReedTrafficBar("LTE трафик", data?.lte?.used_gb ?: 0.0, data?.lte?.total_gb ?: 40.0)
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

        // Круглая кнопка подключения
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

        // Заголовок СЕРВЕРЫ + Обновить / Тест
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("📍 СЕРВЕРЫ", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text("Тест", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    locationViewModel.refreshPings(
                        targetLocationIds = null,
                        performPing = { config -> homeViewModel.performPingFor(config) },
                    )
                })
        }
        Spacer(Modifier.height(14.dp))

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
                        Text(loc.fullName, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black,
                            color = if (isSel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
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
fun ReedSettingsScreen() {
    val uri = LocalUriHandler.current
    var operator by remember { mutableStateOf("МТС") }
    var splitRouting by remember { mutableStateOf(true) }
    var autostart by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<SubscriptionResponse?>(null) }
    LaunchedEffect(ReedSession.token) {
        val t = ReedSession.token
        if (t != null) data = try { ReedApi.subscription(t) } catch (e: Throwable) { null }
    }
    val operators = data?.operators?.takeIf { it.isNotEmpty() } ?: OPERATORS
    val lteUsed = data?.lte?.used_gb ?: 0.0
    val lteTotal = data?.lte?.total_gb ?: 40.0

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        ScreenTitle("Настройки")
        Spacer(Modifier.height(20.dp))

        ReedCard {
            ReedSectionTitle("Сменить оператора")
            Spacer(Modifier.height(4.dp))
            MutedText("Текущий: $operator")
            Spacer(Modifier.height(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                operators.forEach { op ->
                    if (op == operator) Button(onClick = { operator = op }) { Text(op) }
                    else OutlinedButton(onClick = { operator = op }) { Text(op) }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        ReedCard {
            ReedSectionTitle("Трафик")
            Spacer(Modifier.height(10.dp))
            Text("Обычный трафик: без ограничений", style = MaterialTheme.typography.bodyMedium)
            Text("LTE трафик: $lteUsed из $lteTotal ГБ", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            MutedText("Обновление трафика — 1 числа каждого месяца")
        }
        Spacer(Modifier.height(14.dp))

        ReedCard {
            ReedSectionTitle("Устройства")
            Spacer(Modifier.height(6.dp))
            MutedText("Список подключённых устройств и чёрный список появятся после входа.")
        }
        Spacer(Modifier.height(14.dp))

        ReedCard {
            ReedToggleRow("Split-routing", splitRouting) { splitRouting = it }
            Spacer(Modifier.height(6.dp))
            MutedText("Российские сервисы идут напрямую, мимо VPN.")
        }
        Spacer(Modifier.height(14.dp))

        ReedCard {
            ReedToggleRow("Автозапуск", autostart) { autostart = it }
            Spacer(Modifier.height(6.dp))
            MutedText("Подключаться при старте системы.")
        }
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
                ReedPrimaryButton("Открыть чат в Telegram") { uri.openUri(BOT_URL) }
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

    LaunchedEffect(token) {
        val t = token
        if (t != null) {
            statusMsg = "Загрузка…"
            data = try { ReedApi.subscription(t) } catch (e: Throwable) {
                statusMsg = "Не удалось загрузить данные"; null
            }
            if (data != null) statusMsg = ""
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

        // Профиль
        ReedCard {
            Text(d?.profile?.name?.takeIf { it.isNotBlank() } ?: "Аккаунт",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            val uname = d?.profile?.username?.takeIf { it.isNotBlank() }
            if (uname != null) { Spacer(Modifier.height(2.dp)); MutedText("@$uname") }
        }
        Spacer(Modifier.height(14.dp))

        // Текущая подписка
        ReedCard {
            Text("Текущая подписка", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(sub?.plan_label ?: sub?.plan_id ?: statusMsg.ifEmpty { "—" },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (sub != null) { Spacer(Modifier.height(2.dp)); MutedText("заканчивается через ${sub.days_left} дн.") }
        }
        Spacer(Modifier.height(14.dp))

        // Реферальная программа
        ReedCard {
            ReedSectionTitle("Реферальная программа")
            Spacer(Modifier.height(10.dp))
            MutedText("Ваш реферальный код")
            Text(d?.referral?.code ?: "—", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = friendCode,
                onValueChange = { friendCode = it },
                label = { Text("Реферальный код друга") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
            MutedText("Изменить код нельзя")
            Spacer(Modifier.height(12.dp))
            MutedText("Ваш бонусный баланс")
            Text("${d?.referral?.bonus_balance ?: 0} ₽", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            MutedText("Вывод доступен от ${d?.referral?.withdraw_min ?: 3000} бонусов. Обращаться в поддержку.")
        }
        Spacer(Modifier.height(14.dp))

        // Купить LTE-трафик
        ReedCard {
            ReedSectionTitle("Купить LTE-трафик")
            Spacer(Modifier.height(8.dp))
            MutedText("На вашем тарифе — ${d?.lte?.total_gb ?: 40.0} ГБ LTE в месяц. Потреблено ${d?.lte?.used_gb ?: 0.0} ГБ. Сброс 1 числа.")
            Spacer(Modifier.height(14.dp))
            ReedPrimaryButton("Купить пакет трафика") { uri.openUri(BOT_URL) }
        }
        Spacer(Modifier.height(14.dp))

        // История покупок
        ReedCard {
            ReedSectionTitle("История покупок")
            Spacer(Modifier.height(6.dp))
            MutedText("История платежей доступна в боте.")
        }
        Spacer(Modifier.height(14.dp))

        // Уведомления
        ReedCard {
            ReedSectionTitle("Уведомления")
            Spacer(Modifier.height(6.dp))
            MutedText("Об окончании подписки, новых устройствах и бонусах.")
        }
        Spacer(Modifier.height(14.dp))

        // Выйти
        ReedSecondaryButton("Выйти из аккаунта") {
            ReedSession.token = null
            token = null
            data = null
        }
        Spacer(Modifier.height(28.dp))
    }
}
