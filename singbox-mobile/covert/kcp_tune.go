package covert

import (
	"log"
	"unsafe"

	kcp "github.com/xtaci/kcp-go/v5"
)

// Яндекс отдаёт cursor с задержкой в сотни миллисекунд. У kcp-go в режиме
// nodelay нижняя граница RTO — 30 мс, поля наружу нет. Повтор до прихода ACK
// забивает канал полными кадрами ещё много секунд после того, как данные уже
// доехали, и Яндекс рвёт сессию кодом 4007. Держим повтор не чаще двух секунд.
// Раскладка сверена с kcp-go v5.6.72 (64-бит): *KCP по смещению 24, rx_minrto
// по смещению 44. Если константа на месте не 30, поле не трогаем.
const kcpMinRTO = 2000

func tuneKCP(sess *kcp.UDPSession) {
	// Интервал опроса 30 мс, чтобы ACK не ждал. Быстрый повтор выключен:
	// его роль берёт на себя редкий RTO.
	sess.SetNoDelay(1, 30, 0, 1)
	sess.SetACKNoDelay(true)
	if !setKCPMinRTO(sess, kcpMinRTO) {
		log.Printf("[covert] kcp min-rto layout mismatch, left default")
	}
}

// tuneKCPFor на Cups ставит повтор чаще, чем на Яндексе. Комната выбрасывает
// батч и не наказывает за повтор, а 2 с пола RTO длиннее, чем телефон держит
// первое соединение. На Яндексе пол остаётся 2 с: частый повтор ловит 4004.
func tuneKCPFor(sess *kcp.UDPSession, kind string) {
	if kind != "cups" {
		tuneKCP(sess)
		return
	}
	sess.SetNoDelay(1, 20, 2, 1)
	sess.SetACKNoDelay(true)
	if !setKCPMinRTO(sess, 200) {
		log.Printf("[covert] kcp min-rto layout mismatch, left default")
	}
}

func setKCPMinRTO(sess *kcp.UDPSession, ms uint32) bool {
	base := unsafe.Pointer(sess)
	kptr := *(*unsafe.Pointer)(unsafe.Add(base, 24))
	if kptr == nil {
		return false
	}
	minPtr := (*uint32)(unsafe.Add(kptr, 44))
	cur := *minPtr
	if cur != 30 && cur != 100 {
		return false
	}
	*minPtr = ms
	rtoPtr := (*uint32)(unsafe.Add(kptr, 40))
	if *rtoPtr < ms {
		*rtoPtr = ms
	}
	return true
}
