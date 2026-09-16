package org.olcbox.app.data.reed

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.CancellationException

/**
 * РФ-фронт API: EDGE-2 Москва пробрасывает TCP на бот, TLS отдаёт сертификат reedapp.ru.
 * Если reedapp.ru недоступен (провайдер держит в DNS-кэше старый зарубежный IP, ТСПУ режет
 * TLS к нему, DNS заблокирован) — повторяем запрос напрямую на IP фронта. Сертификат при этом
 * проверяется строго на имя reedapp.ru (см. configureReedFrontTls на iOS).
 */
object ReedFront {
    const val API_HOST = "reedapp.ru"
    const val FRONT_IP = "135.106.182.198"
}

/** Платформы, где TLS к [ReedFront.FRONT_IP] умеет проверять сертификат на имя reedapp.ru. */
internal expect val reedFrontFallbackSupported: Boolean

/** Платформенная часть: разрешить TLS к IP фронта с проверкой сертификата на reedapp.ru. */
internal expect fun HttpClientConfig<*>.configureReedFrontTls()

/**
 * Запасной путь к API через IP фронта. Платформенная часть ставится всегда: на десктопе
 * она добавляет адрес фронта прямо в резолвер движка (имя и проверка сертификата остаются
 * reedapp.ru), а на iOS требуется ещё и подмена хоста в URL — её включает флаг ниже.
 */
internal fun HttpClientConfig<*>.installReedFrontFallback() {
    configureReedFrontTls()
    if (!reedFrontFallbackSupported) return
    install(HttpRequestRetry) {
        retryIf(maxRetries = 1) { _, _ -> false }
        retryOnExceptionIf(maxRetries = 1) { request, cause ->
            cause !is CancellationException && request.url.host == ReedFront.API_HOST
        }
        modifyRequest { request ->
            if (request.url.host == ReedFront.API_HOST) request.url.host = ReedFront.FRONT_IP
        }
        constantDelay(millis = 100, randomizationMs = 0)
    }
}
