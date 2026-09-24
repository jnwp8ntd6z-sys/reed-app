import SwiftUI

@main
struct OlcboxIosApp: App {
    // Reed 2.0: нативный SwiftUI-интерфейс по ТЗ. Прежний Compose-хост (IosAppFactory/SharedUI)
    // больше не создаётся — у него свои таймеры и автоподключение, они спорили бы с новой моделью.
    // Мосты SwiftSingBoxManager/SwiftOlcRtcManager остаются в таргете: из них берутся прогрев
    // конфигов, лог extension и общий TunnelConfigStore.
    @StateObject private var model = ReedAppModel(tunnel: ReedSystemTunnel())

    var body: some Scene {
        WindowGroup {
            ReedRootView()
                .environmentObject(model)
        }
    }
}
