// covert-client-test — харнес для проверки covert-канала со стороны клиента.
//
// Поднимает covert-клиент (тот же стек, что в приложении: VolgaPacketConn→KCP→smux),
// открывает поток к target и шлёт простой HTTP GET, печатает статус + тайминги.
// Каждый запуск = новая volga-сессия (новый userId + новый KCP conv), поэтому повтор
// запусков против одного долгоживущего covert-exit проверяет ПЕРЕЖИВАНИЕ реконнекта.
//
//	covert-client-test <public_url> [host:port]
package main

import (
	"bufio"
	"context"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/reedvpn/singbox-mobile/covert"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: covert-client-test <public_url> [host:port]")
		os.Exit(2)
	}
	pub := os.Args[1]
	target := "ifconfig.me:80"
	if len(os.Args) > 2 {
		target = os.Args[2]
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	t0 := time.Now()
	c, err := covert.Dial(ctx, pub)
	if err != nil {
		fmt.Println("DIAL ERR:", err)
		os.Exit(1)
	}
	defer c.Close()
	fmt.Printf("paired in %s\n", time.Since(t0))
	stream, err := c.Open(target)
	if err != nil {
		fmt.Println("OPEN ERR:", err)
		os.Exit(1)
	}
	defer stream.Close()
	req := "GET / HTTP/1.1\r\nHost: ifconfig.me\r\nUser-Agent: covert-test\r\nAccept: */*\r\nConnection: close\r\n\r\n"
	stream.SetDeadline(time.Now().Add(30 * time.Second))
	if _, err := stream.Write([]byte(req)); err != nil {
		fmt.Println("WRITE ERR:", err)
		os.Exit(1)
	}
	br := bufio.NewReader(stream)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		fmt.Println("READ ERR:", err)
		os.Exit(1)
	}
	buf := make([]byte, 256)
	n, _ := br.Read(buf)
	fmt.Printf("HTTP %s | body=%q | total %s\n", resp.Status, string(buf[:n]), time.Since(t0))
}
