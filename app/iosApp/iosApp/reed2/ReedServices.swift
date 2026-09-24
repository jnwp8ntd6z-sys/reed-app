import Foundation
import Network

// MARK: - Сессия (те же ключи UserDefaults, что у прежней версии — обновление не разлогинивает)

enum ReedSessionStore {
    private static let d = UserDefaults.standard

    static var token: String? {
        get { d.string(forKey: "reed_token") }
        set { if let v = newValue { d.set(v, forKey: "reed_token") } else { d.removeObject(forKey: "reed_token") } }
    }
    static var onboardingDone: Bool {
        get { d.string(forKey: "reed_onboarding_done") == "1" }
        set { flag("reed_onboarding_done", newValue) }
    }
    static var consentAccepted: Bool {
        get { d.string(forKey: "reed_consent_accepted") == "1" }
        set { flag("reed_consent_accepted", newValue) }
    }
    static var joinedViaCode: Bool {
        get { d.string(forKey: "reed_joined_via_code") == "1" }
        set { flag("reed_joined_via_code", newValue) }
    }
    static var memberName: String? {
        get { d.string(forKey: "reed_member_name") }
        set { if let v = newValue { d.set(v, forKey: "reed_member_name") } else { d.removeObject(forKey: "reed_member_name") } }
    }
    static var noCodeMode: Bool {
        get { d.string(forKey: "reed_no_code_mode") == "1" }
        set { flag("reed_no_code_mode", newValue) }
    }
    /// Российские сайты напрямую (по умолчанию включено).
    static var splitRouting: Bool {
        get { d.string(forKey: "reed_split_routing") != "0" }
        set { d.set(newValue ? "1" : "0", forKey: "reed_split_routing") }
    }
    static var autoConnect: Bool {
        get { d.string(forKey: "reed_autoconnect") == "1" }
        set { flag("reed_autoconnect", newValue) }
    }
    static var notifSeenMaxId: Int {
        get { Int(d.string(forKey: "reed_notif_seen_max_id") ?? "") ?? 0 }
        set { d.set(String(newValue), forKey: "reed_notif_seen_max_id") }
    }
    static var newDeviceAckMaxId: Int {
        get { Int(d.string(forKey: "reed_newdev_ack_max_id") ?? "") ?? 0 }
        set { d.set(String(newValue), forKey: "reed_newdev_ack_max_id") }
    }
    static var serverNetwork: String? {
        get { d.string(forKey: "reed_server_network") }
        set { d.set(newValue, forKey: "reed_server_network") }
    }
    static var selectedServer: String? {
        get { d.string(forKey: "reed2_selected_server") }
        set { d.set(newValue, forKey: "reed2_selected_server") }
    }
    static var subscriptionCache: Data? {
        get { d.string(forKey: "reed_sub_cache")?.data(using: .utf8) }
        set { d.set(newValue.flatMap { String(data: $0, encoding: .utf8) }, forKey: "reed_sub_cache") }
    }
    static var directServices: [String] {
        get { d.stringArray(forKey: "reed2_direct_services") ?? [] }
        set { d.set(newValue, forKey: "reed2_direct_services") }
    }

    /// Чат поддержки: последний прочитанный ответ и «человек уже писал» (тогда проверяем ответы в фоне).
    static var supportSeenMaxId: Int {
        get { d.integer(forKey: "reed2_support_seen") }
        set { d.set(newValue, forKey: "reed2_support_seen") }
    }
    static var supportUsed: Bool {
        get { d.bool(forKey: "reed2_support_used") }
        set { d.set(newValue, forKey: "reed2_support_used") }
    }

    static func logout() {
        token = nil; joinedViaCode = false; memberName = nil; noCodeMode = false; onboardingDone = false
        selectedServer = nil; subscriptionCache = nil
    }

    private static func flag(_ k: String, _ v: Bool) { if v { d.set("1", forKey: k) } else { d.removeObject(forKey: k) } }

    /// HWID устройства — тот же файл, что у прежней версии (Application Support/Olcbox/device_identity),
    /// чтобы сервер узнал это устройство и не занял ещё один слот.
    static func hwid() -> String {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        let dir = base.appendingPathComponent("Olcbox", isDirectory: true)
        let file = dir.appendingPathComponent("device_identity")
        if let s = try? String(contentsOf: file, encoding: .utf8) {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty { return t }
        }
        let fresh = UUID().uuidString.lowercased()
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        try? fresh.write(to: file, atomically: true, encoding: .utf8)
        return fresh
    }
}

// MARK: - olcRTC (обход белых списков): olcrtc://<provider>?<transport>[<opts>]@<room>#<key>[%client][$name]

struct OlcRtcConfig: Sendable, Equatable {
    var name: String
    var provider: String
    var transport: String
    var room: String
    var key: String
    var vp8Fps: Int
    var vp8Batch: Int

    static func parseAll(_ text: String) -> [OlcRtcConfig] {
        text.split(whereSeparator: \.isNewline).compactMap { parse(String($0).trimmingCharacters(in: .whitespaces)) }
    }

    static func parse(_ line: String) -> OlcRtcConfig? {
        let prefix = "olcrtc://"
        guard line.hasPrefix(prefix) else { return nil }
        let p = Array(line.dropFirst(prefix.count))
        func idx(_ ch: Character, from: Int) -> Int? {
            guard from < p.count else { return nil }
            for i in from..<p.count where p[i] == ch { return i }
            return nil
        }
        guard let tm = idx("?", from: 0), tm > 0,
              let rm = idx("@", from: tm + 1),
              let km = idx("#", from: rm + 1) else { return nil }
        let cm = idx("%", from: km + 1)
        let mm = idx("$", from: km + 1)
        let keyEnd = [cm, mm].compactMap { $0 }.min() ?? p.count
        let provider = String(p[0..<tm]).trimmingCharacters(in: .whitespaces).lowercased()
        var transport = String(p[(tm + 1)..<rm]).trimmingCharacters(in: .whitespaces)
        var opts: [String: Int] = [:]
        if let o1 = transport.firstIndex(of: "<"), let o2 = transport.lastIndex(of: ">"), o1 < o2 {
            let inner = transport[transport.index(after: o1)..<o2]
            for part in inner.split(separator: "&") {
                let kv = part.split(separator: "=", maxSplits: 1)
                if kv.count == 2, let v = Int(kv[1].trimmingCharacters(in: .whitespaces)) {
                    opts[kv[0].trimmingCharacters(in: .whitespaces).lowercased()] = v
                }
            }
            transport = String(transport[..<o1]).trimmingCharacters(in: .whitespaces)
        }
        let room = String(p[(rm + 1)..<km]).trimmingCharacters(in: .whitespaces)
        let key = String(p[(km + 1)..<keyEnd]).trimmingCharacters(in: .whitespaces)
        let name = mm.map { String(p[($0 + 1)...]).trimmingCharacters(in: .whitespaces) } ?? ""
        guard !room.isEmpty, !key.isEmpty else { return nil }
        return OlcRtcConfig(
            name: name.isEmpty ? room : name, provider: provider, transport: transport.lowercased(),
            room: room, key: key,
            vp8Fps: opts["vp8-fps"] ?? opts["fps"] ?? 60,
            vp8Batch: opts["vp8-batch"] ?? opts["batch"] ?? 64)
    }
}

// MARK: - Пинг: время TCP-соединения до host:port (ICMP на iOS недоступен), медиана из 3

enum ReedPing {
    static func tcp(host: String, port: Int, attempts: Int = 3) async -> Int? {
        var results: [Int] = []
        for _ in 0..<attempts {
            if let ms = await once(host: host, port: port) { results.append(ms) }
        }
        guard !results.isEmpty else { return nil }
        return results.sorted()[results.count / 2]
    }

    private static func once(host: String, port: Int) async -> Int? {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return nil }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        let gate = OnceGate()
        let start = DispatchTime.now()
        return await withCheckedContinuation { (cont: CheckedContinuation<Int?, Never>) in
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if gate.fire() {
                        let ms = Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
                        conn.cancel(); cont.resume(returning: ms)
                    }
                case .failed, .cancelled:
                    if gate.fire() { conn.cancel(); cont.resume(returning: nil) }
                default: break
                }
            }
            conn.start(queue: .global(qos: .utility))
            DispatchQueue.global().asyncAfter(deadline: .now() + 3) {
                if gate.fire() { conn.cancel(); cont.resume(returning: nil) }
            }
        }
    }

    /// «Прямое» соединение: HEAD к госуслугам мимо нашей логики (время ответа, мс).
    static func directHttps(_ url: String = "https://www.gosuslugi.ru/") async -> Int? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u, timeoutInterval: 5)
        req.httpMethod = "HEAD"
        let t0 = DispatchTime.now()
        do {
            _ = try await URLSession.shared.data(for: req)
            return Int((DispatchTime.now().uptimeNanoseconds - t0.uptimeNanoseconds) / 1_000_000)
        } catch { return nil }
    }
}

/// Потокобезопасный «выстрелить один раз» (continuation резюмируем ровно один раз).
final class OnceGate: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func fire() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

// MARK: - Туннель (реализация — ReedSystemTunnel в приложении, мок — в превью-проекте)

enum ReedTunnelStatus: Sendable, Equatable { case disconnected, connecting, connected, disconnecting }

enum ReedTunnelTarget: Sendable {
    case vless(token: String, server: String)
    case olc(OlcRtcConfig, clientId: String)
}

@MainActor
protocol ReedTunnel: AnyObject {
    var status: ReedTunnelStatus { get }
    var connectedSince: Date? { get }
    /// Колбэк при каждом изменении статуса (на главном потоке).
    var onChange: (() -> Void)? { get set }
    func refresh() async
    func start(_ target: ReedTunnelTarget, split: Bool, direct: [String]) async -> Bool
    func stop()
    func setOnDemand(_ enabled: Bool) async
    func prewarm(token: String, servers: [String], split: Bool, direct: [String])
    func extensionLog() -> String?
}
