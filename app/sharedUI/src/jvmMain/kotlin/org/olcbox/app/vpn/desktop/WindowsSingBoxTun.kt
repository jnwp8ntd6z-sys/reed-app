package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Windows 3.0: туннель поднимает САМ sing-box, как в Happ: tun-inbound с auto_route и
 * strict_route вместо связки «sing-box SOCKS + tun2socks + маршруты из PowerShell».
 *
 * Почему так. В старой схеме маршруты 0.0.0.0/1 + 128.0.0.0/1 на TUN перехватывали и
 * трафик самого движка: direct (РФ-сайты, РФ-резолвер) и соединения к серверам, кроме
 * одного /32 в обход. Отсюда петли, bind_interface-заплатка, запрет Автовыбора (urltest
 * ходит на чужие IP) и тихий drop QUIC. Когда TUN владеет sing-box, auto_detect_interface
 * привязывает ВСЕ исходящие соединения движка к физическому адаптеру — петли нет в
 * принципе, а strict_route не даёт DNS утечь мимо туннеля.
 *
 * Флаг auto_detect_interface включаем только здесь: запрет 16.09.2026 касался tun2socks
 * (там дефолтным интерфейсом оказывался чужой TUN), со своим TUN sing-box исключает его сам.
 */
internal object WindowsSingBoxTun {
    const val INTERFACE_NAME = "Reed"
    private const val TUN_TAG = "tun-in"
    private const val MIXED_TAG = "mixed-in"
    private const val TUN_IPV4 = "172.19.0.1/30"
    private const val TUN_IPV6 = "fdfe:dcba:9876::1/126"
    private const val TUN_MTU = 9000

    /**
     * gvisor: стек целиком в пользовательском пространстве. system-стек быстрее, но
     * слушает TCP на адресе TUN, и брандмауэр Windows спрашивает «разрешить sing-box?»
     * при первом подключении; отказ там = нет интернета. Режим system — потом настройкой.
     */
    private const val TUN_STACK = "gvisor"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Серверный конфиг (socks-inbound) → конфиг, в котором sing-box сам держит TUN.
     * Локальный mixed-вход (SOCKS+HTTP) на [mixedPort] оставляем: через него приложение
     * обновляет подписку при включённом туннеле, на нём же будет режим «системный прокси».
     */
    fun toTunConfig(configJson: String, mixedPort: Int): String {
        val root = json.parseToJsonElement(configJson).jsonObject
        val patched = root.toMutableMap()

        patched["inbounds"] = JsonArray(listOf(tunInbound(), mixedInbound(mixedPort)))

        val outbounds = root["outbounds"]?.jsonArray
        if (outbounds != null) {
            patched["outbounds"] = JsonArray(outbounds.map { it.withoutKey("bind_interface") })
        }

        val route = (root["route"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        route["auto_detect_interface"] = JsonPrimitive(true)
        route.remove("default_interface")
        route["rules"]?.let { rules -> route["rules"] = JsonArray(rules.jsonArray.map(::rejectInsteadOfDrop)) }
        patched["route"] = JsonObject(route)

        return json.encodeToString(JsonObject.serializer(), JsonObject(patched))
    }

    private fun tunInbound() = JsonObject(mapOf(
        "type" to JsonPrimitive("tun"),
        "tag" to JsonPrimitive(TUN_TAG),
        "interface_name" to JsonPrimitive(INTERFACE_NAME),
        "address" to JsonArray(listOf(JsonPrimitive(TUN_IPV4), JsonPrimitive(TUN_IPV6))),
        "mtu" to JsonPrimitive(TUN_MTU),
        "auto_route" to JsonPrimitive(true),
        "strict_route" to JsonPrimitive(true),
        "stack" to JsonPrimitive(TUN_STACK),
    ))

    private fun mixedInbound(port: Int) = JsonObject(mapOf(
        "type" to JsonPrimitive("mixed"),
        "tag" to JsonPrimitive(MIXED_TAG),
        "listen" to JsonPrimitive(PacServer.LOCAL_SOCKS_HOST),
        "listen_port" to JsonPrimitive(port),
    ))

    /**
     * Сервер выбрасывает QUIC (UDP 443) методом drop: пакет пропадает молча, и Chrome
     * ждёт до ERR_QUIC_PROTOCOL_ERROR. Внутри своего TUN sing-box умеет ответить отказом
     * (ICMP port unreachable) — браузер сразу уходит на HTTPS по TCP. Это замена
     * брандмауэрного правила из старой схемы.
     */
    private fun rejectInsteadOfDrop(rule: JsonElement): JsonElement {
        val o = rule as? JsonObject ?: return rule
        if (o["action"]?.jsonPrimitive?.content != "reject") return rule
        if (o["method"]?.jsonPrimitive?.content != "drop") return rule
        return o.withoutKey("method")
    }

    private fun JsonElement.withoutKey(key: String): JsonElement {
        val o = this as? JsonObject ?: return this
        if (key !in o) return this
        return JsonObject(o - key)
    }

    /**
     * Добивает sing-box, оставшийся от прошлого запуска (приложение упало или его убили
     * из диспетчера задач): живой процесс держит адаптер «Reed», и новый TUN с тем же
     * именем не поднимется. Ищем только наш бинарник по полному пути.
     */
    fun killStaleProcesses(binary: Path, addLog: (String) -> Unit) {
        val target = runCatching { binary.toRealPath().toString() }.getOrDefault(binary.toString())
        ProcessHandle.allProcesses()
            .filter { it.pid() != ProcessHandle.current().pid() }
            .filter { handle ->
                handle.info().command().map { it.equals(target, ignoreCase = true) }.orElse(false)
            }
            .forEach { handle ->
                addLog("Windows: останавливаю sing-box от прошлого запуска (pid ${handle.pid()})")
                handle.destroyForcibly()
                runCatching { handle.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
            }
    }
}
