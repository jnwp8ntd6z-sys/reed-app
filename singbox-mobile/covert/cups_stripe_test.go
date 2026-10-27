package covert

import (
	"bytes"
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

func TestOldFrameDoesNotYieldRoom(t *testing.T) {
	s := testStripe()
	s.onFrame(dataFrame(2, 0, "AA"))
	s.mu.Lock()
	s.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now().Add(-stripePeerDead - time.Second)}
	s.gate.last = time.Now().Add(-20 * time.Second)
	s.mu.Unlock()
	s.onFrame(dataFrame(3, 0, "ZZ"))
	if s.failed != nil {
		t.Fatalf("old frame yielded a live room: %v", s.failed)
	}
	if string(s.rbuf) != "AA" {
		t.Fatalf("foreign bytes landed: %q", s.rbuf)
	}
	s.mu.Lock()
	s.gate.last = time.Now().Add(-stripeQuiet - time.Second)
	s.mu.Unlock()
	s.onFrame(dataFrame(3, 0, "ZZ"))
	if s.failed == nil || !strings.Contains(s.failed.Error(), "peer replaced") {
		t.Fatalf("quiet peer kept the rooms: %v", s.failed)
	}
}

func TestForeignNonceWaitsOutLivePeer(t *testing.T) {
	s := testStripe()
	s.onFrame(dataFrame(2, 0, "AA"))
	s.mu.Lock()
	s.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now().Add(-stripePeerDead - time.Second)}
	s.gate.last = time.Now().Add(-time.Second)
	s.mu.Unlock()
	s.onFrame(dataFrame(3, 0, "ZZ"))
	if s.failed != nil {
		t.Fatal(s.failed)
	}
	if string(s.rbuf) != "AA" {
		t.Fatalf("live peer lost its bytes: %q", s.rbuf)
	}

	quiet := testStripe()
	quiet.onFrame(dataFrame(2, 0, "AA"))
	quiet.mu.Lock()
	quiet.gate.last = time.Now().Add(-10 * time.Second)
	quiet.mu.Unlock()
	quiet.onFrame(dataFrame(3, 0, "ZZ"))
	if quiet.failed != nil {
		t.Fatal(quiet.failed)
	}

	gone := testStripe()
	gone.onFrame(dataFrame(2, 0, "AA"))
	gone.mu.Lock()
	gone.gate.last = time.Now().Add(-stripeStall - time.Second)
	gone.mu.Unlock()
	gone.onFrame(dataFrame(3, 0, "ZZ"))
	if gone.failed != nil {
		t.Fatalf("one missed keepalive yielded the room: %v", gone.failed)
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

func TestAckUnknownNonceStillClears(t *testing.T) {
	s := testStripe()
	s.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now()}
	s.onFrame(stripeFrame{flags: stripeFlagAck, nonce: 2, ack: 1})
	if _, ok := s.sent[0]; ok {
		t.Fatal("ack without peer nonce left the frame in flight")
	}

	other := testStripe()
	other.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now()}
	other.onFrame(dataFrame(2, 0, "AA"))
	other.onFrame(stripeFrame{flags: stripeFlagAck, nonce: 2, ack: 1, ackNonce: 99})
	if _, ok := other.sent[0]; !ok {
		t.Fatal("foreign ack nonce cleared our frame")
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
	stuckBuf.peerAt = time.Now().Add(-stripePeerDead - time.Second)
	stuckBuf.obuf = []byte("x")
	stuckBuf.mu.Unlock()
	stuckBuf.pump()
	if stuckBuf.failed == nil || !strings.Contains(stuckBuf.failed.Error(), "stall") {
		t.Fatalf("obuf: %v", stuckBuf.failed)
	}

	stuckSent := testStripe()
	stuckSent.gate.have = true
	stuckSent.gate.cur = 2
	stuckSent.peerAt = time.Now().Add(-stripePeerDead - time.Second)
	stuckSent.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now().Add(-stripePeerDead - time.Second)}
	stuckSent.pump()
	if stuckSent.failed == nil || !strings.Contains(stuckSent.failed.Error(), "stall") {
		t.Fatalf("inflight: %v", stuckSent.failed)
	}

	// 15:49:36: старый кадр ещё в полёте, но пара только что подтвердила другие.
	// Такой сессии обрыв не нужен.
	live := testStripe()
	live.gate.have = true
	live.gate.cur = 2
	live.peerAt = time.Now()
	live.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: time.Now().Add(-stripeStall - time.Second)}
	live.pump()
	if live.failed != nil {
		t.Fatalf("live peer: %v", live.failed)
	}
}

func testLanes(s *CupsStripe, n int) {
	for i := 0; i < n; i++ {
		s.lanes = append(s.lanes, &cupsLane{sendCh: make(chan laneJob, stripeWindow)})
		s.lanes[i].connected.Store(true)
	}
}

func TestHandshakeDoesNotFlood(t *testing.T) {
	s := testStripe()
	testLanes(s, stripeLanes)
	s.obuf = bytes.Repeat([]byte("a"), stripePayloadMax()*20)
	s.pump()
	if got := len(s.sent); got == 0 || got > stripeFlightProbe {
		t.Fatalf("handshake offered %d frames, want 1..%d", got, stripeFlightProbe)
	}
}

func TestFlightOpensAfterAcks(t *testing.T) {
	s := testStripe()
	testLanes(s, stripeLanes)
	s.acked.Store(4)
	s.obuf = bytes.Repeat([]byte("a"), stripePayloadMax()*40)
	s.pump()
	if got := len(s.sent); got <= stripeFlightProbe {
		t.Fatalf("open window offered %d", got)
	}
}

func TestSlowRTOShrinksFlight(t *testing.T) {
	s := testStripe()
	testLanes(s, stripeLanes)
	s.acked.Store(8)
	s.rtoNanos.Store(int64(3 * time.Second))
	s.obuf = bytes.Repeat([]byte("a"), stripePayloadMax()*20)
	s.pump()
	if got := len(s.sent); got == 0 || got > stripeFlightProbe {
		t.Fatalf("slow link offered %d", got)
	}
}

func TestPartialWriteWaitsToFill(t *testing.T) {
	s := testStripe()
	testLanes(s, 1)
	s.acked.Store(4)
	if _, err := s.Write([]byte("hi")); err != nil {
		t.Fatal(err)
	}
	if s.obufSince.IsZero() {
		t.Fatal("write did not start the coalesce clock")
	}
	s.pump()
	if len(s.sent) != 0 {
		t.Fatal("fresh partial frame was sent")
	}
	s.mu.Lock()
	s.obufSince = time.Now().Add(-stripeCoalesce - time.Millisecond)
	s.mu.Unlock()
	s.pump()
	if len(s.sent) != 1 || string(s.sent[0].payload) != "hi" {
		t.Fatalf("partial after wait: %d", len(s.sent))
	}
}

func TestFullFrameDoesNotWait(t *testing.T) {
	s := testStripe()
	testLanes(s, 1)
	s.acked.Store(4)
	s.obuf = bytes.Repeat([]byte("a"), stripePayloadMax())
	s.obufSince = time.Now()
	s.pump()
	if len(s.sent) != 1 {
		t.Fatalf("full frame waited: %d", len(s.sent))
	}
}

func TestRPCSuccessRestartsRetxClock(t *testing.T) {
	s := testStripe()
	old := time.Now().Add(-2 * time.Second)
	s.sent[0] = &stripeSent{seq: 0, payload: []byte("x"), first: old, sent: old, busy: true}
	s.rpcFinished(0, nil)
	f := s.sent[0]
	if f.busy || time.Since(f.sent) > time.Second {
		t.Fatalf("busy=%v age=%s", f.busy, time.Since(f.sent))
	}
}
