import SwiftUI

/// 12-лучевая «звезда-печенька» (декор входа, серебряный хром).
struct CookieStar: Shape {
    var points: Int = 12
    var amplitude: CGFloat = 0.10
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let cx = rect.midX, cy = rect.midY
        let radius = min(rect.width, rect.height) / 2
        let steps = points * 8
        for i in 0...steps {
            let t = CGFloat(i) / CGFloat(steps)
            let angle = t * 2 * .pi - .pi / 2
            let wave = 1 + amplitude * cos(CGFloat(points) * t * 2 * .pi)
            let r = radius * wave
            let x = cx + r * cos(angle)
            let y = cy + r * sin(angle)
            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
        }
        p.closeSubpath()
        return p
    }
}

/// Логотип REED · CLIENT (точка lime, «CLIENT» хром).
struct ReedLogo: View {
    var size: CGFloat = 20
    var body: some View {
        HStack(spacing: 8) {
            Text("REED").font(.reedLogo(size)).foregroundStyle(Reed.ink)
            Circle().fill(Reed.lime).frame(width: size * 0.35, height: size * 0.35)
            Text("CLIENT").font(.reedLogo(size))
                .foregroundStyle(Reed.chromeGradient)
        }
    }
}

/// Reed 2.0 — экран входа iOS (ТЗ 4.1 + правки: согласие ВНИЗУ экрана, «без кода» приглушённая).
/// Без согласия поле и QR неактивны (приглушены); нажатие на них мягко встряхивает строку согласия.
/// На iOS вход только по коду/QR/ссылке (ТЗ 6.8).
struct ReedLoginView: View {
    @Binding var consent: Bool
    var onOpenCodeEntry: () -> Void = {}
    var onScanQr: () -> Void = {}
    var onContinueWithoutCode: () -> Void = {}
    var onTerms: () -> Void = {}
    var onPrivacy: () -> Void = {}

    @State private var nudge = 0
    @State private var appeared = false

    private func gated(_ action: @escaping () -> Void) -> () -> Void {
        { if consent { action() } else { withAnimation(.smooth(duration: 0.45)) { nudge += 1 } } }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            hero

            VStack(alignment: .leading, spacing: 0) {
                Text("Вставь код, ключ подписки или приглашение — или отсканируй QR.")
                    .font(.system(size: 16)).foregroundStyle(Reed.inkMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 22)

                Text("Код, ключ подписки или приглашение")
                    .font(.system(size: 13, weight: .medium)).foregroundStyle(Reed.inkMuted)
                    .padding(.top, 20)

                HStack(spacing: 10) {
                    Button(action: gated(onOpenCodeEntry)) {
                        HStack {
                            Text("RD-XXXX  или  ссылка").font(.reedMono(15)).foregroundStyle(Reed.chrome600)
                            Spacer()
                        }
                        .padding(.horizontal, 18).frame(height: 56)
                        .background(Reed.surface100, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(ReedPressStyle())
                    Button(action: gated(onScanQr)) {
                        Image(systemName: "qrcode.viewfinder").font(.system(size: 24))
                            .foregroundStyle(Reed.onInk)
                            .frame(width: 56, height: 56)
                            .background(Reed.ink, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(ReedPressStyle())
                    .accessibilityLabel("Сканировать QR-код")
                }
                .padding(.top, 8)
                .opacity(consent ? 1 : 0.45)
                .animation(.smooth(duration: 0.35), value: consent)

                // «Продолжить без кода» — приглушённая вторичная (НЕ светлее).
                Button(action: onContinueWithoutCode) {
                    Text("Продолжить без кода").font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Reed.inkMuted).frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                }
                .buttonStyle(ReedPressStyle())
                .padding(.top, 14)
            }
            .padding(.horizontal, 24)

            Spacer(minLength: 0)

            consentRow
                .padding(.horizontal, 24)
                .padding(.bottom, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Reed.screenBackground.ignoresSafeArea())
        .onAppear { appeared = true }
    }

    // Подложка на всю ширину от верхнего края экрана (≈300 pt вместе со статус-баром), скругление 48
    // только снизу; звезда 250 pt справа сверху, частично за краем, медленно поворачивается.
    private var hero: some View {
        VStack(alignment: .leading, spacing: 0) {
            ReedLogo(size: 20).padding(.top, 16)
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: -4) {
                Text("ИНТЕРНЕТ").font(.reedHeadline(36)).foregroundStyle(Reed.ink)
                Text("БЕЗ ОБРЫВОВ").font(.reedHeadline(36)).foregroundStyle(Reed.chromeGradient)
            }
            .minimumScaleFactor(0.8).lineLimit(1)
            .padding(.bottom, 28)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: 238)
        .background {
            ZStack(alignment: .topTrailing) {
                Reed.surface100
                CookieStar()
                    .fill(Reed.chromeGradient)
                    .frame(width: 250, height: 250)
                    .rotationEffect(.degrees(appeared ? 10 : 0))
                    .animation(.easeInOut(duration: 12).repeatForever(autoreverses: true), value: appeared)
                    .offset(x: 44, y: 18)
            }
            .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 48, bottomTrailingRadius: 48, style: .continuous))
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
        }
    }

    // Согласие: галочка в строке с текстом, по центру, у нижнего края экрана.
    private var consentRow: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            ZStack {
                Circle().fill(consent ? Reed.ink : Reed.surface300).frame(width: 20, height: 20)
                Image(systemName: "checkmark").font(.system(size: 10, weight: .bold))
                    .foregroundStyle(Reed.onInk)
                    .scaleEffect(consent ? 1 : 0.3).opacity(consent ? 1 : 0)
            }
            .animation(.spring(response: 0.32, dampingFraction: 0.72), value: consent)
            .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 4 }
            .padding(12).contentShape(Rectangle()).padding(-12)   // палец попадает легко
            .onTapGesture { consent.toggle() }
            .accessibilityElement()
            .accessibilityLabel("Согласие с соглашением и политикой")
            .accessibilityAddTraits(.isButton)
            .accessibilityValue(consent ? "Отмечено" : "Не отмечено")
            .accessibilityAction { consent.toggle() }
            // «Принимаю» тоже переключает галочку; документы — отдельные ссылки.
            Text(Self.consentText)
                .font(.system(size: 12)).foregroundStyle(Reed.chrome600)
                .tint(Reed.chrome200)
                .multilineTextAlignment(.center)
                .environment(\.openURL, OpenURLAction { url in
                    switch url.host {
                    case "toggle": consent.toggle()
                    case "terms": onTerms()
                    default: onPrivacy()
                    }
                    return .handled
                })
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .modifier(ReedShake(shakes: CGFloat(nudge)))
        .sensoryFeedback(.warning, trigger: nudge)
        .sensoryFeedback(.selection, trigger: consent)
    }

    private static let consentText: AttributedString = {
        var s = AttributedString("Принимаю")
        s.link = URL(string: "reed-doc://toggle")
        s.append(AttributedString(" "))
        var terms = AttributedString("соглашение")
        terms.link = URL(string: "reed-doc://terms")
        terms.underlineStyle = .single
        let mid = AttributedString(" и ")
        var privacy = AttributedString("политику конфиденциальности")
        privacy.link = URL(string: "reed-doc://privacy")
        privacy.underlineStyle = .single
        s.append(terms); s.append(mid); s.append(privacy)
        return s
    }()
}

/// Горизонтальное «встряхивание» по синусу: плавно затухает, без резкого старта.
struct ReedShake: GeometryEffect {
    var shakes: CGFloat
    var animatableData: CGFloat {
        get { shakes }
        set { shakes = newValue }
    }
    func effectValue(size: CGSize) -> ProjectionTransform {
        let t = shakes - shakes.rounded(.down)          // 0…1 внутри текущего встряхивания
        let x = 7 * sin(t * .pi * 4) * (1 - t)
        return ProjectionTransform(CGAffineTransform(translationX: x, y: 0))
    }
}

/// Нажатие: лёгкое сжатие и затемнение на пружине (как у системных кнопок).
struct ReedPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.8), value: configuration.isPressed)
    }
}
