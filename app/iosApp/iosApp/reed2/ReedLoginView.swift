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
            Text("REED").font(.system(size: size, weight: .heavy)).foregroundStyle(Reed.ink)
            Circle().fill(Reed.lime).frame(width: size * 0.35, height: size * 0.35)
            Text("CLIENT").font(.system(size: size, weight: .heavy))
                .foregroundStyle(Reed.chromeGradient)
        }
    }
}

/// Reed 2.0 — экран входа iOS (ТЗ 4.1 + правки: согласие ВНИЗУ, «без кода» приглушённая).
/// Пока согласие не отмечено, действия приглушены; нажатие на них мягко «встряхивает» согласие.
struct ReedLoginView: View {
    @Binding var consent: Bool
    var showTelegram: Bool = false
    var telegramBusy: Bool = false
    var statusText: String? = nil
    var onOpenCodeEntry: () -> Void = {}
    var onScanQr: () -> Void = {}
    var onContinueWithoutCode: () -> Void = {}
    var onTelegram: () -> Void = {}
    var onTerms: () -> Void = {}
    var onPrivacy: () -> Void = {}

    @State private var nudge = 0
    @State private var appeared = false

    private func gated(_ action: @escaping () -> Void) -> () -> Void {
        { if consent { action() } else { withAnimation(.smooth(duration: 0.45)) { nudge += 1 } } }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ReedLogo(size: 20).padding(.top, 12)

            // Декор: тёмная подложка + серебряная звезда (медленно дышит, без рывков).
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 48, style: .continuous)
                    .fill(Reed.surface100)
                    .frame(height: 220)
                CookieStar()
                    .fill(Reed.chromeGradient)
                    .frame(width: 190, height: 190)
                    .rotationEffect(.degrees(appeared ? 8 : 0))
                    .offset(x: 34, y: -26)
                    .animation(.easeInOut(duration: 9).repeatForever(autoreverses: true), value: appeared)
                VStack(alignment: .leading, spacing: -2) {
                    Text("ИНТЕРНЕТ").font(.reedHeadline(34)).foregroundStyle(Reed.ink)
                    Text("БЕЗ ОБРЫВОВ").font(.reedHeadline(34)).foregroundStyle(Reed.chromeGradient)
                }
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 8)
            }
            .frame(height: 230)
            .padding(.top, 12)

            Text(showTelegram
                 ? "Код приходит в Telegram-боте. Скан QR или вставка из буфера входят сразу."
                 : "Вставь код, ключ подписки или приглашение — или отсканируй QR.")
                .font(.system(size: 15)).foregroundStyle(Reed.inkMuted)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 20)

            VStack(alignment: .leading, spacing: 0) {
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
                        .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .contentShape(Rectangle())
                    }.buttonStyle(ReedPressStyle())
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

                if showTelegram {
                    HStack {
                        Rectangle().fill(Reed.hairline).frame(height: 1)
                        Text("или").font(.system(size: 13)).foregroundStyle(Reed.chrome600).padding(.horizontal, 12)
                        Rectangle().fill(Reed.hairline).frame(height: 1)
                    }.padding(.top, 16)
                    Button(action: gated(onTelegram)) {
                        HStack(spacing: 10) {
                            if telegramBusy {
                                ProgressView().tint(Reed.ink).controlSize(.small)
                                    .transition(.opacity.combined(with: .scale(scale: 0.6)))
                            }
                            Text(telegramBusy ? "Ждём подтверждения в Telegram…" : "Войти через Telegram")
                                .font(.system(size: 15, weight: .medium))
                                .contentTransition(.opacity)
                        }
                        .foregroundStyle(Reed.ink).frame(maxWidth: .infinity).frame(height: 52)
                        .background(Reed.surface300, in: Capsule())
                        .animation(.smooth(duration: 0.3), value: telegramBusy)
                    }
                    .buttonStyle(ReedPressStyle())
                    .padding(.top, 16)
                }

                if let statusText {
                    Text(statusText)
                        .font(.system(size: 13)).foregroundStyle(Reed.statusWarn)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                        .padding(.top, 10)
                        .transition(.opacity)
                }

                // «Продолжить без кода» — приглушённая вторичная (НЕ светлее).
                Button(action: gated(onContinueWithoutCode)) {
                    Text("Продолжить без кода").font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Reed.inkMuted).frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                }.buttonStyle(ReedPressStyle()).padding(.top, 18)
            }
            .opacity(consent ? 1 : 0.45)
            .animation(.smooth(duration: 0.35), value: consent)
            .animation(.smooth(duration: 0.3), value: statusText)

            Spacer(minLength: 0)

            // Согласие — прижато к низу экрана. Ссылки открывают документы, остальная строка
            // и кружок переключают галочку.
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    Circle().fill(consent ? Reed.ink : Reed.surface300).frame(width: 22, height: 22)
                    Image(systemName: "checkmark").font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Reed.onInk)
                        .scaleEffect(consent ? 1 : 0.3).opacity(consent ? 1 : 0)
                }
                .animation(.spring(response: 0.32, dampingFraction: 0.72), value: consent)
                .frame(width: 44, height: 44, alignment: .topLeading)   // палец попадает легко
                .contentShape(Rectangle())
                .onTapGesture { consent.toggle() }
                .padding(.trailing, -22)
                .accessibilityElement()
                .accessibilityLabel("Согласие с соглашением и политикой")
                .accessibilityAddTraits(.isButton)
                .accessibilityValue(consent ? "Отмечено" : "Не отмечено")
                .accessibilityAction { consent.toggle() }
                // «Принимаю» тоже переключает галочку; документы — отдельные ссылки.
                Text(Self.consentText)
                    .font(.system(size: 12)).foregroundStyle(Reed.chrome600)
                    .tint(Reed.chrome200)
                    .environment(\.openURL, OpenURLAction { url in
                        switch url.host {
                        case "toggle": consent.toggle()
                        case "terms": onTerms()
                        default: onPrivacy()
                        }
                        return .handled
                    })
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 3)
                Spacer(minLength: 0)
            }
            .modifier(ReedShake(shakes: CGFloat(nudge)))
            .sensoryFeedback(.warning, trigger: nudge)
            .sensoryFeedback(.selection, trigger: consent)
            .padding(.bottom, 4)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Reed.screenBackground.ignoresSafeArea())
        .onAppear { appeared = true }
    }

    private static let consentText: AttributedString = {
        var s = AttributedString("Принимаю")
        s.link = URL(string: "reed-doc://toggle")
        s.foregroundColor = Reed.chrome400
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
