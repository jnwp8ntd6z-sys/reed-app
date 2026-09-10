package covert

// Серверная сторона covert-канала (exit-node). Запускается на нашем боксе-выходе.
// Зеркалит клиентский стек: VolgaPacketConn(exit) → KCP ServeConn → smux server →
// на каждый поток читает connect-заголовок (host:port), дозванивается и релеит.
// Здесь НЕТ gvisor/raw-socket (в отличие от OpenFlux) — обычный net.Dial наружу.

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"time"

	kcp "github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

// RunExit поднимает выход на публичной ссылке и обслуживает клиентов до ctx.Done().
// logf — необязательный логгер (может быть nil).
func RunExit(ctx context.Context, publicURL string, logf func(string, ...any)) error {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	pc, err := NewVolgaPacketConn(ctx, true, publicURL, 0)
	if err != nil {
		return err
	}
	defer pc.Close()
	logf("[covert-exit] paired, serving KCP")

	listener, err := kcp.ServeConn(nil, 0, 0, pc)
	if err != nil {
		return err
	}
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		kcpSess, err := listener.AcceptKCP()
		if err != nil {
			return err
		}
		kcpSess.SetNoDelay(1, 30, 2, 1)
		kcpSess.SetWindowSize(256, 256)
		kcpSess.SetMtu(1200)
		kcpSess.SetStreamMode(true)
		kcpSess.SetACKNoDelay(true)
		go serveKCP(kcpSess, logf)
	}
}

func serveKCP(kcpSess net.Conn, logf func(string, ...any)) {
	defer kcpSess.Close()
	sess, err := smux.Server(kcpSess, smuxConfig())
	if err != nil {
		logf("[covert-exit] smux server: %v", err)
		return
	}
	defer sess.Close()
	for {
		stream, err := sess.AcceptStream()
		if err != nil {
			return
		}
		go handleStream(stream, logf)
	}
}

func handleStream(stream net.Conn, logf func(string, ...any)) {
	defer stream.Close()
	dest, err := readConnectHeader(stream)
	if err != nil {
		logf("[covert-exit] header: %v", err)
		return
	}
	remote, err := net.DialTimeout("tcp", dest, 15*time.Second)
	if err != nil {
		logf("[covert-exit] dial %s: %v", dest, err)
		return
	}
	defer remote.Close()
	logf("[covert-exit] -> %s", dest)
	go func() { io.Copy(remote, stream); remote.Close() }()
	io.Copy(stream, remote)
}

func readConnectHeader(stream net.Conn) (string, error) {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(stream, hdr); err != nil {
		return "", err
	}
	n := binary.BigEndian.Uint16(hdr)
	if n == 0 || n > 512 {
		return "", fmt.Errorf("bad header len %d", n)
	}
	addr := make([]byte, n)
	if _, err := io.ReadFull(stream, addr); err != nil {
		return "", err
	}
	return string(addr), nil
}
