import SwiftUI

/// Reed 2.0 — корень iOS: вход → ввод кода → приложение (три раздела).
/// На iOS 26 — системный TabView (Liquid Glass «из коробки»), на 17–25 — своя стеклянная капсула.
struct ReedRootView: View {
    @EnvironmentObject var m: ReedAppModel
    @Environment(\.scenePhase) private var phase
    @State private var scanner = false

    var body: some View {
        ZStack(alignment: .top) {
            Reed.screenBackground.ignoresSafeArea()
            Group {
                if m.memberBlocked && m.stage == .app {
                    ReedBlockedView().transition(.opacity)
                } else {
                    switch m.stage {
                    case .login:
                        ReedLoginView(
                            consent: Binding(get: { m.consent }, set: { m.acceptConsent($0) }),
                            onOpenCodeEntry: { m.openCodes(from: .login) },
                            onScanQr: { scanner = true },
                            onContinueWithoutCode: { m.continueWithoutCode() },
                            onTerms: { open("https://reedapp.ru/terms") },
                            onPrivacy: { open("https://reedapp.ru/privacy-app") }
                        )
                        .transition(.asymmetric(insertion: .opacity, removal: .opacity.combined(with: .move(edge: .leading))))
                    case .codes:
                        ReedCodeEntryView(onScanQr: { scanner = true })
                            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                                    removal: .move(edge: .trailing).combined(with: .opacity)))
                    case .app:
                        ReedTabsView(onScanQr: { scanner = true })
                            .transition(.opacity)
                    }
                }
            }
            .animation(.smooth(duration: 0.38), value: m.stage)

            if let t = m.toast {
                Text(t)
                    .font(.system(size: 14, weight: .medium)).foregroundStyle(Reed.ink)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .reedGlass(Capsule())
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(10)
            }
        }
        .sheet(isPresented: $scanner) {
            ReedQRScannerSheet { m.scanned($0) }
                .presentationBackground(.black)
        }
        .sheet(item: Binding(get: { m.shareLogURL.map(ShareItem.init) }, set: { if $0 == nil { m.shareLogURL = nil } })) { item in
            ActivityView(items: [item.url]).ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $m.showNotifications) {
            ReedNotificationsView().environmentObject(m)
        }
        .onAppear { m.onAppear() }
        .onChange(of: phase) { old, new in
            if new == .active && old == .background { m.onForeground() }
        }
        .onOpenURL { m.scanned($0.absoluteString) }
        .preferredColorScheme(.dark)
    }

    private func open(_ s: String) { if let u = URL(string: s) { UIApplication.shared.open(u) } }
}

struct ShareItem: Identifiable { let url: URL; var id: String { url.absoluteString } }

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Разделы

struct ReedTabsView: View {
    @EnvironmentObject var m: ReedAppModel
    var onScanQr: () -> Void

    var body: some View {
        if #available(iOS 26, *) {
            TabView(selection: $m.tab) {
                Tab("Главная", systemImage: "house", value: ReedAppModel.Tab.home) { ReedHomeView(onScanQr: onScanQr) }
                Tab("Семья", systemImage: "person.2", value: ReedAppModel.Tab.family) { ReedFamilyView(onScanQr: onScanQr) }
                Tab("Профиль", systemImage: "person", value: ReedAppModel.Tab.profile) { ReedProfileView(onScanQr: onScanQr) }
            }
            .tint(Reed.ink)
        } else {
            ZStack {
                switch m.tab {
                case .home: ReedHomeView(onScanQr: onScanQr).transition(.opacity)
                case .family: ReedFamilyView(onScanQr: onScanQr).transition(.opacity)
                case .profile: ReedProfileView(onScanQr: onScanQr).transition(.opacity)
                }
            }
            .animation(.smooth(duration: 0.25), value: m.tab)
            .safeAreaInset(edge: .bottom) { ReedCapsuleTabBar(selection: $m.tab) }
        }
    }
}

/// Плавающая стеклянная капсула (iOS 17–25). Подсветка выбранного раздела переезжает пружиной.
struct ReedCapsuleTabBar: View {
    @Binding var selection: ReedAppModel.Tab
    @Namespace private var ns
    private let items: [(ReedAppModel.Tab, String, String)] = [
        (.home, "Главная", "house"), (.family, "Семья", "person.2"), (.profile, "Профиль", "person"),
    ]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(items, id: \.0) { item in
                let active = selection == item.0
                Button {
                    withAnimation(.spring(response: 0.38, dampingFraction: 0.82)) { selection = item.0 }
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: item.2).font(.system(size: 19, weight: .regular))
                        Text(item.1).font(.system(size: 10.5, weight: active ? .semibold : .regular))
                    }
                    .foregroundStyle(active ? Reed.ink : Reed.inkMuted)
                    .frame(maxWidth: .infinity).frame(height: 54)
                    .background {
                        if active {
                            Capsule().fill(Color.white.opacity(0.12)).matchedGeometryEffect(id: "pill", in: ns)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .sensoryFeedback(.selection, trigger: active)
            }
        }
        .padding(5)
        .reedGlass(Capsule())
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.08), lineWidth: 1))
        .padding(.horizontal, 20)
        .padding(.bottom, 6)
    }
}

// MARK: - Уведомления

struct ReedNotificationsView: View {
    @EnvironmentObject var m: ReedAppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                ReedBackButton { dismiss() }
                Text("Уведомления").font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
                Spacer()
            }
            .padding(.top, 8)
            if m.notifications.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "bell.slash").font(.system(size: 34)).foregroundStyle(Reed.chrome600)
                    Text("Уведомлений пока нет").font(.system(size: 15)).foregroundStyle(Reed.inkMuted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(m.notifications.sorted { $0.id > $1.id }) { n in
                            let isDevice = n.kind == "new_device"
                            Button {
                                if isDevice { dismiss(); m.tab = .family }
                            } label: {
                                HStack(alignment: .top, spacing: 10) {
                                    if n.id > m.notifSeen {
                                        Circle().fill(isDevice ? Reed.statusWarn : Reed.chrome200)
                                            .frame(width: 8, height: 8).padding(.top, 6)
                                    }
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(n.title ?? "Уведомление").font(.system(size: 15, weight: .semibold)).foregroundStyle(Reed.ink)
                                        if let b = n.body, !b.isEmpty {
                                            Text(b).font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                                                .multilineTextAlignment(.leading)
                                        }
                                        Text(ReedFormat.relative(n.created_at)).font(.system(size: 12)).foregroundStyle(Reed.chrome600)
                                    }
                                    Spacer(minLength: 0)
                                    if isDevice { Image(systemName: "chevron.right").foregroundStyle(Reed.chrome600) }
                                }
                                .padding(14)
                                .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 18)
                }
            }
        }
        .padding(.horizontal, 20)
        .background(Reed.screenBackground.ignoresSafeArea())
        .onDisappear { m.markNotificationsSeen() }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Мелкие общие элементы

struct ReedBackButton: View {
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundStyle(Reed.ink)
                .frame(width: 40, height: 40).reedGlass(Circle(), interactive: true)
        }
        .buttonStyle(.plain)
    }
}

struct ReedBlockedView: View {
    @EnvironmentObject var m: ReedAppModel
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "shield.lefthalf.filled").font(.system(size: 34)).foregroundStyle(Reed.statusWarn)
                .frame(width: 72, height: 72).reedGlass(RoundedRectangle(cornerRadius: 22, style: .continuous))
            Text("Доступ приостановлен").font(.system(size: 20, weight: .semibold)).foregroundStyle(Reed.ink)
            Text("Владелец подписки приостановил твой доступ. Когда он его вернёт, всё заработает само.")
                .font(.system(size: 15)).foregroundStyle(Reed.inkMuted).multilineTextAlignment(.center)
            Button("Выйти") { m.logout() }
                .font(.system(size: 15, weight: .medium)).foregroundStyle(Reed.ink)
                .frame(maxWidth: .infinity).frame(height: 50)
                .background(Reed.surface300, in: Capsule())
                .padding(.top, 10)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Форматирование дат/трафика (как на Android).
enum ReedFormat {
    static let monthsGen = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля",
                            "августа", "сентября", "октября", "ноября", "декабря"]

    static func parse(_ s: String?, utc: Bool = false) -> Date? {
        guard let s, !s.isEmpty else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        if utc { f.timeZone = TimeZone(identifier: "UTC") }
        for p in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"] {
            f.dateFormat = p
            if let d = f.date(from: String(s.prefix(19))) { return d }
        }
        return nil
    }

    /// «до 15 октября» (год — если не текущий).
    static func until(_ s: String?) -> String {
        guard let d = parse(s) else { return "" }
        let c = Calendar.current
        let y = c.component(.year, from: d)
        let base = "до \(c.component(.day, from: d)) \(monthsGen[c.component(.month, from: d) - 1])"
        return y == c.component(.year, from: Date()) ? base : "\(base) \(y)"
    }

    static func relative(_ s: String?) -> String {
        guard let d = parse(s, utc: true) else { return "" }
        return relative(Date().timeIntervalSince(d))
    }

    static func relative(_ seconds: TimeInterval) -> String {
        let min = Int(seconds / 60)
        switch min {
        case ..<1: return "только что"
        case ..<60: return "\(min) мин назад"
        case ..<(24 * 60): return "\(min / 60) ч назад"
        case ..<(48 * 60): return "вчера"
        default: let d = min / (24 * 60); return "\(d) \(plural(d, "день", "дня", "дней")) назад"
        }
    }

    static func plural(_ n: Int, _ one: String, _ few: String, _ many: String) -> String {
        let m10 = n % 10, m100 = n % 100
        if m10 == 1 && m100 != 11 { return one }
        if (2...4).contains(m10) && !(12...14).contains(m100) { return few }
        return many
    }

    static func gb(_ v: Double) -> String {
        let r = (v * 10).rounded(.down) / 10
        return r == r.rounded() ? String(Int(r)) : String(format: "%.1f", r).replacingOccurrences(of: ".", with: ",")
    }

    static func timer(_ sec: Int) -> String {
        String(format: "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    /// ISO-2 → эмодзи-флаг.
    static func flag(_ iso: String) -> String? {
        let u = iso.uppercased()
        guard u.count == 2, u.allSatisfy({ ("A"..."Z").contains($0) }) else { return nil }
        var s = ""
        for ch in u.unicodeScalars { if let sc = UnicodeScalar(0x1F1E6 + ch.value - 65) { s.unicodeScalars.append(sc) } }
        return s
    }
}
