package org.olcbox.app.data.reed

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Клиент к серверному API приложения Reed (reed-vpn.duckdns.org, путь /app).
 * Контракт: reed-app/docs/API.md. Движок Ktor берётся из платформенного
 * (okhttp на Android/JVM, darwin на iOS) — он уже в зависимостях olcbox.
 */
object ReedApi {
    private const val BASE = "https://reed-vpn.duckdns.org"
    // Ключ кэша последнего успешного ответа /app/subscription (персист). Нужен, чтобы
    // без интернета приложение не выглядело как «вылет из аккаунта»: показываем кэш.
    private const val KEY_SUB_CACHE = "reed_sub_cache"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        // БЕЗ таймаута офлайн/при блокировке нашего домена (белые списки) вызовы
        // подписки/входа висели десятками секунд → экран замирал на «Загрузка аккаунта»,
        // нельзя было выбрать сервер и подключиться. Теперь падают быстро → дальше
        // показываем кэш подписки и серверы с диска (офлайн-режим как в HAPP).
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 8_000
            socketTimeoutMillis = 8_000
        }
    }

    suspend fun authStart(): AuthStart =
        client.post("$BASE/app/auth/start").body()

    suspend fun authPoll(nonce: String): AuthPoll =
        client.get("$BASE/app/auth/poll") { parameter("nonce", nonce) }.body()

    suspend fun subscription(token: String): SubscriptionResponse {
        val text = client.get("$BASE/app/subscription") { parameter("token", token) }.bodyAsText()
        val parsed = json.decodeFromString<SubscriptionResponse>(text)
        // Кэшируем только активную подписку — чтобы офлайн показывать рабочий аккаунт,
        // а не последний «закончилась».
        if (parsed.subscription.status == "active") reedStorePut(KEY_SUB_CACHE, text)
        return parsed
    }

    /** Последняя успешно загруженная подписка из кэша (для офлайна). null если кэша нет. */
    fun cachedSubscription(): SubscriptionResponse? {
        val text = reedStoreGet(KEY_SUB_CACHE) ?: return null
        return try { json.decodeFromString<SubscriptionResponse>(text) } catch (e: Throwable) { null }
    }

    // Список всех активных подписок аккаунта (для переключателя «Сменить подписку»).
    suspend fun subscriptions(token: String): SubscriptionsResponse =
        client.get("$BASE/app/subscriptions") { parameter("token", token) }.body()

    // Аватар пользователя (JPEG). Возвращает байты или null, если фото нет / ошибка.
    suspend fun avatarBytes(token: String): ByteArray? = try {
        val resp = client.get("$BASE/app/avatar") { parameter("token", token) }
        if (resp.status.value == 200) resp.body<ByteArray>() else null
    } catch (e: Throwable) {
        null
    }

    // Список устройств подписки (активные + заблокированные, лимит).
    suspend fun devices(token: String): DevicesResponse =
        client.get("$BASE/app/devices") { parameter("token", token) }.body()

    // Действие над устройством: action = "toggle_block" | "delete".
    suspend fun deviceAction(token: String, deviceId: Int, action: String): DeviceActionResult =
        client.post("$BASE/app/device/action") {
            contentType(ContentType.Application.Json)
            setBody(DeviceActionRequest(token, deviceId, action))
        }.body()

    // Сохранить выбранного оператора связи.
    suspend fun setOperator(token: String, operator: String): SetOperatorResult =
        client.post("$BASE/app/operator/set") {
            contentType(ContentType.Application.Json)
            setBody(SetOperatorRequest(token, operator))
        }.body()

    // Уведомления приложения (персональные + общие, новые сверху).
    suspend fun notifications(token: String): NotificationsResponse =
        client.get("$BASE/app/notifications") { parameter("token", token) }.body()

    // Полное удаление аккаунта (из БД бота + VLESS-клиентов в панели).
    suspend fun deleteAccount(token: String): DeleteAccountResult =
        client.post("$BASE/app/account/delete") {
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(token))
        }.body()

    // ── Вход по коду-приглашению (семейный доступ без Telegram) ──────────────

    // Состояние сессии: owner | member | deleted. Для участника — статус и блок.
    suspend fun session(token: String): SessionInfo = try {
        val resp = client.get("$BASE/app/session") { parameter("token", token) }
        if (resp.status.value == 404) SessionInfo(kind = "deleted")
        else resp.body()
    } catch (e: Throwable) {
        SessionInfo(kind = "unknown")
    }

    // Владелец генерирует код-приглашение для своей подписки.
    suspend fun shareCreate(token: String): ShareCreateResult =
        client.post("$BASE/app/share/create") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(token))
        }.body()

    // Вход по коду: возвращает member_token (становится токеном сессии).
    suspend fun shareRedeem(
        code: String, name: String,
        hwid: String = "", deviceModel: String = "", deviceOs: String = "",
    ): RedeemResult {
        val resp = client.post("$BASE/app/share/redeem") {
            contentType(ContentType.Application.Json)
            setBody(RedeemRequest(code, name, hwid, deviceModel, deviceOs))
        }
        return resp.body()
    }

    // Список участников подписки (для владельца).
    suspend fun members(token: String): MembersResponse =
        client.get("$BASE/app/members") { parameter("token", token) }.body()

    // Управление участником: action = "block" | "unblock" | "delete".
    suspend fun memberAction(token: String, memberId: Int, action: String): MemberActionResult =
        client.post("$BASE/app/member/action") {
            contentType(ContentType.Application.Json)
            setBody(MemberActionRequest(token, memberId, action))
        }.body()
}

/**
 * Сессия Reed: токен входа и флаг онбординга. Сохраняются между запусками
 * через платформенное хранилище (см. [reedStoreGet]/[reedStorePut]).
 */
object ReedSession {
    private const val KEY_TOKEN = "reed_token"
    private const val KEY_ONBOARDING = "reed_onboarding_done"

    // Инициализаторы читают сохранённое значение напрямую в backing-поле
    // (минуя сеттер), поэтому при старте лишней записи не происходит.
    var token: String? = reedStoreGet(KEY_TOKEN)
        set(value) {
            field = value
            reedStorePut(KEY_TOKEN, value)
        }

    // Показан ли экран онбординга (первый запуск).
    var onboardingDone: Boolean = reedStoreGet(KEY_ONBOARDING) == "1"
        set(value) {
            field = value
            reedStorePut(KEY_ONBOARDING, if (value) "1" else null)
        }

    // Для какого токена уже импортирована подписка Reed (чтобы не импортировать повторно).
    // Только в памяти — при перезапуске движок всё равно перечитывает локации с диска.
    var importedForToken: String? = null

    // Пользователь нажал «Подключить временный VPN» на экране входа → на главном экране
    // автоматически выбрать и подключить временный сервер.
    var useTempVpnOnEntry: Boolean = false

    // Накопленный трафик временного VPN на устройстве (лимит 5 ГБ). Персист.
    private const val KEY_TEMP_USED = "reed_temp_used_bytes"
    var tempUsedBytes: Long = reedStoreGet(KEY_TEMP_USED)?.toLongOrNull() ?: 0L
        set(value) {
            field = value
            reedStorePut(KEY_TEMP_USED, value.toString())
        }

    // Split-routing: российские сайты идут напрямую мимо VPN. По умолчанию включён.
    // Применяется при подключении (передаётся в /app/singbox как &split=1/0).
    private const val KEY_SPLIT = "reed_split_routing"
    var splitRouting: Boolean = reedStoreGet(KEY_SPLIT) != "0"
        set(value) {
            field = value
            reedStorePut(KEY_SPLIT, if (value) "1" else "0")
        }

    // Принял ли пользователь политику конфиденциальности и условия использования.
    private const val KEY_CONSENT = "reed_consent_accepted"
    var consentAccepted: Boolean = reedStoreGet(KEY_CONSENT) == "1"
        set(value) {
            field = value
            reedStorePut(KEY_CONSENT, if (value) "1" else null)
        }

    // Максимальный id уведомления, которое пользователь уже видел (открывал вкладку
    // «звоночек» на главном). Красный кружок над звоночком показывается, если есть
    // уведомление с id больше этого значения. Персист.
    private const val KEY_NOTIF_SEEN = "reed_notif_seen_max_id"
    var notifSeenMaxId: Int = reedStoreGet(KEY_NOTIF_SEEN)?.toIntOrNull() ?: 0
        set(value) {
            field = value
            reedStorePut(KEY_NOTIF_SEEN, value.toString())
        }

    // Авто-подключение при запуске приложения (фишка Happ). По умолчанию выключено,
    // чтобы не подключать без ведома пользователя. При включении — на главном экране
    // автоматически подключается к выбранному серверу.
    private const val KEY_AUTOCONNECT = "reed_autoconnect"
    var autoConnect: Boolean = reedStoreGet(KEY_AUTOCONNECT) == "1"
        set(value) {
            field = value
            reedStorePut(KEY_AUTOCONNECT, if (value) "1" else null)
        }

    // Вошёл по коду-приглашению (не через Telegram). У таких пользователей нет
    // управления устройствами/приглашениями — только просмотр и подключение.
    private const val KEY_VIA_CODE = "reed_joined_via_code"
    var joinedViaCode: Boolean = reedStoreGet(KEY_VIA_CODE) == "1"
        set(value) {
            field = value
            reedStorePut(KEY_VIA_CODE, if (value) "1" else null)
        }

    // Имя, которое участник ввёл при входе по коду (показываем в кабинете).
    private const val KEY_MEMBER_NAME = "reed_member_name"
    var memberName: String? = reedStoreGet(KEY_MEMBER_NAME)
        set(value) {
            field = value
            reedStorePut(KEY_MEMBER_NAME, value)
        }
}

object ReedLinks {
    const val PRIVACY_POLICY = "https://reedvpn.tilda.ws/privacy-policy"
    const val TERMS_OF_SERVICE = "https://reedvpn.tilda.ws/terms-of-service"
}

@Serializable
data class AuthStart(val nonce: String, val deeplink: String)

@Serializable
data class AuthPoll(
    val status: String,
    val token: String? = null,
    val has_subscription: Boolean? = null,
)

@Serializable
data class Profile(
    val name: String = "",
    val username: String = "",
)

@Serializable
data class SubscriptionResponse(
    val profile: Profile? = null,
    val subscription: SubInfo,
    val traffic: Traffic,
    val lte: Lte,
    val referral: Referral,
    val servers: List<ServerInfo> = emptyList(),
    val operators: List<String> = emptyList(),
    val current_operator: String? = null,
    val bot_url: String? = null,
)

@Serializable
data class SubInfo(
    val status: String,
    val plan_id: String? = null,
    val plan_type: String? = null,
    val plan_label: String? = null,
    val expires_at: String? = null,
    val seconds_left: Long = 0,
    val days_left: Long = 0,
)

@Serializable
data class SubscriptionsResponse(
    val subscriptions: List<SubscriptionItem> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class SubscriptionItem(
    val sub_token: String,
    val plan_id: String? = null,
    val plan_type: String? = null,
    val plan_label: String? = null,
    val expires_at: String? = null,
    val seconds_left: Long = 0,
    val days_left: Long = 0,
    val used: Long = 0,
    val total: Long = 0,
    val current: Boolean = false,
)

@Serializable
data class Traffic(
    val up: Long = 0,
    val down: Long = 0,
    val used: Long = 0,
    val total: Long = 0,
)

@Serializable
data class Lte(
    val used_gb: Double = 0.0,
    val total_gb: Double = 0.0,
)

@Serializable
data class Referral(
    val code: String? = null,
    val bonus_balance: Long = 0,
    val percent: Int = 30,
    val withdraw_min: Int = 3000,
)

@Serializable
data class DeviceInfo(
    val id: Int,
    val name: String = "Устройство",
    val os: String = "",
    val type: String = "",
    val last_seen: String = "",
    val blocked: Boolean = false,
)

@Serializable
data class DevicesResponse(
    val devices: List<DeviceInfo> = emptyList(),
    val blocked: List<DeviceInfo> = emptyList(),
    val used: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class DeviceActionRequest(
    val token: String,
    val device_id: Int,
    val action: String,
)

@Serializable
data class DeviceActionResult(
    val ok: Boolean = false,
    val action: String = "",
    val blocked: Boolean? = null,
    val error: String? = null,
)

@Serializable
data class SetOperatorRequest(
    val token: String,
    val operator: String,
)

@Serializable
data class SetOperatorResult(
    val ok: Boolean = false,
    val operator: String? = null,
    val error: String? = null,
)

@Serializable
data class DeleteAccountRequest(val token: String)

@Serializable
data class DeleteAccountResult(
    val ok: Boolean = false,
    val error: String? = null,
)

@Serializable
data class AppNotification(
    val id: Int,
    val title: String = "",
    val body: String = "",
    val created_at: String = "",
)

@Serializable
data class NotificationsResponse(
    val notifications: List<AppNotification> = emptyList(),
)

@Serializable
data class ServerInfo(
    val id: Int,
    val name: String,
    val desc: String = "",
    val type: String = "SMART",
    val lte: Boolean = false,
    val vless: String? = null,
)

// ── Вход по коду-приглашению ────────────────────────────────────────────────

@Serializable
data class TokenRequest(val token: String)

@Serializable
data class SessionInfo(
    val kind: String = "owner",            // owner | member | deleted | unknown
    val name: String = "",
    val member_status: String = "active",  // active | blocked
    val can_manage: Boolean = true,
    val blocked: Boolean = false,
)

@Serializable
data class ShareCreateResult(
    val ok: Boolean = false,
    val code: String = "",
    val expires_at: String = "",
    val ttl_seconds: Int = 0,
    val error: String? = null,
)

@Serializable
data class RedeemRequest(
    val code: String,
    val name: String,
    val hwid: String = "",
    val device_model: String = "",
    val device_os: String = "",
)

@Serializable
data class RedeemResult(
    val ok: Boolean = false,
    val member_token: String? = null,
    val name: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class MemberItem(
    val id: Int,
    val name: String = "",
    val device: String = "",
    val os: String = "",
    val joined_at: String = "",
    val blocked: Boolean = false,
)

@Serializable
data class MembersResponse(
    val members: List<MemberItem> = emptyList(),
    val count: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class MemberActionRequest(
    val token: String,
    val member_id: Int,
    val action: String,
)

@Serializable
data class MemberActionResult(
    val ok: Boolean = false,
    val action: String = "",
    val error: String? = null,
)
