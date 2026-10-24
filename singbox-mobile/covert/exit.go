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
		go serveKCP(kcpSess, logf)
	}
}

// Одно рукопожатие за раз. Остальные ждут в очереди и не закрываются сразу:
// замер 23.10.2026 показал, что мгновенный EOF на лишних потоках не даёт
// поднять несколько соединений. Замер 24.10.2026 на телефоне: слот отпускался
// через 4 с, выход открывал следующую пачку, пока первая ещё не доехала,
// и все сокеты замирали на 517/5033. Слот занят, пока поток жив.
// Таймер «20 с без новых байт» убран: io.Copy в smux принимает пачку сразу,
// и таймер убивал уже идущую передачу, пока кадры ещё ехали в документ.
const (
	streamSlots = 1
	streamWait  = 90 * time.Second
	// Пауза после зависшего рукопожатия, чтобы следующий ответ не лёг поверх
	// предыдущих 5 КБ. Короткая: сам зависший поток закрывается по таймауту.
	dialGap = 4 * time.Second
	// Телефон закрывал поток через ~17 с, не дождавшись ответа. Дольше держать
	// единственный слот нельзя: 24.10 один сокет провисел 517/5033 больше минуты,
	// а очередь из остальных потоков умерла по таймауту.
	handshakeWait = 18 * time.Second
)

var streamGate = make(chan struct{}, streamSlots)

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
		go func(stream net.Conn) {
			select {
			case streamGate <- struct{}{}:
			case <-time.After(streamWait):
				logf("[covert-exit] stream queue timeout")
				stream.Close()
				return
			}
			var once sync.Once
			release := func() { once.Do(func() { <-streamGate }) }
			defer release()
			handleStream(stream, logf, release)
		}(stream)
	}
}

func handleStream(stream net.Conn, logf func(string, ...any), release func()) {
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
	// Уже закрытый поток не дозвоняем: иначе мёртвая очередь снимает по 5 КБ.
	prefix, alive := streamAlive(stream)
	if !alive {
		return
	}
	waitDialGap()
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
		w := progWriter{w: remote, n: &uploaded, mark: 1500, onProgress: func() {
			clearDialGap()
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
	// Ответ xray пишем в канал всегда. Прошлая версия выбрасывала уже прочитанные
	// 5 КБ, если клиент к этому моменту прислал меньше 800 байт (замер 12:12, up=780).
	// Слот отпускаем, как только ответ ушёл в канал: клиентский hello часто ~517 Б
	// и следующий кусок меньше 1500, пока приложение молчит. Раньше на 18-й секунде
	// такой поток убивался, хотя рукопожатие уже прошло.
	go func() {
		w := progWriter{w: stream, n: &downloaded, mark: 1000, onProgress: func() {
			clearDialGap()
			release()
		}}
		_, _ = io.Copy(&w, remote)
	}()

	timer := time.NewTimer(handshakeWait)
	defer timer.Stop()
	select {
	case <-clientDone:
		if uploaded.Load() > 1500 || downloaded.Load() > 1000 {
			clearDialGap()
			release()
			return
		}
		armDialGap()
		logf("[covert-exit] client closed up=%d down=%d", uploaded.Load(), downloaded.Load())
	case <-timer.C:
		if uploaded.Load() > 1500 || downloaded.Load() > 1000 {
			clearDialGap()
			release()
			logf("[covert-exit] handshake hold up=%d down=%d", uploaded.Load(), downloaded.Load())
			<-clientDone
			return
		}
		armDialGap()
		logf("[covert-exit] handshake timeout up=%d down=%d", uploaded.Load(), downloaded.Load())
		remote.Close()
		stream.Close()
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
	if n > 0 && p.mark > 0 && p.n.Add(int64(n)) > p.mark && p.onProgress != nil {
		p.once.Do(p.onProgress)
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
