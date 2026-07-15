import NetworkExtension
import Foundation
import OlcRtcMobile   // Singboxmobile* (sing-box/libbox) + Mobile* (olcRTC) — один combined XCFramework

/// Packet Tunnel Provider — системный VPN Reed на iOS.
/// Запускается системой при старте VPN. Создаёт TUN, передаёт его sing-box (libbox),
/// sing-box маршрутизирует трафик по конфигу.
///
/// Два режима (providerConfiguration["mode"]):
///  - "vless" (по умолчанию): наш VLESS по токену. Конфиг /app/singbox?inbound=tun.
///  - "olc": LTE. Внутри extension поднимаем движок olcRTC (127.0.0.1:olcPort), затем
///    sing-box tun→socks(olcRTC) со split-РФ (/app/olcsingbox?inbound=tun). Так весь трафик
///    системы идёт через WebRTC-туннель и в статус-баре появляется значок VPN.
/// Общий файл-лог расширения (App Group) — extension отдельный процесс, его os_log не виден
/// в логах приложения. Пишем сюда пошагово, приложение читает и показывает (IosVpnManager).
enum ExtLog {
    static let appGroup = "group.ru.reedapp.app"
    static let fileName = "extension.log"
    private static let q = DispatchQueue(label: "ru.reedapp.extlog")

    static func url() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent(fileName)
    }
    /// reset=true — обнулить лог (в начале нового старта туннеля).
    static func write(_ msg: String, reset: Bool = false) {
        guard let url = url() else { return }
        q.sync {
            let ts = ISO8601DateFormatter().string(from: Date())
            let line = "[\(ts)] \(msg)\n"
            if reset {
                try? line.data(using: .utf8)?.write(to: url)
                return
            }
            if let h = try? FileHandle(forWritingTo: url) {
                h.seekToEndOfFile(); h.write(line.data(using: .utf8)!); try? h.close()
            } else {
                try? line.data(using: .utf8)?.write(to: url)
            }
        }
    }
}

final class PacketTunnelProvider: NEPacketTunnelProvider {

    private let apiBase = "https://reedapp.ru"

    // olcRTC внутри extension активен → на stop надо остановить и его.
    private var olcActive = false
    // Локальный SOCKS движка olcRTC поднимаем С авторизацией (как на Android): 127.0.0.1 доступен
    // другим приложениям устройства, auth не даёт им бесплатно тоннелить через наш LTE. Те же
    // креды инъектим в socks-outbound конфига sing-box (injectOlcAuth).
    private let olcSocksUser = "reedlte"
    private let olcSocksPass = "reedlte-9f3a1c7b"

    override func startTunnel(options: [String: NSObject]?,
                              completionHandler: @escaping (Error?) -> Void) {
        let proto = (protocolConfiguration as? NETunnelProviderProtocol)
        let conf = proto?.providerConfiguration ?? [:]
        let mode = (conf["mode"] as? String) ?? "vless"
        ExtLog.write("startTunnel: mode=\(mode)", reset: true)

        // Сетевые настройки TUN (общие для обоих режимов).
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4 = NEIPv4Settings(addresses: ["172.19.0.1"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]   // весь трафик в TUN; split делает sing-box
        settings.ipv4Settings = ipv4
        settings.dnsSettings = NEDNSSettings(servers: ["8.8.8.8", "1.1.1.1"])
        settings.mtu = 9000

        setTunnelNetworkSettings(settings) { [weak self] error in
            if let error = error {
                ExtLog.write("setTunnelNetworkSettings FAILED: \(error.localizedDescription)")
                completionHandler(error); return
            }
            ExtLog.write("setTunnelNetworkSettings ok")
            guard let self = self else { return }
            if mode == "olc" {
                self.startOlcTunnel(conf: conf, completionHandler: completionHandler)
            } else {
                self.startVlessTunnel(conf: conf, completionHandler: completionHandler)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason,
                             completionHandler: @escaping () -> Void) {
        ExtLog.write("stopTunnel: reason=\(reason.rawValue)")
        var err: NSError?
        SingboxmobileStop(&err)
        if olcActive { MobileStop(); olcActive = false }
        completionHandler()
    }

    // MARK: - VLESS (наш сервер по токену)

    private func startVlessTunnel(conf: [String: Any], completionHandler: @escaping (Error?) -> Void) {
        let token = (conf["token"] as? String) ?? ""
        let server = (conf["server"] as? String) ?? ""
        let split = (conf["split"] as? Bool) ?? true
        guard !token.isEmpty else {
            ExtLog.write("vless: no token")
            completionHandler(NSError(domain: "ReedVPN", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "no token"]))
            return
        }
        ExtLog.write("vless: fetching config server=\(server)…")
        // Скачиваем sing-box конфиг (tun-inbound) и запускаем ядро на TUN этого extension.
        fetchConfig(token: token, server: server, split: split) { [weak self] configJson in
            guard let self = self else { return }
            guard let configJson = configJson else {
                ExtLog.write("vless: config fetch FAILED")
                completionHandler(NSError(domain: "ReedVPN", code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "config fetch failed"]))
                return
            }
            let fd = self.tunnelFileDescriptor()
            guard fd >= 0 else {
                ExtLog.write("vless: tun fd NOT FOUND")
                completionHandler(NSError(domain: "ReedVPN", code: 3,
                    userInfo: [NSLocalizedDescriptionKey: "tun fd not found"]))
                return
            }
            ExtLog.write("vless: starting sing-box on tun fd=\(fd)…")
            var nsErr: NSError?
            let ok = SingboxmobileStartTun(configJson, fd, &nsErr)
            ExtLog.write(ok ? "vless: TUNNEL UP" : "vless: StartTun FAILED: \(nsErr?.localizedDescription ?? "?")")
            completionHandler(ok ? nil : nsErr)
        }
    }

    // MARK: - LTE (olcRTC внутри extension)

    private func startOlcTunnel(conf: [String: Any], completionHandler: @escaping (Error?) -> Void) {
        let carrier = (conf["carrier"] as? String) ?? ""
        let transport = (conf["transport"] as? String) ?? "datachannel"
        let room = (conf["room"] as? String) ?? ""
        let clientId = (conf["clientId"] as? String) ?? ""
        let keyHex = (conf["keyHex"] as? String) ?? ""
        let split = (conf["split"] as? Bool) ?? true
        let olcPort = (conf["olcPort"] as? Int) ?? 10861
        let vp8Fps = (conf["vp8Fps"] as? Int) ?? 0
        let vp8Batch = (conf["vp8Batch"] as? Int) ?? 0
        guard !room.isEmpty, !keyHex.isEmpty else {
            completionHandler(NSError(domain: "ReedVPN", code: 4,
                userInfo: [NSLocalizedDescriptionKey: "no olc room/key"]))
            return
        }

        // 1. Движок olcRTC → локальный SOCKS5 (127.0.0.1:olcPort) с авторизацией.
        ExtLog.write("olc: starting engine carrier=\(carrier) room=\(room) port=\(olcPort)")
        MobileSetProviders()
        MobileSetTransport(transport)
        MobileSetDNS("1.1.1.1:53")
        MobileSetVP8Options(vp8Fps, vp8Batch)
        if MobileIsRunning() { MobileStop() }
        var err: NSError?
        let started = MobileStartWithTransport(carrier, transport, room, clientId, keyHex,
                                               olcPort, olcSocksUser, olcSocksPass, &err)
        guard started else {
            ExtLog.write("olc: MobileStartWithTransport FAILED: \(err?.localizedDescription ?? "?")")
            completionHandler(err ?? NSError(domain: "ReedVPN", code: 5,
                userInfo: [NSLocalizedDescriptionKey: "olcRTC start failed"]))
            return
        }
        ExtLog.write("olc: engine started, waiting ready (25s)…")
        // Бюджет старта 25с — рукопожатие к Jitsi 5–7с (паритет с приложением и Android).
        let ready = MobileWaitReady(25_000, &err)
        guard ready else {
            ExtLog.write("olc: WaitReady FAILED: \(err?.localizedDescription ?? "?")")
            MobileStop()
            completionHandler(err ?? NSError(domain: "ReedVPN", code: 6,
                userInfo: [NSLocalizedDescriptionKey: "olcRTC start timed out"]))
            return
        }
        olcActive = true
        ExtLog.write("olc: engine READY, fetching sing-box config…")

        // 2. sing-box tun→socks(olcRTC) со split-РФ. Инъектим креды в socks-outbound.
        fetchOlcConfig(olcPort: olcPort, split: split) { [weak self] rawConfig in
            guard let self = self else { return }
            guard let raw = rawConfig else {
                ExtLog.write("olc: config fetch FAILED (no network/cache)")
                MobileStop(); self.olcActive = false
                completionHandler(NSError(domain: "ReedVPN", code: 7,
                    userInfo: [NSLocalizedDescriptionKey: "olc config fetch failed"]))
                return
            }
            let configJson = self.injectOlcAuth(raw, user: self.olcSocksUser, pass: self.olcSocksPass)
            let fd = self.tunnelFileDescriptor()
            guard fd >= 0 else {
                ExtLog.write("olc: tun fd NOT FOUND")
                MobileStop(); self.olcActive = false
                completionHandler(NSError(domain: "ReedVPN", code: 3,
                    userInfo: [NSLocalizedDescriptionKey: "tun fd not found"]))
                return
            }
            ExtLog.write("olc: starting sing-box on tun fd=\(fd)…")
            var nsErr: NSError?
            let ok = SingboxmobileStartTun(configJson, fd, &nsErr)
            if !ok {
                ExtLog.write("olc: SingboxmobileStartTun FAILED: \(nsErr?.localizedDescription ?? "?")")
                MobileStop(); self.olcActive = false
            } else {
                ExtLog.write("olc: TUNNEL UP (sing-box running on tun)")
            }
            completionHandler(ok ? nil : nsErr)
        }
    }

    /// Добавляет username/password в socks-outbound (tag=proxy) конфига olcsingbox —
    /// движок olcRTC поднят с этими же кредами. Если распарсить не удалось — отдаём как есть.
    private func injectOlcAuth(_ configJson: String, user: String, pass: String) -> String {
        guard let data = configJson.data(using: .utf8),
              var root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              var outbounds = root["outbounds"] as? [[String: Any]] else {
            return configJson
        }
        for i in outbounds.indices {
            if (outbounds[i]["tag"] as? String) == "proxy" &&
               (outbounds[i]["type"] as? String) == "socks" {
                outbounds[i]["username"] = user
                outbounds[i]["password"] = pass
            }
        }
        root["outbounds"] = outbounds
        guard let out = try? JSONSerialization.data(withJSONObject: root),
              let s = String(data: out, encoding: .utf8) else { return configJson }
        return s
    }

    /// Дескриптор utun-интерфейса этого extension (getsockopt UTUN_OPT_IFNAME — приём WireGuard-Apple).
    private func tunnelFileDescriptor() -> Int {
        var buf = [CChar](repeating: 0, count: Int(IFNAMSIZ))
        for fd: Int32 in 0..<1024 {
            var len = socklen_t(buf.count)
            let ret = getsockopt(fd, 2 /* SYSPROTO_CONTROL */, 2 /* UTUN_OPT_IFNAME */, &buf, &len)
            if ret == 0, String(cString: buf).hasPrefix("utun") {
                return Int(fd)
            }
        }
        return -1
    }

    // MARK: - Конфиги (cache-first: на «зарезанных» сетях РФ наш API недоступен → отдаём кэш сразу)

    /// VLESS: /app/singbox?token=&server=&split=&inbound=tun
    private func fetchConfig(token: String, server: String, split: Bool,
                             completion: @escaping (String?) -> Void) {
        var comps = URLComponents(string: "\(apiBase)/app/singbox")!
        comps.queryItems = [
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "server", value: server),
            URLQueryItem(name: "split", value: split ? "1" : "0"),
            URLQueryItem(name: "inbound", value: "tun"),
        ]
        cacheFirstGet(url: comps.url!, cacheURL: configCacheURL(server: server, split: split),
                      completion: completion)
    }

    /// LTE: /app/olcsingbox?inbound=tun&olc_port=&split=  (split-РФ поверх локального olcRTC).
    private func fetchOlcConfig(olcPort: Int, split: Bool, completion: @escaping (String?) -> Void) {
        var comps = URLComponents(string: "\(apiBase)/app/olcsingbox")!
        comps.queryItems = [
            URLQueryItem(name: "inbound", value: "tun"),
            URLQueryItem(name: "olc_port", value: String(olcPort)),
            URLQueryItem(name: "split", value: split ? "1" : "0"),
        ]
        cacheFirstGet(url: comps.url!, cacheURL: olcConfigCacheURL(split: split),
                      completion: completion)
    }

    private func cacheFirstGet(url: URL, cacheURL: URL, completion: @escaping (String?) -> Void) {
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
            httpGet { _ in }          // свежий конфиг обновляем в фоне для следующего раза
            completion(cached)
            return
        }
        httpGet(completion)
    }

    private func configCacheURL(server: String, split: Bool) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let safe = String(server.unicodeScalars.map {
            CharacterSet.alphanumerics.contains($0) ? Character($0) : "_"
        })
        return dir.appendingPathComponent("singbox_cache_\(safe)_\(split ? "1" : "0").json")
    }

    private func olcConfigCacheURL(split: Bool) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("olcsingbox_tun_\(split ? "1" : "0").json")
    }
}
