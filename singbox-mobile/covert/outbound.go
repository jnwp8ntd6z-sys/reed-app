package covert

// Нативный outbound sing-box "covert": VLESS (или любой TCP-outbound) через detour в
// него получает надёжный поток, который под капотом едет по covert-каналу Яндекс.volga.
//
//   VLESS-out  --detour-->  covert-out
//                              └ smux (мультиплекс)         ← много потоков в одной сессии
//                                 └ KCP (надёжность+порядок) ← поверх неупорядоченного relay
//                                    └ VolgaPacketConn        ← relay + Xiva
//
// gvisor НЕ используется (в отличие от PoC OpenFlux) → память расширения iOS не рвётся.
// Сторона выхода — отдельный exit (ServeConn + KCP+smux server), собирается отдельно.
//
// Конфиг:
//   { "type":"covert", "tag":"covert-out", "public_url":"https://disk.yandex.ru/i/..." }

import (
	"context"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	kcp "github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

const TypeCovert = "covert"

// CovertOutboundOptions — опции outbound'а в конфиге sing-box.
type CovertOutboundOptions struct {
	PublicURL string `json:"public_url"`
}

// RegisterOutbound добавляет тип "covert" в реестр outbound'ов sing-box.
func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[CovertOutboundOptions](registry, TypeCovert, NewCovertOutbound)
}

type Outbound struct {
	outbound.Adapter
	ctx       context.Context
	logger    log.ContextLogger
	publicURL string

	mu   sync.Mutex
	sess *smux.Session
}

func NewCovertOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options CovertOutboundOptions) (adapter.Outbound, error) {
	if options.PublicURL == "" {
		return nil, fmt.Errorf("covert: public_url is required")
	}
	return &Outbound{
		Adapter:   outbound.NewAdapter(TypeCovert, tag, []string{N.NetworkTCP}, nil),
		ctx:       ctx,
		logger:    logger,
		publicURL: options.PublicURL,
	}, nil
}

// smuxConfig — таймауты растянуты под медленный covert-канал.
func smuxConfig() *smux.Config {
	cfg := smux.DefaultConfig()
	cfg.KeepAliveInterval = 15 * time.Second
	cfg.KeepAliveTimeout = 60 * time.Second
	cfg.MaxReceiveBuffer = 4 * 1024 * 1024
	cfg.MaxStreamBuffer = 512 * 1024
	return cfg
}

// ensureSession лениво поднимает covert-сессию (packetconn → KCP → smux).
func (o *Outbound) ensureSession(ctx context.Context) (*smux.Session, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.sess != nil && !o.sess.IsClosed() {
		return o.sess, nil
	}

	pc, err := NewVolgaPacketConn(ctx, false, o.publicURL, 45*time.Second)
	if err != nil {
		return nil, err
	}
	kcpConn, err := kcp.NewConn2(peerAddr{id: pc.peerUserID.Load()}, nil, 0, 0, pc)
	if err != nil {
		pc.Close()
		return nil, err
	}
	// KCP под высокую задержку/узкий канал: turbo NoDelay, окна побольше.
	kcpConn.SetNoDelay(1, 30, 2, 1)
	kcpConn.SetWindowSize(256, 256)
	kcpConn.SetMtu(1200)
	kcpConn.SetStreamMode(true)
	kcpConn.SetACKNoDelay(true)

	sess, err := smux.Client(kcpConn, smuxConfig())
	if err != nil {
		kcpConn.Close()
		pc.Close()
		return nil, err
	}
	o.sess = sess
	return sess, nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
	default:
		return nil, fmt.Errorf("covert: only TCP is supported, got %q", network)
	}
	sess, err := o.ensureSession(ctx)
	if err != nil {
		return nil, err
	}
	stream, err := sess.OpenStream()
	if err != nil {
		// сессия могла умереть — сбросим, следующий Dial поднимет заново.
		o.mu.Lock()
		if o.sess == sess {
			o.sess = nil
		}
		o.mu.Unlock()
		sess.Close()
		return nil, err
	}
	if err := writeConnectHeaderStr(stream, destination.String()); err != nil {
		stream.Close()
		return nil, err
	}
	return stream, nil
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("covert: UDP is not supported")
}

var _ adapter.Outbound = (*Outbound)(nil)
