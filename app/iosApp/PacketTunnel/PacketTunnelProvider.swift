import NetworkExtension
import Foundation
import OlcRtcMobile   // Singboxmobile* / libbox функции (тот же combined XCFramework)

/// Packet Tunnel Provider — системный VPN Reed на iOS.
/// Запускается системой при старте VPN. Создаёт TUN, передаёт его sing-box (libbox),
/// sing-box маршрутизирует трафик по конфигу (split-routing РФ уже в конфиге сервера).
///
/// ВАЖНО (см. docs/IOS_NETWORK_EXTENSION.md):
///  - параметры (token/server/split) приходят из приложения через providerConfiguration
///    (NETunnelProviderProtocol) либо через App Group;
///  - запуск sing-box с TUN требует доработки gomobile-обёртки (StartWithTun / libbox
///    PlatformInterface). Места отмечены TODO.
final class PacketTunnelProvider: NEPacketTunnelProvider {

    private let apiBase = "https://reed-vpn.duckdns.org"

    override func startTunnel(options: [String: NSObject]?,
                              completionHandler: @escaping (Error?) -> Void) {
        // 1. Параметры из providerConfiguration.
        let proto = (protocolConfiguration as? NETunnelProviderProtocol)
        let conf = proto?.providerConfiguration ?? [:]
        let token = (conf["token"] as? String) ?? ""
        let server = (conf["server"] as? String) ?? ""
        let split = (conf["split"] as? Bool) ?? true
        guard !token.isEmpty else {
            completionHandler(NSError(domain: "ReedVPN", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "no token"]))
            return
        }

        // 2. Сетевые настройки TUN.
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4 = NEIPv4Settings(addresses: ["172.19.0.1"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]   // весь трафик в TUN; split делает sing-box
        settings.ipv4Settings = ipv4
        let dns = NEDNSSettings(servers: ["8.8.8.8", "1.1.1.1"])
        settings.dnsSettings = dns
        settings.mtu = 9000

        setTunnelNetworkSettings(settings) { [weak self] error in
            if let error = error { completionHandler(error); return }
            guard let self = self else { return }
            // 3. Скачиваем sing-box конфиг и запускаем ядро с TUN.
            self.fetchConfig(token: token, server: server, split: split) { configJson in
                guard let configJson = configJson else {
                    completionHandler(NSError(domain: "ReedVPN", code: 2,
                        userInfo: [NSLocalizedDescriptionKey: "config fetch failed"]))
                    return
                }
                // TODO(binding): запустить sing-box с TUN-дескриптором этого extension.
                // Вариант A (после доработки обёртки): SingboxmobileStartWithTun(configJson, self.tunFd(), &err)
                // Вариант B (libbox PlatformInterface): LibboxNewService(configJson, platformInterface)
                // Пока — заглушка успешного старта (заменить на реальный вызов в Xcode):
                var nsErr: NSError?
                let ok = SingboxmobileStart(configJson, &nsErr)   // ВРЕМЕННО (socks); заменить на TUN-вариант
                completionHandler(ok ? nil : nsErr)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason,
                             completionHandler: @escaping () -> Void) {
        var err: NSError?
        SingboxmobileStop(&err)
        completionHandler()
    }

    /// Конфиг sing-box: /app/singbox?token=&server=&split=  (inbound=tun — см. серверную доработку).
    private func fetchConfig(token: String, server: String, split: Bool,
                             completion: @escaping (String?) -> Void) {
        var comps = URLComponents(string: "\(apiBase)/app/singbox")!
        comps.queryItems = [
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "server", value: server),
            URLQueryItem(name: "split", value: split ? "1" : "0"),
            URLQueryItem(name: "inbound", value: "tun"),
        ]
        let task = URLSession.shared.dataTask(with: comps.url!) { data, _, _ in
            guard let data = data, let s = String(data: data, encoding: .utf8), !s.isEmpty else {
                completion(nil); return
            }
            completion(s)
        }
        task.resume()
    }
}
