module github.com/reedvpn/singbox-mobile

// olcrtc требует go 1.26.3 — для совмещённого bind берём не ниже.
go 1.26.3

// Обёртка над sing-box + olcRTC для СОВМЕЩЁННОГО gomobile bind в ОДНУ AAR/XCFramework
// (одна libgojni.so с обоими транспортами; два отдельных gomobile-AAR в одном APK
// невозможны — конфликт libgojni.so). Версии sing/sing-box совпадают с sing-box v1.13.13.
// olcrtc НЕ пинуем тут — CI делает `go get github.com/openlibrecommunity/olcrtc@master`,
// а blank-import в tools.go не даёт `go mod tidy` его выбросить. go.sum генерируется
// `go mod tidy` в CI — здесь не коммитим (в этой среде нет Go).
require (
	github.com/sagernet/sing v0.8.10
	github.com/sagernet/sing-box v1.13.13
)
