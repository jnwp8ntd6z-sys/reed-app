package org.olcbox.app.data.reed

actual fun reedIsIOS(): Boolean = false

actual fun reedPlatformName(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("win") -> "Windows"
        os.contains("mac") || os.contains("darwin") -> "macOS"
        else -> "Linux"
    }
}

/** Имя компьютера: так устройство узнаётся в списке «Устройства» бота. */
actual fun reedDeviceModel(): String {
    val fromEnv = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
    if (!fromEnv.isNullOrBlank()) return fromEnv
    return runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
}
