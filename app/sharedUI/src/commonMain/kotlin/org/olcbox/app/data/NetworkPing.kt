package org.olcbox.app.data

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Замеряет время TCP-подключения к host:port (DNS + handshake) — используется как «пинг»
 * для VLESS-серверов (кнопка «Тест»). Возвращает миллисекунды или null при ошибке/таймауте.
 *
 * Реализация платформенная: на Android селектор ktor-network даёт «—» (особенно в
 * эмуляторе), поэтому там используется обычный java.net.Socket. На desktop/iOS/macOS
 * работает ktor-путь (tcpPingMsKtor).
 */
expect suspend fun tcpPingMs(host: String, port: Int, timeoutMs: Long = 3500): Long?

/** Кроссплатформенный замер через ktor-network (JVM-desktop/iOS/macOS). */
internal suspend fun tcpPingMsKtor(host: String, port: Int, timeoutMs: Long): Long? {
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
