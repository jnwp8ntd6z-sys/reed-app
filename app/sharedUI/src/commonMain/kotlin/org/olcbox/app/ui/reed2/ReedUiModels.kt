package org.olcbox.app.ui.reed2

/** Простые UI-модели для stateless-экранов Reed 2.0 (наполняются адаптером из плумбинга). */

data class ServerUi(
    val key: String,
    val country: String,     // ISO-2 (для флага) или "RTC"
    val countryName: String, // «Германия»
    val city: String,        // «Франкфурт» / «Через Москву» / «olcRTC»
    val pingMs: Int? = null, // null → «—»
    val network: String = "wifi", // wifi | cell
)

data class SubscriptionUi(
    val active: Boolean,
    val untilLabel: String,      // «до 15 октября»
    val usedGb: Double,
    val totalGb: Double,
    val footnote: String,        // «Трафик обновится 1 ноября · мобильный без лимита»
) {
    val progress: Float get() = if (totalGb <= 0) 0f else (usedGb / totalGb).toFloat().coerceIn(0f, 1f)
    val usageLabel: String get() = "${fmt(usedGb)} / ${fmt(totalGb)} ГБ"
    private fun fmt(v: Double): String {
        val r = (v * 10).toLong() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().replace('.', ',')
    }
}

data class MemberUi(
    val id: Int,
    val name: String,
    val subtitle: String,   // «Владелец · 2 устройства» / «1 устройство»
    val online: Boolean,
    val isOwnerRow: Boolean = false,
)

data class DeviceUi(
    val id: Int,
    val name: String,
    val subtitle: String,   // «Это устройство» / «Сегодня» / «Новое»
    val kind: String = "phone", // phone | laptop | tablet
)

data class NetCheckRowUi(
    val label: String,      // Wi-Fi / Мобильный / Прямое
    val pingMs: Int?,
    val statusWord: String, // «Работает» / «Ограничено» / «Не отвечает»
    val statusKind: String, // ok | warn | danger
)

data class NewDeviceAlertUi(
    val deviceId: Int,
    val title: String,      // «Новое устройство»
    val subtitle: String,   // «Android-телефон подключился 5 минут назад»
)
