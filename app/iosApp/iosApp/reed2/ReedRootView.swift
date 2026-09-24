import SwiftUI

/// Reed 2.0 — корень iOS. Гейт входа → таб-бар (Главная / Семья / Профиль).
/// TabView на iOS 26 сам получает Liquid Glass; на 17–25 — системный тёмный.
/// Пока демо-данные для валидации нативного вида; подключение к ReedApi/туннелю — следующий шаг.
struct ReedRootView: View {
    enum Stage { case login, codes, app }
    @State private var stage: Stage = .login
    @State private var consent = false
    @State private var conn: ReedConnState = .off
    @State private var selectedTab = 0

    var body: some View {
        Group {
            switch stage {
            case .login:
                ReedLoginView(
                    consent: $consent, showTelegram: false,
                    onOpenCodeEntry: { stage = .codes },
                    onScanQr: { stage = .app },
                    onContinueWithoutCode: { stage = .app }
                )
            case .codes:
                ReedCodeEntryView(onSubmit: { stage = .app }, onBack: { stage = .login })
            case .app:
                TabView(selection: $selectedTab) {
                    ReedHomeView(conn: $conn).tag(0)
                        .tabItem { Label("Главная", systemImage: "house") }
                    ReedFamilyDemoView().tag(1)
                        .tabItem { Label("Семья", systemImage: "person.2") }
                    ReedProfileDemoView(onLogout: { stage = .login }).tag(2)
                        .tabItem { Label("Профиль", systemImage: "person") }
                }
                .tint(Reed.ink)
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// Демо-Главная: кнопка-стекло, статус, таймер, карточка подписки, список серверов.
struct ReedHomeView: View {
    @Binding var conn: ReedConnState
    @State private var selected = "v0"
    private let servers: [(String, String, String, String, Int)] = [
        ("v0", "DE", "Германия", "Франкфурт", 42),
        ("v1", "NL", "Нидерланды", "Амстердам", 48),
        ("v2", "FI", "Финляндия", "Хельсинки", 51),
        ("v3", "TR", "Турция", "Стамбул", 63),
    ]
    private var statusWord: String {
        switch conn { case .on: return "Подключено"; case .connecting: return "Подключаюсь…"; case .off: return "Не подключено" }
    }
    private var dotColor: Color {
        switch conn { case .on: return Reed.lime; case .connecting: return Reed.statusWarn; case .off: return Reed.chrome600 }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HStack {
                    ReedLogo(size: 18)
                    Spacer()
                    Image(systemName: "bell").font(.system(size: 20)).foregroundStyle(Reed.ink)
                        .frame(width: 44, height: 44).reedGlass(Circle())
                }.padding(.top, 8)

                ReedConnectButton(state: conn, size: 150, onTap: {
                    conn = conn == .off ? .connecting : (conn == .connecting ? .on : .off)
                }).padding(.top, 12)

                HStack(spacing: 8) {
                    Circle().fill(dotColor).frame(width: 8, height: 8)
                    Text(statusWord).font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
                }.padding(.top, 8)
                if conn == .on {
                    Text("01:24:07").font(.reedMono(15))
                        .padding(.horizontal, 12).padding(.vertical, 5)
                        .background(Reed.surface300, in: Capsule()).padding(.top, 8)
                }
                Text("Германия · Франкфурт").font(.system(size: 14)).foregroundStyle(Reed.inkMuted).padding(.top, 6)

                // Карточка подписки.
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Подписка до 15 октября").font(.system(size: 14, weight: .semibold)).foregroundStyle(Reed.ink)
                        Spacer()
                        Text("12,4 / 50 ГБ").font(.reedMono(13)).foregroundStyle(Reed.inkMuted)
                    }
                    ProgressView(value: 0.248).tint(Reed.chrome200)
                    Text("Трафик обновится 1 ноября · мобильный без лимита")
                        .font(.system(size: 12)).foregroundStyle(Reed.inkMuted)
                }
                .padding(16)
                .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .padding(.top, 18)

                HStack {
                    Text("СЕРВЕРЫ").font(.system(size: 12, weight: .semibold)).foregroundStyle(Reed.inkMuted).kerning(1.5)
                    Spacer()
                }.padding(.top, 20)

                VStack(spacing: 0) {
                    ForEach(servers, id: \.0) { s in
                        Button(action: { selected = s.0 }) {
                            HStack {
                                ReedFlagView(country: s.1)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(s.2).font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
                                    Text(s.3).font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                                }
                                Spacer()
                                Text(Reed.pingText(s.4)).font(.reedMono(13)).foregroundStyle(Reed.pingColor(s.4))
                                Image(systemName: selected == s.0 ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected == s.0 ? Reed.ink : Reed.chrome600)
                            }
                            .padding(12)
                            .background(selected == s.0 ? Color.white.opacity(0.06) : .clear,
                                        in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                        }.buttonStyle(.plain)
                    }
                }.padding(.top, 10)
            }
            .padding(.horizontal, 20)
        }
        .background(Reed.screenBackground.ignoresSafeArea())
    }
}

/// Флаг-плашка (ТЗ 3.5): пока ISO-2 моно на surface (без набора флагов).
struct ReedFlagView: View {
    let country: String
    var body: some View {
        Text(country.prefix(2)).font(.reedMono(10, weight: .medium)).foregroundStyle(Reed.chrome200)
            .frame(width: 30, height: 20)
            .background(Reed.surface300, in: RoundedRectangle(cornerRadius: 5))
            .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(Color.white.opacity(0.14), lineWidth: 1))
    }
}

struct ReedFamilyDemoView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("СЕМЬЯ").font(.reedTitle()).foregroundStyle(Reed.ink).padding(.top, 8)
                Text("3 из 5 мест занято").font(.system(size: 14)).foregroundStyle(Reed.inkMuted)
                Text("Подключено к данным — следующий шаг.").font(.system(size: 13)).foregroundStyle(Reed.chrome600)
            }.padding(.horizontal, 20).frame(maxWidth: .infinity, alignment: .leading)
        }.background(Reed.screenBackground.ignoresSafeArea())
    }
}

struct ReedProfileDemoView: View {
    var onLogout: () -> Void = {}
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("ПРОФИЛЬ").font(.reedTitle()).foregroundStyle(Reed.ink).padding(.top, 8)
                Text("@username").font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
                Text("Reed 2.0 · превью").font(.reedMono(12)).foregroundStyle(Reed.chrome600)
                Button("Выйти", action: onLogout).foregroundStyle(Reed.ink).padding(.top, 8)
            }.padding(.horizontal, 20).frame(maxWidth: .infinity, alignment: .leading)
        }.background(Reed.screenBackground.ignoresSafeArea())
    }
}
