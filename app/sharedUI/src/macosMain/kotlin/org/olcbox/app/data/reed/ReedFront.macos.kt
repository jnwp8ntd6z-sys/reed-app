package org.olcbox.app.data.reed

import io.ktor.client.HttpClientConfig

// Фолбэк на IP фронта пока только на iOS (там ТСПУ режет большой ClientHello к загранице).
internal actual val reedFrontFallbackSupported: Boolean = false

internal actual fun HttpClientConfig<*>.configureReedFrontTls() {}
