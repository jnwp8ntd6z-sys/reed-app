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
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** Команда запуска sing-box: читает JSON-конфиг и держит SOCKS5-inbound. */
    fun command(binary: Path, configPath: Path): List<String> {
        return listOf(binary.toString(), "run", "-c", configPath.toString())
    }

    /**
     * Возвращает sing-box-конфиг для выбранной VLESS-локации. Бросает исключение
     * с понятным текстом, если конфига нет ни в кэше, ни в сети — чтобы подключение
     * не висело молча на «подключаюсь».
     *
     * CACHE-FIRST (как на мобильных, см. OlcboxVpnService): на «зарезанном» интернете
     * РФ наш API reed-vpn.duckdns.org не в белом списке → недоступен (и висит до
     * таймаута), а сам VPN-сервер (белый SNI) — доступен. Поэтому при наличии кэша
     * подключаемся СРАЗУ по нему, а свежий конфиг тянем в фоне для следующего раза.
     * Сеть блокирующе нужна только при ПЕРВОМ подключении к серверу.
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

        val cached = cacheFile(config.id, socksPort).takeIf { it.exists() }
            ?.readText()?.takeIf { it.isNotBlank() }
        if (cached != null) {
            // Есть кэш — отдаём сразу, свежий конфиг обновляем в фоне (не блокируем запуск).
            Thread {
                httpGet(url)?.let { runCatching { cacheFile(config.id, socksPort).writeText(it) } }
            }.apply { isDaemon = true }.start()
            return cached
        }

        // Кэша нет (первое подключение к серверу) — тянем с сети и сохраняем.
        val fetched = httpGet(url)
        if (fetched != null) {
            runCatching { cacheFile(config.id, socksPort).writeText(fetched) }
            return fetched
        }
        // Временный VPN на свежей установке без доступа к API — берём ВШИТЫЙ конфиг,
        // чтобы поднять туннель и через него докачать реальные ключи (бутстрап).
        if (config.id == "reed-temp") {
            val baked = org.olcbox.app.data.reed.bakedTempSingboxConfig(socksPort)
            runCatching { cacheFile(config.id, socksPort).writeText(baked) }
            return baked
        }
        error("VLESS config unavailable (no network, no cache)")
    }

    /** Уже есть кэш конфига для (сервер, порт)? */
    fun isCached(serverId: String, socksPort: Int): Boolean =
        cacheFile(serverId, socksPort).let { it.exists() && it.length() > 0L }

    /**
     * Предзагрузка конфига сервера в кэш, если его ещё нет (для офлайн-подключения к
     * ещё не использованным серверам). Возвращает true при успехе/наличии кэша.
     */
    fun prewarm(location: LocationConfig, socksPort: Int): Boolean {
        val config = location.normalized()
        if (config.id == "reed-temp") return true
        if (isCached(config.id, socksPort)) return true
        val server = URLEncoder.encode(config.id, "UTF-8")
        val split = if (ReedSession.splitRouting) "1" else "0"
        val url = "$REED_API_BASE/app/singbox?token=${config.key}" +
            "&socks_port=$socksPort&server=$server&split=$split"
        val fetched = httpGet(url) ?: return false
        runCatching { cacheFile(config.id, socksPort).writeText(fetched) }
        return true
    }

    /** Один GET конфига (или null при сбое/не-200). */
    private fun httpGet(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
                .takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Файл кэша sing-box-конфига в каталоге настроек приложения. */
    private fun cacheFile(serverId: String, socksPort: Int): File {
        val dir = File(System.getProperty("user.home"), ".reedvpn/cache").apply { mkdirs() }
        val safe = serverId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return File(dir, "singbox_cache_${safe}_$socksPort.json")
    }
}
