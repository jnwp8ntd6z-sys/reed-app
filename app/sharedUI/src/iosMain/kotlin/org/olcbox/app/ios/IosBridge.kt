package org.olcbox.app.ios

data class IosOlcRtcStartRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosOlcRtcCheckRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val timeoutMillis: Long,
    val pingUrl: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosBridgeResult(
    val success: Boolean,
    val message: String?
)

data class IosLongResult(
    val success: Boolean,
    val valueMillis: Long,
    val message: String?
)

interface IosLogWriter {
    fun writeLog(message: String)
}

interface IosTextCallback {
    fun onSuccess(text: String)
    fun onError(message: String)
}

interface IosMessageCallback {
    fun onSuccess(message: String)
    fun onError(message: String)
}

/**
 * Слушатель РЕАЛЬНОГО состояния системного туннеля (NEPacketTunnelProvider). Системный VPN
 * живёт отдельно от процесса приложения: переживает его закрытие и включается/выключается
 * тумблером в Пункте управления iOS. Swift-сторона дёргает колбэк при каждом переходе
 * .connected / .disconnected (+ один раз при регистрации с текущим состоянием), чтобы кнопка
 * и таймер в приложении всегда отражали реальность.
 */
interface IosSystemTunnelStateListener {
    fun onSystemTunnelState(connected: Boolean, connectedAtMillis: Long)
}

interface IosOlcRtcBridge {
    fun setLogWriter(writer: IosLogWriter?)
    fun start(request: IosOlcRtcStartRequest): IosBridgeResult
    fun stop()
    fun isRunning(): Boolean
    fun ping(request: IosOlcRtcCheckRequest): IosLongResult
    fun check(request: IosOlcRtcCheckRequest): IosLongResult
}

/**
 * Мост к нативному ядру VLESS (sing-box) на iOS. Реализуется в Swift (SwiftSingBoxManager)
 * поверх объединённой XCFramework (функции Singboxmobile*). Модель как у olcRTC: ядро
 * поднимает ЛОКАЛЬНЫЙ SOCKS5 (его адрес задан в configJson через socks-inbound), на который
 * указывают приложения/система. На iOS нет TUN — «VPN» это и есть локальный SOCKS-прокси.
 */
interface IosSingBoxBridge {
    fun setLogWriter(writer: IosLogWriter?)
    fun start(configJson: String): IosBridgeResult
    fun stop()
    fun isRunning(): Boolean

    /**
     * Системный VPN-туннель (NEPacketTunnelProvider) для НАШИХ VLESS-серверов (по токену
     * подписки). В отличие от start() (локальный SOCKS в процессе), здесь трафик всей системы
     * реально идёт через туннель: extension качает /app/singbox?inbound=tun и поднимает sing-box
     * на TUN. Первый запуск вызывает системный запрос «Разрешить VPN-конфигурацию».
     */
    fun startSystemTunnel(token: String, server: String, split: Boolean): IosBridgeResult

    /**
     * Предзагрузка tun-конфигов (/app/singbox?inbound=tun) для списка серверов в общий
     * контейнер App Group, откуда их читает extension. Подключение тогда идёт из кэша без
     * обращения к API (extension не может надёжно качать конфиг сам: трафик уже в TUN).
     * force=false — качать только отсутствующие. Блокирующий вызов; возвращает число
     * успешно сохранённых конфигов.
     */
    fun prewarmTunnelConfigs(token: String, servers: List<String>, split: Boolean, force: Boolean): Int
    fun stopSystemTunnel()
    fun isSystemTunnelConnected(): Boolean

    /** Реальное время подъёма системного туннеля (epoch millis из NEVPNConnection.connectedDate),
     * 0 — туннель не подключён/время неизвестно. Переживает перезапуск приложения. */
    fun systemTunnelConnectedAtMillis(): Long

    /** Подписка на переходы состояния системного туннеля (см. [IosSystemTunnelStateListener]).
     * При регистрации Swift сразу сообщает текущее состояние — ресинк после перезапуска. */
    fun setSystemTunnelStateListener(listener: IosSystemTunnelStateListener?)

    /**
     * СИСТЕМНЫЙ туннель для LTE (olcRTC). В отличие от startSystemTunnel (VLESS): расширение
     * поднимает движок olcRTC на 127.0.0.1:<olcPort> и прогоняет весь трафик через него —
     * tun → sing-box (split РФ, конфиг /app/olcsingbox?inbound=tun) → socks(olcRTC) → WebRTC.
     * Даёт значок VPN и реальный захват трафика на сотовой (раньше olcRTC жил как in-app SOCKS
     * без туннеля). Параметры движка (carrier/transport/room/key/vp8) те же, что у [IosOlcRtcBridge].
     */
    fun startSystemTunnelOlc(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        split: Boolean,
        vp8Fps: Int,
        vp8BatchSize: Int
    ): IosBridgeResult

    /** Диагностика: полный текст пошагового лога extension (App Group-файл), чтобы показать
     * его в логах приложения — extension это отдельный процесс, его os_log приложению не виден. */
    fun readExtensionLog(): String?
}

interface IosPlatformBridge {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun pickConfigText(callback: IosTextCallback)
    fun shareText(title: String, text: String)
    fun saveLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun shareLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun showMessage(message: String)
}
