package covert

// Экспортируемый клиент covert-канала — для тест-харнеса и переиспользования.
// Тот же стек, что в Outbound.ensureSession: VolgaPacketConn → KCP → smux.

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"time"

	kcp "github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

type Client struct {
	sess *smux.Session
	pc   *VolgaPacketConn
}

// Dial поднимает covert-сессию (клиент) и мультиплексор поверх неё.
func Dial(ctx context.Context, publicURL string) (*Client, error) {
	pc, err := NewVolgaPacketConn(ctx, false, publicURL, 45*time.Second)
	if err != nil {
		return nil, err
	}
	kcpConn, err := kcp.NewConn2(kcpDialAddr, nil, 0, 0, pc)
	if err != nil {
		pc.Close()
		return nil, err
	}
	kcpConn.SetNoDelay(0, 40, 0, 1)
	kcpConn.SetWindowSize(128, 256)
	kcpConn.SetMtu(1200)
	kcpConn.SetStreamMode(true)
	kcpConn.SetACKNoDelay(false)
	sess, err := smux.Client(kcpConn, smuxConfig())
	if err != nil {
		kcpConn.Close()
		pc.Close()
		return nil, err
	}
	return &Client{sess: sess, pc: pc}, nil
}

// Open открывает поток к dest ("host:port") через covert-канал.
func (c *Client) Open(dest string) (net.Conn, error) {
	stream, err := c.sess.OpenStream()
	if err != nil {
		return nil, err
	}
	if err := writeConnectHeaderStr(stream, dest); err != nil {
		stream.Close()
		return nil, err
	}
	return stream, nil
}

func (c *Client) Close() error {
	if c.sess != nil {
		c.sess.Close()
	}
	if c.pc != nil {
		c.pc.Close()
	}
	return nil
}

func writeConnectHeaderStr(stream net.Conn, addr string) error {
	if len(addr) == 0 || len(addr) > 512 {
		return fmt.Errorf("covert: bad destination length")
	}
	buf := make([]byte, 2+len(addr))
	binary.BigEndian.PutUint16(buf[:2], uint16(len(addr)))
	copy(buf[2:], addr)
	_, err := stream.Write(buf)
	return err
}
