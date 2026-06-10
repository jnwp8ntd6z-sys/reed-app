package org.olcbox.app.data

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Замеряет время TCP-подключения к host:port (DNS + handshake) — используется как «пинг»
 * для VLESS-серверов (кнопка «Тест»). Возвращает миллисекунды или null при ошибке/таймауте.
 * Кроссплатформенно через ktor-network (JVM/Android/iOS/macOS).
 */
suspend fun tcpPingMs(host: String, port: Int, timeoutMs: Long = 3500): Long? {
    if (host.isBlank() || port <= 0) return null
    val selector = SelectorManager(Dispatchers.Default)
    return try {
        withTimeoutOrNull(timeoutMs) {
            val mark = TimeSource.Monotonic.markNow()
            val socket = aSocket(selector).tcp().connect(host, port)
            val elapsed = mark.elapsedNow().inWholeMilliseconds
            socket.close()
            elapsed
        }
    } catch (e: Throwable) {
        null
    } finally {
        selector.close()
    }
}
