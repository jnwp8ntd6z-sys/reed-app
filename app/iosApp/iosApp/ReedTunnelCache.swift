import Foundation
import Security

/// Reed 2.0 — кэш tun-конфигов для PacketTunnel extension (без Kotlin/SharedUI).
/// Приложение заранее скачивает конфиги всех серверов в App Group, extension подключается
/// из кэша, не обращаясь к API (из extension качать ненадёжно — трафик уже в TUN).
enum ReedTunnelCache {
    /// Прогрев: скачать недостающие конфиги (блокирующий — вызывать вне главного потока).
    @discardableResult
    static func prewarm(token: String, servers: [String], split: Bool, direct: String, force: Bool) -> Int {
        guard let dir = TunnelConfigStore.directory() else { return 0 }
        let saved = PrewarmCounter()
        let group = DispatchGroup()
        for server in servers {
            let target = TunnelConfigStore.fileURL(dir: dir, server: server, split: split, direct: direct)
            if !force, FileManager.default.fileExists(atPath: target.path) { continue }
            group.enter()
            TunnelConfigStore.fetchConfig(token: token, server: server, split: split, direct: direct, timeout: 12) { body, _ in
                defer { group.leave() }
                guard let body = body else { return }
                if (try? body.write(to: target, options: .atomic)) != nil { saved.increment() }
            }
        }
        _ = group.wait(timeout: .now() + 90)
        return saved.value
    }

    /// Пошаговый лог extension из общего контейнера App Group (диагностика, «Экспорт логов»).
    static func extensionLog() -> String? {
        guard let dir = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: TunnelConfigStore.appGroup) else { return nil }
        return try? String(contentsOf: dir.appendingPathComponent("extension.log"), encoding: .utf8)
    }
}

/// Кэш tun-конфигов sing-box в App Group — общий с PacketTunnel extension.
/// ВАЖНО: имена файлов и проверка тела должны совпадать с TunnelConfigStore в
/// PacketTunnel/PacketTunnelProvider.swift (разные таргеты, общий код не подключён).
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

    static func fileURL(dir: URL, server: String, split: Bool, direct: String = "") -> URL {
        let safe = String(server.unicodeScalars.map {
            CharacterSet.alphanumerics.contains($0) ? Character($0) : "_"
        })
        // Reed 2.0: свой файл кэша для набора «Сервисов напрямую» (пусто — прежнее имя).
        let d = direct.isEmpty ? "" : "_d" + String(direct.utf8.reduce(UInt32(5381)) { ($0 &* 33) &+ UInt32($1) }, radix: 16)
        return dir.appendingPathComponent("singbox_\(safe)_\(split ? "1" : "0")\(d).json")
    }


    static let frontIP = "135.106.182.198"

    /// Конфиг: сначала https://reedapp.ru, при сбое — напрямую на IP РФ-фронта (DNS-кэш провайдера
    /// со старым зарубежным IP / ТСПУ). completion(body, "host"|"front"|"fail http=… err=…").
    static func fetchConfig(token: String, server: String, split: Bool, direct: String = "", timeout: TimeInterval,
                            completion: @escaping (Data?, String) -> Void) {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout * 2
        let session = URLSession(configuration: cfg, delegate: ReedFrontTrustDelegate(), delegateQueue: nil)
        // Фронт-IP ПЕРВЫМ: у РФ-провайдеров reedapp.ru часто в старом DNS-кэше (заблокирован),
        // ожидание его таймаута рвало загрузку (499). Московский IP доступен всегда → пробуем его,
        // reedapp.ru — как запасной (для сетей, где DNS уже указывает на Москву напрямую).
        guard let primary = configURL(token: token, server: server, split: split, direct: direct, host: frontIP),
              let fallback = configURL(token: token, server: server, split: split, direct: direct, host: apiHost) else {
            session.finishTasksAndInvalidate(); completion(nil, "fail bad url"); return
        }
        session.dataTask(with: primary) { data, response, error in
            if let body = validBody(data: data, response: response) {
                session.finishTasksAndInvalidate(); completion(body, "front"); return
            }
            let code1 = (response as? HTTPURLResponse)?.statusCode ?? 0
            let err1 = error?.localizedDescription ?? "-"
            session.dataTask(with: fallback) { data2, response2, error2 in
                session.finishTasksAndInvalidate()
                if let body = validBody(data: data2, response: response2) {
                    completion(body, "host"); return
                }
                let code2 = (response2 as? HTTPURLResponse)?.statusCode ?? 0
                completion(nil, "fail host http=\(code1) err=\(err1); front http=\(code2) err=\(error2?.localizedDescription ?? "-")")
            }.resume()
        }.resume()
    }

    static func configURL(token: String, server: String, split: Bool, direct: String = "", host: String = apiHost) -> URL? {
        var comps = URLComponents(string: "https://\(host)/app/singbox")
        var items = [
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "server", value: server),
            URLQueryItem(name: "split", value: split ? "1" : "0"),
            URLQueryItem(name: "inbound", value: "tun"),
        ]
        if !direct.isEmpty { items.append(URLQueryItem(name: "direct", value: direct)) }
        comps?.queryItems = items
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
