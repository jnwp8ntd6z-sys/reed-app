package org.olcbox.app.vpn.service

import android.content.Context
import java.io.File

/**
 * Кэш sing-box-конфигов (VLESS) на диске устройства — единый источник имён файлов для
 * сервиса VPN (читает при подключении) и для предзагрузки в AndroidVpnManager.
 *
 * Зачем: на «зарезанном» мобильном интернете РФ наш API reed-vpn.duckdns.org не в белом
 * списке → недоступен, а сам VPN-сервер (белый SNI) доступен. Поэтому, как Happ, держим
 * конфиг локально и подключаемся по кэшу без обращения к API. Имя файла привязано к
 * (серверу, socks-порту), т.к. порт зашит в сам конфиг.
 *
 * ВАЖНО: формат имени файла НЕ менять — иначе ранее сохранённые кэши «потеряются» и
 * офлайн-подключение к уже использованным серверам перестанет работать.
 */
object SingboxConfigCache {
    private fun fileFor(context: Context, serverId: String, socksPort: Int): File {
        val safe = serverId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return File(context.filesDir, "singbox_cache_${safe}_$socksPort.json")
    }

    fun read(context: Context, serverId: String, socksPort: Int): String? =
        fileFor(context, serverId, socksPort).takeIf { it.exists() }
            ?.readText()?.takeIf { it.isNotBlank() }

    fun write(context: Context, serverId: String, socksPort: Int, json: String) {
        fileFor(context, serverId, socksPort).writeText(json)
    }

    fun exists(context: Context, serverId: String, socksPort: Int): Boolean =
        fileFor(context, serverId, socksPort).let { it.exists() && it.length() > 0L }
}
