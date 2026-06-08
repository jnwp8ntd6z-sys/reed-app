package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.reed.ReedSession
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Path

/**
 * Десктопный VLESS-движок. Поднимает локальный SOCKS5 через бинарник sing-box —
 * тот же движок, что используется в мобильной сборке (Android/iOS). Готовый
 * sing-box-конфиг (с socks-inbound на нужном порту) отдаёт сервер:
 *   GET /app/singbox?token=&socks_port=N&server=&split= (см. sub_server.py).
 * Поверх локального SOCKS дальше наводится тот же tun2socks/hev, что и для olcRTC.
 */
internal object SingBoxDesktopRunner {
    // Тот же базовый адрес Reed API, что и в Android/iOS (REED_API_BASE).
    private const val REED_API_BASE = "https://reed-vpn.duckdns.org"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Команда запуска sing-box: читает JSON-конфиг и держит SOCKS5-inbound. */
    fun command(binary: Path, configPath: Path): List<String> {
        return listOf(binary.toString(), "run", "-c", configPath.toString())
    }

    /**
     * Скачивает sing-box-конфиг для выбранной VLESS-локации. Бросает исключение
     * с понятным текстом, если сервер недоступен или вернул ошибку — чтобы
     * подключение не висело молча на «подключаюсь».
     */
    fun fetchConfig(location: LocationConfig, socksPort: Int): String {
        val config = location.normalized()
        val token = config.key
        val server = URLEncoder.encode(config.id, "UTF-8")
        val split = if (ReedSession.splitRouting) "1" else "0"
        val url = "$REED_API_BASE/app/singbox?token=$token&socks_port=$socksPort&server=$server&split=$split"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("VLESS config request failed with HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                error("VLESS config response was empty")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
