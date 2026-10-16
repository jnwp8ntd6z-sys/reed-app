package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.LocationConfig
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets

/** Окно приложения → служба Reed Client Service (127.0.0.1, см. [ReedWindowsService]). */
internal object ReedServiceClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val BASE = "http://127.0.0.1:${ReedWindowsService.PORT}"

    /** Служба установлена и отвечает? Короткий таймаут — вызывается на каждом подключении. */
    fun available(): Boolean = runCatching { status(0) != null }.getOrDefault(false)

    fun status(from: Int): ReedWindowsService.Status? =
        request("GET", "/status?from=$from", null, timeoutMs = 700)
            ?.let { json.decodeFromString(ReedWindowsService.Status.serializer(), it) }

    fun start(location: LocationConfig, split: Boolean): ReedWindowsService.Status? =
        request("POST", "/start",
            json.encodeToString(ReedWindowsService.StartRequest.serializer(), ReedWindowsService.StartRequest(location, split)),
            timeoutMs = 5_000)
            ?.let { json.decodeFromString(ReedWindowsService.Status.serializer(), it) }

    fun stop() { request("POST", "/stop", "", timeoutMs = 5_000) }

    private fun request(method: String, path: String, body: String?, timeoutMs: Int): String? {
        // Proxy.NO_PROXY: системный прокси (режим «прокси» у других программ) не должен перехватывать 127.0.0.1.
        val c = URL(BASE + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.requestMethod = method
        c.setRequestProperty(ReedWindowsService.HEADER, "1")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) text else error(text?.let { Regex("\"error\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) } ?: "HTTP $code")
        } finally { c.disconnect() }
    }
}
