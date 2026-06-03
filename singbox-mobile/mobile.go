// Package singboxmobile — тонкая gomobile-обёртка над sing-box.
//
// Модель такая же, как у olcRTC mobile-биндинга: ядро запускается ВНУТРИ приложения
// и поднимает ЛОКАЛЬНЫЙ SOCKS5 (его задаёт socks-inbound в конфиге), на который потом
// наводится tun2socks. Поэтому здесь НЕ нужен тяжёлый libbox.PlatformInterface
// (он для случая, когда sing-box сам владеет TUN) — нам достаточно пакета box.
//
// Конфиг приходит с нашего сервера: GET /app/singbox?token=&socks_port=N (sub_server.py),
// он уже содержит inbound socks 127.0.0.1:N + VLESS Reality outbounds + RU split-routing.
//
// Экспортируемые функции gomobile-совместимы (string/bool/error):
//   Start(configJSON string) error — запустить из JSON-конфига
//   Stop() error                   — остановить
//   IsRunning() bool               — статус
//
// Сборка (в CI, как olcrtc):
//   gomobile bind -target=android -o singbox.aar .
//   gomobile bind -target=ios     -o SingBoxMobile.xcframework .
//
// СТАТУС: WIP. Код написан по исходнику sing-box v1.13.13 (box.New / include.Context /
// json.UnmarshalExtendedContext), но в этой среде нет Go → не скомпилирован. Перед
// подключением в gradle/CI прогнать `go mod tidy` и сборку gomobile.
package singboxmobile

import (
	"context"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

var (
	instance *box.Box
	cancel   context.CancelFunc
)

// Start запускает sing-box из JSON-конфига. Конфиг должен содержать socks-inbound —
// именно его адрес (127.0.0.1:port) потом использует tun2socks.
func Start(configJSON string) error {
	if instance != nil {
		_ = Stop()
	}

	// Контекст со всеми реестрами sing-box (inbound/outbound/endpoint/dns/service).
	baseCtx := include.Context(context.Background())

	options, err := json.UnmarshalExtendedContext[option.Options](baseCtx, []byte(configJSON))
	if err != nil {
		return err
	}

	runCtx, c := context.WithCancel(baseCtx)
	instanceBox, err := box.New(box.Options{
		Context: runCtx,
		Options: options,
	})
	if err != nil {
		c()
		return err
	}

	if err = instanceBox.Start(); err != nil {
		_ = instanceBox.Close()
		c()
		return err
	}

	instance = instanceBox
	cancel = c
	return nil
}

// Stop останавливает текущий инстанс sing-box (idempotent).
func Stop() error {
	if instance == nil {
		return nil
	}
	err := instance.Close()
	if cancel != nil {
		cancel()
	}
	instance = nil
	cancel = nil
	return err
}

// IsRunning сообщает, запущен ли инстанс.
func IsRunning() bool {
	return instance != nil
}
