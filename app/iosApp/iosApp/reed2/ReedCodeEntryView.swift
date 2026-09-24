import SwiftUI

/// Reed 2.0 — Ввод кода iOS (ТЗ 4.2). Поле в фокусе, чип распознавания с точкой lime,
/// «Войти» над клавиатурой (safeAreaInset). Для приглашения в семью — поле имени.
struct ReedCodeEntryView: View {
    @EnvironmentObject var m: ReedAppModel
    var onScanQr: () -> Void

    @FocusState private var focus: Field?
    enum Field { case code, name }

    private var detected: CodeKindDetector.Kind? { CodeKindDetector.detect(m.code) }
    private var needsName: Bool { detected == .familyInvite }
    private var canSubmit: Bool {
        !m.code.trimmingCharacters(in: .whitespaces).isEmpty && !m.codeBusy &&
            (!needsName || !m.codeName.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                ReedBackButton { if !m.codeBusy { m.stage = m.codesReturn } }
                Spacer()
                Text("Вход по коду").font(.system(size: 17, weight: .semibold)).foregroundStyle(Reed.ink)
                Spacer()
                Color.clear.frame(width: 40, height: 40)
            }
            .padding(.top, 8)

            Text("Код, ключ подписки или приглашение")
                .font(.system(size: 13, weight: .medium)).foregroundStyle(Reed.inkMuted).padding(.top, 24)

            HStack(spacing: 8) {
                TextField("", text: Binding(get: { m.code }, set: { m.codeChanged($0) }),
                          prompt: Text("RD-XXXX  или  ссылка").foregroundColor(Reed.chrome600))
                    .font(.reedMono(15)).foregroundStyle(Reed.ink)
                    .focused($focus, equals: .code)
                    .autocorrectionDisabled().textInputAutocapitalization(.characters)
                    .submitLabel(needsName ? .next : .go)
                    .onSubmit { if needsName { focus = .name } else if canSubmit { m.submitCode() } }
                    .disabled(m.codeBusy)
                Button(action: onScanQr) {
                    Image(systemName: "qrcode.viewfinder").font(.system(size: 18)).foregroundStyle(Reed.ink)
                        .frame(width: 36, height: 36)
                        .background(Reed.surface300, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            .padding(.leading, 16).padding(.trailing, 10).frame(height: 56)
            .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(m.codeError == nil ? Reed.ink : Reed.statusDanger, lineWidth: 2))
            .animation(.smooth(duration: 0.2), value: m.codeError)
            .padding(.top, 8)

            Group {
                if let e = m.codeError {
                    Text(e).font(.system(size: 13, weight: .medium)).foregroundStyle(Reed.statusDanger)
                } else if let d = detected {
                    HStack(spacing: 8) {
                        Circle().fill(Reed.lime).frame(width: 7, height: 7)
                        Text(CodeKindDetector.chip(d)).font(.system(size: 13)).foregroundStyle(Reed.ink)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Reed.surface200, in: Capsule())
                    }
                } else {
                    Text("Скан QR или вставка из буфера входят сразу, без кнопки.")
                        .font(.system(size: 13)).foregroundStyle(Reed.inkMuted)
                }
            }
            .padding(.top, 10)
            .transition(.opacity)
            .animation(.smooth(duration: 0.2), value: detected.map { CodeKindDetector.chip($0) })

            if needsName {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Как тебя зовут? Имя увидит владелец семьи")
                        .font(.system(size: 13, weight: .medium)).foregroundStyle(Reed.inkMuted)
                    TextField("", text: $m.codeName, prompt: Text("Имя").foregroundColor(Reed.chrome600))
                        .font(.system(size: 15)).foregroundStyle(Reed.ink)
                        .focused($focus, equals: .name)
                        .textInputAutocapitalization(.words).submitLabel(.go)
                        .onSubmit { if canSubmit { m.submitCode() } }
                        .padding(.horizontal, 16).frame(height: 52)
                        .background(Reed.surface200, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .padding(.top, 20)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
            Spacer(minLength: 0)
        }
        .animation(.smooth(duration: 0.3), value: needsName)
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .safeAreaInset(edge: .bottom) {
            Button { m.submitCode() } label: {
                HStack(spacing: 10) {
                    if m.codeBusy { ProgressView().tint(Reed.onInk) }
                    Text(m.codeBusy ? "Входим…" : "Войти").font(.system(size: 16, weight: .semibold))
                }
                .foregroundStyle(canSubmit || m.codeBusy ? Reed.onInk : Reed.chrome600)
                .frame(maxWidth: .infinity).frame(height: 54)
                .background(canSubmit || m.codeBusy ? Reed.ink : Reed.surface300, in: Capsule())
                .animation(.smooth(duration: 0.2), value: canSubmit)
            }
            .buttonStyle(.plain)
            .disabled(!canSubmit)
            .padding(.horizontal, 20).padding(.bottom, 12)
        }
        .onAppear { focus = .code }
        .onChange(of: needsName) { _, need in if need { focus = .name } }
    }
}
