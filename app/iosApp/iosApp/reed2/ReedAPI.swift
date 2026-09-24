import Foundation

// MARK: - Модели ответов сервера (контракт /app/*, как в Kotlin ReedApi)

struct RSubscriptionResponse: Codable, Sendable {
    struct Profile: Codable, Sendable { var name: String?; var username: String? }
    struct SubInfo: Codable, Sendable {
        var status: String
        var plan_label: String?
        var plan_type: String?
        var expires_at: String?
        var seconds_left: Int64?
        var days_left: Int64?
    }
    struct Traffic: Codable, Sendable { var used: Int64?; var total: Int64? }
    struct Lte: Codable, Sendable { var used_gb: Double?; var total_gb: Double? }
    var profile: Profile?
    var subscription: SubInfo
    var traffic: Traffic?
    var lte: Lte?
}

struct RSubscriptionItem: Codable, Sendable, Identifiable {
    var sub_token: String
    var plan_label: String?
    var days_left: Int64?
    var current: Bool?
    var id: String { sub_token }
}
struct RSubscriptions: Codable, Sendable { var subscriptions: [RSubscriptionItem]? }

struct RLocation: Codable, Sendable {
    var key: String
    var tag: String?
    var name: String
    var transport: String
    var host: String?
    var port: Int?
    var country: String?
    var city: String?
    var network: String?
}
struct RLocations: Codable, Sendable { var locations: [RLocation]?; var member_blocked: Bool? }

struct RCodeLoginResponse: Codable, Sendable {
    var ok: Bool?; var error: String?; var message: String?; var sub_token: String?
}
struct RRedeemResult: Codable, Sendable {
    var ok: Bool?; var kind: String?; var member_token: String?; var sub_token: String?
    var name: String?; var error: String?; var message: String?
}
struct RLinkLoginResult: Codable, Sendable { var ok: Bool?; var token: String?; var error: String?; var message: String? }
struct RShareCreateResult: Codable, Sendable { var ok: Bool?; var code: String?; var error: String? }
struct ROk: Codable, Sendable { var ok: Bool?; var error: String? }

struct RDevice: Codable, Sendable, Identifiable {
    var id: Int; var name: String?; var os: String?; var type: String?; var last_seen: String?; var blocked: Bool?
}
struct RDevices: Codable, Sendable { var devices: [RDevice]?; var blocked: [RDevice]?; var used: Int?; var limit: Int? }

struct RMember: Codable, Sendable, Identifiable {
    var id: Int; var name: String?; var device: String?; var blocked: Bool?
}
struct RMembers: Codable, Sendable { var members: [RMember]?; var count: Int?; var limit: Int? }

struct RNotification: Codable, Sendable, Identifiable {
    var id: Int; var title: String?; var body: String?; var created_at: String?; var kind: String?
    var data: [String: RJSONValue]?
    var deviceId: Int? {
        if case .number(let n)? = data?["device_id"] { return Int(n) }
        if case .string(let s)? = data?["device_id"] { return Int(s) }
        return nil
    }
}
struct RNotifications: Codable, Sendable { var notifications: [RNotification]? }

struct RSession: Codable, Sendable { var kind: String?; var blocked: Bool?; var member_status: String? }
struct RSupportMessage: Codable, Sendable, Identifiable, Equatable {
    var id: Int; var direction: String; var text: String; var created_at: String?
    var isMine: Bool { direction == "in" }
}
struct RSupportMessages: Codable, Sendable { var messages: [RSupportMessage]? }
struct RSupportSendResult: Codable, Sendable { var ok: Bool?; var message: RSupportMessage?; var error: String?; var hint: String? }

/// Минимальное JSON-значение (для поля data уведомлений).
enum RJSONValue: Codable, Sendable {
    case string(String), number(Double), bool(Bool), null
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else { self = .null }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .null: try c.encodeNil()
        }
    }
}

enum ReedAPIError: Error, Sendable { case network, badStatus(Int), decode }

// MARK: - Клиент

/// Клиент API Reed (reedapp.ru/app/*). Нативный URLSession, короткие таймауты (белые списки:
/// лучше быстро упасть и показать кэш, чем висеть). Если reedapp.ru не ответил — повтор на
/// IP РФ-фронта с проверкой сертификата на имя reedapp.ru (как ReedFront в Kotlin-версии).
final class ReedAPI: NSObject, Sendable {
    static let shared = ReedAPI()
    static let host = "reedapp.ru"
    static let frontIP = "135.106.182.198"

    private let session: URLSession
    private let frontSession: URLSession

    private override init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 8
        cfg.timeoutIntervalForResource = 12
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)
        frontSession = URLSession(configuration: cfg, delegate: FrontTrustDelegate(), delegateQueue: nil)
        super.init()
    }

    // MARK: транспорт

    private func request(_ path: String, query: [String: String] = [:], body: [String: any Sendable]? = nil,
                         host: String) -> URLRequest {
        var comps = URLComponents()
        comps.scheme = "https"; comps.host = host; comps.path = path
        if !query.isEmpty { comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) } }
        var req = URLRequest(url: comps.url!)
        if let body {
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return req
    }

    private func raw(_ path: String, query: [String: String] = [:], body: [String: any Sendable]? = nil) async throws -> (Data, Int) {
        // Московский фронт ПЕРВЫМ (как в расширении туннеля): у РФ-провайдеров reedapp.ru часто
        // в старом DNS-кэше, и ожидание его таймаута тормозит вход. reedapp.ru — запасной путь.
        do {
            let (d, r) = try await frontSession.data(for: request(path, query: query, body: body, host: ReedAPI.frontIP))
            return (d, (r as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            do {
                let (d, r) = try await session.data(for: request(path, query: query, body: body, host: ReedAPI.host))
                return (d, (r as? HTTPURLResponse)?.statusCode ?? 0)
            } catch { throw ReedAPIError.network }
        }
    }

    private func get<T: Decodable & Sendable>(_ path: String, _ query: [String: String] = [:], as: T.Type) async throws -> T {
        let (d, code) = try await raw(path, query: query)
        guard (200..<500).contains(code) else { throw ReedAPIError.badStatus(code) }
        do { return try JSONDecoder().decode(T.self, from: d) } catch { throw ReedAPIError.decode }
    }

    private func post<T: Decodable & Sendable>(_ path: String, _ body: [String: any Sendable], as: T.Type) async throws -> T {
        let (d, code) = try await raw(path, body: body)
        guard (200..<500).contains(code) else { throw ReedAPIError.badStatus(code) }
        do { return try JSONDecoder().decode(T.self, from: d) } catch { throw ReedAPIError.decode }
    }

    // MARK: эндпоинты

    func subscriptionRaw(_ token: String) async throws -> Data {
        let (d, code) = try await raw("/app/subscription", query: ["token": token])
        guard code == 200 else { throw ReedAPIError.badStatus(code) }
        return d
    }
    func subscriptions(_ t: String) async throws -> RSubscriptions { try await get("/app/subscriptions", ["token": t], as: RSubscriptions.self) }
    func locations(_ t: String) async throws -> RLocations { try await get("/app/locations", ["token": t], as: RLocations.self) }
    func olcconf(_ t: String) async throws -> String {
        let (d, code) = try await raw("/app/olcconf", query: ["token": t])
        guard code == 200 else { throw ReedAPIError.badStatus(code) }
        return String(data: d, encoding: .utf8) ?? ""
    }
    func codeLogin(_ code: String, hwid: String, model: String) async throws -> RCodeLoginResponse {
        try await post("/app/code/login", ["code": code, "hwid": hwid, "name": "", "device_model": model, "device_os": "iOS"], as: RCodeLoginResponse.self)
    }
    func redeem(_ code: String, name: String, hwid: String, model: String) async throws -> RRedeemResult {
        try await post("/app/share/redeem", ["code": code, "name": name, "hwid": hwid, "device_model": model, "device_os": "iOS"], as: RRedeemResult.self)
    }
    func linkLogin(_ t: String) async throws -> RLinkLoginResult { try await post("/app/link/login", ["t": t], as: RLinkLoginResult.self) }
    func shareCreate(_ t: String, type: String) async throws -> RShareCreateResult {
        try await post("/app/share/create", ["token": t, "type": type], as: RShareCreateResult.self)
    }
    func devices(_ t: String) async throws -> RDevices { try await get("/app/devices", ["token": t], as: RDevices.self) }
    func deviceAction(_ t: String, id: Int, action: String) async throws -> ROk {
        try await post("/app/device/action", ["token": t, "device_id": id, "action": action], as: ROk.self)
    }
    func members(_ t: String) async throws -> RMembers { try await get("/app/members", ["token": t], as: RMembers.self) }
    func memberAction(_ t: String, id: Int, action: String) async throws -> ROk {
        try await post("/app/member/action", ["token": t, "member_id": id, "action": action], as: ROk.self)
    }
    func notifications(_ t: String) async throws -> RNotifications { try await get("/app/notifications", ["token": t], as: RNotifications.self) }
    func supportSend(token: String?, hwid: String, text: String) async throws -> RSupportSendResult {
        try await post("/app/support/send", ["token": token ?? "", "hwid": hwid, "text": text,
                                             "platform": "iOS", "device": "iPhone"], as: RSupportSendResult.self)
    }
    func supportMessages(token: String?, hwid: String, after: Int) async throws -> RSupportMessages {
        try await get("/app/support/messages", ["token": token ?? "", "hwid": hwid, "after": String(after)], as: RSupportMessages.self)
    }
    func deleteAccount(_ t: String) async throws -> ROk { try await post("/app/account/delete", ["token": t], as: ROk.self) }
    func session(_ t: String) async -> RSession? {
        guard let (d, code) = try? await raw("/app/session", query: ["token": t]) else { return nil }
        if code == 404 { return RSession(kind: "deleted", blocked: nil, member_status: nil) }
        return try? JSONDecoder().decode(RSession.self, from: d)
    }
}

/// TLS к IP РФ-фронта: сертификат проверяем строго на имя reedapp.ru.
private final class FrontTrustDelegate: NSObject, URLSessionDelegate, Sendable {
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil); return
        }
        SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, ReedAPI.host as CFString))
        var err: CFError?
        if SecTrustEvaluateWithError(trust, &err) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
