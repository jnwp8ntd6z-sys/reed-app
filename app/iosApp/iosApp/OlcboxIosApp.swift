import SwiftUI

@main
struct OlcboxIosApp: App {
    // Reed 2.0: нативное SwiftUI-приложение по ТЗ, без Kotlin/Compose (SharedUI остался только
    // у Android). Туннель — PacketTunnel extension через ReedVPNManager.
    @StateObject private var model = ReedAppModel(tunnel: ReedSystemTunnel())

    var body: some Scene {
        WindowGroup {
            ReedRootView()
                .environmentObject(model)
        }
    }
}
