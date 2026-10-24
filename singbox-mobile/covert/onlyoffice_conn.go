package covert

// OnlyOfficePacketConn — net.PacketConn поверх covert-канала Яндекс.Документов на СТАРОМ
// редакторе OnlyOffice (socket.io/Engine.IO поверх WebSocket). Носитель — сообщения
// "cursor" соавторов: данные прячем в поле cursor, сервер OnlyOffice broadcast'ит их
// другим соавторам того же документа. Это ЗАМЕТНО быстрее volga (relay POST): персистентный
// WebSocket без пер-сообщенного HTTP-оверхеда. Замер PoC: ~5.2 Mbps против ~0.9 у volga.
//
// Поверх этого PacketConn (неупорядоченные датаграммы) ложится НАШ KCP+smux (надёжность+
// мультиплекс) — тот же стек, что у volga, БЕЗ gvisor (память iOS-расширения цела).
//
// Ключ подключения (иначе Яндекс рвёт WS, close 1005): ПРАВИЛЬНЫЙ Engine.IO v4 хендшейк —
// polling open → sid → socket.io connect+auth (POST 40{token}) → апгрейд в WebSocket с этим
// sid (2probe/3probe/5). Старый yandex.go лез сразу в WS без sid и падал.
//
// Канал BROADCAST (не unicast как volga relay): все соавторы получают cursor друг друга.
// Поэтому userId-rendezvous НЕ нужен; свои эхо-кадры отсекаем по self-id в кадре, чужих/
// шум OnlyOffice — по MAGIC. Демультиплекс KCP на выходе — по conv (convAddr) → мультиклиент
// как бонус (хотя downlink пока broadcast — для одного владельца ок; пул/таргетинг — потом).

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/rand"
	"net"
	"net/http"
	"net/http/cookiejar"
	neturl "net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	ooMagic   = "0FLXo1"         // отсекает чужие cursor/шум OnlyOffice
	ooVersion = "2024.1.1-375"   // версия редактора (подтверждена live: buildNumber 375)
	ooKAEvery = 20 * time.Second // cursor-keepalive, чтобы соавтор не считался ушедшим
	// make-before-break: Яндекс дропает co-editing-сессию ~раз в 70с (4007 «drop»). Заранее
	// (< 70с) поднимаем НОВУЮ сессию (со своим userID → уживается как отдельный соавтор),
	// переключаем на неё запись без разрыва, старую гасим с нахлёстом. 70-сек дроп тогда
	// всегда бьёт уже брошенную сессию → канал не рвётся.
	rotateEvery = 50 * time.Second
	sessOverlap = 3 * time.Second
	// Яндекс ретранслирует cursor с верным префиксом вплоть до ~2000 байт (замер
	// 23.10.2026). Префикс «18» не ретранслируется. Непрерывные ~33 полных сегмента/с
	// через ~5 с дают 4004 access deny. Пауза на КАЖДОМ кадре (включая короткие ACK)
	// срывает RTO у KCP, и даже 8 КБ не доезжают. Поэтому ooSendEvery — только после
	// длинного сегмента; короткие подтверждения уходят сразу.
	ooMaxFrame  = 300
	ooSendEvery = 70 * time.Millisecond
)

var ooClientConfigRe = regexp.MustCompile(`(?s)<script[^>]*id="client-config"[^>]*>(.*?)</script>`)
var ooCursorRe = regexp.MustCompile(`"cursor":"[^;]*;([^"]+)"`)

const ooSaveMarker = `"excelAdditionalInfo":"`

var ooDebug = os.Getenv("COVERT_DEBUG") == "1"

func oodbg(format string, a ...any) {
	if ooDebug {
		log.Printf("[oo] "+format, a...)
	}
}

type ooSession struct {
	conn    *websocket.Conn
	writeMu sync.Mutex
	key     string
	userID  string // тот же id, что в auth этой сессии
	excel   bool
	opened  time.Time
	sawPeer atomic.Bool // был чужой кадр: в этой комнате есть клиент, не закрывать
}

func (s *ooSession) write(b []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.conn.WriteMessage(websocket.TextMessage, b)
}

// OnlyOfficePacketConn реализует net.PacketConn поверх OnlyOffice-WS cursor-канала.
type OnlyOfficePacketConn struct {
	exit      bool
	publicURL string
	selfID    uint32 // случайный id этого конца — отсекать собственные эхо-кадры

	follow *http.Client

	mu       sync.RWMutex
	sessions []*ooSession // активные WS-сессии; во время make-before-break-нахлёста их две

	tx     chan []byte
	rx     chan []byte
	closed chan struct{}
	once   sync.Once

	rdDeadline atomic.Pointer[time.Time]
	running    atomic.Bool
	connected  atomic.Bool
	// Сколько байт длинных кадров выход ещё шлёт быстро. Пауза в длинных кадрах
	// снова даёт быстрые ~8 КБ: это один ответ Reality. Дальше редкий темп,
	// чтобы не ловить 4004. На ровных 150 мс телефон закрывал поток раньше.
	fastLeft int
	lastLong time.Time
}

type ooDocInfo struct {
	host, key, token, origin, cookie string
	fileType, url, title             string
	permissions                      json.RawMessage
}

// NewOnlyOfficePacketConn поднимает OnlyOffice-WS сессию и ждёт готовности канала (WS + doc open).
// timeout<=0 — ждать бесконечно (для exit).
func NewOnlyOfficePacketConn(ctx context.Context, exit bool, publicURL string, timeout time.Duration) (*OnlyOfficePacketConn, error) {
	jar, _ := cookiejar.New(nil)
	// Логин-куки Яндекс-аккаунта (env YANDEX_COOKIE): залогиненную сессию Яндекс не встречает
	// интерактивной капчей cc=1 на дата-центровых IP, поэтому fetchDocInfo доходит до client-config.
	// Без куки анонимный заход с такого IP упирается в cc=1 и client-config не находится.
	if raw := os.Getenv("YANDEX_COOKIE"); raw != "" {
		var cks []*http.Cookie
		for _, part := range strings.Split(raw, ";") {
			part = strings.TrimSpace(part)
			if part == "" {
				continue
			}
			kv := strings.SplitN(part, "=", 2)
			if len(kv) != 2 {
				continue
			}
			cks = append(cks, &http.Cookie{Name: strings.TrimSpace(kv[0]), Value: strings.TrimSpace(kv[1]), Domain: ".yandex.ru", Path: "/"})
		}
		for _, h := range []string{"https://yandex.ru", "https://disk.yandex.ru", "https://docs.yandex.ru"} {
			if u, e := neturl.Parse(h); e == nil {
				jar.SetCookies(u, cks)
			}
		}
		if ooDebug {
			log.Printf("[covert/oo] seeded %d cookies from YANDEX_COOKIE", len(cks))
		}
	}
	fast := 0
	if exit {
		fast = 8000
	}
	c := &OnlyOfficePacketConn{
		exit:      exit,
		publicURL: publicURL,
		selfID:    rand.Uint32(),
		fastLeft:  fast,
		follow: &http.Client{Jar: jar, Timeout: 25 * time.Second, Transport: &http.Transport{
			DialContext: forceIPv4Dial, ForceAttemptHTTP2: true, TLSHandshakeTimeout: 15 * time.Second,
			MaxIdleConns: 32, IdleConnTimeout: 90 * time.Second,
		}},
		tx:     make(chan []byte, txQueue),
		rx:     make(chan []byte, rxQueue),
		closed: make(chan struct{}),
	}
	c.running.Store(true)
	go c.sessionLoop()
	go c.writeLoop()
	go c.keepAliveLoop()
	go c.editorLoop()

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
			return nil, errors.New("onlyoffice: connect timeout")
		case <-time.After(150 * time.Millisecond):
		}
	}
}

// writeAll дублирует кадр во ВСЕ активные сессии. Во время make-before-break-нахлёста их две
// (старая+новая) → когда Яндекс кикнет старую, новая уже получила эти данные → без разрыва.
func (c *OnlyOfficePacketConn) writeAll(msg []byte) int {
	c.mu.RLock()
	ss := make([]*ooSession, len(c.sessions))
	copy(ss, c.sessions)
	c.mu.RUnlock()
	n := 0
	for _, s := range ss {
		if err := s.write(msg); err != nil {
			oodbg("ws write err=%v", err)
			continue
		}
		n++
	}
	return n
}

func (c *OnlyOfficePacketConn) removeSession(o *ooSession) {
	c.mu.Lock()
	for i, s := range c.sessions {
		if s == o {
			c.sessions = append(c.sessions[:i], c.sessions[i+1:]...)
			break
		}
	}
	c.mu.Unlock()
}

func (c *OnlyOfficePacketConn) sessionLoop() {
	if c.exit {
		// Страница документа отдаёт новую комнату (key) примерно раз в 1–2 с.
		// Клиент забирает ту, что объявлена в момент его захода, и сидит в ней до drop.
		// Выход обязан быть уже в этой комнате, а не в той, что открыл 50 с назад.
		c.sessionLoopExit()
		return
	}
	c.sessionLoopClient()
}

// sessionLoopClient — одна комната за раз, ротация до того, как Яндекс пришлёт drop.
func (c *OnlyOfficePacketConn) sessionLoopClient() {
	var cur *ooSession
	backoff := 200 * time.Millisecond
	for c.running.Load() {
		s, err := c.launch()
		if err != nil {
			if cur == nil { // ни разу не подключились — держим connected=false для конструктора
				c.connected.Store(false)
			}
			c.sleepBackoff(&backoff)
			continue
		}
		backoff = 200 * time.Millisecond

		// make-before-break: новая сессия готова → атомарно переключаем запись KCP на неё,
		// старую гасим с нахлёстом (успеет отдать запоздавшие downstream-кадры). KCP-сессия
		// живёт на PacketConn, не на WS → переключение без разрыва.
		old := cur
		c.attach(s)
		cur = s

		dead := make(chan struct{})
		go func() { c.readLoop(s); c.removeSession(s); close(dead) }()
		if old != nil {
			go func(o *ooSession) {
				select {
				case <-time.After(sessOverlap): // нахлёст: пишем в обе, downstream старой ещё жив
				case <-c.closed:
				}
				c.removeSession(o)
				o.conn.Close()
			}(old)
		}

		select {
		case <-time.After(rotateEvery):
			// проактивная ротация: s остаётся текущей, пока не поднимется новая (без разрыва)
		case <-dead:
			// сессия умерла раньше срока (спорадический 4007/сеть) → быстрый реактивный реконнект
			c.sleepBackoff(&backoff)
		case <-c.closed:
			s.conn.Close()
			return
		}
	}
}

// sessionLoopExit входит в комнату, которую страница отдаёт прямо сейчас, и держит её.
// Следующий заход — только когда страница начала отдавать другую комнату.
// Комнату без клиента отпускаем через несколько секунд. Комнату, где клиент уже есть, держим.
func (c *OnlyOfficePacketConn) sessionLoopExit() {
	backoff := 200 * time.Millisecond
	var prev *ooSession
	var lastHold time.Time
	for c.running.Load() {
		info, err := c.fetchDocInfo()
		if err != nil {
			oodbg("exit fetch: %v", err)
			c.sleepBackoff(&backoff)
			continue
		}
		if c.hasKey(info.key) {
			// Сторож рестартит выход, если лог молчит >300 с. Стабильная комната
			// сама ничего не пишет, поэтому раз в минуту оставляем пульс.
			if time.Since(lastHold) > 45*time.Second {
				lastHold = time.Now()
				oodbg("exit hold key=%.16s", info.key)
			}
			select {
			case <-c.closed:
				return
			case <-time.After(time.Second):
			}
			continue
		}
		s, err := c.launchWith(info)
		if err != nil {
			oodbg("exit launch: %v", err)
			c.sleepBackoff(&backoff)
			continue
		}
		backoff = 200 * time.Millisecond
		c.attach(s)
		go func(s *ooSession) {
			c.readLoop(s)
			c.removeSession(s)
			s.conn.Close()
		}(s)
		if prev != nil {
			old := prev
			go func() {
				select {
				case <-c.closed:
					return
				case <-time.After(6 * time.Second):
				}
				if old.sawPeer.Load() {
					return
				}
				c.removeSession(old)
				old.conn.Close()
				oodbg("drop empty room key=%.16s", old.key)
			}()
		}
		prev = s
		select {
		case <-c.closed:
			return
		case <-time.After(time.Second):
		}
	}
}

func (c *OnlyOfficePacketConn) attach(s *ooSession) {
	c.mu.Lock()
	c.sessions = append(c.sessions, s)
	c.mu.Unlock()
	c.connected.Store(true)
}

func (c *OnlyOfficePacketConn) hasKey(key string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	for _, s := range c.sessions {
		if s.key == key {
			return true
		}
	}
	return false
}

func (c *OnlyOfficePacketConn) sleepBackoff(b *time.Duration) {
	select {
	case <-c.closed:
	case <-time.After(*b):
	}
	// covert-канал ДОЛЖЕН вставать почти мгновенно после дропа: Яндекс шлёт 4007 «drop»
	// каждые ~30-60с (особенно на мобильном), и пока выход/клиент переподключается, KCP-
	// сессия успевает умереть. Раскачка была до 15с → KCP гарантированно рвался. Кап 1с +
	// conv-демукс (KCP переживает 1-2с разрыв) → канал не рвётся между дропами.
	if *b *= 2; *b > 1*time.Second {
		*b = 1 * time.Second
	}
}

// launch: fetch config → Engine.IO polling open → socket.io connect+auth → WS upgrade → open doc.
func (c *OnlyOfficePacketConn) launch() (*ooSession, error) {
	info, err := c.fetchDocInfo()
	if err != nil {
		return nil, err
	}
	return c.launchWith(info)
}

func (c *OnlyOfficePacketConn) launchWith(info ooDocInfo) (*ooSession, error) {
	base := fmt.Sprintf("https://%s/%s/doc/%s/c/", info.host, ooVersion, info.key)
	setHdr := func(r *http.Request) {
		r.Header.Set("User-Agent", userAgent)
		r.Header.Set("Origin", info.origin)
		if info.cookie != "" {
			r.Header.Set("Cookie", info.cookie)
		}
	}
	// 1) Engine.IO open → sid
	r1, _ := http.NewRequest("GET", base+"?EIO=4&transport=polling", nil)
	setHdr(r1)
	resp1, err := c.follow.Do(r1)
	if err != nil {
		return nil, err
	}
	b1, _ := readAllClose(resp1)
	om := regexp.MustCompile(`0(\{.*?\})`).FindSubmatch(b1)
	if len(om) < 2 {
		return nil, fmt.Errorf("no engine.io open packet")
	}
	var openp struct {
		Sid string `json:"sid"`
	}
	if err := json.Unmarshal(om[1], &openp); err != nil || openp.Sid == "" {
		return nil, fmt.Errorf("bad open packet")
	}
	// 2) socket.io connect + auth
	r2, _ := http.NewRequest("POST", base+"?EIO=4&transport=polling&sid="+openp.Sid,
		strings.NewReader(fmt.Sprintf(`40{"token":"%s"}`, info.token)))
	setHdr(r2)
	r2.Header.Set("Content-Type", "text/plain;charset=UTF-8")
	resp2, err := c.follow.Do(r2)
	if err != nil {
		return nil, err
	}
	readAllClose(resp2)
	// 3) WS upgrade с тем же sid
	wsURL := fmt.Sprintf("wss://%s/%s/doc/%s/c/?EIO=4&transport=websocket&sid=%s", info.host, ooVersion, info.key, openp.Sid)
	d := websocket.Dialer{HandshakeTimeout: 15 * time.Second, ReadBufferSize: 1 << 20, WriteBufferSize: 1 << 20, NetDialContext: forceIPv4Dial}
	h := http.Header{}
	h.Set("User-Agent", userAgent)
	h.Set("Origin", info.origin)
	if info.cookie != "" {
		h.Set("Cookie", info.cookie)
	}
	conn, _, err := d.Dial(wsURL, h)
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(64 << 20)
	// Engine.IO transport upgrade probe
	conn.WriteMessage(websocket.TextMessage, []byte("2probe"))
	conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	ok := false
	for i := 0; i < 20; i++ {
		_, pm, perr := conn.ReadMessage()
		if perr != nil {
			conn.Close()
			return nil, perr
		}
		if string(pm) == "3probe" {
			ok = true
			break
		}
	}
	if !ok {
		conn.Close()
		return nil, fmt.Errorf("no 3probe")
	}
	conn.SetReadDeadline(time.Time{})
	conn.WriteMessage(websocket.TextMessage, []byte("5"))
	// свой userID НА КАЖДУЮ сессию: при make-before-break старая и новая должны уживаться
	// как РАЗНЫЕ соавторы (одинаковый userID → Яндекс кикнул бы старую сразу, был бы разрыв).
	uid := fmt.Sprintf("%010d", rand.Intn(1000000000))
	s := &ooSession{conn: conn, key: info.key, userID: uid, excel: ooExcel(info.fileType), opened: time.Now()}
	openMsg, _ := json.Marshal([]interface{}{"message", map[string]interface{}{
		"type":              "auth",
		"docid":             info.key,
		"token":             "fghhfgsjdgfjs",
		"user":              map[string]interface{}{"id": uid},
		"editorType":        0,
		"lastOtherSaveTime": -1,
		"permissions":       info.permissions,
		"openCmd": map[string]interface{}{
			"c": "open", "id": info.key, "userid": uid,
			"format": info.fileType, "url": info.url, "title": info.title, "lcid": 25,
		},
		"coEditingMode": "fast",
		"jwtOpen":       info.token,
	}})
	s.write([]byte("42" + string(openMsg)))
	oodbg("launch OK exit=%v self=%d host=%s key=%.16s sid=%.10s", c.exit, c.selfID, info.host, info.key, openp.Sid)
	return s, nil
}

func (c *OnlyOfficePacketConn) fetchDocInfo() (ooDocInfo, error) {
	req, _ := http.NewRequest("GET", c.publicURL, nil)
	req.Header.Set("User-Agent", userAgent)
	resp, err := c.follow.Do(req)
	if err != nil {
		return ooDocInfo{}, err
	}
	body, _ := readAllClose(resp)
	var cookies []string
	for _, ck := range resp.Cookies() {
		cookies = append(cookies, ck.Name+"="+ck.Value)
	}
	// Плюс логин-куки из jar (посеянные из YANDEX_COOKIE): нужны, чтобы WS-сессия
	// авторизованного дока открылась под тем же аккаунтом, что прошёл captcha-гейт.
	for _, ck := range c.follow.Jar.Cookies(resp.Request.URL) {
		cookies = append(cookies, ck.Name+"="+ck.Value)
	}
	m := ooClientConfigRe.FindSubmatch(body)
	if len(m) < 2 {
		return ooDocInfo{}, fmt.Errorf("client-config not found")
	}
	var cfg struct {
		OfficeActionData struct {
			BalancerURL  string `json:"balancer_url"`
			EditorConfig struct {
				Token    string `json:"token"`
				Document struct {
					Key         string          `json:"key"`
					FileType    string          `json:"fileType"`
					URL         string          `json:"url"`
					Title       string          `json:"title"`
					Permissions json.RawMessage `json:"permissions"`
				} `json:"document"`
			} `json:"editor_config"`
		} `json:"officeActionData"`
	}
	if err := json.Unmarshal(m[1], &cfg); err != nil {
		return ooDocInfo{}, err
	}
	oa := cfg.OfficeActionData
	doc := oa.EditorConfig.Document
	if oa.BalancerURL == "" || oa.EditorConfig.Token == "" || doc.Key == "" {
		return ooDocInfo{}, fmt.Errorf("officeActionData missing fields (не old-editor док?)")
	}
	perms := doc.Permissions
	if len(perms) == 0 {
		perms = json.RawMessage("{}")
	}
	return ooDocInfo{
		host:        strings.TrimPrefix(oa.BalancerURL, "https://"),
		key:         doc.Key,
		token:       oa.EditorConfig.Token,
		origin:      oa.BalancerURL,
		cookie:      strings.Join(cookies, "; "),
		fileType:    doc.FileType,
		url:         doc.URL,
		title:       doc.Title,
		permissions: perms,
	}, nil
}

func (c *OnlyOfficePacketConn) readLoop(s *ooSession) {
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-c.closed:
			s.conn.Close()
		case <-done:
		}
	}()
	s.conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
	s.conn.SetPongHandler(func(string) error { s.conn.SetReadDeadline(time.Now().Add(wsReadTimeout)); return nil })
	// WS-ping (как у volga): держит NAT-mapping на сотовой и ловит тихо умерший сокет —
	// pong продлевает read-deadline. WriteControl безопасен параллельно с writeLoop (gorilla).
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
				_ = s.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second))
			}
		}
	}()
	for c.running.Load() {
		_, msg, err := s.conn.ReadMessage()
		if err != nil {
			return
		}
		s.conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
		t := string(msg)
		if t == "2" { // Engine.IO ping → pong
			s.write([]byte("3"))
			continue
		}
		if len(t) < 2 || t[0] != '4' { // интересны только socket.io message (4x)
			oodbg("rx non-msg len=%d head=%.50q", len(t), t)
			continue
		}
		hasCur := strings.Contains(t, "cursor")
		if !hasCur && !strings.Contains(t, "saveChanges") {
			oodbg("rx msg no-cursor len=%d head=%.90q", len(t), t)
			continue
		}
		b64 := ooExtractPayload(t)
		if b64 == "" {
			oodbg("rx cursor no-payload len=%d head=%.120q", len(t), t)
			continue
		}
		if b64 == "---KA---" {
			continue
		}
		raw, err := base64.StdEncoding.DecodeString(b64)
		if err != nil || len(raw) < len(ooMagic)+4 {
			oodbg("rx b64 decerr=%v rawlen=%d b64len=%d", err, len(raw), len(b64))
			continue
		}
		if string(raw[:len(ooMagic)]) != ooMagic {
			oodbg("rx MAGIC-miss got=%q rawlen=%d", raw[:min(len(raw), 8)], len(raw))
			continue
		}
		sender := uint32(raw[len(ooMagic)])<<24 | uint32(raw[len(ooMagic)+1])<<16 | uint32(raw[len(ooMagic)+2])<<8 | uint32(raw[len(ooMagic)+3])
		if sender == c.selfID {
			oodbg("rx self-echo sender=%d", sender)
			continue // собственное эхо
		}
		npk := 0
		for _, pkt := range unpackBatch(raw[len(ooMagic)+4:]) {
			npk++
			select {
			case c.rx <- pkt:
			case <-c.closed:
			default: // переполнение — дропаем, KCP переспросит
			}
		}
		if npk > 0 {
			s.sawPeer.Store(true)
		}
		oodbg("rx OK sender=%d npkts=%d rawlen=%d", sender, npk, len(raw))
	}
}

func ooExtractPayload(msg string) string {
	if strings.Contains(msg, "saveChanges") {
		l := strings.Index(msg, ooSaveMarker)
		if l < 0 {
			return ""
		}
		l += len(ooSaveMarker)
		r := strings.IndexByte(msg[l:], '"')
		if r < 0 {
			return ""
		}
		return msg[l : l+r]
	}
	mm := ooCursorRe.FindStringSubmatch(msg)
	if len(mm) > 1 {
		return mm[1]
	}
	return ""
}

// writeLoop: собирает пакеты из tx, склеивает в пачку (packBatch), заворачивает в один
// cursor-кадр (MAGIC+selfID+batch → base64) и шлёт по WS.
func (c *OnlyOfficePacketConn) writeLoop() {
	var held []byte
	for {
		var first []byte
		if held != nil {
			first = held
			held = nil
		} else {
			select {
			case <-c.closed:
				return
			case first = <-c.tx:
			}
		}
		pkts := [][]byte{first}
		total := 2 + len(first)
		// len(frame) = magic + selfID + batch. Не выходим за ooMaxFrame, иначе
		// Яндекс молча дропает кадр и KCP крутит одну и ту же пачку.
		room := ooMaxFrame - (len(ooMagic) + 4)
	drain:
		for len(pkts) < batchMaxPkts && total < room {
			select {
			case p := <-c.tx:
				need := 2 + len(p)
				if total+need > room {
					held = p
					break drain
				}
				pkts = append(pkts, p)
				total += need
			default:
				break drain
			}
		}
		batch := packBatch(pkts)
		frame := make([]byte, 0, len(ooMagic)+4+len(batch))
		frame = append(frame, ooMagic...)
		frame = append(frame, byte(c.selfID>>24), byte(c.selfID>>16), byte(c.selfID>>8), byte(c.selfID))
		frame = append(frame, batch...)
		b64 := base64.StdEncoding.EncodeToString(frame)
		// Префикс курсора = размер СЫРОГО кадра в байтах ДО base64 (апстрим OpenFlux #165, 0.4.3,
		// 11.10.2026). Яндекс.Диск с начала октября 2026 трактует префикс как позицию курсора и
		// дропает cursor-кадр, если он не равен реальному размеру пакета → раньше с фиксированным
		// "18" терялись ВСЕ пакеты, Session-handshake истекал по таймауту (ЛТЕ умерла ~04.10).
		msg := []byte(fmt.Sprintf(`42["message",{"type":"cursor","cursor":"%d;%s"}]`, len(frame), b64))
		// KCP без congestion control выдаёт сотни cursor/с. Держим кадр, пока есть
		// живая сессия. Частоту длинных сегментов ограничивает пауза ниже.
		var n int
		for {
			n = c.writeAll(msg)
			if n > 0 {
				break
			}
			select {
			case <-c.closed:
				return
			case <-time.After(50 * time.Millisecond):
			}
		}
		oodbg("tx self=%d npkts=%d batch=%d b64=%d sessions=%d", c.selfID, len(pkts), len(batch), len(b64), n)
		// Короткие кадры — это в основном ACK. Их задержка срывает KCP.
		// Длинный сегмент троттлим: непрерывный поток полных кадров ловит 4004.
		// Выход шлёт реже клиента: 24.10 телефон на gHHP, выход выдавал ~13 полных
		// кадров/с и Яндекс отвечал 4004 каждые 17 с. 5 КБ ответа Reality при
		// таком темпе не успевает доехать до телефона.
		if len(batch) > 100 {
			pace := ooSendEvery
			if c.exit {
				now := time.Now()
				// Быстрый кусок только после паузы: один ответ Reality, не весь поток.
				if c.fastLeft <= 0 && (c.lastLong.IsZero() || now.Sub(c.lastLong) > 2*time.Second) {
					c.fastLeft = 8000
				}
				c.lastLong = now
				if c.fastLeft > 0 {
					pace = 20 * time.Millisecond
					c.fastLeft -= len(batch)
				} else {
					pace = 150 * time.Millisecond
				}
			}
			select {
			case <-c.closed:
				return
			case <-time.After(pace):
			}
		}
	}
}

func (c *OnlyOfficePacketConn) keepAliveLoop() {
	t := time.NewTicker(ooKAEvery)
	defer t.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
			c.writeAll([]byte(`42["message",{"type":"cursor","cursor":"18;---KA---"}]`))
		}
	}
}

// editorEvery — как часто слать saveChanges. 4007 приходит примерно на 30-й
// секунде, если соавтор шлёт только cursor (замер 24.10.2026). Каждые 1.5–4 с
// с обоих концов раздувают историю документа до сотен КБ и вместе с полными
// cursor-кадрами дают 4004 access deny (телефон на gHHP, 24.10.2026, шесть
// обрывов за две минуты). 12 с укладывается в окно 4007 и не кормит 4004.
// COVERT_EDITOR_EVERY — для прогона, который повторяет старый клиент.
func editorEvery() time.Duration {
	base := 12 * time.Second
	if v := os.Getenv("COVERT_EDITOR_EVERY"); v != "" {
		if d, err := time.ParseDuration(v); err == nil && d >= time.Second {
			base = d
		}
	}
	return base + time.Duration(rand.Intn(3000))*time.Millisecond
}

// editorLoop шлёт saveChanges от каждой живой сессии. Формат — как у редактора
// и у OpenFlux: без этого Яндекс рвёт соавтора кодом 4007.
func (c *OnlyOfficePacketConn) editorLoop() {
	seq := 0
	for {
		select {
		case <-c.closed:
			return
		case <-time.After(editorEvery()):
		}
		c.mu.RLock()
		ss := make([]*ooSession, len(c.sessions))
		copy(ss, c.sessions)
		c.mu.RUnlock()
		for _, s := range ss {
			if s.userID == "" {
				continue
			}
			seq++
			if err := s.write(ooSaveChanges(s.userID, s.excel, seq)); err != nil {
				oodbg("ws write err=%v", err)
			}
		}
	}
}

// ---- net.PacketConn ----

func (c *OnlyOfficePacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	var timer *time.Timer
	var tch <-chan time.Time
	if dl := c.rdDeadline.Load(); dl != nil && !dl.IsZero() {
		d := time.Until(*dl)
		if d <= 0 {
			return 0, nil, os_timeout{}
		}
		timer = time.NewTimer(d)
		tch = timer.C
		defer timer.Stop()
	}
	select {
	case <-c.closed:
		return 0, nil, net.ErrClosed
	case <-tch:
		return 0, nil, os_timeout{}
	case b := <-c.rx:
		n := copy(p, b)
		if c.exit {
			return n, convAddr(b), nil // демультиплекс KCP-листенера по conv
		}
		return n, kcpDialAddr, nil
	}
}

func (c *OnlyOfficePacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	default:
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

func (c *OnlyOfficePacketConn) Close() error {
	c.once.Do(func() {
		c.running.Store(false)
		close(c.closed)
	})
	return nil
}

func (c *OnlyOfficePacketConn) LocalAddr() net.Addr               { return kcpDialAddr }
func (c *OnlyOfficePacketConn) SetDeadline(t time.Time) error     { c.rdDeadline.Store(&t); return nil }
func (c *OnlyOfficePacketConn) SetReadDeadline(t time.Time) error { c.rdDeadline.Store(&t); return nil }
func (c *OnlyOfficePacketConn) SetWriteDeadline(time.Time) error  { return nil }

// newCarrier выбирает носитель. По умолчанию — OnlyOffice. volga — старый фолбэк.
// cups — комната cups.online (поле carrier, COVERT_CARRIER или адрес cups.online).
func newCarrier(ctx context.Context, exit bool, publicURL string, timeout time.Duration) (net.PacketConn, error) {
	return newCarrierKind(ctx, exit, publicURL, timeout, resolveKind("", publicURL))
}

func newCarrierKind(ctx context.Context, exit bool, publicURL string, timeout time.Duration, kind string) (net.PacketConn, error) {
	switch resolveKind(kind, publicURL) {
	case "volga":
		return NewVolgaPacketConn(ctx, exit, publicURL, timeout)
	case "cups":
		return NewCupsPacketConn(ctx, exit, publicURL, timeout)
	default:
		return NewOnlyOfficePacketConn(ctx, exit, publicURL, timeout)
	}
}
