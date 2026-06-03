package org.olcbox.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.olcbox.app.data.reed.ReedApi
import org.olcbox.app.data.reed.ReedSession
import org.olcbox.app.data.reed.SubscriptionResponse

private const val BOT_URL = "https://t.me/ReedVPNbot"

private val OPERATORS = listOf("МТС", "Мегафон", "Yota", "Билайн", "Т2", "Т-Мобайл", "Ростелеком")

// ── Управление ───────────────────────────────────────────────────────────────
@Composable
fun ReedControlScreen() {
    val uri = LocalUriHandler.current
    var operator by remember { mutableStateOf("МТС") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Управление", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // Оператор связи (для LTE / обхода белых списков)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Оператор связи (для LTE)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("Текущий: $operator", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    OPERATORS.forEach { op ->
                        if (op == operator) {
                            Button(onClick = { operator = op }) { Text(op) }
                        } else {
                            OutlinedButton(onClick = { operator = op }) { Text(op) }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Трафик
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Трафик", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text("Обычный: — без ограничений", style = MaterialTheme.typography.bodyMedium)
                Text("LTE: — из 40 ГБ", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { uri.openUri(BOT_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Купить LTE-трафик")
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Устройства
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Устройства", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Подключённые устройства и чёрный список — появятся после входа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Поддержка ────────────────────────────────────────────────────────────────
@Composable
fun ReedSupportScreen() {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("💬", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("Поддержка", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Напишите нам в Telegram — поможем быстро.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { uri.openUri(BOT_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Открыть чат в Telegram")
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
            data = try {
                ReedApi.subscription(t)
            } catch (e: Throwable) {
                statusMsg = "Не удалось загрузить данные"
                null
            }
            if (data != null) statusMsg = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Личный кабинет", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (token == null) {
            // ── Не вошёл: кнопка входа через Telegram ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Войдите через Telegram, чтобы увидеть подписку, трафик и реферальный код.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
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
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Зарегистрироваться через Telegram")
                    }
                    if (statusMsg.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(statusMsg, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        val d = data
        val sub = d?.subscription

        // Подписка
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Текущая подписка", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    sub?.let { "${it.plan_id ?: it.status} · осталось ${it.days_left} дн." }
                        ?: statusMsg.ifEmpty { "—" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { uri.openUri(BOT_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Продлить подписку")
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Трафик
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Трафик", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("LTE: ${d?.lte?.used_gb ?: 0.0} из ${d?.lte?.total_gb ?: 40.0} ГБ",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Реферальная программа
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Реферальная программа", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("Ваш код: ${d?.referral?.code ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Бонусы: ${d?.referral?.bonus_balance ?: 0} ₽ · ${d?.referral?.percent ?: 30}% с оплат друзей",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = friendCode,
                    onValueChange = { friendCode = it },
                    label = { Text("Код друга") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Вывод доступен от ${d?.referral?.withdraw_min ?: 3000} бонусов — через поддержку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Действия (в бота)
        OutlinedButton(onClick = { uri.openUri(BOT_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Купить LTE-трафик")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { uri.openUri(BOT_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Подарить подписку")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { uri.openUri(BOT_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Улучшить до семейной")
        }
        Spacer(Modifier.height(24.dp))
    }
}
