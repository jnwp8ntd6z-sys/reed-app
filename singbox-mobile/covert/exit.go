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
	"os"
	"strconv"
	"strings"
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
		win := 256
		if kind == "cups" {
			win = 128
		}
		kcpSess.SetWindowSize(win, win)
		// Яндекс: телефон не получает кадр крупнее ooMaxFrame, MTU 280.
		// Cups вмещает сегмент 1200. Входящий буфер kcp-go всё равно 1500.
		kcpSess.SetMtu(mtuForCarrier(kind))
		kcpSess.SetStreamMode(true)
		tuneKCPFor(kcpSess, kind)
		go serveKCP(kcpSess, logf, tuneFor(kind))
	}
}

// Яндекс держит один слот: лишние рукопожатия забивают короткий кадр.
// Cups — четыре. Порог прогресса низкий: 24.10.2026 порог 1500 держал слот
// до конца соединения, если телефон прислал больше hello, но меньше 1500.
// За пару минут 80 потоков умерли в очереди, пока живой звонок занимал слот.
const (
	streamSlots = 1
	streamWait  = 90 * time.Second
	// Reality до моста доделывает сам выход, в комнату уходит короткий
	// кадр. Слоты больше не держат одно 5 КБ hello: параллельные потоки
	// телефона (мессенджер открывает их пачкой) идут вместе.
	cupsSlots       = 32
	cupsWait        = 45 * time.Second
	cupsProgress    = 1
	cupsReleaseDown = 16000
	yandexProgress  = 1500
	// Пауза после зависшего рукопожатия, чтобы следующий ответ не лёг поверх
	// предыдущих 5 КБ. Только Яндекс.
	dialGap = 4 * time.Second
	// Cups больше не разводит рукопожатия по секунде: в комнате нет ответа xray.
	cupsDialGap = time.Duration(0)
	// Телефон закрывал поток через ~17 с, не дождавшись ответа.
	handshakeWait = 18 * time.Second
)

type exitTune struct {
	gate     chan struct{}
	wait     time.Duration
	header   time.Duration
	progress int64
	gap      bool
	pace     time.Duration
}

func tuneFor(kind string) exitTune {
	if kind == "cups" {
		// Заголовок на LTE доезжает дольше 12 с: кадр теряется, повтор ждёт RTO.
		// Яндекс остаётся на 12 с.
		return exitTune{gate: make(chan struct{}, cupsSlots), wait: cupsWait, header: 25 * time.Second, progress: cupsProgress, pace: cupsDialGap}
	}
	return exitTune{gate: make(chan struct{}, streamSlots), wait: streamWait, header: 12 * time.Second, progress: yandexProgress, gap: true}
}

var dialMu sync.Mutex
var nextDial time.Time

// paceDial разводит старты рукопожатий. Два потока, пришедшие вместе,
// не начинают набор в одну и ту же миллисекунду.
func paceDial(d time.Duration) {
	if d <= 0 {
		return
	}
	dialMu.Lock()
	wait := time.Until(nextDial)
	base := time.Now()
	if wait > 0 {
		base = nextDial
	}
	nextDial = base.Add(d)
	dialMu.Unlock()
	if wait > 0 {
		time.Sleep(wait)
	}
}

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

// dialUpstream звонит напрямую или через локальный SOCKS, если задан
// COVERT_UPLINK_SOCKS. Через него выход на №2 сам договаривается с мостом №1,
// и телефон в комнату кладёт только короткий кадр, не рукопожатие Reality.
func dialUpstream(dest string) (net.Conn, error) {
	socks := strings.TrimSpace(os.Getenv("COVERT_UPLINK_SOCKS"))
	if socks == "" {
		return net.DialTimeout("tcp", dest, 15*time.Second)
	}
	conn, err := net.DialTimeout("tcp", socks, 15*time.Second)
	if err != nil {
		return nil, err
	}
	if err := socks5Connect(conn, dest); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func socks5Connect(conn net.Conn, dest string) error {
	host, portStr, err := net.SplitHostPort(dest)
	if err != nil {
		return err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("cups: bad port %q", portStr)
	}
	if len(host) == 0 || len(host) > 255 {
		return fmt.Errorf("cups: bad host")
	}
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return err
	}
	greet := make([]byte, 2)
	if _, err := io.ReadFull(conn, greet); err != nil {
		return err
	}
	if greet[0] != 0x05 || greet[1] != 0x00 {
		return fmt.Errorf("socks auth %d", greet[1])
	}
	req := make([]byte, 0, 5+len(host)+2)
	req = append(req, 0x05, 0x01, 0x00, 0x03, byte(len(host)))
	req = append(req, host...)
	req = append(req, byte(port>>8), byte(port))
	if _, err := conn.Write(req); err != nil {
		return err
	}
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return err
	}
	if hdr[1] != 0 {
		return fmt.Errorf("socks connect %d", hdr[1])
	}
	var skip int64
	switch hdr[3] {
	case 0x01:
		skip = 4 + 2
	case 0x04:
		skip = 16 + 2
	case 0x03:
		var n [1]byte
		if _, err := io.ReadFull(conn, n[:]); err != nil {
			return err
		}
		skip = int64(n[0]) + 2
	default:
		return fmt.Errorf("socks atyp %d", hdr[3])
	}
	if _, err := io.CopyN(io.Discard, conn, skip); err != nil {
		return err
	}
	return conn.SetDeadline(time.Time{})
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
	headerWait := tune.header
	if headerWait <= 0 {
		headerWait = 12 * time.Second
	}
	_ = stream.SetDeadline(time.Now().Add(headerWait))
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
	if tune.pace > 0 {
		paceDial(tune.pace)
	}
	if tune.gap {
		waitDialGap()
	}
	remote, err := dialUpstream(dest)
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
	// Ответ xray пишем в канал всегда. Слот не отпускаем на 5 КБ hello:
	// запись в smux ещё не значит, что телефон их прочитал. Большое тело
	// вниз — уже живой ответ, слот можно отдать следующему потоку.
	go func() {
		w := progWriter{w: stream, n: &downloaded, mark: cupsReleaseDown, onProgress: func() {
			if tune.pace > 0 {
				release()
			}
		}}
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
