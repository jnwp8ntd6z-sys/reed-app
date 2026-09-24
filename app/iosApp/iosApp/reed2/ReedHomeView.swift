import SwiftUI

/// Reed 2.0 — Главная iOS (ТЗ 4.4) на живых данных модели.
struct ReedHomeView: View {
    @EnvironmentObject var m: ReedAppModel
    var onScanQr: () -> Void
    @State private var subPicker = false

    private var noCode: Bool { m.token == nil && m.servers.isEmpty }

    private var connState: ReedConnState {
        switch m.tunnelStatus {
        case .connected: return .on
        case .connecting: return .connecting
        case .disconnected, .disconnecting: return .off
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header
                if noCode { noCodeBody } else { accountBody }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(Reed.screenBackground.ignoresSafeArea())
        .refreshable { await refreshAsync() }
        .confirmationDialog("Подписки аккаунта", isPresented: $subPicker, titleVisibility: .visible) {
            ForEach(m.subsList) { item in
                let cur = (item.current ?? false) || item.sub_token == m.token
                Button("\(item.plan_label ?? "Подписка") · ещё \(item.days_left ?? 0) дн.\(cur ? " · сейчас" : "")") {
                    if !cur { m.switchSubscription(item.sub_token) }
                }
            }
            Button("Отмена", role: .cancel) {}
        }
    }

    private func refreshAsync() async {
        m.refreshAll()
        while m.refreshing { try? await Task.sleep(nanoseconds: 150_000_000) }
    }

    // MARK: шапка

    private var header: some View {
        HStack {
            ReedLogo(size: 18)
            Spacer()
            Button { m.showNotifications = true } label: {
                Image(systemName: "bell").font(.system(size: 19)).foregroundStyle(Reed.ink)
                    .frame(width: 44, height: 44).reedGlass(Circle(), interactive: true)
                    .overlay(alignment: .topTrailing) {
                        Circle().fill(Reed.statusWarn).frame(width: 9, height: 9)
                            .offset(x: -3, y: 3)
                            .opacity(m.hasUnread ? 1 : 0)
                            .animation(.smooth(duration: 0.25), value: m.hasUnread)
                    }
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 8)
    }

    // MARK: без кода (ТЗ 4.3)

    private var noCodeBody: some View {
        VStack(spacing: 0) {
            ReedConnectButton(state: .off, size: 150).disabled(true).opacity(0.55).padding(.top, 16)
            HStack(spacing: 8) {
                Circle().fill(Reed.chrome600).frame(width: 8, height: 8)
                Text("Нет подписки").font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
            }
            .padding(.top, 8)
            Text("Добавь код, чтобы подключиться").font(.system(size: 14)).foregroundStyle(Reed.inkMuted).padding(.top, 4)
            ReedConnectCard(onEnterCode: { m.openCodes(from: .app) }, onScanQr: onScanQr).padding(.top, 22)
            HStack(spacing: 8) {
                Image(systemName: "lock").font(.system(size: 13)).foregroundStyle(Reed.chrome600)
                Text("Серверы появятся после входа").font(.system(size: 13)).foregroundStyle(Reed.chrome600)
                Spacer()
            }
            .padding(.top, 16)
        }
    }

    // MARK: с аккаунтом

    private var accountBody: some View {
        VStack(spacing: 0) {
            ReedConnectButton(state: connState, size: 150) { m.toggleConnect() }.padding(.top, 12)

            HStack(spacing: 8) {
                Circle().fill(dotColor).frame(width: 8, height: 8)
                Text(statusWord).font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
                    .contentTransition(.opacity)
            }
            .padding(.top, 8)
            .animation(.smooth(duration: 0.3), value: m.tunnelStatus)

            if connState == .on, let since = m.connectedSince {
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    Text(ReedFormat.timer(max(0, Int(ctx.date.timeIntervalSince(since)))))
                        .font(.reedMono(15)).foregroundStyle(Reed.ink)
                        .monospacedDigit()
                        .padding(.horizontal, 12).padding(.vertical, 5)
                        .background(Reed.surface300, in: Capsule())
                }
                .padding(.top, 8)
                .transition(.opacity.combined(with: .scale(scale: 0.95)))
            }
            if let s = m.selectedServer {
                Text([s.title, s.subtitle].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.system(size: 14)).foregroundStyle(Reed.inkMuted).padding(.top, 6)
            }
            if let e = m.connectError, connState == .off {
                Text(e).font(.system(size: 13)).foregroundStyle(Reed.statusDanger)
                    .multilineTextAlignment(.center).padding(.top, 6).padding(.horizontal, 16)
            }
            if connState == .connecting, m.selectedServer?.olc != nil {
                Text("Мобильные серверы поднимаются чуть дольше — около 20 секунд")
                    .font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                    .multilineTextAlignment(.center).padding(.top, 6).padding(.horizontal, 24)
            }

            subscriptionCard.padding(.top, 18)

            Picker("Сеть", selection: Binding(get: { m.network }, set: { v in withAnimation(.smooth(duration: 0.3)) { m.setNetwork(v) } })) {
                Label("Wi-Fi", systemImage: "wifi").tag("wifi")
                Label("Мобильный", systemImage: "antenna.radiowaves.left.and.right").tag("cell")
            }
            .pickerStyle(.segmented)
            .frame(width: 240)
            .padding(.top, 18)

            HStack {
                Text("СЕРВЕРЫ").font(.system(size: 12, weight: .semibold)).kerning(1.5).foregroundStyle(Reed.inkMuted)
                Spacer()
                pillButton("Пинг", icon: "gauge.with.dots.needle.33percent", spinning: false, pulsing: m.pinging) {
                    Task { await m.pingAll() }
                }
                pillButton("Обновить", icon: "arrow.triangle.2.circlepath", spinning: m.refreshing, pulsing: false) {
                    m.refreshAll()
                }
            }
            .padding(.top, 20)

            serverList.padding(.top, 10)
        }
        .animation(.smooth(duration: 0.3), value: connState)
    }

    private var statusWord: String {
        switch connState {
        case .on: return "Подключено"
        case .connecting: return "Подключаюсь…"
        case .off: return "Не подключено"
        }
    }
    private var dotColor: Color {
        switch connState {
        case .on: return Reed.lime
        case .connecting: return Reed.statusWarn
        case .off: return Reed.chrome600
        }
    }

    @ViewBuilder
    private var subscriptionCard: some View {
        if let sub = m.sub, sub.subscription.status == "active", m.token != nil {
            let used = Double(sub.traffic?.used ?? 0) / 1_073_741_824
            let total = Double(sub.traffic?.total ?? 0) / 1_073_741_824
            Button { if m.subsList.count > 1 { subPicker = true } } label: {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Подписка \(ReedFormat.until(sub.subscription.expires_at))")
                            .font(.system(size: 14, weight: .semibold)).foregroundStyle(Reed.ink).lineLimit(1)
                        Spacer()
                        if total > 0 {
                            Text("\(ReedFormat.gb(used)) / \(ReedFormat.gb(total)) ГБ").font(.reedMono(13)).foregroundStyle(Reed.inkMuted)
                        }
                        if m.subsList.count > 1 {
                            Image(systemName: "chevron.up.chevron.down").font(.system(size: 12)).foregroundStyle(Reed.inkMuted)
                        }
                    }
                    if total > 0 { ChromeProgress(value: min(1, used / total)) }
                    Text(footnote(sub)).font(.system(size: 12)).foregroundStyle(Reed.inkMuted)
                }
                .padding(16)
                .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
            }
            .buttonStyle(.plain)
        } else if m.token != nil {
            VStack(alignment: .leading, spacing: 6) {
                Text(m.subLoaded ? "Подписка закончилась" : "Загружаем подписку…")
                    .font(.system(size: 15, weight: .semibold)).foregroundStyle(Reed.ink)
                if m.subLoaded {
                    Text("Серверы станут доступны, когда подписка снова будет активна.")
                        .font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
    }

    private func footnote(_ r: RSubscriptionResponse) -> String {
        var parts: [String] = []
        if let d = r.subscription.days_left, d > 0 { parts.append("Осталось \(d) \(ReedFormat.plural(Int(d), "день", "дня", "дней"))") }
        if (r.traffic?.total ?? 0) <= 0 { parts.append("трафик без лимита") }
        let lt = r.lte?.total_gb ?? 0
        parts.append(lt <= 0 ? "мобильный без лимита"
                     : "мобильный \(ReedFormat.gb(r.lte?.used_gb ?? 0)) / \(ReedFormat.gb(lt)) ГБ")
        let s = parts.joined(separator: " · ")
        return s.prefix(1).uppercased() + s.dropFirst()
    }

    private func pillButton(_ title: String, icon: String, spinning: Bool, pulsing: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 13, weight: .medium))
                    .rotationEffect(.degrees(spinning ? 360 : 0))
                    .animation(spinning ? .linear(duration: 0.9).repeatForever(autoreverses: false) : .default, value: spinning)
                    .symbolEffect(.pulse, isActive: pulsing)
                Text(title).font(.system(size: 13, weight: .medium))
            }
            .foregroundStyle(Reed.ink)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .reedGlass(Capsule(), interactive: true)
        }
        .buttonStyle(.plain)
        .disabled(spinning)
    }

    @ViewBuilder
    private var serverList: some View {
        let shown = m.servers.filter { $0.network == m.network }
        if shown.isEmpty {
            Text(m.servers.isEmpty ? "Загружаем твои серверы…"
                 : (m.network == "cell" ? "Мобильных серверов в подписке нет" : "Серверов Wi-Fi в подписке нет"))
                .font(.system(size: 14)).foregroundStyle(Reed.inkMuted)
                .frame(maxWidth: .infinity).padding(.vertical, 18)
        } else {
            VStack(spacing: 2) {
                ForEach(shown) { s in
                    ReedServerRowView(server: s, ping: m.pings[s.id], selected: s.id == m.selectedId) {
                        withAnimation(.smooth(duration: 0.25)) { m.select(s) }
                    }
                }
            }
            .padding(6)
            .background(Reed.surface200.opacity(0.6), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
    }
}

/// Строка сервера: флаг, страна (жирно), город, пинг моно-шрифтом, галочка в круге.
struct ReedServerRowView: View {
    let server: ReedServer
    let ping: Int?
    let selected: Bool
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                ReedFlagBadge(country: server.country)
                VStack(alignment: .leading, spacing: 2) {
                    Text(server.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink).lineLimit(1)
                    if !server.subtitle.isEmpty {
                        Text(server.subtitle).font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                    }
                }
                Spacer()
                Text(Reed.pingText(ping)).font(.reedMono(13)).foregroundStyle(Reed.pingColor(ping))
                    .contentTransition(.numericText())
                    .animation(.smooth(duration: 0.3), value: ping)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(selected ? Reed.ink : Reed.chrome600)
                    .contentTransition(.symbolEffect(.replace))
            }
            .padding(.horizontal, 12).padding(.vertical, 11)
            .background(selected ? Color.white.opacity(0.06) : .clear,
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: selected)
    }
}

/// Флаг 30×20, скругление 5, обводка (ТЗ 3.5). olcRTC — иконка камеры.
struct ReedFlagBadge: View {
    let country: String
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 5, style: .continuous).fill(Reed.surface300)
            if country == "RTC" {
                Image(systemName: "video.fill").font(.system(size: 11)).foregroundStyle(Reed.chrome200)
            } else if let f = ReedFormat.flag(country) {
                Text(f).font(.system(size: 26)).frame(width: 30, height: 20).clipped()
            } else {
                Text(country.isEmpty ? "··" : String(country.prefix(2)).uppercased())
                    .font(.reedMono(10, weight: .medium)).foregroundStyle(Reed.chrome200)
            }
        }
        .frame(width: 30, height: 20)
        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 5, style: .continuous).strokeBorder(Color.white.opacity(0.14), lineWidth: 1))
    }
}

/// Прямая полоса трафика с хромовой заливкой (ТЗ 3.1, iOS).
struct ChromeProgress: View {
    let value: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Reed.chrome800)
                Capsule().fill(LinearGradient(colors: [Reed.chrome400, Reed.chrome050, Reed.chrome200],
                                              startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(6, g.size.width * value))
                    .animation(.smooth(duration: 0.6), value: value)
            }
        }
        .frame(height: 6)
    }
}

/// Карточка «Подключи подписку» (ТЗ 4.3, iOS-текст без упоминания бота).
struct ReedConnectCard: View {
    var onEnterCode: () -> Void
    var onScanQr: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Подключи подписку").font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
            Text("Вставь код, ключ подписки или приглашение от владельца семьи.")
                .font(.system(size: 14)).foregroundStyle(Reed.inkMuted)
            HStack(spacing: 10) {
                Button(action: onEnterCode) {
                    Text("Ввести код").font(.system(size: 15, weight: .semibold)).foregroundStyle(Reed.onInk)
                        .frame(maxWidth: .infinity).frame(height: 48).background(Reed.ink, in: Capsule())
                }
                .buttonStyle(.plain)
                Button(action: onScanQr) {
                    Image(systemName: "qrcode.viewfinder").font(.system(size: 20)).foregroundStyle(Reed.ink)
                        .frame(width: 48, height: 48).reedGlass(Circle(), interactive: true)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 6)
        }
        .padding(16)
        .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
    }
}
