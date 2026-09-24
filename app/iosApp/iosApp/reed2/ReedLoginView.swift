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
struct ReedLoginView: View {
    @Binding var consent: Bool
    var showTelegram: Bool = false
    var onOpenCodeEntry: () -> Void = {}
    var onScanQr: () -> Void = {}
    var onContinueWithoutCode: () -> Void = {}
    var onTelegram: () -> Void = {}
    var onTerms: () -> Void = {}
    var onPrivacy: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ReedLogo(size: 20).padding(.top, 12)

            // Декор: тёмная подложка + серебряная звезда.
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 48, style: .continuous)
                    .fill(Reed.surface100)
                    .frame(height: 220)
                CookieStar()
                    .fill(Reed.chromeGradient)
                    .frame(width: 190, height: 190)
                    .offset(x: 34, y: -26)
                VStack(alignment: .leading, spacing: -2) {
                    Text("ИНТЕРНЕТ").font(.reedHeadline(34)).foregroundStyle(Reed.ink)
                    Text("БЕЗ ОБРЫВОВ").font(.reedHeadline(34)).foregroundStyle(Reed.chromeGradient)
                }
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 8)
            }
            .frame(height: 230)
            .padding(.top, 12)

            Text("Вставь код, ключ подписки или приглашение — или отсканируй QR.")
                .font(.system(size: 15)).foregroundStyle(Reed.inkMuted)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 20)

            Text("Код, ключ подписки или приглашение")
                .font(.system(size: 13, weight: .medium)).foregroundStyle(Reed.inkMuted)
                .padding(.top, 20)

            HStack(spacing: 10) {
                Button(action: { if consent { onOpenCodeEntry() } }) {
                    HStack {
                        Text("RD-XXXX  или  ссылка").font(.reedMono(15)).foregroundStyle(Reed.chrome600)
                        Spacer()
                    }
                    .padding(.horizontal, 18).frame(height: 56)
                    .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }.buttonStyle(.plain)
                Button(action: { if consent { onScanQr() } }) {
                    Image(systemName: "qrcode.viewfinder").font(.system(size: 24))
                        .foregroundStyle(Reed.onInk)
                        .frame(width: 56, height: 56)
                        .background(Reed.ink, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }.buttonStyle(.plain)
            }
            .padding(.top, 8)

            if showTelegram {
                HStack {
                    Rectangle().fill(Reed.hairline).frame(height: 1)
                    Text("или").font(.system(size: 13)).foregroundStyle(Reed.chrome600).padding(.horizontal, 12)
                    Rectangle().fill(Reed.hairline).frame(height: 1)
                }.padding(.top, 16)
                Button(action: onTelegram) {
                    Text("Войти через Telegram").font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Reed.ink).frame(maxWidth: .infinity).frame(height: 52)
                        .background(Reed.surface300, in: Capsule())
                }.buttonStyle(.plain).padding(.top, 16)
            }

            // «Продолжить без кода» — приглушённая вторичная (НЕ светлее).
            Button(action: onContinueWithoutCode) {
                Text("Продолжить без кода").font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Reed.inkMuted).frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }.buttonStyle(.plain).padding(.top, 18)

            Spacer(minLength: 0)

            // Согласие — прижато к низу экрана.
            HStack(alignment: .top, spacing: 8) {
                Button(action: { consent.toggle() }) {
                    ZStack {
                        Circle().fill(consent ? Reed.ink : Reed.surface300).frame(width: 20, height: 20)
                        if consent {
                            Image(systemName: "checkmark").font(.system(size: 11, weight: .bold))
                                .foregroundStyle(Reed.onInk)
                        }
                    }
                }.buttonStyle(.plain)
                (Text("Принимаю ").foregroundColor(Reed.chrome600)
                 + Text("соглашение").foregroundColor(Reed.chrome200).underline()
                 + Text(" и ").foregroundColor(Reed.chrome600)
                 + Text("политику конфиденциальности").foregroundColor(Reed.chrome200).underline())
                    .font(.system(size: 12))
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 16)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Reed.screenBackground.ignoresSafeArea())
    }
}
