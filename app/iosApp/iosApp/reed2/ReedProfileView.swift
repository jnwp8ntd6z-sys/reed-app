import SwiftUI

/// Reed 2.0 — Профиль iOS (ТЗ 4.6). Долгое нажатие 1,5 с на «ПРОФИЛЬ» — выгрузка логов.
/// На iOS нет ни бота, ни оплаты (ТЗ 6.8).
struct ReedProfileView: View {
    @EnvironmentObject var m: ReedAppModel
    var onScanQr: () -> Void

    @State private var confirmLogout = false
    @State private var confirmDelete = false
    @State private var servicesOpen = false
    @State private var logsTick = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("ПРОФИЛЬ").font(.reedTitle()).foregroundStyle(Reed.ink).padding(.top, 8)
                    .onLongPressGesture(minimumDuration: 1.5) { logsTick += 1; m.exportLogs() }
                    .sensoryFeedback(.impact(weight: .light), trigger: logsTick)

                if m.token == nil {
                    ReedConnectCard(onEnterCode: { m.openCodes(from: .app) }, onScanQr: onScanQr).padding(.top, 18)
                    section("ПОМОЩЬ")
                    group {
                        navRow("bubble.left", "Написать в поддержку", "Ответим прямо в приложении", badge: m.supportUnread) { m.openSupport() }
                    }
                } else {
                    accountCard.padding(.top, 16)
                    netCard.padding(.top, 14)

                    section("ПОДКЛЮЧЕНИЕ")
                    group {
                        toggleRow("bolt", "Автоподключение", "При запуске", isOn: Binding(get: { m.autoConnect }, set: { m.setAutoConnect($0) }))
                        divider
                        toggleRow("globe", "Российские сайты напрямую", "Мимо туннеля", isOn: Binding(get: { m.ruDirect }, set: { m.setRuDirect($0) }))
                        divider
                        navRow("square.grid.2x2", "Сервисы напрямую", servicesSubtitle) { servicesOpen = true }
                        divider
                        navRow("bell", "Уведомления", nil) { m.showNotifications = true }
                    }

                    section("ПОМОЩЬ")
                    group {
                        navRow("bubble.left", "Написать в поддержку", m.supportUnread ? "Есть ответ" : nil, badge: m.supportUnread) { m.openSupport() }
                        divider
                        navRow("doc.text", "Условия и конфиденциальность", nil) { open("https://reedapp.ru/privacy-app") }
                    }

                    section("АККАУНТ")
                    group {
                        navRow("key", "Войти по другому коду", nil) { m.openCodes(from: .app) }
                        divider
                        navRow("rectangle.portrait.and.arrow.right", "Выйти", nil) { confirmLogout = true }
                        divider
                        navRow("trash", "Удалить аккаунт", nil, danger: true) { confirmDelete = true }
                    }
                }

                Text(Self.versionLabel).font(.reedMono(12)).foregroundStyle(Reed.chrome600)
                    .frame(maxWidth: .infinity).padding(.top, 24)
            }
            .padding(.horizontal, 20).padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(Reed.screenBackground.ignoresSafeArea())
        .confirmationDialog("Выйти из аккаунта?", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("Выйти", role: .destructive) { m.logout() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Серверы аккаунта пропадут с этого устройства. Вернуться можно по коду.")
        }
        .confirmationDialog("Удалить аккаунт навсегда?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Удалить аккаунт", role: .destructive) { m.deleteAccount() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Удалим аккаунт, подписки и все устройства. Отменить это нельзя.")
        }
        .sheet(isPresented: $servicesOpen) {
            ReedServicesDirectView().environmentObject(m)
                .presentationDetents([.medium, .large])
                .presentationBackground(.regularMaterial)
        }
    }

    // MARK: карточки

    private var accountCard: some View {
        let prof = m.sub?.profile
        let name: String = {
            if let u = prof?.username, !u.isEmpty { return "@" + u }
            if let n = prof?.name, !n.isEmpty { return n }
            if let n = ReedSessionStore.memberName, !n.isEmpty { return n }
            return "Аккаунт"
        }()
        let sub: String = {
            if ReedSessionStore.joinedViaCode { return "Участник семьи" }
            if m.sub?.subscription.status == "active" { return "Подписка активна \(ReedFormat.until(m.sub?.subscription.expires_at))" }
            return m.subLoaded ? "Подписка закончилась" : "Загружаем подписку…"
        }()
        return HStack(spacing: 12) {
            Text(String(name.replacingOccurrences(of: "@", with: "").prefix(1)).uppercased())
                .font(.system(size: 18, weight: .semibold)).foregroundStyle(Reed.ink)
                .frame(width: 48, height: 48)
                .overlay(Circle().strokeBorder(AngularGradient(colors: [Reed.chrome050, Reed.chrome400, Reed.chrome800, Reed.chrome200, Reed.chrome050], center: .center), lineWidth: 1.5))
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
                Text(sub).font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
            }
            Spacer()
        }
        .padding(16)
        .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
    }

    private var netCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Проверка сети").font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
                Spacer()
                if let at = m.netCheckedAt {
                    TimelineView(.periodic(from: .now, by: 30)) { ctx in
                        Text(ReedFormat.relative(ctx.date.timeIntervalSince(at))).font(.system(size: 12)).foregroundStyle(Reed.inkMuted)
                    }
                }
            }
            Text("Reed проверяет каждый тип подключения на твоей сети")
                .font(.system(size: 13)).foregroundStyle(Reed.inkMuted).padding(.top, 6)
            VStack(spacing: 0) {
                ForEach(m.netRows) { r in
                    HStack(spacing: 12) {
                        Image(systemName: r.label == "Wi-Fi" ? "wifi" : (r.label == "Мобильный" ? "antenna.radiowaves.left.and.right" : "globe"))
                            .font(.system(size: 16)).foregroundStyle(Reed.inkMuted).frame(width: 22)
                        Text(r.label).font(.system(size: 15)).foregroundStyle(Reed.ink)
                        Spacer()
                        Text(Reed.pingText(r.ping)).font(.reedMono(13)).foregroundStyle(Reed.inkMuted)
                        Circle().fill(color(r.kind)).frame(width: 7, height: 7)
                        Text(r.word).font(.system(size: 13)).foregroundStyle(color(r.kind))
                    }
                    .padding(.vertical, 9)
                    .animation(.smooth(duration: 0.3), value: r)
                }
            }
            .padding(.top, 8)
            Button { m.runNetCheck() } label: {
                HStack(spacing: 8) {
                    if m.netChecking { ProgressView().tint(Reed.onInk) } else { Image(systemName: "waveform.path.ecg") }
                    Text(m.netChecking ? "Проверяем…" : "Проверить сеть").font(.system(size: 15, weight: .semibold))
                }
                .foregroundStyle(Reed.onInk)
                .frame(maxWidth: .infinity).frame(height: 48)
                .background(Reed.ink, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(m.netChecking)
            .padding(.top, 10)
        }
        .padding(16)
        .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
    }

    private var servicesSubtitle: String {
        let n = m.directServices.count
        return n == 0 ? "Выбери сервисы, которые идут мимо туннеля"
            : "\(n) \(ReedFormat.plural(n, "сервис", "сервиса", "сервисов")) напрямую"
    }

    // MARK: элементы

    private func color(_ kind: String) -> Color {
        switch kind {
        case "ok": return Reed.statusOk
        case "warn": return Reed.statusWarn
        case "muted": return Reed.inkMuted
        default: return Reed.statusDanger
        }
    }

    private func section(_ t: String) -> some View {
        Text(t).font(.system(size: 12, weight: .semibold)).kerning(1.5).foregroundStyle(Reed.inkMuted)
            .padding(.top, 22).padding(.bottom, 8)
    }

    private func group<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(spacing: 0) { content() }
            .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
    }

    private var divider: some View {
        Rectangle().fill(Reed.hairline).frame(height: 1).padding(.leading, 58)
    }

    private func icon(_ name: String, danger: Bool = false) -> some View {
        Image(systemName: name).font(.system(size: 15)).foregroundStyle(danger ? Reed.statusDanger : Reed.ink)
            .frame(width: 32, height: 32)
            .background(Reed.surface300, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
    }

    private func toggleRow(_ ic: String, _ title: String, _ sub: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            icon(ic)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundStyle(Reed.ink)
                Text(sub).font(.system(size: 12)).foregroundStyle(Reed.inkMuted)
            }
            Spacer()
            Toggle("", isOn: isOn).labelsHidden().tint(Reed.statusOk)
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
    }

    private func navRow(_ ic: String, _ title: String, _ sub: String?, danger: Bool = false, badge: Bool = false,
                        action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                icon(ic, danger: danger)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15)).foregroundStyle(danger ? Reed.statusDanger : Reed.ink)
                    if let sub { Text(sub).font(.system(size: 12)).foregroundStyle(Reed.inkMuted) }
                }
                Spacer()
                if badge {
                    Circle().fill(Reed.statusWarn).frame(width: 8, height: 8)
                        .transition(.scale.combined(with: .opacity))
                }
                Image(systemName: "chevron.right").font(.system(size: 13)).foregroundStyle(Reed.chrome600)
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.7), value: badge)
            .padding(.horizontal, 14).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func open(_ s: String) { if let u = URL(string: s) { UIApplication.shared.open(u) } }

    static var versionLabel: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "2.0"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "Reed \(v) · сборка \(b)"
    }
}

/// «Сервисы напрямую» (ТЗ 4.6, iOS): раздельный туннель по приложениям на iOS невозможен,
/// поэтому — по сервисам (домены идут мимо туннеля правилами sing-box).
struct ReedServicesDirectView: View {
    @EnvironmentObject var m: ReedAppModel
    @Environment(\.dismiss) private var dismiss

    static let services: [(id: String, name: String, icon: String)] = [
        ("gosuslugi", "Госуслуги", "building.columns"), ("sber", "Сбер", "creditcard"),
        ("tbank", "Т-Банк", "creditcard"), ("vtb", "ВТБ", "creditcard"), ("alfa", "Альфа-Банк", "creditcard"),
        ("ozon", "Ozon", "shippingbox"), ("wildberries", "Wildberries", "bag"), ("yandex", "Яндекс", "magnifyingglass"),
        ("vk", "VK", "person.2"), ("avito", "Авито", "tag"), ("mts", "МТС", "antenna.radiowaves.left.and.right"),
        ("megafon", "МегаФон", "antenna.radiowaves.left.and.right"), ("beeline", "Билайн", "antenna.radiowaves.left.and.right"),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Self.services, id: \.id) { s in
                        Button { withAnimation(.smooth(duration: 0.2)) { m.toggleDirectService(s.id) } } label: {
                            HStack(spacing: 12) {
                                Image(systemName: s.icon).frame(width: 24).foregroundStyle(Reed.inkMuted)
                                Text(s.name).foregroundStyle(Reed.ink)
                                Spacer()
                                Image(systemName: m.directServices.contains(s.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(m.directServices.contains(s.id) ? Reed.statusOk : Reed.chrome600)
                                    .contentTransition(.symbolEffect(.replace))
                            }
                        }
                        .sensoryFeedback(.selection, trigger: m.directServices.contains(s.id))
                    }
                } footer: {
                    Text("Выбранные сервисы открываются напрямую, мимо туннеля — удобно для банков и госуслуг. Применяется при следующем подключении.")
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle("Сервисы напрямую")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
    }
}
