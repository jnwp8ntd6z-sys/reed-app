import Foundation
import NetworkExtension

/// Reed 2.0 — системный туннель iOS для нового SwiftUI-интерфейса.
/// Запуск/остановка — через проверенный ReedVPNManager (VLESS по токену и olcRTC-обход),
/// статус — прямо из NEVPNStatusDidChange (туннель живёт отдельно от приложения и может быть
/// включён из Пункта управления — UI обязан отражать реальное состояние, а не своё).
@MainActor
final class ReedSystemTunnel: ReedTunnel {
    private(set) var status: ReedTunnelStatus = .disconnected
    private(set) var connectedSince: Date?
    var onChange: (() -> Void)?
    private var observer: NSObjectProtocol?

    init() {
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: nil, queue: .main
        ) { [weak self] note in
            let conn = note.object as? NEVPNConnection
            let st = conn?.status
            let since = conn?.connectedDate
            Task { @MainActor [weak self] in self?.apply(st, since) }
        }
    }

    private func apply(_ st: NEVPNStatus?, _ since: Date?) {
        let new: ReedTunnelStatus
        switch st {
        case .some(.connected): new = .connected
        case .some(.connecting), .some(.reasserting): new = .connecting
        case .some(.disconnecting): new = .disconnecting
        default: new = .disconnected
        }
        status = new
        connectedSince = (new == .connected) ? (since ?? connectedSince ?? Date()) : nil
        onChange?()
    }

    func refresh() async {
        let snap: (NEVPNStatus?, Date?) = await withCheckedContinuation { cont in
            NETunnelProviderManager.loadAllFromPreferences { managers, _ in
                let c = managers?.first?.connection
                cont.resume(returning: (c?.status, c?.connectedDate))
            }
        }
        apply(snap.0, snap.1)
    }

    func start(_ target: ReedTunnelTarget, split: Bool, direct: [String]) async -> Bool {
        status = .connecting
        onChange?()
        let ok: Bool = await withCheckedContinuation { cont in
            switch target {
            case .vless(let token, let server):
                ReedVPNManager.shared.start(token: token, server: server, split: split,
                                            direct: direct.joined(separator: ",")) { ok in
                    cont.resume(returning: ok)
                }
            case .olc(let c, let clientId):
                ReedVPNManager.shared.startOlc(
                    carrier: c.provider, transport: c.transport, room: c.room, clientId: clientId,
                    keyHex: c.key, split: split, vp8Fps: c.vp8Fps, vp8Batch: c.vp8Batch
                ) { ok in
                    cont.resume(returning: ok)
                }
            }
        }
        await refresh()
        return ok
    }

    func stop() {
        ReedVPNManager.shared.stop()
    }

    /// Автоподключение (ТЗ 6.5): NEOnDemandRuleConnect для любых сетей.
    func setOnDemand(_ enabled: Bool) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            NETunnelProviderManager.loadAllFromPreferences { managers, _ in
                guard let m = managers?.first else { cont.resume(); return }
                let rule = NEOnDemandRuleConnect()
                rule.interfaceTypeMatch = .any
                m.onDemandRules = [rule]
                m.isOnDemandEnabled = enabled
                m.saveToPreferences { _ in cont.resume() }
            }
        }
    }

    /// Прогрев tun-конфигов в App Group (extension подключается из кэша, не ходя в API).
    func prewarm(token: String, servers: [String], split: Bool, direct: [String]) {
        guard !servers.isEmpty else { return }
        let d = direct.joined(separator: ",")
        Task.detached(priority: .utility) {
            let sb = SwiftSingBoxManager()
            _ = sb.prewarmTunnelConfigs(token: token, servers: servers, split: split, direct: d, force: false)
            _ = sb.prewarmTunnelConfigs(token: token, servers: servers, split: !split, direct: d, force: false)
        }
    }

    func extensionLog() -> String? {
        SwiftSingBoxManager().readExtensionLog()
    }
}
