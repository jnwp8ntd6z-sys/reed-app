//go:build tools

// Держит golang.org/x/mobile в графе зависимостей модуля, иначе `gomobile bind`
// ругается «missing golang.org/x/mobile dependency». Файл собирается только под тегом
// tools (в обычной сборке игнорируется), но `go mod tidy` видит импорт и сохраняет dep.
package singboxmobile

import (
	_ "golang.org/x/mobile/bind"
)
