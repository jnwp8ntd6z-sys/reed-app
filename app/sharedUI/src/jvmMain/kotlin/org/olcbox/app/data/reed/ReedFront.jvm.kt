package org.olcbox.app.data.reed

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

// Подмена хоста в URL на десктопе не нужна: адрес фронта добавляется в резолвер движка,
// поэтому SNI и проверка сертификата остаются на имя reedapp.ru.
internal actual val reedFrontFallbackSupported: Boolean = false

@Suppress("UNCHECKED_CAST")
internal actual fun HttpClientConfig<*>.configureReedFrontTls() {
    (this as HttpClientConfig<OkHttpConfig>).engine {
        config {
            dns(ReedFrontDns)
        }
    }
}

/**
 * Резолвер API: к системным адресам reedapp.ru добавляем в конец IP московского фронта.
 * OkHttp сам переходит к следующему адресу, если к предыдущему не удалось подключиться,
 * поэтому фронт срабатывает и когда провайдер держит в кэше старый зарубежный IP, и когда
 * ТСПУ режет к нему TLS, и когда имя вообще не резолвится.
 */
private object ReedFrontDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(ReedFront.API_HOST, ignoreCase = true)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        // Имя привязываем к адресу явно: OkHttp возьмёт его для SNI и проверки сертификата.
        val front = runCatching {
            InetAddress.getByAddress(ReedFront.API_HOST, frontIpBytes())
        }.getOrNull()
        val all = (system + listOfNotNull(front)).distinct()
        if (all.isEmpty()) throw UnknownHostException(hostname)
        return all
    }

    private fun frontIpBytes(): ByteArray =
        ReedFront.FRONT_IP.split(".").map { it.toInt().toByte() }.toByteArray()
}
