package org.olcbox.app.data.datasource

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Разбор «любых» ключей подписки (как в Happ): vless://, vmess://, trojan://, ss://
 * и подписок (URL вернул base64-список этих ссылок). Превращает ключ в outbound
 * sing-box (схема 1.13) и собирает полный конфиг для запуска ядра ЛОКАЛЬНО на устройстве —
 * без нашего сервера (для чужих провайдеров и для работы на заблокированных сетях РФ).
 *
 * Для НАШИХ ключей (…/sub/<token>) используется отдельный путь (токен → /app/singbox),
 * там сервер сам собирает конфиг с умной маршрутизацией. Здесь — простой «всё через прокси».
 */
object ProxyKeyImport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Результат разбора одной ссылки: имя для списка, адрес для пинга, готовый outbound,
     *  и исходный URI (его храним в LocationConfig.key, чтобы пересобрать конфиг при коннекте). */
    data class ParsedProxy(
        val name: String,
        val host: String,
        val port: Int,
        val outbound: JsonObject,
        val uri: String = "",
    )

    /** Возвращает true, если текст похож на ключ/подписку, которую мы умеем разобрать. */
    fun looksLikeProxyKeys(text: String): Boolean {
        val t = text.trim()
        if (PROXY_SCHEMES.any { t.contains(it) }) return true
        // Возможно, это base64-подписка (одной строкой) — попробуем декодировать.
        val decoded = tryDecodeBase64ToText(t)
        return decoded != null && PROXY_SCHEMES.any { decoded.contains(it) }
    }

    /**
     * Разбирает содержимое подписки/ключа в список прокси. Принимает: одну ссылку, несколько
     * ссылок построчно, либо base64-список (как отдаёт большинство панелей).
     * unsupportedTransports (если передан) собирает транспорты ключей, которые пришлось
     * пропустить (xhttp, quic, kcp, …) — движок их не умеет, а молча импортировать как tcp
     * значит получить «сервер есть, но не заведётся».
     */
    fun parseSubscription(
        content: String,
        unsupportedTransports: MutableSet<String>? = null
    ): List<ParsedProxy> {
        val raw = content.trim()
        if (raw.isEmpty()) return emptyList()
        // Если в тексте уже есть схемы — берём как есть; иначе пробуем base64-декод.
        val body = if (PROXY_SCHEMES.any { raw.contains(it) }) raw
        else tryDecodeBase64ToText(raw) ?: raw
        val result = ArrayList<ParsedProxy>()
        for (lineRaw in body.split('\n', '\r')) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            parseUri(line, unsupportedTransports)?.let { result.add(it) }
        }
        return result
    }

    /** Разбор одной ссылки в outbound sing-box. null — если формат не распознан. */
    fun parseUri(
        uriRaw: String,
        unsupportedTransports: MutableSet<String>? = null
    ): ParsedProxy? {
        val uri = uriRaw.trim()
        return runCatching {
            when {
                uri.startsWith("vless://", true) -> parseVless(uri, unsupportedTransports)
                uri.startsWith("trojan://", true) -> parseTrojan(uri, unsupportedTransports)
                uri.startsWith("vmess://", true) -> parseVmess(uri, unsupportedTransports)
                uri.startsWith("ss://", true) -> parseShadowsocks(uri)
                else -> null
            }
        }.getOrNull()?.copy(uri = uri)
    }

    /** Собирает полный конфиг sing-box (1.13) с одним outbound «proxy» и socks-входом. */
    fun buildSingboxConfig(outbound: JsonObject, socksPort: Int): String {
        // Гарантируем tag = "proxy" у пользовательского outbound.
        val proxy = JsonObject(outbound.toMutableMap().apply { put("tag", JsonPrimitive("proxy")) })
        val config = buildJsonObject {
            putJsonObject("log") { put("level", "warn"); put("timestamp", true) }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("tag", "remote"); put("type", "udp"); put("server", "1.1.1.1"); put("detour", "proxy")
                    })
                    add(buildJsonObject {
                        put("tag", "local"); put("type", "udp"); put("server", "8.8.8.8")
                    })
                }
                put("final", "remote")
                put("strategy", "ipv4_only")
            }
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "socks"); put("tag", "socks-in")
                    put("listen", "127.0.0.1"); put("listen_port", socksPort)
                })
            }
            putJsonArray("outbounds") {
                add(proxy)
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject { put("action", "sniff") })
                    add(buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") })
                    // QUIC (UDP/443) через TCP-туннель тормозит/висит — глушим, как и на сервере.
                    add(buildJsonObject {
                        put("network", "udp"); putJsonArray("port") { add(443) }; put("action", "reject")
                    })
                }
                put("final", "proxy")
                put("auto_detect_interface", false)
                putJsonObject("default_domain_resolver") { put("server", "local") }
            }
        }
        // JsonElement.toString() выдаёт валидный JSON — без возни с reified-сериализаторами.
        return config.toString()
    }

    // ── Разбор протоколов ─────────────────────────────────────────────────────

    private fun parseVless(uri: String, unsupportedTransports: MutableSet<String>? = null): ParsedProxy? {
        val p = splitUserHostQueryFragment(uri.removePrefixIgnoreCase("vless://")) ?: return null
        val uuid = p.user.ifBlank { return null }
        val q = p.query
        val net = q["type"]?.lowercase() ?: "tcp"
        if (!isTransportSupported(net)) { unsupportedTransports?.add(net); return null }
        val security = q["security"]?.lowercase() ?: "none"
        val name = p.fragment.ifBlank { p.host }
        val outbound = buildJsonObject {
            put("type", "vless")
            put("tag", name)
            put("server", p.host)
            put("server_port", p.port)
            put("uuid", uuid)
            q["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            put("packet_encoding", "xudp")
            if (security == "tls" || security == "reality" || security == "xtls") {
                put("tls", tlsBlock(q, p.host, reality = security == "reality"))
            }
            transportBlock(net, q)?.let { put("transport", it) }
        }
        return ParsedProxy(name, p.host, p.port, outbound)
    }

    private fun parseTrojan(uri: String, unsupportedTransports: MutableSet<String>? = null): ParsedProxy? {
        val p = splitUserHostQueryFragment(uri.removePrefixIgnoreCase("trojan://")) ?: return null
        val password = p.user.ifBlank { return null }
        val q = p.query
        val net = q["type"]?.lowercase() ?: "tcp"
        if (!isTransportSupported(net)) { unsupportedTransports?.add(net); return null }
        val name = p.fragment.ifBlank { p.host }
        val outbound = buildJsonObject {
            put("type", "trojan")
            put("tag", name)
            put("server", p.host)
            put("server_port", p.port)
            put("password", password)
            // Trojan почти всегда поверх TLS.
            put("tls", tlsBlock(q, p.host, reality = q["security"]?.lowercase() == "reality"))
            transportBlock(net, q)?.let { put("transport", it) }
        }
        return ParsedProxy(name, p.host, p.port, outbound)
    }

    private fun parseVmess(uri: String, unsupportedTransports: MutableSet<String>? = null): ParsedProxy? {
        val b64 = uri.removePrefixIgnoreCase("vmess://").substringBefore('#').trim()
        val jsonText = tryDecodeBase64ToText(b64) ?: return null
        val o = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
        fun s(k: String): String = o[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val host = s("add").ifBlank { return null }
        val port = s("port").toIntOrNull() ?: o["port"]?.jsonPrimitive?.intOrNull ?: return null
        val uuid = s("id").ifBlank { return null }
        val net = s("net").lowercase().ifBlank { "tcp" }
        if (!isTransportSupported(net)) { unsupportedTransports?.add(net); return null }
        val tls = s("tls").lowercase()
        val aid = s("aid").toIntOrNull() ?: 0
        val scy = s("scy").ifBlank { "auto" }
        val name = s("ps").ifBlank { host }
        // Параметры транспорта vmess лежат в host/path/type ключах самого JSON.
        val q = mapOf(
            "host" to s("host"),
            "path" to s("path"),
            "serviceName" to s("path"),
            "sni" to s("sni"),
            "alpn" to s("alpn"),
            "fp" to s("fp"),
        )
        val outbound = buildJsonObject {
            put("type", "vmess")
            put("tag", name)
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("security", scy)
            put("alter_id", aid)
            if (tls == "tls") put("tls", tlsBlock(q, host, reality = false))
            transportBlock(net, q)?.let { put("transport", it) }
        }
        return ParsedProxy(name, host, port, outbound)
    }

    private fun parseShadowsocks(uri: String): ParsedProxy? {
        val body = uri.removePrefixIgnoreCase("ss://")
        val fragment = percentDecode(body.substringAfter('#', "")).trim()
        val main = body.substringBefore('#').substringBefore('?')
        var method: String; var password: String; var host: String; var port: Int
        if (main.contains('@')) {
            // SIP002: ss://base64(method:password)@host:port
            val userPart = main.substringBefore('@')
            val hostPart = main.substringAfter('@')
            val creds = tryDecodeBase64ToText(userPart) ?: percentDecode(userPart)
            method = creds.substringBefore(':').trim()
            password = creds.substringAfter(':', "").trim()
            host = hostPart.substringBeforeLast(':').trim().trim('[', ']')
            port = hostPart.substringAfterLast(':').toIntOrNull() ?: return null
        } else {
            // Legacy: ss://base64(method:password@host:port)
            val decoded = tryDecodeBase64ToText(main) ?: return null
            val creds = decoded.substringBeforeLast('@')
            val hostPart = decoded.substringAfterLast('@')
            method = creds.substringBefore(':').trim()
            password = creds.substringAfter(':', "").trim()
            host = hostPart.substringBeforeLast(':').trim().trim('[', ']')
            port = hostPart.substringAfterLast(':').toIntOrNull() ?: return null
        }
        if (method.isBlank() || host.isBlank()) return null
        val name = fragment.ifBlank { host }
        val outbound = buildJsonObject {
            put("type", "shadowsocks")
            put("tag", name)
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        return ParsedProxy(name, host, port, outbound)
    }

    // ── Вспомогательные сборщики ──────────────────────────────────────────────

    private fun tlsBlock(q: Map<String, String>, server: String, reality: Boolean): JsonObject =
        buildJsonObject {
            put("enabled", true)
            val sni = q["sni"]?.ifBlank { null } ?: q["peer"]?.ifBlank { null } ?: server
            put("server_name", sni)
            q["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                putJsonArray("alpn") { alpn.split(',').forEach { add(it.trim()) } }
            }
            if (q["allowInsecure"] == "1" || q["insecure"] == "1") put("insecure", true)
            // uTLS-отпечаток: критично для Reality; по умолчанию chrome.
            putJsonObject("utls") {
                put("enabled", true)
                put("fingerprint", q["fp"]?.ifBlank { null } ?: "chrome")
            }
            if (reality) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", q["pbk"].orEmpty())
                    put("short_id", q["sid"].orEmpty())
                }
            }
        }

    // Транспорты, которые умеет собрать transportBlock (+ tcp/raw = без блока transport).
    // Всё остальное (xhttp, splithttp, httpupgrade, quic, kcp, …) движок не поддерживает —
    // такие ключи пропускаем с внятной ошибкой, а не импортируем молча как tcp.
    private val SUPPORTED_TRANSPORTS = setOf("", "tcp", "raw", "none", "ws", "grpc", "http", "h2")

    private fun isTransportSupported(net: String): Boolean = net in SUPPORTED_TRANSPORTS

    private fun transportBlock(net: String, q: Map<String, String>): JsonObject? = when (net) {
        "ws" -> buildJsonObject {
            put("type", "ws")
            put("path", q["path"]?.ifBlank { null } ?: "/")
            q["host"]?.takeIf { it.isNotBlank() }?.let { putJsonObject("headers") { put("Host", it) } }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            put("service_name", q["serviceName"]?.ifBlank { null } ?: q["path"].orEmpty())
        }
        "http", "h2" -> buildJsonObject {
            put("type", "http")
            put("path", q["path"]?.ifBlank { null } ?: "/")
            q["host"]?.takeIf { it.isNotBlank() }?.let { h -> putJsonArray("host") { add(h) } }
        }
        else -> null // tcp — без блока transport
    }

    // ── Низкоуровневый разбор URI ─────────────────────────────────────────────

    private data class UriParts(
        val user: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    )

    /** Разбирает «user@host:port?query#fragment» (без схемы). */
    private fun splitUserHostQueryFragment(s: String): UriParts? {
        val fragment = percentDecode(s.substringAfter('#', ""))
        val beforeFragment = s.substringBefore('#')
        val query = parseQuery(beforeFragment.substringAfter('?', ""))
        val authority = beforeFragment.substringBefore('?')
        if (!authority.contains('@')) return null
        val user = percentDecode(authority.substringBefore('@'))
        var hostPort = authority.substringAfter('@')
        // IPv6 в скобках: [::1]:443
        val host: String
        val port: Int
        if (hostPort.startsWith('[')) {
            host = hostPort.substringAfter('[').substringBefore(']')
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        } else {
            host = hostPort.substringBeforeLast(':').trim()
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        }
        if (host.isBlank()) return null
        return UriParts(user, host, port, query, fragment)
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = percentDecode(pair.substringAfter('=', ""))
            if (k.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) { out.append(code.toChar()); i += 3; continue }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    // ── base64 (стандартный и url-safe, терпим к отсутствию паддинга) ──────────

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun tryDecodeBase64ToText(input: String): String? {
        val cleaned = input.trim().replace('-', '+').replace('_', '/')
            .filter { it != '\n' && it != '\r' && it != ' ' && it != '=' }
        if (cleaned.isEmpty()) return null
        if (!cleaned.all { (it.code < 128) && B64.indexOf(it) >= 0 }) return null
        val table = IntArray(128) { -1 }
        for (i in B64.indices) table[B64[i].code] = i
        val out = ArrayList<Byte>(cleaned.length * 3 / 4)
        var buffer = 0; var bits = 0
        for (c in cleaned) {
            val v = if (c.code < 128) table[c.code] else -1
            if (v < 0) continue
            buffer = (buffer shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.add(((buffer shr bits) and 0xFF).toByte()) }
        }
        if (out.isEmpty()) return null
        val text = out.toByteArray().decodeToString()
        // Защита от мусора: текст должен быть печатным (иначе это не подписка).
        if (text.any { it.code in 0..8 || it.code in 14..31 }) return null
        return text
    }

    private val PROXY_SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://")
}
