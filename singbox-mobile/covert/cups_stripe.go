package covert

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/cookiejar"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	kcp "github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

// CupsStripe — надёжный поток поверх нескольких комнат cups.online.
// В комнату пишем с паузой 12 мс и не ждём её ответ: она отвечает OK и на
// кадр, который уже выбросила. Повтор и порядок живут здесь, по подтверждению
// пары. Сверху достаточно smux: второй KCP лечил бы кадры, которые сервер
// уже молча выбросил.

type laneJob struct {
	seq     uint32
	raw     []byte
	ackOnly bool
}

type stripeSent struct {
	seq     uint32
	payload []byte
	first   time.Time
	sent    time.Time
	busy    bool
	lane    int
}

type cupsAddr struct{}

func (cupsAddr) Network() string { return "cups" }
func (cupsAddr) String() string  { return "stripe" }

type CupsStripe struct {
	ctx    context.Context
	cancel context.CancelFunc
	lanes  []*cupsLane
	nonce  uint32

	mu        sync.Mutex
	obuf      []byte
	obufSince time.Time
	sent      map[uint32]*stripeSent
	next      uint32
	asm       stripeAssembler
	gate      nonceGate
	failed    error
	rd, wd    time.Time
	rbuf      []byte
	needAck   bool
	lastAck   time.Time
	prog      time.Time
	// peerAt — последний кадр пары. Свой повтор сюда не пишется:
	// иначе тишина пары маскируется нашей же отправкой.
	peerAt     time.Time
	foreignLog time.Time
	rr         int
	rtoNanos   atomic.Int64

	kick     chan struct{}
	readable chan struct{}
	once     sync.Once

	offers atomic.Int64
	rtx    atomic.Int64
	acked  atomic.Int64

	gpace sync.Mutex
	glast time.Time

	selfMu sync.Mutex
	self   map[string]struct{}
}

type cupsLane struct {
	stripe *CupsStripe
	idx    int
	roomID string
	http   *http.Client
	ctx    context.Context

	mu        sync.Mutex
	auth      *cupsAuth
	ws        *websocket.Conn
	connected atomic.Bool
	jobs      atomic.Int32
	cmd       atomic.Int64
	writeMu   sync.Mutex

	pendMu  sync.Mutex
	pending map[int64]chan error

	sendCh chan laneJob
	pongCh chan struct{}

	paceMu   sync.Mutex
	lastSend time.Time

	good      atomic.Int32
	bad       atomic.Int32
	skipUntil atomic.Int64
}

// Сервер комнаты принимает около 8 КБ данных (на проводе ~34 КБ JSON) и
// отвергает сообщение около 44 КБ. 12 КБ полезной нагрузки уже за этим
// порогом. 10 мс между сообщениями одной комнаты даёт 403, 12 мс — нет.
var stripeChunkOverride atomic.Int64

func stripeChunk() int {
	if v := stripeChunkOverride.Load(); v > 0 {
		return int(v)
	}
	if v := os.Getenv("CUPS_CHUNK"); v != "" {
		n, err := strconv.Atoi(v)
		if err == nil && n >= stripeHeader+256 && n <= 32*1024 {
			return n
		}
	}
	return 8192
}

func noteChunkRejected() {
	cur := stripeChunk()
	const next = 8000
	if cur <= next {
		return
	}
	if stripeChunkOverride.CompareAndSwap(0, next) || stripeChunkOverride.Load() > next {
		stripeChunkOverride.Store(next)
		log.Printf("[covert/cups] chunk %d rejected, using %d", cur, next)
	}
}

func cupsChunkTooBig(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "size") || strings.Contains(s, "limit") ||
		strings.Contains(s, "too large") || strings.Contains(s, "3508") ||
		strings.Contains(s, "3509")
}

func stripePayloadMax() int { return stripeChunk() - stripeHeader }

func newCupsHTTP() *http.Client {
	jar, err := cookiejar.New(nil)
	tr := &http.Transport{
		DialContext:           forceIPv4Dial,
		ForceAttemptHTTP2:     true,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
		IdleConnTimeout:       90 * time.Second,
	}
	if err != nil {
		return &http.Client{Timeout: 30 * time.Second, Transport: tr}
	}
	return &http.Client{Jar: jar, Timeout: 30 * time.Second, Transport: tr}
}

func MintCupsRooms(ctx context.Context, n int) (string, error) {
	if n < 1 {
		n = stripeLanes
	}
	var ids []string
	seen := map[string]struct{}{}
	var last error
	for tries := 0; len(ids) < n && tries < n*5; tries++ {
		id, err := cupsMintOne(ctx)
		if err != nil {
			last = err
			if !sleepCtx(ctx, 400*time.Millisecond) {
				return "", ctx.Err()
			}
			continue
		}
		if _, ok := seen[id]; ok {
			last = errors.New("cups: room id repeated")
			if !sleepCtx(ctx, 300*time.Millisecond) {
				return "", ctx.Err()
			}
			continue
		}
		seen[id] = struct{}{}
		ids = append(ids, id)
		if !sleepCtx(ctx, 500*time.Millisecond) {
			return "", ctx.Err()
		}
	}
	if len(ids) < n {
		if last == nil {
			last = errors.New("cups: short room set")
		}
		return "", fmt.Errorf("cups: minted %d/%d: %w", len(ids), n, last)
	}
	return cupsRoomsURL(ids), nil
}

func cupsMintOne(ctx context.Context) (string, error) {
	auth, err := cupsAuthorize(ctx, newCupsHTTP(), cupsBaseURL)
	if err != nil {
		return "", err
	}
	return auth.roomUUID, nil
}

func NewCupsStripe(ctx context.Context, exit bool, publicURL string, timeout time.Duration) (*CupsStripe, error) {
	if !exit {
		useCarrierBypassDNS()
	}
	ids := dedupeIDs(roomIDsFromPublicURL(publicURL))
	if len(ids) == 0 {
		if !exit {
			return nil, errors.New("cups: client needs rooms")
		}
		url, err := MintCupsRooms(ctx, stripeLanes)
		if err != nil {
			return nil, err
		}
		log.Printf("[covert/cups] minted %s", url)
		ids = roomIDsFromPublicURL(url)
	}
	if len(ids) == 0 {
		return nil, errors.New("cups: no rooms")
	}
	life, cancel := context.WithCancel(ctx)
	var nonce uint32
	var nb [4]byte
	if _, err := rand.Read(nb[:]); err != nil {
		cancel()
		return nil, err
	}
	nonce = binary.BigEndian.Uint32(nb[:])
	if nonce == 0 {
		nonce = 1
	}
	s := &CupsStripe{
		ctx:      life,
		cancel:   cancel,
		nonce:    nonce,
		sent:     map[uint32]*stripeSent{},
		self:     map[string]struct{}{},
		kick:     make(chan struct{}, 1),
		readable: make(chan struct{}, 1),
		prog:     time.Now(),
	}
	s.rtoNanos.Store(int64(time.Second))
	for i, id := range ids {
		s.lanes = append(s.lanes, newCupsLane(s, i, id))
	}
	for _, ln := range s.lanes {
		go ln.sessionLoop()
		go ln.pongLoop()
		go ln.sendLoop()
	}
	go s.loop()
	if timeout <= 0 {
		timeout = 45 * time.Second
	}
	dead := time.NewTimer(timeout)
	defer dead.Stop()
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		up := s.upLanes()
		if up == len(s.lanes) {
			log.Printf("[covert/cups] up %d/%d", up, len(s.lanes))
			return s, nil
		}
		select {
		case <-ctx.Done():
			s.Close()
			return nil, ctx.Err()
		case <-life.Done():
			s.Close()
			return nil, errors.New("cups: closed while connecting")
		case <-dead.C:
			if up > 0 {
				log.Printf("[covert/cups] up %d/%d", up, len(s.lanes))
				return s, nil
			}
			s.Close()
			return nil, errors.New("cups: no lane connected")
		case <-tick.C:
		}
	}
}

func newCupsLane(s *CupsStripe, idx int, roomID string) *cupsLane {
	return &cupsLane{
		stripe:  s,
		idx:     idx,
		roomID:  roomID,
		http:    newCupsHTTP(),
		ctx:     s.ctx,
		pending: map[int64]chan error{},
		sendCh:  make(chan laneJob, stripeWindow),
		pongCh:  make(chan struct{}, 4),
	}
}

func (s *CupsStripe) upLanes() int {
	n := 0
	for _, ln := range s.lanes {
		if ln.connected.Load() {
			n++
		}
	}
	return n
}

func (s *CupsStripe) addSelf(id string) {
	if id == "" {
		return
	}
	s.selfMu.Lock()
	s.self[id] = struct{}{}
	s.selfMu.Unlock()
}

func (s *CupsStripe) isSelf(id string) bool {
	s.selfMu.Lock()
	_, ok := s.self[id]
	s.selfMu.Unlock()
	return ok
}

func (s *CupsStripe) Read(p []byte) (int, error) {
	for {
		s.mu.Lock()
		if len(s.rbuf) > 0 {
			n := copy(p, s.rbuf)
			s.rbuf = append([]byte(nil), s.rbuf[n:]...)
			s.mu.Unlock()
			return n, nil
		}
		err := s.failed
		rd := s.rd
		s.mu.Unlock()
		if err != nil {
			return 0, err
		}
		var timer <-chan time.Time
		var stop func()
		if !rd.IsZero() {
			d := time.Until(rd)
			if d <= 0 {
				return 0, os_timeout{}
			}
			t := time.NewTimer(d)
			timer = t.C
			stop = func() { t.Stop() }
		}
		select {
		case <-s.ctx.Done():
			if stop != nil {
				stop()
			}
			return 0, s.ctx.Err()
		case <-s.readable:
			if stop != nil {
				stop()
			}
		case <-timer:
			return 0, os_timeout{}
		}
	}
}

func (s *CupsStripe) Write(p []byte) (int, error) {
	off := 0
	for off < len(p) {
		s.mu.Lock()
		err := s.failed
		wd := s.wd
		full := len(s.obuf) >= s.flightCap()
		if err == nil && !full {
			n := s.flightCap() - len(s.obuf)
			if n > len(p)-off {
				n = len(p) - off
			}
			if len(s.obuf) == 0 {
				s.obufSince = time.Now()
			}
			s.obuf = append(s.obuf, p[off:off+n]...)
			off += n
			s.mu.Unlock()
			s.wake()
			continue
		}
		s.mu.Unlock()
		if err != nil {
			if off > 0 {
				return off, nil
			}
			return 0, err
		}
		if !wd.IsZero() && !time.Now().Before(wd) {
			if off > 0 {
				return off, nil
			}
			return 0, os_timeout{}
		}
		select {
		case <-s.ctx.Done():
			if off > 0 {
				return off, nil
			}
			return 0, s.ctx.Err()
		case <-time.After(20 * time.Millisecond):
		}
	}
	return off, nil
}

func (s *CupsStripe) flightCap() int {
	// Окно одно: шлёт одна комната. Множитель на число комнат раздувал
	// повтор, когда кадры уходили во все восемь сразу.
	return stripeFlight * stripePayloadMax()
}

// flightCountCap режет число кадров, а не только байты. Короткие кадры
// рукопожатия иначе проходят мимо байтового окна: 52 штуки при RTO 3 с.
func (s *CupsStripe) flightCountCap() int {
	if s.acked.Load() < 4 {
		return stripeFlightProbe
	}
	rto := time.Duration(s.rtoNanos.Load())
	if rto >= 3*time.Second {
		return stripeFlightProbe
	}
	if rto >= 2*time.Second {
		return 12
	}
	return stripeFlight
}

func (s *CupsStripe) Err() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.failed
}

func (s *CupsStripe) Close() error {
	s.once.Do(func() {
		s.mu.Lock()
		if s.failed == nil {
			s.failed = net.ErrClosed
		}
		inflight := len(s.sent)
		s.mu.Unlock()
		log.Printf("[covert/cups] stats offers=%d rtx=%d acked=%d inflight=%d rto=%s",
			s.offers.Load(), s.rtx.Load(), s.acked.Load(), inflight, time.Duration(s.rtoNanos.Load()))
		s.cancel()
		s.wake()
	})
	return nil
}

func (s *CupsStripe) LocalAddr() net.Addr  { return cupsAddr{} }
func (s *CupsStripe) RemoteAddr() net.Addr { return cupsAddr{} }

func (s *CupsStripe) SetDeadline(t time.Time) error {
	_ = s.SetReadDeadline(t)
	return s.SetWriteDeadline(t)
}

func (s *CupsStripe) SetReadDeadline(t time.Time) error {
	s.mu.Lock()
	s.rd = t
	s.mu.Unlock()
	s.wake()
	return nil
}

func (s *CupsStripe) SetWriteDeadline(t time.Time) error {
	s.mu.Lock()
	s.wd = t
	s.mu.Unlock()
	return nil
}

func (s *CupsStripe) wake() {
	select {
	case s.kick <- struct{}{}:
	default:
	}
	select {
	case s.readable <- struct{}{}:
	default:
	}
}

func (s *CupsStripe) fail(err error) {
	s.mu.Lock()
	s.failLocked(err)
	s.mu.Unlock()
	s.wake()
}

func (s *CupsStripe) failLocked(err error) {
	if s.failed != nil {
		return
	}
	s.failed = err
	s.cancel()
}

func (s *CupsStripe) loop() {
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-s.kick:
		case <-tick.C:
		}
		s.pump()
	}
}

func (s *CupsStripe) pump() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failed != nil {
		return
	}
	now := time.Now()
	rto := time.Duration(s.rtoNanos.Load())
	if rto < time.Second {
		rto = time.Second
	}
	// Пока пару не слышно, smux уже шлёт пустые keepalive. Их некому подтвердить,
	// и обрыв здесь пересоздавал сессию каждые ~35 с, не дождавшись клиента.
	// Обрыв по возрасту одного кадра (20 с, потом 8 с) убивал живой Wi‑Fi и
	// не давал мобильному дописать рукопожатие. Рвём только когда пара молчит
	// дольше stripePeerDead, а не когда один кадр ещё в полёте.
	if s.gate.have && !s.peerAt.IsZero() && now.Sub(s.peerAt) > stripePeerDead &&
		(len(s.obuf) > 0 || len(s.sent) > 0) {
		s.failLocked(errors.New("cups: peer stall"))
		s.wake()
		return
	}
	// Повторяем только самый старый кадр и только когда вышел его срок.
	// Повтор всей пачки сразу удваивал поток в одну комнату: подтверждения
	// опаздывали, RTO уезжал к 3 с, сокет закрывался без pong.
	var oldest *stripeSent
	for _, f := range s.sent {
		if oldest == nil || f.seq < oldest.seq {
			oldest = f
		}
	}
	if oldest != nil && !oldest.busy {
		due := oldest.sent.IsZero() || now.Sub(oldest.sent) >= rto
		if due {
			s.offerLocked(oldest, now)
		}
	}
	for len(s.obuf) > 0 {
		if s.flightBytes() >= s.flightCap() || len(s.sent) >= s.flightCountCap() {
			break
		}
		n := len(s.obuf)
		if n > stripePayloadMax() {
			n = stripePayloadMax()
		}
		if n < stripePayloadMax() && !s.obufSince.IsZero() && now.Sub(s.obufSince) < stripeCoalesce {
			break
		}
		payload := append([]byte(nil), s.obuf[:n]...)
		f := &stripeSent{seq: s.next, payload: payload, lane: -1}
		if !s.offerLocked(f, now) {
			break
		}
		s.next++
		s.sent[f.seq] = f
		s.obuf = s.obuf[n:]
	}
	if s.needAck && (s.lastAck.IsZero() || now.Sub(s.lastAck) > 100*time.Millisecond) {
		if s.offerAckLocked(now) {
			s.needAck = false
			s.lastAck = now
		}
	}
}

func (s *CupsStripe) flightBytes() int {
	n := 0
	for _, f := range s.sent {
		n += len(f.payload)
	}
	return n
}

func (s *CupsStripe) offerLocked(f *stripeSent, now time.Time) bool {
	raw := encodeStripe(stripeFrame{
		flags:    stripeFlagData | stripeFlagAck,
		nonce:    s.nonce,
		seq:      f.seq,
		ack:      s.asm.expect,
		ackNonce: s.gate.cur,
		payload:  f.payload,
	})
	lane, ok := s.offerRaw(laneJob{seq: f.seq, raw: raw})
	if !ok {
		return false
	}
	if !f.first.IsZero() {
		s.rtx.Add(1)
	}
	f.lane = lane
	f.busy = true
	f.sent = now
	if f.first.IsZero() {
		f.first = now
		s.prog = now
		s.offers.Add(1)
	}
	s.needAck = false
	s.lastAck = now
	return true
}

func (s *CupsStripe) offerAckLocked(now time.Time) bool {
	if !s.gate.have {
		return false
	}
	raw := encodeStripe(stripeFrame{
		flags:    stripeFlagAck,
		nonce:    s.nonce,
		ack:      s.asm.expect,
		ackNonce: s.gate.cur,
	})
	if _, ok := s.offerRaw(laneJob{raw: raw, ackOnly: true}); !ok {
		return false
	}
	s.lastAck = now
	return true
}

func (s *CupsStripe) offerRaw(job laneJob) (int, bool) {
	// Кадр уходит в следующую живую комнату, у которой есть свободный вызов.
	// Одна комната упирается в свой ответ RPC. Восемь комнат с окном 4
	// на короткой пачке приняли около 209 КБ/с.
	n := len(s.lanes)
	if n == 0 {
		return -1, false
	}
	for i := 0; i < n; i++ {
		idx := (s.rr + i) % n
		ln := s.lanes[idx]
		if !ln.connected.Load() || ln.jobs.Load() >= stripeWindow {
			continue
		}
		ln.jobs.Add(1)
		select {
		case ln.sendCh <- job:
			s.rr = (idx + 1) % n
			return idx, true
		default:
			ln.jobs.Add(-1)
		}
	}
	return -1, false
}

func (s *CupsStripe) offerRawPass(job laneJob, honorSkip bool) (int, bool) {
	n := len(s.lanes)
	if n == 0 {
		return -1, false
	}
	now := time.Now().UnixNano()
	for i := 0; i < n; i++ {
		idx := (s.rr + i) % n
		ln := s.lanes[idx]
		if !ln.connected.Load() || ln.jobs.Load() >= stripeWindow {
			continue
		}
		if honorSkip && now < ln.skipUntil.Load() {
			continue
		}
		ln.jobs.Add(1)
		select {
		case ln.sendCh <- job:
			s.rr = (idx + 1) % n
			return idx, true
		default:
			ln.jobs.Add(-1)
		}
	}
	return -1, false
}

// paceGlobal держит один кадр данных на все комнаты раз в stripeGlobalPace.
// Иначе восемь комнат выстреливают пачкой, сервер кадры выбрасывает.
func (s *CupsStripe) paceGlobal() error {
	s.gpace.Lock()
	defer s.gpace.Unlock()
	d := stripeGlobalPace - time.Since(s.glast)
	if d > 0 {
		t := time.NewTimer(d)
		select {
		case <-s.ctx.Done():
			t.Stop()
			return s.ctx.Err()
		case <-t.C:
		}
	}
	s.glast = time.Now()
	return nil
}

// noteLaneLoss убирает комнату, которая не довозит кадры. Иначе половина
// отправок уходит в пустые комнаты, повтор держит окно, и скорость падает
// в несколько раз. Успешные кадры этой же комнаты голос не отнимают.
func (s *CupsStripe) noteLaneLoss(idx int) {
	if idx < 0 || idx >= len(s.lanes) {
		return
	}
	ln := s.lanes[idx]
	b := ln.bad.Add(1)
	g := ln.good.Load()
	if g > 4 {
		ln.good.Store(4)
		g = 4
	}
	if b >= 3 && b > g {
		ln.skipUntil.Store(time.Now().Add(20 * time.Second).UnixNano())
		ln.bad.Store(0)
		ln.good.Store(0)
		log.Printf("[covert/cups] lane %d skipped", idx)
	}
}

func (s *CupsStripe) rpcFinished(seq uint32, err error) {
	s.mu.Lock()
	f := s.sent[seq]
	if f != nil {
		f.busy = false
		if err != nil {
			f.sent = time.Time{}
		} else {
			// Повтор считаем от приёма комнатой, не от постановки в очередь.
			// RPC на 1–2 с иначе сам выглядел потерей, и кадр уходил второй раз
			// ещё до подтверждения телефона.
			f.sent = time.Now()
		}
	}
	s.mu.Unlock()
	s.wake()
}

func (s *CupsStripe) onFrame(f stripeFrame) {
	if f.nonce == s.nonce || f.nonce == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failed != nil {
		return
	}
	now := time.Now()
	var silence time.Duration
	if s.gate.have && !s.gate.last.IsZero() {
		silence = now.Sub(s.gate.last)
	}
	ok, reset := s.gate.accept(f.nonce, now, s.quietFor(now))
	if !ok {
		// Чужой nonce при живой паре — не повод рвать сокет. Иначе повтор
		// телефона после дырки на LTE сносит сессию, которая ещё несёт трафик.
		if silence > 5*time.Second && (s.foreignLog.IsZero() || now.Sub(s.foreignLog) > 10*time.Second) {
			s.foreignLog = now
			log.Printf("[covert/cups] foreign nonce ignored silence=%s", silence.Round(time.Millisecond))
		}
		return
	}
	if reset {
		log.Printf("[covert/cups] peer replaced silence=%s", silence.Round(time.Millisecond))
		s.failLocked(errors.New("cups: peer replaced"))
		s.wake()
		return
	}
	s.peerAt = now
	// ackNonce 0 — клиент ещё не видел наш nonce, но номер подтверждения верный.
	// Иначе первые кадры висят, и телефон на сборке 50 рвёт сессию через 20 с.
	if f.flags&stripeFlagAck != 0 && (f.ackNonce == s.nonce || f.ackNonce == 0) {
		if s.applyAckLocked(f.ack, now) {
			s.prog = now
		}
	}
	if f.flags&stripeFlagData != 0 {
		before := s.asm.expect
		if f.seq < before {
			// Повтор уже принятого: прошлый ACK потерялся, шлём его снова.
			s.needAck = true
		} else {
			s.asm.push(f.seq, f.payload)
			if len(s.asm.out) > 0 {
				s.rbuf = append(s.rbuf, s.asm.out...)
				s.asm.out = nil
				s.needAck = true
				s.prog = now
				select {
				case s.readable <- struct{}{}:
				default:
				}
			} else if f.seq >= s.asm.expect {
				s.needAck = true
			}
		}
	}
	s.wake()
}

// quietFor — чужой nonce занимает комнаты только после полной тишины.
// Старый неподтверждённый кадр окно не сокращает: 27.10.2026 в 16:54 UTC
// это отдало живую пару (915 подтверждений) следующему nonce через 8 с.
func (s *CupsStripe) quietFor(time.Time) time.Duration {
	return stripeQuiet
}

func (s *CupsStripe) applyAckLocked(cum uint32, now time.Time) bool {
	var sample time.Duration
	freed := false
	var nAck int64
	for seq, f := range s.sent {
		if seq < cum {
			if d := now.Sub(f.first); d > 0 && (sample == 0 || d < sample) {
				sample = d
			}
			delete(s.sent, seq)
			freed = true
			nAck++
			if f.lane >= 0 && f.lane < len(s.lanes) {
				s.lanes[f.lane].good.Add(1)
			}
		}
	}
	if nAck > 0 {
		s.acked.Add(nAck)
	}
	if sample > 0 {
		rto := sample * 2
		if rto < time.Second {
			rto = time.Second
		}
		if rto > 3*time.Second {
			rto = 3 * time.Second
		}
		s.rtoNanos.Store(int64(rto))
	}
	return freed
}

func (l *cupsLane) sessionLoop() {
	backoff := 400 * time.Millisecond
	for {
		if l.ctx.Err() != nil {
			return
		}
		auth, err := cupsAuthorize(l.ctx, l.http, cupsJoinURL(l.roomID))
		if err != nil {
			log.Printf("[covert/cups] lane %d join: %v", l.idx, err)
			if !l.sleep(backoff) {
				return
			}
			if backoff < 8*time.Second {
				backoff *= 2
			}
			continue
		}
		if auth.roomUUID != l.roomID {
			log.Printf("[covert/cups] lane %d room mismatch", l.idx)
			if !l.sleep(backoff) {
				return
			}
			continue
		}
		l.stripe.addSelf(auth.userUUID)
		backoff = 400 * time.Millisecond
		if err := l.pump(auth); err != nil && l.ctx.Err() == nil && !errors.Is(err, net.ErrClosed) {
			log.Printf("[covert/cups] lane %d: %v", l.idx, err)
		}
		l.drop()
		if !l.sleep(300 * time.Millisecond) {
			return
		}
	}
}

func (l *cupsLane) pump(a *cupsAuth) error {
	wsURL := cupsSocketURL(a.connURL)
	header := http.Header{}
	header.Set("Origin", originOf(a.connURL))
	header.Set("User-Agent", cupsUserAgent)
	if ck := cookieHeader(l.http.Jar, a.connURL); ck != "" {
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
		return err
	}
	defer ws.Close()
	ws.SetReadLimit(8 << 20)
	l.cmd.Store(2)
	if err := l.writeJSON(ws, map[string]any{
		"id":      1,
		"connect": map[string]any{"token": a.connToken, "name": "js"},
	}); err != nil {
		return err
	}
	if err := l.waitID(ws, 1, 15*time.Second); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	if err := l.writeJSON(ws, map[string]any{
		"id":        2,
		"subscribe": map[string]any{"channel": a.channel, "token": a.subToken},
	}); err != nil {
		return err
	}
	if err := l.waitID(ws, 2, 15*time.Second); err != nil {
		return fmt.Errorf("subscribe: %w", err)
	}
	l.mu.Lock()
	l.auth = a
	l.ws = ws
	l.mu.Unlock()
	l.connected.Store(true)
	l.stripe.wake()
	defer func() {
		l.connected.Store(false)
		l.drop()
	}()
	room := a.roomUUID
	if len(room) > 8 {
		room = room[:8]
	}
	log.Printf("[covert/cups] lane %d room %s", l.idx, room)
	for {
		lines, err := l.readLines(ws, time.Now().Add(90*time.Second))
		if err != nil {
			return err
		}
		for _, line := range lines {
			if err := l.consume(ws, line); err != nil {
				return err
			}
		}
	}
}

func (l *cupsLane) sendLoop() {
	for {
		select {
		case <-l.ctx.Done():
			return
		case job := <-l.sendCh:
			go func(job laneJob) {
				defer l.jobs.Add(-1)
				err := l.doRPC(job.raw)
				if err != nil {
					n := rpcErrLog.Add(1)
					if n <= 6 || n%100 == 0 {
						log.Printf("[covert/cups] rpc err n=%d bytes=%d %v", n, len(job.raw), err)
					}
				}
				if err != nil && l.ctx.Err() == nil && cupsLaneDead(err) {
					l.kick()
				}
				if job.ackOnly {
					l.stripe.wake()
					return
				}
				l.stripe.rpcFinished(job.seq, err)
			}(job)
		}
	}
}

// cupsLaneDead — сокет умер. Ответ комнаты с кодом ошибки и долгий RPC
// сокет не рвут: иначе одна медленная пачка закрывает все восемь комнат.
func cupsLaneDead(err error) bool {
	if err == nil || errors.Is(err, errRPCTimeout) {
		return false
	}
	msg := err.Error()
	if msg != "" && msg[0] >= '0' && msg[0] <= '9' {
		return false
	}
	return true
}

var errRPCTimeout = errors.New("cups: rpc timeout")

var rpcErrLog atomic.Int64

func (l *cupsLane) doRPC(raw []byte) error {
	ws := l.currentWS()
	a := l.snapshot()
	if ws == nil || a == nil {
		return errors.New("cups: lane down")
	}
	l.takePong()
	if err := l.paceSend(); err != nil {
		return err
	}
	wire := make([]byte, 2+len(raw))
	binary.BigEndian.PutUint16(wire[:2], uint16(len(raw)))
	copy(wire[2:], raw)
	id := l.cmd.Add(1)
	msg := map[string]any{
		"id": id,
		"rpc": map[string]any{
			"method": "shared_editor_change_cursors",
			"data": map[string]any{
				"cursors": cupsCursorPairs(wire),
				"ranges":  []any{},
				"room":    a.roomUUID,
				"user":    a.userUUID,
			},
		},
	}
	ch := make(chan error, 1)
	l.pendMu.Lock()
	l.pending[id] = ch
	l.pendMu.Unlock()
	if err := l.writeJSON(ws, msg); err != nil {
		l.clearPend(id)
		return err
	}
	// Ответ комнаты — доказательство, что она взяла кадр. Без ожидания
	// 8 КБ копятся в сокете, ping остаётся сзади и комната закрывается 3012.
	// Мьютекс записи на время ожидания не держим.
	timer := time.NewTimer(4 * time.Second)
	defer timer.Stop()
	select {
	case err := <-ch:
		return err
	case <-timer.C:
		l.clearPend(id)
		return errRPCTimeout
	case <-l.ctx.Done():
		l.clearPend(id)
		return l.ctx.Err()
	}
}

func (l *cupsLane) clearPend(id int64) {
	l.pendMu.Lock()
	delete(l.pending, id)
	l.pendMu.Unlock()
}

func (l *cupsLane) consume(ws *websocket.Conn, line []byte) error {
	if string(line) == "{}" {
		// Ping комнаты отвечаем здесь же. Очередь с отказом, если запись
		// занята, 27.10 роняла единственную рабочую комнату: 3012 no pong.
		return l.writeRaw(ws, []byte("{}"))
	}
	if l.resolve(line) {
		return nil
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
	if sender == "" || l.stripe.isSelf(sender) {
		return nil
	}
	rawCursors, _ := payload["cursors"].([]any)
	chunk, ok := cupsChunkFromCursors(rawCursors)
	if !ok {
		return nil
	}
	f, ok := decodeStripe(chunk)
	if !ok {
		return nil
	}
	l.stripe.onFrame(f)
	return nil
}

func (l *cupsLane) resolve(line []byte) bool {
	var reply struct {
		ID    int64 `json:"id"`
		Error *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal(line, &reply) != nil || reply.ID == 0 {
		return false
	}
	l.pendMu.Lock()
	ch := l.pending[reply.ID]
	if ch != nil {
		delete(l.pending, reply.ID)
	}
	l.pendMu.Unlock()
	if reply.Error != nil && cupsChunkTooBig(fmt.Errorf("%d %s", reply.Error.Code, reply.Error.Message)) {
		noteChunkRejected()
	}
	if ch == nil {
		return false
	}
	if reply.Error != nil {
		ch <- fmt.Errorf("%d %s", reply.Error.Code, reply.Error.Message)
	} else {
		ch <- nil
	}
	return true
}

func (l *cupsLane) waitID(ws *websocket.Conn, id int, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		lines, err := l.readLines(ws, deadline)
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
			if err := l.consume(ws, line); err != nil {
				return err
			}
		}
		if matched {
			return nil
		}
	}
}

func (l *cupsLane) readLines(ws *websocket.Conn, deadline time.Time) ([][]byte, error) {
	if err := ws.SetReadDeadline(deadline); err != nil {
		return nil, err
	}
	_, raw, err := ws.ReadMessage()
	if err != nil {
		return nil, err
	}
	return splitWS(raw), nil
}

func (l *cupsLane) pongLoop() {
	for {
		select {
		case <-l.ctx.Done():
			return
		case <-l.pongCh:
			l.writePongNow()
		}
	}
}

// takePong забирает ответ на ping, если он уже лежит в очереди.
// Очередь отдельная от кадров, поэтому ping не ждёт окно данных.
func takePong(ch <-chan struct{}) bool {
	select {
	case <-ch:
		return true
	default:
		return false
	}
}

func (l *cupsLane) takePong() {
	if takePong(l.pongCh) {
		l.writePongNow()
	}
}

// paceSend держит 12 мс между сообщениями одной комнаты. Быстрее сервер
// закрывает сокет кодом 403, а ответ RPC при этом всё равно может быть пустым.
func (l *cupsLane) paceSend() error {
	l.paceMu.Lock()
	d := cupsPace - time.Since(l.lastSend)
	if d > 0 {
		l.paceMu.Unlock()
		if !l.sleep(d) {
			return l.ctx.Err()
		}
		l.paceMu.Lock()
		d = cupsPace - time.Since(l.lastSend)
		if d > 0 {
			l.paceMu.Unlock()
			if !l.sleep(d) {
				return l.ctx.Err()
			}
			l.paceMu.Lock()
		}
	}
	l.lastSend = time.Now()
	l.paceMu.Unlock()
	return nil
}

func (l *cupsLane) writePongNow() {
	ws := l.currentWS()
	if ws == nil {
		return
	}
	// Ждём текущую запись и отвечаем. Отказ от pong закрывает комнату 3012.
	_ = l.writeRaw(ws, []byte("{}"))
}

func (l *cupsLane) currentWS() *websocket.Conn {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.ws
}

func (l *cupsLane) snapshot() *cupsAuth {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.auth == nil {
		return nil
	}
	cp := *l.auth
	return &cp
}

func (l *cupsLane) drop() {
	l.connected.Store(false)
	l.mu.Lock()
	l.auth = nil
	ws := l.ws
	l.ws = nil
	l.mu.Unlock()
	if ws != nil {
		ws.Close()
	}
}

func (l *cupsLane) kick() {
	l.drop()
}

func (l *cupsLane) writeJSON(ws *websocket.Conn, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return l.writeRaw(ws, raw)
}

func (l *cupsLane) writeRaw(ws *websocket.Conn, raw []byte) error {
	l.writeMu.Lock()
	defer l.writeMu.Unlock()
	return l.writeRawLocked(ws, raw)
}

func (l *cupsLane) writeRawLocked(ws *websocket.Conn, raw []byte) error {
	// Дольше двух секунд запись уже не отдаёт сокет под pong.
	// Просрочка делает сокет непригодным: закрываем, чтобы чтение ушло на переподключение.
	if err := ws.SetWriteDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return err
	}
	if err := ws.WriteMessage(websocket.TextMessage, raw); err != nil {
		ws.Close()
		return err
	}
	return nil
}

func (l *cupsLane) sleep(d time.Duration) bool {
	return sleepCtx(l.ctx, d)
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return true
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

func runExitCups(ctx context.Context, publicURL string, logf func(string, ...any)) error {
	tune := tuneFor("cups")
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		pc, err := NewCupsFlux(ctx, true, publicURL, 45*time.Second)
		if err != nil {
			logf("[covert-exit] cups: %v", err)
			if !sleepCtx(ctx, 2*time.Second) {
				return ctx.Err()
			}
			continue
		}
		logf("[covert-exit] cups rooms %d", pc.upLanes())
		listener, err := kcp.ServeConn(nil, 0, 0, pc)
		if err != nil {
			pc.Close()
			return err
		}
		done := make(chan struct{})
		go func() {
			select {
			case <-ctx.Done():
				_ = listener.Close()
				_ = pc.Close()
			case <-done:
			}
		}()
		for {
			kcpSess, err := listener.AcceptKCP()
			if err != nil {
				close(done)
				_ = listener.Close()
				_ = pc.Close()
				logf("[covert-exit] cups kcp: %v", err)
				if ctx.Err() != nil {
					return ctx.Err()
				}
				break
			}
			kcpSess.SetWindowSize(128, 128)
			kcpSess.SetMtu(mtuForCarrier("cups"))
			kcpSess.SetStreamMode(true)
			tuneKCPFor(kcpSess, "cups")
			go serveKCP(kcpSess, logf, tune)
		}
		if !sleepCtx(ctx, time.Second) {
			return ctx.Err()
		}
	}
}

// DialCups открывает клиентский KCP+smux поверх комнат. Подтверждения внутри
// комнаты нет: потерянный батч выбрасывается, повтор делает KCP.
func DialCups(ctx context.Context, publicURL string) (*Client, error) {
	pc, err := NewCupsFlux(ctx, false, publicURL, 45*time.Second)
	if err != nil {
		return nil, err
	}
	kcpConn, err := kcp.NewConn2(kcpDialAddr, nil, 0, 0, pc)
	if err != nil {
		pc.Close()
		return nil, err
	}
	kcpConn.SetWindowSize(128, 128)
	kcpConn.SetMtu(mtuForCarrier("cups"))
	kcpConn.SetStreamMode(true)
	tuneKCPFor(kcpConn, "cups")
	sess, err := smux.Client(kcpConn, smuxConfig())
	if err != nil {
		kcpConn.Close()
		pc.Close()
		return nil, err
	}
	return &Client{sess: sess, closer: pc}, nil
}
