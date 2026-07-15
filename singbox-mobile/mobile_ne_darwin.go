// Package singboxmobile — iOS/macOS (darwin) путь: запуск sing-box с TUN-дескриптором,
// который отдаёт NEPacketTunnelProvider (Network Extension). В отличие от Start() (локальный
// SOCKS для tun2socks), здесь sing-box САМ владеет TUN и маршрутизирует пакеты по конфигу
// (split-routing РФ уже в конфиге сервера). Это настоящий системный VPN.
//
// Механика (как в experimental/libbox, но PlatformInterface реализован ЗДЕСЬ, в Go —
// Swift-стороне достаточно передать fd; libbox-command-server и его тяжёлый gomobile-
// интерфейс не нужны):
//   - box.New берёт adapter.PlatformInterface из контекста (service.FromContext);
//   - для tun-inbound NetworkManager зовёт OpenInterface → мы кладём fd в tun.Options и
//     возвращаем tun.New(fd);
//   - при ненулевом PlatformInterface NetworkManager ТРЕБУЕТ CreateDefaultInterfaceMonitor,
//     поэтому отдаём минимальный монитор (в NE маршрутизацию задаёт сама Network Extension
//     через NEPacketTunnelNetworkSettings, поэтому default-interface не нужен).
//
// Файл darwin-only: на Android VLESS идёт через Start()+VpnService/tun2socks, StartTun не нужен.

//go:build darwin

package singboxmobile

import (
	"context"
	"net/netip"
	"os"
	"sync"
	"syscall"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"

	"golang.org/x/sys/unix"
)

// StartTun запускает sing-box, отдавая ему TUN-дескриптор Network Extension.
// configJSON должен содержать tun-inbound (auto_route=false — маршруты задаёт NE).
// Переиспользует глобальные mu/instance/cancel из mobile.go → Stop() останавливает и этот путь.
func StartTun(configJSON string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		stopLocked()
	}

	baseCtx := include.Context(context.Background())
	platform := &nePlatformInterface{tunFd: tunFd}
	baseCtx = service.ContextWith[adapter.PlatformInterface](baseCtx, platform)

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

// ── adapter.PlatformInterface для Network Extension ─────────────────────────

type nePlatformInterface struct {
	tunFd   int
	monitor *neInterfaceMonitor
}

func (p *nePlatformInterface) Initialize(networkManager adapter.NetworkManager) error { return nil }

func (p *nePlatformInterface) UsePlatformAutoDetectInterfaceControl() bool { return false }
func (p *nePlatformInterface) AutoDetectInterfaceControl(fd int) error     { return nil }

func (p *nePlatformInterface) UsePlatformInterface() bool { return true }

func (p *nePlatformInterface) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	name, err := getTunnelName(int32(p.tunFd))
	if err != nil {
		return nil, err
	}
	options.Name = name
	if options.InterfaceMonitor != nil {
		options.InterfaceMonitor.RegisterMyInterface(name)
	}
	// dup: sing-box закрывает свой fd при остановке; оставляем оригинал NE нетронутым.
	dupFd, err := syscall.Dup(p.tunFd)
	if err != nil {
		return nil, err
	}
	options.FileDescriptor = dupFd
	return tun.New(*options)
}

func (p *nePlatformInterface) UsePlatformDefaultInterfaceMonitor() bool { return true }

func (p *nePlatformInterface) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	p.monitor = &neInterfaceMonitor{}
	return p.monitor
}

func (p *nePlatformInterface) UsePlatformNetworkInterfaces() bool                        { return false }
func (p *nePlatformInterface) NetworkInterfaces() ([]adapter.NetworkInterface, error)     { return nil, nil }
func (p *nePlatformInterface) UnderNetworkExtension() bool                                { return true }
func (p *nePlatformInterface) NetworkExtensionIncludeAllNetworks() bool                   { return false }
func (p *nePlatformInterface) ClearDNSCache()                                             {}
func (p *nePlatformInterface) RequestPermissionForWIFIState() error                       { return nil }
func (p *nePlatformInterface) ReadWIFIState() adapter.WIFIState                           { return adapter.WIFIState{} }
func (p *nePlatformInterface) SystemCertificates() []string                              { return nil }
func (p *nePlatformInterface) UsePlatformConnectionOwnerFinder() bool                     { return false }

func (p *nePlatformInterface) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}

func (p *nePlatformInterface) UsePlatformWIFIMonitor() bool                     { return false }
func (p *nePlatformInterface) UsePlatformNotification() bool                    { return false }
func (p *nePlatformInterface) SendNotification(n *adapter.Notification) error   { return nil }
func (p *nePlatformInterface) MyInterfaceAddress() []netip.Addr                 { return nil }

// ── Минимальный DefaultInterfaceMonitor ─────────────────────────────────────
// В Network Extension маршрутизацию исходящих задаёт сама NE (туннель исключён из
// собственного маршрута), поэтому default-interface нам не нужен: отдаём nil.

type neInterfaceMonitor struct {
	access    sync.Mutex
	callbacks list.List[tun.DefaultInterfaceUpdateCallback]
}

func (m *neInterfaceMonitor) Start() error                        { return nil }
func (m *neInterfaceMonitor) Close() error                        { return nil }
func (m *neInterfaceMonitor) DefaultInterface() *control.Interface { return nil }
func (m *neInterfaceMonitor) OverrideAndroidVPN() bool            { return false }
func (m *neInterfaceMonitor) AndroidVPNEnabled() bool             { return false }

func (m *neInterfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *neInterfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *neInterfaceMonitor) RegisterMyInterface(interfaceName string) {}
func (m *neInterfaceMonitor) MyInterfaces() []string                   { return nil }

// getTunnelName — имя utun-интерфейса по его fd (как в libbox/tun_name_darwin.go).
func getTunnelName(fd int32) (string, error) {
	return unix.GetsockoptString(
		int(fd),
		2, /* SYSPROTO_CONTROL */
		2, /* UTUN_OPT_IFNAME */
	)
}
