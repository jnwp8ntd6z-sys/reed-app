package covert

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/url"
	"strings"
	"time"
)

// Полосатый кадр Cups. Один порядковый номер на направление, подтверждение
// накопительное. nonce отличает нашу сторону от пары и от чужого телефона
// в тех же комнатах: свой nonce — это эхо, чужой при живой паре игнорируется.

const (
	stripeMagic0 = 'R'
	stripeMagic1 = 'S'
	stripeVer    = 1

	stripeFlagData = 1
	stripeFlagAck  = 2

	stripeHeader = 20

	// Сколько вызовов комнаты одна полоса держит, не дожидаясь следующего.
	// Ответ RPC — это часы сервера: 8 КБ он принимает за 0,4–1,7 с.
	// Четыре вызова на комнату и восемь комнат на короткой пачке дали
	// около 209 КБ/с. Восемь вызовов на комнату уже медленнее.
	// Пока вызов ждёт ответ, мьютекс записи свободен и pong проходит.
	stripeWindow = 4
	stripeLanes  = 8

	// Сколько кадров можно отправить, не дожидаясь подтверждения пары.
	// Темп одной комнаты — 8 КБ / 12 мс. Три кадра при RTT 0,3 с дают ~80 КБ/с.
	// 64 кадра — это уже полмегабайта в очереди комнаты: RTT раздувается,
	// повтор бьёт по тем же кадрам. 32 кадра держат темп при RTT до ~0,4 с
	// и не заливают комнату.
	stripeFlight = 32

	stripeStall = 20 * time.Second

	// Общий темп всех комнат. По комнате быстрее 12 мс сервер рвёт сокет,
	// а пачка сразу на все 8 комнат он молча выбрасывает. 20 мс на кадр
	// со всех полос — около 400 КБ/с, без залпа.
	stripeGlobalPace = 20 * time.Millisecond
	stripeQuiet      = 60 * time.Second
)

type stripeFrame struct {
	flags    byte
	nonce    uint32
	seq      uint32
	ack      uint32
	ackNonce uint32
	payload  []byte
}

func encodeStripe(f stripeFrame) []byte {
	b := make([]byte, stripeHeader+len(f.payload))
	b[0], b[1] = stripeMagic0, stripeMagic1
	b[2] = stripeVer
	b[3] = f.flags
	binary.BigEndian.PutUint32(b[4:8], f.nonce)
	binary.BigEndian.PutUint32(b[8:12], f.seq)
	binary.BigEndian.PutUint32(b[12:16], f.ack)
	binary.BigEndian.PutUint32(b[16:20], f.ackNonce)
	copy(b[stripeHeader:], f.payload)
	return b
}

func decodeStripe(b []byte) (stripeFrame, bool) {
	if len(b) < stripeHeader || b[0] != stripeMagic0 || b[1] != stripeMagic1 || b[2] != stripeVer {
		return stripeFrame{}, false
	}
	f := stripeFrame{
		flags:    b[3],
		nonce:    binary.BigEndian.Uint32(b[4:8]),
		seq:      binary.BigEndian.Uint32(b[8:12]),
		ack:      binary.BigEndian.Uint32(b[12:16]),
		ackNonce: binary.BigEndian.Uint32(b[16:20]),
	}
	if f.flags&stripeFlagData != 0 && len(b) > stripeHeader {
		f.payload = append([]byte(nil), b[stripeHeader:]...)
	}
	return f, true
}

// stripeAssembler собирает полезную нагрузку строго по seq.
type stripeAssembler struct {
	expect uint32
	hold   map[uint32][]byte
	out    []byte
}

func (a *stripeAssembler) push(seq uint32, p []byte) {
	if a.hold == nil {
		a.hold = map[uint32][]byte{}
	}
	if seq < a.expect {
		return
	}
	if _, ok := a.hold[seq]; ok {
		return
	}
	a.hold[seq] = append([]byte(nil), p...)
	for {
		b, ok := a.hold[a.expect]
		if !ok {
			return
		}
		a.out = append(a.out, b...)
		delete(a.hold, a.expect)
		a.expect++
	}
}

func (a *stripeAssembler) reset() {
	a.expect = 0
	a.hold = map[uint32][]byte{}
	a.out = nil
}

// nonceGate принимает кадры одной пары. Второй телефон в тех же комнатах
// не перебивает живую пару. После stripeQuiet тишины новый nonce занимает место.
type nonceGate struct {
	cur  uint32
	last time.Time
	have bool
}

func (g *nonceGate) accept(nonce uint32, now time.Time) (ok bool, reset bool) {
	if !g.have {
		g.cur = nonce
		g.last = now
		g.have = true
		return true, false
	}
	if nonce == g.cur {
		g.last = now
		return true, false
	}
	if now.Sub(g.last) < stripeQuiet {
		return false, false
	}
	g.cur = nonce
	g.last = now
	return true, true
}

// laneWindow — не больше stripeWindow RPC в полёте на одну комнату.
type laneWindow struct {
	n int
}

func (w *laneWindow) tryAcquire() bool {
	if w.n >= stripeWindow {
		return false
	}
	w.n++
	return true
}

func (w *laneWindow) release() {
	if w.n > 0 {
		w.n--
	}
}

func roomIDsFromPublicURL(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" || strings.EqualFold(raw, "create") {
		return nil
	}
	if strings.Contains(raw, ",") {
		var all []string
		seen := map[string]struct{}{}
		for _, part := range strings.Split(raw, ",") {
			for _, id := range roomIDsFromPublicURL(strings.TrimSpace(part)) {
				if _, ok := seen[id]; ok {
					continue
				}
				seen[id] = struct{}{}
				all = append(all, id)
			}
		}
		return all
	}
	if u, err := url.Parse(raw); err == nil && u.Host != "" {
		if ids := unpackRoomIDs(u.Query().Get("rooms")); len(ids) > 0 {
			return ids
		}
		if id := strings.ToLower(u.Query().Get("room")); cupsUUID.MatchString(id) {
			return []string{id}
		}
	}
	if cupsUUID.MatchString(raw) {
		return []string{strings.ToLower(raw)}
	}
	if ids := unpackRoomIDs(raw); len(ids) > 0 {
		return ids
	}
	return nil
}

func cupsRoomsURL(ids []string) string {
	raw, _ := json.Marshal(ids)
	return cupsBaseURL + "?rooms=" + base64.RawURLEncoding.EncodeToString(raw)
}

func dedupeIDs(ids []string) []string {
	seen := map[string]struct{}{}
	var out []string
	for _, id := range ids {
		id = strings.ToLower(strings.TrimSpace(id))
		if !cupsUUID.MatchString(id) {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		out = append(out, id)
	}
	return out
}
