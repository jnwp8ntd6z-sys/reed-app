package covert

import (
	"context"
	"strings"
	"testing"
	"time"
)

func testStripe() *CupsStripe {
	ctx, cancel := context.WithCancel(context.Background())
	return &CupsStripe{
		ctx:      ctx,
		cancel:   cancel,
		nonce:    0x11111111,
		sent:     map[uint32]*stripeSent{},
		self:     map[string]struct{}{},
		kick:     make(chan struct{}, 1),
		readable: make(chan struct{}, 1),
		prog:     time.Now(),
	}
}

func dataFrame(nonce, seq uint32, payload string) stripeFrame {
	return stripeFrame{
		flags:   stripeFlagData | stripeFlagAck,
		nonce:   nonce,
		seq:     seq,
		payload: []byte(payload),
	}
}

func TestStripeReorderDupHole(t *testing.T) {
	s := testStripe()
	s.onFrame(dataFrame(2, 1, "BB"))
	if len(s.rbuf) != 0 {
		t.Fatal("hole delivered early")
	}
	s.onFrame(dataFrame(2, 0, "AA"))
	if string(s.rbuf) != "AABB" {
		t.Fatalf("reorder: %q", s.rbuf)
	}
	s.needAck = false
	s.onFrame(dataFrame(2, 0, "AA"))
	if string(s.rbuf) != "AABB" {
		t.Fatalf("dup changed buffer: %q", s.rbuf)
	}
	if !s.needAck {
		t.Fatal("duplicate must ask for another ack")
	}
	s.onFrame(dataFrame(s.nonce, 2, "NO"))
	if string(s.rbuf) != "AABB" {
		t.Fatal("own nonce was accepted")
	}
}

func TestStripeForeignNonce(t *testing.T) {
	s := testStripe()
	s.onFrame(dataFrame(2, 0, "AA"))
	s.onFrame(dataFrame(3, 0, "ZZ"))
	if string(s.rbuf) != "AA" {
		t.Fatalf("foreign nonce while fresh: %q", s.rbuf)
	}
	if s.failed != nil {
		t.Fatal(s.failed)
	}
	s.mu.Lock()
	s.gate.last = time.Now().Add(-stripeQuiet - time.Second)
	s.mu.Unlock()
	s.onFrame(dataFrame(3, 0, "ZZ"))
	if s.failed == nil || !strings.Contains(s.failed.Error(), "peer replaced") {
		t.Fatalf("quiet foreign nonce: %v", s.failed)
	}
}

func TestFlightCoversSlowAck(t *testing.T) {
	s := testStripe()
	for i := 0; i < stripeLanes; i++ {
		s.lanes = append(s.lanes, &cupsLane{})
	}
	capBytes := s.flightCap()
	const ackRTT = 0.5
	rate := float64(capBytes) / ackRTT
	if rate < 350*1024 {
		t.Fatalf("flight %d B is %.0f B/s at a 0.5s ack, want >= 350KB/s", capBytes, rate)
	}
}

func TestOfferUsesEveryLane(t *testing.T) {
	s := testStripe()
	for i := 0; i < 2; i++ {
		s.lanes = append(s.lanes, &cupsLane{sendCh: make(chan laneJob, stripeWindow)})
	}
	s.lanes[0].connected.Store(true)
	s.lanes[1].connected.Store(true)
	seen := map[int]int{}
	for i := 0; i < stripeWindow*2; i++ {
		lane, ok := s.offerRaw(laneJob{raw: []byte{1}})
		if !ok {
			t.Fatalf("offer %d failed", i)
		}
		seen[lane]++
	}
	if seen[0] == 0 || seen[1] == 0 {
		t.Fatalf("lanes %v", seen)
	}
	if _, ok := s.offerRaw(laneJob{raw: []byte{1}}); ok {
		t.Fatal("both lanes full and offer still succeeded")
	}
	s.lanes[0].connected.Store(false)
	// Очередь первой комнаты полна, но она уже не жива. Вторая тоже полна.
	if _, ok := s.offerRaw(laneJob{raw: []byte{2}}); ok {
		t.Fatal("full live lane accepted another frame")
	}
}

func TestLaneWindow(t *testing.T) {
	var w laneWindow
	for i := 0; i < stripeWindow; i++ {
		if !w.tryAcquire() {
			t.Fatalf("acquire %d", i)
		}
	}
	if w.tryAcquire() {
		t.Fatal("window let an extra rpc through")
	}
	w.release()
	if !w.tryAcquire() {
		t.Fatal("release did not free a slot")
	}
}

func TestCupsRoomsURLRoundTrip(t *testing.T) {
	ids := []string{
		"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
		"bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
	}
	raw := cupsRoomsURL(ids)
	if !strings.Contains(raw, "rooms=") {
		t.Fatal(raw)
	}
	got := roomIDsFromPublicURL(raw)
	if len(got) != 2 || got[0] != ids[0] || got[1] != ids[1] {
		t.Fatalf("round trip: %#v", got)
	}
	one := cupsBaseURL + "?room=" + ids[0]
	got = roomIDsFromPublicURL(one)
	if len(got) != 1 || got[0] != ids[0] {
		t.Fatalf("single room: %#v", got)
	}
	if roomIDFromPublicURL(raw) != ids[0] {
		t.Fatal("legacy parser dropped the first id")
	}
}

func TestTakePongNotBehindData(t *testing.T) {
	pong := make(chan struct{}, 1)
	data := make(chan []byte, 1)
	data <- []byte("frame")
	pong <- struct{}{}
	if !takePong(pong) {
		t.Fatal("queued pong was not taken")
	}
	if takePong(pong) {
		t.Fatal("empty pong queue returned a pong")
	}
	select {
	case b := <-data:
		if string(b) != "frame" {
			t.Fatal(string(b))
		}
	default:
		t.Fatal("pong reader consumed the data queue")
	}
}

func TestStripeCodec(t *testing.T) {
	in := stripeFrame{flags: stripeFlagData | stripeFlagAck, nonce: 9, seq: 3, ack: 1, ackNonce: 4, payload: []byte("hi")}
	out, ok := decodeStripe(encodeStripe(in))
	if !ok || out.nonce != 9 || out.seq != 3 || out.ack != 1 || out.ackNonce != 4 || string(out.payload) != "hi" {
		t.Fatalf("%+v %v", out, ok)
	}
	if _, ok := decodeStripe([]byte("nope")); ok {
		t.Fatal("short frame decoded")
	}
}

func TestStripeStallIdleVersusStuck(t *testing.T) {
	idle := testStripe()
	idle.mu.Lock()
	idle.prog = time.Now().Add(-stripeStall - time.Second)
	idle.mu.Unlock()
	idle.pump()
	if idle.failed != nil {
		t.Fatal("silence with nothing in flight closed the session")
	}

	alone := testStripe()
	alone.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now().Add(-stripeStall - time.Second)}
	alone.pump()
	if alone.failed != nil {
		t.Fatal("unacked keepalive before the peer appears must keep the session")
	}

	stuckBuf := testStripe()
	stuckBuf.mu.Lock()
	stuckBuf.gate.have = true
	stuckBuf.gate.cur = 2
	stuckBuf.prog = time.Now().Add(-stripeStall - time.Second)
	stuckBuf.obuf = []byte("x")
	stuckBuf.mu.Unlock()
	stuckBuf.pump()
	if stuckBuf.failed == nil || !strings.Contains(stuckBuf.failed.Error(), "stall") {
		t.Fatalf("obuf: %v", stuckBuf.failed)
	}

	stuckSent := testStripe()
	stuckSent.gate.have = true
	stuckSent.gate.cur = 2
	stuckSent.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now().Add(-stripeStall - time.Second)}
	stuckSent.pump()
	if stuckSent.failed == nil || !strings.Contains(stuckSent.failed.Error(), "stall") {
		t.Fatalf("inflight: %v", stuckSent.failed)
	}
}
