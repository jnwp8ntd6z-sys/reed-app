import SwiftUI

/// Reed 2.0 — чат поддержки прямо в приложении. Сообщения уходят операторам в бота поддержки,
/// их ответы приходят сюда же (опрос раз в 3 с, пока чат открыт).
///
/// Анимации: новое сообщение въезжает снизу на пружине, лента плавно доезжает до конца,
/// кнопка отправки «проявляется», когда есть текст; статус «отправляется» мягко пульсирует.
struct ReedSupportView: View {
    @EnvironmentObject var m: ReedAppModel
    @FocusState private var focused: Bool
    @State private var sentTick = 0
    @State private var chipsShown = false

    private static let chips = ["Не подключается", "Медленно работает", "Не открывается сайт", "Вопрос по подписке"]

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        if m.chat.isEmpty {
                            emptyState
                                .padding(.top, 36)
                                .transition(.opacity.combined(with: .scale(scale: 0.97)))
                        }
                        ForEach(Array(m.chat.enumerated()), id: \.element.id) { i, item in
                            if dayChanges(at: i) { daySeparator(item.date) }
                            bubble(item, firstInGroup: firstInGroup(i), lastInGroup: lastInGroup(i))
                                .id(item.id)
                                .transition(.asymmetric(
                                    insertion: .move(edge: .bottom).combined(with: .opacity)
                                        .combined(with: .scale(scale: 0.92, anchor: item.mine ? .bottomTrailing : .bottomLeading)),
                                    removal: .opacity))
                        }
                        Color.clear.frame(height: 6).id("bottom")
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 8)
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)
                .defaultScrollAnchor(.bottom)
                .onChange(of: m.chat.count) { _, _ in
                    withAnimation(.smooth(duration: 0.35)) { proxy.scrollTo("bottom", anchor: .bottom) }
                }
                .onChange(of: focused) { _, isOn in
                    guard isOn else { return }
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 280_000_000)
                        withAnimation(.smooth(duration: 0.3)) { proxy.scrollTo("bottom", anchor: .bottom) }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { inputBar }
        .background(Reed.screenBackground.ignoresSafeArea())
        .animation(.smooth(duration: 0.3), value: m.chat.isEmpty)
        .sensoryFeedback(.impact(weight: .light), trigger: sentTick)
        .preferredColorScheme(.dark)
    }

    // MARK: шапка

    private var header: some View {
        HStack(spacing: 12) {
            ReedBackButton { m.closeSupport() }
            VStack(alignment: .leading, spacing: 2) {
                Text("Поддержка").font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
                HStack(spacing: 6) {
                    Circle().fill(Reed.statusOk).frame(width: 7, height: 7)
                    Text("Ответим здесь же").font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    // MARK: пустой чат

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 26)).foregroundStyle(Reed.ink)
                .frame(width: 64, height: 64)
                .reedGlass(Circle())
            Text("Напиши, что случилось").font(.system(size: 18, weight: .semibold)).foregroundStyle(Reed.ink)
            Text("Опиши проблему своими словами — ответим прямо в этом чате.")
                .font(.system(size: 14)).foregroundStyle(Reed.inkMuted)
                .multilineTextAlignment(.center).padding(.horizontal, 30)
            FlowChips(items: Self.chips, shown: chipsShown) { chip in
                m.chatDraft = chip
                focused = true
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
        .onAppear { withAnimation(.spring(response: 0.5, dampingFraction: 0.85).delay(0.1)) { chipsShown = true } }
    }

    // MARK: сообщения

    private func firstInGroup(_ i: Int) -> Bool {
        i == 0 || m.chat[i - 1].mine != m.chat[i].mine || dayChanges(at: i)
            || m.chat[i].date.timeIntervalSince(m.chat[i - 1].date) > 300
    }
    private func lastInGroup(_ i: Int) -> Bool { i == m.chat.count - 1 || firstInGroup(i + 1) }
    private func dayChanges(at i: Int) -> Bool {
        i == 0 || !Calendar.current.isDate(m.chat[i - 1].date, inSameDayAs: m.chat[i].date)
    }

    private func daySeparator(_ d: Date) -> some View {
        Text(Self.dayTitle(d))
            .font(.system(size: 12, weight: .medium)).foregroundStyle(Reed.inkMuted)
            .padding(.horizontal, 12).padding(.vertical, 5)
            .reedGlass(Capsule())
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
    }

    private func bubble(_ item: ReedChatItem, firstInGroup: Bool, lastInGroup: Bool) -> some View {
        // Группа подряд идущих сообщений: со стороны отправителя углы малые (как «хвостик» и стык),
        // у первого в группе верхний угол большой.
        let big: CGFloat = 20, small: CGFloat = 6
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: item.mine ? big : (firstInGroup ? big : small),
            bottomLeadingRadius: item.mine ? big : small,
            bottomTrailingRadius: item.mine ? small : big,
            topTrailingRadius: item.mine ? (firstInGroup ? big : small) : big,
            style: .continuous)
        return VStack(alignment: item.mine ? .trailing : .leading, spacing: 4) {
            if firstInGroup && !item.mine {
                Text("Поддержка Reed").font(.system(size: 12, weight: .medium)).foregroundStyle(Reed.inkMuted)
                    .padding(.leading, 6).padding(.top, 6)
            }
            HStack(spacing: 0) {
                if item.mine { Spacer(minLength: 56) }
                Text(Self.linkified(item.text))
                    .font(.system(size: 16))
                    .foregroundStyle(item.mine ? Reed.onInk : Reed.ink)
                    .tint(item.mine ? Reed.onInk : Reed.chrome200)
                    .textSelection(.enabled)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(item.mine ? AnyShapeStyle(Reed.ink) : AnyShapeStyle(Reed.surface200), in: shape)
                    .overlay(shape.strokeBorder(item.mine ? Color.clear : Reed.hairline, lineWidth: 1))
                    .opacity(item.state == .failed ? 0.6 : 1)
                if !item.mine { Spacer(minLength: 56) }
            }
            if lastInGroup || item.state != .sent {
                meta(item).padding(.horizontal, 6)
            }
        }
        .padding(.top, firstInGroup ? 8 : 2)
    }

    @ViewBuilder
    private func meta(_ item: ReedChatItem) -> some View {
        HStack(spacing: 5) {
            switch item.state {
            case .failed:
                Button { m.retryChat(item.id) } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "exclamationmark.circle.fill")
                        Text("Не отправлено · Повторить")
                    }
                    .font(.system(size: 12, weight: .medium)).foregroundStyle(Reed.statusDanger)
                }
                .buttonStyle(.plain)
            case .sending:
                Text(Self.time(item.date)).font(.reedMono(11)).foregroundStyle(Reed.chrome600)
                Image(systemName: "clock").font(.system(size: 10)).foregroundStyle(Reed.chrome600)
                    .symbolEffect(.pulse, isActive: true)
            case .sent:
                Text(Self.time(item.date)).font(.reedMono(11)).foregroundStyle(Reed.chrome600)
                if item.mine {
                    Image(systemName: "checkmark").font(.system(size: 10, weight: .semibold)).foregroundStyle(Reed.chrome600)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        }
        .contentTransition(.opacity)
    }

    // MARK: поле ввода

    private var canSend: Bool { !m.chatDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Сообщение", text: $m.chatDraft, axis: .vertical)
                .lineLimit(1...6)
                .font(.system(size: 16))
                .foregroundStyle(Reed.ink)
                .tint(Reed.ink)
                .focused($focused)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .reedGlass(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.white.opacity(0.08), lineWidth: 1))
            Button {
                guard canSend else { return }
                m.sendChat()
                sentTick += 1
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Reed.onInk)
                    .frame(width: 44, height: 44)
                    .background(Reed.ink, in: Circle())
            }
            .buttonStyle(ReedPressStyle())
            .scaleEffect(canSend ? 1 : 0.82)
            .opacity(canSend ? 1 : 0.35)
            .animation(.spring(response: 0.32, dampingFraction: 0.72), value: canSend)
            .disabled(!canSend)
            .accessibilityLabel("Отправить")
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background {
            LinearGradient(colors: [Reed.ground100.opacity(0), Reed.ground100], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
        }
    }

    // MARK: форматирование

    static func time(_ d: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "ru_RU"); f.dateFormat = "HH:mm"
        return f.string(from: d)
    }

    static func dayTitle(_ d: Date) -> String {
        let c = Calendar.current
        if c.isDateInToday(d) { return "Сегодня" }
        if c.isDateInYesterday(d) { return "Вчера" }
        let base = "\(c.component(.day, from: d)) \(ReedFormat.monthsGen[c.component(.month, from: d) - 1])"
        return c.component(.year, from: d) == c.component(.year, from: Date()) ? base : "\(base) \(c.component(.year, from: d))"
    }

    /// Ссылки в тексте становятся нажимаемыми (операторы часто присылают инструкции).
    static func linkified(_ text: String) -> AttributedString {
        var a = AttributedString(text)
        guard let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return a }
        let ns = text as NSString
        for match in det.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let url = match.url, let r = Range(match.range, in: text),
                  let lo = AttributedString.Index(r.lowerBound, within: a),
                  let hi = AttributedString.Index(r.upperBound, within: a) else { continue }
            a[lo..<hi].link = url
            a[lo..<hi].underlineStyle = .single
        }
        return a
    }
}

/// Подсказки-«чипы» с переносом строк; появляются по очереди.
struct FlowChips: View {
    let items: [String]
    let shown: Bool
    var onTap: (String) -> Void

    var body: some View {
        ReedFlowLayout(spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, s in
                Button { onTap(s) } label: {
                    Text(s).font(.system(size: 14, weight: .medium)).foregroundStyle(Reed.ink)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .reedGlass(Capsule(), interactive: true)
                }
                .buttonStyle(ReedPressStyle())
                .opacity(shown ? 1 : 0)
                .offset(y: shown ? 0 : 10)
                .animation(.spring(response: 0.5, dampingFraction: 0.85).delay(Double(i) * 0.06), value: shown)
            }
        }
        .padding(.horizontal, 24)
    }
}

/// Простая раскладка «в строку с переносом» (по центру).
struct ReedFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = rows(subviews, width: proposal.width ?? .infinity)
        let h = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(0, rows.count - 1))
        let w = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? w, height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(subviews, width: bounds.width) {
            var x = bounds.minX + (bounds.width - row.width) / 2
            for i in row.items {
                let s = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y + (row.height - s.height) / 2), proposal: .unspecified)
                x += s.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row { var items: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func rows(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var out: [Row] = [], cur = Row()
        for i in subviews.indices {
            let s = subviews[i].sizeThatFits(.unspecified)
            let add = cur.items.isEmpty ? s.width : cur.width + spacing + s.width
            if add > width, !cur.items.isEmpty { out.append(cur); cur = Row() }
            cur.width = cur.items.isEmpty ? s.width : cur.width + spacing + s.width
            cur.height = max(cur.height, s.height)
            cur.items.append(i)
        }
        if !cur.items.isEmpty { out.append(cur) }
        return out
    }
}
