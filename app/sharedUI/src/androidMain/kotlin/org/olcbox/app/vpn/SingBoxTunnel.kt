package org.olcbox.app.vpn

import singboxmobile.Singboxmobile

/**
 * Android-обёртка над ядром VLESS (sing-box), собранным gomobile в AAR
 * (модуль :sharedUI:singbox-bin, Go-пакет singboxmobile).
 *
 * Модель ровно как у olcRTC [mobile.Mobile]: ядро запускается ВНУТРИ процесса и
 * поднимает ЛОКАЛЬНЫЙ SOCKS5 (его задаёт socks-inbound в конфиге), на который потом
 * наводится тот же tun2socks, что и для olcRTC. Конфиг приходит с сервера:
 * GET /app/singbox?token=&socks_port=N (см. sub_server.py).
 *
 * ВНИМАНИЕ: пока НЕ подключено в OlcboxVpnService — рабочий olcRTC-путь не тронут.
 * Этот объект существует, чтобы сборка APK проверила, что AAR ядра sing-box реально
 * собирается gomobile и линкуется в приложение (символы start/stop/isRunning).
 * Подключение в транспорт-селекцию — следующий шаг.
 */
internal object SingBoxTunnel {

    /** Запустить sing-box из JSON-конфига (должен содержать socks-inbound 127.0.0.1:N). */
    @Throws(Exception::class)
    fun start(configJson: String) {
        Singboxmobile.start(configJson)
    }

    /** Остановить ядро (idempotent). */
    @Throws(Exception::class)
    fun stop() {
        Singboxmobile.stop()
    }

    /** Запущено ли ядро. */
    fun isRunning(): Boolean = Singboxmobile.isRunning()
}
