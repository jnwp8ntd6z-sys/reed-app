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
