import SwiftUI
import AVFoundation

/// Нативный сканер QR (AVFoundation). Показывается как sheet; при первом распознанном коде
/// отдаёт текст и закрывается. Скан входит сразу, без кнопки (ТЗ 4.2).
struct ReedQRScannerSheet: View {
    var onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var denied = false

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            if denied {
                VStack(spacing: 12) {
                    Image(systemName: "camera.fill").font(.system(size: 34)).foregroundStyle(Reed.inkMuted)
                    Text("Нет доступа к камере").font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
                    Text("Разреши камеру для Reed Client в Настройках, чтобы сканировать QR.")
                        .font(.system(size: 14)).foregroundStyle(Reed.inkMuted).multilineTextAlignment(.center)
                    Button("Открыть Настройки") {
                        if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                    }
                    .font(.system(size: 15, weight: .medium)).foregroundStyle(Reed.ink).padding(.top, 6)
                }
                .padding(32).frame(maxHeight: .infinity)
            } else {
                QRCameraView { code in
                    onCode(code)
                    dismiss()
                }
                .ignoresSafeArea()
                // Рамка-подсказка.
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .strokeBorder(Reed.ink.opacity(0.85), lineWidth: 2)
                    .frame(width: 240, height: 240)
                    .frame(maxHeight: .infinity)
                    .allowsHitTesting(false)
            }
            HStack {
                Text("Наведи камеру на QR-код").font(.system(size: 15, weight: .medium)).foregroundStyle(Reed.ink)
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark").font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.ink)
                        .frame(width: 40, height: 40).reedGlass(Circle(), interactive: true)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20).padding(.top, 16)
        }
        .task {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: denied = false
            case .notDetermined: denied = !(await AVCaptureDevice.requestAccess(for: .video))
            default: denied = true
            }
        }
    }
}

private struct QRCameraView: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    func makeUIViewController(context: Context) -> QRCameraController {
        let c = QRCameraController(); c.onCode = onCode; return c
    }
    func updateUIViewController(_ vc: QRCameraController, context: Context) {}
}

final class QRCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var fired = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let box = SessionBox(session)
        DispatchQueue.global(qos: .userInitiated).async { if !box.session.isRunning { box.session.startRunning() } }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let box = SessionBox(session)
        DispatchQueue.global(qos: .userInitiated).async { if box.session.isRunning { box.session.stopRunning() } }
    }

    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput,
                                    didOutput metadataObjects: [AVMetadataObject],
                                    from connection: AVCaptureConnection) {
        let text = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue
        MainActor.assumeIsolated {   // делегат вызывается на .main (см. setMetadataObjectsDelegate)
            guard let text, !text.isEmpty, !fired else { return }
            fired = true
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            onCode?(text)
        }
    }
}

/// Сессию камеры запускаем/останавливаем не на главном потоке (так советует Apple).
private final class SessionBox: @unchecked Sendable {
    let session: AVCaptureSession
    init(_ s: AVCaptureSession) { session = s }
}
