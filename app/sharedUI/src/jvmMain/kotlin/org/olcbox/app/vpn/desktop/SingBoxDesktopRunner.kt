package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.reed.ReedSession
import java.io.File
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
        // Временный VPN (id=reed-temp) — БЕЗ токена с /app/temp (работает до входа).
        val url = if (config.id == "reed-temp") {
            "$REED_API_BASE/app/temp?socks_port=$socksPort"
        } else {
            "$REED_API_BASE/app/singbox?token=$token&socks_port=$socksPort&server=$server&split=$split"
        }

        val fetched = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("VLESS config request failed with HTTP $code")
                connection.inputStream.bufferedReader().use { it.readText() }
                    .takeIf { it.isNotBlank() }
                    ?: error("VLESS config response was empty")
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        if (fetched != null) {
            // Кэшируем последний рабочий конфиг — подключение без интернета (как HAPP).
            runCatching { cacheFile(config.id, socksPort).writeText(fetched) }
            return fetched
        }
        // API недоступен (нет сети / белые списки) — берём последний рабочий конфиг из кэша.
        val cached = cacheFile(config.id, socksPort).takeIf { it.exists() }
            ?.readText()?.takeIf { it.isNotBlank() }
        return cached ?: error("VLESS config unavailable (no network, no cache)")
    }

    /** Файл кэша sing-box-конфига в каталоге настроек приложения. */
    private fun cacheFile(serverId: String, socksPort: Int): File {
        val dir = File(System.getProperty("user.home"), ".reedvpn/cache").apply { mkdirs() }
        val safe = serverId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return File(dir, "singbox_cache_${safe}_$socksPort.json")
    }
}
