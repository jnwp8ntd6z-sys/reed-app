package covert

// volgaPacketConn — net.PacketConn поверх covert-канала Яндекс.volga (relay + Xiva).
// Носитель доказан живым (см. память project-openflux-happ-integration): upstream —
// POST volga.yandex.ru/session/main/<rp>/relay, downstream — Xiva push WS. Здесь тот же
// протокол, но обёрнут в net.PacketConn, чтобы поверх лёг KCP (надёжность+порядок) и
// smux (мультиплекс) — так covert превращается в надёжные потоки для VLESS БЕЗ gvisor.
//
// Каждый UDP-«пакет» KCP уезжает как одно relay-сообщение (кадр MAGIC:TYPE:BASE64,
// TYPE=D — данные). Rendezvous (S/A) узнаёт userId соседа: client шлёт SYN всем из
// /users, exit отвечает ACK. Один peer на сессию → это по сути connected packet conn.

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	userAgent   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
	volgaOrigin = "https://volga.yandex.ru"
	diskReferer = "https://disk.yandex.ru/"
	wireMagic   = "0FLXv1"

	typeSyn  = "S"
	typeAck  = "A"
	typeData = "D"
	typeKA   = "K"
)

var clientConfigRe = regexp.MustCompile(`(?s)<script[^>]*id="client-config"[^>]*>(.*?)</script>`)

// peerAddr — фиктивный адрес единственного соседа (KCP требует net.Addr).
type peerAddr struct{ id int64 }

func (a peerAddr) Network() string { return "volga" }
func (a peerAddr) String() string  { return fmt.Sprintf("volga:%d", a.id) }

type volgaSession struct {
	requestPath string
	volgaToken  string
	sessionID   string
	ownUserID   int64
	xivaURL     string
}

// VolgaPacketConn реализует net.PacketConn поверх covert-канала.
type VolgaPacketConn struct {
	exit      bool
	publicURL string

	follow     *http.Client
	noRedirect *http.Client

	mu   sync.RWMutex
	sess *volgaSession

	peerUserID atomic.Int64

	rx     chan []byte
	closed chan struct{}
	once   sync.Once

	rdDeadline atomic.Pointer[time.Time]
	running    atomic.Bool
}

// NewVolgaPacketConn поднимает covert-сессию и ждёт спаривания.
// rendezvousTimeout<=0 — ждать бесконечно (для exit, он живёт долго); клиент задаёт лимит.
func NewVolgaPacketConn(ctx context.Context, exit bool, publicURL string, rendezvousTimeout time.Duration) (*VolgaPacketConn, error) {
	jar, _ := cookiejar.New(nil)
	c := &VolgaPacketConn{
		exit:       exit,
		publicURL:  publicURL,
		follow:     &http.Client{Jar: jar, Timeout: 30 * time.Second},
		noRedirect: &http.Client{Jar: jar, Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		rx:         make(chan []byte, 1024),
		closed:     make(chan struct{}),
	}
	c.running.Store(true)
	go c.sessionLoop()
	if !c.exit {
		go c.rendezvousLoop()
	}
	go c.keepAliveLoop()

	// Ждём первого спаривания, чтобы KCP-хэндшейк не стучал в пустоту.
	var timeout <-chan time.Time
	if rendezvousTimeout > 0 {
		t := time.NewTimer(rendezvousTimeout)
		defer t.Stop()
		timeout = t.C
	}
	for {
		if c.peerUserID.Load() != 0 {
			return c, nil
		}
		select {
		case <-ctx.Done():
			c.Close()
			return nil, ctx.Err()
		case <-timeout:
			c.Close()
			return nil, errors.New("volga: rendezvous timeout")
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func (c *VolgaPacketConn) sessionLoop() {
	backoff := 500 * time.Millisecond
	for c.running.Load() {
		s, err := c.launch()
		if err != nil {
			c.sleepBackoff(&backoff)
			continue
		}
		c.mu.Lock()
		c.sess = s
		c.mu.Unlock()
		_ = c.subscribeAndRead(s)
		c.sleepBackoff(&backoff)
	}
}

func (c *VolgaPacketConn) sleepBackoff(b *time.Duration) {
	select {
	case <-c.closed:
	case <-time.After(*b):
	}
	*b *= 2
	if *b > 15*time.Second {
		*b = 15 * time.Second
	}
}

func (c *VolgaPacketConn) launch() (*volgaSession, error) {
	req, _ := http.NewRequest("GET", c.publicURL, nil)
	req.Header.Set("User-Agent", userAgent)
	resp, err := c.follow.Do(req)
	if err != nil {
		return nil, err
	}
	body, _ := readAllClose(resp)

	m := clientConfigRe.FindSubmatch(body)
	if len(m) < 2 {
		return nil, fmt.Errorf("client-config not found")
	}
	var cc struct {
		OfficeActionData struct {
			ActionURL      string      `json:"action_url"`
			AccessToken    string      `json:"access_token"`
			AccessTokenTTL json.Number `json:"access_token_ttl"`
		} `json:"officeActionData"`
	}
	if err := json.Unmarshal(m[1], &cc); err != nil {
		return nil, err
	}
	oa := cc.OfficeActionData
	if oa.ActionURL == "" || oa.AccessToken == "" {
		return nil, fmt.Errorf("officeActionData missing fields")
	}

	form := url.Values{}
	form.Set("access_token", oa.AccessToken)
	form.Set("access_token_ttl", oa.AccessTokenTTL.String())
	preq, _ := http.NewRequest("POST", oa.ActionURL, strings.NewReader(form.Encode()))
	preq.Header.Set("User-Agent", userAgent)
	preq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	preq.Header.Set("Referer", diskReferer)
	presp, err := c.noRedirect.Do(preq)
	if err != nil {
		return nil, err
	}
	readAllClose(presp)

	loc := presp.Header.Get("Location")
	if presp.StatusCode < 300 || presp.StatusCode >= 400 || !strings.Contains(loc, "spreadsheet") {
		return nil, fmt.Errorf("auth/initial unexpected: %d", presp.StatusCode)
	}
	u, err := url.Parse(loc)
	if err != nil {
		return nil, err
	}
	q := u.Query()
	requestPath := q.Get("request-path")
	volgaToken := q.Get("token")
	rawJSON := q.Get("json")
	if requestPath == "" || volgaToken == "" || rawJSON == "" {
		return nil, fmt.Errorf("location missing fields")
	}

	var sj struct {
		Xiva struct {
			URL     string      `json:"url"`
			Service string      `json:"service"`
			User    string      `json:"user"`
			Sign    string      `json:"sign"`
			TS      json.Number `json:"ts"`
		} `json:"xiva"`
		SessionID string      `json:"sessionId"`
		UserID    json.Number `json:"userId"`
	}
	if err := json.Unmarshal([]byte(rawJSON), &sj); err != nil {
		return nil, err
	}
	uid, err := sj.UserID.Int64()
	if err != nil {
		return nil, err
	}

	host := sj.Xiva.URL
	if i := strings.Index(host, "://"); i >= 0 {
		host = host[i+3:]
	}
	xq := url.Values{}
	xq.Set("service", sj.Xiva.Service)
	xq.Set("user", sj.Xiva.User)
	xq.Set("sign", sj.Xiva.Sign)
	xq.Set("ts", sj.Xiva.TS.String())
	xq.Set("client", "web")
	xq.Set("session", sj.SessionID)
	xq.Set("fetch_history", fmt.Sprintf("%s:%s:0:1", sj.Xiva.User, sj.Xiva.Service))
	xq.Set("x_request_attempt", "0")
	xivaURL := fmt.Sprintf("wss://%s/subscribe/websocket?%s", host, xq.Encode())

	return &volgaSession{
		requestPath: requestPath,
		volgaToken:  volgaToken,
		sessionID:   sj.SessionID,
		ownUserID:   uid,
		xivaURL:     xivaURL,
	}, nil
}

func (c *VolgaPacketConn) subscribeAndRead(s *volgaSession) error {
	dialer := websocket.Dialer{HandshakeTimeout: 15 * time.Second}
	h := http.Header{}
	h.Set("User-Agent", userAgent)
	h.Set("Origin", volgaOrigin)
	conn, _, err := dialer.Dial(s.xivaURL, h)
	if err != nil {
		return err
	}
	defer conn.Close()
	go func() {
		<-c.closed
		conn.Close()
	}()
	for c.running.Load() {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		c.handleXivaFrame(s, msg)
	}
	return nil
}

func (c *VolgaPacketConn) rendezvousLoop() {
	for c.running.Load() {
		if c.peerUserID.Load() != 0 {
			select {
			case <-c.closed:
				return
			case <-time.After(5 * time.Second):
			}
			continue
		}
		c.mu.RLock()
		s := c.sess
		c.mu.RUnlock()
		if s != nil {
			for _, uid := range c.fetchUsers(s) {
				if uid != s.ownUserID {
					c.sendRelay(s, uid, typeSyn, nil)
				}
			}
		}
		select {
		case <-c.closed:
			return
		case <-time.After(2 * time.Second):
		}
	}
}

func (c *VolgaPacketConn) keepAliveLoop() {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
			peer := c.peerUserID.Load()
			if peer == 0 {
				continue
			}
			c.mu.RLock()
			s := c.sess
			c.mu.RUnlock()
			if s != nil {
				c.sendRelay(s, peer, typeKA, nil)
			}
		}
	}
}

func (c *VolgaPacketConn) fetchUsers(s *volgaSession) []int64 {
	base := fmt.Sprintf("%s/session/main/%s/users", volgaOrigin, s.requestPath)
	req, _ := http.NewRequest("POST", base, strings.NewReader("{}"))
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Origin", volgaOrigin)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+s.volgaToken)
	resp, err := c.follow.Do(req)
	if err != nil {
		return nil
	}
	body, _ := readAllClose(resp)
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	var out struct {
		Users []struct {
			ID json.Number `json:"id"`
		} `json:"users"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return nil
	}
	ids := make([]int64, 0, len(out.Users))
	for _, u := range out.Users {
		if id, err := u.ID.Int64(); err == nil {
			ids = append(ids, id)
		}
	}
	return ids
}

func (c *VolgaPacketConn) handleXivaFrame(s *volgaSession, raw []byte) {
	var outer struct {
		Operation string `json:"operation"`
		Message   string `json:"message"`
	}
	if err := json.Unmarshal(raw, &outer); err != nil || outer.Message == "" {
		return
	}
	var inner struct {
		T       string      `json:"t"`
		UserID  json.Number `json:"userId"`
		Message string      `json:"message"`
	}
	if err := json.Unmarshal([]byte(outer.Message), &inner); err != nil || inner.T != "relay" {
		return
	}
	sender, err := inner.UserID.Int64()
	if err != nil || sender == s.ownUserID {
		return
	}
	mtype, payload, ok := parseWire(inner.Message)
	if !ok {
		return
	}
	switch mtype {
	case typeSyn:
		if c.exit {
			c.peerUserID.Store(sender)
			c.sendRelay(s, sender, typeAck, nil)
		}
	case typeAck:
		if !c.exit {
			c.peerUserID.CompareAndSwap(0, sender)
		}
	case typeKA:
		c.peerUserID.CompareAndSwap(0, sender)
	case typeData:
		if p := c.peerUserID.Load(); p == 0 {
			c.peerUserID.Store(sender)
		} else if p != sender {
			return
		}
		buf := make([]byte, len(payload))
		copy(buf, payload)
		select {
		case c.rx <- buf:
		case <-c.closed:
		default: // очередь переполнена — дропаем, KCP переспросит
		}
	}
}

func (c *VolgaPacketConn) sendRelay(s *volgaSession, target int64, mtype string, payload []byte) error {
	wire := wireMagic + ":" + mtype + ":"
	if len(payload) > 0 {
		wire += base64.StdEncoding.EncodeToString(payload)
	}
	bodyBytes, _ := json.Marshal(struct {
		Message      string `json:"message"`
		TargetUserID int64  `json:"targetUserId"`
	}{Message: wire, TargetUserID: target})

	base := fmt.Sprintf("%s/session/main/%s/relay", volgaOrigin, s.requestPath)
	req, _ := http.NewRequest("POST", base, strings.NewReader(string(bodyBytes)))
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Origin", volgaOrigin)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+s.volgaToken)
	resp, err := c.follow.Do(req)
	if err != nil {
		return err
	}
	readAllClose(resp)
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("relay http %d", resp.StatusCode)
	}
	return nil
}

// ---- net.PacketConn ----

func (c *VolgaPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	var timer *time.Timer
	var timeout <-chan time.Time
	if dl := c.rdDeadline.Load(); dl != nil && !dl.IsZero() {
		d := time.Until(*dl)
		if d <= 0 {
			return 0, nil, os_timeout{}
		}
		timer = time.NewTimer(d)
		timeout = timer.C
		defer timer.Stop()
	}
	select {
	case <-c.closed:
		return 0, nil, net.ErrClosed
	case <-timeout:
		return 0, nil, os_timeout{}
	case b := <-c.rx:
		n := copy(p, b)
		return n, peerAddr{id: c.peerUserID.Load()}, nil
	}
}

func (c *VolgaPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	default:
	}
	peer := c.peerUserID.Load()
	if peer == 0 {
		return 0, errors.New("volga: not paired")
	}
	c.mu.RLock()
	s := c.sess
	c.mu.RUnlock()
	if s == nil {
		return 0, errors.New("volga: no session")
	}
	if err := c.sendRelay(s, peer, typeData, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *VolgaPacketConn) Close() error {
	c.once.Do(func() {
		c.running.Store(false)
		close(c.closed)
	})
	return nil
}

func (c *VolgaPacketConn) LocalAddr() net.Addr {
	c.mu.RLock()
	defer c.mu.RUnlock()
	var id int64
	if c.sess != nil {
		id = c.sess.ownUserID
	}
	return peerAddr{id: id}
}

func (c *VolgaPacketConn) SetDeadline(t time.Time) error {
	c.rdDeadline.Store(&t)
	return nil
}
func (c *VolgaPacketConn) SetReadDeadline(t time.Time) error {
	c.rdDeadline.Store(&t)
	return nil
}
func (c *VolgaPacketConn) SetWriteDeadline(time.Time) error { return nil }

// os_timeout — net.Error с Timeout()=true, чтобы KCP трактовал как таймаут чтения.
type os_timeout struct{}

func (os_timeout) Error() string   { return "volga: i/o timeout" }
func (os_timeout) Timeout() bool   { return true }
func (os_timeout) Temporary() bool { return true }

func parseWire(s string) (string, []byte, bool) {
	if !strings.HasPrefix(s, wireMagic+":") {
		return "", nil, false
	}
	rest := s[len(wireMagic)+1:]
	i := strings.IndexByte(rest, ':')
	if i < 0 {
		return "", nil, false
	}
	mtype := rest[:i]
	b64 := rest[i+1:]
	var payload []byte
	if b64 != "" {
		p, err := base64.StdEncoding.DecodeString(b64)
		if err != nil {
			return "", nil, false
		}
		payload = p
	}
	return mtype, payload, true
}

func readAllClose(resp *http.Response) ([]byte, error) {
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}
