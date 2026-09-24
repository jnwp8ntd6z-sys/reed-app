import SwiftUI

/// Reed 2.0 — дизайн-система iOS (тема Graphite, ТЗ 3.2). Настоящие цвета/материалы Apple,
/// не копия макета: стекло — родной Liquid Glass на iOS 26, .ultraThinMaterial на 17–25.
enum Reed {
    // Фон / поверхности
    static let ground000 = Color(hex: 0x08090A)   // фон экранов
    static let ground100 = Color(hex: 0x101113)   // второй цвет градиента фона
    static let surface100 = Color(hex: 0x17191B)
    static let surface200 = Color(hex: 0x1F2225)
    static let surface300 = Color(hex: 0x282C30)
    // Хром
    static let chrome050 = Color(hex: 0xF5F7F8)
    static let chrome200 = Color(hex: 0xCDD3D8)
    static let chrome400 = Color(hex: 0x949BA2)
    static let chrome600 = Color(hex: 0x626A71)
    static let chrome800 = Color(hex: 0x383D42)
    // Текст
    static let ink = Color(hex: 0xF5F7F8)
    static let inkMuted = Color(hex: 0xA4ACB3)
    static let onInk = Color(hex: 0x0A0B0C)
    static let hairline = Color(hex: 0x2B2F33)
    // Акценты
    static let lime = Color(hex: 0xAAFF00)
    static let onLime = Color(hex: 0x0D1400)
    // Статусы
    static let statusOk = Color(hex: 0x5FD39A)
    static let statusWarn = Color(hex: 0xE6B45C)
    static let statusDanger = Color(hex: 0xF0705F)

    /// Хромовый градиент для «CLIENT» в логотипе и «БЕЗ ОБРЫВОВ» на входе (ТЗ 3.2).
    static let chromeGradient = LinearGradient(
        stops: [
            .init(color: chrome050, location: 0.00),
            .init(color: chrome200, location: 0.18),
            .init(color: chrome600, location: 0.34),
            .init(color: chrome050, location: 0.47),
            .init(color: chrome800, location: 0.58),
            .init(color: chrome200, location: 0.74),
            .init(color: chrome050, location: 0.90),
            .init(color: chrome400, location: 1.00),
        ],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )

    /// Фон экрана — тёмный вертикальный градиент (ground-000 → ground-100).
    static let screenBackground = LinearGradient(
        colors: [ground000, ground100], startPoint: .top, endPoint: .bottom
    )

    /// Цвет пинга по порогам (ТЗ 4.4).
    static func pingColor(_ ms: Int?) -> Color {
        guard let ms, ms >= 0 else { return chrome600 }
        if ms < 60 { return statusOk }
        if ms < 100 { return inkMuted }
        return statusWarn
    }
    static func pingText(_ ms: Int?) -> String {
        guard let ms, ms >= 0 else { return "—" }
        return "\(ms) мс"
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }
}

// MARK: - Liquid Glass с откатом (ТЗ 6.2)

extension View {
    /// Стекло Apple (ТЗ 6.2): Liquid Glass на iOS 26, .ultraThinMaterial на 17–25.
    @ViewBuilder
    func reedGlass<S: Shape>(_ shape: S = Capsule(), interactive: Bool = false) -> some View {
        if #available(iOS 26, *) {
            // Настоящий Liquid Glass (Xcode 26 SDK на раннере).
            self.glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
    }
}

// MARK: - Шрифты (ТЗ 3.3: заголовки Unbounded, коды JetBrains Mono; пока системные + monospaced)

extension Font {
    /// Заголовок экрана капсом (ПРОФИЛЬ, СЕМЬЯ). Пока системный heavy; позже — Unbounded.
    static func reedTitle(_ size: CGFloat = 30) -> Font { .system(size: size, weight: .heavy) }
    static func reedHeadline(_ size: CGFloat = 34) -> Font { .system(size: size, weight: .heavy) }
    /// Коды/пинг/таймер/версия — моноширинный (позже JetBrains Mono).
    static func reedMono(_ size: CGFloat = 15, weight: Font.Weight = .medium) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}
