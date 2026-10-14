import SwiftUI
import UIKit
import AVKit
import AVFoundation
import CoreTransferable
import UniformTypeIdentifiers

/// Reed 2.0 — вложения в чате поддержки: фото, видео, файлы.
///
/// Анимации: превью плавно проявляется после загрузки, полноэкранный просмотр открывается
/// системным переходом, фото приближается щипком и двойным тапом на мягкой кривой.

// MARK: - кэш на диске

enum ReedMediaDisk {
    static func url(_ id: Int, _ name: String?) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("support_media", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let ext = URL(fileURLWithPath: name ?? "").pathExtension
        return dir.appendingPathComponent(ext.isEmpty ? "\(id)" : "\(id).\(ext)")
    }
}

// MARK: - подготовка файлов

enum ReedMediaTools {
    /// Фото из галереи (в т.ч. HEIC) → JPEG, длинная сторона до 2048 px.
    @MainActor
    static func jpeg(_ data: Data, maxSide: CGFloat = 2048) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        let s = img.size
        guard s.width > 0, s.height > 0 else { return nil }
        let k = min(1, maxSide / max(s.width, s.height))
        let target = CGSize(width: (s.width * k).rounded(), height: (s.height * k).rounded())
        let fmt = UIGraphicsImageRendererFormat.default()
        fmt.scale = 1
        let out = UIGraphicsImageRenderer(size: target, format: fmt).image { _ in
            img.draw(in: CGRect(origin: .zero, size: target))
        }
        return out.jpegData(compressionQuality: 0.85)
    }

    /// Кадр-обложка для своего видео.
    static func poster(_ url: URL) async -> Data? {
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 900, height: 900)
        guard let cg = try? await gen.image(at: .zero).image else { return nil }
        return UIImage(cgImage: cg).jpegData(compressionQuality: 0.75)
    }

    static func videoMime(_ url: URL) -> String {
        ["mp4", "m4v"].contains(url.pathExtension.lowercased()) ? "video/mp4" : "video/quicktime"
    }

    static func fileSize(_ url: URL) -> Int {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.intValue ?? 0
    }
}

/// Видео из галереи — копия во временную папку (PhotosPicker отдаёт файл только на время импорта).
struct ReedPickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let dst = FileManager.default.temporaryDirectory.appendingPathComponent("reed-\(UUID().uuidString).\(ext)")
            try FileManager.default.copyItem(at: received.file, to: dst)
            return ReedPickedMovie(url: dst)
        }
    }
}

// MARK: - вложение в пузыре

struct ReedMediaViewerItem: Identifiable {
    enum Kind { case image(UIImage), video(URL), file(URL) }
    let id = UUID()
    let kind: Kind
}

struct ReedChatMediaView: View {
    @EnvironmentObject var m: ReedAppModel
    let item: ReedChatItem
    let media: ReedChatMedia
    var open: (ReedMediaViewerItem) -> Void

    @State private var image: UIImage?
    @State private var busy = false
    @State private var failed = false

    private static let width: CGFloat = 230

    var body: some View {
        Group {
            if media.kind == "photo" || media.kind == "video" { visual } else { fileRow }
        }
        .task(id: media.serverId ?? -1) { await loadPreview() }
    }

    private var height: CGFloat {
        guard let image, image.size.width > 0 else { return media.kind == "video" ? 150 : 180 }
        return min(320, max(130, Self.width * image.size.height / image.size.width))
    }

    private var visual: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
                    .transition(.opacity)
            } else {
                Rectangle().fill(Reed.surface300)
                if media.kind == "photo" && !failed {
                    ProgressView().tint(Reed.inkMuted)
                } else if failed {
                    Image(systemName: "photo").font(.system(size: 24)).foregroundStyle(Reed.chrome600)
                }
            }
            if media.kind == "video" {
                Image(systemName: busy ? "hourglass" : "play.fill")
                    .font(.system(size: 22, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 54, height: 54)
                    .background(.black.opacity(0.45), in: Circle())
                    .contentTransition(.symbolEffect(.replace))
            }
            if item.state == .sending {
                ProgressView().tint(.white)
                    .frame(width: 40, height: 40)
                    .background(.black.opacity(0.45), in: Circle())
            }
        }
        .frame(width: Self.width, height: height)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Reed.hairline, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .animation(.smooth(duration: 0.3), value: image != nil)
        .onTapGesture { Task { await tap() } }
        .accessibilityLabel(media.kind == "video" ? "Видео" : "Фото")
    }

    private var fileRow: some View {
        HStack(spacing: 12) {
            Image(systemName: busy ? "hourglass" : "doc.fill")
                .font(.system(size: 18)).foregroundStyle(item.mine ? Reed.onInk : Reed.ink)
                .frame(width: 40, height: 40)
                .background((item.mine ? Reed.onInk : Reed.ink).opacity(0.1), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            Text(media.name ?? "Файл").font(.system(size: 15, weight: .medium)).lineLimit(1)
                .foregroundStyle(item.mine ? Reed.onInk : Reed.ink)
        }
        .padding(10)
        .frame(maxWidth: Self.width, alignment: .leading)
        .background(item.mine ? AnyShapeStyle(Reed.ink) : AnyShapeStyle(Reed.surface200),
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { Task { await tap() } }
    }

    private func loadPreview() async {
        if let p = media.preview, let img = UIImage(data: p) { image = img; return }
        guard media.kind == "photo", let id = media.serverId else { return }
        if let d = await m.supportMedia(id, name: media.name), let img = UIImage(data: d) {
            withAnimation(.smooth(duration: 0.3)) { image = img }
        } else {
            failed = true
        }
    }

    private func tap() async {
        switch media.kind {
        case "photo":
            if let image { open(ReedMediaViewerItem(kind: .image(image))) }
        case "video":
            if let url = media.localURL, FileManager.default.fileExists(atPath: url.path) {
                open(ReedMediaViewerItem(kind: .video(url))); return
            }
            guard let id = media.serverId, !busy else { return }
            busy = true
            defer { busy = false }
            if let url = await m.supportMediaFile(id, name: media.name) { open(ReedMediaViewerItem(kind: .video(url))) }
            else { m.show("Не удалось загрузить видео") }
        default:
            guard let id = media.serverId, !busy else { return }
            busy = true
            defer { busy = false }
            if let url = await m.supportMediaFile(id, name: media.name) { open(ReedMediaViewerItem(kind: .file(url))) }
            else { m.show("Не удалось загрузить файл") }
        }
    }
}

// MARK: - полноэкранный просмотр

struct ReedMediaViewer: View {
    let item: ReedMediaViewerItem
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            switch item.kind {
            case .image(let img):
                ReedZoomImage(image: img)
            case .video(let url):
                VideoPlayer(player: player)
                    .ignoresSafeArea(edges: .bottom)
                    .onAppear {
                        let p = AVPlayer(url: url)
                        player = p
                        p.play()
                    }
                    .onDisappear { player?.pause() }
            case .file(let url):
                VStack(spacing: 16) {
                    Image(systemName: "doc.fill").font(.system(size: 44)).foregroundStyle(Reed.ink)
                    Text(url.lastPathComponent).font(.system(size: 15)).foregroundStyle(Reed.inkMuted)
                    ShareLink(item: url) {
                        Label("Сохранить или открыть", systemImage: "square.and.arrow.up")
                            .font(.system(size: 16, weight: .semibold)).foregroundStyle(Reed.onInk)
                            .padding(.horizontal, 20).padding(.vertical, 12)
                            .background(Reed.ink, in: Capsule())
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            HStack(spacing: 10) {
                if case .image(let img) = item.kind {
                    ShareLink(item: Image(uiImage: img), preview: SharePreview("Фото", image: Image(uiImage: img))) {
                        circleIcon("square.and.arrow.up")
                    }
                }
                Button { dismiss() } label: { circleIcon("xmark") }
                    .accessibilityLabel("Закрыть")
            }
            .padding(.trailing, 16)
            .padding(.top, 8)
        }
        .preferredColorScheme(.dark)
    }

    private func circleIcon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
            .frame(width: 40, height: 40)
            .background(.black.opacity(0.5), in: Circle())
    }
}

/// Фото во весь экран: щипок — приближение до 5×, двойной тап — 2.5× / назад.
struct ReedZoomImage: View {
    let image: UIImage
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .scaleEffect(scale)
            .offset(offset)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                MagnifyGesture()
                    .onChanged { v in scale = max(1, min(5, lastScale * v.magnification)) }
                    .onEnded { _ in
                        lastScale = scale
                        if scale < 1.05 { reset() }
                    }
                    .simultaneously(with: DragGesture()
                        .onChanged { v in
                            guard scale > 1 else { return }
                            offset = CGSize(width: lastOffset.width + v.translation.width,
                                            height: lastOffset.height + v.translation.height)
                        }
                        .onEnded { _ in lastOffset = offset })
            )
            .onTapGesture(count: 2) {
                if scale > 1 { reset() } else {
                    withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { scale = 2.5; lastScale = 2.5 }
                }
            }
    }

    private func reset() {
        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
            scale = 1; lastScale = 1; offset = .zero; lastOffset = .zero
        }
    }
}
