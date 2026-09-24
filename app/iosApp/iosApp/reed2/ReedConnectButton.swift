import SwiftUI

enum ReedConnState { case off, connecting, on }

/// Reed 2.0 — кнопка подключения iOS (ТЗ 6.3). Диск 150pt: внешнее кольцо с конусным
/// хромовым градиентом, внутри настоящее стекло Apple (reedGlass), иконка power.
/// Выключено — ink-muted; подключено — lime + мягкое свечение. Тактильный отклик по нажатию.
/// Анимации плавные: свечение/цвет через easeInOut, лёгкое «дыхание» при подключении.
struct ReedConnectButton: View {
    let state: ReedConnState
    var size: CGFloat = 150
    var onTap: () -> Void = {}

    @State private var breathe = false

    private var iconColor: Color { state == .on ? Reed.lime : Reed.inkMuted }
    private var glow: CGFloat { state == .on ? 0.4 : (state == .connecting ? 0.2 : 0) }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                // Свечение (мягкий радиальный ореол).
                Circle()
                    .fill(Reed.lime.opacity(glow))
                    .frame(width: size * 1.25, height: size * 1.25)
                    .blur(radius: size * 0.22)

                // Внешнее хромовое кольцо (конусный градиент).
                Circle()
                    .strokeBorder(
                        AngularGradient(
                            colors: [Reed.chrome050, Reed.chrome400, Reed.chrome800,
                                     Reed.chrome200, Reed.chrome050],
                            center: .center
                        ),
                        lineWidth: 1.5
                    )
                    .frame(width: size, height: size)

                // Стеклянное тело.
                Circle()
                    .fill(Color.white.opacity(state == .on ? 0 : 0.02))
                    .frame(width: size - 6, height: size - 6)
                    .reedGlass(Circle())
                    .overlay(
                        Circle().fill(Reed.lime).opacity(state == .on ? 1 : 0)
                            .frame(width: size - 6, height: size - 6)
                    )

                Image(systemName: "power")
                    .font(.system(size: size * 0.29, weight: .regular))
                    .foregroundStyle(iconColor)
            }
            .frame(width: size * 1.25, height: size * 1.25)
            .scaleEffect(state == .connecting && breathe ? 1.03 : 1.0)
            .shadow(color: Reed.lime.opacity(state == .on ? 0.4 : 0), radius: 28)
            .animation(.easeInOut(duration: 0.45), value: state)
            .animation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true), value: breathe)
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.impact(weight: .medium), trigger: state)
        .onChange(of: state) { _, newValue in
            breathe = (newValue == .connecting)
        }
        .onAppear { breathe = (state == .connecting) }
    }
}
