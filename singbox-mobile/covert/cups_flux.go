package covert

// CupsFlux — net.PacketConn как у OpenFlux cupsonline, без своего слоя
// подтверждений. Четыре комнаты (или сколько перечислено в rooms=) шлют
// кадры по кругу. Внутри комнаты батч копится 2 мс или до 22000 байт,
// режется на сообщения по 8192 и уходит раз в 12 мс. Ответ RPC не ждём:
// потерянный батч выбрасывается. Повтор делает KCP сверху, как у OpenFlux
// повтор делает TCP. Пинг «{}» пишется в сокет сразу, раньше данных.
// Свои курсоры в KCP не попадают.

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	fluxBatchMax     = 22000
	fluxBatchPackets = 256
	fluxMsgData      = 8192
	fluxBatchWait    = 2 * time.Millisecond
	fluxPace         = 12 * time.Millisecond
	fluxKeepAlive    = 20 * time.Second
	fluxReadWait     = 90 * time.Second
	fluxWriteWait    = 15 * time.Second
	fluxQueue        = 1024
)

// CupsFlux гоняет датаграммы KCP через комнаты cups.online.
type CupsFlux struct {
	exit bool

	ctx  context.Context
	stop context.CancelFunc

	lanes []*fluxLane
	rr    atomic.Uint32

	holdMu    sync.Mutex
	hold      [][]byte
	holdN     int
	holdArmed bool

	rx     chan []byte
	closed chan struct{}
	once   sync.Once

	rdDeadline atomic.Pointer[time.Time]
}

type fluxLane struct {
	parent *CupsFlux
	idx    int
	roomID string

	http *http.Client
	ctx  context.Context

	mu   sync.Mutex
	auth *cupsAuth
	ws   *websocket.Conn

	connected atomic.Bool
	tx        chan []byte
	cmd       atomic.Int64

	echoMu   sync.Mutex
	echoRing [64]uint64
	echoPos  int

	paceMu   sync.Mutex
	lastSend time.Time

	recv map[string][]byte
}

// NewCupsFlux входит в перечисленные комнаты и ждёт хотя бы одну подписку.
// Новые комнаты не создаёт: с адреса №2 cups.online отвечает 403.
func NewCupsFlux(ctx context.Context, exit bool, publicURL string, timeout time.Duration) (*CupsFlux, error) {
	ids := roomIDsFromPublicURL(publicURL)
	if len(ids) == 0 {
		return nil, errors.New("cups: room list required")
	}
	life, stop := context.WithCancel(context.Background())
	c := &CupsFlux{
		exit:   exit,
		ctx:    life,
		stop:   stop,
		rx:     make(chan []byte, rxQueue),
		closed: make(chan struct{}),
	}
	c.lanes = make([]*fluxLane, len(ids))
	for i, id := range ids {
		jar, err := cookiejar.New(nil)
		if err != nil {
			c.Close()
			return nil, err
		}
		lane := &fluxLane{
			parent: c,
			idx:    i,
			roomID: id,
			http: &http.Client{
				Jar:     jar,
				Timeout: 30 * time.Second,
				Transport: &http.Transport{
					DialContext:           forceIPv4Dial,
					ForceAttemptHTTP2:     true,
					TLSHandshakeTimeout:   15 * time.Second,
					ResponseHeaderTimeout: 20 * time.Second,
					IdleConnTimeout:       90 * time.Second,
				},
			},
			ctx:  life,
			tx:   make(chan []byte, fluxQueue),
			recv: map[string][]byte{},
		}
		c.lanes[i] = lane
		go lane.run()
		go lane.sendLoop()
	}

	var tch <-chan time.Time
	if timeout > 0 {
		t := time.NewTimer(timeout)
		defer t.Stop()
		tch = t.C
	}
	for {
		if c.upLanes() > 0 {
			log.Printf("[covert/cups] flux up %d/%d exit=%v", c.upLanes(), len(c.lanes), exit)
			return c, nil
		}
		select {
		case <-ctx.Done():
			c.Close()
			return nil, ctx.Err()
		case <-tch:
			c.Close()
			return nil, errors.New("cups: connect timeout")
		case <-c.closed:
			return nil, errors.New("cups: closed")
		case <-time.After(150 * time.Millisecond):
		}
	}
}

func (c *CupsFlux) upLanes() int {
	n := 0
	for _, lane := range c.lanes {
		if lane.connected.Load() {
			n++
		}
	}
	return n
}

func (c *CupsFlux) deliver(pkt []byte) {
	select {
	case <-c.closed:
	case c.rx <- pkt:
	default:
		log.Printf("[covert/cups] rx full, drop %d bytes", len(pkt))
	}
}

func (c *CupsFlux) ReadFrom(p []byte) (int, net.Addr, error) {
	var tch <-chan time.Time
	if dl := c.rdDeadline.Load(); dl != nil && !dl.IsZero() {
		d := time.Until(*dl)
		if d <= 0 {
			return 0, nil, os_timeout{}
		}
		timer := time.NewTimer(d)
		defer timer.Stop()
		tch = timer.C
	}
	select {
	case <-c.closed:
		return 0, nil, net.ErrClosed
	case <-tch:
		return 0, nil, os_timeout{}
	case b := <-c.rx:
		n := copy(p, b)
		if c.exit {
			return n, convAddr(b), nil
		}
		return n, kcpDialAddr, nil
	}
}

func (c *CupsFlux) WriteTo(p []byte, _ net.Addr) (int, error) {
	if len(p) == 0 || len(p) > 65535 {
		return 0, fmt.Errorf("cups: bad datagram %d", len(p))
	}
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	default:
	}
	buf := append([]byte(nil), p...)
	c.holdMu.Lock()
	c.hold = append(c.hold, buf)
	c.holdN += 2 + len(buf)
	// Одно сообщение комнаты, как кадр OpenFlux ~8 КБ, затем следующая комната.
	if c.holdN < 7000 && len(c.hold) < 16 {
		if !c.holdArmed {
			c.holdArmed = true
			time.AfterFunc(fluxBatchWait, c.flushHold)
		}
		c.holdMu.Unlock()
		return len(p), nil
	}
	pkts := c.hold
	c.hold = nil
	c.holdN = 0
	c.holdArmed = false
	c.holdMu.Unlock()
	c.ship(pkts)
	return len(p), nil
}

func (c *CupsFlux) flushHold() {
	c.holdMu.Lock()
	pkts := c.hold
	c.hold = nil
	c.holdN = 0
	c.holdArmed = false
	c.holdMu.Unlock()
	if len(pkts) > 0 {
		c.ship(pkts)
	}
}

// ship кладёт уже склеенную пачку в одну комнату. Следующая пачка — в следующую.
func (c *CupsFlux) ship(pkts [][]byte) {
	n := len(c.lanes)
	if n == 0 {
		return
	}
	start := int(c.rr.Add(1))
	var lane *fluxLane
	for i := 0; i < n; i++ {
		cand := c.lanes[(start+i)%n]
		if cand.connected.Load() {
			lane = cand
			break
		}
	}
	if lane == nil {
		return
	}
	for _, pkt := range pkts {
		select {
		case <-c.closed:
			return
		case lane.tx <- pkt:
		}
	}
}

func (c *CupsFlux) Close() error {
	c.once.Do(func() {
		c.stop()
		close(c.closed)
		for _, lane := range c.lanes {
			lane.kick()
		}
	})
	return nil
}

func (c *CupsFlux) LocalAddr() net.Addr               { return kcpDialAddr }
func (c *CupsFlux) SetDeadline(t time.Time) error     { c.rdDeadline.Store(&t); return nil }
func (c *CupsFlux) SetReadDeadline(t time.Time) error { c.rdDeadline.Store(&t); return nil }
func (c *CupsFlux) SetWriteDeadline(time.Time) error  { return nil }

func (l *fluxLane) run() {
	delay := 100 * time.Millisecond
	for l.ctx.Err() == nil {
		err := l.serve()
		l.connected.Store(false)
		l.kick()
		if l.ctx.Err() != nil {
			return
		}
		log.Printf("[covert/cups] room %s: %v", shortRoom(l.roomID), err)
		wait := delay
		if err != nil && (errors.Is(err, errRoomGone) || strings.Contains(err.Error(), "room page 404") || strings.Contains(err.Error(), "room page 410")) {
			wait = time.Minute
		}
		if !sleepCtx(l.ctx, wait) {
			return
		}
		if delay < 10*time.Second {
			delay = time.Duration(float64(delay) * 1.3)
		}
	}
}

var errRoomGone = errors.New("cups: room gone")

func shortRoom(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

func (l *fluxLane) serve() error {
	auth, err := cupsAuthorize(l.ctx, l.http, cupsJoinURL(l.roomID))
	if err != nil {
		return err
	}
	if auth.roomUUID != l.roomID {
		return fmt.Errorf("%w: page returned %s", errRoomGone, shortRoom(auth.roomUUID))
	}
	wsURL := cupsSocketURL(auth.connURL)
	header := http.Header{}
	header.Set("Origin", originOf(auth.connURL))
	header.Set("User-Agent", cupsUserAgent)
	if ck := cookieHeader(l.http.Jar, auth.connURL); ck != "" {
		header.Set("Cookie", ck)
	}
	dialer := websocket.Dialer{
		NetDialContext:   forceIPv4Dial,
		HandshakeTimeout: 15 * time.Second,
		ReadBufferSize:   64 << 10,
		WriteBufferSize:  64 << 10,
	}
	ws, _, err := dialer.DialContext(l.ctx, wsURL, header)
	if err != nil {
		return fmt.Errorf("websocket %s: %w", hostOf(wsURL), err)
	}
	defer ws.Close()
	ws.SetReadLimit(8 << 20)
	l.recv = map[string][]byte{}
	l.cmd.Store(2)

	l.mu.Lock()
	l.auth = auth
	l.ws = ws
	l.mu.Unlock()
	defer func() {
		l.mu.Lock()
		l.auth = nil
		l.ws = nil
		l.mu.Unlock()
		l.connected.Store(false)
	}()

	if err := l.writeJSON(map[string]any{
		"id":      1,
		"connect": map[string]any{"token": auth.connToken, "name": "js"},
	}); err != nil {
		return err
	}
	if err := l.waitID(1, 15*time.Second); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	if err := l.writeJSON(map[string]any{
		"id":        2,
		"subscribe": map[string]any{"channel": auth.channel, "token": auth.subToken},
	}); err != nil {
		return err
	}
	if err := l.waitID(2, 15*time.Second); err != nil {
		return fmt.Errorf("subscribe: %w", err)
	}
	l.connected.Store(true)
	log.Printf("[covert/cups] subscribed %s exit=%v", shortRoom(l.roomID), l.parent.exit)

	kaStop := make(chan struct{})
	go func() {
		t := time.NewTicker(fluxKeepAlive)
		defer t.Stop()
		for {
			select {
			case <-kaStop:
				return
			case <-l.ctx.Done():
				return
			case <-t.C:
				if err := l.ping(); err != nil {
					l.kick()
					return
				}
			}
		}
	}()
	defer close(kaStop)

	for {
		lines, err := l.readLines(time.Now().Add(fluxReadWait))
		if err != nil {
			return err
		}
		for _, line := range lines {
			if err := l.consume(line); err != nil {
				return err
			}
		}
	}
}

func (l *fluxLane) ping() error {
	a := l.snapshot()
	if a == nil {
		return nil
	}
	if err := l.waitPace(); err != nil {
		return err
	}
	return l.writeJSON(map[string]any{
		"id": l.cmd.Add(1),
		"rpc": map[string]any{
			"method": "shared_editor_ping",
			"data":   map[string]any{"room": a.roomUUID, "user": a.userUUID},
		},
	})
}

func (l *fluxLane) waitID(id int, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		lines, err := l.readLines(deadline)
		if err != nil {
			return err
		}
		matched := false
		for _, line := range lines {
			ok, bad := matchCommand(line, id)
			if ok {
				matched = true
				if bad != nil {
					return bad
				}
				continue
			}
			if err := l.consume(line); err != nil {
				return err
			}
		}
		if matched {
			return nil
		}
	}
}

func (l *fluxLane) readLines(deadline time.Time) ([][]byte, error) {
	ws := l.currentWS()
	if ws == nil {
		return nil, errors.New("cups: no socket")
	}
	if err := ws.SetReadDeadline(deadline); err != nil {
		return nil, err
	}
	_, raw, err := ws.ReadMessage()
	if err != nil {
		return nil, err
	}
	return splitWS(raw), nil
}

func (l *fluxLane) consume(line []byte) error {
	if string(line) == "{}" {
		return l.writeRaw([]byte("{}"))
	}
	var obj map[string]any
	if json.Unmarshal(line, &obj) != nil {
		return nil
	}
	push, _ := obj["push"].(map[string]any)
	if push == nil {
		return nil
	}
	pub, _ := push["pub"].(map[string]any)
	data, _ := pub["data"].(map[string]any)
	if data == nil {
		return nil
	}
	if t, _ := data["type"].(string); t != "cursors_update" {
		return nil
	}
	payload, _ := data["payload"].(map[string]any)
	if payload == nil {
		return nil
	}
	sender, _ := payload["user_uuid"].(string)
	self := ""
	if a := l.snapshot(); a != nil {
		self = a.userUUID
	}
	if sender == "" || sender == self {
		return nil
	}
	rawCursors, _ := payload["cursors"].([]any)
	chunk, ok := cupsChunkFromCursors(rawCursors)
	if !ok || l.wasEcho(chunk) {
		return nil
	}
	l.accept(sender, chunk)
	return nil
}

func (l *fluxLane) accept(sender string, chunk []byte) {
	buf := append(l.recv[sender], chunk...)
	pkts, rest := fluxPull(buf)
	if len(rest) > 65535+fluxMsgData {
		delete(l.recv, sender)
	} else if len(rest) == 0 {
		delete(l.recv, sender)
	} else {
		l.recv[sender] = rest
	}
	for _, pkt := range pkts {
		l.parent.deliver(pkt)
	}
}

// fluxPull вытаскивает целые пакеты uint16+байты. Ноль в длине — поток сбит.
func fluxPull(buf []byte) (pkts [][]byte, rest []byte) {
	adv := 0
	for len(buf)-adv >= 2 {
		ln := int(binary.BigEndian.Uint16(buf[adv : adv+2]))
		if ln == 0 {
			return pkts, nil
		}
		if len(buf)-adv < 2+ln {
			break
		}
		pkts = append(pkts, append([]byte(nil), buf[adv+2:adv+2+ln]...))
		adv += 2 + ln
	}
	if adv == len(buf) {
		return pkts, nil
	}
	return pkts, append([]byte(nil), buf[adv:]...)
}

func (l *fluxLane) sendLoop() {
	batch := make([][]byte, 0, 16)
	total := 0
	timer := time.NewTimer(fluxBatchWait)
	timer.Stop()
	defer timer.Stop()

	flush := func() {
		timer.Stop()
		if len(batch) == 0 {
			return
		}
		pkts := batch
		batch = make([][]byte, 0, 16)
		n := total
		total = 0
		if !l.connected.Load() {
			return
		}
		if err := l.sendBatch(pkts); err != nil {
			log.Printf("[covert/cups] send %s (%d bytes): %v", shortRoom(l.roomID), n, err)
			l.kick()
		}
	}

	for {
		select {
		case <-l.ctx.Done():
			return
		case pkt := <-l.tx:
			if len(batch) > 0 && total+2+len(pkt) > fluxBatchMax {
				flush()
			}
			batch = append(batch, pkt)
			total += 2 + len(pkt)
			if len(batch) >= fluxBatchPackets || total >= fluxBatchMax {
				flush()
			} else if len(batch) == 1 {
				timer.Reset(fluxBatchWait)
			}
		case <-timer.C:
			flush()
		}
	}
}

func (l *fluxLane) sendBatch(pkts [][]byte) error {
	var inner []byte
	for _, pkt := range pkts {
		var hdr [2]byte
		binary.BigEndian.PutUint16(hdr[:], uint16(len(pkt)))
		inner = append(inner, hdr[:]...)
		inner = append(inner, pkt...)
	}
	for off := 0; off < len(inner); {
		n := len(inner) - off
		if n > fluxMsgData {
			n = fluxMsgData
		}
		chunk := inner[off : off+n]
		if err := l.waitPace(); err != nil {
			return err
		}
		wire := make([]byte, 2+len(chunk))
		binary.BigEndian.PutUint16(wire[:2], uint16(len(chunk)))
		copy(wire[2:], chunk)
		l.rememberEcho(chunk)
		if err := l.rpcCursors(wire); err != nil {
			return err
		}
		off += n
	}
	return nil
}

// waitPace держит 12 мс между сообщениями одной комнаты. Пинг «{}» сюда не входит:
// его пишем сразу, иначе сайт закрывает сокет кодом 3012.
func (l *fluxLane) waitPace() error {
	l.paceMu.Lock()
	defer l.paceMu.Unlock()
	if !l.lastSend.IsZero() {
		if d := fluxPace - time.Since(l.lastSend); d > 0 {
			if !sleepCtx(l.ctx, d) {
				return net.ErrClosed
			}
		}
	}
	l.lastSend = time.Now()
	return nil
}

func (l *fluxLane) rpcCursors(wire []byte) error {
	a := l.snapshot()
	if a == nil || l.currentWS() == nil {
		return errors.New("cups: not in a room")
	}
	return l.writeJSON(map[string]any{
		"id": l.cmd.Add(1),
		"rpc": map[string]any{
			"method": "shared_editor_change_cursors",
			"data": map[string]any{
				"cursors": cupsCursorPairs(wire),
				"ranges":  []any{},
				"room":    a.roomUUID,
				"user":    a.userUUID,
			},
		},
	})
}

func (l *fluxLane) snapshot() *cupsAuth {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.auth == nil {
		return nil
	}
	cp := *l.auth
	return &cp
}

func (l *fluxLane) currentWS() *websocket.Conn {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.ws
}

func (l *fluxLane) writeJSON(v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return l.writeRaw(raw)
}

func (l *fluxLane) writeRaw(raw []byte) error {
	ws := l.currentWS()
	if ws == nil {
		return errors.New("cups: no socket")
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.ws != ws {
		return errors.New("cups: socket replaced")
	}
	if err := ws.SetWriteDeadline(time.Now().Add(fluxWriteWait)); err != nil {
		return err
	}
	if err := ws.WriteMessage(websocket.TextMessage, raw); err != nil {
		ws.Close()
		return err
	}
	return nil
}

func (l *fluxLane) rememberEcho(b []byte) {
	sum := cupsHash(b)
	l.echoMu.Lock()
	l.echoRing[l.echoPos%len(l.echoRing)] = sum
	l.echoPos++
	l.echoMu.Unlock()
}

func (l *fluxLane) wasEcho(b []byte) bool {
	sum := cupsHash(b)
	l.echoMu.Lock()
	defer l.echoMu.Unlock()
	n := l.echoPos
	if n > len(l.echoRing) {
		n = len(l.echoRing)
	}
	for i := 0; i < n; i++ {
		if l.echoRing[(l.echoPos-1-i)%len(l.echoRing)] == sum {
			return true
		}
	}
	return false
}

func (l *fluxLane) kick() {
	l.connected.Store(false)
	l.mu.Lock()
	ws := l.ws
	l.ws = nil
	l.mu.Unlock()
	if ws != nil {
		ws.Close()
	}
}
