import SwiftUI
import SharedUI

@main
struct OlcboxIosApp: App {
    private let platformBridge: SwiftPlatformBridge
    private let olcRtcBridge: SwiftOlcRtcManager
    private let singBoxBridge: SwiftSingBoxManager
    private let appSession: IosAppSession

    init() {
        let platformBridge = SwiftPlatformBridge()
        let olcRtcBridge = SwiftOlcRtcManager()
        let singBoxBridge = SwiftSingBoxManager()
        self.platformBridge = platformBridge
        self.olcRtcBridge = olcRtcBridge
        self.singBoxBridge = singBoxBridge
        self.appSession = IosAppFactory().createSession(
            platformBridge: platformBridge,
            olcRtcBridge: olcRtcBridge,
            singBoxBridge: singBoxBridge
        )
    }

    var body: some Scene {
        WindowGroup {
            // Reed 2.0: нативный SwiftUI-интерфейс (ТЗ). Старый Compose-хост (ComposeHostView
            // ниже) больше не показывается; KMP-мосты в init оставлены, чтобы SharedUI/туннель
            // линковались как прежде.
            ReedRootView()
        }
    }
}

private struct ComposeHostView: UIViewControllerRepresentable {
    let platformBridge: SwiftPlatformBridge
    let appSession: IosAppSession

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = appSession.createViewController()
        platformBridge.presenter = controller
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        platformBridge.presenter = uiViewController
    }
}
