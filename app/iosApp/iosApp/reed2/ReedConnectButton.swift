import SwiftUI

enum ReedConnState { case off, connecting, on }

/// Reed 2.0 — кнопка подключения iOS (ТЗ 6.3). Диск 150pt: хромовое кольцо с конусным
/// градиентом, внутри настоящее стекло Apple (reedGlass), иконка power.
/// Выключено — стекло и приглушённая иконка; подключение — лаймовая дуга бежит по кольцу,
/// диск мягко «дышит»; подключено — диск заливается лаймом, иконка тёмная, вокруг ореол.
///
/// Анимации: дыхание и дуга считаются от времени (TimelineView), а не через repeatForever —
/// поэтому при смене состояния они не «залипают» и плавно гаснут вместе с общей кривой.
struct ReedConnectButton: View {
    let state: ReedConnState
    var size: CGFloat = 150
    var onTap: () -> Void = {}

    private var isOn: Bool { state == .on }
    private var isConnecting: Bool { state == .connecting }

    var body: some View {
        Button(action: onTap) {
            TimelineView(.animation(minimumInterval: nil, paused: !isConnecting)) { ctx in
                let t = ctx.date.timeIntervalSinceReferenceDate
                disk(breath: sin(t * 2 * .pi / 1.6),
                     spin: .degrees(t.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360))
            }
        }
        .buttonStyle(ReedDiskPressStyle())
        .animation(.smooth(duration: 0.5), value: state)
        .sensoryFeedback(.impact(weight: .medium), trigger: state)
        .accessibilityLabel(isOn ? "Отключиться" : "Подключиться")
        .accessibilityValue(isOn ? "Подключено" : (isConnecting ? "Подключаюсь" : "Не подключено"))
    }

    private func disk(breath: Double, spin: Angle) -> some View {
        ZStack {
            // Ореол: мягкий радиальный, без колец.
            Circle()
                .fill(RadialGradient(
                    colors: [Reed.lime.opacity(isOn ? 0.42 : (isConnecting ? 0.16 : 0)), Reed.lime.opacity(0)],
                    center: .center, startRadius: size * 0.32, endRadius: size * 0.8))
                .frame(width: size * 1.6, height: size * 1.6)
                .allowsHitTesting(false)

            // Хромовое кольцо.
            Circle()
                .strokeBorder(
                    AngularGradient(colors: [Reed.chrome050, Reed.chrome400, Reed.chrome800,
                                             Reed.chrome200, Reed.chrome050], center: .center),
                    lineWidth: 1.5)
                .frame(width: size, height: size)

            // Дуга подключения: хвост прозрачный, голова лаймовая.
            Circle()
                .trim(from: 0, to: 0.26)
                .stroke(AngularGradient(colors: [Reed.lime.opacity(0), Reed.lime], center: .center,
                                        startAngle: .degrees(0), endAngle: .degrees(94)),
                        style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .frame(width: size + 12, height: size + 12)
                .rotationEffect(spin)
                .opacity(isConnecting ? 1 : 0)

            // Стеклянное тело + лаймовая заливка во включённом состоянии.
            Circle()
                .fill(Color.white.opacity(0.02))
                .frame(width: size - 6, height: size - 6)
                .reedGlass(Circle())
                .overlay(Circle().fill(Reed.lime).opacity(isOn ? 1 : 0))

            Image(systemName: "power")
                .font(.system(size: size * 0.29, weight: .regular))
                .foregroundStyle(isOn ? Reed.onLime : (isConnecting ? Reed.ink : Reed.inkMuted))
        }
        .frame(width: size * 1.25, height: size * 1.25)
        .scaleEffect(1 + (isConnecting ? 0.022 * breath : 0))
        .contentShape(Circle().inset(by: size * 0.125))
    }
}

/// Нажатие на диск: мягкое сжатие на пружине.
struct ReedDiskPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: configuration.isPressed)
    }
}
