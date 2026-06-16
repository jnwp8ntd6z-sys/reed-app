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
    ///
    /// CACHE-FIRST (как на Android, см. OlcboxVpnService): на «зарезанных» мобильных сетях РФ
    /// наш API недоступен, поэтому при наличии кэша отдаём его СРАЗУ (свежий тянем в фоне) —
    /// иначе extension не поднимет туннель там, где он нужнее всего. Сеть блокирующе нужна
    /// только при ПЕРВОМ подключении к серверу.
    private func fetchConfig(token: String, server: String, split: Bool,
                             completion: @escaping (String?) -> Void) {
        var comps = URLComponents(string: "\(apiBase)/app/singbox")!
        comps.queryItems = [
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "server", value: server),
            URLQueryItem(name: "split", value: split ? "1" : "0"),
            URLQueryItem(name: "inbound", value: "tun"),
        ]
        let url = comps.url!
        let cacheURL = configCacheURL(server: server, split: split)

        func httpGet(_ done: @escaping (String?) -> Void) {
            let task = URLSession.shared.dataTask(with: url) { data, _, _ in
                guard let data = data, let s = String(data: data, encoding: .utf8), !s.isEmpty else {
                    done(nil); return
                }
                try? s.data(using: .utf8)?.write(to: cacheURL)
                done(s)
            }
            task.resume()
        }

        if let cached = try? String(contentsOf: cacheURL, encoding: .utf8), !cached.isEmpty {
            // Есть кэш — отдаём сразу, свежий конфиг обновляем в фоне для следующего раза.
            httpGet { _ in }
            completion(cached)
            return
        }
        // Кэша ещё нет (первое подключение к серверу) — тянем с сети и сохраняем.
        httpGet(completion)
    }

    /// Файл кэша конфига для (сервер, split) в контейнере extension.
    private func configCacheURL(server: String, split: Bool) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let safe = String(server.unicodeScalars.map {
            CharacterSet.alphanumerics.contains($0) ? Character($0) : "_"
        })
        return dir.appendingPathComponent("singbox_cache_\(safe)_\(split ? "1" : "0").json")
    }
}
