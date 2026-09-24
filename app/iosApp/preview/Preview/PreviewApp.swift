import SwiftUI

/// Превью Reed 2.0 для симулятора: сцена задаётся переменной окружения REED_SCENE.
/// Статичные сцены — для скриншотов, anim_* — сценарии для записи видео анимаций.
@main
struct PreviewApp: App {
    @StateObject private var model: ReedAppModel
    private let scene: String
    private let tunnel: PreviewTunnel

    init() {
        let s = ProcessInfo.processInfo.environment["REED_SCENE"] ?? "home_off"
        scene = s
        PreviewSeed.resetDefaults(s)
        let t = PreviewTunnel()
        PreviewSeed.prepareTunnel(s, t)
        let m = ReedAppModel(tunnel: t)
        m.live = false
        PreviewSeed.apply(s, m)
        tunnel = t
        _model = StateObject(wrappedValue: m)
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if scene == "services" {
                    ReedServicesDirectView()
                } else {
                    ReedRootView()
                }
            }
            .environmentObject(model)
            .preferredColorScheme(.dark)
            .task { await PreviewScript.run(scene, model, tunnel) }
        }
    }
}

/// Туннель-заглушка: подключение ~2,4 с (olcRTC — 4 с), отключение 0,5 с.
@MainActor
final class PreviewTunnel: ReedTunnel {
    private(set) var status: ReedTunnelStatus = .disconnected
    private(set) var connectedSince: Date?
    var onChange: (() -> Void)?

    func force(_ s: ReedTunnelStatus, since: Date? = nil) {
        status = s
        connectedSince = s == .connected ? (since ?? Date()) : nil
        onChange?()
    }

    func refresh() async {}

    func start(_ target: ReedTunnelTarget, split: Bool, direct: [String]) async -> Bool {
        force(.connecting)
        var delay: UInt64 = 2_400_000_000
        if case .olc = target { delay = 4_000_000_000 }
        try? await Task.sleep(nanoseconds: delay)
        guard status == .connecting else { return false }
        force(.connected)
        return true
    }

    func stop() {
        guard status != .disconnected else { return }
        force(.disconnecting)
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 500_000_000)
            self.force(.disconnected)
        }
    }

    func setOnDemand(_ enabled: Bool) async {}
    func prewarm(token: String, servers: [String], split: Bool, direct: [String]) {}
    func extensionLog() -> String? { "preview" }
}

@MainActor
enum PreviewSeed {
    static func resetDefaults(_ scene: String) {
        if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        let loggedOut = scene.hasPrefix("login") || scene == "codes" || scene == "codes_family" || scene == "anim_login"
        ReedSessionStore.onboardingDone = !loggedOut
        ReedSessionStore.consentAccepted = scene != "login" && scene != "anim_login"
        ReedSessionStore.joinedViaCode = scene == "family_member" || scene == "blocked"
        ReedSessionStore.noCodeMode = scene == "home_nocode"
        ReedSessionStore.splitRouting = true
        ReedSessionStore.autoConnect = scene == "profile" || scene == "profile_netcheck"
        ReedSessionStore.serverNetwork = scene == "home_cell" ? "cell" : "wifi"
        ReedSessionStore.directServices = scene == "services" ? ["gosuslugi", "sber", "ozon"] : []
        ReedSessionStore.notifSeenMaxId = scene == "home_on" ? 0 : 3
    }

    static func prepareTunnel(_ scene: String, _ t: PreviewTunnel) {
        switch scene {
        case "home_on", "profile", "family", "anim_server": t.force(.connected, since: Date().addingTimeInterval(-(2 * 3600 + 14 * 60 + 7)))
        case "home_connecting": t.force(.connecting)
        default: break
        }
    }

    static func apply(_ scene: String, _ m: ReedAppModel) {
        let loggedOut = scene.hasPrefix("login") || scene == "codes" || scene == "codes_family"
            || scene == "anim_login" || scene == "home_nocode"
        if !loggedOut {
            m.token = "preview"
            m.sub = sub
            m.subLoaded = true
            m.subsList = [
                RSubscriptionItem(sub_token: "preview", plan_label: "Семейная", days_left: 168, current: true),
                RSubscriptionItem(sub_token: "preview2", plan_label: "Личная", days_left: 23, current: false),
            ]
            m.servers = servers
            m.pings = ["NL": 42, "DE": 58, "FI": 71, "RU-BRIDGE": 23, "PL": 104, "LTE-DE": 88]
            m.selectedId = scene == "home_cell" ? "LTE-DE" : "NL"
            m.devices = RDevices(
                devices: [
                    RDevice(id: 1, name: "iPhone 16 Pro", os: "iOS 26", type: "phone", last_seen: "2026-09-24 09:12:00", blocked: false),
                    RDevice(id: 2, name: "MacBook Air", os: "macOS 26", type: "desktop", last_seen: "2026-09-23 22:40:00", blocked: false),
                    RDevice(id: 3, name: "Pixel 9", os: "Android 16", type: "phone", last_seen: "2026-09-20 18:03:00", blocked: false),
                ],
                blocked: [RDevice(id: 4, name: "Galaxy Tab", os: "Android 14", type: "tablet", last_seen: "2026-08-30 11:00:00", blocked: true)],
                used: 3, limit: 11)
            m.members = RMembers(members: [
                RMember(id: 11, name: "Мама", device: "iPhone 13", blocked: false),
                RMember(id: 12, name: "Саша", device: "Galaxy S24", blocked: true),
            ], count: 2, limit: 5)
            m.notifications = notifications(scene == "newdevice")
            m.inviteCode = scene == "family" ? "RDI-7K2M-Q9XA" : nil
        }
        switch scene {
        case "codes":
            m.stage = .codes
            m.code = "RD-4F7K-2M9Q"
        case "codes_family":
            m.stage = .codes
            m.code = "RDI-7K2M-Q9XA"
            m.codeName = "Саша"
        case "family", "family_member", "newdevice": m.tab = .family
        case "profile", "profile_netcheck": m.tab = .profile
        case "notifications": m.showNotifications = true
        case "blocked": m.memberBlocked = true
        case "support_empty": m.chatLoaded = true; m.showSupport = true
        case "support_chat":
            m.chat = supportChat
            m.chatLoaded = true
            m.showSupport = true
        default: break
        }
        if scene == "profile_netcheck" {
            m.netRows = [
                ReedNetRow(label: "Wi-Fi", ping: 42, word: "Работает", kind: "ok"),
                ReedNetRow(label: "Мобильный", ping: 88, word: "Ограничено", kind: "warn"),
                ReedNetRow(label: "Прямое", ping: 31, word: "Работает", kind: "ok"),
            ]
            m.netCheckedAt = Date().addingTimeInterval(-40)
        }
    }

    static let supportChat: [ReedChatItem] = {
        let now = Date()
        func t(_ min: Double) -> Date { now.addingTimeInterval(-min * 60) }
        return [
            ReedChatItem(id: "s1", serverId: 1, mine: true, text: "Здравствуйте! Не подключается мобильный интернет, на Wi-Fi всё работает.", date: t(26 * 60)),
            ReedChatItem(id: "s2", serverId: 2, mine: false, text: "Привет! Переключи внизу на «Мобильный» и выбери сервер «Обход» — он для сетей с белыми списками.", date: t(25 * 60)),
            ReedChatItem(id: "s3", serverId: 3, mine: true, text: "Заработало, спасибо!", date: t(24 * 60)),
            ReedChatItem(id: "s4", serverId: 4, mine: true, text: "А можно добавить ещё один телефон?", date: t(12)),
            ReedChatItem(id: "s5", serverId: 5, mine: true, text: "Для мамы", date: t(11.5)),
            ReedChatItem(id: "s6", serverId: 6, mine: false, text: "Конечно. Открой «Семья» → «Пригласить участника» и отправь маме код. Подробнее: https://reedapp.ru/family", date: t(4)),
            ReedChatItem(id: "l7", serverId: nil, mine: true, text: "Спасибо, сейчас попробую", date: t(0.2), state: .sending),
        ]
    }()

    static let sub: RSubscriptionResponse = {
        let json = """
        {"profile":{"name":"Алекс","username":"alex"},
         "subscription":{"status":"active","plan_label":"Семейная","plan_type":"family",
           "expires_at":"2027-03-11 12:00:00","seconds_left":14515200,"days_left":168},
         "traffic":{"used":0,"total":0},"lte":{"used_gb":3.4,"total_gb":20}}
        """
        return try! JSONDecoder().decode(RSubscriptionResponse.self, from: Data(json.utf8))
    }()

    static let servers: [ReedServer] = [
        ReedServer(id: "NL", title: "Нидерланды", subtitle: "Амстердам", country: "NL", network: "wifi", host: nil, port: nil, olc: nil),
        ReedServer(id: "DE", title: "Германия", subtitle: "Франкфурт", country: "DE", network: "wifi", host: nil, port: nil, olc: nil),
        ReedServer(id: "FI", title: "Финляндия", subtitle: "Хельсинки", country: "FI", network: "wifi", host: nil, port: nil, olc: nil),
        ReedServer(id: "PL", title: "Польша", subtitle: "Варшава", country: "PL", network: "wifi", host: nil, port: nil, olc: nil),
        ReedServer(id: "RU-BRIDGE", title: "Германия", subtitle: "Через Москву", country: "DE", network: "wifi", host: nil, port: nil, olc: nil),
        ReedServer(id: "LTE-DE", title: "Германия", subtitle: "Франкфурт", country: "DE", network: "cell", host: nil, port: nil, olc: nil),
        ReedServer(id: "olc:Обход", title: "Обход", subtitle: "olcRTC", country: "RTC", network: "cell", host: nil, port: nil,
                   olc: OlcRtcConfig(name: "Обход", provider: "preview", transport: "vp8", room: "r", key: "k", vp8Fps: 60, vp8Batch: 64)),
    ]

    static func notifications(_ withDevice: Bool) -> [RNotification] {
        var list = [
            RNotification(id: 1, title: "Подписка продлена", body: "Семейная подписка активна до 11 марта 2027.",
                          created_at: "2026-09-20 10:00:00", kind: "info", data: nil),
            RNotification(id: 2, title: "Новый сервер", body: "Добавили Польшу — Варшава, низкий пинг из Москвы.",
                          created_at: "2026-09-22 15:30:00", kind: "info", data: nil),
            RNotification(id: 3, title: "Саша в семье", body: "Участник присоединился по приглашению.",
                          created_at: "2026-09-23 19:10:00", kind: "info", data: nil),
        ]
        if withDevice {
            list.append(RNotification(id: 4, title: "Новое устройство", body: "К подписке подключилось Pixel 9.",
                                      created_at: "2026-09-24 08:55:00", kind: "new_device", data: ["device_id": .number(3)]))
        }
        return list
    }
}

/// Сценарии для видео: только то, что делает пользователь (через методы модели).
enum PreviewScript {
    @MainActor
    static func run(_ scene: String, _ m: ReedAppModel, _ t: PreviewTunnel) async {
        func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        switch scene {
        case "anim_connect":
            await wait(1.5)
            m.toggleConnect()                 // выкл → подключение → подключено
            await wait(5.0)
            m.toggleConnect()                 // → отключение
            await wait(2.0)
        case "anim_tabs":
            await wait(1.2)
            for tab in [ReedAppModel.Tab.family, .profile, .home, .profile] {
                m.tab = tab
                await wait(1.3)
            }
        case "anim_login":
            await wait(1.2)
            m.acceptConsent(true)
            await wait(1.2)
            m.openCodes(from: .login)
            await wait(1.0)
            for ch in "RD-4F7K-2M9Q" {
                m.codeChanged(m.code + String(ch))
                await wait(0.09)
            }
            await wait(0.8)
            withAnimation(.smooth(duration: 0.35)) {
                m.token = "preview"
                m.sub = PreviewSeed.sub
                m.subLoaded = true
                m.servers = PreviewSeed.servers
                m.selectedId = "NL"
                m.stage = .app
            }
            await wait(2.5)
        case "anim_server":
            await wait(1.2)
            m.select(PreviewSeed.servers[1])
            await wait(4.0)
            m.setNetwork("cell")
            await wait(1.2)
            m.select(PreviewSeed.servers[6])
            await wait(6.0)
        case "anim_support":
            await wait(0.8)
            m.openSupport()
            await wait(1.6)
            m.chatDraft = "Не подключается"
            await wait(0.6)
            for ch in " на мобильном интернете" { m.chatDraft.append(ch); await wait(0.05) }
            await wait(0.5)
            m.sendChat()
            await wait(2.2)
            withAnimation(.spring(response: 0.42, dampingFraction: 0.86)) {
                m.chat.append(ReedChatItem(id: "s900", serverId: 900, mine: false,
                                           text: "Привет! Переключи внизу на «Мобильный» и выбери «Обход».", date: Date()))
            }
            await wait(1.4)
            withAnimation(.spring(response: 0.42, dampingFraction: 0.86)) {
                m.chat.append(ReedChatItem(id: "s901", serverId: 901, mine: false,
                                           text: "Если не поможет — пришли, пожалуйста, скрин главного экрана.", date: Date()))
            }
            await wait(2.5)
        case "anim_notif":
            await wait(1.0)
            m.showNotifications = true
            await wait(2.0)
            m.showNotifications = false
            await wait(1.5)
            m.show("Список обновлён")
            await wait(3.0)
        default:
            break
        }
    }
}
