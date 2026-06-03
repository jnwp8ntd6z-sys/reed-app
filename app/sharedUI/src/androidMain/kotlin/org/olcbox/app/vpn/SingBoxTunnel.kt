package org.olcbox.app.vpn

import singboxmobile.Singboxmobile

/**
 * Android-обёртка над ядром VLESS (sing-box). Идёт В ТОЙ ЖЕ нативной библиотеке, что и
 * olcRTC (модуль :sharedUI:reedmobile-bin — один gomobile bind пакетов mobile +
 * singboxmobile в одну libgojni.so).
 *
 * Модель ровно как у olcRTC [mobile.Mobile]: ядро запускается ВНУТРИ процесса и поднимает
 * ЛОКАЛЬНЫЙ SOCKS5 (его задаёт socks-inbound в конфиге), на который потом наводится тот же
 * tun2socks, что и для olcRTC. Конфиг приходит с сервера:
 * GET /app/singbox?token=&socks_port=N (см. sub_server.py).
 *
 * Подключение в транспорт-селекцию (вызов из OlcboxVpnService при transport==vless) —
 * следующий шаг; рабочий olcRTC-путь не тронут.
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
