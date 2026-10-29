package covert

// CupsPacketConn — net.PacketConn поверх общей комнаты cups.online.
// Сайт принимает позиции курсора (целые row/column) и рассылает их остальным
// участникам комнаты. Сюда кладётся один кадр KCP: внешняя длина, затем
// внутренние пакеты с длиной uint16. Оба конца — наш клиент и наш выход,
// Яндекс-документ этот носитель не открывает.
//
// Темп 12 мс между сообщениями курсора.
// Замер 24.10.2026 с датацентра: один MTU за интервал, около 95 КБ/с.
// Интервал 4 мс сайт не закрывал кодом 403, но кадры терялись и передача
// становилась медленнее. Быстрее этого темпа канал не разгоняется.
// Одно сообщение несёт до 8000 байт полезной нагрузки, поэтому KCP на этом
// носителе может держать MTU 1200. На Яндексе MTU остаётся 280.

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"hash/fnv"
	"html"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	cupsBaseURL   = "https://interview.cups.online/live-coding/"
	cupsPace      = 12 * time.Millisecond
	cupsKeepAlive = 20 * time.Second
	cupsMaxChunk  = 8000
	cupsDigits    = 6
	cupsUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

var cupsUUID = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// mtuForCarrier — размер сегмента KCP. Cups вмещает кадр OpenFlux (около 8 КБ),
// Яндекс — нет, там потолок 280, иначе cursor ловит 4004.
func mtuForCarrier(kind string) int {
	if strings.EqualFold(strings.TrimSpace(kind), "cups") {
		return 1200
	}
	return 280
}

// resolveKind выбирает носитель: явное поле, затем COVERT_CARRIER, затем адрес комнаты.
func resolveKind(kind, publicURL string) string {
	k := strings.ToLower(strings.TrimSpace(kind))
	if k == "" {
		k = strings.ToLower(strings.TrimSpace(os.Getenv("COVERT_CARRIER")))
	}
	if k == "" && strings.Contains(strings.ToLower(publicURL), "cups.online") {
		k = "cups"
	}
	return k
}

type cupsAuth struct {
	roomUUID  string
	userUUID  string
	channel   string
	connURL   string
	connToken string
	subToken  string
}

// CupsPacketConn гоняет датаграммы KCP через одну комнату.
type CupsPacketConn struct {
	exit      bool
	publicURL string

	http *http.Client
	ctx  context.Context
	stop context.CancelFunc

	mu      sync.Mutex
	writeMu sync.Mutex
	auth    *cupsAuth
	ws      *websocket.Conn

	roomID string

	tx     chan []byte
	rx     chan []byte
	pongCh chan struct{}
	closed chan struct{}
	once   sync.Once

	rdDeadline atomic.Pointer[time.Time]
	connected  atomic.Bool
	fatal      atomic.Pointer[error]
	cmd        atomic.Int64

	recvMu sync.Mutex
	recv   []byte

	// Свои курсоры сайт присылает обратно. Если user_uuid не совпал,
	// повторный кадр всё равно не должен попасть в KCP.
	echoMu   sync.Mutex
	echoRing [64]uint64
	echoPos  int
}

// NewCupsPacketConn входит в комнату и ждёт подписки на канал.
// publicURL — ссылка ?room=, uuid, упакованный список, либо "create" на выходе.
// timeout<=0 — ждать, пока контекст не отменят.
func NewCupsPacketConn(ctx context.Context, exit bool, publicURL string, timeout time.Duration) (*CupsPacketConn, error) {
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, err
	}
	life, stop := context.WithCancel(context.Background())
	c := &CupsPacketConn{
		exit:      exit,
		publicURL: strings.TrimSpace(publicURL),
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
		ctx:    life,
		stop:   stop,
		tx:     make(chan []byte, txQueue),
		rx:     make(chan []byte, rxQueue),
		pongCh: make(chan struct{}, 1),
		closed: make(chan struct{}),
	}
	if !exit && roomIDFromPublicURL(c.publicURL) == "" {
		c.Close()
		return nil, errors.New("cups: client needs a room url")
	}
	go c.sessionLoop()
	go c.writeLoop()

	var tch <-chan time.Time
	if timeout > 0 {
		t := time.NewTimer(timeout)
		defer t.Stop()
		tch = t.C
	}
	for {
		if c.connected.Load() {
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
			if p := c.fatal.Load(); p != nil && *p != nil {
				return nil, *p
			}
			return nil, errors.New("cups: closed")
		case <-time.After(150 * time.Millisecond):
		}
	}
}

func (c *CupsPacketConn) fail(err error) {
	c.fatal.Store(&err)
	c.Close()
}

func (c *CupsPacketConn) sessionLoop() {
	backoff := 400 * time.Millisecond
	for {
		select {
		case <-c.closed:
			return
		default:
		}
		page := c.pageURL()
		if page == "" {
			c.fail(errors.New("cups: room url required"))
			return
		}
		auth, err := c.authorize(page)
		if err != nil {
			log.Printf("[covert/cups] join: %v", err)
			if !c.sleep(backoff) {
				return
			}
			if backoff < 8*time.Second {
				backoff *= 2
			}
			continue
		}
		if c.roomID == "" {
			c.roomID = auth.roomUUID
			if c.exit {
				log.Printf("[covert/cups] room-url %s", cupsJoinURL(auth.roomUUID))
			}
		} else if auth.roomUUID != c.roomID {
			log.Printf("[covert/cups] room gone, page returned another")
			if !c.sleep(backoff) {
				return
			}
			continue
		}
		backoff = 400 * time.Millisecond
		if err := c.pump(auth); err != nil && !errors.Is(err, net.ErrClosed) {
			log.Printf("[covert/cups] session: %v", err)
		}
		c.dropAuth()
		if !c.sleep(300 * time.Millisecond) {
			return
		}
	}
}

func (c *CupsPacketConn) pageURL() string {
	if c.roomID != "" {
		return cupsJoinURL(c.roomID)
	}
	if id := roomIDFromPublicURL(c.publicURL); id != "" {
		c.roomID = id
		return cupsJoinURL(id)
	}
	if c.exit && (c.publicURL == "" || strings.EqualFold(c.publicURL, "create")) {
		return cupsBaseURL
	}
	return ""
}

func (c *CupsPacketConn) authorize(page string) (*cupsAuth, error) {
	return cupsAuthorize(c.ctx, c.http, page)
}

func cupsAuthorize(ctx context.Context, client *http.Client, page string) (*cupsAuth, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, page, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", cupsUserAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml")
	req.Header.Set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("room page %d", resp.StatusCode)
	}
	doc := string(body)
	room := cupsDataUUID(doc, "data-room")
	user := cupsDataUUID(doc, "data-user")
	connURL := absURL(page, cupsMeta(doc, "centrifuge-connection-url"))
	connToken := cupsMeta(doc, "centrifuge-connection-token")
	subURL := absURL(page, cupsMeta(doc, "centrifuge-subscription-token-url"))
	if room == "" || user == "" {
		return nil, errors.New("room has no uuid")
	}
	if connURL == "" || connToken == "" || subURL == "" {
		return nil, errors.New("centrifuge fields missing")
	}
	csrf := cookieValue(client.Jar, page, "csrftoken")
	if csrf == "" {
		return nil, errors.New("csrftoken missing")
	}
	channel := "$shared_editor:room-" + room
	payload, _ := json.Marshal(map[string]string{"channel": channel})
	req2, err := http.NewRequestWithContext(ctx, http.MethodPost, subURL, strings.NewReader(string(payload)))
	if err != nil {
		return nil, err
	}
	origin := originOf(page)
	req2.Header.Set("User-Agent", cupsUserAgent)
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("X-CSRFToken", csrf)
	req2.Header.Set("Origin", origin)
	req2.Header.Set("Referer", page)
	resp2, err := client.Do(req2)
	if err != nil {
		return nil, err
	}
	body2, _ := io.ReadAll(io.LimitReader(resp2.Body, 1<<20))
	resp2.Body.Close()
	if resp2.StatusCode != 200 {
		return nil, fmt.Errorf("subscription token %d", resp2.StatusCode)
	}
	var sub struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(body2, &sub); err != nil {
		return nil, err
	}
	if sub.Token == "" {
		return nil, errors.New("empty subscription token")
	}
	return &cupsAuth{
		roomUUID:  room,
		userUUID:  user,
		channel:   channel,
		connURL:   connURL,
		connToken: connToken,
		subToken:  sub.Token,
	}, nil
}

func (c *CupsPacketConn) pump(a *cupsAuth) error {
	wsURL := cupsSocketURL(a.connURL)
	header := http.Header{}
	header.Set("Origin", originOf(a.connURL))
	header.Set("User-Agent", cupsUserAgent)
	if ck := cookieHeader(c.http.Jar, a.connURL); ck != "" {
		header.Set("Cookie", ck)
	}
	dialer := websocket.Dialer{
		NetDialContext:   forceIPv4Dial,
		HandshakeTimeout: 15 * time.Second,
		ReadBufferSize:   64 << 10,
		WriteBufferSize:  64 << 10,
	}
	ws, _, err := dialer.DialContext(c.ctx, wsURL, header)
	if err != nil {
		return fmt.Errorf("websocket %s: %w", hostOf(wsURL), err)
	}
	defer ws.Close()
	ws.SetReadLimit(8 << 20)

	c.recvMu.Lock()
	c.recv = nil
	c.recvMu.Unlock()
	c.cmd.Store(2)

	if err := c.writeJSON(ws, map[string]any{
		"id":      1,
		"connect": map[string]any{"token": a.connToken, "name": "js"},
	}); err != nil {
		return err
	}
	if err := c.waitID(ws, 1, 15*time.Second); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	if err := c.writeJSON(ws, map[string]any{
		"id":        2,
		"subscribe": map[string]any{"channel": a.channel, "token": a.subToken},
	}); err != nil {
		return err
	}
	if err := c.waitID(ws, 2, 15*time.Second); err != nil {
		return fmt.Errorf("subscribe: %w", err)
	}

	c.mu.Lock()
	c.auth = a
	c.ws = ws
	c.mu.Unlock()
	c.connected.Store(true)
	defer func() {
		c.dropAuth()
		c.kick()
	}()
	log.Printf("[covert/cups] subscribed room %s exit=%v", a.roomUUID[:8], c.exit)

	for {
		lines, err := c.readLines(ws, time.Now().Add(90*time.Second))
		if err != nil {
			return err
		}
		for _, line := range lines {
			if err := c.consume(ws, line); err != nil {
				return err
			}
		}
	}
}

func (c *CupsPacketConn) dropAuth() {
	c.connected.Store(false)
	c.mu.Lock()
	c.auth = nil
	c.mu.Unlock()
}

func (c *CupsPacketConn) currentWS() *websocket.Conn {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ws
}

func (c *CupsPacketConn) waitID(ws *websocket.Conn, id int, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		lines, err := c.readLines(ws, deadline)
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
			if err := c.consume(ws, line); err != nil {
				return err
			}
		}
		if matched {
			return nil
		}
	}
}

func (c *CupsPacketConn) readLines(ws *websocket.Conn, deadline time.Time) ([][]byte, error) {
	if err := ws.SetReadDeadline(deadline); err != nil {
		return nil, err
	}
	_, raw, err := ws.ReadMessage()
	if err != nil {
		return nil, err
	}
	return splitWS(raw), nil
}

func (c *CupsPacketConn) consume(ws *websocket.Conn, line []byte) error {
	if string(line) == "{}" {
		// Пока в комнату лиются кадры, прямой ответ на ping встаёт за записью
		// и сайт закрывает сокет 3012. Пишем pong из цикла записи, впереди данных.
		if c.connected.Load() {
			select {
			case c.pongCh <- struct{}{}:
			default:
			}
			return nil
		}
		return c.writeRaw(ws, []byte("{}"))
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
	c.mu.Lock()
	self := ""
	if c.auth != nil {
		self = c.auth.userUUID
	}
	c.mu.Unlock()
	if sender == "" || sender == self {
		return nil
	}
	rawCursors, _ := payload["cursors"].([]any)
	chunk, ok := cupsChunkFromCursors(rawCursors)
	if !ok || c.wasEcho(chunk) {
		return nil
	}
	c.acceptChunk(chunk)
	return nil
}

func (c *CupsPacketConn) acceptChunk(chunk []byte) {
	c.recvMu.Lock()
	c.recv = append(c.recv, chunk...)
	var pkts [][]byte
	for len(c.recv) >= 2 {
		ln := int(binary.BigEndian.Uint16(c.recv[:2]))
		if ln == 0 {
			c.recv = nil
			break
		}
		if len(c.recv) < 2+ln {
			break
		}
		pkts = append(pkts, append([]byte(nil), c.recv[2:2+ln]...))
		c.recv = append([]byte(nil), c.recv[2+ln:]...)
	}
	if len(c.recv) > 1<<20 {
		c.recv = nil
	}
	c.recvMu.Unlock()
	for _, pkt := range pkts {
		select {
		case c.rx <- pkt:
		case <-c.closed:
			return
		default:
			log.Printf("[covert/cups] rx full, drop %d bytes", len(pkt))
		}
	}
}

func (c *CupsPacketConn) writeLoop() {
	tick := time.NewTicker(cupsKeepAlive)
	defer tick.Stop()
	var last time.Time
	for {
		if !c.flushPong() {
			return
		}
		select {
		case <-c.closed:
			return
		case <-c.pongCh:
			if err := c.sendPong(); err != nil {
				c.kick()
			}
		case pkt := <-c.tx:
			if !c.waitReady() {
				return
			}
			// Одно сообщение курсора — пачка кадров до 8000 байт, темп тот же 12 мс.
			// Иначе каждый ACK KCP занимает слот, и 200 КБ едут около 20 с.
			if err := c.drainTx(pkt, &last); err != nil {
				log.Printf("[covert/cups] send: %v", err)
				c.kick()
			}
		case <-tick.C:
			if !c.connected.Load() {
				continue
			}
			c.pace(&last)
			if err := c.sendKeepalive(); err != nil {
				c.kick()
			}
		}
	}
}

// flushPong выталкивает ответ на ping раньше кадров KCP.
func (c *CupsPacketConn) flushPong() bool {
	for {
		select {
		case <-c.closed:
			return false
		case <-c.pongCh:
			if err := c.sendPong(); err != nil {
				c.kick()
			}
		default:
			return true
		}
	}
}

func (c *CupsPacketConn) sendPong() error {
	ws := c.currentWS()
	if ws == nil {
		return nil
	}
	return c.writeRaw(ws, []byte("{}"))
}

func (c *CupsPacketConn) waitReady() bool {
	for {
		if c.connected.Load() {
			return true
		}
		select {
		case <-c.closed:
			return false
		case <-time.After(100 * time.Millisecond):
		}
	}
}

func (c *CupsPacketConn) pace(last *time.Time) {
	if !last.IsZero() {
		if d := cupsPace - time.Since(*last); d > 0 {
			c.sleep(d)
		}
	}
	*last = time.Now()
}

// drainTx забирает кадры, которые уже лежат в очереди, в одно сообщение.
func (c *CupsPacketConn) drainTx(first []byte, last *time.Time) error {
	pkts := [][]byte{first}
	total := 2 + len(first)
	for {
		if !c.flushPong() {
			return net.ErrClosed
		}
		select {
		case pkt := <-c.tx:
			need := 2 + len(pkt)
			if total+need > cupsMaxChunk {
				c.pace(last)
				if err := c.sendDatagrams(pkts); err != nil {
					return err
				}
				pkts = [][]byte{pkt}
				total = need
				continue
			}
			pkts = append(pkts, pkt)
			total += need
		default:
			c.pace(last)
			return c.sendDatagrams(pkts)
		}
	}
}

func (c *CupsPacketConn) sendDatagrams(pkts [][]byte) error {
	var inner []byte
	for _, pkt := range pkts {
		if len(pkt) == 0 || len(pkt) > 65535 {
			return fmt.Errorf("cups: bad datagram %d", len(pkt))
		}
		var hdr [2]byte
		binary.BigEndian.PutUint16(hdr[:], uint16(len(pkt)))
		inner = append(inner, hdr[:]...)
		inner = append(inner, pkt...)
	}
	return c.sendStream(inner)
}

func (c *CupsPacketConn) sendStream(inner []byte) error {
	for off := 0; off < len(inner); {
		n := len(inner) - off
		if n > cupsMaxChunk {
			n = cupsMaxChunk
		}
		chunk := inner[off : off+n]
		wire := make([]byte, 2+len(chunk))
		binary.BigEndian.PutUint16(wire[:2], uint16(len(chunk)))
		copy(wire[2:], chunk)
		c.rememberEcho(chunk)
		if err := c.rpcCursors(wire); err != nil {
			return err
		}
		off += n
	}
	return nil
}

func (c *CupsPacketConn) rpcCursors(wire []byte) error {
	a := c.snapshot()
	ws := c.currentWS()
	if a == nil || ws == nil {
		return errors.New("cups: not in a room")
	}
	msg := map[string]any{
		"id": c.cmd.Add(1),
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
	return c.writeJSON(ws, msg)
}

func (c *CupsPacketConn) sendKeepalive() error {
	a := c.snapshot()
	ws := c.currentWS()
	if a == nil || ws == nil {
		return nil
	}
	return c.writeJSON(ws, map[string]any{
		"id": c.cmd.Add(1),
		"rpc": map[string]any{
			"method": "shared_editor_ping",
			"data":   map[string]any{"room": a.roomUUID, "user": a.userUUID},
		},
	})
}

func (c *CupsPacketConn) snapshot() *cupsAuth {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.auth == nil {
		return nil
	}
	cp := *c.auth
	return &cp
}

func cupsHash(b []byte) uint64 {
	h := fnv.New64a()
	_, _ = h.Write(b)
	return h.Sum64()
}

func (c *CupsPacketConn) rememberEcho(b []byte) {
	sum := cupsHash(b)
	c.echoMu.Lock()
	c.echoRing[c.echoPos%len(c.echoRing)] = sum
	c.echoPos++
	c.echoMu.Unlock()
}

func (c *CupsPacketConn) wasEcho(b []byte) bool {
	sum := cupsHash(b)
	c.echoMu.Lock()
	defer c.echoMu.Unlock()
	n := c.echoPos
	if n > len(c.echoRing) {
		n = len(c.echoRing)
	}
	for i := 0; i < n; i++ {
		idx := c.echoPos - 1 - i
		if idx < 0 {
			break
		}
		if c.echoRing[idx%len(c.echoRing)] == sum {
			return true
		}
	}
	return false
}

func (c *CupsPacketConn) kick() {
	c.connected.Store(false)
	c.mu.Lock()
	ws := c.ws
	c.ws = nil
	c.mu.Unlock()
	if ws != nil {
		ws.Close()
	}
}

func (c *CupsPacketConn) writeJSON(ws *websocket.Conn, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.writeRaw(ws, raw)
}

func (c *CupsPacketConn) writeRaw(ws *websocket.Conn, raw []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if err := ws.SetWriteDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return err
	}
	return ws.WriteMessage(websocket.TextMessage, raw)
}

func (c *CupsPacketConn) sleep(d time.Duration) bool {
	if d <= 0 {
		return true
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-c.closed:
		return false
	case <-c.ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

func (c *CupsPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
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

func (c *CupsPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	buf := make([]byte, len(p))
	copy(buf, p)
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	case c.tx <- buf:
		return len(p), nil
	}
}

func (c *CupsPacketConn) Close() error {
	c.once.Do(func() {
		c.stop()
		c.kick()
		close(c.closed)
	})
	return nil
}

func (c *CupsPacketConn) LocalAddr() net.Addr               { return kcpDialAddr }
func (c *CupsPacketConn) SetDeadline(t time.Time) error     { c.rdDeadline.Store(&t); return nil }
func (c *CupsPacketConn) SetReadDeadline(t time.Time) error { c.rdDeadline.Store(&t); return nil }
func (c *CupsPacketConn) SetWriteDeadline(time.Time) error  { return nil }

func cupsJoinURL(id string) string { return cupsBaseURL + "?room=" + id }

func cupsSocketURL(connURL string) string {
	u, err := url.Parse(connURL)
	if err != nil {
		return ""
	}
	switch u.Scheme {
	case "https":
		u.Scheme = "wss"
	case "http":
		u.Scheme = "ws"
	}
	u.Path = strings.TrimRight(u.Path, "/") + "/websocket"
	u.RawQuery = ""
	u.Fragment = ""
	return u.String()
}

func originOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return ""
	}
	scheme := u.Scheme
	if scheme == "wss" {
		scheme = "https"
	} else if scheme == "ws" {
		scheme = "http"
	}
	return scheme + "://" + u.Host
}

func hostOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return u.Host
}

func absURL(base, ref string) string {
	ref = strings.TrimSpace(ref)
	if ref == "" {
		return ""
	}
	u, err := url.Parse(ref)
	if err != nil {
		return ref
	}
	if u.IsAbs() {
		return u.String()
	}
	b, err := url.Parse(base)
	if err != nil {
		return ref
	}
	return b.ResolveReference(u).String()
}

func cookieValue(jar http.CookieJar, raw, name string) string {
	u, err := url.Parse(raw)
	if err != nil || jar == nil {
		return ""
	}
	for _, ck := range jar.Cookies(u) {
		if ck.Name == name {
			return ck.Value
		}
	}
	return ""
}

func cookieHeader(jar http.CookieJar, raw string) string {
	u, err := url.Parse(raw)
	if err != nil || jar == nil {
		return ""
	}
	var b strings.Builder
	for _, ck := range jar.Cookies(u) {
		if b.Len() > 0 {
			b.WriteString("; ")
		}
		b.WriteString(ck.Name)
		b.WriteByte('=')
		b.WriteString(ck.Value)
	}
	return b.String()
}

func cupsMeta(doc, name string) string {
	for _, tag := range regexp.MustCompile(`(?i)<meta\b[^>]*>`).FindAllString(doc, -1) {
		attrs := htmlAttrs(tag)
		if strings.EqualFold(attrs["name"], name) {
			return html.UnescapeString(attrs["content"])
		}
	}
	return ""
}

func cupsDataUUID(doc, attr string) string {
	raw := htmlAttr(doc, attr)
	if raw == "" {
		return ""
	}
	var obj struct {
		UUID string `json:"uuid"`
	}
	if json.Unmarshal([]byte(html.UnescapeString(raw)), &obj) != nil {
		return ""
	}
	if cupsUUID.MatchString(obj.UUID) {
		return strings.ToLower(obj.UUID)
	}
	return ""
}

func htmlAttr(doc, name string) string {
	re := regexp.MustCompile(`(?i)\b` + regexp.QuoteMeta(name) + `\s*=\s*("([^"]*)"|'([^']*)')`)
	m := re.FindStringSubmatch(doc)
	if m == nil {
		return ""
	}
	if m[2] != "" {
		return m[2]
	}
	return m[3]
}

func htmlAttrs(tag string) map[string]string {
	out := map[string]string{}
	re := regexp.MustCompile(`(?i)([a-z_:][-a-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)')`)
	for _, m := range re.FindAllStringSubmatch(tag, -1) {
		val := m[3]
		if m[2] != "" && strings.HasPrefix(m[2], "'") {
			val = m[4]
		}
		out[strings.ToLower(m[1])] = val
	}
	return out
}

func roomIDFromPublicURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" || strings.EqualFold(raw, "create") {
		return ""
	}
	if u, err := url.Parse(raw); err == nil && u.Host != "" {
		if id := strings.ToLower(u.Query().Get("room")); cupsUUID.MatchString(id) {
			return id
		}
		if ids := unpackRoomIDs(u.Query().Get("rooms")); len(ids) > 0 {
			return ids[0]
		}
	}
	if cupsUUID.MatchString(raw) {
		return strings.ToLower(raw)
	}
	if ids := unpackRoomIDs(raw); len(ids) > 0 {
		return ids[0]
	}
	return ""
}

func unpackRoomIDs(s string) []string {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		raw, err = base64.URLEncoding.DecodeString(s)
		if err != nil {
			return nil
		}
	}
	var ids []string
	if json.Unmarshal(raw, &ids) != nil {
		return nil
	}
	var out []string
	for _, id := range ids {
		id = strings.ToLower(strings.TrimSpace(id))
		if cupsUUID.MatchString(id) {
			out = append(out, id)
		}
	}
	return out
}

func cupsCursorPairs(blob []byte) []map[string]any {
	nums := cupsInts(blob)
	pairs := make([]map[string]any, 0, (len(nums)+1)/2)
	for i := 0; i < len(nums); i += 2 {
		col := uint64(0)
		if i+1 < len(nums) {
			col = nums[i+1]
		}
		pairs = append(pairs, map[string]any{"row": nums[i], "column": col})
	}
	return pairs
}

func cupsInts(blob []byte) []uint64 {
	n := (len(blob) + cupsDigits - 1) / cupsDigits
	out := make([]uint64, n)
	for i := range out {
		var v uint64
		for j := 0; j < cupsDigits; j++ {
			v <<= 8
			if idx := i*cupsDigits + j; idx < len(blob) {
				v |= uint64(blob[idx])
			}
		}
		out[i] = v
	}
	return out
}

func cupsBytes(nums []uint64) []byte {
	out := make([]byte, 0, len(nums)*cupsDigits)
	for _, v := range nums {
		var b [cupsDigits]byte
		for j := cupsDigits - 1; j >= 0; j-- {
			b[j] = byte(v)
			v >>= 8
		}
		out = append(out, b[:]...)
	}
	return out
}

func cupsChunkFromCursors(raw []any) ([]byte, bool) {
	if len(raw) == 0 {
		return nil, false
	}
	nums := make([]uint64, 0, len(raw)*2)
	for _, item := range raw {
		m, ok := item.(map[string]any)
		if !ok {
			return nil, false
		}
		row, ok := cupsJSONUint(m["row"])
		if !ok {
			return nil, false
		}
		col, _ := cupsJSONUint(m["column"])
		nums = append(nums, row, col)
	}
	decoded := cupsBytes(nums)
	if len(decoded) < 2 {
		return nil, false
	}
	n := int(binary.BigEndian.Uint16(decoded[:2]))
	if n < 0 || 2+n > len(decoded) {
		return nil, false
	}
	return decoded[2 : 2+n], true
}

func cupsJSONUint(v any) (uint64, bool) {
	f, ok := v.(float64)
	if !ok || f < 0 || f != float64(uint64(f)) {
		return 0, false
	}
	return uint64(f), true
}

func splitWS(raw []byte) [][]byte {
	parts := strings.Split(string(raw), "\n")
	out := make([][]byte, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, []byte(p))
		}
	}
	return out
}

func matchCommand(line []byte, id int) (bool, error) {
	if string(line) == "{}" {
		return false, nil
	}
	var reply struct {
		ID    int `json:"id"`
		Error *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal(line, &reply) != nil || reply.ID != id {
		return false, nil
	}
	if reply.Error != nil {
		return true, fmt.Errorf("%d %s", reply.Error.Code, reply.Error.Message)
	}
	return true, nil
}
