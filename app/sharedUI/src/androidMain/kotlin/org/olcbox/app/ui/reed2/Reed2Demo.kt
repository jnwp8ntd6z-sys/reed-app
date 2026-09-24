package org.olcbox.app.ui.reed2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.olcbox.app.data.reed.CodeKind

/**
 * Reed 2.0 — ИНТЕРАКТИВНЫЙ ДЕМО-корень (превью дизайна на телефоне до подключения к данным).
 * Табы переключаются, кнопка подключения проигрывает состояния, ввод кода распознаёт тип —
 * чтобы пощупать вид и анимации вживую. Данные — примерные. Реальное подключение к VPN/
 * ReedApi делается в основном корне (следующий шаг).
 */
@Composable
fun Reed2DemoRoot(modifier: Modifier = Modifier) {
    var screen by remember { mutableStateOf("login") } // login | codes | app
    var tab by remember { mutableStateOf(ReedTab.Home) }
    var consent by remember { mutableStateOf(false) }
    var conn by remember { mutableStateOf(ReedConnState.Off) }
    var code by remember { mutableStateOf("") }
    var selectedServer by remember { mutableStateOf("v0") }
    var network by remember { mutableStateOf("wifi") }

    val servers = listOf(
        ServerUi("v0", "DE", "Германия", "Франкфурт", 42, "wifi"),
        ServerUi("v1", "NL", "Нидерланды", "Амстердам", 48, "wifi"),
        ServerUi("v2", "FI", "Финляндия", "Хельсинки", 51, "wifi"),
        ServerUi("v3", "TR", "Турция", "Стамбул", 63, "wifi"),
        ServerUi("r0", "RTC", "olcRTC", "Через Москву", 118, "cell"),
    )
    val sub = SubscriptionUi(true, "до 15 октября", 12.4, 50.0,
        "Трафик обновится 1 ноября · мобильный без лимита")
    val members = listOf(
        MemberUi(0, "Ты", "Владелец · 2 устройства", true, true),
        MemberUi(1, "Мама", "1 устройство", true),
        MemberUi(2, "Брат", "Был вчера", false),
    )
    val devices = listOf(
        DeviceUi(0, "Этот телефон", "Это устройство", "phone"),
        DeviceUi(1, "Ноутбук", "Сегодня", "laptop"),
    )
    val net = listOf(
        NetCheckRowUi("Wi-Fi", 38, "Работает", "ok"),
        NetCheckRowUi("Мобильный", 64, "Работает", "ok"),
        NetCheckRowUi("Прямое", null, "Ограничено", "warn"),
    )

    Box(modifier.fillMaxSize()) {
        when (screen) {
            "login" -> ReedLoginScreen(
                consent = consent, onConsentChange = { consent = it },
                onOpenCodeEntry = { screen = "codes" }, onScanQr = { screen = "codes" },
                onContinueWithoutCode = { screen = "app" }, showTelegram = true,
            )
            "codes" -> ReedCodeEntryScreen(
                code = code, onCodeChange = { code = it }, detected = CodeKind.detect(code),
                hint = "Код приходит в Telegram-боте. Скан QR или вставка входят сразу.",
                error = null, onSubmit = { screen = "app" }, onScanQr = { screen = "app" },
                onBack = { screen = "login" },
            )
            else -> ReedAppShell(selectedTab = tab, onSelectTab = { tab = it }) {
                when (tab) {
                    ReedTab.Home -> ReedHomeScreen(
                        connState = conn,
                        statusWord = when (conn) {
                            ReedConnState.On -> "Подключено"; ReedConnState.Connecting -> "Подключаюсь…"
                            ReedConnState.Off -> "Не подключено"
                        },
                        timer = if (conn == ReedConnState.On) "01:24:07" else "",
                        locationSubtitle = "Германия · Франкфурт", subscription = sub,
                        servers = servers, selectedKey = selectedServer, network = network,
                        hasUnread = true,
                        onToggleConnect = {
                            conn = when (conn) {
                                ReedConnState.Off -> ReedConnState.Connecting
                                ReedConnState.Connecting -> ReedConnState.On
                                ReedConnState.On -> ReedConnState.Off
                            }
                        },
                        onSelectServer = { selectedServer = it.key },
                        onSelectNetwork = { network = it }, onBell = {}, onPing = {}, onRefresh = {},
                    )
                    ReedTab.Family -> ReedFamilyScreen(
                        placesUsed = 3, placesTotal = 5,
                        alert = NewDeviceAlertUi(9, "Новое устройство", "Android-телефон подключился 5 минут назад"),
                        members = members, devices = devices,
                        inviteCode = "RDI-GHQMCV", deviceCode = "RDX-ZNEFEN", onCopy = {},
                    )
                    ReedTab.Profile -> ReedProfileScreen(
                        username = "@username", subLabel = "Подписка активна до 15 октября",
                        lastCheckLabel = "2 мин назад", netRows = net, autoConnect = true, ruDirect = true,
                        version = "Reed 2.0 · превью", showBotButtons = true,
                        onLogout = { screen = "login" },
                    )
                }
            }
        }
    }
}
