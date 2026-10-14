#!/bin/sh
# Ядро Reed для iOS: sing-box (+ наш covert-транспорт) и olcRTC в ОДНОЙ XCFramework (одна go-библиотека:
# две отдельные gomobile-XCFramework конфликтуют рантаймом). Вызывается pre-build скриптом PacketTunnel.
# Команды go те же, что раньше собирали ядро через gradle — меняется только имя модуля (ReedCore).
set -e
export PATH="$HOME/go/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

IOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$IOS_DIR/../.." && pwd)"
CORE_SRC="${SINGBOX_REPO:-$REPO_ROOT/singbox-mobile}"
OUT="$IOS_DIR/Core/ReedCore.xcframework"

if [ -d "$OUT" ] && [ -z "$REED_CORE_REBUILD" ]; then
  echo "ReedCore.xcframework уже собран: $OUT"
  exit 0
fi
rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
cd "$CORE_SRC"

# olcRTC — из нашего форка (local-replace на $OLCRTC_REPO, пин OLCRTC_REF в CI); без него — upstream master.
if [ -n "$OLCRTC_REPO" ]; then
  go mod edit -replace github.com/openlibrecommunity/olcrtc="$OLCRTC_REPO"
else
  go get github.com/openlibrecommunity/olcrtc@master
fi
go get golang.org/x/mobile/bind@6129f5bee9d5
go mod tidy
# with_utls — обязателен для REALITY-клиента sing-box; with_gvisor — tun-inbound в Network Extension.
gomobile bind -target=ios -tags with_utls,with_gvisor -ldflags "-s -w -checklinkname=0" \
  -o "$OUT" github.com/openlibrecommunity/olcrtc/mobile .
echo "ReedCore.xcframework готов: $OUT"
