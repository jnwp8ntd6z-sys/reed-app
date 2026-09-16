package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.reed.ReedFront
import org.olcbox.app.data.reed.ReedSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Path
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

/**
 * Десктопный VLESS-движок. Поднимает локальный SOCKS5 через бинарник sing-box —
 * тот же движок, что используется в мобильной сборке (Android/iOS). Готовый
 * sing-box-конфиг (с socks-inbound на нужном порту) отдаёт сервер:
 *   GET /app/singbox?token=&socks_port=N&server=&split= (см. sub_server.py).
 * Поверх локального SOCKS дальше наводится тот же tun2socks/hev, что и для olcRTC.
 */
internal object SingBoxDesktopRunner {
    // Тот же базовый адрес Reed API, что и в Android/iOS (REED_API_BASE).
    private const val REED_API_BASE = "https://reedapp.ru"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    /** Тег DNS-сервера РФ-резолвера в конфиге сервера (build_singbox_config). */
    private const val LOCAL_DNS_TAG = "local"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
    fun fetchConfig(location: LocationConfig, socksPort: Int, bindInterface: String? = null): String {
        val config = location.normalized()
        val token = config.key
        // «Любой» (чужой) ключ: key — это сам URI. Конфиг sing-box собираем локально.
        if (token.contains("://")) {
            val parsed = org.olcbox.app.data.datasource.ProxyKeyImport.parseUri(token)
                ?: error("Imported key not recognized")
            return bindDirectToInterface(
                org.olcbox.app.data.datasource.ProxyKeyImport
                    .buildSingboxConfig(parsed.outbound, socksPort),
                bindInterface,
            )
        }
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
            return bindDirectToInterface(cached, bindInterface)
        }

        // Кэша нет (первое подключение к серверу) — тянем с сети и сохраняем.
        val fetched = httpGet(url)
        if (fetched != null) {
            // В кэш кладём ИСХОДНЫЙ конфиг сервера: привязка к адаптеру зависит от машины
            // и сети, её накладываем при каждом запуске заново.
            runCatching { cacheFile(config.id, socksPort).writeText(fetched) }
            return bindDirectToInterface(fetched, bindInterface)
        }
        // Временный VPN на свежей установке без доступа к API — берём ВШИТЫЙ конфиг,
        // чтобы поднять туннель и через него докачать реальные ключи (бутстрап).
        if (config.id == "reed-temp") {
            val baked = org.olcbox.app.data.reed.bakedTempSingboxConfig(socksPort)
            runCatching { cacheFile(config.id, socksPort).writeText(baked) }
            return bindDirectToInterface(baked, bindInterface)
        }
        error("VLESS config unavailable (no network, no cache)")
    }

    /**
     * Windows-TUN: привязываем direct-трафик к физическому адаптеру (bind_interface,
     * под капотом IP_UNICAST_IF), а РФ-резолверу даём detour=direct, чтобы он унаследовал
     * ту же привязку.
     *
     * Зачем. tun2socks вешает на TUN маршруты 0.0.0.0/1 и 128.0.0.0/1 с метрикой 1. По
     * длине префикса они выигрывают у 0.0.0.0/0 физической карты, поэтому пакет, который
     * sing-box отправляет в direct (РФ-сайты при включённом сплите и DoH-запрос к
     * российскому резолверу), снова попадает в TUN, оттуда в наш же SOCKS и снова в
     * direct. Петля: РФ-сайты не открываются вообще, а не просто «видят наш IP».
     * Само соединение с VPN-сервером от петли спасал только /32-маршрут в обход TUN
     * (см. WindowsTunController), на direct его не натянешь — подсетей РФ тысячи.
     *
     * Флаг auto_detect_interface для этого НЕ годится: на Windows он выбирает «дефолтный»
     * интерфейс той же таблицей маршрутов и берёт TUN, из-за чего в туннель уходит всё,
     * включая соединение с сервером, и подключение ломается целиком (проверено 16.09.2026).
     *
     * Если имя адаптера не определилось, конфиг возвращаем как есть: лучше рабочий VPN
     * без РФ-сплита, чем упавший старт.
     */
    internal fun bindDirectToInterface(configJson: String, bindInterface: String?): String {
        if (bindInterface.isNullOrBlank()) return configJson
        return runCatching {
            val root = json.parseToJsonElement(configJson).jsonObject
            val outbounds = root["outbounds"]?.jsonArray ?: return configJson
            val patchedOutbounds = JsonArray(
                outbounds.map { element ->
                    val outbound = element.jsonObject
                    if (outbound["type"]?.jsonPrimitive?.content == "direct") {
                        JsonObject(outbound + ("bind_interface" to JsonPrimitive(bindInterface)))
                    } else {
                        element
                    }
                }
            )
            val patched = root.toMutableMap()
            patched["outbounds"] = patchedOutbounds
            val dns = root["dns"] as? JsonObject
            val dnsServers = dns?.get("servers")?.jsonArray
            if (dns != null && dnsServers != null) {
                val patchedServers = JsonArray(
                    dnsServers.map { element ->
                        val server = element.jsonObject
                        val isLocal = server["tag"]?.jsonPrimitive?.content == LOCAL_DNS_TAG
                        if (isLocal && server["detour"] == null) {
                            JsonObject(server + ("detour" to JsonPrimitive("direct")))
                        } else {
                            element
                        }
                    }
                )
                patched["dns"] = JsonObject(dns + ("servers" to patchedServers))
            }
            json.encodeToString(JsonObject.serializer(), JsonObject(patched))
        }.getOrElse { configJson }
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
        // Локальный («чужой») ключ — предзагружать нечего, конфиг строится на устройстве.
        if (config.key.contains("://")) return true
        if (isCached(config.id, socksPort)) return true
        val server = URLEncoder.encode(config.id, "UTF-8")
        val split = if (ReedSession.splitRouting) "1" else "0"
        val url = "$REED_API_BASE/app/singbox?token=${config.key}" +
            "&socks_port=$socksPort&server=$server&split=$split"
        val fetched = httpGet(url) ?: return false
        runCatching { cacheFile(config.id, socksPort).writeText(fetched) }
        return true
    }

    /**
     * Один GET конфига (или null при сбое/не-200). Если reedapp.ru не ответил, повторяем
     * запрос на IP московского фронта: у части провайдеров РФ в DNS-кэше висит старый
     * зарубежный адрес, а ТСПУ режет к нему TLS. На iOS такой фолбэк уже есть (ReedFront),
     * десктоп до сих пор ходил только по имени и на этом оставался без конфига вообще.
     */
    private fun httpGet(url: String): String? =
        httpGetOnce(url, viaFront = false) ?: httpGetOnce(url, viaFront = true)

    private fun httpGetOnce(url: String, viaFront: Boolean): String? = runCatching {
        val target = if (viaFront) {
            if (!url.startsWith("https://${ReedFront.API_HOST}")) return@runCatching null
            url.replaceFirst(ReedFront.API_HOST, ReedFront.FRONT_IP)
        } else {
            url
        }
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        // На IP фронта имя в URL не совпадает с сертификатом, поэтому проверяем его сами:
        // цепочку по-прежнему валидирует системный доверенный список, а имя сверяем с
        // reedapp.ru — то же, что делает iOS (SecPolicyCreateSSL на имя API).
        if (viaFront && connection is HttpsURLConnection) {
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                host == ReedFront.FRONT_IP && certificateMatchesApiHost(session)
            }
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
                .takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Сертификат фронта выписан на reedapp.ru? Смотрим CN и SAN конечного сертификата. */
    private fun certificateMatchesApiHost(session: SSLSession): Boolean = runCatching {
        val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            ?: return@runCatching false
        val names = (certificate.subjectAlternativeNames ?: emptyList<List<*>>())
            .mapNotNull { entry -> (entry.getOrNull(1) as? String)?.lowercase() }
        names.contains(ReedFront.API_HOST) ||
            certificate.subjectX500Principal.name.lowercase().contains("cn=${ReedFront.API_HOST}")
    }.getOrDefault(false)

    /** Файл кэша sing-box-конфига в каталоге настроек приложения. */
    private fun cacheFile(serverId: String, socksPort: Int): File {
        val dir = File(System.getProperty("user.home"), ".reedvpn/cache").apply { mkdirs() }
        val safe = serverId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return File(dir, "singbox_cache_${safe}_$socksPort.json")
    }
}
