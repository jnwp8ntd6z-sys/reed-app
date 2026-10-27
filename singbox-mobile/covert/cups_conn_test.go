package covert

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"testing"
)

func TestCupsCursorRoundTrip(t *testing.T) {
	pkt := []byte{0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01, 0x02, 0x7f}
	inner := make([]byte, 2+len(pkt))
	binary.BigEndian.PutUint16(inner[:2], uint16(len(pkt)))
	copy(inner[2:], pkt)
	wire := make([]byte, 2+len(inner))
	binary.BigEndian.PutUint16(wire[:2], uint16(len(inner)))
	copy(wire[2:], inner)

	raw, err := json.Marshal(cupsCursorPairs(wire))
	if err != nil {
		t.Fatal(err)
	}
	var pairs []map[string]any
	if err := json.Unmarshal(raw, &pairs); err != nil {
		t.Fatal(err)
	}
	asAny := make([]any, len(pairs))
	for i := range pairs {
		asAny[i] = pairs[i]
	}
	chunk, ok := cupsChunkFromCursors(asAny)
	if !ok {
		t.Fatal("chunk")
	}
	if !bytes.Equal(chunk, inner) {
		t.Fatalf("chunk %x want %x", chunk, inner)
	}
	ln := int(binary.BigEndian.Uint16(chunk[:2]))
	if !bytes.Equal(chunk[2:2+ln], pkt) {
		t.Fatal("packet")
	}
}

func TestCupsPageFields(t *testing.T) {
	const doc = `<meta content="tok-1" name="centrifuge-connection-token">` +
		`<meta name="centrifuge-connection-url" content="https://interview.cups.online/centrifugo/connection">` +
		`<meta name="centrifuge-subscription-token-url" content="/centrifuge/subscription/token/">` +
		`<div data-room="{&quot;uuid&quot;:&quot;11111111-2222-3333-4444-555555555555&quot;,&quot;code&quot;:&quot;x&quot;}"` +
		` data-user="{&quot;uuid&quot;:&quot;AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE&quot;}">`
	if cupsMeta(doc, "centrifuge-connection-token") != "tok-1" {
		t.Fatal("token order")
	}
	if cupsMeta(doc, "centrifuge-connection-url") != "https://interview.cups.online/centrifugo/connection" {
		t.Fatal("conn url")
	}
	if cupsDataUUID(doc, "data-room") != "11111111-2222-3333-4444-555555555555" {
		t.Fatal("room")
	}
	if cupsDataUUID(doc, "data-user") != "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" {
		t.Fatal("user")
	}
	sub := absURL("https://interview.cups.online/live-coding/?room=11111111-2222-3333-4444-555555555555",
		cupsMeta(doc, "centrifuge-subscription-token-url"))
	if sub != "https://interview.cups.online/centrifuge/subscription/token/" {
		t.Fatal(sub)
	}
	ws := cupsSocketURL(cupsMeta(doc, "centrifuge-connection-url"))
	if ws != "wss://interview.cups.online/centrifugo/connection/websocket" {
		t.Fatal(ws)
	}
}

func TestCupsBatchTwoPackets(t *testing.T) {
	pkts := [][]byte{[]byte("abc"), []byte("defg")}
	var inner []byte
	for _, pkt := range pkts {
		var hdr [2]byte
		binary.BigEndian.PutUint16(hdr[:], uint16(len(pkt)))
		inner = append(inner, hdr[:]...)
		inner = append(inner, pkt...)
	}
	wire := make([]byte, 2+len(inner))
	binary.BigEndian.PutUint16(wire[:2], uint16(len(inner)))
	copy(wire[2:], inner)
	raw, err := json.Marshal(cupsCursorPairs(wire))
	if err != nil {
		t.Fatal(err)
	}
	var pairs []map[string]any
	if err := json.Unmarshal(raw, &pairs); err != nil {
		t.Fatal(err)
	}
	asAny := make([]any, len(pairs))
	for i := range pairs {
		asAny[i] = pairs[i]
	}
	chunk, ok := cupsChunkFromCursors(asAny)
	if !ok || !bytes.Equal(chunk, inner) {
		t.Fatalf("chunk ok=%v", ok)
	}
	var got [][]byte
	buf := append([]byte(nil), chunk...)
	for len(buf) >= 2 {
		n := int(binary.BigEndian.Uint16(buf[:2]))
		if n == 0 || 2+n > len(buf) {
			t.Fatal("split")
		}
		got = append(got, append([]byte(nil), buf[2:2+n]...))
		buf = buf[2+n:]
	}
	if len(got) != 2 || !bytes.Equal(got[0], pkts[0]) || !bytes.Equal(got[1], pkts[1]) {
		t.Fatalf("pkts %q", got)
	}
}

func TestRoomIDFromPublicURL(t *testing.T) {
	id := "11111111-2222-3333-4444-555555555555"
	if roomIDFromPublicURL("https://interview.cups.online/live-coding/?room="+id) != id {
		t.Fatal("query")
	}
	if roomIDFromPublicURL("create") != "" || roomIDFromPublicURL("") != "" {
		t.Fatal("create")
	}
	if resolveKind("", "https://interview.cups.online/live-coding/?room="+id) != "cups" {
		t.Fatal("kind")
	}
	if resolveKind("volga", "https://interview.cups.online/live-coding/?room="+id) != "volga" {
		t.Fatal("explicit")
	}
	if mtuForCarrier("cups") != 1200 || mtuForCarrier("") != 280 {
		t.Fatal("mtu")
	}
}
