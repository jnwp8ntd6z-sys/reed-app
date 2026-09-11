import NetworkExtension
import Foundation
import Security
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
            // Ротация: heartbeat пишет каждые 20 с, без неё файл рос бесконечно.
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
            if reset || size > 512_000 {
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
    // Периодический "пульс" в лог, пока туннель предположительно жив — раньше между
    // TUNNEL UP и stopTunnel лог молчал даже если внутри что-то ломалось (обрыв, DNS,
    // зависший процесс). Пишет реальное состояние движка, а не просто "тишина=норм".
    private var heartbeatTimer: DispatchSourceTimer?
    private let heartbeatIntervalSec: Int = 20
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

        if mode == "olc" {
            applyTunnelSettings(completionHandler: completionHandler) { [weak self] in
                self?.startOlcTunnel(conf: conf, completionHandler: completionHandler)
            }
        } else {
            // VLESS: конфиг получаем ДО подъёма TUN. После setTunnelNetworkSettings системный
            // DNS (8.8.8.8/1.1.1.1) уже уходит в TUN, который ещё никто не читает → запрос
            // конфига висел, «Подключение…» бесконечно (0 запросов /app/singbox на сервере).
            startVlessTunnel(conf: conf, completionHandler: completionHandler)
        }
    }

    /// Сетевые настройки TUN (общие для обоих режимов).
    private func applyTunnelSettings(completionHandler: @escaping (Error?) -> Void,
                                     then next: @escaping () -> Void) {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4 = NEIPv4Settings(addresses: ["172.19.0.1"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]   // весь трафик в TUN; split делает sing-box
        settings.ipv4Settings = ipv4
        settings.dnsSettings = NEDNSSettings(servers: ["8.8.8.8", "1.1.1.1"])
        // Как у tun-inbound в конфиге с сервера (mtu 1500). Было 9000: система отдавала в TUN
        // пакеты крупнее, чем читает sing-box → «подключено, но тяжёлое не грузится».
        settings.mtu = 1500

        setTunnelNetworkSettings(settings) { error in
            if let error = error {
                ExtLog.write("setTunnelNetworkSettings FAILED: \(error.localizedDescription)")
                completionHandler(error); return
            }
            ExtLog.write("setTunnelNetworkSettings ok")
            next()
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason,
                             completionHandler: @escaping () -> Void) {
        ExtLog.write("stopTunnel: reason=\(reason.rawValue)")
        stopHeartbeat()
        var err: NSError?
        SingboxmobileStop(&err)
        if olcActive { MobileStop(); olcActive = false }
        completionHandler()
    }

    /// Пишет в ExtLog реальное состояние движка каждые heartbeatIntervalSec, пока туннель
    /// должен быть активен. Без этого между "TUNNEL UP" и "stopTunnel" в логе тишина —
    /// не отличить "всё тихо-хорошо" от "давно упало, просто никто не заметил".
    private func startHeartbeat() {
        stopHeartbeat()
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + .seconds(heartbeatIntervalSec),
                        repeating: .seconds(heartbeatIntervalSec))
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            let singboxUp = SingboxmobileIsRunning()
            // olc_running добавляем в лог ТОЛЬКО в LTE-режиме (olcActive) — в обычном VLESS
            // движка olcRTC вообще нет, писать "olc_running=true" как заглушку вводило в
            // заблуждение (выглядело так, будто olcRTC зачем-то работает и в VLESS-режиме).
            if self.olcActive {
                let olcUp = MobileIsRunning()
                ExtLog.write("heartbeat: singbox_running=\(singboxUp) olc_running=\(olcUp)")
            } else {
                ExtLog.write("heartbeat: singbox_running=\(singboxUp)")
            }
            // Ядро упало на ходу (напр. jetsam/OOM в лимите памяти NE), а маршруты ещё
            // заворачивают весь трафик в мёртвый TUN → «инет отрубило». Гасим туннель, чтобы
            // система вернула обычный интернет вместо чёрной дыры.
            if !singboxUp && !self.olcActive {
                ExtLog.write("heartbeat: sing-box DOWN → снимаем туннель (восстановить инет)")
                self.stopHeartbeat()
                self.cancelTunnelWithError(NSError(domain: "ReedVPN", code: 9,
                    userInfo: [NSLocalizedDescriptionKey: "sing-box stopped"]))
            }
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private func stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
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
        resolveVlessConfig(token: token, server: server, split: split) { [weak self] configJson, fromCache in
            guard let self = self else { return }
            guard let configJson = configJson else {
                ExtLog.write("vless: no config (cache empty, fetch failed)")
                completionHandler(NSError(domain: "ReedVPN", code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "config fetch failed"]))
                return
            }
            self.applyTunnelSettings(completionHandler: completionHandler) { [weak self] in
                guard let self = self else { return }
                let fd = self.tunnelFileDescriptor()
                guard fd >= 0 else {
                    ExtLog.write("vless: tun fd NOT FOUND")
                    completionHandler(NSError(domain: "ReedVPN", code: 3,
                        userInfo: [NSLocalizedDescriptionKey: "tun fd not found"]))
                    return
                }
                ExtLog.write("vless: starting sing-box on tun fd=\(fd)…")
                var nsErr: NSError?
                if SingboxmobileIsRunning() { SingboxmobileStop(&nsErr); nsErr = nil }
                let ok = SingboxmobileStartTun(configJson, fd, &nsErr)
                ExtLog.write(ok ? "vless: TUNNEL UP" : "vless: StartTun FAILED: \(nsErr?.localizedDescription ?? "?")")
                if ok {
                    self.startHeartbeat()
                    // Конфиг был из кэша → тихо обновляем его уже через поднятый туннель.
                    if fromCache { self.refreshVlessConfigInBackground(token: token, server: server, split: split) }
                    completionHandler(nil)
                } else {
                    // Ядро не поднялось (битый конфиг/covert/OOM). Маршруты уже применены —
                    // ВЕСЬ трафик телефона сейчас проваливается в мёртвый TUN («отрубает инет»).
                    // Снимаем настройки туннеля → система возвращает обычный интернет, и только
                    // потом рапортуем ошибку. Иначе инета нет, пока юзер не выключит VPN вручную.
                    self.setTunnelNetworkSettings(nil) { _ in completionHandler(nsErr) }
                }
            }
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
            if SingboxmobileIsRunning() { SingboxmobileStop(&nsErr); nsErr = nil }
            let ok = SingboxmobileStartTun(configJson, fd, &nsErr)
            if !ok {
                ExtLog.write("olc: SingboxmobileStartTun FAILED: \(nsErr?.localizedDescription ?? "?")")
                MobileStop(); self.olcActive = false
            } else {
                ExtLog.write("olc: TUNNEL UP (sing-box running on tun)")
                self.startHeartbeat()
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

    /// VLESS-конфиг для старта: 1) App Group (кладёт приложение по «Обновить»/при запуске),
    /// 2) старый кэш extension, 3) сеть — ДО подъёма TUN, с таймаутом. completion(config, fromCache).
    private func resolveVlessConfig(token: String, server: String, split: Bool,
                                    completion: @escaping (String?, Bool) -> Void) {
        if let dir = TunnelConfigStore.directory(),
           let data = try? Data(contentsOf: TunnelConfigStore.fileURL(dir: dir, server: server, split: split)),
           TunnelConfigStore.isValidConfig(data), let s = String(data: data, encoding: .utf8) {
            ExtLog.write("vless: config from App Group cache server=\(server) split=\(split)")
            completion(s, true); return
        }
        if let data = try? Data(contentsOf: configCacheURL(server: server, split: split)),
           TunnelConfigStore.isValidConfig(data), let s = String(data: data, encoding: .utf8) {
            ExtLog.write("vless: config from legacy extension cache server=\(server)")
            completion(s, true); return
        }
        ExtLog.write("vless: no cache, fetching config BEFORE tun server=\(server)…")
        TunnelConfigStore.fetchConfig(token: token, server: server, split: split, timeout: 10) { body, via in
            guard let body = body, let s = String(data: body, encoding: .utf8) else {
                ExtLog.write("vless: config fetch FAILED \(via)")
                completion(nil, false); return
            }
            if let dir = TunnelConfigStore.directory() {
                try? body.write(to: TunnelConfigStore.fileURL(dir: dir, server: server, split: split), options: .atomic)
            }
            ExtLog.write("vless: config fetched via \(via) (\(body.count) B) and cached")
            completion(s, false)
        }
    }

    /// Фоновое обновление кэша после TUNNEL UP (best-effort, результат — только на следующий старт).
    private func refreshVlessConfigInBackground(token: String, server: String, split: Bool) {
        guard let dir = TunnelConfigStore.directory() else { return }
        let target = TunnelConfigStore.fileURL(dir: dir, server: server, split: split)
        TunnelConfigStore.fetchConfig(token: token, server: server, split: split, timeout: 20) { body, via in
            guard let body = body else { return }
            try? body.write(to: target, options: .atomic)
            ExtLog.write("vless: background config refresh via \(via) ok (\(body.count) B)")
        }
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
            let task = URLSession.shared.dataTask(with: url) { data, response, _ in
                guard let body = TunnelConfigStore.validBody(data: data, response: response),
                      let s = String(data: body, encoding: .utf8) else {
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

/// Кэш tun-конфигов sing-box в App Group — общий с приложением.
/// ВАЖНО: имена файлов и проверка тела должны совпадать с TunnelConfigStore в
/// iosApp/SwiftSingBoxManager.swift (разные таргеты, общий код не подключён).
enum TunnelConfigStore {
    static let appGroup = "group.ru.reedapp.app"
    static let apiHost = "reedapp.ru"

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


    static let frontIP = "135.106.182.198"

    /// Конфиг: сначала https://reedapp.ru, при сбое — напрямую на IP РФ-фронта (DNS-кэш провайдера
    /// со старым зарубежным IP / ТСПУ). completion(body, "host"|"front"|"fail http=… err=…").
    static func fetchConfig(token: String, server: String, split: Bool, timeout: TimeInterval,
                            completion: @escaping (Data?, String) -> Void) {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout * 2
        let session = URLSession(configuration: cfg, delegate: ReedFrontTrustDelegate(), delegateQueue: nil)
        guard let primary = configURL(token: token, server: server, split: split, host: apiHost),
              let fallback = configURL(token: token, server: server, split: split, host: frontIP) else {
            session.finishTasksAndInvalidate(); completion(nil, "fail bad url"); return
        }
        session.dataTask(with: primary) { data, response, error in
            if let body = validBody(data: data, response: response) {
                session.finishTasksAndInvalidate(); completion(body, "host"); return
            }
            let code1 = (response as? HTTPURLResponse)?.statusCode ?? 0
            let err1 = error?.localizedDescription ?? "-"
            session.dataTask(with: fallback) { data2, response2, error2 in
                session.finishTasksAndInvalidate()
                if let body = validBody(data: data2, response: response2) {
                    completion(body, "front"); return
                }
                let code2 = (response2 as? HTTPURLResponse)?.statusCode ?? 0
                completion(nil, "fail host http=\(code1) err=\(err1); front http=\(code2) err=\(error2?.localizedDescription ?? "-")")
            }.resume()
        }.resume()
    }

    static func configURL(token: String, server: String, split: Bool, host: String = apiHost) -> URL? {
        var comps = URLComponents(string: "https://\(host)/app/singbox")
        comps?.queryItems = [
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "server", value: server),
            URLQueryItem(name: "split", value: split ? "1" : "0"),
            URLQueryItem(name: "inbound", value: "tun"),
        ]
        return comps?.url
    }

    /// JSON-объект разумного размера (а не тело ошибки «expired»/HTML).
    static func isValidConfig(_ data: Data) -> Bool {
        guard data.count > 100 else { return false }
        let first = data.first { !($0 == 0x20 || $0 == 0x0A || $0 == 0x0D || $0 == 0x09) }
        return first == UInt8(ascii: "{")
    }

    /// Только HTTP 200 с валидным конфигом.
    static func validBody(data: Data?, response: URLResponse?) -> Data? {
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let data = data, isValidConfig(data) else { return nil }
        return data
    }
}

/// TLS к IP РФ-фронта: системная проверка цепочки, но на имя reedapp.ru (сертификат бота).
/// Для любых других хостов — стандартная обработка.
final class ReedFrontTrustDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let space = challenge.protectionSpace
        guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              space.host == TunnelConfigStore.frontIP,
              let trust = space.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, TunnelConfigStore.apiHost as CFString))
        var error: CFError?
        if SecTrustEvaluateWithError(trust, &error) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
