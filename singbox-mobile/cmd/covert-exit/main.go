// covert-exit — серверная сторона covert-канала (профиль «ЛТЕ»).
//
// Держит covert-канал Яндекс.volga на публичной ссылке-документе и релеит потоки
// клиента до их назначения обычным net.Dial (без gvisor/raw-socket). В боевой
// схеме клиент = наше приложение (sing-box outbound type:covert), назначение =
// наш прод VLESS-инбаунд, внутри трубы едет штатный VLESS с авторизацией по uuid.
//
//	covert-exit <public_url>
//	  public_url — публичная ссылка на Яндекс-документ (edit-доступ всем), тот же,
//	               что в профиле приложения (covert-out.public_url).
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/reedvpn/singbox-mobile/covert"
)

func main() {
	pub := os.Getenv("COVERT_PUBLIC_URL")
	if len(os.Args) > 1 {
		pub = os.Args[1]
	}
	if pub == "" {
		log.Fatal("covert-exit: public_url required (arg or COVERT_PUBLIC_URL)")
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		ch := make(chan os.Signal, 1)
		signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
		<-ch
		log.Println("covert-exit: signal, shutting down")
		cancel()
	}()
	log.Printf("covert-exit: serving on %s", pub)
	for {
		if err := covert.RunExit(ctx, pub, log.Printf); err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("covert-exit: RunExit ended: %v — repairing in 5s", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}
