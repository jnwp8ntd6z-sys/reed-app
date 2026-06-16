package org.olcbox.app.data

actual suspend fun tcpPingMs(host: String, port: Int, timeoutMs: Long): Long? =
    tcpPingMsKtor(host, port, timeoutMs)
