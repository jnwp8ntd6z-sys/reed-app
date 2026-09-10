import Darwin
import AVFoundation
import Foundation
import OlcRtcMobile
import SharedUI
import UIKit

/// Нативный мост к ядру VLESS (sing-box) на iOS поверх объединённой XCFramework
/// (функции Singboxmobile* лежат в том же модуле OlcRtcMobile, что и Mobile* olcRTC —
/// собраны одним gomobile bind). Ядро поднимает ЛОКАЛЬНЫЙ SOCKS5 (адрес задан в configJson
/// через socks-inbound). Фон держим как у olcRTC: AVAudioSession(.playback) + background task.
final class SwiftSingBoxManager: NSObject, @unchecked Sendable, IosSingBoxBridge {
    private var logWriter: IosLogWriter?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private let lock = NSLock()

    func setLogWriter(writer: IosLogWriter?) {
        lock.lock()
        defer { lock.unlock() }
        // У обёртки sing-box нет колбэка логов — храним writer для собственных сообщений.
        logWriter = writer
    }

    func start(configJson: String) -> IosBridgeResult {
        lock.lock()
        defer { lock.unlock() }

        var error: NSError?
        if SingboxmobileIsRunning() {
            SingboxmobileStop(&error)
            error = nil
        }

        let started = SingboxmobileStart(configJson, &error)
        guard started else {
            return IosBridgeResult(
                success: false,
                message: error?.localizedDescription ?? "VLESS start failed"
            )
        }

        // box.Start() поднимает socks-inbound синхронно — отдельного waitReady не нужно.
        activatePlaybackSession()
        beginBackgroundTaskIfNeeded()
        return IosBridgeResult(success: true, message: nil)
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        var error: NSError?
        SingboxmobileStop(&error)
        endBackgroundTaskIfNeeded()
        deactivatePlaybackSession()
    }

    func isRunning() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return SingboxmobileIsRunning()
    }

    // MARK: - Системный туннель (NEPacketTunnelProvider) для Reed-VLESS

    func startSystemTunnel(token: String, server: String, split: Bool) -> IosBridgeResult {
        let sem = DispatchSemaphore(value: 0)
        var ok = false
        // start() возвращается быстро (после startVPNTunnel); само подключение туннеля —
        // асинхронно, его статус читает isSystemTunnelConnected(). Ждём с запасом на первый
        // показ системного запроса «Разрешить VPN-конфигурацию».
        ReedVPNManager.shared.start(token: token, server: server, split: split) { success in
            ok = success
            sem.signal()
        }
        _ = sem.wait(timeout: .now() + 25)
        return IosBridgeResult(
            success: ok,
            message: ok ? nil : "Не удалось запустить системный туннель"
        )
    }

    func stopSystemTunnel() {
        ReedVPNManager.shared.stop()
    }

    // Предзагрузка tun-конфигов всех серверов в App Group: extension подключается из этого
    // кэша, не обращаясь к API (из extension конфиг качать ненадёжно — трафик уже в TUN).
    func prewarmTunnelConfigs(token: String, servers: [String], split: Bool, force: Bool) -> Int32 {
        guard let dir = TunnelConfigStore.directory() else { return 0 }
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 40
        let session = URLSession(configuration: cfg)
        let saved = PrewarmCounter()
        let group = DispatchGroup()
        for server in servers {
            let target = TunnelConfigStore.fileURL(dir: dir, server: server, split: split)
            if !force, FileManager.default.fileExists(atPath: target.path) { continue }
            guard let url = TunnelConfigStore.configURL(token: token, server: server, split: split) else { continue }
            group.enter()
            session.dataTask(with: url) { data, response, _ in
                defer { group.leave() }
                guard let body = TunnelConfigStore.validBody(data: data, response: response) else { return }
                if (try? body.write(to: target, options: .atomic)) != nil { saved.increment() }
            }.resume()
        }
        _ = group.wait(timeout: .now() + 60)
        session.finishTasksAndInvalidate()
        return Int32(saved.value)
    }

    // Диагностика: читаем пошаговый лог extension из общего контейнера App Group.
    func readExtensionLog() -> String? {
        guard let dir = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.ru.reedapp.app") else { return nil }
        let url = dir.appendingPathComponent("extension.log")
        return try? String(contentsOf: url, encoding: .utf8)
    }

    func isSystemTunnelConnected() -> Bool {
        ReedVPNManager.shared.isConnected()
    }

    // Реальное время подъёма туннеля (NEVPNConnection.connectedDate) — переживает
    // перезапуск приложения; 0 = не подключён/неизвестно.
    func systemTunnelConnectedAtMillis() -> Int64 {
        ReedVPNManager.shared.connectedDateMillis()
    }

    // Подписка Kotlin-стороны на реальные переходы состояния системного туннеля
    // (включая внешние: тумблер VPN в Пункте управления, перезапуск приложения).
    func setSystemTunnelStateListener(listener: IosSystemTunnelStateListener?) {
        guard let listener else {
            ReedVPNManager.shared.setStateHandler(nil)
            return
        }
        ReedVPNManager.shared.setStateHandler { connected, connectedAtMillis in
            listener.onSystemTunnelState(connected: connected, connectedAtMillis: connectedAtMillis)
        }
    }

    // Системный туннель для LTE (olcRTC внутри extension). Расширение само поднимает движок
    // olcRTC и прогоняет через него весь трафик TUN (см. PacketTunnelProvider mode=olc).
    func startSystemTunnelOlc(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        split: Bool,
        vp8Fps: Int32,
        vp8BatchSize: Int32
    ) -> IosBridgeResult {
        let sem = DispatchSemaphore(value: 0)
        var ok = false
        ReedVPNManager.shared.startOlc(
            carrier: carrier,
            transport: transport,
            room: roomId,
            clientId: clientId,
            keyHex: keyHex,
            split: split,
            vp8Fps: Int(vp8Fps),
            vp8Batch: Int(vp8BatchSize)
        ) { success in
            ok = success
            sem.signal()
        }
        // Подъём olcRTC внутри extension дольше VLESS (WebRTC-хендшейк) — ждём с запасом,
        // строго больше окна ожидания .connected в ReedVPNManager.startOlc (38с).
        _ = sem.wait(timeout: .now() + 42)
        return IosBridgeResult(
            success: ok,
            message: ok ? nil : "Не удалось запустить системный туннель LTE"
        )
    }

    private func beginBackgroundTaskIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let existingTask = self.backgroundTask
            self.lock.unlock()
            guard existingTask == .invalid else { return }

            var newTask: UIBackgroundTaskIdentifier = .invalid
            newTask = UIApplication.shared.beginBackgroundTask(withName: "Reed VLESS") { [weak self] in
                self?.endBackgroundTaskIfNeeded()
            }

            self.lock.lock()
            if self.backgroundTask == .invalid {
                self.backgroundTask = newTask
            } else if newTask != .invalid {
                UIApplication.shared.endBackgroundTask(newTask)
            }
            self.lock.unlock()
        }
    }

    // Фон-режим «audio» убран для App Store (без реального звука Apple отклоняет). Reed-серверы
    // (VLESS/LTE) работают через системный туннель (NEPacketTunnelProvider), который держит фон
    // сам. Встроенный SOCKS остаётся только для импортированных ключей и в фоне не удерживается.
    private func activatePlaybackSession() {}

    private func deactivatePlaybackSession() {}

    private func endBackgroundTaskIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let task = self.backgroundTask
            self.backgroundTask = .invalid
            self.lock.unlock()

            guard task != .invalid else { return }
            UIApplication.shared.endBackgroundTask(task)
        }
    }
}

/// Кэш tun-конфигов sing-box в App Group — общий с PacketTunnel extension.
/// ВАЖНО: имена файлов и проверка тела должны совпадать с TunnelConfigStore в
/// PacketTunnel/PacketTunnelProvider.swift (разные таргеты, общий код не подключён).
enum TunnelConfigStore {
    static let appGroup = "group.ru.reedapp.app"
    static let apiBase = "https://reedapp.ru"

    static func directory() -> URL? {
        guard let root = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup) else { return nil }
        let dir = root.appendingPathComponent("tunnel_configs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func fileURL(dir: URL, server: String, split: Bool) -> URL {
        let safe = String(server.unicodeScalars.map {
            CharacterSet.alphanumerics.contains($0) ? Character($0) : "_"
        })
        return dir.appendingPathComponent("singbox_\(safe)_\(split ? "1" : "0").json")
    }

    static func configURL(token: String, server: String, split: Bool) -> URL? {
        var comps = URLComponents(string: "\(apiBase)/app/singbox")
        comps?.queryItems = [
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "server", value: server),
            URLQueryItem(name: "split", value: split ? "1" : "0"),
            URLQueryItem(name: "inbound", value: "tun"),
        ]
        return comps?.url
    }

    /// Только HTTP 200 с JSON-объектом: тело ошибки (403/404 «expired») в кэш не пускаем.
    static func validBody(data: Data?, response: URLResponse?) -> Data? {
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let data = data, data.count > 100 else { return nil }
        let first = data.first { !($0 == 0x20 || $0 == 0x0A || $0 == 0x0D || $0 == 0x09) }
        return first == UInt8(ascii: "{") ? data : nil
    }
}

private final class PrewarmCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.lock(); count += 1; lock.unlock() }
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
}
