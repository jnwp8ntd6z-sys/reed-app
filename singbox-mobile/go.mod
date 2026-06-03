module github.com/reedvpn/singbox-mobile

go 1.24.7

// Обёртка над sing-box для gomobile. Версии sing/sing-box должны совпадать с тем,
// что тянет sing-box (см. go.mod sing-box v1.13.13). go.sum генерируется `go mod tidy`
// в CI — здесь не коммитим, т.к. в этой среде нет Go.
require (
	github.com/sagernet/sing v0.8.10
	github.com/sagernet/sing-box v1.13.13
)
