package org.olcbox.app.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.olcbox.app.data.reed.ReedApi
import org.olcbox.app.data.reed.ReedSession
import org.olcbox.app.data.reed.SubscriptionResponse

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
