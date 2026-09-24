import SwiftUI

/// Reed 2.0 — распознавание кода (ТЗ 4.2), нативный порт CodeKind.
enum ReedCodeKind {
    case subscriptionLink, familyInvite, deviceCode, accountCode
    var chip: String {
        switch self {
        case .subscriptionLink: return "Ссылка подписки — добавим её серверы"
        case .familyInvite: return "Приглашение — войдёшь в семью владельца"
        case .deviceCode: return "Код устройства — войдёшь в свой аккаунт"
        case .accountCode: return "Код входа — откроется твой аккаунт"
        }
    }
    static func detect(_ raw: String) -> ReedCodeKind? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        let lower = s.lowercased()
        for p in ["https://", "http://", "vless://", "vmess://", "trojan://", "ss://", "ssconf://"] where lower.hasPrefix(p) {
            return .subscriptionLink
        }
        let upper = s.uppercased()
        if upper.hasPrefix("RDI-") { return .familyInvite }
        if upper.hasPrefix("RDX-") { return .deviceCode }
        return .accountCode
    }
}

/// Reed 2.0 — Ввод кода iOS (ТЗ 4.2). Поле в фокусе, чип распознавания, «Войти» над клавиатурой.
struct ReedCodeEntryView: View {
    var onSubmit: () -> Void = {}
    var onBack: () -> Void = {}
    var onScanQr: () -> Void = {}

    @State private var code = ""
    @FocusState private var focused: Bool
    private var detected: ReedCodeKind? { ReedCodeKind.detect(code) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Reed.ink).frame(width: 40, height: 40).reedGlass(Circle())
                }.buttonStyle(.plain)
                Spacer()
                Text("Вход по коду").font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
                Spacer()
                Color.clear.frame(width: 40, height: 40)
            }.padding(.top, 12)

            Text("Код, ключ подписки или приглашение")
                .font(.system(size: 13, weight: .medium)).foregroundStyle(Reed.inkMuted).padding(.top, 24)

            HStack {
                TextField("", text: $code, prompt: Text("RD-XXXX  или  ссылка").foregroundColor(Reed.chrome600))
                    .font(.reedMono(15)).foregroundStyle(Reed.ink).focused($focused)
                    .autocorrectionDisabled().textInputAutocapitalization(.characters)
                    .submitLabel(.go).onSubmit(onSubmit)
                Button(action: onScanQr) {
                    Image(systemName: "qrcode.viewfinder").font(.system(size: 18)).foregroundStyle(Reed.ink)
                        .frame(width: 36, height: 36)
                        .background(Reed.surface300, in: RoundedRectangle(cornerRadius: 10))
                }.buttonStyle(.plain)
            }
            .padding(.horizontal, 16).frame(height: 56)
            .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Reed.ink, lineWidth: 2))
            .padding(.top, 8)

            if let d = detected {
                HStack(spacing: 8) {
                    Circle().fill(Reed.lime).frame(width: 7, height: 7)
                    Text(d.chip).font(.system(size: 13)).foregroundStyle(Reed.ink)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Reed.surface200, in: Capsule())
                }.padding(.top, 10)
            } else {
                Text("Скан QR или вставка из буфера входят сразу, без кнопки.")
                    .font(.system(size: 13)).foregroundStyle(Reed.inkMuted).padding(.top, 10)
            }

            Spacer(minLength: 0)

            Button(action: onSubmit) {
                Text("Войти").font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(code.isEmpty ? Reed.chrome600 : Reed.onInk)
                    .frame(maxWidth: .infinity).frame(height: 54)
                    .background(code.isEmpty ? Reed.surface300 : Reed.ink, in: Capsule())
            }.buttonStyle(.plain).disabled(code.isEmpty).padding(.bottom, 16)
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Reed.screenBackground.ignoresSafeArea())
        .onAppear { focused = true }
    }
}
