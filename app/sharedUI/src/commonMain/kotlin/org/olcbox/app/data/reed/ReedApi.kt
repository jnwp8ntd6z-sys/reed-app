package org.olcbox.app.data.reed

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клиент к серверному API приложения Reed (reed-vpn.duckdns.org/app/*).
 * Контракт: reed-app/docs/API.md. Движок Ktor берётся из платформенного
 * (okhttp на Android/JVM, darwin на iOS) — он уже в зависимостях olcbox.
 */
object ReedApi {
    private const val BASE = "https://reed-vpn.duckdns.org"

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    suspend fun authStart(): AuthStart =
        client.post("$BASE/app/auth/start").body()

    suspend fun authPoll(nonce: String): AuthPoll =
        client.get("$BASE/app/auth/poll") { parameter("nonce", nonce) }.body()

    suspend fun subscription(token: String): SubscriptionResponse =
        client.get("$BASE/app/subscription") { parameter("token", token) }.body()
}

/** Простое хранилище токена в памяти (на старте — без персистентности). */
object ReedSession {
    var token: String? = null
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
data class SubscriptionResponse(
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
    val expires_at: String? = null,
    val seconds_left: Long = 0,
    val days_left: Long = 0,
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
data class ServerInfo(
    val id: Int,
    val name: String,
    val desc: String = "",
    val type: String = "SMART",
    val lte: Boolean = false,
    val vless: String? = null,
)
