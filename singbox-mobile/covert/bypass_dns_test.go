package covert

import (
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

func TestParseDNSA(t *testing.T) {
	// question example.com, one A answer 1.2.3.4, name compressed
	msg := []byte{
		0x00, 0x01, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
		7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0,
		0x00, 0x01, 0x00, 0x01,
		0xC0, 0x0C,
		0x00, 0x01, 0x00, 0x01,
		0x00, 0x00, 0x00, 0x3C,
		0x00, 0x04,
		1, 2, 3, 4,
	}
	ips := parseDNSA(msg)
	if len(ips) != 1 || !ips[0].Equal(net.IPv4(1, 2, 3, 4)) {
		t.Fatalf("got %v", ips)
	}
	q := buildDNSQuery("disk.yandex.ru")
	if binary.BigEndian.Uint16(q[4:6]) != 1 {
		t.Fatalf("qdcount %d", binary.BigEndian.Uint16(q[4:6]))
	}
}

func TestCarrierResolveYandex(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	ips, err := carrierResolveIPv4(ctx, "disk.yandex.ru")
	if err != nil {
		t.Fatal(err)
	}
	for _, ip := range ips {
		if ip.To4() == nil {
			t.Fatalf("not v4: %s", ip)
		}
	}
	t.Log(ips)
}
