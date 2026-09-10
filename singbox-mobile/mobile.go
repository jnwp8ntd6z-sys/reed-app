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
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"

	"github.com/reedvpn/singbox-mobile/covert"
)

// baseContext строит контекст sing-box со всеми штатными реестрами ПЛЮС нашим
// covert-outbound'ом, чтобы конфиг мог ссылаться на {"type":"covert",...}.
func baseContext() context.Context {
	outReg := include.OutboundRegistry()
	covert.RegisterOutbound(outReg)
	return box.Context(context.Background(),
		include.InboundRegistry(),
		outReg,
		include.EndpointRegistry(),
		include.DNSTransportRegistry(),
		include.ServiceRegistry(),
	)
}

// mu сериализует доступ к instance/cancel. БЕЗ него быстрый Stop→Start при
// переключении сервера «на ходу» давал гонку по глобальному инстансу и НАТИВНЫЙ КРАШ
// (olcRTC-биндинг такой мьютекс имеет — поэтому крашил только VLESS-путь).
var (
	mu       sync.Mutex
	instance *box.Box
	cancel   context.CancelFunc
)

// Start запускает sing-box из JSON-конфига. Конфиг должен содержать socks-inbound —
// именно его адрес (127.0.0.1:port) потом использует tun2socks.
func Start(configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		stopLocked()
	}

	// Контекст со всеми реестрами sing-box + наш covert-outbound.
	baseCtx := baseContext()

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
	mu.Lock()
	defer mu.Unlock()
	return stopLocked()
}

// stopLocked закрывает инстанс; вызывать ТОЛЬКО под mu.
func stopLocked() error {
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
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}
