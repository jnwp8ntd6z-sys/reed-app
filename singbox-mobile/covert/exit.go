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
	pc, err := newCarrier(ctx, true, publicURL, 0)
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
		kcpSess.SetWindowSize(256, 256)
		// Телефон получает мелкие cursor и не получает кадр с полным сегментом MTU 1200.
		// Меньший MTU только на выходе: входящие сегменты телефона (до 1200) всё ещё
		// помещаются в приёмный буфер kcp-go (1500).
		kcpSess.SetMtu(280)
		kcpSess.SetStreamMode(true)
		tuneKCP(kcpSess)
		go serveKCP(kcpSess, logf)
	}
}

// Восемь потоков, не один. Замер 23.10.2026: при одном слоте из шести Open
// доезжает один ответ, остальные — EOF, и Телеграм так не поднимается.
// Длинные кадры всё равно уходят не чаще ooSendEvery из общего writeLoop,
// поэтому слоты не разгоняют cursor. Десятки не открываем: старый клиент
// без паузы снова упирается в 4004.
var streamGate = make(chan struct{}, 8)

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
		select {
		case streamGate <- struct{}{}:
			go func() {
				defer func() { <-streamGate }()
				handleStream(stream, logf)
			}()
		default:
			stream.Close()
		}
	}
}

func handleStream(stream net.Conn, logf func(string, ...any)) {
	defer stream.Close()
	// Срок только на заголовок: иначе слот вечно держит зависшее рукопожатие.
	// На саму передачу срок не ставим. 12 с на весь поток обрывали уже ~40 КБ
	// при щадящем темпе, а KCP продолжал слать неподтверждённое и ловил drop.
	_ = stream.SetDeadline(time.Now().Add(12 * time.Second))
	dest, err := readConnectHeader(stream)
	if err != nil {
		logf("[covert-exit] header: %v", err)
		return
	}
	_ = stream.SetDeadline(time.Time{})
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
