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
//
// ПРОИЗВОДИТЕЛЬНОСТЬ (11.09.2026): раньше WriteTo слал каждый KCP-пакет ОДНИМ синхронным
// POST в одну ленту → канал сериализовался (~10 КБ/с) и захлёбывался под нагрузкой
// телефона. Теперь исходящие пакеты кладутся в очередь txCh, которую разгребает ПУЛ из
// txWorkers параллельных отправителей (HTTP/2 к Яндексу мультиплексирует их по одному
// соединению). KCP толерантен к переупорядочиванию → параллельные POST безопасны.

import (
	"context"
	"encoding/base64"
	"encoding/binary"
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

	// Пул параллельных отправителей + СКЛЕЙКА пакетов. На сотовой сети (потери/задержки)
	// KCP генерит лавину микро-пакетов (ACK/переспросы ~24 байта); раньше каждый уезжал
	// ОТДЕЛЬНЫМ POST → канал захлёбывался служебкой и в разнос (93% трафика = 24-байтные
	// кадры). Теперь batchSender склеивает всё, что накопилось в очереди, в ОДИН relay-кадр
	// (до batchMaxBytes) → на порядок меньше HTTP-запросов, петля разноса рвётся.
	batchSenders  = 8
	batchMaxBytes = 40 * 1024
	batchMaxPkts  = 128
	txQueue       = 8192
	rxQueue       = 8192

	// Xiva downstream живучесть: на сотовой WS тихо умирает (радио-сон, NAT-rebind,
	// хэндовер), а ReadMessage без дедлайна висит вслепую → пара не пересобирается.
	// Дедлайн ловит мёртвый сокет за секунды и релончит сессию. Сервер шлёт app-ping
	// раз в ~60с, мы ещё и свой WS-ping раз в 30с (обновляет дедлайн через pong).
	wsReadTimeout = 90 * time.Second
	wsPingEvery   = 30 * time.Second

	// Период непрерывного re-rendezvous у клиента (SYN-discovery). Раньше клиент слал
	// SYN ОДИН раз до спаривания и умолкал → после переподключения любой из сторон
	// (новый volga-userId) пара навсегда рвалась. Теперь SYN идёт постоянно, дёшево.
	rendezvousEvery = 4 * time.Second
)

// kcpDialAddr — фиктивный remote для kcp.NewConn2 на стороне клиента. На клиенте KCP
// одиночный (dial), возвращаемый ReadFrom адрес он игнорирует, а наш WriteTo шлёт по
// peerUserID, не по этому адресу → значение чисто косметическое.
var kcpDialAddr = peerAddr{id: 0}

// convAddr парсит conv-id KCP-пакета (первые 4 байта LE; block=nil, FEC off → без
// смещения) и делает из него адрес. Демультиплекс KCP-листенера на выходе идёт ПО conv,
// а не по volga-userId. Следствия:
//   - флап сети (userId сменился, conv тот же) → та же KCP-сессия → поток не рвётся;
//   - реконнект приложения (новый kcpConn → новый conv) → новая сессия, без коллизии
//     со старой (старая сама отвалится по smux-таймауту);
//   - разные клиенты (разные conv) → независимые сессии (бонус к будущему пулу).
func convAddr(pkt []byte) peerAddr {
	if len(pkt) >= 4 {
		return peerAddr{id: int64(binary.LittleEndian.Uint32(pkt[:4]))}
	}
	return peerAddr{id: 0}
}

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

	tx     chan []byte
	rx     chan []byte
	closed chan struct{}
	once   sync.Once

	rdDeadline atomic.Pointer[time.Time]
	running    atomic.Bool
}

// forceIPv4Dial — диалер, принудительно ходящий по IPv4. На сотовых сетях РФ IPv6
// часто «no route to host» (видно в логе olcRTC: STUN udp6 no route to host), и часть
// параллельных relay-соединений к Яндексу залипала на v6 → covert-данные не шли на
// мобильном, хотя на WiFi (живой v6) всё работало. tcp→tcp4 убирает залипание.
func forceIPv4Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	d := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	switch network {
	case "tcp", "tcp6":
		network = "tcp4"
	}
	return d.DialContext(ctx, network, addr)
}

// sharedTransport — общий транспорт с большим пулом соединений и HTTP/2, чтобы
// десятки параллельных relay-POST не открывали каждый своё TLS-соединение.
func sharedTransport() *http.Transport {
	return &http.Transport{
		DialContext:         forceIPv4Dial,
		MaxIdleConns:        256,
		MaxIdleConnsPerHost: 256,
		MaxConnsPerHost:     0,
		IdleConnTimeout:     90 * time.Second,
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: 15 * time.Second,
	}
}

// NewVolgaPacketConn поднимает covert-сессию и ждёт спаривания.
// rendezvousTimeout<=0 — ждать бесконечно (для exit, он живёт долго); клиент задаёт лимит.
func NewVolgaPacketConn(ctx context.Context, exit bool, publicURL string, rendezvousTimeout time.Duration) (*VolgaPacketConn, error) {
	jar, _ := cookiejar.New(nil)
	tr := sharedTransport()
	c := &VolgaPacketConn{
		exit:       exit,
		publicURL:  publicURL,
		follow:     &http.Client{Jar: jar, Timeout: 30 * time.Second, Transport: tr},
		noRedirect: &http.Client{Jar: jar, Timeout: 30 * time.Second, Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		tx:         make(chan []byte, txQueue),
		rx:         make(chan []byte, rxQueue),
		closed:     make(chan struct{}),
	}
	c.running.Store(true)
	go c.sessionLoop()
	if !c.exit {
		go c.rendezvousLoop()
	}
	go c.keepAliveLoop()
	for i := 0; i < batchSenders; i++ {
		go c.batchSender()
	}

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

// batchSender разгребает очередь исходящих пакетов и СКЛЕИВАЕТ всё, что накопилось,
// в один relay-кадр (формат пачки: [2 байта длина][пакет]...). Это резко снижает число
// HTTP-запросов в Яндекс на «болтливом» трафике (KCP ACK/переспросы) и не даёт каналу
// уйти в разнос на сотовой сети. KCP собирает пакеты обратно по порядку сам.
func (c *VolgaPacketConn) batchSender() {
	for {
		var first []byte
		select {
		case <-c.closed:
			return
		case first = <-c.tx:
		}
		pkts := [][]byte{first}
		total := 2 + len(first)
	drain:
		for len(pkts) < batchMaxPkts && total < batchMaxBytes {
			select {
			case p := <-c.tx:
				pkts = append(pkts, p)
				total += 2 + len(p)
			default:
				break drain
			}
		}
		peer := c.peerUserID.Load()
		if peer == 0 {
			continue
		}
		c.mu.RLock()
		s := c.sess
		c.mu.RUnlock()
		if s == nil {
			continue
		}
		_ = c.sendRelay(s, peer, typeData, packBatch(pkts))
	}
}

// packBatch упаковывает пачку пакетов в один блоб: [2 байта BE длина][пакет]...
func packBatch(pkts [][]byte) []byte {
	n := 0
	for _, p := range pkts {
		n += 2 + len(p)
	}
	buf := make([]byte, 0, n)
	var hdr [2]byte
	for _, p := range pkts {
		binary.BigEndian.PutUint16(hdr[:], uint16(len(p)))
		buf = append(buf, hdr[0], hdr[1])
		buf = append(buf, p...)
	}
	return buf
}

// unpackBatch распаковывает блоб пачки обратно в отдельные пакеты.
func unpackBatch(blob []byte) [][]byte {
	var out [][]byte
	for len(blob) >= 2 {
		n := int(binary.BigEndian.Uint16(blob[:2]))
		blob = blob[2:]
		if n > len(blob) {
			break
		}
		p := make([]byte, n)
		copy(p, blob[:n])
		out = append(out, p)
		blob = blob[n:]
	}
	return out
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
	dialer := websocket.Dialer{HandshakeTimeout: 15 * time.Second, ReadBufferSize: 1 << 20, NetDialContext: forceIPv4Dial}
	h := http.Header{}
	h.Set("User-Agent", userAgent)
	h.Set("Origin", volgaOrigin)
	conn, _, err := dialer.Dial(s.xivaURL, h)
	if err != nil {
		return err
	}
	conn.SetReadLimit(8 << 20)
	defer conn.Close()

	// Живучесть: read-deadline ловит тихо умерший на сотовой WS; каждый прочитанный
	// кадр (в т.ч. app-ping сервера ~60с) и pong на наш ping его продлевают.
	conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
		return nil
	})

	done := make(chan struct{})
	defer close(done)
	go func() {
		<-c.closed
		conn.Close()
	}()
	// WS-ping для NAT-keepalive и раннего детекта мёртвого сокета. Единственный писатель
	// в conn — конфликта с ReadMessage нет (gorilla допускает 1 читателя + 1 писателя).
	go func() {
		t := time.NewTicker(wsPingEvery)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-c.closed:
				return
			case <-t.C:
				_ = conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second))
			}
		}
	}()

	for c.running.Load() {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
		c.handleXivaFrame(s, msg)
	}
	return nil
}

// rendezvousLoop (только клиент) НЕПРЕРЫВНО объявляет свой текущий userId и открывает
// userId выхода: каждые rendezvousEvery шлёт SYN всем соавторам-не-себе. Выход отвечает
// ACK со своим текущим userId. Это восстанавливает пару после переподключения ЛЮБОЙ из
// сторон (у обеих на реконнекте новый volga-userId). Раньше SYN шёл разово до первой
// пары и умолкал → на сотовой (частые обрывы WS) пара рвалась навсегда.
func (c *VolgaPacketConn) rendezvousLoop() {
	// Пока не спарились — опрашиваем чаще, чтобы старт был быстрым.
	fast := 1 * time.Second
	for c.running.Load() {
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
		wait := rendezvousEvery
		if c.peerUserID.Load() == 0 {
			wait = fast
		}
		select {
		case <-c.closed:
			return
		case <-time.After(wait):
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
	// Любой валидный (наш MAGIC) кадр от соседа несёт его ТЕКУЩИЙ userId. Всегда
	// подхватываем его как активного пира — так пара переживает переподключение любой
	// из сторон (новый volga-userId после реконнекта). KCP-сессия при этом одна и та же
	// одна на conv (см. convAddr), смена userId для неё невидима.
	switch mtype {
	case typeSyn:
		if c.exit {
			c.setPeer(sender)
			c.sendRelay(s, sender, typeAck, nil)
		}
	case typeAck:
		if !c.exit {
			c.setPeer(sender)
		}
	case typeKA:
		c.setPeer(sender)
	case typeData:
		c.setPeer(sender)
		// payload — ПАЧКА из нескольких KCP-пакетов (см. packBatch); распаковываем и
		// отдаём каждый пакет KCP отдельно.
		for _, pkt := range unpackBatch(payload) {
			select {
			case c.rx <- pkt:
			case <-c.closed:
			default: // очередь переполнена — дропаем, KCP переспросит
			}
		}
	}
}

// setPeer запоминает текущий userId соседа. Меняем только при реальной смене, чтобы
// не молотить atomic впустую. Адрес KCP-сессии зависит от conv, а не отсюда (см. convAddr).
func (c *VolgaPacketConn) setPeer(sender int64) {
	if c.peerUserID.Load() != sender {
		c.peerUserID.Store(sender)
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
		// Адрес источника зависит от роли:
		//  - ВЫХОД (kcp listener): по conv KCP-пакета → демультиплекс сессий (флап-
		//    непрерывность + чистая сессия на реконнект приложения + мультиклиент).
		//  - КЛИЕНТ (kcp dial): dialed UDPSession в kcp-go ОТБРАСЫВАЕТ пакеты, чей addr
		//    != raddr из NewConn2 (kcpDialAddr). Поэтому клиент обязан вернуть тот же
		//    kcpDialAddr, иначе весь downstream молча дропается.
		if c.exit {
			return n, convAddr(b), nil
		}
		return n, kcpDialAddr, nil
	}
}

func (c *VolgaPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	default:
	}
	if c.peerUserID.Load() == 0 {
		return 0, errors.New("volga: not paired")
	}
	buf := make([]byte, len(p))
	copy(buf, p)
	select {
	case c.tx <- buf:
		return len(p), nil
	case <-c.closed:
		return 0, net.ErrClosed
	}
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
