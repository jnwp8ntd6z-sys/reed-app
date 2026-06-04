import NetworkExtension
import Foundation

/// App-side управление системным VPN-туннелем (NEPacketTunnelProvider).
/// Ставит VPN-конфиг (первый раз iOS попросит «Разрешить VPN-конфигурацию»),
/// запускает/останавливает туннель, передаёт параметры (token/server/split) в extension.
///
/// Заменяет in-app `SwiftSingBoxManager` для VLESS: вместо локального SOCKS в процессе
/// приложения теперь настоящий системный туннель через extension.
/// providerBundleID должен совпадать с bundle id таргета PacketTunnel.
@objc final class ReedVPNManager: NSObject {

    @objc static let shared = ReedVPNManager()

    // Заменить на реальный bundle id extension при настройке таргета в Xcode.
    private let providerBundleID = "org.reedvpn.app.PacketTunnel"
    private var manager: NETunnelProviderManager?

    /// Загружает существующий или создаёт новый VPN-менеджер и сохраняет его
    /// (первый saveToPreferences вызывает системный запрос разрешения VPN).
    private func loadOrCreate(_ completion: @escaping (NETunnelProviderManager?) -> Void) {
        NETunnelProviderManager.loadAllFromPreferences { managers, _ in
            let mgr = managers?.first ?? NETunnelProviderManager()
            let proto = (mgr.protocolConfiguration as? NETunnelProviderProtocol)
                ?? NETunnelProviderProtocol()
            proto.providerBundleIdentifier = self.providerBundleID
            proto.serverAddress = "Reed VPN"
            mgr.protocolConfiguration = proto
            mgr.localizedDescription = "Reed VPN"
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
        loadOrCreate { mgr in
            guard let mgr = mgr,
                  let proto = mgr.protocolConfiguration as? NETunnelProviderProtocol else {
                completion(false); return
            }
            proto.providerConfiguration = [
                "token": token,
                "server": server,
                "split": split,
            ]
            mgr.protocolConfiguration = proto
            mgr.saveToPreferences { _ in
                mgr.loadFromPreferences { _ in
                    do {
                        try mgr.connection.startVPNTunnel()
                        completion(true)
                    } catch {
                        completion(false)
                    }
                }
            }
        }
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
