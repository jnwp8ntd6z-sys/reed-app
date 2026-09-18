// covert-soak — держит одну загрузку через covert >2 мин и печатает скорость посекундно,
// ловит стволы (0 байт/с). Нужен, чтобы проверить make-before-break через ротацию сессий.
package main

import (
	"bufio"
	"context"
	"fmt"
	"net/http"
	"os"
	"sync/atomic"
	"time"

	"github.com/reedvpn/singbox-mobile/covert"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: covert-soak <public_url>")
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
	defer cancel()
	c, err := covert.Dial(ctx, os.Args[1])
	if err != nil {
		fmt.Println("DIAL ERR:", err)
		os.Exit(1)
	}
	defer c.Close()
	fmt.Println("PAIRED, качаю 100MB...")
	st, err := c.Open("speedtest.tele2.net:80")
	if err != nil {
		fmt.Println("open err", err)
		os.Exit(1)
	}
	st.SetDeadline(time.Now().Add(175 * time.Second))
	fmt.Fprintf(st, "GET /100MB.zip HTTP/1.1\r\nHost: speedtest.tele2.net\r\nUser-Agent: soak\r\nConnection: close\r\n\r\n")
	br := bufio.NewReader(st)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		fmt.Println("READ ERR", err)
		os.Exit(1)
	}
	var total int64
	done := make(chan struct{})
	go func() {
		last := int64(0)
		for {
			select {
			case <-done:
				return
			case <-time.After(time.Second):
				t := atomic.LoadInt64(&total)
				d := t - last
				last = t
				stall := ""
				if d == 0 {
					stall = "  <<< STALL (0 байт/с)"
				}
				fmt.Printf("%s  total=%dKB  +%.0fKB/s%s\n", time.Now().Format("15:04:05"), t/1024, float64(d)/1024, stall)
			}
		}
	}()
	buf := make([]byte, 65536)
	for {
		n, err := resp.Body.Read(buf)
		atomic.AddInt64(&total, int64(n))
		if err != nil {
			fmt.Println("read end:", err, "total", atomic.LoadInt64(&total)/1024, "KB")
			break
		}
		if atomic.LoadInt64(&total) > 100*1024*1024 {
			break
		}
	}
	close(done)
	st.Close()
}
