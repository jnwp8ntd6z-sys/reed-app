import Foundation
import SwiftUI

/// Сервер в списке (VLESS по токену или olcRTC-обход).
struct ReedServer: Identifiable, Equatable, Sendable {
    var id: String            // vless: тег сервера; olc: "olc:" + имя
    var title: String         // «Германия»
    var subtitle: String      // «Франкфурт» / «Через Москву» / «olcRTC»
    var country: String       // ISO-2 / "RTC"
    var network: String       // wifi | cell
    var host: String?
    var port: Int?
    var olc: OlcRtcConfig?
    var tag: String { olc == nil ? id : "" }
}

/// Сообщение в чате поддержки (с локальным состоянием отправки).
struct ReedChatItem: Identifiable, Equatable, Sendable {
    enum State: Sendable, Equatable { case sent, sending, failed }
    var id: String            // стабильный id для анимаций: "s<id>" с сервера или "l<uuid>" локальный
    var serverId: Int?
    var mine: Bool
    var text: String
    var date: Date
    var state: State = .sent
}

struct ReedNetRow: Identifiable, Equatable, Sendable {
    var id: String { label }
    var label: String; var ping: Int?; var word: String; var kind: String // ok|warn|danger|muted
}

/// Reed 2.0 — состояние и логика iOS-приложения. Экраны только читают его и вызывают методы.
@MainActor
final class ReedAppModel: ObservableObject {
    enum Stage: Equatable { case login, codes, app }
    enum Tab: Hashable { case home, family, profile }

    let tunnel: ReedTunnel
    private let api = ReedAPI.shared

    @Published var stage: Stage
    @Published var codesReturn: Stage = .login
    @Published var tab: Tab = .home
    @Published var consent: Bool

    @Published var code = ""
    @Published var codeName = ""
    @Published var codeError: String?
    @Published var codeBusy = false

    @Published var token: String?
    @Published var sub: RSubscriptionResponse?
    @Published var subLoaded = false
    @Published var subsList: [RSubscriptionItem] = []
    @Published var notifications: [RNotification] = []
    @Published var notifSeen: Int
    @Published var newDevAck: Int
    @Published var devices: RDevices?
    @Published var members: RMembers?
    @Published var inviteCode: String?
    @Published var deviceCode: String?
    @Published var memberBlocked = false

    @Published var servers: [ReedServer] = []
    @Published var selectedId: String?
    @Published var network: String
    @Published var pings: [String: Int] = [:]
    @Published var pinging = false
    @Published var refreshing = false

    @Published var tunnelStatus: ReedTunnelStatus = .disconnected
    @Published var connectedSince: Date?
    @Published var connectError: String?

    @Published var autoConnect: Bool
    @Published var ruDirect: Bool
    @Published var directServices: Set<String>
    private var directApplyTask: Task<Void, Never>?
    @Published var netRows: [ReedNetRow] = ReedAppModel.emptyNetRows
    @Published var netChecking = false
    @Published var netCheckedAt: Date?

    // Чат поддержки
    @Published var chat: [ReedChatItem] = []
    @Published var chatDraft = ""
    @Published var chatLoaded = false
    @Published var showSupport = false
    @Published var supportUnread = false
    private var chatPoll: Task<Void, Never>?
    private var chatLastId = 0
    private var chatFakeId = 0

    @Published var showNotifications = false
    @Published var toast: String?
    @Published var shareLogURL: URL?

    /// false — превью/скриншоты: модель не ходит в сеть (данные подставлены заранее).
    var live = true

    private var bgTasks: [Task<Void, Never>] = []
    private var switchTask: Task<Void, Never>?
    private var autoPinged = false
    private var autoConnected = false

    nonisolated static let emptyNetRows = [
        ReedNetRow(label: "Wi-Fi", ping: nil, word: "Не проверено", kind: "muted"),
        ReedNetRow(label: "Мобильный", ping: nil, word: "Не проверено", kind: "muted"),
        ReedNetRow(label: "Прямое", ping: nil, word: "Не проверено", kind: "muted"),
    ]

    init(tunnel: ReedTunnel) {
        self.tunnel = tunnel
        token = ReedSessionStore.token
        stage = ReedSessionStore.onboardingDone ? .app : .login
        consent = ReedSessionStore.consentAccepted
        notifSeen = ReedSessionStore.notifSeenMaxId
        newDevAck = ReedSessionStore.newDeviceAckMaxId
        network = ReedSessionStore.serverNetwork ?? "wifi"
        autoConnect = ReedSessionStore.autoConnect
        ruDirect = ReedSessionStore.splitRouting
        directServices = Set(ReedSessionStore.directServices)
        selectedId = ReedSessionStore.selectedServer
        if let data = ReedSessionStore.subscriptionCache,
           let cached = try? JSONDecoder().decode(RSubscriptionResponse.self, from: data) {
            sub = cached; subLoaded = true
        }
        loadCachedServers()
        tunnel.onChange = { [weak self] in self?.syncTunnel() }
    }

    // MARK: жизненный цикл

    func onAppear() {
        Task {
            await tunnel.refresh()
            syncTunnel()
            if token != nil && live { startAccountTasks() }
        }
        supportWatch?.cancel()
        supportWatch = Task { [weak self] in
            while !Task.isCancelled {
                await self?.checkSupportUnread()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }
    private var supportWatch: Task<Void, Never>?

    /// Возврат в приложение: статус туннеля мог смениться из Пункта управления/настроек,
    /// подписка — продлиться в боте. Лёгкое обновление без перезапуска фоновых задач.
    func onForeground() {
        Task {
            await tunnel.refresh()
            syncTunnel()
            guard token != nil, live else { return }
            async let a: Void = reloadSubscription()
            async let b: Void = reloadNotifications()
            _ = await (a, b)
        }
    }

    // MARK: чат поддержки

    func openSupport() {
        showNotifications = false
        showSupport = true
        chatPoll?.cancel()
        chatPoll = Task { [weak self] in
            await self?.loadChat()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if Task.isCancelled { return }
                await self?.loadChat()
            }
        }
    }

    func closeSupport() {
        showSupport = false
        chatPoll?.cancel(); chatPoll = nil
        markSupportSeen()
    }

    /// Смена аккаунта: переписка другая (сервер сам переносит диалог «без входа» на аккаунт).
    private func resetChat() {
        chatPoll?.cancel(); chatPoll = nil
        chat = []; chatLastId = 0; chatLoaded = false; supportUnread = false
        ReedSessionStore.supportSeenMaxId = 0
    }

    private func markSupportSeen() {
        let maxOut = chat.filter { !$0.mine }.compactMap(\.serverId).max() ?? 0
        if maxOut > ReedSessionStore.supportSeenMaxId { ReedSessionStore.supportSeenMaxId = maxOut }
        supportUnread = false
    }

    /// Новые сообщения с сервера (после последнего известного id). Свои отправленные не дублируются.
    func loadChat() async {
        guard live else { chatLoaded = true; return }
        guard let r = try? await api.supportMessages(token: token, hwid: ReedSessionStore.hwid(), after: chatLastId) else {
            chatLoaded = true; return
        }
        let known = Set(chat.compactMap(\.serverId))
        let fresh = (r.messages ?? []).filter { !known.contains($0.id) }
        if !fresh.isEmpty {
            withAnimation(.spring(response: 0.42, dampingFraction: 0.86)) {
                chat.append(contentsOf: fresh.map(Self.chatItem))
            }
            chatLastId = max(chatLastId, fresh.map(\.id).max() ?? 0)
            if showSupport { markSupportSeen() }
        }
        chatLoaded = true
    }

    func sendChat() {
        let text = chatDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        chatDraft = ""
        let item = ReedChatItem(id: "l" + UUID().uuidString, serverId: nil, mine: true, text: text, date: Date(), state: .sending)
        withAnimation(.spring(response: 0.42, dampingFraction: 0.86)) { chat.append(item) }
        deliver(item.id, text)
    }

    func retryChat(_ id: String) {
        guard let i = chat.firstIndex(where: { $0.id == id }) else { return }
        withAnimation(.smooth(duration: 0.25)) { chat[i].state = .sending }
        deliver(id, chat[i].text)
    }

    private func deliver(_ localId: String, _ text: String) {
        Task {
            let r: RSupportSendResult?
            if live {
                r = try? await api.supportSend(token: token, hwid: ReedSessionStore.hwid(), text: text)
            } else {   // превью: без сети, «доставлено» через полсекунды
                try? await Task.sleep(nanoseconds: 600_000_000)
                chatFakeId += 1
                r = RSupportSendResult(ok: true, message: RSupportMessage(id: 10_000 + chatFakeId, direction: "in", text: text, created_at: nil))
            }
            guard let i = chat.firstIndex(where: { $0.id == localId }) else { return }
            withAnimation(.smooth(duration: 0.25)) {
                if let m = r?.message, r?.ok == true {
                    chat[i].serverId = m.id
                    chat[i].state = .sent
                    chatLastId = max(chatLastId, m.id)
                    ReedSessionStore.supportUsed = true
                } else {
                    chat[i].state = .failed
                }
            }
            if r?.error == "rate_limited" { show(r?.hint ?? "Подожди немного и отправь ещё раз") }
        }
    }

    /// Фоновая проверка: есть ли непрочитанный ответ поддержки (точка на «Написать в поддержку»).
    func checkSupportUnread() async {
        guard live, ReedSessionStore.supportUsed || token != nil, !showSupport else { return }
        let seen = ReedSessionStore.supportSeenMaxId
        guard let r = try? await api.supportMessages(token: token, hwid: ReedSessionStore.hwid(), after: seen) else { return }
        let has = (r.messages ?? []).contains { !$0.isMine }
        if has != supportUnread { withAnimation(.smooth(duration: 0.3)) { supportUnread = has } }
    }

    nonisolated static func chatItem(_ m: RSupportMessage) -> ReedChatItem {
        ReedChatItem(id: "s\(m.id)", serverId: m.id, mine: m.isMine, text: m.text,
                     date: ReedFormat.parse(m.created_at, utc: true) ?? Date(), state: .sent)
    }

    private func syncTunnel() {
        tunnelStatus = tunnel.status
        connectedSince = tunnel.connectedSince
        if tunnelStatus == .connected { connectError = nil }
    }

    private func startAccountTasks() {
        bgTasks.forEach { $0.cancel() }
        bgTasks = []
        bgTasks.append(Task { await self.loadAll() })
        bgTasks.append(Task {   // уведомления раз в 5 минут
            while !Task.isCancelled {
                await self.reloadNotifications()
                try? await Task.sleep(nanoseconds: 300_000_000_000)
            }
        })
        if ReedSessionStore.joinedViaCode {
            bgTasks.append(Task {   // гейт участника: владелец мог приостановить/удалить доступ
                while !Task.isCancelled {
                    if let t = self.token, let s = await self.api.session(t) {
                        if s.kind == "deleted" { self.logout(); return }
                        if s.kind == "member" { self.memberBlocked = (s.blocked ?? false) || s.member_status == "blocked" }
                    }
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                }
            })
        }
    }

    private func loadAll() async {
        async let a: Void = reloadSubscription()
        async let b: Void = reloadServers()
        async let c: Void = reloadSubsList()
        _ = await (a, b, c)
        if !autoPinged && !servers.isEmpty { autoPinged = true; await pingAll() }
        if let t = token {
            tunnel.prewarm(token: t, servers: servers.filter { $0.olc == nil }.map(\.id), split: ruDirect, direct: directServices.sorted())
        }
        if autoConnect && !autoConnected && tunnelStatus == .disconnected && !servers.isEmpty {
            autoConnected = true
            await connect()
        }
    }

    // MARK: вход

    func openCodes(from: Stage) { codesReturn = from; codeError = nil; stage = .codes }

    func acceptConsent(_ v: Bool) { consent = v; ReedSessionStore.consentAccepted = v }

    func continueWithoutCode() {
        ReedSessionStore.noCodeMode = true
        ReedSessionStore.onboardingDone = true
        tab = .home
        stage = .app
    }

    /// Ввод из поля: вставка из буфера (рост больше чем на 1 символ) входит сразу, кроме
    /// приглашения в семью — ему сначала нужно имя.
    func codeChanged(_ new: String) {
        let grew = new.count - code.count > 1
        code = new
        codeError = nil
        if grew, let k = CodeKindDetector.detect(new), k != .familyInvite { submitCode() }
    }

    func scanned(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        code = t
        if stage != .codes { codesReturn = stage; stage = .codes }
        if CodeKindDetector.detect(t) != .familyInvite { submitCode() }
    }

    func submitCode() {
        guard !codeBusy else { return }
        codeBusy = true; codeError = nil
        Task {
            codeError = await submit(code, name: codeName)
            codeBusy = false
        }
    }

    private func submit(_ raw: String, name: String) async -> String? {
        let input = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let kind = CodeKindDetector.detect(input) else { return "Введи код или ссылку" }
        do {
            switch kind {
            case .subscriptionLink:
                if let t = Self.linkLoginToken(input) {
                    let r = try await api.linkLogin(t)
                    if r.ok == true, let tok = r.token, !tok.isEmpty { login(tok, member: false, name: nil); return nil }
                    return r.message ?? "Ссылка не сработала. Попроси новую."
                }
                if let t = Self.reedSubToken(input) {
                    _ = try await api.subscriptionRaw(t)   // проверяем, что токен живой
                    login(t, member: false, name: nil)
                    return nil
                }
                return "На iPhone работают ключи и коды Reed. Этот ключ пока не поддерживается."
            case .accountCode:
                let r = try await api.codeLogin(input, hwid: ReedSessionStore.hwid(), model: Self.deviceModel)
                if r.ok == true, let t = r.sub_token, !t.isEmpty { login(t, member: false, name: nil); return nil }
                return r.message ?? Self.errorText(r.error)
            case .familyInvite, .deviceCode:
                let isDevice = kind == .deviceCode
                let who = isDevice ? Self.deviceModel : name.trimmingCharacters(in: .whitespaces)
                if who.isEmpty { return "Напиши своё имя — его увидит владелец" }
                let r = try await api.redeem(input, name: who, hwid: ReedSessionStore.hwid(), model: Self.deviceModel)
                if r.ok == true, let t = r.member_token ?? r.sub_token, !t.isEmpty {
                    let member = r.kind != "device"
                    login(t, member: member, name: member ? (r.name ?? who) : nil)
                    return nil
                }
                return r.message ?? Self.errorText(r.error)
            }
        } catch {
            return "Нет связи с сервером. Попробуй ещё раз."
        }
    }

    private func login(_ t: String, member: Bool, name: String?) {
        let switching = token != nil && token != t
        if switching { Task { await self.tunnel.setOnDemand(false); self.tunnel.stop() } }
        ReedSessionStore.token = t
        ReedSessionStore.joinedViaCode = member
        ReedSessionStore.memberName = name
        ReedSessionStore.noCodeMode = false
        ReedSessionStore.consentAccepted = true
        ReedSessionStore.onboardingDone = true
        if switching { ReedSessionStore.selectedServer = nil; selectedId = nil; servers = []; pings = [:] }
        token = t
        sub = nil; subLoaded = false; devices = nil; members = nil; inviteCode = nil; deviceCode = nil
        memberBlocked = false; autoPinged = false
        code = ""; codeName = ""; codeError = nil
        resetChat()
        tab = .home
        withAnimation(.smooth(duration: 0.35)) { stage = .app }
        startAccountTasks()
    }

    /// Смена активной подписки аккаунта (ТЗ 4.4: нажатие на карточку, если подписок несколько).
    func switchSubscription(_ t: String) {
        login(t, member: ReedSessionStore.joinedViaCode, name: ReedSessionStore.memberName)
        show("Подписка переключена")
    }

    func logout() {
        bgTasks.forEach { $0.cancel() }; bgTasks = []
        Task { await tunnel.setOnDemand(false); tunnel.stop() }
        ReedSessionStore.logout()
        UserDefaults.standard.removeObject(forKey: "reed2_locations_cache")
        UserDefaults.standard.removeObject(forKey: "reed2_olcconf_cache")
        token = nil; sub = nil; subLoaded = false; subsList = []; notifications = []
        devices = nil; members = nil; inviteCode = nil; deviceCode = nil; memberBlocked = false
        servers = []; pings = [:]; selectedId = nil
        resetChat(); ReedSessionStore.supportUsed = false
        tab = .home
        withAnimation(.smooth(duration: 0.35)) { stage = .login }
    }

    func deleteAccount() {
        guard let t = token else { return }
        Task {
            let ok = (try? await api.deleteAccount(t))?.ok == true
            if ok { show("Аккаунт удалён"); logout() } else { show("Не удалось удалить аккаунт. Попробуй ещё раз.") }
        }
    }

    // MARK: данные

    func reloadSubscription() async {
        guard let t = token else { return }
        if let data = try? await api.subscriptionRaw(t),
           let r = try? JSONDecoder().decode(RSubscriptionResponse.self, from: data) {
            sub = r
            if r.subscription.status == "active" { ReedSessionStore.subscriptionCache = data }
        }
        subLoaded = true
    }

    func reloadSubsList() async {
        guard let t = token else { return }
        subsList = (try? await api.subscriptions(t))?.subscriptions ?? subsList
    }

    func reloadNotifications() async {
        guard let t = token else { return }
        if let n = try? await api.notifications(t).notifications { notifications = n }
    }

    /// Серверы: VLESS из /app/locations + olcRTC-обходы из /app/olcconf. С повтором, пока не появятся.
    func reloadServers() async {
        guard let t = token else { return }
        for attempt in 0..<6 {
            var ok = false
            if let locs = try? await api.locations(t) {
                if locs.member_blocked == true { memberBlocked = true }
                let olcText = (try? await api.olcconf(t)) ?? UserDefaults.standard.string(forKey: "reed2_olcconf_cache") ?? ""
                if let data = try? JSONEncoder().encode(locs) { UserDefaults.standard.set(data, forKey: "reed2_locations_cache") }
                UserDefaults.standard.set(olcText, forKey: "reed2_olcconf_cache")
                applyServers(locs.locations ?? [], olcText)
                ok = true
            }
            if ok || Task.isCancelled { return }
            try? await Task.sleep(nanoseconds: UInt64(min(3 + attempt * 2, 20)) * 1_000_000_000)
        }
    }

    private func loadCachedServers() {
        guard token != nil,
              let data = UserDefaults.standard.data(forKey: "reed2_locations_cache"),
              let locs = try? JSONDecoder().decode(RLocations.self, from: data) else { return }
        applyServers(locs.locations ?? [], UserDefaults.standard.string(forKey: "reed2_olcconf_cache") ?? "")
    }

    private func applyServers(_ locs: [RLocation], _ olcText: String) {
        var list: [ReedServer] = []
        for l in locs where l.transport == "vless" {
            let name = l.tag ?? l.name
            let d = Self.describeServer(name, olc: false)
            list.append(ReedServer(id: name, title: d.title, subtitle: d.subtitle, country: d.iso,
                                   network: d.network, host: l.host, port: l.port, olc: nil))
        }
        for c in OlcRtcConfig.parseAll(olcText) {
            let d = Self.describeServer(c.name, olc: true)
            list.append(ReedServer(id: "olc:" + c.name, title: d.title, subtitle: d.subtitle, country: d.iso,
                                   network: "cell", host: nil, port: nil, olc: c))
        }
        servers = list
        // Как в Happ: при входе сразу выбран первый сервер текущего списка (обычно «Нидерланды»).
        // Если выбранный пропал из подписки — выбираем первый заново, подключение при этом не трогаем.
        if selectedId == nil || !list.contains(where: { $0.id == selectedId }) {
            selectedId = list.first(where: { $0.network == network })?.id ?? list.first?.id
            ReedSessionStore.selectedServer = selectedId
        }
    }

    func refreshAll() {
        guard !refreshing, live else { return }
        refreshing = true
        Task {
            async let a: Void = reloadSubscription()
            async let b: Void = reloadServers()
            async let c: Void = reloadSubsList()
            _ = await (a, b, c)
            if let t = token { tunnel.prewarm(token: t, servers: servers.filter { $0.olc == nil }.map(\.id), split: ruDirect, direct: directServices.sorted()) }
            refreshing = false
            show("Список обновлён")
        }
    }

    func pingAll() async {
        guard !pinging else { return }
        pinging = true
        await withTaskGroup(of: (String, Int?).self) { group in
            for s in servers {
                guard let h = s.host, let p = s.port else { continue }
                group.addTask { (s.id, await ReedPing.tcp(host: h, port: p)) }
            }
            for await (id, ms) in group {
                if let ms { pings[id] = ms } else { pings[id] = -1 }
            }
        }
        pinging = false
    }

    // MARK: подключение

    var selectedServer: ReedServer? { servers.first { $0.id == selectedId } }

    func toggleConnect() {
        switch tunnelStatus {
        case .connected, .connecting:
            Task { await disconnect() }
        case .disconnected, .disconnecting:
            Task { await connect() }
        }
    }

    func connect() async {
        // Выбран сервер из другого списка, а подключения нет — берём первый из видимого.
        if selectedServer?.network != network, let first = servers.first(where: { $0.network == network }) {
            selectedId = first.id
            ReedSessionStore.selectedServer = first.id
        }
        guard let s = selectedServer else { show("Серверы ещё загружаются"); return }
        let target: ReedTunnelTarget
        if let c = s.olc {
            target = .olc(c, clientId: ReedSessionStore.hwid())
        } else {
            guard let t = token else { return }
            target = .vless(token: t, server: s.id)
        }
        connectError = nil
        let ok = await tunnel.start(target, split: ruDirect, direct: directServices.sorted())
        syncTunnel()
        if !ok {
            connectError = "Не удалось подключиться. Попробуй ещё раз или выбери другой сервер."
        } else if autoConnect {
            await tunnel.setOnDemand(true)
        }
    }

    func disconnect() async {
        await tunnel.setOnDemand(false)
        tunnel.stop()
    }

    /// Выбор сервера. Если туннель был включён — переподключаемся к новому (с отменой
    /// предыдущего переключения, чтобы быстрые тапы не накладывали несколько запусков).
    func select(_ s: ReedServer) {
        selectedId = s.id
        ReedSessionStore.selectedServer = s.id
        let wasOn = tunnelStatus == .connected || tunnelStatus == .connecting
        guard wasOn else { return }
        switchTask?.cancel()
        switchTask = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            if Task.isCancelled { return }
            await tunnel.setOnDemand(false)
            tunnel.stop()
            for _ in 0..<50 where tunnel.status != .disconnected {
                try? await Task.sleep(nanoseconds: 100_000_000)
                await tunnel.refresh()
            }
            if Task.isCancelled { return }
            await connect()
        }
    }

    /// Wi-Fi / Мобильный — просто другой список. Без подключения выбор переезжает на первый сервер
    /// нового списка (кнопка подключит то, что видно); при подключении ничего не трогаем.
    func setNetwork(_ n: String) {
        network = n
        ReedSessionStore.serverNetwork = n
        guard tunnelStatus == .disconnected, selectedServer?.network != n,
              let first = servers.first(where: { $0.network == n }) else { return }
        selectedId = first.id
        ReedSessionStore.selectedServer = first.id
    }

    // MARK: семья

    func loadFamily() {
        guard live, let t = token, !ReedSessionStore.joinedViaCode else { return }
        Task {
            if let d = try? await api.devices(t) { devices = d }
            if let m = try? await api.members(t) { members = m }
        }
    }

    func requestCode(device: Bool) {
        guard let t = token else { return }
        if device ? deviceCode != nil : inviteCode != nil { return }
        Task {
            let r = try? await api.shareCreate(t, type: device ? "device" : "member")
            if r?.ok == true, let c = r?.code, !c.isEmpty {
                if device { deviceCode = c } else { inviteCode = c }
            } else {
                show(r?.error == "members_cannot_share" ? "Приглашать может только владелец" : "Не удалось создать код")
            }
        }
    }

    func deviceAction(_ id: Int, _ action: String, done: String) {
        guard let t = token else { return }
        Task {
            if (try? await api.deviceAction(t, id: id, action: action))?.ok == true { show(done) } else { show("Не получилось. Попробуй ещё раз.") }
            loadFamily()
        }
    }

    func memberAction(_ id: Int, _ action: String, done: String) {
        guard let t = token else { return }
        Task {
            if (try? await api.memberAction(t, id: id, action: action))?.ok == true { show(done) } else { show("Не получилось. Попробуй ещё раз.") }
            loadFamily()
        }
    }

    var newDeviceAlert: RNotification? {
        notifications.filter { $0.kind == "new_device" && $0.id > newDevAck && $0.deviceId != nil }.max { $0.id < $1.id }
    }

    func ackNewDevice() {
        guard let n = newDeviceAlert else { return }
        newDevAck = n.id
        ReedSessionStore.newDeviceAckMaxId = n.id
    }

    var hasUnread: Bool { (notifications.map(\.id).max() ?? 0) > notifSeen }

    func markNotificationsSeen() {
        let m = notifications.map(\.id).max() ?? 0
        if m > notifSeen { notifSeen = m; ReedSessionStore.notifSeenMaxId = m }
    }

    // MARK: профиль

    func setAutoConnect(_ v: Bool) {
        autoConnect = v
        ReedSessionStore.autoConnect = v
        Task { await tunnel.setOnDemand(v && (tunnelStatus == .connected)) }
    }

    func setRuDirect(_ v: Bool) {
        ruDirect = v
        ReedSessionStore.splitRouting = v
        if tunnelStatus == .connected {
            Task { await disconnect(); try? await Task.sleep(nanoseconds: 600_000_000); await connect() }
        }
    }

    func toggleDirectService(_ id: String) {
        if directServices.contains(id) { directServices.remove(id) } else { directServices.insert(id) }
        ReedSessionStore.directServices = Array(directServices)
        // Несколько переключений подряд → одно переподключение, когда пользователь закончил.
        directApplyTask?.cancel()
        directApplyTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self, !Task.isCancelled else { return }
            if let t = self.token {
                self.tunnel.prewarm(token: t, servers: self.servers.filter { $0.olc == nil }.map(\.id),
                                    split: self.ruDirect, direct: self.directServices.sorted())
            }
            if self.tunnelStatus == .connected, self.selectedServer?.olc == nil {
                await self.disconnect()
                try? await Task.sleep(nanoseconds: 600_000_000)
                await self.connect()
            }
        }
    }

    func runNetCheck() {
        guard !netChecking else { return }
        netChecking = true
        Task {
            let wifi = servers.first { $0.network == "wifi" && $0.host != nil }
            let cell = servers.first { $0.network == "cell" && $0.host != nil }
            async let w: Int? = Self.pingServer(wifi)
            async let c: Int? = Self.pingServer(cell)
            async let d: Int? = ReedPing.directHttps()
            let (wm, cm, dm) = await (w, c, d)
            netRows = [
                Self.netRow("Wi-Fi", wifi != nil, wm),
                Self.netRow("Мобильный", cell != nil, cm),
                Self.directRow(dm),
            ]
            netCheckedAt = Date()
            netChecking = false
        }
    }

    func exportLogs() {
        let text = tunnel.extensionLog() ?? "Лог пуст"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("reed-logs.txt")
        try? text.write(to: url, atomically: true, encoding: .utf8)
        shareLogURL = url
    }

    func show(_ s: String) {
        withAnimation(.smooth(duration: 0.25)) { toast = s }
        Task {
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            if toast == s { withAnimation(.smooth(duration: 0.3)) { toast = nil } }
        }
    }

    // MARK: вспомогательное

    static var deviceModel: String { "iPhone" }

    nonisolated static func pingServer(_ s: ReedServer?) async -> Int? {
        guard let s, let h = s.host else { return nil }
        return await ReedPing.tcp(host: h, port: s.port ?? 443)
    }

    static func errorText(_ e: String?) -> String {
        switch e {
        case "not_found": return "Код не найден"
        case "expired": return "Код истёк"
        case "used": return "Приглашение уже использовано"
        case "device_limit": return "На подписке уже максимум устройств. Пусть владелец удалит одно — и введи код снова."
        case "all_full", "no_slot": return "На подписке нет свободных мест"
        case "no_subscription": return "У аккаунта нет активной подписки"
        case "limit": return "В семье нет свободных мест"
        default: return "Не удалось войти по коду"
        }
    }

    /// «Прямое» меряет полный TLS до сайта мимо туннеля — порог мягче, чем у пинга серверов.
    static func directRow(_ ms: Int?) -> ReedNetRow {
        guard let ms, ms >= 0 else { return ReedNetRow(label: "Прямое", ping: nil, word: "Не отвечает", kind: "danger") }
        return ms >= 2500 ? ReedNetRow(label: "Прямое", ping: ms, word: "Ограничено", kind: "warn")
                          : ReedNetRow(label: "Прямое", ping: ms, word: "Работает", kind: "ok")
    }

    static func netRow(_ label: String, _ has: Bool, _ ms: Int?) -> ReedNetRow {
        if !has { return ReedNetRow(label: label, ping: nil, word: "Нет сервера", kind: "muted") }
        guard let ms else { return ReedNetRow(label: label, ping: nil, word: "Не отвечает", kind: "danger") }
        if ms >= 400 { return ReedNetRow(label: label, ping: ms, word: "Ограничено", kind: "warn") }
        return ReedNetRow(label: label, ping: ms, word: "Работает", kind: "ok")
    }

    /// https://reedapp.ru/app/login?t=… → t (вход по ссылке из бота).
    static func linkLoginToken(_ s: String) -> String? {
        guard let u = URLComponents(string: s), u.host?.hasSuffix("reedapp.ru") == true,
              u.path.hasPrefix("/app/login") else { return nil }
        return u.queryItems?.first { $0.name == "t" }?.value
    }

    /// Reed-ссылка подписки …/sub/<token> → token.
    static func reedSubToken(_ s: String) -> String? {
        guard (s.contains("reedapp.ru") || s.contains("reed-vpn.duckdns.org")), s.contains("/sub/") else { return nil }
        var t = s.components(separatedBy: "?")[0].components(separatedBy: "#")[0]
        while t.hasSuffix("/") { t.removeLast() }
        let token = t.components(separatedBy: "/").last ?? ""
        guard (8...64).contains(token.count),
              token.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else { return nil }
        return token
    }

    // MARK: названия серверов (как ReedServerNaming в Android)

    nonisolated static let subFast = "Быстрый обход белых списков"
    nonisolated static let subStable = "Надёжный · подключение ~20 с"
    nonisolated static let subViaMoscow = "Через Москву"
    nonisolated static let countryNames: [String: String] = [
        "DE": "Германия", "NL": "Нидерланды", "FI": "Финляндия", "SE": "Швеция", "PL": "Польша", "US": "США",
        "RU": "Россия", "TR": "Турция", "CH": "Швейцария", "FR": "Франция", "GB": "Великобритания",
        "KZ": "Казахстан", "LV": "Латвия", "EE": "Эстония", "AT": "Австрия",
    ]
    nonisolated static let cities: [String: String] = [
        "DE": "Франкфурт", "NL": "Амстердам", "FI": "Хельсинки", "SE": "Стокгольм", "PL": "Варшава", "US": "Нью-Йорк",
        "RU": "Москва", "TR": "Стамбул", "CH": "Цюрих", "FR": "Париж", "GB": "Лондон", "KZ": "Алматы",
        "LV": "Рига", "EE": "Таллин", "AT": "Вена",
    ]

    /// Служебное имя («🇪🇺 ЛТЕ тест (GCP)», «🇳🇱 SMART-Нидерланды 2», «🇩🇪 LTE-Германия») → страна, флаг, подпись.
    nonisolated static func describeServer(_ raw: String, olc: Bool) -> (iso: String, title: String, subtitle: String, network: String) {
        var iso = flagISO(raw) ?? ""
        let stripped = displayName(raw)
        let upper = raw.uppercased()
        let fast = !olc && (upper.contains("ЛТЕ") || upper.contains("LTE"))
        if fast && (iso.isEmpty || iso == "EU") { iso = upper.contains("GCP") ? "PL" : "DE" }
        if iso.isEmpty { iso = countryNames.first(where: { stripped.hasPrefix($0.value) })?.key ?? "" }
        let title = countryNames[iso] ?? stripped
        let viaMoscow = upper.contains("BRIDGE") || stripped.range(of: #"\s\d+\s*$"#, options: .regularExpression) != nil
        if olc { return (iso, title, subStable, "cell") }
        if fast { return (iso, title, subFast, "cell") }
        if viaMoscow { return (iso, title, subViaMoscow, "wifi") }
        return (iso, title, cities[iso] ?? "", "wifi")
    }

    /// «🇩🇪 SMART-Германия» → «Германия».
    nonisolated static func displayName(_ raw: String) -> String {
        var s = raw.unicodeScalars.filter { !($0.properties.isEmojiPresentation || (0x1F1E6...0x1F1FF).contains($0.value) || $0.value == 0xFE0F) }
            .reduce(into: "") { $0.unicodeScalars.append($1) }
        s = s.trimmingCharacters(in: .whitespaces)
        for p in ["SMART-", "SMART ", "BRIDGE-", "BRIDGE ", "LTE-", "ЛТЕ "] where s.uppercased().hasPrefix(p) {
            s = String(s.dropFirst(p.count))
        }
        return s.isEmpty ? raw : s.trimmingCharacters(in: .whitespaces)
    }

    /// Флаг-эмодзи в имени → ISO-2.
    nonisolated static func flagISO(_ raw: String) -> String? {
        let sc = Array(raw.unicodeScalars)
        for i in 0..<max(0, sc.count - 1) {
            let a = sc[i].value, b = sc[i + 1].value
            if (0x1F1E6...0x1F1FF).contains(a) && (0x1F1E6...0x1F1FF).contains(b) {
                let x = UnicodeScalar(a - 0x1F1E6 + 65)!, y = UnicodeScalar(b - 0x1F1E6 + 65)!
                return String(Character(x)) + String(Character(y))
            }
        }
        return nil
    }
}


/// Нативный порт CodeKind (ТЗ 4.2).
enum CodeKindDetector {
    enum Kind { case subscriptionLink, familyInvite, deviceCode, accountCode }
    static func detect(_ raw: String) -> Kind? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        let lower = s.lowercased()
        for p in ["https://", "http://", "vless://", "vmess://", "trojan://", "ss://", "ssconf://"] where lower.hasPrefix(p) {
            return .subscriptionLink
        }
        let upper = s.uppercased()
        if upper.hasPrefix("RDI-") { return .familyInvite }
        if upper.hasPrefix("RDX-") { return .deviceCode }
        return .accountCode
    }
    static func chip(_ k: Kind) -> String {
        switch k {
        case .subscriptionLink: return "Ссылка подписки — добавим её серверы"
        case .familyInvite: return "Приглашение — войдёшь в семью владельца"
        case .deviceCode: return "Код устройства — войдёшь в свой аккаунт"
        case .accountCode: return "Код входа — откроется твой аккаунт"
        }
    }
}
