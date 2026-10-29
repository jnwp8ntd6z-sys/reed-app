package covert

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestFluxPullSplitAndZero(t *testing.T) {
	pktA := bytes.Repeat([]byte("a"), 30)
	pktB := bytes.Repeat([]byte("b"), 10)
	var buf []byte
	for _, pkt := range [][]byte{pktA, pktB} {
		hdr := []byte{byte(len(pkt) >> 8), byte(len(pkt))}
		buf = append(buf, hdr...)
		buf = append(buf, pkt...)
	}
	first, rest := fluxPull(buf[:5])
	if len(first) != 0 || len(rest) != 5 {
		t.Fatalf("partial first=%d rest=%d", len(first), len(rest))
	}
	got, rest := fluxPull(append(rest, buf[5:]...))
	if len(got) != 2 || len(rest) != 0 {
		t.Fatalf("got %d rest %d", len(got), len(rest))
	}
	if !bytes.Equal(got[0], pktA) || !bytes.Equal(got[1], pktB) {
		t.Fatal("payload")
	}
	broken := append([]byte{0, 0}, []byte("junk")...)
	got, rest = fluxPull(broken)
	if len(got) != 0 || rest != nil {
		t.Fatalf("zero length got=%d rest=%v", len(got), rest)
	}
}

func TestFluxChunkMatchesCursorFraming(t *testing.T) {
	pkt := []byte{1, 2, 3, 4, 5, 9}
	inner := make([]byte, 2+len(pkt))
	inner[0] = 0
	inner[1] = byte(len(pkt))
	copy(inner[2:], pkt)
	wire := make([]byte, 2+len(inner))
	wire[0] = 0
	wire[1] = byte(len(inner))
	copy(wire[2:], inner)
	raw, err := json.Marshal(cupsCursorPairs(wire))
	if err != nil {
		t.Fatal(err)
	}
	var pairs []map[string]any
	if err := json.Unmarshal(raw, &pairs); err != nil {
		t.Fatal(err)
	}
	chunk, ok := cupsChunkFromCursors(asCursors(pairs))
	if !ok || !bytes.Equal(chunk, inner) {
		t.Fatalf("chunk ok=%v %x", ok, chunk)
	}
	got, rest := fluxPull(chunk)
	if len(got) != 1 || len(rest) != 0 || !bytes.Equal(got[0], pkt) {
		t.Fatal("pull")
	}
}

func asCursors(pairs []map[string]any) []any {
	out := make([]any, len(pairs))
	for i := range pairs {
		out[i] = pairs[i]
	}
	return out
}
