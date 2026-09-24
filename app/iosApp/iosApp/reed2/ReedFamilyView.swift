import SwiftUI

/// Reed 2.0 — Семья iOS (ТЗ 4.5): места, оповещение о новом устройстве, участники, приглашения,
/// свои устройства, код для нового устройства. Действия — нативные листы подтверждения.
struct ReedFamilyView: View {
    @EnvironmentObject var m: ReedAppModel
    var onScanQr: () -> Void

    @State private var memberAction: RMember?
    @State private var deviceActionItem: RDevice?
    @State private var inviteOpen = false
    @State private var deviceOpen = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("СЕМЬЯ").font(.reedTitle()).foregroundStyle(Reed.ink).padding(.top, 8)
                if m.token == nil {
                    ReedConnectCard(onEnterCode: { m.openCodes(from: .app) }, onScanQr: onScanQr).padding(.top, 18)
                } else if ReedSessionStore.joinedViaCode {
                    card {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Подпиской управляет владелец").font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
                            Text("Ты в семье как участник. Места, приглашения и устройства настраивает владелец подписки.")
                                .font(.system(size: 14)).foregroundStyle(Reed.inkMuted)
                        }
                    }
                    .padding(.top, 18)
                } else {
                    ownerBody
                }
            }
            .padding(.horizontal, 20).padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(Reed.screenBackground.ignoresSafeArea())
        .refreshable { await reloadFamily() }
        .onAppear { m.loadFamily() }
        .confirmationDialog(memberAction?.name ?? "Участник", isPresented: Binding(
            get: { memberAction != nil }, set: { if !$0 { memberAction = nil } }), titleVisibility: .visible
        ) {
            if let mem = memberAction {
                if mem.blocked == true {
                    Button("Возобновить доступ") { m.memberAction(mem.id, "unblock", done: "Доступ возобновлён") }
                } else {
                    Button("Приостановить доступ") { m.memberAction(mem.id, "block", done: "Доступ приостановлен") }
                }
                Button("Удалить из семьи", role: .destructive) { m.memberAction(mem.id, "delete", done: "Участник удалён") }
            }
            Button("Отмена", role: .cancel) {}
        }
        .confirmationDialog(deviceActionItem?.name ?? "Устройство", isPresented: Binding(
            get: { deviceActionItem != nil }, set: { if !$0 { deviceActionItem = nil } }), titleVisibility: .visible
        ) {
            if let d = deviceActionItem {
                Button(d.blocked == true ? "Разблокировать" : "Заблокировать") {
                    m.deviceAction(d.id, "toggle_block", done: d.blocked == true ? "Устройство разблокировано" : "Устройство заблокировано")
                }
                Button("Удалить устройство", role: .destructive) { m.deviceAction(d.id, "delete", done: "Устройство удалено") }
            }
            Button("Отмена", role: .cancel) {}
        }
    }

    // MARK: владелец

    @ViewBuilder
    private var ownerBody: some View {
        let d = m.devices
        let all = (d?.devices ?? []) + (d?.blocked ?? [])
        if let limit = d?.limit, limit > 0 {
            let used = d?.used ?? 0
            Text("\(used) из \(limit) мест занято").font(.system(size: 14)).foregroundStyle(Reed.inkMuted).padding(.top, 10)
            HStack(spacing: 6) {
                ForEach(0..<min(limit, 12), id: \.self) { i in
                    Capsule().fill(i < used ? Reed.chrome050 : Reed.chrome800).frame(height: 6)
                }
            }
            .padding(.top, 8)
            .animation(.smooth(duration: 0.4), value: used)
        }

        if let n = m.newDeviceAlert {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "shield.lefthalf.filled").font(.system(size: 20)).foregroundStyle(Reed.onInk)
                        .frame(width: 40, height: 40).background(Reed.statusWarn, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Новое устройство").font(.system(size: 15, weight: .semibold)).foregroundStyle(Reed.ink)
                        Text([n.body ?? "Подключилось новое устройство.", ReedFormat.relative(n.created_at)]
                            .filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                    }
                }
                HStack(spacing: 10) {
                    Button { withAnimation(.smooth) { m.ackNewDevice() } } label: {
                        Text("Это я").font(.system(size: 15, weight: .medium)).foregroundStyle(Reed.ink)
                            .frame(maxWidth: .infinity).frame(height: 44)
                            .overlay(Capsule().strokeBorder(Reed.hairline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    Button {
                        if let id = n.deviceId { m.deviceAction(id, "toggle_block", done: "Устройство заблокировано") }
                        withAnimation(.smooth) { m.ackNewDevice() }
                    } label: {
                        Text("Заблокировать").font(.system(size: 15, weight: .semibold)).foregroundStyle(Reed.onInk)
                            .frame(maxWidth: .infinity).frame(height: 44).background(Reed.ink, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
            .background(Reed.statusWarn.opacity(0.12), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Reed.statusWarn.opacity(0.3), lineWidth: 1))
            .padding(.top, 16)
            .transition(.move(edge: .top).combined(with: .opacity))
        }

        section("УЧАСТНИКИ")
        VStack(spacing: 2) {
            memberRow(initial: String(ownerName.replacingOccurrences(of: "@", with: "").prefix(1)).uppercased(), name: ownerName,
                      subtitle: "Владелец · \(all.count) \(ReedFormat.plural(all.count, "устройство", "устройства", "устройств"))",
                      status: nil) {}
            ForEach(m.members?.members ?? []) { mem in
                memberRow(initial: String((mem.name ?? "У").prefix(1)).uppercased(), name: mem.name ?? "Участник",
                          subtitle: mem.device?.isEmpty == false ? mem.device! : "Участник",
                          status: mem.blocked == true ? "Приостановлен" : nil) { memberAction = mem }
            }
        }
        codePlank(title: "Пригласить участника", subtitle: "Код для близкого человека",
                  code: m.inviteCode, note: "Код действует 1 час. Участник войдёт в твою подписку.",
                  open: $inviteOpen) { m.requestCode(device: false) }
            .padding(.top, 12)

        section("ТВОИ УСТРОЙСТВА")
        VStack(spacing: 8) {
            ForEach(all) { dev in
                Button { deviceActionItem = dev } label: {
                    HStack(spacing: 12) {
                        Image(systemName: Self.deviceIcon(dev)).font(.system(size: 18)).foregroundStyle(Reed.inkMuted).frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(dev.name ?? "Устройство").font(.system(size: 16)).foregroundStyle(dev.blocked == true ? Reed.inkMuted : Reed.ink)
                            Text(dev.blocked == true ? "Заблокировано" : Self.lastSeen(dev.last_seen))
                                .font(.system(size: 13)).foregroundStyle(dev.blocked == true ? Reed.statusDanger : Reed.inkMuted)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.system(size: 13)).foregroundStyle(Reed.chrome600)
                    }
                    .padding(14)
                    .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        codePlank(title: "Добавить устройство", subtitle: "Код для твоего планшета или ноутбука",
                  code: m.deviceCode, note: "Код действует 1 час. Устройство войдёт в твой аккаунт.",
                  open: $deviceOpen,
                  blockedText: (d?.limit ?? 0) > 0 && (d?.used ?? 0) >= (d?.limit ?? 0)
                    ? "Добавлено максимальное количество устройств. Удалите одно устройство — тогда сможете добавить новое." : nil
        ) { m.requestCode(device: true) }
            .padding(.top, 12)
    }

    private func reloadFamily() async {
        m.loadFamily()
        try? await Task.sleep(nanoseconds: 700_000_000)
    }

    // MARK: элементы

    private func section(_ t: String) -> some View {
        Text(t).font(.system(size: 12, weight: .semibold)).kerning(1.5).foregroundStyle(Reed.inkMuted).padding(.top, 22).padding(.bottom, 8)
    }

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
    }

    private func memberRow(initial: String, name: String, subtitle: String, status: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(initial).font(.system(size: 15, weight: .semibold)).foregroundStyle(Reed.ink)
                    .frame(width: 40, height: 40)
                    .overlay(Circle().strokeBorder(AngularGradient(colors: [Reed.chrome050, Reed.chrome400, Reed.chrome800, Reed.chrome200, Reed.chrome050], center: .center), lineWidth: 1.5))
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                }
                Spacer()
                if let status {
                    HStack(spacing: 6) {
                        Circle().fill(Reed.statusDanger).frame(width: 7, height: 7)
                        Text(status).font(.system(size: 13)).foregroundStyle(Reed.statusDanger)
                    }
                }
            }
            .padding(.vertical, 8).padding(.horizontal, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Владелец — имя из Telegram (профиль подписки), а не безликое «Ты».
    private var ownerName: String {
        if let u = m.sub?.profile?.username, !u.isEmpty { return "@" + u }
        if let n = m.sub?.profile?.name, !n.isEmpty { return n }
        return "Владелец"
    }

    private func codePlank(title: String, subtitle: String, code: String?, note: String,
                           open: Binding<Bool>, blockedText: String? = nil,
                           request: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.smooth(duration: 0.32)) { open.wrappedValue.toggle() }
                if open.wrappedValue && blockedText == nil { request() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Reed.ink)
                        Text(subtitle).font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                    }
                    Spacer()
                    Image(systemName: "arrow.up.right").font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.onInk)
                        .frame(width: 40, height: 40).background(Reed.ink, in: Circle())
                        .rotationEffect(.degrees(open.wrappedValue ? 90 : 0))
                }
                .padding(16)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open.wrappedValue, let blockedText {
                Text(blockedText)
                    .font(.system(size: 14)).foregroundStyle(Reed.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(Reed.statusWarn.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .padding([.horizontal, .bottom], 16)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            } else if open.wrappedValue {
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        if let code { UIPasteboard.general.string = code; m.show("Код скопирован") }
                    } label: {
                        HStack {
                            Text(code ?? "Создаём код…").font(.reedMono(18)).foregroundStyle(code == nil ? Reed.chrome600 : Reed.ink)
                            Spacer()
                            if code != nil { Image(systemName: "doc.on.doc").foregroundStyle(Reed.inkMuted) }
                        }
                        .padding(14)
                        .background(Reed.ground000, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    Text(note).font(.system(size: 12)).foregroundStyle(Reed.inkMuted)
                }
                .padding([.horizontal, .bottom], 16)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(Reed.surface300, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    static func deviceIcon(_ d: RDevice) -> String {
        let s = "\(d.os ?? "") \(d.type ?? "") \(d.name ?? "")".lowercased()
        if ["windows", "mac", "linux", "ноутбук", "laptop", "desktop"].contains(where: { s.contains($0) }) { return "laptopcomputer" }
        if s.contains("ipad") || s.contains("tablet") || s.contains("планшет") { return "ipad" }
        return "iphone"
    }

    static func lastSeen(_ s: String?) -> String {
        guard let d = ReedFormat.parse(s, utc: true) else { return "Подключено" }
        let h = Date().timeIntervalSince(d) / 3600
        if h < 24 { return "Сегодня" }
        if h < 48 { return "Вчера" }
        return "Был \(ReedFormat.relative(Date().timeIntervalSince(d)))"
    }
}
