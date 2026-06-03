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

    private func activatePlaybackSession() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                try session.setActive(true)
            } catch {
                self.lock.lock()
                let writer = self.logWriter
                self.lock.unlock()
                writer?.writeLog(message: "iOS playback background mode unavailable: \(error.localizedDescription)")
            }
        }
    }

    private func deactivatePlaybackSession() {
        DispatchQueue.main.async {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }

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
