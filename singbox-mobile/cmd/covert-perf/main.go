// covert-perf — замер covert-канала: пара + 3 мелких запроса (латентность) + 1МБ (пропускная).
//
//	covert-perf <public_url>
package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/reedvpn/singbox-mobile/covert"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: covert-perf <public_url>")
		os.Exit(2)
	}
	pub := os.Args[1]
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	t0 := time.Now()
	c, err := covert.Dial(ctx, pub)
	if err != nil {
		fmt.Println("DIAL ERR:", err)
		os.Exit(1)
	}
	defer c.Close()
	fmt.Printf("PAIR: %s\n", time.Since(t0))

	for i := 1; i <= 3; i++ {
		s := time.Now()
		st, err := c.Open("ifconfig.me:80")
		if err != nil {
			fmt.Println("open err", err)
			continue
		}
		st.SetDeadline(time.Now().Add(30 * time.Second))
		fmt.Fprintf(st, "GET / HTTP/1.1\r\nHost: ifconfig.me\r\nUser-Agent: perf\r\nConnection: close\r\n\r\n")
		br := bufio.NewReader(st)
		resp, err := http.ReadResponse(br, nil)
		if err != nil {
			fmt.Println("small#", i, "READ ERR", err)
			st.Close()
			continue
		}
		io.Copy(io.Discard, resp.Body)
		st.Close()
		fmt.Printf("SMALL#%d: %s (HTTP %s)\n", i, time.Since(s), resp.Status)
	}

	s := time.Now()
	st, err := c.Open("speedtest.tele2.net:80")
	if err != nil {
		fmt.Println("big open err", err)
		os.Exit(1)
	}
	st.SetDeadline(time.Now().Add(90 * time.Second))
	fmt.Fprintf(st, "GET /1MB.zip HTTP/1.1\r\nHost: speedtest.tele2.net\r\nUser-Agent: perf\r\nConnection: close\r\n\r\n")
	br := bufio.NewReader(st)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		fmt.Println("BIG READ ERR", err)
		os.Exit(1)
	}
	n, _ := io.Copy(io.Discard, resp.Body)
	st.Close()
	d := time.Since(s)
	fmt.Printf("BIG: %d bytes in %s = %.1f KB/s (HTTP %s)\n", n, d, float64(n)/1024.0/d.Seconds(), resp.Status)
}
