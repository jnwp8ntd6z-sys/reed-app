import SwiftUI

enum ReedConnState { case off, connecting, on }

/// Reed 2.0 — кнопка подключения iOS (ТЗ 6.3): настоящий Liquid Glass.
/// Под стеклом лежит мягкая подсветка (хром + лайм), и стекло её преломляет — поэтому диск
/// выглядит объёмным, а не плоской заливкой. Выключено — прозрачное стекло с хромовым бликом;
/// подключение — лаймовая дуга бежит по кольцу, стекло «дышит»; подключено — стекло окрашено
/// в лайм (tint), вокруг ореол. Стекло откликается на касание (interactive).
///
/// Анимации: дыхание и дуга считаются от времени (TimelineView), без repeatForever —
/// при смене состояния ничего не залипает и всё гаснет по одной плавной кривой.
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
                     spin: .degrees(t.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360),
                     drift: t)
            }
        }
        .buttonStyle(ReedDiskPressStyle())
        .animation(.smooth(duration: 0.55), value: state)
        .sensoryFeedback(.impact(weight: .medium), trigger: state)
        .accessibilityLabel(isOn ? "Отключиться" : "Подключиться")
        .accessibilityValue(isOn ? "Подключено" : (isConnecting ? "Подключаюсь" : "Не подключено"))
    }

    private func disk(breath: Double, spin: Angle, drift: Double) -> some View {
        ZStack {
            // Ореол вокруг: мягкий радиальный, без колец.
            Circle()
                .fill(RadialGradient(
                    colors: [Reed.lime.opacity(isOn ? 0.45 : (isConnecting ? 0.18 : 0)), Reed.lime.opacity(0)],
                    center: .center, startRadius: size * 0.34, endRadius: size * 0.85))
                .frame(width: size * 1.7, height: size * 1.7)
                .allowsHitTesting(false)

            // Подсветка ПОД стеклом — её и преломляет Liquid Glass (иначе стекло на тёмном фоне плоское).
            ZStack {
                Circle()
                    .fill(AngularGradient(colors: [Reed.chrome050, Reed.chrome400, Reed.chrome800,
                                                   Reed.chrome200, Reed.chrome050],
                                          center: .center, angle: .degrees(isConnecting ? drift * 40 : 20)))
                    .opacity(isOn ? 0.0 : 0.55)
                Circle()
                    .fill(RadialGradient(colors: [Reed.lime, Reed.lime.opacity(0.55), Reed.lime.opacity(0)],
                                         center: UnitPoint(x: 0.42, y: 0.38), startRadius: 0, endRadius: size * 0.62))
                    .opacity(isOn ? 1 : (isConnecting ? 0.35 : 0))
            }
            .frame(width: size * 0.86, height: size * 0.86)
            .blur(radius: size * 0.09)
            .allowsHitTesting(false)

            // Хромовое кольцо.
            Circle()
                .strokeBorder(
                    AngularGradient(colors: [Reed.chrome050, Reed.chrome400, Reed.chrome800,
                                             Reed.chrome200, Reed.chrome050], center: .center),
                    lineWidth: 1.5)
                .frame(width: size, height: size)
                .opacity(isOn ? 0.5 : 1)

            // Дуга подключения: хвост прозрачный, голова лаймовая.
            Circle()
                .trim(from: 0, to: 0.26)
                .stroke(AngularGradient(colors: [Reed.lime.opacity(0), Reed.lime], center: .center,
                                        startAngle: .degrees(0), endAngle: .degrees(94)),
                        style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .frame(width: size + 12, height: size + 12)
                .rotationEffect(spin)
                .opacity(isConnecting ? 1 : 0)

            // Само стекло.
            glassDisk
                .frame(width: size - 6, height: size - 6)

            Image(systemName: "power")
                .font(.system(size: size * 0.29, weight: .regular))
                .foregroundStyle(isOn ? Reed.onLime : (isConnecting ? Reed.ink : Reed.inkMuted))
        }
        .frame(width: size * 1.25, height: size * 1.25)
        .scaleEffect(1 + (isConnecting ? 0.022 * breath : 0))
        .contentShape(Circle().inset(by: size * 0.125))
    }

    @ViewBuilder
    private var glassDisk: some View {
        if #available(iOS 26, *) {
            // Настоящий Liquid Glass: во включённом состоянии — окрашен лаймом, но остаётся стеклом.
            Circle()
                .fill(Color.clear)
                .glassEffect(isOn ? .regular.tint(Reed.lime.opacity(0.85)).interactive()
                                  : .regular.interactive(),
                             in: Circle())
        } else {
            // iOS 17–25: системный материал + лаймовая вуаль (не сплошная заливка).
            Circle()
                .fill(.ultraThinMaterial)
                .overlay(Circle().fill(Reed.lime.opacity(isOn ? 0.78 : 0)))
                .overlay(Circle().strokeBorder(
                    LinearGradient(colors: [Color.white.opacity(0.35), Color.white.opacity(0.02)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing),
                    lineWidth: 1))
        }
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
