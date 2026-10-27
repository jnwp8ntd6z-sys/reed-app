package covert

// Серверная сторона covert-канала (exit-node). Запускается на нашем боксе-выходе.
// Зеркалит клиентский стек: VolgaPacketConn(exit) → KCP ServeConn → smux server →
// на каждый поток читает connect-заголовок (host:port), дозванивается и релеит.
// Здесь НЕТ gvisor/raw-socket (в отличие от OpenFlux) — обычный net.Dial наружу.

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
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
	kind := resolveKind("", publicURL)
	if kind == "cups" {
		return runExitCups(ctx, publicURL, logf)
	}
	pc, err := newCarrierKind(ctx, true, publicURL, 0, kind)
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
		// Яндекс: телефон не получает кадр крупнее ooMaxFrame, MTU 280.
		// Cups вмещает сегмент 1200. Входящий буфер kcp-go всё равно 1500.
		kcpSess.SetMtu(mtuForCarrier(kind))
		kcpSess.SetStreamMode(true)
		tuneKCP(kcpSess)
		go serveKCP(kcpSess, logf, tuneFor(kind))
	}
}

// Яндекс держит один слот: лишние рукопожатия забивают короткий кадр.
// Cups — четыре. Порог прогресса низкий: 24.10.2026 порог 1500 держал слот
// до конца соединения, если телефон прислал больше hello, но меньше 1500.
// За пару минут 80 потоков умерли в очереди, пока живой звонок занимал слот.
const (
	streamSlots    = 1
	streamWait     = 90 * time.Second
	cupsSlots      = 24
	cupsWait       = 25 * time.Second
	cupsProgress   = 600
	yandexProgress = 1500
	// Пауза после зависшего рукопожатия, чтобы следующий ответ не лёг поверх
	// предыдущих 5 КБ.
	dialGap = 4 * time.Second
	// Телефон закрывал поток через ~17 с, не дождавшись ответа.
	handshakeWait = 18 * time.Second
)

type exitTune struct {
	gate     chan struct{}
	wait     time.Duration
	progress int64
	gap      bool
}

func tuneFor(kind string) exitTune {
	if kind == "cups" {
		return exitTune{gate: make(chan struct{}, cupsSlots), wait: cupsWait, progress: cupsProgress}
	}
	return exitTune{gate: make(chan struct{}, streamSlots), wait: streamWait, progress: yandexProgress, gap: true}
}

var dialMu sync.Mutex
var nextDial time.Time

func waitDialGap() {
	for {
		dialMu.Lock()
		wait := time.Until(nextDial)
		dialMu.Unlock()
		if wait <= 0 {
			return
		}
		if wait > 200*time.Millisecond {
			wait = 200 * time.Millisecond
		}
		time.Sleep(wait)
	}
}

func armDialGap() {
	dialMu.Lock()
	nextDial = time.Now().Add(dialGap)
	dialMu.Unlock()
}

func clearDialGap() {
	dialMu.Lock()
	nextDial = time.Time{}
	dialMu.Unlock()
}

func serveKCP(kcpSess net.Conn, logf func(string, ...any), tune exitTune) error {
	defer kcpSess.Close()
	sess, err := smux.Server(kcpSess, smuxConfig())
	if err != nil {
		logf("[covert-exit] smux server: %v", err)
		return err
	}
	defer sess.Close()
	for {
		stream, err := sess.AcceptStream()
		if err != nil {
			return err
		}
		go func(stream net.Conn) {
			select {
			case tune.gate <- struct{}{}:
			case <-time.After(tune.wait):
				logf("[covert-exit] stream queue timeout")
				stream.Close()
				return
			}
			var once sync.Once
			release := func() { once.Do(func() { <-tune.gate }) }
			defer release()
			handleStream(stream, logf, release, tune)
		}(stream)
	}
}

func handleStream(stream net.Conn, logf func(string, ...any), release func(), tune exitTune) {
	defer stream.Close()
	progress := tune.progress
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
	// Уже закрытый поток не дозвоняем: иначе мёртвая очередь снимает по 5 КБ.
	prefix, alive := streamAlive(stream)
	if !alive {
		return
	}
	if tune.gap {
		waitDialGap()
	}
	remote, err := net.DialTimeout("tcp", dest, 15*time.Second)
	if err != nil {
		logf("[covert-exit] dial %s: %v", dest, err)
		return
	}
	defer remote.Close()
	logf("[covert-exit] -> %s", dest)

	var uploaded atomic.Int64
	var downloaded atomic.Int64
	clientDone := make(chan struct{})
	go func() {
		w := progWriter{w: remote, n: &uploaded, mark: progress, onProgress: func() {
			if tune.gap {
				clearDialGap()
			}
			release()
		}}
		if len(prefix) > 0 {
			_, _ = w.Write(prefix)
		}
		_, _ = io.Copy(&w, stream)
		close(clientDone)
		if tc, ok := remote.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}()
	// Ответ xray пишем в канал всегда. Слот не отпускаем, пока телефон не прислал
	// байты сверх client hello: запись 5 КБ в smux ещё не значит, что он их получил.
	go func() {
		w := progWriter{w: stream, n: &downloaded}
		_, _ = io.Copy(&w, remote)
	}()

	timer := time.NewTimer(handshakeWait)
	defer timer.Stop()
	// 18 с без единого нового байта — зависшее рукопожатие, слот надо отпустить.
	// Идущая загрузка при коротком запросе (up меньше порога) сюда не попадает:
	// пока байты в любую сторону растут, срок сдвигается. Иначе длинная отдача
	// обрывалась на 18-й секунде, хотя канал был жив.
	var seenUp, seenDown int64
	for {
		select {
		case <-clientDone:
			if uploaded.Load() > progress {
				if tune.gap {
					clearDialGap()
				}
				release()
				return
			}
			if tune.gap {
				armDialGap()
			}
			logf("[covert-exit] client closed up=%d down=%d", uploaded.Load(), downloaded.Load())
			return
		case <-timer.C:
			up, down := uploaded.Load(), downloaded.Load()
			if up > progress {
				if tune.gap {
					clearDialGap()
				}
				release()
				logf("[covert-exit] handshake hold up=%d down=%d", up, down)
				<-clientDone
				return
			}
			if up != seenUp || down != seenDown {
				seenUp, seenDown = up, down
				timer.Reset(handshakeWait)
				continue
			}
			if tune.gap {
				armDialGap()
			}
			logf("[covert-exit] handshake timeout up=%d down=%d", up, down)
			remote.Close()
			stream.Close()
			return
		}
	}
}

// progWriter считает байты и один раз зовёт onProgress, когда счётчик перешёл mark.
type progWriter struct {
	w          io.Writer
	n          *atomic.Int64
	mark       int64
	onProgress func()
	once       sync.Once
}

func (p *progWriter) Write(b []byte) (int, error) {
	n, err := p.w.Write(b)
	if n > 0 && p.n != nil {
		total := p.n.Add(int64(n))
		if p.mark > 0 && total > p.mark && p.onProgress != nil {
			p.once.Do(p.onProgress)
		}
	}
	return n, err
}

// streamAlive читает один байт. EOF — поток уже закрыт. Таймаут — клиент жив,
// данных ещё нет. Байт возвращается, чтобы не потерять начало client hello.
func streamAlive(stream net.Conn) ([]byte, bool) {
	_ = stream.SetReadDeadline(time.Now().Add(80 * time.Millisecond))
	var b [1]byte
	n, err := stream.Read(b[:])
	_ = stream.SetReadDeadline(time.Time{})
	if n == 1 {
		return b[:], true
	}
	if err == nil {
		return nil, true
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return nil, true
	}
	return nil, false
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
