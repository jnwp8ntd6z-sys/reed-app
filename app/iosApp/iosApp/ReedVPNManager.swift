import NetworkExtension
import Foundation

/// App-side управление системным VPN-туннелем (NEPacketTunnelProvider).
/// Ставит VPN-конфиг (первый раз iOS попросит «Разрешить VPN-конфигурацию»),
/// запускает/останавливает туннель, передаёт параметры (token/server/split) в extension.
///
/// Заменяет in-app `SwiftSingBoxManager` для VLESS: вместо локального SOCKS в процессе
/// приложения теперь настоящий системный туннель через extension.
/// providerBundleID должен совпадать с bundle id таргета PacketTunnel.
@objc final class ReedVPNManager: NSObject, @unchecked Sendable {

    @objc static let shared = ReedVPNManager()

    // Заменить на реальный bundle id extension при настройке таргета в Xcode.
    private let providerBundleID = "ru.reedapp.app.PacketTunnel"
    private var manager: NETunnelProviderManager?

    // Колбэк реального состояния туннеля для Kotlin-стороны: (connected, connectedAtMillis).
    // Туннель живёт отдельно от процесса приложения (переживает закрытие, управляется
    // тумблером в Пункте управления) — приложение обязано отражать его состояние, а не
    // помнить своё. Дёргается на каждом переходе .connected/.disconnected + один раз при
    // регистрации с текущим состоянием (ресинк после перезапуска приложения).
    private var stateHandler: ((Bool, Int64) -> Void)?

    private override init() {
        super.init()
        // Глобальный наблюдатель статуса VPN: object=nil, потому что connection-объект
        // меняется (loadOrCreate создаёт новые менеджеры), а уведомления шлёт система для
        // каждого из них. Фильтруем по типу — в нашем процессе только наш туннель.
        NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: nil, queue: .main
        ) { [weak self] note in
            guard let self, let connection = note.object as? NEVPNConnection else { return }
            self.emitState(connection)
        }
    }

    /// Регистрирует (или снимает, nil) обработчик состояния и сразу сообщает текущее
    /// состояние существующего туннеля — ресинк кнопки/таймера после перезапуска приложения.
    @objc func setStateHandler(_ handler: ((Bool, Int64) -> Void)?) {
        stateHandler = handler
        guard handler != nil else { return }
        if manager != nil {
            emitCurrentStateOnMain()
            return
        }
        NETunnelProviderManager.loadAllFromPreferences { managers, _ in
            guard let mgr = managers?.first else { return }
            self.manager = mgr
            self.emitCurrentStateOnMain()
        }
    }

    // В @Sendable-замыкание переносим только Sendable-ссылку на self — non-Sendable
    // manager/connection читаем уже на главной очереди (strict concurrency).
    private func emitCurrentStateOnMain() {
        DispatchQueue.main.async { [weak self] in
            guard let self, let connection = self.manager?.connection else { return }
            self.emitState(connection)
        }
    }

    /// Время реального подъёма туннеля (epoch millis) из NEVPNConnection.connectedDate;
    /// 0 — не подключён/неизвестно. Переживает перезапуск приложения.
    @objc func connectedDateMillis() -> Int64 {
        guard let connection = manager?.connection, connection.status == .connected else { return 0 }
        return Self.millis(connection.connectedDate)
    }

    private static func millis(_ date: Date?) -> Int64 {
        guard let date else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    // Сообщаем Kotlin только устойчивые состояния (.connected / .disconnected / .invalid):
    // промежуточные .connecting/.reasserting/.disconnecting обрабатывает логика start/stop.
    private func emitState(_ connection: NEVPNConnection) {
        guard let handler = stateHandler else { return }
        switch connection.status {
        case .connected:
            handler(true, Self.millis(connection.connectedDate))
        case .disconnected, .invalid:
            handler(false, 0)
        default:
            break
        }
    }

    /// Загружает существующий или создаёт новый VPN-менеджер и сохраняет его
    /// (первый saveToPreferences вызывает системный запрос разрешения VPN).
    private func loadOrCreate(_ completion: @escaping (NETunnelProviderManager?) -> Void) {
        NETunnelProviderManager.loadAllFromPreferences { managers, _ in
            let mgr = managers?.first ?? NETunnelProviderManager()
            let proto = (mgr.protocolConfiguration as? NETunnelProviderProtocol)
                ?? NETunnelProviderProtocol()
            proto.providerBundleIdentifier = self.providerBundleID
            proto.serverAddress = "Reed"
            mgr.protocolConfiguration = proto
            mgr.localizedDescription = "Reed"
            mgr.isEnabled = true
            mgr.saveToPreferences { _ in
                mgr.loadFromPreferences { _ in
                    self.manager = mgr
                    completion(mgr)
                }
            }
        }
    }

    /// Запуск туннеля с параметрами текущей подписки/сервера.
    @objc func start(token: String, server: String, split: Bool,
                     completion: @escaping (Bool) -> Void) {
        start(token: token, server: server, split: split, direct: "", completion: completion)
    }

    /// Reed 2.0: то же + «Сервисы напрямую» (id через запятую; пусто — как раньше).
    @objc func start(token: String, server: String, split: Bool, direct: String,
                     completion: @escaping (Bool) -> Void) {
        loadOrCreate { mgr in
            guard let mgr = mgr,
                  let proto = mgr.protocolConfiguration as? NETunnelProviderProtocol else {
                completion(false); return
            }
            proto.providerConfiguration = [
                "token": token,
                "server": server,
                "split": split,
                "direct": direct,
            ]
            mgr.protocolConfiguration = proto
            mgr.saveToPreferences { _ in
                mgr.loadFromPreferences { _ in
                    do {
                        try mgr.connection.startVPNTunnel()
                        // VLESS поднимается быстро, но всё равно рапортуем успех только когда
                        // туннель реально встал (.connected), а не сразу после startVPNTunnel.
                        self.awaitConnected(mgr.connection, timeout: 20, completion: completion)
                    } catch {
                        completion(false)
                    }
                }
            }
        }
    }

    /// Запуск системного туннеля для LTE (olcRTC внутри extension). Параметры движка передаём
    /// в providerConfiguration с mode=olc — PacketTunnelProvider поднимет olcRTC + sing-box tun→socks.
    @objc func startOlc(carrier: String, transport: String, room: String, clientId: String,
                        keyHex: String, split: Bool, vp8Fps: Int, vp8Batch: Int,
                        completion: @escaping (Bool) -> Void) {
        loadOrCreate { mgr in
            guard let mgr = mgr,
                  let proto = mgr.protocolConfiguration as? NETunnelProviderProtocol else {
                completion(false); return
            }
            proto.providerConfiguration = [
                "mode": "olc",
                "carrier": carrier,
                "transport": transport,
                "room": room,
                "clientId": clientId,
                "keyHex": keyHex,
                "split": split,
                "vp8Fps": vp8Fps,
                "vp8Batch": vp8Batch,
                "olcPort": 10861,
            ]
            mgr.protocolConfiguration = proto
            mgr.saveToPreferences { _ in
                mgr.loadFromPreferences { _ in
                    do {
                        try mgr.connection.startVPNTunnel()
                        // LTE: extension поднимает olcRTC (WebRTC-хендшейк) + sing-box на TUN —
                        // это ~20с. completionHandler extension (→ статус .connected) срабатывает
                        // ТОЛЬКО когда туннель реально встал. Рапортуем успех именно тогда, чтобы
                        // приложение держало «Подключаюсь…» весь подъём, а не «подключено» сразу.
                        self.awaitConnected(mgr.connection, timeout: 38, completion: completion)
                    } catch {
                        completion(false)
                    }
                }
            }
        }
    }

    /// Ждёт реального подъёма системного туннеля: рапортует success только когда
    /// `connection.status == .connected` (extension вызвал completionHandler(nil)). Провал —
    /// когда после фазы .connecting туннель свалился в .disconnected/.invalid, либо по таймауту.
    /// Логика вынесена в ConnectWaiter (@unchecked Sendable), чтобы non-Sendable connection/
    /// completion не «пересылались» напрямую в @Sendable-замыкания (strict concurrency).
    private func awaitConnected(_ connection: NEVPNConnection,
                                timeout: TimeInterval,
                                completion: @escaping (Bool) -> Void) {
        ConnectWaiter(connection, completion: completion).start(timeout: timeout)
    }

    @objc func stop() {
        (manager ?? nil)?.connection.stopVPNTunnel()
        NETunnelProviderManager.loadAllFromPreferences { managers, _ in
            managers?.first?.connection.stopVPNTunnel()
        }
    }

    @objc func isConnected() -> Bool {
        return manager?.connection.status == .connected
    }
}

/// Одноразовый наблюдатель за подъёмом системного туннеля. non-Sendable connection/completion
/// спрятаны за @unchecked Sendable-классом (доступ сериализован на главной очереди + NSLock),
/// поэтому в @Sendable-замыкания «пересылается» только Sendable-ссылка на сам объект — без
/// ошибок strict concurrency. completion зовётся ровно один раз: либо при .connected, либо при
/// падении в .disconnected/.invalid после фазы .connecting, либо по таймауту. Держит себя живым
/// сильными self-захватами до срабатывания таймера (он гарантированно наступает и освобождает).
private final class ConnectWaiter: @unchecked Sendable {
    private let connection: NEVPNConnection
    private let completion: (Bool) -> Void
    private let lock = NSLock()
    private var finished = false
    private var sawActive = false
    private var token: NSObjectProtocol?

    init(_ connection: NEVPNConnection, completion: @escaping (Bool) -> Void) {
        self.connection = connection
        self.completion = completion
    }

    func start(timeout: TimeInterval) {
        DispatchQueue.main.async {
            if self.connection.status == .connected { self.finish(true); return }
            self.token = NotificationCenter.default.addObserver(
                forName: .NEVPNStatusDidChange, object: self.connection, queue: .main) { _ in
                switch self.connection.status {
                case .connecting, .reasserting:
                    self.sawActive = true
                case .connected:
                    self.finish(true)
                case .disconnected, .invalid:
                    // Ранний .disconnected до старта — не провал; провал только если туннель
                    // уже пытался подняться (.connecting) и упал.
                    if self.sawActive { self.finish(false) }
                default:
                    break
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { self.finish(false) }
        }
    }

    private func finish(_ ok: Bool) {
        lock.lock()
        if finished { lock.unlock(); return }
        finished = true
        let t = token
        token = nil
        lock.unlock()
        if let t = t { NotificationCenter.default.removeObserver(t) }
        completion(ok)
    }
}
