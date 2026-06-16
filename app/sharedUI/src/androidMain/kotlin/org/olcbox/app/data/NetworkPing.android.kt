package org.olcbox.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Android-замер пинга через обычный java.net.Socket. Селектор ktor-network на Android
 * (особенно в эмуляторе) часто не подключается и отдаёт «—», хотя сеть рабочая —
 * блокирующий connect через java.net надёжнее.
 */
actual suspend fun tcpPingMs(host: String, port: Int, timeoutMs: Long): Long? {
    if (host.isBlank() || port <= 0) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                (System.nanoTime() - start) / 1_000_000L
            }
        }.getOrNull()
    }
}
