//go:build tools

// Держит в графе зависимостей модуля:
//   - golang.org/x/mobile — иначе `gomobile bind` ругается «missing golang.org/x/mobile
//     dependency»;
//   - github.com/openlibrecommunity/olcrtc/mobile — чтобы `go mod tidy` НЕ выбрасывал
//     olcrtc из go.mod. Это нужно для СОВМЕЩЁННОГО gomobile bind обоих транспортов
//     (olcRTC + sing-box) в ОДНУ AAR/XCFramework с ОДНОЙ libgojni.so. Два отдельных
//     gomobile-AAR в одном APK невозможны (конфликт libgojni.so) — см. память
//     vless_singbox_integration_plan.
// Файл собирается только под тегом tools (в обычной сборке игнорируется), но
// `go mod tidy` видит импорты и сохраняет deps.
package singboxmobile

import (
	_ "github.com/openlibrecommunity/olcrtc/mobile"
	_ "golang.org/x/mobile/bind"
)
